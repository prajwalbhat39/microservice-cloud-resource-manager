package com.crm.notification.domain;

import com.crm.notification.config.DeliverySettings;
import com.crm.notification.persistence.Notification;
import com.crm.notification.persistence.NotificationRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final NotificationRepository repository;
    private final DeliverySimulator delivery;
    private final DeliverySettings settings;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, DeliverySimulator delivery,
            DeliverySettings settings, Clock clock) {
        this.repository = repository;
        this.delivery = delivery;
        this.settings = settings;
        this.clock = clock;
    }

    // shortcut: delivery is at least once; add an event identifier if caller retries need deduplication.
    @Transactional
    public Notification record(String tenantId, String resourceId, EventType eventType, String message) {
        Notification notification = repository.saveAndFlush(
                new Notification(tenantId, resourceId, eventType, message, clock.instant()));
        deliver(notification);
        return notification;
    }

    @Transactional(readOnly = true)
    public List<Notification> history(String tenantId) {
        return repository.findByTenantIdOrderByCreatedAtAscNotificationIdAsc(tenantId);
    }

    // shortcut: process 100 retries per sweep; add paging when sustained backlog exceeds this batch.
    @Transactional(readOnly = true)
    public List<UUID> pending() {
        return repository.findTop100ByStatusAndAttemptsLessThanOrderByCreatedAtAsc(
                DeliveryStatus.PENDING, settings.maxAttempts()).stream()
                .map(Notification::getNotificationId).toList();
    }

    @Transactional
    public void retry(UUID id) {
        repository.findForDelivery(id)
                .filter(n -> n.getStatus() == DeliveryStatus.PENDING && n.getAttempts() < settings.maxAttempts())
                .ifPresent(this::deliver);
    }

    private void deliver(Notification notification) {
        notification.attempt();
        try {
            delivery.send(notification);
            notification.delivered(clock.instant());
        } catch (RuntimeException failure) {
            if (notification.getAttempts() >= settings.maxAttempts()) notification.exhausted();
            log.warn("notification.delivery.failed notificationId={} attempts={} status={} cause={}",
                    notification.getNotificationId(), notification.getAttempts(),
                    notification.getStatus(), failure.getClass().getSimpleName());
        }
    }
}
