package com.crm.resource.api.dto;

import com.crm.resource.common.validation.ValueOfEnum;
import com.crm.resource.domain.ResourceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * R1 inbound DTO (FR-11). {@code @Max(256)} is syntactic sanity, not a business rule — a request
 * above the tier limit is rejected by Quota's BR1 with a 422 naming the exhausted limit, not by
 * a 400 here. {@code durationSeconds} floor of 30s matches the expiry sweep interval (FR-23).
 */
public record ProvisionResourceRequest(
        @NotBlank @Size(max = 64) String tenantId,
        @NotBlank @ValueOfEnum(ResourceType.class) String type,
        @NotNull @Min(1) @Max(256) Integer cpu,
        @NotNull @Min(1) @Max(2048) Integer ramGb,
        @Min(30) @Max(2_592_000) Long durationSeconds) {
}
