package com.crm.quota.api.dto;

import java.time.Instant;

/** Q1/Q2 response (FR-1, FR-2). Limits are the immutable tier limits (FR-5). */
public record TenantResponse(
        String tenantId,
        String name,
        String tier,
        Limits limits,
        Instant createdAt) {

    public record Limits(int cpu, int ramGb, int instances) {
    }
}
