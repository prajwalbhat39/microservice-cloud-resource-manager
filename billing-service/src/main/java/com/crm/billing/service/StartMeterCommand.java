package com.crm.billing.service;

import java.math.BigDecimal;

public record StartMeterCommand(
    String resourceId,
    String tenantId,
    int cpu,
    BigDecimal ratePerCpuHour
) {
}

