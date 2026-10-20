package com.crm.resource.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * One shared UTC {@link Clock} (NFR-22). Services take a Clock instead of calling Instant.now()
 * directly so tests can substitute a fixed clock and assert exact timestamps.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
