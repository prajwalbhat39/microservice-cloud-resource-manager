package com.crm.billing.config;

import java.time.Clock;
import java.util.function.DoubleSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BillingBeansConfig {

    @Bean
    public Clock billingClock() {
        return Clock.systemUTC();
    }

    @Bean
    public DoubleSupplier failureRandomSupplier() {
        return Math::random;
    }
}

