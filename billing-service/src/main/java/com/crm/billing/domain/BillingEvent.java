package com.crm.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "billing_events")
public class BillingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    protected BillingEvent() {
    }

    public BillingEvent(Long eventId, String resourceId, EventType eventType, Instant eventTime) {
        this.eventId = eventId;
        this.resourceId = resourceId;
        this.eventType = eventType;
        this.eventTime = eventTime;
    }

    public Long getEventId() {
        return eventId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public Instant getEventTime() {
        return eventTime;
    }
}

