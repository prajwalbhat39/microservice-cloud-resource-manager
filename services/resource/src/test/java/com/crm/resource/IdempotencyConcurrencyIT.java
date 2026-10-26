package com.crm.resource;

import com.crm.resource.persistence.repository.IdempotencyRecordRepository;
import com.crm.resource.persistence.repository.ReservationRepository;
import com.crm.resource.persistence.repository.ResourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/** Concurrent duplicate keys return one stored response and create one resource. */
class IdempotencyConcurrencyIT extends AbstractResourceIT {

    private static final int CONCURRENT_DUPLICATES = 10;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void tenConcurrentDuplicatesCreateOneResource() throws Exception {
        // Arrange: ONE Idempotency-Key, ONE body, ten threads held at a latch.
        String key = "concurrent-" + UUID.randomUUID();
        String tenantId = "tnt-std-" + UUID.randomUUID().toString().substring(0, 8);
        String body = provisionBody(tenantId, "VM", 4, 8);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_DUPLICATES);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < CONCURRENT_DUPLICATES; i++) {
                futures.add(pool.submit(() -> {
                    startGun.await();
                    return rest.exchange("/api/v1/resources", HttpMethod.POST, request(key, body), String.class);
                }));
            }
            startGun.countDown();   // released simultaneously: this is what makes it a race
            pool.shutdown();
            assertThat(pool.awaitTermination(90, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        List<ResponseEntity<String>> responses = futures.stream().map(future -> {
            try {
                return future.get();
            } catch (Exception e) {
                throw new IllegalStateException("A provisioning call did not complete", e);
            }
        }).toList();

        // Every caller gets a 202: a duplicate is answered, never rejected.
        assertThat(responses).allSatisfy(response ->
                assertThat(response.getStatusCode().value()).isEqualTo(202));

        // AC-2.3: all ten answers describe the SAME reservation.
        List<String> reservationIds = new ArrayList<>();
        List<String> resourceIds = new ArrayList<>();
        for (ResponseEntity<String> response : responses) {
            JsonNode json = objectMapper.readTree(response.getBody());
            reservationIds.add(json.path("reservationId").asText());
            if (!json.path("resourceId").isNull() && !json.path("resourceId").asText().isEmpty()) {
                resourceIds.add(json.path("resourceId").asText());
            }
        }
        assertThat(reservationIds).as("one reservation, ten answers").hasSize(CONCURRENT_DUPLICATES);
        assertThat(List.copyOf(new java.util.HashSet<>(reservationIds)))
                .as("every duplicate must point at the winner's reservation").hasSize(1);
        // A loser that observed IN_PROGRESS rather than COMPLETED has no resourceId to report
        // yet (decision-table step 6, rare in practice); every one that does report one must agree.
        assertThat(new java.util.HashSet<>(resourceIds))
                .as("no duplicate may invent a second resource").hasSize(1);

        // AC-2.2: the work happened exactly once, in the database. Scoped to this run's key and
        // tenant rather than a global row count, because the ITs share one database.
        assertThat(idempotencyRecordRepository.findById(key)).isPresent();
        assertThat(resourceRepository.findByTenantId(tenantId)).hasSize(1);
        assertThat(reservationRepository.findByTenantId(tenantId)).hasSize(1);

        // ...and at the peer: exactly ONE quota reservation was taken, not ten. This is the
        // assertion that would fail if quota were reserved before the idempotency insert.
        peers.verify(1, WireMock.postRequestedFor(urlPathMatching("/api/v1/quota/reservations"))
                .withRequestBody(WireMock.matchingJsonPath("$.tenantId", WireMock.equalTo(tenantId))));

        // The saga ran once too (the resource ends ACTIVE, the only path to which is BR4's).
        awaitUntil(Duration.ofSeconds(20), "the saga to finish", () ->
                resourceRepository.findByTenantIdAndStatus(tenantId, "ACTIVE").size() == 1);
        peers.verify(1, WireMock.postRequestedFor(urlPathMatching("/api/v1/provider/instances"))
                .withRequestBody(WireMock.matchingJsonPath("$.clientToken", WireMock.equalTo("ct-" + resourceIds.getFirst()))));
        peers.verify(1, WireMock.postRequestedFor(urlPathMatching("/api/v1/billing/meters"))
                .withRequestBody(WireMock.matchingJsonPath("$.resourceId", WireMock.equalTo(resourceIds.getFirst()))));
    }

    @Test
    void sequentialRetryReplaysTheStoredAnswerByteForByte() throws Exception {
        // The ordinary client retry: same key, same body, one after the other.
        String key = "sequential-" + UUID.randomUUID();
        String tenantId = "tnt-std-" + UUID.randomUUID().toString().substring(0, 8);
        String body = provisionBody(tenantId, "VM", 4, 8);

        ResponseEntity<String> first = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(key, body), String.class);
        ResponseEntity<String> replay = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(key, body), String.class);

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(replay.getStatusCode().value()).isEqualTo(202);
        // AC-2.1: identical bytes, so the Challenge 2 script's body comparison holds.
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        // AC-2.5: and the replay announces itself without anyone reading logs.
        assertThat(first.getHeaders().getFirst("Idempotency-Replayed")).isNull();
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");

        // No second reservation, no second resource.
        assertThat(resourceRepository.findByTenantId(tenantId)).hasSize(1);
        peers.verify(1, WireMock.postRequestedFor(urlPathMatching("/api/v1/quota/reservations"))
                .withRequestBody(WireMock.matchingJsonPath("$.tenantId", WireMock.equalTo(tenantId))));
    }

    @Test
    void aMissingKeyIsRejectedBeforeAnythingIsWritten() {
        // Step 1 of the decision table: no key, no record, no quota call. A client must not be
        // able to burn a key — or consume capacity — on a malformed request.
        String tenantId = "tnt-std-" + UUID.randomUUID().toString().substring(0, 8);

        ResponseEntity<String> response = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(null, provisionBody(tenantId, "VM", 4, 8)), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(resourceRepository.findByTenantId(tenantId)).isEmpty();
        peers.verify(0, WireMock.postRequestedFor(urlPathMatching("/api/v1/quota/reservations")));
    }

    @Test
    void anInvalidBodyIsRejectedBeforeAnythingIsWritten() {
        // Step 2: validation runs before the idempotency insert, for the same reason.
        String key = "invalid-" + UUID.randomUUID();

        ResponseEntity<String> response = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(key, "{\"tenantId\":\"\",\"type\":\"VM\",\"cpu\":0,\"ramGb\":8}"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("VALIDATION_FAILED").contains("errors");
        assertThat(idempotencyRecordRepository.findById(key)).isEmpty();
        peers.verify(0, WireMock.postRequestedFor(urlPathMatching("/api/v1/quota/reservations")));
    }
}
