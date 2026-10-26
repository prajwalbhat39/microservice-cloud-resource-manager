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

class ProvisioningSagaCompensationTest {

    private WireMockServer wireMock;
    private ResourceLifecycle lifecycle;
    private ReservationService reservationService;
    private ProvisioningSaga saga;
    private ResourceWork work;

    private final UUID resourceId = UUID.fromString("e5f1a2b3-1111-4c2d-8e3f-9a0b1c2d3e4f");
    private final UUID reservationId = UUID.fromString("3b9a1c2d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        String baseUrl = "http://localhost:" + wireMock.port();
        PeerRetry retry = new PeerRetry(2, 1, 2.0, 2, 0.0);

        lifecycle = mock(ResourceLifecycle.class);
        reservationService = mock(ReservationService.class);
        work = mock(ResourceWork.class);

        saga = new ProvisioningSaga(
                lifecycle,
                new ProviderClient(peerBuilder(baseUrl), retry),
                new BillingClient(peerBuilder(baseUrl), retry),
                new QuotaClient(peerBuilder(baseUrl), retry),
                new NotificationClient(peerBuilder(baseUrl), retry),
                new BigDecimal("0.12"), work, new ProvisioningCompletion(lifecycle, reservationService, work));
    }

    private static RestClient.Builder peerBuilder(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
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
        stubProviderProvisionSucceeds();
        wireMock.stubFor(post(urlPathMatching("/api/v1/billing/meters"))
                .willReturn(aResponse().withStatus(503)));
        stubCompensationPeersSucceed();

        saga.run(task());

        List<String> journal = chronologicalJournal();

        int meterStop = indexOfMatch(journal, "POST /api/v1/billing/meters/" + resourceId + "/stop");
        int instanceDelete = indexOfMatch(journal, "DELETE /api/v1/provider/instances/requests/ct-" + resourceId + "");
        int quotaRelease = indexOfMatch(journal, "POST /api/v1/quota/reservations/" + resourceId + "/release");

        assertThat(instanceDelete).as("provider instance must be terminated").isNotEqualTo(-1);
        assertThat(quotaRelease).as("quota must be released (AC-3.4)").isNotEqualTo(-1);
        assertThat(meterStop).as("stop reconciles a possibly committed meter").isLessThan(instanceDelete);
        assertThat(instanceDelete).isLessThan(quotaRelease);

        wireMock.verify(2, WireMock.postRequestedFor(urlPathMatching("/api/v1/billing/meters")));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(lifecycle).fail(eq(resourceId), reason.capture());
        assertThat(reason.getValue()).startsWith("BILLING_UNAVAILABLE:");

        verify(lifecycle).clearInstanceId(resourceId);
        verify(reservationService).fail(eq(reservationId), reason.capture());
        assertThat(reason.getValue()).startsWith("BILLING_UNAVAILABLE:");

        verify(lifecycle, never()).apply(resourceId, ResourceEvent.ACTIVATED);
        verify(reservationService, never()).complete(any());

        assertThat(indexOfMatch(journal, "POST /api/v1/notifications")).isNotEqualTo(-1);
    }

