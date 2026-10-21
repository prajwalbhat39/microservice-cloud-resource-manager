package com.crm.billing.service;

import com.crm.billing.domain.MeterState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MeterView(
    UUID meterId,
    String resourceId,
    String tenantId,
    int cpu,
    BigDecimal ratePerCpuHour,
    MeterState state,
    Instant startedAt,
    Instant stoppedAt,
    BigDecimal currentCost
) {
}

