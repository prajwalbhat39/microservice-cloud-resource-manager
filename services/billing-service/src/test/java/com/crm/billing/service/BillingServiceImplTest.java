package com.crm.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crm.billing.config.BillingProperties;
import com.crm.billing.domain.EventType;
import com.crm.billing.domain.MeterState;
import com.crm.billing.persistence.BillingEventRepository;
import com.crm.billing.persistence.BillingMeterRepository;
import com.crm.billing.web.ServiceUnavailableException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@DataJpaTest
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@Import(BillingServiceImplTest.TestConfig.class)
class BillingServiceImplTest {

    @Autowired
    private BillingService billingService;

    @Autowired
    private BillingMeterRepository meterRepository;

    @Autowired
    private BillingEventRepository eventRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv().getOrDefault("BILLING_TEST_DB_URL", "jdbc:postgresql://localhost:5432/billing_test"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("BILLING_TEST_DB_USER", "billing_user"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("BILLING_TEST_DB_PASSWORD", "changeme"));
    }

    @Autowired
    private MutableClock mutableClock;

    @BeforeEach
    void clearData() {
        eventRepository.deleteAll();
        meterRepository.deleteAll();
        mutableClock.setInstant(Instant.parse("2026-10-19T00:00:00Z"));
    }

    @Test
    void meterLifecycle_startCalculateCostStop() {
        StartMeterResult started = billingService.startMeter(
            new StartMeterCommand("res-1", "tenant-1", 2, new BigDecimal("0.12")),
            false
        );

        assertThat(started.created()).isTrue();
        assertThat(started.meter().state()).isEqualTo(MeterState.ACTIVE);
        assertThat(started.meter().currentCost()).isEqualByComparingTo("0.000000");

        mutableClock.setInstant(Instant.parse("2026-10-19T00:30:00Z"));
        MeterView running = billingService.getMeter("res-1");
        assertThat(running.currentCost()).isEqualByComparingTo("0.120000");

        MeterView stopped = billingService.stopMeter("res-1");
        assertThat(stopped.state()).isEqualTo(MeterState.STOPPED);
        assertThat(stopped.currentCost()).isEqualByComparingTo("0.120000");

        mutableClock.setInstant(Instant.parse("2026-10-19T02:00:00Z"));
        MeterView stoppedLater = billingService.getMeter("res-1");
        assertThat(stoppedLater.currentCost()).isEqualByComparingTo("0.120000");
    }

    @Test
    void idempotency_startSameResourceTwice_returnsSameMeter() {
        StartMeterResult first = billingService.startMeter(
            new StartMeterCommand("res-2", "tenant-1", 1, null),
            false
        );

        StartMeterResult second = billingService.startMeter(
            new StartMeterCommand("res-2", "tenant-1", 1, null),
            false
        );

        assertThat(first.meter().meterId()).isEqualTo(second.meter().meterId());
        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(meterRepository.count()).isEqualTo(1);
    }

    @Test
    void costCalculationAccuracy_knownDuration() {
        billingService.startMeter(
            new StartMeterCommand("res-3", "tenant-1", 2, new BigDecimal("0.12")),
            false
        );

        mutableClock.setInstant(Instant.parse("2026-10-19T01:00:00Z"));
        MeterView meter = billingService.getMeter("res-3");

        assertThat(meter.currentCost()).isEqualByComparingTo("0.240000");
    }

    @Test
    void br5_billingStopsWhenResourceIsTerminated_idempotentStop() {
        billingService.startMeter(
            new StartMeterCommand("res-4", "tenant-2", 1, new BigDecimal("0.12")),
            false
        );
        mutableClock.setInstant(Instant.parse("2026-10-19T00:10:00Z"));

        MeterView firstStop = billingService.stopMeter("res-4");
        MeterView secondStop = billingService.stopMeter("res-4");

        assertThat(firstStop.state()).isEqualTo(MeterState.STOPPED);
        assertThat(secondStop.state()).isEqualTo(MeterState.STOPPED);
        assertThat(firstStop.stoppedAt()).isEqualTo(secondStop.stoppedAt());
        assertThat(eventRepository.findByResourceId("res-4")).extracting("eventType")
            .containsExactly(EventType.START, EventType.STOP);
    }

