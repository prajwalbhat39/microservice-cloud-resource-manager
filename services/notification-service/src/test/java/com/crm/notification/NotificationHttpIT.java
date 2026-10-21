package com.crm.notification;

import com.crm.notification.domain.*;
import com.crm.notification.persistence.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test-pg")
class NotificationHttpIT {
    @Autowired TestRestTemplate http;
    @Autowired NotificationRepository repository;
    @Autowired NotificationService service;
    @MockitoBean DeliverySimulator delivery;

    @BeforeEach
    void cleanTestDatabase() { repository.deleteAll(); }

    private Map<String, Object> event(String tenant, String type) {
        return Map.of("tenantId", tenant, "resourceId", UUID.randomUUID().toString(),
                "eventType", type, "message", "Resource lifecycle event");
    }

    @Test
    void recordsAllLifecycleEventsAndIsolatesTenantHistory() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "u4-notification-trace");
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (String type : new String[]{"ACTIVE", "FAILED", "TERMINATED"}) {
            var response = http.exchange("/api/v1/notifications", HttpMethod.POST,
                    new HttpEntity<>(event("tenant-a", type), headers), JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("u4-notification-trace");
            assertThat(response.getBody().path("status").asText()).isEqualTo("SENT");
            assertThat(response.getBody().path("sentAt").asText()).endsWith("Z");
        }
        http.postForEntity("/api/v1/notifications", event("tenant-b", "ACTIVE"), JsonNode.class);
        JsonNode history = http.getForObject("/api/v1/notifications?tenantId=tenant-a", JsonNode.class);
        assertThat(history).hasSize(3);
        assertThat(repository.count()).isEqualTo(4);
        assertThat(http.getForObject("/health", JsonNode.class).path("components").path("db").path("status").asText())
                .isEqualTo("UP");
    }

    @Test
    void scheduledRetryRecoversPersistedDelivery() {
        doThrow(new IllegalStateException("offline")).doNothing().when(delivery).send(any());
        var response = http.postForEntity("/api/v1/notifications", event("tenant", "ACTIVE"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID id = UUID.fromString(response.getBody().path("notificationId").asText());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Notification saved = repository.findById(id).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(DeliveryStatus.SENT);
            assertThat(saved.getAttempts()).isEqualTo(2);
        });
    }

    @Test
    void scheduledRetryStopsAfterBudgetIsExhausted() {
        doThrow(new IllegalStateException("offline")).when(delivery).send(any());
        var response = http.postForEntity("/api/v1/notifications", event("tenant", "FAILED"), JsonNode.class);
        UUID id = UUID.fromString(response.getBody().path("notificationId").asText());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(DeliveryStatus.FAILED));
        assertThat(service.pending()).doesNotContain(id);
        verify(delivery, times(3)).send(any());
    }

    @Test
    void concurrentRetryCannotDeliverAnAlreadySentRowAgain() throws Exception {
        Notification saved = repository.saveAndFlush(new Notification("tenant", "resource", EventType.ACTIVE,
                "Ready", java.time.Instant.now()));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> service.retry(saved.getNotificationId()));
            var second = pool.submit(() -> service.retry(saved.getNotificationId()));
            first.get();
            second.get();
        }
        assertThat(repository.findById(saved.getNotificationId()).orElseThrow().getAttempts()).isEqualTo(1);
        verify(delivery, times(1)).send(any());
    }

    @Test
    void invalidRequestsUseProblemDetailsAndWriteNothing() {
        var response = http.postForEntity("/api/v1/notifications",
                Map.of("tenantId", "", "resourceId", "resource", "eventType", "ACTIVE", "message", ""), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().path("errors").toString()).contains("tenantId", "message");
        assertThat(repository.count()).isZero();
        var missingTenant = http.getForEntity("/api/v1/notifications", JsonNode.class);
        assertThat(missingTenant.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var blankTenant = http.getForEntity("/api/v1/notifications?tenantId=", JsonNode.class);
        assertThat(blankTenant.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blankTenant.getBody().path("errors").toString()).contains("tenantId");
    }
    @Test
    void frameworkErrorsKeepTheProblemContract() {
        var missing = http.getForEntity("/api/v1/missing", JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().path("code").asText()).isEqualTo("ENDPOINT_NOT_FOUND");
        var wrongMethod = http.exchange("/api/v1/notifications", HttpMethod.PUT, HttpEntity.EMPTY, JsonNode.class);
        assertThat(wrongMethod.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(wrongMethod.getBody().path("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        var wrongType = http.postForEntity("/api/v1/notifications", new HttpEntity<>("plain text", headers), JsonNode.class);
        assertThat(wrongType.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(wrongType.getBody().path("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(wrongType.getBody().path("correlationId").asText()).isNotBlank();
        assertThat(wrongType.getBody().path("timestamp").asText()).endsWith("Z");
        assertThat(repository.count()).isZero();
    }

}
