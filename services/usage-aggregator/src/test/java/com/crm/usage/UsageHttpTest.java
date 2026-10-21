package com.crm.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UsageHttpTest {
    static final WireMockServer peers = new WireMockServer(options().dynamicPort());
    static { peers.start(); }
    @Autowired TestRestTemplate http;

    @TestConfiguration
    static class Time {
        @Bean @Primary Clock testClock() {
            return Clock.fixed(Instant.parse("2025-03-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void addresses(DynamicPropertyRegistry properties) {
        for (String peer : new String[]{"quota", "resource", "billing"}) {
            properties.add("peers." + peer + ".url", peers::baseUrl);
            properties.add("peers." + peer + ".read-timeout-ms", () -> 150);
        }
    }

    @BeforeEach
    void healthyPeers() {
        peers.resetAll();
        peers.stubFor(get(urlPathEqualTo("/api/v1/tenants/tenant-a/quota"))
                .willReturn(okJson("{\"cpu\":{\"limit\":20,\"used\":4,\"remaining\":16}}")));
        peers.stubFor(get(urlPathEqualTo("/api/v1/resources"))
                .willReturn(okJson("{\"items\":[{\"resourceId\":\"resource-1\",\"status\":\"ACTIVE\"}],\"count\":1}")));
        peers.stubFor(get(urlPathEqualTo("/api/v1/billing/tenants/tenant-a/cost"))
                .willReturn(okJson("{\"amount\":\"0.0000\",\"currency\":\"USD\"}")));
    }

    @AfterAll
    static void stopPeers() { peers.stop(); }

    private JsonNode usage() { return http.getForObject("/api/v1/usage/tenant-a", JsonNode.class); }

    @Test
    void composesRealHttpResponsesPreservesZeroAndPropagatesCorrelation() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "u4-usage-trace");
        var response = http.exchange("/api/v1/usage/tenant-a", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        JsonNode body = response.getBody();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body.path("quota").path("cpu").path("used").asInt()).isEqualTo(4);
        assertThat(body.path("resources").get(0).path("resourceId").asText()).isEqualTo("resource-1");
        assertThat(body.path("cost").path("amount").asText()).isEqualTo("0.0000");
        assertThat(body.path("availability").toString()).isEqualTo("{\"quota\":true,\"resources\":true,\"billing\":true}");
        peers.verify(getRequestedFor(urlPathEqualTo("/api/v1/resources"))
                .withQueryParam("tenantId", equalTo("tenant-a")).withQueryParam("status", equalTo("ACTIVE"))
                .withHeader("X-Correlation-Id", equalTo("u4-usage-trace")));
        peers.verify(getRequestedFor(urlPathEqualTo("/api/v1/billing/tenants/tenant-a/cost"))
                .withQueryParam("period", equalTo("2025-03"))
                .withHeader("X-Correlation-Id", equalTo("u4-usage-trace")));
        peers.verify(getRequestedFor(urlPathEqualTo("/api/v1/tenants/tenant-a/quota"))
                .withHeader("X-Correlation-Id", equalTo("u4-usage-trace")));
    }

    @Test
    void missingBillingDoesNotHideQuotaOrResources() {
        peers.stubFor(get(urlPathEqualTo("/api/v1/billing/tenants/tenant-a/cost"))
                .willReturn(aResponse().withStatus(503)));
        JsonNode result = usage();
        assertThat(result.has("cost")).isFalse();
        assertThat(result.path("availability").path("billing").asBoolean()).isFalse();
        assertThat(result.path("quota").isObject()).isTrue();
        assertThat(result.path("resources")).hasSize(1);
    }

    @Test
    void totalOutageReturnsAvailabilityWithoutInventingZeroUsage() {
        peers.stubFor(get(anyUrl()).atPriority(1).willReturn(aResponse().withStatus(503)));
        var response = http.getForEntity("/api/v1/usage/tenant-a", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().fieldNames()).toIterable().containsExactly("availability");
        assertThat(response.getBody().path("availability").toString())
                .isEqualTo("{\"quota\":false,\"resources\":false,\"billing\":false}");
    }

    @Test
    void malformedResourceEnvelopeMarksOnlyResourcesUnavailable() {
        peers.stubFor(get(urlPathEqualTo("/api/v1/resources")).willReturn(okJson("{\"items\":{}}")));
        JsonNode result = usage();
        assertThat(result.has("resources")).isFalse();
        assertThat(result.path("availability").path("resources").asBoolean()).isFalse();
        assertThat(result.has("quota")).isTrue();
        assertThat(result.has("cost")).isTrue();
    }

    @Test
    void slowOrMalformedBillingCannotFailTheWholeRequest() {
        peers.stubFor(get(urlPathEqualTo("/api/v1/billing/tenants/tenant-a/cost"))
                .willReturn(okJson("{}").withFixedDelay(600)));
        assertThat(usage().path("availability").path("billing").asBoolean()).isFalse();
        peers.stubFor(get(urlPathEqualTo("/api/v1/billing/tenants/tenant-a/cost"))
                .willReturn(okJson("not json")));
        assertThat(usage().path("availability").path("billing").asBoolean()).isFalse();
    }

    @Test
    void invalidTenantIsRejectedBeforeCallingPeers() {
        var response = http.getForEntity("/api/v1/usage/" + "a".repeat(65), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("errors").toString()).contains("tenantId");
        assertThat(peers.getAllServeEvents()).isEmpty();
        assertThat(http.getForObject("/health", JsonNode.class).path("status").asText()).isEqualTo("UP");
    }
}
