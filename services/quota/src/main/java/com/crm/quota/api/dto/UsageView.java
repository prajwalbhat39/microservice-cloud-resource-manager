package com.crm.quota.api.dto;

/** Usage across all three dimensions (FR-9). Reused by the quota, reserve and release responses. */
public record UsageView(DimensionView cpu, DimensionView ramGb, DimensionView instances) {
}
