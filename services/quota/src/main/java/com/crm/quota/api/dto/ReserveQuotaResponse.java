package com.crm.quota.api.dto;

import java.util.UUID;

/**
 * Q4 response (200, not 201: the endpoint is idempotent on resourceId). {@code replay=true} means
 * the ledger already held a RESERVED row for this resource and no usage was added (NFR-27).
 */
public record ReserveQuotaResponse(
        UUID reservationId,
        String tenantId,
        String resourceId,
        String state,
        int cpu,
        int ramGb,
        boolean replay,
        UsageView usage) {
}
