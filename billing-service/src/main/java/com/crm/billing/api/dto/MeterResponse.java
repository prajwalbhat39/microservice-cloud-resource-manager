package com.crm.billing.api.dto;

import com.crm.billing.domain.MeterState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MeterResponse(
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

