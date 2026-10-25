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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Concurrent reservations must reach capacity without exceeding it. */
@org.junit.jupiter.api.condition.EnabledIf("com.crm.quota.PostgresSingleton#available")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Br1ConcurrencyIT {

    /** 20 CPU of headroom, reserved 4 at a time: exactly 5 of the 20 requests can fit. */
    private static final int CPU_PER_REQUEST = 4;
    private static final int RAM_PER_REQUEST = 8;
    private static final int CONCURRENT_REQUESTS = 20;
    private static final int EXPECTED_SUCCESSES = 5;


    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresSingleton::url);
        registry.add("spring.datasource.username", PostgresSingleton::username);
        registry.add("spring.datasource.password", PostgresSingleton::password);
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
    @Test
    void duplicateTenantCannotResetReservedQuota() {
        String tenant = "duplicate-" + UUID.randomUUID();
        var body = new CreateTenantRequest(tenant, "Original", "STANDARD");
        assertThat(rest.postForEntity("/api/v1/tenants", body, String.class).getStatusCode().value()).isEqualTo(201);
        var reservation = new ReserveQuotaRequest(tenant, UUID.randomUUID().toString(), "VM", 4, 8);
        assertThat(rest.postForEntity("/api/v1/quota/reservations", reservation, String.class).getStatusCode().value()).isEqualTo(200);
        var duplicate = new CreateTenantRequest(tenant, "Replacement", "ENTERPRISE");
        assertThat(rest.postForEntity("/api/v1/tenants", duplicate, String.class).getStatusCode().value()).isEqualTo(409);
        var quota = rest.getForObject("/api/v1/tenants/{id}/quota", QuotaUsageResponse.class, tenant);
        assertThat(quota.cpu().used()).isEqualTo(4);
        assertThat(quota.cpu().limit()).isEqualTo(20);
        assertThat(rest.getForObject("/api/v1/tenants/{id}", TenantResponse.class, tenant).name()).isEqualTo("Original");
    }

    @Test
    void concurrentQuotaRetriesShareOneAllocationAndRejectDifferentPayloads() throws Exception {
        String tenant = "retry-" + UUID.randomUUID();
        rest.postForEntity("/api/v1/tenants", new CreateTenantRequest(tenant, "Retries", "STANDARD"), String.class);
        var request = new ReserveQuotaRequest(tenant, UUID.randomUUID().toString(), "VM", 20, 8);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var replies = java.util.stream.IntStream.range(0, 5).mapToObj(index -> pool.submit(() -> {
                start.await();
                return rest.postForEntity("/api/v1/quota/reservations", request, ReserveQuotaResponse.class);
            })).toList();
            start.countDown();
            for (var reply : replies) assertThat(reply.get().getStatusCode().value()).isEqualTo(200);
        }
        var quota = rest.getForObject("/api/v1/tenants/{id}/quota", QuotaUsageResponse.class, tenant);
        assertThat(quota.cpu().used()).isEqualTo(20);
        assertThat(quota.instances().used()).isEqualTo(1);
        var changed = new ReserveQuotaRequest(tenant, request.resourceId(), "VM", 1, 8);
        assertThat(rest.postForEntity("/api/v1/quota/reservations", changed, String.class).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void releaseBeforeLateReservationPreventsAnOrphanedAllocation() {
        String tenant = "cancel-" + UUID.randomUUID();
        rest.postForEntity("/api/v1/tenants", new CreateTenantRequest(tenant, "Cancellation", "STANDARD"), String.class);
        String id = UUID.randomUUID().toString();
        assertThat(rest.postForEntity("/api/v1/quota/reservations/{id}/release", null, String.class, id).getStatusCode().value()).isEqualTo(404);
        var late = new ReserveQuotaRequest(tenant, id, "VM", 4, 8);
        assertThat(rest.postForEntity("/api/v1/quota/reservations", late, String.class).getStatusCode().value()).isEqualTo(409);
        assertThat(rest.getForObject("/api/v1/tenants/{id}/quota", QuotaUsageResponse.class, tenant).cpu().used()).isZero();
    }

}
