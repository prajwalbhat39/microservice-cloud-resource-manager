package com.crm.billing.service;

public record StartMeterResult(
    MeterView meter,
    boolean created
) {
}

