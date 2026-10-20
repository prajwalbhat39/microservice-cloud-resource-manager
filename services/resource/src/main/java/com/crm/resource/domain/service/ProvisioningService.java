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

/**
 * FR-13, FR-14: the SYNCHRONOUS half of provisioning. Quota is reserved HERE, before the 202, so
 * the Challenge 1 count is deterministic (AC-1.2). Everything after the 202 (provider, billing,
 * notify) is {@link ProvisioningSaga}'s job, dispatched only after this transaction commits.
 *
 * <p>========================= BUILDING BLOCKS ALREADY CREATED =========================
 * <ul>
 *   <li>{@code idempotencyService} — IdempotencyService.beginOrReplay(key, fingerprint,
 *       candidateReservationId) -&gt; IdempotencyDecision; .complete(key, status, body)
 *   <li>{@code quotaClient} — QuotaClient.reserve(tenantId, resourceId, type, cpu, ramGb) -&gt;
 *       ReserveQuotaResponseDto, throws ApiException subclasses on 404/422, or
 *       DownstreamUnavailableException on an unreachable peer
 *   <li>{@code resourceRepository} — ResourceRepository (save/findById inherited)
 *   <li>{@code reservationRepository} — ReservationRepository (save/findById inherited)
 *   <li>{@code sagaDispatcher} — SagaDispatcher.afterCommit(ProvisioningTask) — runs the saga
 *       only after THIS transaction commits (never inside it)
 *   <li>RequestFingerprint.of(request) -&gt; String (implement RequestFingerprint first)
 *   <li>SimulateFailureDirective.normalise(headerValue) -&gt; SimulateFailureDirective
 *   <li>ProvisionAcceptedResponse.pending(reservationId, resourceId) — the frozen 202 body shape
 * </ul>
 * ===================================================================================
 */
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

    public ProvisioningService(IdempotencyService idempotencyService, QuotaClient quotaClient,
                               ResourceRepository resourceRepository,
                               ReservationRepository reservationRepository,
                               SagaDispatcher sagaDispatcher, Clock clock, ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.quotaClient = quotaClient;
        this.resourceRepository = resourceRepository;
        this.reservationRepository = reservationRepository;
        this.sagaDispatcher = sagaDispatcher;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * Follow functional-design.md 9.3's five steps EXACTLY, in order — the order is part of the
     * contract (a request that fails steps 1-2 must never leave a record behind):
     * <ol>
     *   <li>(a) fingerprint the request; mint resourceId and reservationId; call
     *       {@code idempotencyService.beginOrReplay(...)}. If {@code !decision.proceed()},
     *       return {@code ProvisionOutcome.of(decision)} immediately (replay or in-flight)
     *   <li>(b) call {@code quotaClient.reserve(...)}. On a BUSINESS rejection (404/422 from
     *       Quota): call {@code idempotencyService.complete(key, rejectedStatus, rejectedBody)}
     *       then return {@code ProvisionOutcome.rejected(...)} — the TRANSACTION COMMITS here,
     *       so the key now owns this answer permanently. On an INFRASTRUCTURE failure
     *       (DownstreamUnavailableException): let it propagate uncaught — the whole transaction
     *       rolls back, the idempotency record disappears, the key becomes reusable
     *   <li>(c) save the Resource (status=REQUESTED) and Reservation (status=PENDING) rows —
     *       ONLY after quota is held (FR-13: no resource row on a rejected request)
     *   <li>(d) build {@code ProvisionAcceptedResponse.pending(reservationId, resourceId)},
     *       call {@code idempotencyService.complete(key, 202, json-of-that-body)} so every
     *       future replay is byte-identical (AC-2.1)
     *   <li>(e) {@code sagaDispatcher.afterCommit(new ProvisioningTask(...))} — reads
     *       {@code MDC.get("correlationId")} for the task's correlationId field
     * </ol>
     * {@code durationSeconds} becomes {@code expires_at = created_at + durationSeconds} at
     * insert (FR-23); absent duration leaves the column null.
     */
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

        // (b) Quota reserved SYNCHRONOUSLY, before the response. The Challenge 1 count is decided here.
        try {
            ReserveQuotaResponseDto quota = quotaClient.reserve(
                    request.tenantId(), resourceId, request.type(), request.cpu(), request.ramGb());
            log.info("quota.reserved resourceId={} tenantId={} replay={}", resourceId, request.tenantId(), quota.replay());
        } catch (DownstreamUnavailableException unavailable) {
            // Infrastructure failure: not an answer. Propagate so the whole transaction rolls back,
            // the idempotency record disappears and the key is reusable (assumption A3).
            throw unavailable;
        } catch (ApiException rejected) {
            // Business rejection (404 tenant / 422 quota / 422 BR8) from Quota. Freeze the answer on
            // the key; the transaction COMMITS so the key owns this answer.
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

    /**
     * Builds the problem document for a peer's business rejection through the one construction
     * point (ProblemDetailsFactory), keeping code/title/detail/extensions (nfr-design.md 2.4) and
     * pointing {@code instance} at this service's own path. The text is stored on the key and
     * later replayed verbatim.
     */
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
