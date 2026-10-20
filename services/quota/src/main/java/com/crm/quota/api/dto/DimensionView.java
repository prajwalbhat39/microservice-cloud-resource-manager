package com.crm.quota.api.dto;

/** One quota dimension as seen by a client: its ceiling, current use, and computed headroom. */
public record DimensionView(int limit, int used, int remaining) {

    public static DimensionView of(int limit, int used) {
        return new DimensionView(limit, used, limit - used);
    }
}
