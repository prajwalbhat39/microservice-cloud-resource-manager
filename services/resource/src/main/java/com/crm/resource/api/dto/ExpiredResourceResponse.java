package com.crm.resource.api.dto;

import java.time.Instant;
import java.util.UUID;

/** R6 item shape (FR-24 support, consumed by the Expiry Worker, U4). */
public record ExpiredResourceResponse(UUID resourceId, String tenantId, Instant expiresAt) {
}
