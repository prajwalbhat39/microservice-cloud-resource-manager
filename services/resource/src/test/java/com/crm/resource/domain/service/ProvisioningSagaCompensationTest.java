package com.crm.resource.domain.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.crm.resource.client.BillingClient;
import com.crm.resource.client.NotificationClient;
import com.crm.resource.client.PeerRetry;
import com.crm.resource.client.ProviderClient;
import com.crm.resource.client.QuotaClient;
import com.crm.resource.common.web.CorrelationIdFilter;
import com.crm.resource.domain.ResourceEvent;
import com.crm.resource.domain.ResourceLifecycle;
import com.crm.resource.domain.SimulateFailureDirective;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BR6, Challenge 3C. WireMock stands in for Provider, Billing and Notification; the real clients
 * (and the real retry policy) run against it, so the retry-then-compensate path is genuinely
 * exercised rather than mocked away.
 *
 * <p>The lifecycle and reservation writers are Mockito mocks: this test is about the ORDER and
 * the SELECTION of compensating calls (functional-design.md 9.5), so what matters is which
 * transitions and which peer calls were made. The database effects those transitions produce are
 * asserted by {@code TerminationIdempotencyIT} and the Challenge 3C script.
 */
class ProvisioningSagaCompensationTest {

    private WireMockServer wireMock;
    private ResourceLifecycle lifecycle;
    private ReservationService reservationService;
    private ProvisioningSaga saga;

    private final UUID resourceId = UUID.fromString("e5f1a2b3-1111-4c2d-8e3f-9a0b1c2d3e4f");
    private final UUID reservationId = UUID.fromString("3b9a1c2d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        String baseUrl = "http://localhost:" + wireMock.port();
        // Two attempts and a 1 ms backoff: the retry policy is proven by PeerRetry's own
        // behaviour, and a real 200 ms/400 ms backoff would only slow this test down.
        PeerRetry retry = new PeerRetry(2, 1, 2.0, 2, 0.0);

        lifecycle = mock(ResourceLifecycle.class);
        reservationService = mock(ReservationService.class);

        saga = new ProvisioningSaga(
                lifecycle,
                new ProviderClient(peerBuilder(baseUrl), retry),
                new BillingClient(peerBuilder(baseUrl), retry),
                new QuotaClient(peerBuilder(baseUrl), retry),
                new NotificationClient(peerBuilder(baseUrl), retry),
                reservationService,
                new BigDecimal("0.12"));
    }

    /**
     * Builds a peer client the same way {@code RestClientConfig.peerClient} does — in particular
     * on {@link SimpleClientHttpRequestFactory}, which speaks HTTP/1.1. The default
     * {@code RestClient.builder()} would pick the JDK HttpClient, attempt an h2c upgrade and be
     * reset by WireMock's Jetty, so matching production here is both correct and necessary.
     */
    private static RestClient.Builder peerBuilder(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                // Mirrors the correlation-id initializer the real builders carry (nfr-design 3.2).
                .requestInitializer(request -> {
                    String id = org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY);
                    if (id != null) {
                        request.getHeaders().add(CorrelationIdFilter.HEADER, id);
                    }
                });
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private ProvisioningTask task() {
        return new ProvisioningTask(reservationId, resourceId, "tnt-std-001", "VM", 4, 8,
                SimulateFailureDirective.normalise("billing:always-fail"), "corr-3c-001");
    }

    private void stubProviderProvisionSucceeds() {
        wireMock.stubFor(post(urlPathMatching("/api/v1/provider/instances"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"instanceId\":\"i-mock-7f3a9c21\",\"status\":\"ACTIVE\"}")));
    }

