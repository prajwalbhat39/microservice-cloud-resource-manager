package com.crm.quota.api.dto;

/** Q3 response (FR-9): usage and headroom across all three dimensions. */
public record QuotaUsageResponse(
        String tenantId,
        String tier,
        DimensionView cpu,
        DimensionView ramGb,
        DimensionView instances) {
}
