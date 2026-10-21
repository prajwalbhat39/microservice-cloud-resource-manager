package com.crm.provider.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "provider")
public record ProviderProperties(
        @DecimalMin("0.0") @DecimalMax("1.0") double failureRate,
        @Min(0) int delayMinMs,
        @Min(0) int delayMaxMs,
        @Min(0) int slowDelayMs) {

    @AssertTrue(message = "delay-max-ms must be greater than or equal to delay-min-ms")
    public boolean isDelayRangeValid() {
        return delayMaxMs >= delayMinMs;
    }
}
