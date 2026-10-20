package com.crm.billing.service;

import java.time.YearMonth;

public interface BillingService {

    StartMeterResult startMeter(StartMeterCommand command, boolean simulateFailure);

    MeterView stopMeter(String resourceId);

    MeterView getMeter(String resourceId);

    TenantCostView getTenantCost(String tenantId, YearMonth period);
}