    private void stubCompensationPeersSucceed() {
        wireMock.stubFor(delete(urlPathMatching("/api/v1/provider/instances/.*"))
                .willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(post(urlPathMatching("/api/v1/billing/meters/.*/stop"))
                .willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(post(urlPathMatching("/api/v1/quota/reservations/.*/release"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"resourceId\":\"" + resourceId + "\",\"released\":true}")));
        wireMock.stubFor(post(urlPathMatching("/api/v1/notifications"))
                .willReturn(aResponse().withStatus(202)));
    }

    @Test
    void billingFailureReleasesQuotaAndTerminatesInstance() {
        // Arrange: provider succeeds, billing meter start is forced to 503 (Challenge 3C),
        // every compensating call succeeds.
        stubProviderProvisionSucceeds();
        wireMock.stubFor(post(urlPathMatching("/api/v1/billing/meters"))
                .willReturn(aResponse().withStatus(503)));
        stubCompensationPeersSucceed();

        // Act
        saga.run(task());

        // Assert (reverse order, AC-3.3): the request journal shows the meter stop BEFORE the
        // instance it meters is deleted, and quota released LAST — releasing it earlier would
        // let another tenant consume capacity while this instance was still alive.
        List<String> journal = chronologicalJournal();

        int meterStop = indexOfMatch(journal, "POST /api/v1/billing/meters/" + resourceId + "/stop");
        int instanceDelete = indexOfMatch(journal, "DELETE /api/v1/provider/instances/i-mock-7f3a9c21");
        int quotaRelease = indexOfMatch(journal, "POST /api/v1/quota/reservations/" + resourceId + "/release");

        assertThat(instanceDelete).as("provider instance must be terminated").isNotEqualTo(-1);
        assertThat(quotaRelease).as("quota must be released (AC-3.4)").isNotEqualTo(-1);
        // The meter was never started (billing 503 on every attempt), so there is nothing to
        // stop: meterStarted stayed false and step (1) of compensation is correctly skipped.
        assertThat(meterStop).as("no meter was ever started, so none is stopped").isEqualTo(-1);
        assertThat(instanceDelete).isLessThan(quotaRelease);

        // Billing was retried, not given up on after one try (NFR-26).
        wireMock.verify(2, WireMock.postRequestedFor(urlPathMatching("/api/v1/billing/meters")));

        // The failure is recorded as the queryable saga state (AC-3.1, AC-3.5).
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(lifecycle).apply(eq(resourceId), eq(ResourceEvent.SAGA_FAILED), reason.capture());
        assertThat(reason.getValue()).startsWith("BILLING_UNAVAILABLE:");

        // provider_instance_id is cleared, so the column honestly reads "nothing is running" (A11).
        verify(lifecycle).clearInstanceId(resourceId);
        verify(reservationService).fail(eq(reservationId), reason.capture());
        assertThat(reason.getValue()).startsWith("BILLING_UNAVAILABLE:");

        // The resource never became ACTIVE and the reservation was never completed.
        verify(lifecycle, never()).apply(resourceId, ResourceEvent.ACTIVATED);
        verify(reservationService, never()).complete(any());

        // AC-3.6: the tenant is told.
        assertThat(indexOfMatch(journal, "POST /api/v1/notifications")).isNotEqualTo(-1);
    }

    @Test
    void fullReverseOrderWhenEveryForwardStepHadCompleted() {
        // The only path where ALL THREE compensations run, so it is the only one that can prove
        // the 3 -> 2 -> 1 ordering of AC-3.3 end to end. Provider and billing both succeed, then
        // ACTIVATE fails — the "a bug or a late duplicate task" case of functional-design.md 6.3.
        stubProviderProvisionSucceeds();
        wireMock.stubFor(post(urlPathMatching("/api/v1/billing/meters"))
                .willReturn(aResponse().withStatus(201)));
        stubCompensationPeersSucceed();
        org.mockito.Mockito.doThrow(new com.crm.resource.common.error.IllegalStateTransitionException(
                        "Resource is REQUESTED; event ACTIVATED is not allowed from this state",
                        "REQUESTED", "ACTIVATED"))
                .when(lifecycle).apply(resourceId, ResourceEvent.ACTIVATED);

        saga.run(new ProvisioningTask(reservationId, resourceId, "tnt-std-001", "VM", 4, 8,
                SimulateFailureDirective.none(), "corr-order-001"));

        List<String> journal = chronologicalJournal();
        int meterStop = indexOfMatch(journal, "POST /api/v1/billing/meters/" + resourceId + "/stop");
        int instanceDelete = indexOfMatch(journal, "DELETE /api/v1/provider/instances/i-mock-7f3a9c21");
        int quotaRelease = indexOfMatch(journal, "POST /api/v1/quota/reservations/" + resourceId + "/release");

        assertThat(meterStop).as("the meter was started, so it must be stopped").isNotEqualTo(-1);
        assertThat(instanceDelete).isNotEqualTo(-1);
        assertThat(quotaRelease).isNotEqualTo(-1);

        // The meter stops BEFORE the instance it meters disappears, otherwise a stopped-meter
        // call could race a terminated instance and leave an open meter.
        assertThat(meterStop).as("undo step 3 before step 2").isLessThan(instanceDelete);
        // Quota is released LAST: it is the only undo that is a promise to the tenant, and
        // releasing it earlier would let another request consume capacity while this instance
        // was still alive.
        assertThat(instanceDelete).as("undo step 2 before step 1").isLessThan(quotaRelease);

        // A non-peer cause deliberately says nothing about how far the saga got.
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(lifecycle).apply(eq(resourceId), eq(ResourceEvent.SAGA_FAILED), reason.capture());
        assertThat(reason.getValue()).isEqualTo("INVALID_STATE_TRANSITION: REQUESTED --ACTIVATED--> rejected");
        verify(lifecycle).clearInstanceId(resourceId);
    }

    @Test
    void aFailingCompensationStepDoesNotStopTheRest() {
        // "quietly": a compensation that aborts half-way is worse than one that pushes on — the
        // resource would be left PROVISIONING with quota still held. Here the provider's
        // terminate call fails outright, and quota must STILL be released.
        stubProviderProvisionSucceeds();
        wireMock.stubFor(post(urlPathMatching("/api/v1/billing/meters"))
                .willReturn(aResponse().withStatus(503)));
        stubCompensationPeersSucceed();
        wireMock.stubFor(delete(urlPathMatching("/api/v1/provider/instances/.*"))
                .willReturn(aResponse().withStatus(500)));

        saga.run(task());

        assertThat(indexOfMatch(chronologicalJournal(),
                "POST /api/v1/quota/reservations/" + resourceId + "/release"))
                .as("quota is released even though the provider undo failed")
                .isNotEqualTo(-1);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(lifecycle).apply(eq(resourceId), eq(ResourceEvent.SAGA_FAILED), reason.capture());
        assertThat(reason.getValue()).startsWith("BILLING_UNAVAILABLE:");
        verify(reservationService).fail(eq(reservationId), any());
    }

    @Test
    void providerFailureNeverStartsBillingOrClearsAnInstanceId() {
        // Provider itself fails before an instance ever exists.
        wireMock.stubFor(post(urlPathMatching("/api/v1/provider/instances"))
                .willReturn(aResponse().withStatus(503)));
        stubCompensationPeersSucceed();

        saga.run(task());

        List<String> journal = chronologicalJournal();

        // instanceId == null, so the provider-terminate step is skipped and the instance id is
        // never cleared (there was none). meterStarted == false, so no meter stop either.
        assertThat(journal).noneMatch(entry -> entry.startsWith("DELETE /api/v1/provider/instances"));
        assertThat(journal).noneMatch(entry -> entry.startsWith("POST /api/v1/billing/meters"));
        verify(lifecycle, never()).clearInstanceId(any());
        verify(lifecycle, never()).apply(eq(resourceId), eq(ResourceEvent.INSTANCE_CREATED), any());

        // Only quota is released — the one step that did complete before the failure.
        assertThat(indexOfMatch(journal, "POST /api/v1/quota/reservations/" + resourceId + "/release"))
                .isNotEqualTo(-1);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(lifecycle).apply(eq(resourceId), eq(ResourceEvent.SAGA_FAILED), reason.capture());
        assertThat(reason.getValue()).startsWith("PROVIDER_UNAVAILABLE:");
    }

    @Test
    void happyPathActivatesAndCompensatesNothing() {
        // The control case: with every peer healthy nothing is undone, the resource reaches
        // ACTIVE through the only transition that leads there (BR4), and the reservation completes.
        stubProviderProvisionSucceeds();
        wireMock.stubFor(post(urlPathMatching("/api/v1/billing/meters"))
                .willReturn(aResponse().withStatus(201)));
        stubCompensationPeersSucceed();

        saga.run(new ProvisioningTask(reservationId, resourceId, "tnt-std-001", "VM", 4, 8,
                SimulateFailureDirective.none(), "corr-happy-001"));

        verify(lifecycle).apply(resourceId, ResourceEvent.PROVISION_STARTED);
        verify(lifecycle).apply(resourceId, ResourceEvent.INSTANCE_CREATED, "i-mock-7f3a9c21");
        verify(lifecycle).apply(resourceId, ResourceEvent.ACTIVATED);
        verify(reservationService).complete(reservationId);

        verify(lifecycle, never()).apply(eq(resourceId), eq(ResourceEvent.SAGA_FAILED), any());
        verify(lifecycle, never()).clearInstanceId(any());

        List<String> journal = chronologicalJournal();
        assertThat(journal).noneMatch(entry -> entry.startsWith("DELETE /api/v1/provider/instances"));
        assertThat(journal).noneMatch(entry -> entry.contains("/release"));
    }

    @Test
    void aNotificationFailureNeverTriggersCompensation() {
        // FR-33: notification sits OUTSIDE the compensation path. A failing notifier must not
        // roll back a provisioned, paid-for resource.
        stubProviderProvisionSucceeds();
        wireMock.stubFor(post(urlPathMatching("/api/v1/billing/meters"))
                .willReturn(aResponse().withStatus(201)));
        wireMock.stubFor(post(urlPathMatching("/api/v1/notifications"))
                .willReturn(aResponse().withStatus(503)));
        wireMock.stubFor(post(urlPathMatching("/api/v1/quota/reservations/.*/release"))
                .willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(delete(urlPathMatching("/api/v1/provider/instances/.*"))
                .willReturn(aResponse().withStatus(200)));

        saga.run(new ProvisioningTask(reservationId, resourceId, "tnt-std-001", "VM", 4, 8,
                SimulateFailureDirective.none(), "corr-notify-001"));

        verify(lifecycle).apply(resourceId, ResourceEvent.ACTIVATED);
        verify(reservationService).complete(reservationId);
        verify(lifecycle, never()).apply(eq(resourceId), eq(ResourceEvent.SAGA_FAILED), any());

        List<String> journal = chronologicalJournal();
        assertThat(journal).noneMatch(entry -> entry.contains("/release"));
    }

    @Test
    void correlationIdCrossesTheAsyncBoundaryOntoPeerCalls() {
        // nfr-design.md 3.3: the saga re-seeds the MDC from the task's correlationId field, and
        // the RestClient initializer puts it on every outbound call. One trace, both halves.
        stubProviderProvisionSucceeds();
        wireMock.stubFor(post(urlPathMatching("/api/v1/billing/meters"))
                .willReturn(aResponse().withStatus(201)));
        stubCompensationPeersSucceed();

        saga.run(new ProvisioningTask(reservationId, resourceId, "tnt-std-001", "VM", 4, 8,
                SimulateFailureDirective.none(), "corr-trace-001"));

        List<LoggedRequest> providerCalls = wireMock.findAll(
                WireMock.postRequestedFor(urlPathMatching("/api/v1/provider/instances")));
        assertThat(providerCalls).isNotEmpty();
        assertThat(providerCalls.getFirst().getHeader("X-Correlation-Id")).isEqualTo("corr-trace-001");
    }

    @Test
    void theSimulateDirectiveIsReplayedOntoTheRightPeerOnly() {
        // FR-46: "billing:always-fail" must reach Billing and NOT Provider, even though the
        // header itself died with the client's HTTP exchange at the 202.
        stubProviderProvisionSucceeds();
        wireMock.stubFor(post(urlPathMatching("/api/v1/billing/meters"))
                .willReturn(aResponse().withStatus(503)));
        stubCompensationPeersSucceed();

        saga.run(task());

        assertThat(wireMock.findAll(WireMock.postRequestedFor(urlPathMatching("/api/v1/billing/meters")))
                .getFirst().getHeader("X-Simulate-Failure")).isEqualTo("always-fail");
        assertThat(wireMock.findAll(WireMock.postRequestedFor(urlPathMatching("/api/v1/provider/instances")))
                .getFirst().getHeader("X-Simulate-Failure")).isNull();
    }

    private static int indexOfMatch(List<String> journal, String entry) {
        return journal.indexOf(entry);
    }

    /**
     * The request journal in the order the calls actually happened.
     *
     * <p>{@code getAllServeEvents()} returns events NEWEST-FIRST, so it has to be reversed before
     * any ordering assertion. Reading it raw would invert every "A happened before B" check —
     * and would have quietly reported the compensation order as correct if it were reversed.
     */
    private List<String> chronologicalJournal() {
        List<String> entries = new java.util.ArrayList<>(wireMock.getAllServeEvents().stream()
                .map(event -> event.getRequest().getMethod().value() + " " + event.getRequest().getUrl())
                .toList());
        java.util.Collections.reverse(entries);
        return entries;
    }
}
