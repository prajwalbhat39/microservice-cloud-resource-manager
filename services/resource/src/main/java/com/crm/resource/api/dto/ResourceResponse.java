package com.crm.resource.api.dto;

import java.time.Instant;
import java.util.UUID;

/** R3 response (FR-17). */
public record ResourceResponse(
        UUID resourceId,
        String tenantId,
        String type,
        int cpu,
        int ramGb,
        String status,
        String providerInstanceId,
        Instant createdAt,
        Instant activatedAt,
        Instant expiresAt,
        Instant terminatedAt,
        String failureReason) {
}
