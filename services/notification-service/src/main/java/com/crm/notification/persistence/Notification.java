package com.crm.notification.persistence;

import com.crm.notification.domain.DeliveryStatus;
import com.crm.notification.domain.EventType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {
    @Id
    private UUID notificationId;
    private String tenantId;
    private String resourceId;
    @Enumerated(EnumType.STRING)
    private EventType eventType;
    @Enumerated(EnumType.STRING)
    private DeliveryStatus status;
    private String message;
    private int attempts;
    private Instant createdAt;
    private Instant sentAt;

    protected Notification() { }

    public Notification(String tenantId, String resourceId, EventType eventType, String message, Instant now) {
        this.notificationId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.resourceId = resourceId;
        this.eventType = eventType;
        this.message = message;
        this.status = DeliveryStatus.PENDING;
        this.createdAt = now;
    }

    public void attempt() { attempts++; }
    public void delivered(Instant now) { status = DeliveryStatus.SENT; sentAt = now; }
    public void exhausted() { status = DeliveryStatus.FAILED; }
    public UUID getNotificationId() { return notificationId; }
    public String getTenantId() { return tenantId; }
    public String getResourceId() { return resourceId; }
    public EventType getEventType() { return eventType; }
    public DeliveryStatus getStatus() { return status; }
    public String getMessage() { return message; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
