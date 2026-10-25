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

/** Notification errors propagate to the caller, which logs them without undoing resource changes. */
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
