package com.crm.quota;

import com.crm.quota.api.dto.CreateTenantRequest;
import com.crm.quota.api.dto.QuotaUsageResponse;
import com.crm.quota.api.dto.ReserveQuotaRequest;
import com.crm.quota.api.dto.ReserveQuotaResponse;
import com.crm.quota.api.dto.TenantResponse;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BR1 / Challenge 1 database-backed test (Testcontainers, real PostgreSQL). Proves the atomic
 * conditional UPDATE never over-allocates under concurrency: N parallel reservations against a
 * limit of N CPU succeed, the rest get 422 QUOTA_EXCEEDED, and tenant_quota is exactly at its
 * limit afterward — not above it, and not below it either (AC-1.1 to AC-1.4).
 *
 * <p>This is the test the mechanism actually needs: a check-then-update in application code
 * passes every single-threaded test and fails this one. Skipped automatically when Docker is
 * not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Br1ConcurrencyIT {

    /** 20 CPU of headroom, reserved 4 at a time: exactly 5 of the 20 requests can fit. */
    private static final int CPU_PER_REQUEST = 4;
    private static final int RAM_PER_REQUEST = 8;
    private static final int CONCURRENT_REQUESTS = 20;
    private static final int EXPECTED_SUCCESSES = 5;

    @Container
    @SuppressWarnings("resource")   // closed by the Testcontainers JUnit extension
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

    @Test
    void br1_concurrentReservations_neverExceedLimit() throws Exception {
        // Arrange: a STANDARD tenant (20 CPU / 64 GB / 10 instances, functional-design.md 10).
        String tenantId = "tnt-br1-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<TenantResponse> created = rest.postForEntity("/api/v1/tenants",
                new CreateTenantRequest(tenantId, "BR1 concurrency tenant", "STANDARD"), TenantResponse.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().limits().cpu()).isEqualTo(20);

        // Act: fire all 20 reservations at the same instant. The latch is what makes this a real
        // race: without it the threads would trickle in and the test would pass on any design.
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<Integer>> results;
        try {
            results = java.util.stream.IntStream.range(0, CONCURRENT_REQUESTS)
                    .mapToObj(i -> pool.submit(() -> {
                        startGun.await();
                        ReserveQuotaRequest request = new ReserveQuotaRequest(
                                tenantId, UUID.randomUUID().toString(), "VM", CPU_PER_REQUEST, RAM_PER_REQUEST);
                        return rest.postForEntity("/api/v1/quota/reservations", request,
                                ReserveQuotaResponse.class).getStatusCode().value();
                    }))
                    .toList();
            startGun.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        List<Integer> statuses = results.stream().map(future -> {
            try {
                return future.get();
            } catch (Exception e) {
                throw new IllegalStateException("A reservation call did not complete", e);
            }
        }).toList();

        // Assert AC-1.2: EXACTLY N succeed. Not "at most" — a design that rejects a request while
        // capacity remains (e.g. optimistic locking without retries) fails here too.
        long succeeded = statuses.stream().filter(status -> status == 200).count();
        long rejected = statuses.stream().filter(status -> status == 422).count();

        assertThat(succeeded).as("exactly %d of %d reservations fit in 20 CPU", EXPECTED_SUCCESSES, CONCURRENT_REQUESTS)
                .isEqualTo(EXPECTED_SUCCESSES);
        assertThat(rejected).isEqualTo(CONCURRENT_REQUESTS - EXPECTED_SUCCESSES);
        assertThat(statuses).as("no request may fail for any other reason").containsOnly(200, 422);

        // Assert AC-1.1: usage lands exactly on the limit, never above it.
        QuotaUsageResponse usage = rest.getForObject("/api/v1/tenants/{id}/quota", QuotaUsageResponse.class, tenantId);
        assertThat(usage).isNotNull();
        assertThat(usage.cpu().used()).isEqualTo(20);
        assertThat(usage.cpu().limit()).isEqualTo(20);
        assertThat(usage.cpu().remaining()).isZero();
        assertThat(usage.ramGb().used()).isEqualTo(EXPECTED_SUCCESSES * RAM_PER_REQUEST);
        assertThat(usage.instances().used()).isEqualTo(EXPECTED_SUCCESSES);

        // Assert AC-1.4: a rejected request leaves NO trace in the ledger — the 422 rolls the
        // ledger insert back, so the row count is exact rather than "mostly right".
        assertThat(reservationRepository.findByTenantIdAndState(tenantId, "RESERVED"))
                .hasSize(EXPECTED_SUCCESSES);
    }

    @Test
    void br1_rejectionNamesTheExhaustedDimension() {
        // AC-1.3: the 422 says WHICH limit stopped the request, so a script need not parse prose.
        String tenantId = "tnt-br1-free-" + UUID.randomUUID().toString().substring(0, 8);
        rest.postForEntity("/api/v1/tenants",
                new CreateTenantRequest(tenantId, "BR1 free tenant", "FREE"), TenantResponse.class);

        // FREE is 2 CPU / 4 GB / 1 instance: asking for 4 CPU exhausts CPU and RAM at once.
        ResponseEntity<String> response = rest.postForEntity("/api/v1/quota/reservations",
                new ReserveQuotaRequest(tenantId, UUID.randomUUID().toString(), "VM", 4, 8), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).contains("QUOTA_EXCEEDED").contains("exhausted").contains("CPU");
    }
}
