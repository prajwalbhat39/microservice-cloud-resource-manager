package com.crm.resource.client;

import com.crm.resource.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stubbed against WireMock in U1. Real implementation is U4 (Siddharth)'s Notification service.
 * Best-effort ONLY (FR-32, FR-33): a failure here is logged at ERROR and MUST NOT trigger saga
 * compensation or be re-thrown into the saga's catch block — notification sits outside the
 * compensation path by design (it must never roll back a provisioned, paid-for resource).
 *
 * <p>This class just makes the call and lets a failure propagate as a normal exception. Swallowing
 * is the CALLER's job ({@code ProvisioningSaga.notifyQuietly}, {@code TerminationTeardown}), so
 * the "never unwind a resource over a notification" rule lives at the one place that could unwind.
 */
@Component
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    private final RestClient restClient;
    private final PeerRetry retry;

    public NotificationClient(@Qualifier("notificationRestClientBuilder") RestClient.Builder notificationRestClientBuilder,
                              PeerRetry retry) {
        this.restClient = notificationRestClientBuilder.build();
        this.retry = retry;
    }

    /** POST .../notifications. Always attempted, never retried into the saga's failure path. */
    public void send(String tenantId, String resourceId, String eventType, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("resourceId", resourceId);
        body.put("eventType", eventType);
        body.put("message", message);

        retry.callVoid(ErrorCode.NOTIFICATION_UNAVAILABLE, "notification send", true, () -> {
            restClient.post()
                    .uri("/api/v1/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("notification.sent eventType={} resourceId={}", eventType, resourceId);
        });
    }
}
