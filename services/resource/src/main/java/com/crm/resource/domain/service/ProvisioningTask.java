package com.crm.resource.domain.service;

import com.crm.resource.domain.SimulateFailureDirective;

import java.util.UUID;

/** Persist the correlation ID with the task so recovery keeps its original trace. */
public record ProvisioningTask(
        UUID reservationId,
        UUID resourceId,
        String tenantId,
        String type,
        int cpu,
        int ramGb,
        SimulateFailureDirective simulate,
        String correlationId) {
}
