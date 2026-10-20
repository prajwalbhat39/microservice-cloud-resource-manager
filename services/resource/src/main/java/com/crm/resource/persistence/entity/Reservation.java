package com.crm.resource.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code reservations}: the request-level async handle returned by the 202 (FR-14, FR-16).
 * {@code updatedAt} has exactly one writer: {@code ReservationService.complete}/{@code .fail},
 * which set status + failureReason + updatedAt in one UPDATE. No trigger — see
 * functional-design.md 9.5.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @Column(name = "reservation_id", nullable = false, updatable = false)
    private UUID reservationId;

    @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "simulate_failure", length = 40, updatable = false)
    private String simulateFailure;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Reservation() {
    }

    public Reservation(UUID reservationId, String tenantId, UUID resourceId, String status,
                       String simulateFailure) {
        this.reservationId = reservationId;
        this.tenantId = tenantId;
        this.resourceId = resourceId;
        this.status = status;
        this.simulateFailure = simulateFailure;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getSimulateFailure() {
        return simulateFailure;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
