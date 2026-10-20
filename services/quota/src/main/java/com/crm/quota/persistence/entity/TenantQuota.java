package com.crm.quota.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps {@code tenant_quota}, THE contended resource (Condition C, BR1). Deliberately has no
 * {@code @Version} column: concurrency is handled by the atomic conditional UPDATE in
 * {@code QuotaService.reserve}, not by optimistic locking. Usage fields are updated only through
 * that native UPDATE, never by a JPA dirty-check, so a stale managed copy can never write back a
 * wrong count.
 */
@Entity
@Table(name = "tenant_quota")
public class TenantQuota {

    @Id
    @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "cpu_used", nullable = false)
    private int cpuUsed;

    @Column(name = "cpu_limit", nullable = false)
    private int cpuLimit;

    @Column(name = "ram_used_gb", nullable = false)
    private int ramUsedGb;

    @Column(name = "ram_limit_gb", nullable = false)
    private int ramLimitGb;

    @Column(name = "instance_used", nullable = false)
    private int instanceUsed;

    @Column(name = "instance_limit", nullable = false)
    private int instanceLimit;

    protected TenantQuota() {
    }

    public TenantQuota(String tenantId, int cpuLimit, int ramLimitGb, int instanceLimit) {
        this.tenantId = tenantId;
        this.cpuLimit = cpuLimit;
        this.ramLimitGb = ramLimitGb;
        this.instanceLimit = instanceLimit;
    }

    public String getTenantId() {
        return tenantId;
    }

    public int getCpuUsed() {
        return cpuUsed;
    }

    public int getCpuLimit() {
        return cpuLimit;
    }

    public int getRamUsedGb() {
        return ramUsedGb;
    }

    public int getRamLimitGb() {
        return ramLimitGb;
    }

    public int getInstanceUsed() {
        return instanceUsed;
    }

    public int getInstanceLimit() {
        return instanceLimit;
    }
}
