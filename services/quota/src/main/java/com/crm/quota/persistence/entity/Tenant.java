package com.crm.quota.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Maps the {@code tenants} table (FR-1, FR-2). Tier is stored as a String and is immutable (FR-3). */
@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "tier", length = 20, nullable = false, updatable = false)
    private String tier;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected Tenant() {
    }

    public Tenant(String tenantId, String name, String tier) {
        this.tenantId = tenantId;
        this.name = name;
        this.tier = tier;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getTier() {
        return tier;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
