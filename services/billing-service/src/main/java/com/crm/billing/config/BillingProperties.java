package com.crm.billing.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "billing")
public record BillingProperties(
    @NotNull @DecimalMin("0.0") BigDecimal defaultCpuRatePerHour,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal failureRate
) {
}

