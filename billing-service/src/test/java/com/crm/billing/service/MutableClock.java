package com.crm.billing.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public class MutableClock extends Clock {

    private Instant instant;
    private ZoneId zoneId;

    public MutableClock(Instant instant) {
        this.instant = instant;
        this.zoneId = ZoneOffset.UTC;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
        return zoneId;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        this.zoneId = zone;
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}

