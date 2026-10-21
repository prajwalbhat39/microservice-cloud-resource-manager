package com.crm.billing.service;

import java.math.BigDecimal;
import java.time.YearMonth;

public record TenantCostView(
    String tenantId,
    YearMonth period,
    BigDecimal totalCost
) {
}

