package com.crm.notification.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("notification.delivery")
public record DeliverySettings(@Min(1) int maxAttempts,
        @DecimalMin("0.0") @DecimalMax("1.0") double failureRate, @Min(1) long retryIntervalMs) { }
