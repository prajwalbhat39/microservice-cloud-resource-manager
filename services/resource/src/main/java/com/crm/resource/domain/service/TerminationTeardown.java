package com.crm.resource.domain.service;

import com.crm.resource.client.BillingClient;
import com.crm.resource.client.NotificationClient;
import com.crm.resource.client.ProviderClient;
import com.crm.resource.client.QuotaClient;
import com.crm.resource.common.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class TerminationTeardown {

    private static final Logger log = LoggerFactory.getLogger(TerminationTeardown.class);

    private final ProviderClient providerClient;
    private final BillingClient billingClient;
    private final QuotaClient quotaClient;
    private final NotificationClient notificationClient;
    private final ResourceWork work;

    public TerminationTeardown(ProviderClient providerClient, BillingClient billingClient,
                               QuotaClient quotaClient, NotificationClient notificationClient, ResourceWork work) {
        this.work = work;
        this.providerClient = providerClient;
        this.billingClient = billingClient;
        this.quotaClient = quotaClient;
        this.notificationClient = notificationClient;
    }

    public void run(TerminationTask task) {
        String previousCorrelation = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (task.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, task.correlationId());
        }
        try {
            String resourceId = task.resourceId().toString();
            log.info("teardown.start resourceId={}", resourceId);

            PeerCleanup.run(() -> {
                if (task.providerInstanceId() != null) providerClient.terminate(task.providerInstanceId());
            }, () -> billingClient.stopMeter(resourceId));
            quotaClient.release(resourceId);
            work.done(task.resourceId());
            step(() -> notificationClient.send(task.tenantId(), resourceId, "TERMINATED",
                    "Resource " + resourceId + " has been terminated"), "notification.send", resourceId);

            log.info("teardown.done resourceId={}", resourceId);
        } finally {
            if (previousCorrelation == null) MDC.remove(CorrelationIdFilter.MDC_KEY);
            else MDC.put(CorrelationIdFilter.MDC_KEY, previousCorrelation);
        }
    }

    private void step(Runnable action, String label, String resourceId) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("teardown.step.failed step={} resourceId={}", label, resourceId, e);
        }
    }
}
