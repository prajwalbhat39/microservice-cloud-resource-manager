package com.crm.provider.domain.service;

import com.crm.provider.common.error.ErrorCode;
import com.crm.provider.common.error.ProviderException;
import com.crm.provider.config.ProviderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.random.RandomGenerator;

@Component
public class ProviderSimulator {
    private static final Logger log = LoggerFactory.getLogger(ProviderSimulator.class);
    private final ProviderProperties properties;
    private final RandomGenerator random;
    private final Sleeper sleeper;

    public ProviderSimulator(ProviderProperties properties, RandomGenerator random, Sleeper sleeper) {
        this.properties = properties;
        this.random = random;
        this.sleeper = sleeper;
    }

    public void simulate(String header) {
        String mode = header == null ? "" : header.trim().toLowerCase(Locale.ROOT);
        boolean forceFailure = mode.equals("always-fail") || mode.equals("true");
        boolean forceSuccess = mode.equals("always-succeed") || mode.equals("false") || mode.equals("slow");
        if (!mode.isEmpty() && !forceFailure && !forceSuccess) {
            log.debug("simulate-failure.ignored value={}", mode);
        }
        long delay = mode.equals("slow") ? properties.slowDelayMs()
                : random.nextLong(properties.delayMinMs(), (long) properties.delayMaxMs() + 1);
        log.debug("provider.simulation delayMs={} mode={}", delay, mode);
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ErrorCode.PROVIDER_UNAVAILABLE, "Provider operation was interrupted");
        }
        if (forceFailure || (!forceSuccess && random.nextDouble() < properties.failureRate())) {
            throw new ProviderException(ErrorCode.SIMULATED_FAILURE, "Simulated provider failure");
        }
    }
}
