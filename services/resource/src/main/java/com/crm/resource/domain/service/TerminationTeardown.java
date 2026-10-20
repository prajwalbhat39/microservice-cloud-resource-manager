package com.crm.resource.domain.service;

import com.crm.resource.client.BillingClient;
import com.crm.resource.client.NotificationClient;
import com.crm.resource.client.ProviderClient;
import com.crm.resource.client.QuotaClient;
import com.crm.resource.common.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Background teardown after a termination has been committed (functional-design.md 9.6).
 * Mirror image of provisioning, every step idempotent:
 * <ol>
 *   <li>providerClient.terminate(instanceId) — skipped when the resource never got an instance
 *   <li>billingClient.stopMeter(resourceId)
 *   <li>quotaClient.release(resourceId) — exactly once by Quota's ledger flip (BR7)
 *   <li>notificationClient.send(..., "TERMINATED", ...) — best effort (FR-32, FR-33)
 * </ol>
 * Termination is NOT a compensating saga: TERMINATED is the desired end state, so a failed step is
 * logged at ERROR and the remaining steps still run; nothing is undone.
 */
@Component
public class TerminationTeardown {

    private static final Logger log = LoggerFactory.getLogger(TerminationTeardown.class);

    private final ProviderClient providerClient;
    private final BillingClient billingClient;
    private final QuotaClient quotaClient;
    private final NotificationClient notificationClient;

    public TerminationTeardown(ProviderClient providerClient, BillingClient billingClient,
                               QuotaClient quotaClient, NotificationClient notificationClient) {
        this.providerClient = providerClient;
        this.billingClient = billingClient;
        this.quotaClient = quotaClient;
        this.notificationClient = notificationClient;
    }

    @Async("sagaExecutor")
    public void run(TerminationTask task) {
        // Layer (b) of the async-boundary fix (nfr-design.md 3.3): re-seed the MDC from the task.
        if (task.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, task.correlationId());
        }
        try {
            String resourceId = task.resourceId().toString();
            log.info("teardown.start resourceId={}", resourceId);

            if (task.providerInstanceId() != null) {
                step(() -> providerClient.terminate(task.providerInstanceId()), "provider.terminate", resourceId);
            }
            step(() -> billingClient.stopMeter(resourceId), "billing.stopMeter", resourceId);
            step(() -> quotaClient.release(resourceId), "quota.release", resourceId);
            step(() -> notificationClient.send(task.tenantId(), resourceId, "TERMINATED",
                    "Resource " + resourceId + " has been terminated"), "notification.send", resourceId);

            log.info("teardown.done resourceId={}", resourceId);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    /** Runs one idempotent step; a failure is logged and the remaining steps still run. */
    private void step(Runnable action, String label, String resourceId) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("teardown.step.failed step={} resourceId={}", label, resourceId, e);
        }
    }
}
