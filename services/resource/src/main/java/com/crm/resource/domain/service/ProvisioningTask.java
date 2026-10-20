package com.crm.resource.domain.service;

import com.crm.resource.domain.SimulateFailureDirective;

import java.util.UUID;

/**
 * The background saga's input (functional-design.md 9.3 step (e), 9.5). Carries
 * {@code correlationId} as a FIELD — not relying solely on the async-executor's MDC decorator —
 * because this is the data half of the two-mechanism fix for the async-boundary correlation
 * problem (nfr-design.md 3.3). The saga re-seeds the MDC from this field as its first statement.
 */
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
