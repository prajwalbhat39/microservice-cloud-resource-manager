package com.crm.resource.domain.service;

import java.util.UUID;

/**
 * Background teardown's input (functional-design.md 9.6). Mirrors ProvisioningTask: carries
 * correlationId as a field for the same async-boundary reason (nfr-design.md 3.3).
 */
public record TerminationTask(UUID resourceId, String tenantId, String providerInstanceId, String correlationId) {
}
