package com.crm.billing.service;

import com.crm.billing.config.BillingProperties;
import com.crm.billing.domain.BillingEvent;
import com.crm.billing.domain.BillingMeter;
import com.crm.billing.domain.EventType;
import com.crm.billing.domain.MeterState;
import com.crm.billing.persistence.BillingEventRepository;
import com.crm.billing.persistence.BillingMeterRepository;
import com.crm.billing.web.NotFoundException;
import com.crm.billing.web.ServiceUnavailableException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingServiceImpl implements BillingService {

    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    private final BillingMeterRepository meterRepository;
    private final BillingEventRepository eventRepository;
    private final BillingProperties billingProperties;
    private final Clock clock;
    private final DoubleSupplier failureRandomSupplier;

    public BillingServiceImpl(BillingMeterRepository meterRepository,
                              BillingEventRepository eventRepository,
                              BillingProperties billingProperties,
                              Clock billingClock,
                              DoubleSupplier failureRandomSupplier) {
        this.meterRepository = meterRepository;
        this.eventRepository = eventRepository;
        this.billingProperties = billingProperties;
        this.clock = billingClock;
        this.failureRandomSupplier = failureRandomSupplier;
    }

    @Override
    @Transactional
    public StartMeterResult startMeter(StartMeterCommand command, boolean simulateFailure) {
        maybeFail(simulateFailure);

        return meterRepository.findByResourceId(command.resourceId())
            .map(existing -> new StartMeterResult(toMeterView(existing), false))
            .orElseGet(() -> {
                Instant now = Instant.now(clock);
                BillingMeter meter = new BillingMeter(
                    UUID.randomUUID(),
                    command.resourceId(),
                    command.tenantId(),
                    command.cpu(),
                    resolveRate(command.ratePerCpuHour()),
                    MeterState.ACTIVE,
                    now,
                    null
                );

                BillingMeter saved = meterRepository.save(meter);
                eventRepository.save(new BillingEvent(null, command.resourceId(), EventType.START, now));
                return new StartMeterResult(toMeterView(saved), true);
            });
    }

    @Override
    @Transactional
    public MeterView stopMeter(String resourceId) {
        BillingMeter meter = meterRepository.findByResourceId(resourceId)
            .orElseThrow(() -> new NotFoundException("Meter not found for resourceId " + resourceId));

        if (!meter.isStopped()) {
            Instant now = Instant.now(clock);
            meter.stop(now);
            meterRepository.save(meter);
            eventRepository.save(new BillingEvent(null, resourceId, EventType.STOP, now));
        }

        return toMeterView(meter);
    }

    @Override
    @Transactional(readOnly = true)
    public MeterView getMeter(String resourceId) {
        BillingMeter meter = meterRepository.findByResourceId(resourceId)
            .orElseThrow(() -> new NotFoundException("Meter not found for resourceId " + resourceId));
        return toMeterView(meter);
    }

    @Override
    @Transactional(readOnly = true)
    public TenantCostView getTenantCost(String tenantId, YearMonth period) {
        List<BillingMeter> tenantMeters = meterRepository.findByTenantId(tenantId);

        Instant periodStart = period.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant periodEndExclusive = period.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant now = Instant.now(clock);

        BigDecimal total = tenantMeters.stream()
            .map(meter -> costWithinWindow(meter, periodStart, periodEndExclusive, now))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(6, RoundingMode.HALF_UP);

        return new TenantCostView(tenantId, period, total);
    }

    private MeterView toMeterView(BillingMeter meter) {
        Instant now = Instant.now(clock);
        return new MeterView(
            meter.getMeterId(),
            meter.getResourceId(),
            meter.getTenantId(),
            meter.getCpu(),
            meter.getRatePerCpuHour(),
            meter.getState(),
            meter.getStartedAt(),
            meter.getStoppedAt(),
            computeCost(meter, now)
        );
    }

    private BigDecimal resolveRate(BigDecimal requestedRate) {
        if (requestedRate != null) {
            return requestedRate;
        }
        return billingProperties.defaultCpuRatePerHour();
    }

    private void maybeFail(boolean simulateFailure) {
        if (simulateFailure) {
            throw new ServiceUnavailableException("Simulated failure");
        }

        BigDecimal rate = billingProperties.failureRate();
        if (rate.signum() <= 0) {
            return;
        }

        double threshold = rate.doubleValue();
        if (failureRandomSupplier.getAsDouble() < threshold) {
            throw new ServiceUnavailableException("Random simulated failure");
        }
    }

    private BigDecimal computeCost(BillingMeter meter, Instant now) {
        Instant endTime = meter.getStoppedAt() != null ? meter.getStoppedAt() : now;
        long seconds = Math.max(0, Duration.between(meter.getStartedAt(), endTime).getSeconds());

        return meter.getRatePerCpuHour()
            .multiply(BigDecimal.valueOf(meter.getCpu()))
            .multiply(BigDecimal.valueOf(seconds))
            .divide(SECONDS_PER_HOUR, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal costWithinWindow(BillingMeter meter, Instant startInclusive, Instant endExclusive, Instant now) {
        Instant meterEnd = meter.getStoppedAt() != null ? meter.getStoppedAt() : now;

        Instant overlapStart = meter.getStartedAt().isAfter(startInclusive) ? meter.getStartedAt() : startInclusive;
        Instant overlapEnd = meterEnd.isBefore(endExclusive) ? meterEnd : endExclusive;

        if (!overlapEnd.isAfter(overlapStart)) {
            return BigDecimal.ZERO;
        }

        long seconds = Duration.between(overlapStart, overlapEnd).getSeconds();
        return meter.getRatePerCpuHour()
            .multiply(BigDecimal.valueOf(meter.getCpu()))
            .multiply(BigDecimal.valueOf(seconds))
            .divide(SECONDS_PER_HOUR, 6, RoundingMode.HALF_UP);
    }
}

