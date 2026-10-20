package com.crm.resource;

import com.crm.resource.persistence.entity.IdempotencyRecord;
import com.crm.resource.persistence.repository.IdempotencyRecordRepository;
import com.crm.resource.persistence.repository.ResourceRepository;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * BR3 database-backed test (Testcontainers): the fingerprint-mismatch path, reached only AFTER
 * the savepoint rollback has restored the transaction — so this also proves the recovery read
 * works on a real PostgreSQL connection that a constraint violation has just aborted
 * (functional-design.md 9.2, step 4).
 */
class IdempotencyPayloadMismatchIT extends AbstractResourceIT {

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Test
    void reusedKeyAlteredPayloadReturns422() {
        // Arrange: key K owns a COMPLETED record for fingerprint F1 (4 CPU).
        String key = "mismatch-" + UUID.randomUUID();
        String tenantId = "tnt-std-" + UUID.randomUUID().toString().substring(0, 8);

        ResponseEntity<String> first = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(key, provisionBody(tenantId, "VM", 4, 8)), String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(202);

        IdempotencyRecord original = idempotencyRecordRepository.findById(key).orElseThrow();
        String originalFingerprint = original.getRequestFingerprint();
        String originalBody = original.getResponseBody();
        UUID originalReservationId = original.getReservationId();

        // Act: the same key with a DIFFERENT body (8 CPU -> fingerprint F2).
        ResponseEntity<String> mismatch = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(key, provisionBody(tenantId, "VM", 8, 8)), String.class);

        // Assert: 422 naming the key. 422 and not 409, because the frozen contract and NFR-9
        // fixed 422 as "business-rule violation" and 409 is already taken by state conflicts.
        assertThat(mismatch.getStatusCode().value()).isEqualTo(422);
        assertThat(mismatch.getHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
        assertThat(mismatch.getBody())
                .contains("IDEMPOTENCY_PAYLOAD_MISMATCH")
                .contains(key);

        // The ORIGINAL record is untouched: a mismatch must have no effect at all, or a client
        // could overwrite a good answer with a typo.
        IdempotencyRecord after = idempotencyRecordRepository.findById(key).orElseThrow();
        assertThat(after.getRequestFingerprint()).isEqualTo(originalFingerprint);
        assertThat(after.getResponseBody()).isEqualTo(originalBody);
        assertThat(after.getReservationId()).isEqualTo(originalReservationId);
        assertThat(after.getState()).isEqualTo("COMPLETED");

        // And no second resource, and no second quota reservation, was created.
        assertThat(resourceRepository.findByTenantId(tenantId)).hasSize(1);
        peers.verify(1, WireMock.postRequestedFor(urlPathMatching("/api/v1/quota/reservations")));
    }

    @Test
    void mismatchIsDetectedWhileTheOriginalIsStillInProgress() {
        // The same rule applies against an IN_PROGRESS record, not just a COMPLETED one: the
        // fingerprint comparison comes BEFORE the state check in the decision table.
        String key = "mismatch-inflight-" + UUID.randomUUID();
        String tenantId = "tnt-std-" + UUID.randomUUID().toString().substring(0, 8);

        rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(key, provisionBody(tenantId, "CONTAINER", 2, 4)), String.class);

        ResponseEntity<String> mismatch = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(key, provisionBody(tenantId, "CONTAINER", 2, 16)), String.class);

        assertThat(mismatch.getStatusCode().value()).isEqualTo(422);
        assertThat(mismatch.getBody()).contains("IDEMPOTENCY_PAYLOAD_MISMATCH");
    }

    @Test
    void headerDifferencesAreNotPayloadDifferences() {
        // Assumption A4: the fingerprint covers the request BODY only. The same key with the
        // same body but a different X-Simulate-Failure header replays the original answer
        // rather than being treated as a mismatch — and does NOT re-inject the failure.
        String key = "headers-" + UUID.randomUUID();
        String tenantId = "tnt-std-" + UUID.randomUUID().toString().substring(0, 8);
        String body = provisionBody(tenantId, "VM", 4, 8);

        ResponseEntity<String> first = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(key, body), String.class);

        ResponseEntity<String> second = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(key, body, "X-Simulate-Failure", "billing:always-fail"), String.class);

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(second.getStatusCode().value()).isEqualTo(202);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    }

    @Test
    void aPeerRejectionIsFrozenOnTheKeyAndReplayedVerbatim() {
        // functional-design.md 9.3: a BUSINESS rejection commits, so the key owns that answer.
        // The replay must be byte-identical, including the ORIGINAL correlationId (nfr 2.5).
        peers.stubFor(post(urlPathMatching("/api/v1/quota/reservations"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("""
                                {"type":"https://crm.example/errors/quota-exceeded",
                                 "title":"Quota exceeded","status":422,
                                 "detail":"Requested 4 CPU exceeds remaining 0 CPU for tenant tnt-x",
                                 "instance":"/api/v1/quota/reservations","code":"QUOTA_EXCEEDED",
                                 "exhausted":["CPU"],"correlationId":"quota-corr","timestamp":"2026-10-14T09:12:05Z"}""")));

        String key = "rejected-" + UUID.randomUUID();
        String tenantId = "tnt-std-" + UUID.randomUUID().toString().substring(0, 8);
        String body = provisionBody(tenantId, "VM", 4, 8);

        ResponseEntity<String> first = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(key, body), String.class);
        ResponseEntity<String> replay = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(key, body), String.class);

        assertThat(first.getStatusCode().value()).isEqualTo(422);
        // A12/nfr-design.md 2.4: Quota's code AND its extension members propagate unchanged —
        // AC-1.3 is read from the ORCHESTRATOR's 422, not from Quota's.
        assertThat(first.getBody()).contains("QUOTA_EXCEEDED").contains("exhausted").contains("CPU");
        // instance points at the path the client actually called.
        assertThat(first.getBody()).contains("/api/v1/resources");

        assertThat(replay.getStatusCode().value()).isEqualTo(422);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");

        // FR-13: a rejected request leaves no resource behind, but the key IS consumed, because
        // the rejection is a final answer about this payload.
        assertThat(resourceRepository.findByTenantId(tenantId)).isEmpty();
        assertThat(idempotencyRecordRepository.findById(key)).isPresent();
    }

    @Test
    void anUnreachablePeerLeavesTheKeyReusable() {
        // The mirror image (assumption A3): a 503 is NOT an answer, so the whole transaction
        // rolls back — no idempotency record — and the client may retry the same key.
        peers.stubFor(post(urlPathMatching("/api/v1/quota/reservations"))
                .willReturn(aResponse().withStatus(503)));

        String key = "unreachable-" + UUID.randomUUID();
        String tenantId = "tnt-std-" + UUID.randomUUID().toString().substring(0, 8);
        String body = provisionBody(tenantId, "VM", 4, 8);

        ResponseEntity<String> failed = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(key, body), String.class);

        assertThat(failed.getStatusCode().value()).isEqualTo(503);
        assertThat(failed.getBody()).contains("DOWNSTREAM_UNAVAILABLE");
        assertThat(idempotencyRecordRepository.findById(key)).as("the key must be reusable").isEmpty();
        assertThat(resourceRepository.findByTenantId(tenantId)).isEmpty();

        // Proof that it really is reusable: the same key succeeds once Quota recovers.
        stubAllPeersHealthy();
        ResponseEntity<String> retry = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(key, body), String.class);
        assertThat(retry.getStatusCode().value()).isEqualTo(202);
        assertThat(retry.getHeaders().getFirst("Idempotency-Replayed")).isNull();
    }
}
