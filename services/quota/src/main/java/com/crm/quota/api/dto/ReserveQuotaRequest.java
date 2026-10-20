package com.crm.quota.api.dto;

import com.crm.quota.common.validation.ValueOfEnum;
import com.crm.quota.domain.ResourceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Q4 inbound DTO (FR-7). The {@code @Max} bounds are syntactic sanity, NOT business rules: a
 * request above the tier limit must be rejected by BR1 with a 422 that names the exhausted limit,
 * not by a 400. 256 is deliberately above the ENTERPRISE ceiling of 200 for that reason.
 */
public record ReserveQuotaRequest(
        @NotBlank @Size(max = 64) String tenantId,
        @NotBlank @Size(max = 64) String resourceId,
        @NotBlank @ValueOfEnum(ResourceType.class) String type,
        @NotNull @Min(1) @Max(256) Integer cpu,
        @NotNull @Min(1) @Max(2048) Integer ramGb) {
}
