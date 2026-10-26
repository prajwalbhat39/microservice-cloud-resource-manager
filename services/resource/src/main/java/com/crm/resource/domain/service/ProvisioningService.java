package com.crm.resource.domain.service;

import com.crm.resource.api.dto.ProvisionResourceRequest;
import com.crm.resource.client.QuotaClient;
import com.crm.resource.client.dto.ReserveQuotaResponseDto;
import com.crm.resource.common.error.ApiException;
import com.crm.resource.common.error.DownstreamUnavailableException;
import com.crm.resource.api.dto.ProvisionAcceptedResponse;
import com.crm.resource.common.error.ProblemDetailsFactory;
import com.crm.resource.domain.RequestFingerprint;
import com.crm.resource.domain.ReservationStatus;
import com.crm.resource.domain.ResourceStatus;
import com.crm.resource.domain.SimulateFailureDirective;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import com.crm.resource.persistence.entity.Reservation;
import com.crm.resource.persistence.entity.Resource;
import com.crm.resource.persistence.repository.ReservationRepository;
import com.crm.resource.persistence.repository.ResourceRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Reserve quota before accepting; dispatch persisted provisioning work after commit. */
@Service
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);

    private final IdempotencyService idempotencyService;
    private final QuotaClient quotaClient;
    private final ResourceRepository resourceRepository;
    private final ReservationRepository reservationRepository;
    private final SagaDispatcher sagaDispatcher;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ResourceWork work;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ProvisioningService(IdempotencyService idempotencyService, QuotaClient quotaClient,
                               ResourceRepository resourceRepository,
                               ReservationRepository reservationRepository,
                               SagaDispatcher sagaDispatcher, Clock clock, ObjectMapper objectMapper, ResourceWork work, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.work = work; this.jdbc = jdbc;
        this.idempotencyService = idempotencyService;
        this.quotaClient = quotaClient;
        this.resourceRepository = resourceRepository;
        this.reservationRepository = reservationRepository;
        this.sagaDispatcher = sagaDispatcher;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProvisionOutcome provision(String idempotencyKey, ProvisionResourceRequest request,
                                      String simulateFailureHeader) {
        String fingerprint = RequestFingerprint.of(request);
        UUID resourceId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        // (a) Idempotency gate. May return a replay / in-flight answer, or throw 422 on a mismatch.
        IdempotencyDecision decision = idempotencyService.beginOrReplay(idempotencyKey, fingerprint, reservationId);
        if (!decision.proceed()) {
            return ProvisionOutcome.of(decision);
        }

        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> { }, resourceId.toString());
        work.rememberQuota(resourceId);

        // (b) Quota reserved SYNCHRONOUSLY, before the response. The Challenge 1 count is decided here.
        try {
            ReserveQuotaResponseDto quota = quotaClient.reserve(
                    request.tenantId().trim(), resourceId, request.type().trim().toUpperCase(Locale.ROOT), request.cpu(), request.ramGb());
            log.info("quota.reserved resourceId={} tenantId={} replay={}", resourceId, request.tenantId(), quota.replay());
        } catch (DownstreamUnavailableException unavailable) {
            // Infrastructure failure: not an answer. Propagate so the whole transaction rolls back,
            // the idempotency record disappears and the key is reusable (assumption A3).
            throw unavailable;
        } catch (ApiException rejected) {
            // Business rejection (404 tenant / 422 quota / 422 BR8) from Quota. Freeze the answer on
            // the key; the transaction COMMITS so the key owns this answer.
            work.done(resourceId);
            int status = rejected.code().status();
            String body = rejectionBody(rejected, status);
            idempotencyService.complete(idempotencyKey, status, body);
            return ProvisionOutcome.rejected(rejected, status, body);
        }

        // (c) Rows exist only after quota is held (FR-13: no resource on a rejected request).
        Instant now = clock.instant();
        Instant expiresAt = request.durationSeconds() == null ? null : now.plusSeconds(request.durationSeconds());
        SimulateFailureDirective simulate = SimulateFailureDirective.normalise(simulateFailureHeader);

        resourceRepository.save(new Resource(resourceId, request.tenantId().trim(),
                request.type().trim().toUpperCase(Locale.ROOT), request.cpu(), request.ramGb(),
                ResourceStatus.REQUESTED.name(), expiresAt));
        Reservation reservation = new Reservation(reservationId, request.tenantId().trim(), resourceId,
                ReservationStatus.PENDING.name(), simulate.raw());
        reservation.setUpdatedAt(now);   // NOT NULL; the DEFAULT now() only applies when the column is omitted
        reservationRepository.save(reservation);

        // (d) Freeze the 202 body on the key, so every replay is byte-identical (AC-2.1).
        ProvisionAcceptedResponse body = ProvisionAcceptedResponse.pending(reservationId, resourceId);
        idempotencyService.complete(idempotencyKey, 202, toJson(body));

        // (e) Hand the saga to the background pool AFTER COMMIT, never inside the transaction.
        sagaDispatcher.afterCommit(new ProvisioningTask(reservationId, resourceId, request.tenantId().trim(),
                request.type().trim().toUpperCase(Locale.ROOT), request.cpu(), request.ramGb(),
                simulate, MDC.get("correlationId")));

        return ProvisionOutcome.accepted(body);
    }

    /** Store the peer business rejection for byte-identical replay. */
    private String rejectionBody(ApiException rejected, int status) {
        ProblemDetail pd = ProblemDetailsFactory.problem(rejected.code(), status, rejected.getMessage(),
                null, rejected.extensions());
        pd.setInstance(URI.create("/api/v1/resources"));
        return toJson(pd);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + value.getClass().getSimpleName(), e);
        }
    }
}
