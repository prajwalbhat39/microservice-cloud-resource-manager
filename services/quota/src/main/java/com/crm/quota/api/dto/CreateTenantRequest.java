package com.crm.quota.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Q1 inbound DTO (FR-1). {@code tier} is a String, not an enum, so a bad tier yields the
 * contract's 422 INVALID_TIER from the domain layer rather than Jackson's 400 (functional-design
 * 3.1). {@code tenantId} is optional and client-supplied so the seed script can fix demo ids
 * (FR-4); when absent the service generates {@code tnt-<uuid>}.
 */
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
