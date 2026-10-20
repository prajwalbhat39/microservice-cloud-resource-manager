package com.crm.resource.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code resources}: the lifecycle aggregate AND the queryable saga outcome (AC-3.1).
 * {@code tenantId} and {@code providerInstanceId} are opaque foreign identifiers (NFR-3, NFR-4) —
 * no joins, no foreign keys across service boundaries.
 */
@Entity
@Table(name = "resources")
public class Resource {

    @Id
    @Column(name = "resource_id", nullable = false, updatable = false)
    private UUID resourceId;

    @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "type", length = 20, nullable = false, updatable = false)
    private String type;

    @Column(name = "cpu", nullable = false, updatable = false)
    private int cpu;

    @Column(name = "ram_gb", nullable = false, updatable = false)
    private int ramGb;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "provider_instance_id", length = 100)
    private String providerInstanceId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    protected Resource() {
    }

    public Resource(UUID resourceId, String tenantId, String type, int cpu, int ramGb,
                    String status, Instant expiresAt) {
        this.resourceId = resourceId;
        this.tenantId = tenantId;
        this.type = type;
        this.cpu = cpu;
        this.ramGb = ramGb;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getType() {
        return type;
    }

    public int getCpu() {
        return cpu;
    }

    public int getRamGb() {
        return ramGb;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getProviderInstanceId() {
        return providerInstanceId;
    }

    public void setProviderInstanceId(String providerInstanceId) {
        this.providerInstanceId = providerInstanceId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    public Instant getTerminatedAt() {
        return terminatedAt;
    }

    public void setTerminatedAt(Instant terminatedAt) {
        this.terminatedAt = terminatedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
