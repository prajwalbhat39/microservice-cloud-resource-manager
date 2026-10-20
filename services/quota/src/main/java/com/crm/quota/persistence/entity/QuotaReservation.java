package com.crm.quota.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code quota_reservations}, the release-exactly-once ledger (BR7). UNIQUE(resource_id) is
 * what makes both reserve and release idempotent; the entity does not re-check in code.
 */
@Entity
@Table(name = "quota_reservations")
public class QuotaReservation {

    @Id
    @Column(name = "reservation_id", nullable = false, updatable = false)
    private UUID reservationId;

    @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "resource_id", length = 64, nullable = false, updatable = false)
    private String resourceId;

    @Column(name = "cpu", nullable = false, updatable = false)
    private int cpu;

    @Column(name = "ram_gb", nullable = false, updatable = false)
    private int ramGb;

    @Column(name = "state", length = 20, nullable = false)
    private String state;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected QuotaReservation() {
    }

    public QuotaReservation(UUID reservationId, String tenantId, String resourceId,
                            int cpu, int ramGb, String state) {
        this.reservationId = reservationId;
        this.tenantId = tenantId;
        this.resourceId = resourceId;
        this.cpu = cpu;
        this.ramGb = ramGb;
        this.state = state;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public int getCpu() {
        return cpu;
    }

    public int getRamGb() {
        return ramGb;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }
}