    @Test
    void randomFailureRate_configurableAndHeaderFailure() {
        BillingService failingService = new BillingServiceImpl(
            meterRepository,
            eventRepository,
            new BillingProperties(new BigDecimal("0.12"), new BigDecimal("1.0")),
            mutableClock,
            () -> 0.1, jdbc
        );

        assertThatThrownBy(() -> failingService.startMeter(
            new StartMeterCommand("res-5", "tenant-1", 1, null),
            false
        )).isInstanceOf(ServiceUnavailableException.class);

        assertThatThrownBy(() -> billingService.startMeter(
            new StartMeterCommand("res-6", "tenant-1", 1, null),
            true
        )).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void tenantMonthCost_aggregatesWithinPeriod() {
        billingService.startMeter(new StartMeterCommand("res-7", "tenant-a", 1, new BigDecimal("0.12")), false);

        mutableClock.setInstant(Instant.parse("2026-10-19T02:00:00Z"));
        billingService.stopMeter("res-7");

        TenantCostView tenantCost = billingService.getTenantCost("tenant-a", YearMonth.of(2026, 10));
        assertThat(tenantCost.totalCost()).isEqualByComparingTo("0.240000");
    }

    @Test
    void concurrentStartsAndStopsHaveOneBusinessEffect() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        var command = new StartMeterCommand(id, "race", 2, new BigDecimal("0.12"));
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var starts = java.util.stream.IntStream.range(0, 5).mapToObj(index -> pool.submit(() -> {
                gate.await();
                return billingService.startMeter(command, false);
            })).toList();
            gate.countDown();
            int created = 0;
            for (var start : starts) if (start.get().created()) created++;
            assertThat(created).isEqualTo(1);
            var stops = java.util.stream.IntStream.range(0, 5)
                    .mapToObj(index -> pool.submit(() -> billingService.stopMeter(id))).toList();
            var timestamps = new java.util.HashSet<Instant>();
            for (var stop : stops) timestamps.add(stop.get().stoppedAt());
            assertThat(timestamps).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_events WHERE resource_id = ? AND event_type = 'START'", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_events WHERE resource_id = ? AND event_type = 'STOP'", Integer.class, id)).isEqualTo(1);
        assertThat(billingService.startMeter(command, true).created()).isFalse();
    }

    @Test
    void stopBeforeStartPreventsALateMeterFromCharging() {
        String id = java.util.UUID.randomUUID().toString();
        assertThatThrownBy(() -> billingService.stopMeter(id)).isInstanceOf(com.crm.billing.web.NotFoundException.class);
        assertThatThrownBy(() -> billingService.startMeter(new StartMeterCommand(id, "tenant", 1, null), false))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        BillingService billingService(BillingMeterRepository meterRepository,
                                      BillingEventRepository eventRepository,
                                      BillingProperties billingProperties,
                                      Clock billingClock,
                                      DoubleSupplier failureRandomSupplier, org.springframework.jdbc.core.JdbcTemplate jdbc) {
            return new BillingServiceImpl(meterRepository, eventRepository, billingProperties, billingClock,
                failureRandomSupplier, jdbc);
        }

        @Bean
        BillingProperties billingProperties() {
            return new BillingProperties(new BigDecimal("0.12"), BigDecimal.ZERO);
        }

        @Bean
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-10-19T00:00:00Z"));
        }

        @Bean
        Clock billingClock(MutableClock mutableClock) {
            return mutableClock;
        }

        @Bean
        DoubleSupplier failureRandomSupplier() {
            return () -> 0.99;
        }
    }
}

