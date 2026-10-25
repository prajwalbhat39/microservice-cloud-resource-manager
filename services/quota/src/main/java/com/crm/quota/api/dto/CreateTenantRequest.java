package com.crm.quota.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Tier stays a String so invalid values return business 422 rather than deserialization 400. */
public record CreateTenantRequest(
        @Size(max = 64)
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,63}$",
                 message = "must be lowercase alphanumeric with hyphens, 2-64 chars")
        String tenantId,

        @NotBlank @Size(max = 200)
        String name,

        @NotBlank
        String tier) {
}
