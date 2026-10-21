package com.crm.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "billing_meters")
public class BillingMeter {

    @Id
    @Column(name = "meter_id", nullable = false)
    private UUID meterId;

    @Column(name = "resource_id", nullable = false, unique = true)
    private String resourceId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "cpu", nullable = false)
    private int cpu;

    @Column(name = "rate_per_cpu_hour", nullable = false, precision = 10, scale = 4)
    private BigDecimal ratePerCpuHour;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private MeterState state;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    protected BillingMeter() {
    }

    public BillingMeter(UUID meterId, String resourceId, String tenantId, int cpu, BigDecimal ratePerCpuHour,
                        MeterState state, Instant startedAt, Instant stoppedAt) {
        this.meterId = meterId;
        this.resourceId = resourceId;
        this.tenantId = tenantId;
        this.cpu = cpu;
        this.ratePerCpuHour = ratePerCpuHour;
        this.state = state;
        this.startedAt = startedAt;
        this.stoppedAt = stoppedAt;
    }

    public UUID getMeterId() {
        return meterId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public int getCpu() {
        return cpu;
    }

    public BigDecimal getRatePerCpuHour() {
        return ratePerCpuHour;
    }

    public MeterState getState() {
        return state;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getStoppedAt() {
        return stoppedAt;
    }

    public boolean isStopped() {
        return state == MeterState.STOPPED;
    }

    public void stop(Instant stoppedAtValue) {
        this.state = MeterState.STOPPED;
        this.stoppedAt = stoppedAtValue;
    }
}

