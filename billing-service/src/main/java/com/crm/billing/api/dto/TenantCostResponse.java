package com.crm.billing.api.dto;

import java.math.BigDecimal;

public record TenantCostResponse(
    String tenantId,
    String period,
    BigDecimal totalCost
) {
}

