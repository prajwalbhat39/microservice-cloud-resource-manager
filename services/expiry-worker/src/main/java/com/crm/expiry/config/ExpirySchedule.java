package com.crm.expiry.config;

import com.crm.expiry.domain.ExpirySweep;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "expiry.enabled", havingValue = "true", matchIfMissing = true)
public class ExpirySchedule {
    private final ExpirySweep sweep;

    public ExpirySchedule(ExpirySweep sweep) { this.sweep = sweep; }

    @Scheduled(fixedDelayString = "${expiry.check-interval-ms}", initialDelayString = "${expiry.check-interval-ms}")
    public void run() { sweep.run(); }
}
