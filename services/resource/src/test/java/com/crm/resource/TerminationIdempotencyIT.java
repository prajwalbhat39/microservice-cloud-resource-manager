package com.crm.resource;

import com.crm.resource.persistence.entity.Resource;
import com.crm.resource.persistence.repository.ResourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
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

/** Concurrent termination requests must produce one state transition and teardown. */
class TerminationIdempotencyIT extends AbstractResourceIT {

    private static final int CONCURRENT_CALLERS = 10;

    @Autowired
    private ResourceRepository resourceRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Provisions a resource and waits for the saga to drive it to ACTIVE (the only terminable state). */
    private UUID activeResource(String tenantId) {
        ResponseEntity<String> accepted = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request("terminate-" + UUID.randomUUID(), provisionBody(tenantId, "VM", 4, 8)), String.class);
        assertThat(accepted.getStatusCode().value()).isEqualTo(202);

        awaitUntil(Duration.ofSeconds(20), "the resource to become ACTIVE", () ->
                resourceRepository.findByTenantIdAndStatus(tenantId, "ACTIVE").size() == 1);
        return resourceRepository.findByTenantIdAndStatus(tenantId, "ACTIVE").getFirst().getResourceId();
    }

    @Test
    void repeatedTerminationHasNoFurtherEffect() throws Exception {
        // Arrange: one ACTIVE resource.
        String tenantId = "tnt-term-" + UUID.randomUUID().toString().substring(0, 8);
        UUID resourceId = activeResource(tenantId);

        // Act: ten callers terminate it at the same instant.
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CALLERS);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < CONCURRENT_CALLERS; i++) {
                futures.add(pool.submit(() -> {
                    startGun.await();
                    return rest.exchange("/api/v1/resources/{id}/termination", HttpMethod.POST,
                            request(null, ""), String.class, resourceId);
                }));
            }
            startGun.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(90, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        List<ResponseEntity<String>> responses = futures.stream().map(future -> {
            try {
                return future.get();
            } catch (Exception e) {
                throw new IllegalStateException("A termination call did not complete", e);
            }
        }).toList();

        // Every caller gets a 202 — a repeat is not an error (FR-21), because the expiry worker
        // legitimately re-sends terminations after a timeout.
        assertThat(responses).allSatisfy(response ->
                assertThat(response.getStatusCode().value()).isEqualTo(202));

        int winners = 0;
        for (ResponseEntity<String> response : responses) {
            JsonNode json = objectMapper.readTree(response.getBody());
            assertThat(json.path("status").asText()).isEqualTo("TERMINATED");
            if (!json.path("alreadyTerminated").asBoolean()) {
                winners++;
            }
        }

        // Exactly one winner, nine no-ops.
        assertThat(winners).as("the conditional UPDATE admits exactly one winner").isEqualTo(1);

        // The row is TERMINATED, and terminated_at was stamped once.
        Resource terminated = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(terminated.getStatus()).isEqualTo("TERMINATED");
        assertThat(terminated.getTerminatedAt()).isNotNull();
        Instant terminatedAt = terminated.getTerminatedAt();

        // Teardown ran for the winner only: one provider delete, one meter stop, one quota
        // release. Nine extra releases would leak capacity back to the tenant nine times over.
        awaitUntil(Duration.ofSeconds(20), "teardown to finish", () ->
                !peers.findAll(WireMock.postRequestedFor(
                        urlPathMatching("/api/v1/quota/reservations/.*/release"))).isEmpty());

        // Scoped to this resource's own id: the ITs share one WireMock, so a wildcard could
        // pick up a teardown still draining from another test.
        peers.verify(1, WireMock.postRequestedFor(
                urlPathMatching("/api/v1/quota/reservations/" + resourceId + "/release")));
        peers.verify(1, WireMock.postRequestedFor(
                urlPathMatching("/api/v1/billing/meters/" + resourceId + "/stop")));
        peers.verify(1, WireMock.deleteRequestedFor(
                urlPathMatching("/api/v1/provider/instances/i-mock-7f3a9c21")));

        // A later repeat still changes nothing, including the timestamp.
        ResponseEntity<String> late = rest.exchange("/api/v1/resources/{id}/termination", HttpMethod.POST,
                request(null, ""), String.class, resourceId);
        assertThat(late.getStatusCode().value()).isEqualTo(202);
        assertThat(late.getBody()).contains("\"alreadyTerminated\":true");
        assertThat(resourceRepository.findById(resourceId).orElseThrow().getTerminatedAt())
                .isEqualTo(terminatedAt);
        peers.verify(1, WireMock.postRequestedFor(
                urlPathMatching("/api/v1/quota/reservations/" + resourceId + "/release")));
    }

    @Test
    void terminatingANonActiveResourceIs409() {
        // FR-22, BR4: REQUESTED/PROVISIONING/FAILED are not terminable, and the body names the
        // state the resource was actually in so the caller can act on it.
        String tenantId = "tnt-term-bad-" + UUID.randomUUID().toString().substring(0, 8);

        // Hold the saga at the provider call so the resource stays PROVISIONING while we ask.
        peers.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(urlPathMatching("/api/v1/provider/instances"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(201)
                        .withFixedDelay(5_000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"instanceId\":\"i-mock-slow\",\"status\":\"ACTIVE\"}")));

        rest.exchange("/api/v1/resources", HttpMethod.POST,
                request("slow-" + UUID.randomUUID(), provisionBody(tenantId, "VM", 4, 8)), String.class);

        awaitUntil(Duration.ofSeconds(20), "the resource to reach PROVISIONING", () ->
                resourceRepository.findByTenantIdAndStatus(tenantId, "PROVISIONING").size() == 1);
        UUID resourceId = resourceRepository.findByTenantIdAndStatus(tenantId, "PROVISIONING")
                .getFirst().getResourceId();

        ResponseEntity<String> response = rest.exchange("/api/v1/resources/{id}/termination",
                HttpMethod.POST, request(null, ""), String.class, resourceId);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody())
                .contains("INVALID_STATE_TRANSITION")
                .contains("\"currentStatus\":\"PROVISIONING\"");
    }

    @Test
    void terminatingAnUnknownResourceIs404() {
        ResponseEntity<String> response = rest.exchange("/api/v1/resources/{id}/termination",
                HttpMethod.POST, request(null, ""), String.class, UUID.randomUUID());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("RESOURCE_NOT_FOUND");
    }
}
