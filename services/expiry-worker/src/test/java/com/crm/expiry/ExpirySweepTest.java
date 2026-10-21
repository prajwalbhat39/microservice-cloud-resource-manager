package com.crm.expiry;

import com.crm.expiry.domain.ExpirySweep;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "expiry.enabled=false")
class ExpirySweepTest {
    static final String FIRST = "e5f1a2b3-1111-4c2d-8e3f-9a0b1c2d3e4f";
    static final String SECOND = "e5f1a2b3-2222-4c2d-8e3f-9a0b1c2d3e4f";
    static final WireMockServer resource = new WireMockServer(options().dynamicPort());
    static { resource.start(); }
    @Autowired ExpirySweep sweep;
    @Autowired TestRestTemplate http;

    @TestConfiguration
    static class Time {
        @Bean @Primary Clock testClock() {
            return Clock.fixed(Instant.parse("2025-03-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void address(DynamicPropertyRegistry properties) {
        properties.add("peers.resource.url", resource::baseUrl);
        properties.add("peers.resource.read-timeout-ms", () -> 150);
    }

    @BeforeEach
    void reset() {
        resource.resetAll();
        resource.stubFor(post(urlPathMatching("/api/v1/resources/.*/termination"))
                .willReturn(aResponse().withStatus(202)));
    }

    @AfterAll
    static void stopPeer() { resource.stop(); }

    private void candidates(String items) {
        resource.stubFor(get(urlEqualTo("/api/v1/resources/expired"))
                .willReturn(okJson("{\"items\":" + items + ",\"count\":2}")));
    }

    private String expired(String id) {
        return "{\"resourceId\":\"" + id + "\",\"expiresAt\":\"2025-02-28T23:59:59Z\"}";
    }

    @Test
    void terminatesCandidatesAndSharesOneCorrelationAcrossTheSweep() {
        candidates("[" + expired(FIRST) + "," + expired(SECOND) + "]");
        sweep.run();
        String id = resource.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getMethod().getName().equals("GET"))
                .findFirst().orElseThrow().getRequest().getHeader("X-Correlation-Id");
        assertThat(id).isNotBlank();
        resource.verify(2, postRequestedFor(urlPathMatching("/api/v1/resources/.*/termination"))
                .withHeader("X-Correlation-Id", equalTo(id)));
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void failedTerminationDoesNotBlockOtherResourcesOrTheNextSweep() {
        candidates("[" + expired(FIRST) + "," + expired(SECOND) + "]");
        resource.stubFor(post(urlEqualTo("/api/v1/resources/" + FIRST + "/termination"))
                .willReturn(aResponse().withStatus(503)));
        sweep.run();
        resource.verify(postRequestedFor(urlEqualTo("/api/v1/resources/" + SECOND + "/termination")));
        resource.stubFor(post(urlEqualTo("/api/v1/resources/" + FIRST + "/termination"))
                .willReturn(aResponse().withStatus(202)));
        sweep.run();
        resource.verify(2, postRequestedFor(urlEqualTo("/api/v1/resources/" + FIRST + "/termination")));
    }

    @Test
    void staleCandidateConflictDoesNotBlockTheRest() {
        candidates("[" + expired(FIRST) + "," + expired(SECOND) + "]");
        resource.stubFor(post(urlEqualTo("/api/v1/resources/" + FIRST + "/termination"))
                .willReturn(aResponse().withStatus(409)));
        sweep.run();
        resource.verify(postRequestedFor(urlEqualTo("/api/v1/resources/" + SECOND + "/termination")));
    }

    @Test
    void skipsFutureAndIncompleteCandidates() {
        candidates("[{\"resourceId\":\"" + FIRST + "\",\"expiresAt\":\"2025-03-02T00:00:00Z\"},null,{\"resourceId\":\"" + SECOND + "\"}]");
        sweep.run();
        resource.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void emptyOrUnavailableCandidateListCausesNoTermination() {
        candidates("[]");
        sweep.run();
        resource.stubFor(get(urlEqualTo("/api/v1/resources/expired")).willReturn(aResponse().withStatus(503)));
        sweep.run();
        resource.verify(0, postRequestedFor(anyUrl()));
        assertThat(http.getForObject("/health", JsonNode.class).path("status").asText()).isEqualTo("UP");
    }

    @Test
    void malformedOrSlowResponseDefersTheSweep() {
        resource.stubFor(get(urlEqualTo("/api/v1/resources/expired")).willReturn(okJson("not json")));
        sweep.run();
        resource.stubFor(get(urlEqualTo("/api/v1/resources/expired"))
                .willReturn(okJson("{\"items\":[]}").withFixedDelay(600)));
        sweep.run();
        resource.verify(0, postRequestedFor(anyUrl()));
    }
}
