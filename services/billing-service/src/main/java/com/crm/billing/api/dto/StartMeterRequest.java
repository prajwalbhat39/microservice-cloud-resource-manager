package com.crm.billing.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record StartMeterRequest(
    @NotBlank @jakarta.validation.constraints.Size(max = 64) String resourceId,
    @NotBlank @jakarta.validation.constraints.Size(max = 64) String tenantId,
    @Min(1) int cpu,
    @DecimalMin("0.0") @Digits(integer = 6, fraction = 4) BigDecimal ratePerCpuHour
) {
}

