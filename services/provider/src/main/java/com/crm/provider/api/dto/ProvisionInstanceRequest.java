package com.crm.provider.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProvisionInstanceRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(max = 256) String clientToken,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"VM", "CONTAINER", "CLUSTER"})
        @NotBlank @Pattern(regexp = "VM|CONTAINER|CLUSTER") String type,
        @NotNull @Min(1) @Max(256) Integer cpu,
        @NotNull @Min(1) @Max(2048) Integer ramGb) {
}
