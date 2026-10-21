package com.crm.notification.domain;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationRetry {
    private static final Logger log = LoggerFactory.getLogger(NotificationRetry.class);
    private final NotificationService service;

    public NotificationRetry(NotificationService service) { this.service = service; }

    @Scheduled(fixedDelayString = "${notification.delivery.retry-interval-ms}",
            initialDelayString = "${notification.delivery.retry-interval-ms}")
    public void run() {
        try (var ignored = MDC.putCloseable("correlationId", UUID.randomUUID().toString())) {
            for (UUID id : service.pending()) {
                try {
                    service.retry(id);
                } catch (RuntimeException failure) {
                    log.error("notification.retry.failed notificationId={} cause={}", id,
                            failure.getClass().getSimpleName());
                }
            }
        }
    }
}
