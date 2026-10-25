package com.crm.resource.domain.service;

import com.crm.resource.client.BillingClient;
import com.crm.resource.client.NotificationClient;
import com.crm.resource.client.ProviderClient;
import com.crm.resource.client.QuotaClient;
import com.crm.resource.common.web.CorrelationIdFilter;
import com.crm.resource.domain.ClientToken;
import com.crm.resource.domain.FailureReason;
import com.crm.resource.domain.ResourceEvent;
import com.crm.resource.domain.ResourceLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

import static com.crm.resource.domain.ResourceEvent.ACTIVATED;
import static com.crm.resource.domain.ResourceEvent.INSTANCE_CREATED;
import static com.crm.resource.domain.ResourceEvent.PROVISION_STARTED;
import static com.crm.resource.domain.ResourceEvent.SAGA_FAILED;

@Service
public class ProvisioningSaga {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningSaga.class);

    private final ResourceLifecycle lifecycle;
    private final ProviderClient providerClient;
    private final BillingClient billingClient;
    private final QuotaClient quotaClient;
    private final NotificationClient notificationClient;
    private final BigDecimal ratePerCpuHour;
    private final ResourceWork work;
    private final ProvisioningCompletion completion;

    public ProvisioningSaga(ResourceLifecycle lifecycle, ProviderClient providerClient,
                            BillingClient billingClient, QuotaClient quotaClient,
                            NotificationClient notificationClient,
                            @Value("${billing.rate-per-cpu-hour:0.12}") BigDecimal ratePerCpuHour, ResourceWork work, ProvisioningCompletion completion) {
        this.work = work; this.completion = completion;
        this.lifecycle = lifecycle;
        this.providerClient = providerClient;
        this.billingClient = billingClient;
        this.quotaClient = quotaClient;
        this.notificationClient = notificationClient;
        this.ratePerCpuHour = ratePerCpuHour;
    }

    public void run(ProvisioningTask task) {
        String previousCorrelation = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (task.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, task.correlationId());
        }
        String instanceId = null;      // step 2 progress
        try {
            // ---- Step 2: PROVISION -------------------------------------------------
            lifecycle.begin(task.resourceId());                       // -> PROVISIONING
            instanceId = providerClient.provision(ClientToken.of(task.resourceId()),     // FR-19: stable token
                    task.type(), task.cpu(), task.ramGb(), task.simulate().forProvider());
            lifecycle.apply(task.resourceId(), INSTANCE_CREATED, instanceId);            // sets provider_instance_id

            // ---- Step 3: START_BILLING --------------------------------------------
            billingClient.startMeter(task.resourceId().toString(), task.tenantId(), task.cpu(),
                    ratePerCpuHour, task.simulate().forBilling());                       // forced 503 here in Challenge 3C

            // ---- Step 4: ACTIVATE --------------------------------------------------
            completion.complete(task);                               // -> ACTIVE, activated_at (BR4)
            log.info("saga.completed resourceId={} instanceId={}", task.resourceId(), instanceId);

            // ---- Step 5: NOTIFY (best effort, outside compensation, FR-33) --------
            notifyQuietly(task, "ACTIVE", "Resource " + task.resourceId() + " is ready (" + instanceId + ")");

        } catch (Exception failure) {
            var pending = new ResourceWork.Compensation(task, FailureReason.of(failure));
            work.enqueue(task.resourceId(), "COMPENSATE", pending);
            compensate(pending);
        } finally {
            if (previousCorrelation == null) MDC.remove(CorrelationIdFilter.MDC_KEY);
            else MDC.put(CorrelationIdFilter.MDC_KEY, previousCorrelation);
        }
    }

    public void compensate(ResourceWork.Compensation pending) {
        var task = pending.task();
        String id = task.resourceId().toString();
        log.info("saga.compensation.start resourceId={} reason={}", id, pending.reason());
        PeerCleanup.run(() -> billingClient.stopMeter(id),
                () -> providerClient.cancel(ClientToken.of(task.resourceId()).value()));
        lifecycle.clearInstanceId(task.resourceId());
        quotaClient.release(id);
        completion.failed(task, pending.reason());
        notifyQuietly(task, "FAILED", pending.reason());
        log.info("saga.compensation.done resourceId={} status=FAILED", id);
    }

    private void notifyQuietly(ProvisioningTask task, String eventType, String message) {
        try {
            notificationClient.send(task.tenantId(), task.resourceId().toString(), eventType, message);
        } catch (Exception e) {
            log.error("saga.notify.failed eventType={} resourceId={}", eventType, task.resourceId(), e);
        }
    }
}