    @Test
    void fullReverseOrderWhenEveryForwardStepHadCompleted() {
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
        int instanceDelete = indexOfMatch(journal, "DELETE /api/v1/provider/instances/requests/ct-" + resourceId + "");
        int quotaRelease = indexOfMatch(journal, "POST /api/v1/quota/reservations/" + resourceId + "/release");

        assertThat(meterStop).as("the meter was started, so it must be stopped").isNotEqualTo(-1);
        assertThat(instanceDelete).isNotEqualTo(-1);
        assertThat(quotaRelease).isNotEqualTo(-1);

        assertThat(meterStop).as("undo step 3 before step 2").isLessThan(instanceDelete);
        assertThat(instanceDelete).as("undo step 2 before step 1").isLessThan(quotaRelease);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(lifecycle).fail(eq(resourceId), reason.capture());
        assertThat(reason.getValue()).isEqualTo("INVALID_STATE_TRANSITION: REQUESTED --ACTIVATED--> rejected");
        verify(lifecycle).clearInstanceId(resourceId);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"provider", "billing"})
    void failedCleanupStaysPendingUntilRecoverySucceeds(String peer) {
        stubProviderProvisionSucceeds();
        wireMock.stubFor(post(urlPathMatching("/api/v1/billing/meters")).willReturn(aResponse().withStatus(503)));
        stubCompensationPeersSucceed();
        if (peer.equals("provider")) {
            wireMock.stubFor(delete(urlPathMatching("/api/v1/provider/instances/.*")).willReturn(aResponse().withStatus(500)));
        } else {
            wireMock.stubFor(post(urlPathMatching("/api/v1/billing/meters/.*/stop")).willReturn(aResponse().withStatus(503)));
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> saga.run(task())).isInstanceOf(RuntimeException.class);
        verify(work).enqueue(eq(resourceId), eq("COMPENSATE"), any());
        wireMock.verify(WireMock.deleteRequestedFor(urlPathMatching("/api/v1/provider/instances/requests/.*")));
        wireMock.verify(WireMock.postRequestedFor(urlPathMatching("/api/v1/billing/meters/.*/stop")));
        verify(lifecycle, never()).clearInstanceId(any());
        verify(reservationService, never()).fail(any(), any());
        assertThat(chronologicalJournal()).noneMatch(entry -> entry.contains("/release"));

        stubCompensationPeersSucceed();
        saga.compensate(new ResourceWork.Compensation(task(), "BILLING_UNAVAILABLE: retry"));
        verify(lifecycle).clearInstanceId(resourceId);
        verify(reservationService).fail(eq(reservationId), any());
        verify(work).done(resourceId);
    }

    @Test
    void providerFailureCancelsUnacknowledgedPeerEffects() {
        wireMock.stubFor(post(urlPathMatching("/api/v1/provider/instances"))
                .willReturn(aResponse().withStatus(503)));
        stubCompensationPeersSucceed();

        saga.run(task());

        List<String> journal = chronologicalJournal();

        assertThat(journal).anyMatch(entry -> entry.startsWith("DELETE /api/v1/provider/instances/requests"));
        assertThat(journal).noneMatch(entry -> entry.equals("POST /api/v1/billing/meters"));
        verify(lifecycle).clearInstanceId(resourceId);
        verify(lifecycle, never()).apply(eq(resourceId), eq(ResourceEvent.INSTANCE_CREATED), any());

        assertThat(indexOfMatch(journal, "POST /api/v1/quota/reservations/" + resourceId + "/release"))
                .isNotEqualTo(-1);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(lifecycle).fail(eq(resourceId), reason.capture());
        assertThat(reason.getValue()).startsWith("PROVIDER_UNAVAILABLE:");
    }

    @Test
    void happyPathActivatesAndCompensatesNothing() {
        stubProviderProvisionSucceeds();
        wireMock.stubFor(post(urlPathMatching("/api/v1/billing/meters"))
                .willReturn(aResponse().withStatus(201)));
        stubCompensationPeersSucceed();

        saga.run(new ProvisioningTask(reservationId, resourceId, "tnt-std-001", "VM", 4, 8,
                SimulateFailureDirective.none(), "corr-happy-001"));

        verify(lifecycle).begin(resourceId);
        verify(lifecycle).apply(resourceId, ResourceEvent.INSTANCE_CREATED, "i-mock-7f3a9c21");
        verify(lifecycle).apply(resourceId, ResourceEvent.ACTIVATED);
        verify(reservationService).complete(reservationId);

        verify(lifecycle, never()).fail(eq(resourceId), any());
        verify(lifecycle, never()).clearInstanceId(any());

        List<String> journal = chronologicalJournal();
        assertThat(journal).noneMatch(entry -> entry.startsWith("DELETE /api/v1/provider/instances"));
        assertThat(journal).noneMatch(entry -> entry.contains("/release"));
    }

    @Test
    void aNotificationFailureNeverTriggersCompensation() {
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
        verify(lifecycle, never()).fail(eq(resourceId), any());

        List<String> journal = chronologicalJournal();
        assertThat(journal).noneMatch(entry -> entry.contains("/release"));
    }

    @Test
    void correlationIdCrossesTheAsyncBoundaryOntoPeerCalls() {
        org.slf4j.MDC.put(CorrelationIdFilter.MDC_KEY, "recovery-sweep");
        stubProviderProvisionSucceeds();
        wireMock.stubFor(post(urlPathMatching("/api/v1/billing/meters"))
                .willReturn(aResponse().withStatus(201)));
        stubCompensationPeersSucceed();

        saga.run(new ProvisioningTask(reservationId, resourceId, "tnt-std-001", "VM", 4, 8,
                SimulateFailureDirective.none(), "corr-trace-001"));
        assertThat(org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("recovery-sweep");

        List<LoggedRequest> providerCalls = wireMock.findAll(
                WireMock.postRequestedFor(urlPathMatching("/api/v1/provider/instances")));
        assertThat(providerCalls).isNotEmpty();
        assertThat(providerCalls.getFirst().getHeader("X-Correlation-Id")).isEqualTo("corr-trace-001");
    }

    @Test
    void theSimulateDirectiveIsReplayedOntoTheRightPeerOnly() {
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

    private List<String> chronologicalJournal() {
        List<String> entries = new java.util.ArrayList<>(wireMock.getAllServeEvents().stream()
                .map(event -> event.getRequest().getMethod().value() + " " + event.getRequest().getUrl())
                .toList());
        java.util.Collections.reverse(entries);
        return entries;
    }
}
