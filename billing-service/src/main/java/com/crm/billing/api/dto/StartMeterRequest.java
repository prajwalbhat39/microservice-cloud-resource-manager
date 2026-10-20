package com.crm.billing.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record StartMeterRequest(
    @NotBlank String resourceId,
    @NotBlank String tenantId,
    @Min(1) int cpu,
    @DecimalMin("0.0") BigDecimal ratePerCpuHour
) {
}

