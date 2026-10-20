package com.crm.resource;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Shared harness for the orchestrator's database-backed tests: a real PostgreSQL (so the
 * duplicate-key recovery and the conditional UPDATE are genuinely exercised, not emulated) plus
 * one WireMock standing in for all four peers, as in infrastructure-design.md 8.
 *
 * <p>Quota is a real peer in U1, but these tests are about the ORCHESTRATOR's guards, so stubbing
 * it keeps each test's failure pointing at one service. Quota's own rules are proven by
 * {@code Br1ConcurrencyIT} and {@code Br7ReleaseExactlyOnceIT} in that service's own build.
 *
 * <p>Every subclass is skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractResourceIT {

    /**
     * One WireMock for the whole JVM, deliberately NOT stopped in {@code @AfterAll}.
     *
     * <p>All three IT classes share one Spring context (same annotations, same property source),
     * so Spring caches and reuses it — including the peer base-urls resolved on first use. Were
     * this stopped after the first class, the reused context would keep pointing at a dead port
     * and every later class would fail with a connection error. The JVM exiting is the cleanup.
     */
    static WireMockServer peers;

    static synchronized void startPeers() {
        if (peers == null) {
            WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
            server.start();
            // Jetty's threads are non-daemon, so without this the fork never exits on its own and
            // the build reports "Surefire is going to kill self fork JVM" after a 30s wait.
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            peers = server;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> postgres = PostgresSingleton.INSTANCE;
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        startPeers();
        String peerUrl = "http://localhost:" + peers.port();
        registry.add("peers.quota.base-url", () -> peerUrl);
        registry.add("peers.provider.base-url", () -> peerUrl);
        registry.add("peers.billing.base-url", () -> peerUrl);
        registry.add("peers.notification.base-url", () -> peerUrl);

        // Keep the retry policy's shape but not its wall-clock cost.
        registry.add("retry.max-attempts", () -> 2);
        registry.add("retry.initial-backoff-ms", () -> 1);
        registry.add("retry.max-backoff-ms", () -> 2);
    }

    @Autowired
    protected TestRestTemplate rest;

    @BeforeEach
    void resetPeers() {
        peers.resetAll();
        stubAllPeersHealthy();
    }

    /** The default world: every peer succeeds, so a test only stubs what it wants to break. */
    protected void stubAllPeersHealthy() {
        peers.stubFor(post(urlPathMatching("/api/v1/quota/reservations"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"reservationId":"7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                 "tenantId":"tnt-std-001","resourceId":"ignored",
                                 "state":"RESERVED","cpu":4,"ramGb":8,"replay":false}""")));
        peers.stubFor(post(urlPathMatching("/api/v1/quota/reservations/.*/release"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"released\":true}")));
        peers.stubFor(post(urlPathMatching("/api/v1/provider/instances"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"instanceId\":\"i-mock-7f3a9c21\",\"status\":\"ACTIVE\"}")));
        peers.stubFor(delete(urlPathMatching("/api/v1/provider/instances/.*"))
                .willReturn(aResponse().withStatus(200)));
        peers.stubFor(post(urlPathMatching("/api/v1/billing/meters"))
                .willReturn(aResponse().withStatus(201)));
        peers.stubFor(post(urlPathMatching("/api/v1/billing/meters/.*/stop"))
                .willReturn(aResponse().withStatus(200)));
        peers.stubFor(post(urlPathMatching("/api/v1/notifications"))
                .willReturn(aResponse().withStatus(202)));
    }

    protected static String provisionBody(String tenantId, String type, int cpu, int ramGb) {
        return "{\"tenantId\":\"" + tenantId + "\",\"type\":\"" + type
                + "\",\"cpu\":" + cpu + ",\"ramGb\":" + ramGb + "}";
    }

    protected static HttpEntity<String> request(String idempotencyKey, String body) {
        return request(idempotencyKey, body, null, null);
    }

    /**
     * Builds the request with any extra header applied up front. An extra header cannot be added
     * after the fact: {@link HttpEntity} wraps its {@link HttpHeaders} read-only, so mutating them
     * afterwards throws {@code UnsupportedOperationException}.
     */
    protected static HttpEntity<String> request(String idempotencyKey, String body,
                                                String extraHeader, String extraValue) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.add("Idempotency-Key", idempotencyKey);
        }
        if (extraHeader != null) {
            headers.add(extraHeader, extraValue);
        }
        return new HttpEntity<>(body, headers);
    }

    /** Polls until {@code condition} holds, so an async saga can be awaited without a sleep. */
    protected static void awaitUntil(Duration timeout, String description, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + description, e);
            }
        }
        throw new AssertionError("Timed out after " + timeout + " waiting for " + description);
    }
}
