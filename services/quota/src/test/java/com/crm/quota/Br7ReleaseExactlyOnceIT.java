package com.crm.quota;

import com.crm.quota.api.dto.CreateTenantRequest;
import com.crm.quota.api.dto.QuotaUsageResponse;
import com.crm.quota.api.dto.ReleaseQuotaResponse;
import com.crm.quota.api.dto.ReserveQuotaRequest;
import com.crm.quota.api.dto.ReserveQuotaResponse;
import com.crm.quota.api.dto.TenantResponse;
import com.crm.quota.persistence.entity.QuotaReservation;
import com.crm.quota.persistence.repository.QuotaReservationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BR7 database-backed test (Testcontainers). Proves release is exactly-once: repeated release
 * calls for one resource give back its quota only the first time, enforced by the ledger's
 * UNIQUE(resource_id) and the RESERVED -> RELEASED conditional state flip (functional-design.md 8).
 *
 * <p>This matters because the orchestrator's compensation path and its termination path can both
 * call release for the same resource, and the expiry worker can call it again after a timeout.
 * Skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Br7ReleaseExactlyOnceIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("quota_db")
            .withUsername("quota_user")
            .withPassword("changeme");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private QuotaReservationRepository reservationRepository;

    private String createTenant(String prefix) {
        String tenantId = prefix + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<TenantResponse> created = rest.postForEntity("/api/v1/tenants",
                new CreateTenantRequest(tenantId, "BR7 tenant", "STANDARD"), TenantResponse.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return tenantId;
    }

    private String reserve(String tenantId, int cpu, int ramGb) {
        String resourceId = UUID.randomUUID().toString();
        ResponseEntity<ReserveQuotaResponse> reserved = rest.postForEntity("/api/v1/quota/reservations",
                new ReserveQuotaRequest(tenantId, resourceId, "VM", cpu, ramGb), ReserveQuotaResponse.class);
        assertThat(reserved.getStatusCode().value()).isEqualTo(200);
        return resourceId;
    }

    private QuotaUsageResponse usage(String tenantId) {
        return rest.getForObject("/api/v1/tenants/{id}/quota", QuotaUsageResponse.class, tenantId);
    }

    @Test
    void br7_repeatedRelease_refundsQuotaOnlyOnce() {
        // Arrange: a RESERVED ledger row consuming 4 CPU / 8 GB / 1 instance.
        String tenantId = createTenant("tnt-br7-");
        String resourceId = reserve(tenantId, 4, 8);
        assertThat(usage(tenantId).cpu().used()).isEqualTo(4);

        // Act 1: the first release gives the quota back.
        ResponseEntity<ReleaseQuotaResponse> first = rest.postForEntity(
                "/api/v1/quota/reservations/{resourceId}/release", null, ReleaseQuotaResponse.class, resourceId);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody()).isNotNull();
        assertThat(first.getBody().released()).isTrue();
        assertThat(first.getBody().state()).isEqualTo("RELEASED");
        assertThat(first.getBody().releasedAt()).isNotNull();

        QuotaUsageResponse afterFirst = usage(tenantId);
        assertThat(afterFirst.cpu().used()).isZero();
        assertThat(afterFirst.ramGb().used()).isZero();
        assertThat(afterFirst.instances().used()).isZero();

        // Read the stamp back from the database rather than from the JSON: PostgreSQL stores
        // timestamptz at microsecond precision, so the round-tripped value differs from
        // Instant.now()'s nanoseconds in the response body. Comparing DB-to-DB keeps the
        // assertion about "did it change?" rather than about clock resolution.
        Instant firstReleasedAt = reservationRepository.findByResourceId(resourceId)
                .orElseThrow().getReleasedAt();
        assertThat(firstReleasedAt).isNotNull();

        // Act 2: the second release is a no-op. 200, not 404 and not 409: the caller's goal
        // ("this resource holds no quota") is already true.
        ResponseEntity<ReleaseQuotaResponse> second = rest.postForEntity(
                "/api/v1/quota/reservations/{resourceId}/release", null, ReleaseQuotaResponse.class, resourceId);

        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().released()).as("the second call must not refund again").isFalse();
        assertThat(second.getBody().state()).isEqualTo("RELEASED");

        // Assert: usage did NOT drop below zero, and released_at was not re-stamped.
        QuotaUsageResponse afterSecond = usage(tenantId);
        assertThat(afterSecond.cpu().used()).isZero();
        assertThat(afterSecond.instances().used()).isZero();

        List<QuotaReservation> rows = reservationRepository.findByTenantIdAndState(tenantId, "RELEASED");
        assertThat(rows).as("UNIQUE(resource_id): exactly one ledger row per resource").hasSize(1);
        assertThat(rows.getFirst().getResourceId()).isEqualTo(resourceId);
        // released_at was stamped once and not re-stamped by the repeat.
        assertThat(rows.getFirst().getReleasedAt()).isEqualTo(firstReleasedAt);
        assertThat(second.getBody().releasedAt()).isNotNull();
    }

    @Test
    void br7_concurrentReleases_refundExactlyOnce() throws Exception {
        // The ordering above is the easy case. This is the one the state flip exists for:
        // compensation, termination and the expiry worker all arriving at the same instant.
        String tenantId = createTenant("tnt-br7-race-");
        String resourceId = reserve(tenantId, 4, 8);

        int callers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<ReleaseQuotaResponse>> results;
        try {
            results = java.util.stream.IntStream.range(0, callers)
                    .mapToObj(i -> pool.submit(() -> {
                        startGun.await();
                        return rest.postForEntity("/api/v1/quota/reservations/{resourceId}/release",
                                null, ReleaseQuotaResponse.class, resourceId).getBody();
                    }))
                    .toList();
            startGun.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        long refunded = results.stream().map(future -> {
            try {
                return future.get();
            } catch (Exception e) {
                throw new IllegalStateException("A release call did not complete", e);
            }
        }).filter(response -> response != null && response.released()).count();

        // Exactly one winner, however many callers raced.
        assertThat(refunded).isEqualTo(1);
        assertThat(usage(tenantId).cpu().used()).isZero();
        assertThat(reservationRepository.findByTenantIdAndState(tenantId, "RELEASED")).hasSize(1);
    }

    @Test
    void br7_releaseForAnUnknownResourceIs404() {
        // No ledger row at all is a different answer from "already released": there is nothing
        // this service ever promised for that resourceId.
        ResponseEntity<String> response = rest.postForEntity("/api/v1/quota/reservations/{resourceId}/release",
                null, String.class, UUID.randomUUID().toString());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("QUOTA_RESERVATION_NOT_FOUND");
    }

    @Test
    void br7_reserveAfterReleaseIsRejected() {
        // A9 / functional-design.md 12.3: reserving again under a ledger row the ledger says is
        // closed is a state conflict (409), not a fresh reservation (200) and not a quota
        // problem (422).
        String tenantId = createTenant("tnt-br7-reuse-");
        String resourceId = reserve(tenantId, 4, 8);
        rest.postForEntity("/api/v1/quota/reservations/{resourceId}/release",
                null, ReleaseQuotaResponse.class, resourceId);

        ResponseEntity<String> response = rest.postForEntity("/api/v1/quota/reservations",
                new ReserveQuotaRequest(tenantId, resourceId, "VM", 4, 8), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("QUOTA_RESERVATION_ALREADY_RELEASED");
    }

    @Test
    void br7_duplicateReserveForOneResourceAddsUsageOnce() {
        // NFR-27: a retried reserve for the SAME resourceId replays rather than double-charging.
        String tenantId = createTenant("tnt-br7-replay-");
        String resourceId = UUID.randomUUID().toString();

        ResponseEntity<ReserveQuotaResponse> first = rest.postForEntity("/api/v1/quota/reservations",
                new ReserveQuotaRequest(tenantId, resourceId, "VM", 4, 8), ReserveQuotaResponse.class);
        ResponseEntity<ReserveQuotaResponse> retry = rest.postForEntity("/api/v1/quota/reservations",
                new ReserveQuotaRequest(tenantId, resourceId, "VM", 4, 8), ReserveQuotaResponse.class);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody()).isNotNull();
        assertThat(retry.getBody()).isNotNull();
        assertThat(retry.getBody().replay()).isTrue();
        assertThat(retry.getBody().reservationId()).isEqualTo(first.getBody().reservationId());

        // The decisive assertion: usage was added once, not twice.
        assertThat(usage(tenantId).cpu().used()).isEqualTo(4);
        assertThat(usage(tenantId).instances().used()).isEqualTo(1);
    }
}
