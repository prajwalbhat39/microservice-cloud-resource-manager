package com.crm.notification.domain;

import com.crm.notification.config.DeliverySettings;
import com.crm.notification.persistence.Notification;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DeliverySimulator {
    private static final Logger log = LoggerFactory.getLogger(DeliverySimulator.class);
    private final DeliverySettings settings;

    public DeliverySimulator(DeliverySettings settings) { this.settings = settings; }

    public void send(Notification notification) {
        if (ThreadLocalRandom.current().nextDouble() < settings.failureRate()) {
            throw new IllegalStateException("Simulated delivery failure");
        }
        log.info("notification.sent notificationId={} tenantId={} resourceId={} eventType={}",
                notification.getNotificationId(), notification.getTenantId(),
                notification.getResourceId(), notification.getEventType());
    }
}
