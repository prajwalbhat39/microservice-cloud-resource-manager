package com.crm.quota.domain;

/**
 * Subscription tier and its fixed quota limits (FR-5). Limits are immutable (FR-3): there is no
 * code path that changes a tenant's tier, so these constants are the single source of the limit
 * values applied when a tenant_quota row is created.
 */
public enum Tier {
    FREE(2, 4, 1),
    STANDARD(20, 64, 10),
    ENTERPRISE(200, 512, 100);

    private final int cpuLimit;
    private final int ramLimitGb;
    private final int instanceLimit;

    Tier(int cpuLimit, int ramLimitGb, int instanceLimit) {
        this.cpuLimit = cpuLimit;
        this.ramLimitGb = ramLimitGb;
        this.instanceLimit = instanceLimit;
    }

    public int cpuLimit() {
        return cpuLimit;
    }

    public int ramLimitGb() {
        return ramLimitGb;
    }

    public int instanceLimit() {
        return instanceLimit;
    }
}
