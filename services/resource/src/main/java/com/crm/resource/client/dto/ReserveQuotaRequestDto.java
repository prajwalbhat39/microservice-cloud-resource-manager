package com.crm.resource.client.dto;

/** Mirrors Quota's ReserveQuotaRequest shape (POST /api/v1/quota/reservations). */
public record ReserveQuotaRequestDto(String tenantId, String resourceId, String type, int cpu, int ramGb) {
}
