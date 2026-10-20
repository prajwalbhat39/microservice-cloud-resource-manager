package com.crm.quota.domain;

/**
 * Immutable value object: used/limit/remaining across the three dimensions at one instant.
 * {@code remaining} is always computed ({@code limit - used}), never stored, so it cannot drift
 * from the truth. This is the shape surfaced by the quota usage endpoint (FR-9) and consumed by
 * the Usage aggregator (U4).
 *
 * @param cpuUsed        current CPU in use
 * @param cpuLimit       CPU ceiling for the tier
 * @param ramUsedGb      current RAM in use (GB)
 * @param ramLimitGb     RAM ceiling for the tier (GB)
 * @param instanceUsed   current instance count
 * @param instanceLimit  instance ceiling for the tier
 */
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
