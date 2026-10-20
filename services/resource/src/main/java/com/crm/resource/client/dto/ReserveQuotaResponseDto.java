package com.crm.resource.client.dto;

/** Mirrors Quota's ReserveQuotaResponse shape (only the fields the orchestrator needs). */
public record ReserveQuotaResponseDto(
        String reservationId, String tenantId, String resourceId,
        String state, int cpu, int ramGb, boolean replay) {
}
