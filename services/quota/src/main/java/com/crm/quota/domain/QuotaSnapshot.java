package com.crm.quota.domain;

/** Remaining quota is derived from usage and limits, never stored separately. */
public record QuotaSnapshot(
        int cpuUsed, int cpuLimit,
        int ramUsedGb, int ramLimitGb,
        int instanceUsed, int instanceLimit) {

    public int cpuRemaining() {
        return cpuLimit - cpuUsed;
    }

    public int ramRemainingGb() {
        return ramLimitGb - ramUsedGb;
    }

    public int instancesRemaining() {
        return instanceLimit - instanceUsed;
    }
}
