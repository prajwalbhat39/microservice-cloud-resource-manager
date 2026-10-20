package com.crm.resource.client;

import com.crm.resource.client.dto.ReserveQuotaRequestDto;
import com.crm.resource.client.dto.ReserveQuotaResponseDto;
import com.crm.resource.common.error.ApiException;
import com.crm.resource.common.error.BusinessRuleViolationException;
import com.crm.resource.common.error.DownstreamUnavailableException;
import com.crm.resource.common.error.ErrorCode;
import com.crm.resource.common.error.NotFoundException;
import com.crm.resource.common.error.StateConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The REAL peer in U1 (Quota is in the same unit, running on :8081 by default). One class per
 * peer (not a generic PeerHttpClient) because each peer has its own timeouts and its own retry
 * predicate (nfr-design.md 8.2) — Quota retries connect failures only, since BR1's atomic UPDATE
 * is final the instant it returns and a read timeout is ambiguous (the reservation may have
 * been taken).
 *
 * <p>Error translation (functional-design.md 9.3, nfr-design.md 2.4):
 * <ul>
 *   <li>404 TENANT_NOT_FOUND -&gt; {@link NotFoundException}, so the orchestrator propagates
 *       Quota's answer rather than inventing one (A12)
 *   <li>422 QUOTA_EXCEEDED / TIER_FORBIDS_RESOURCE_TYPE -&gt;
 *       {@link BusinessRuleViolationException} carrying the SAME code, the same detail AND the
 *       peer's extension members (notably {@code exhausted[]}, which AC-1.3 is read from)
 *   <li>409 QUOTA_RESERVATION_ALREADY_RELEASED -&gt; {@link StateConflictException}
 *   <li>timeout / connection failure / 503 after retries -&gt;
 *       {@link DownstreamUnavailableException} with DOWNSTREAM_UNAVAILABLE and no extensions
 *       (there is nothing authoritative to carry)
 * </ul>
 */
@Component
public class QuotaClient {

    private static final Logger log = LoggerFactory.getLogger(QuotaClient.class);

    /** The members ProblemDetailsFactory re-derives locally; never carried over from a peer. */
    private static final Set<String> NON_PROPAGATED = Set.of(
            "type", "title", "status", "detail", "instance", "code", "correlationId", "timestamp");

    private final RestClient restClient;
    private final PeerRetry retry;

    public QuotaClient(@Qualifier("quotaRestClientBuilder") RestClient.Builder quotaRestClientBuilder,
                       PeerRetry retry) {
        // The builder already carries peers.quota's base URL, its timeouts and the
        // correlation-id request initializer (RestClientConfig.peerClient).
        this.restClient = quotaRestClientBuilder.build();
        this.retry = retry;
    }

    /** POST /api/v1/quota/reservations. Synchronous; the Challenge 1 decision happens here. */
    public ReserveQuotaResponseDto reserve(String tenantId, UUID resourceId, String type, int cpu, int ramGb) {
        ReserveQuotaRequestDto body = new ReserveQuotaRequestDto(
                tenantId, resourceId.toString(), type, cpu, ramGb);

        return retry.call(ErrorCode.DOWNSTREAM_UNAVAILABLE, "quota reserve", false, () ->
                restClient.post()
                        .uri("/api/v1/quota/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .exchange((request, response) -> {
                            if (response.getStatusCode().is2xxSuccessful()) {
                                return response.bodyTo(ReserveQuotaResponseDto.class);
                            }
                            throw translate(response.getStatusCode().value(),
                                    response.bodyTo(PeerProblem.class), "reserve");
                        }));
    }

    /** POST /api/v1/quota/reservations/{resourceId}/release. Called from compensation and termination. */
    public void release(String resourceId) {
        retry.callVoid(ErrorCode.DOWNSTREAM_UNAVAILABLE, "quota release", true, () ->
                restClient.post()
                        .uri("/api/v1/quota/reservations/{resourceId}/release", resourceId)
                        .exchange((request, response) -> {
                            if (response.getStatusCode().is2xxSuccessful()) {
                                return null;
                            }
                            // A release for a resource Quota has no ledger row for is not a
                            // failure worth unwinding: nothing is held, so the goal is met.
                            if (response.getStatusCode().value() == 404) {
                                log.warn("quota.release.not-found resourceId={}", resourceId);
                                return null;
                            }
                            throw translate(response.getStatusCode().value(),
                                    response.bodyTo(PeerProblem.class), "release");
                        }));
    }

    /**
     * Turns Quota's problem body into the matching local exception, keeping its code, detail and
     * extension members. Anything that is not a recognised business rejection becomes a 503 with
     * the peer named and no extensions carried.
     */
    private ApiException translate(int status, PeerProblem problem, String operation) {
        ErrorCode code = problem == null ? null : parseCode(problem.code());
        String detail = problem == null || problem.detail() == null
                ? "Quota " + operation + " rejected with status " + status
                : problem.detail();
        Map<String, Object> extensions = problem == null ? Map.of() : problem.extensions();

        if (status == 503) {
            // Re-thrown in the shape PeerRetry recognises, so a 503 is retried like any other.
            throw org.springframework.web.client.HttpServerErrorException.create(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "quota unavailable", null, null, null);
        }
        if (code == null || status >= 500) {
            return new DownstreamUnavailableException(ErrorCode.DOWNSTREAM_UNAVAILABLE,
                    "quota " + operation + " failed (" + status + ")");
        }
        return switch (code) {
            case TENANT_NOT_FOUND, QUOTA_RESERVATION_NOT_FOUND -> new NotFoundException(code, detail);
            case QUOTA_EXCEEDED, TIER_FORBIDS_RESOURCE_TYPE, INVALID_TIER ->
                    new BusinessRuleViolationException(code, detail, extensions);
            case QUOTA_RESERVATION_ALREADY_RELEASED -> new StateConflictException(code, detail, extensions);
            default -> new DownstreamUnavailableException(ErrorCode.DOWNSTREAM_UNAVAILABLE,
                    "quota " + operation + " rejected with " + code + " (" + status + ")");
        };
    }

    private static ErrorCode parseCode(String code) {
        if (code == null) {
            return null;
        }
        try {
            return ErrorCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            log.warn("quota.unknown-error-code code={}", code);
            return null;
        }
    }

    /**
     * A peer's RFC 9457 body: the named members plus every unknown member collected into
     * {@link #extensions()} so {@code exhausted[]} (and anything Quota adds later) propagates
     * without an orchestrator change (nfr-design.md 2.4).
     */
    static final class PeerProblem {
        private final Map<String, Object> all = new LinkedHashMap<>();

        @com.fasterxml.jackson.annotation.JsonAnySetter
        void put(String key, Object value) {
            all.put(key, value);
        }

        String code() {
            Object value = all.get("code");
            return value == null ? null : value.toString();
        }

        String detail() {
            Object value = all.get("detail");
            return value == null ? null : value.toString();
        }

        Map<String, Object> extensions() {
            Map<String, Object> extensions = new LinkedHashMap<>(all);
            NON_PROPAGATED.forEach(extensions::remove);
            return extensions;
        }
    }
}
