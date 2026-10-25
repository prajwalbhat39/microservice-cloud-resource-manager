package com.crm.billing.service;

import com.crm.billing.config.BillingProperties;
import com.crm.billing.domain.BillingMeter;
import com.crm.billing.domain.MeterState;
import com.crm.billing.persistence.BillingEventRepository;
import com.crm.billing.persistence.BillingMeterRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StoppedCostTest {
    @Test
    void stoppedMeterDoesNotAccrueAnotherHoursCost() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var meter = new BillingMeter(UUID.randomUUID(), "resource", "tenant", 2,
                new BigDecimal("0.12"), MeterState.ACTIVE, clock.instant(), null);
        var meters = mock(BillingMeterRepository.class);
        when(meters.findByResourceId("resource")).thenReturn(Optional.of(meter));
        var billing = new BillingServiceImpl(meters, mock(BillingEventRepository.class),
                new BillingProperties(new BigDecimal("0.12"), BigDecimal.ZERO), clock, () -> 0,
                mock(JdbcTemplate.class));
        clock.setInstant(Instant.parse("2026-01-01T01:00:00Z"));
        billing.stopMeter("resource");
        clock.setInstant(Instant.parse("2026-01-01T02:00:00Z"));
        assertThat(billing.getMeter("resource").currentCost()).isEqualByComparingTo("0.24");
    }
}
