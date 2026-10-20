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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

import static com.crm.resource.domain.ResourceEvent.ACTIVATED;
import static com.crm.resource.domain.ResourceEvent.INSTANCE_CREATED;
import static com.crm.resource.domain.ResourceEvent.PROVISION_STARTED;
import static com.crm.resource.domain.ResourceEvent.SAGA_FAILED;

/**
 * Challenge 3C (BR6). The forward path is a plain sequence of statements; compensation is its
 * {@code catch} block, calling reverse operations in reverse order. There are deliberately NO
 * saga coordination tables — state lives in {@code resources.status}, {@code reservations.status}
 * and {@code resources.failure_reason}.
 *
 * <p>========================= BUILDING BLOCKS ALREADY CREATED =========================
 * <ul>
 *   <li>{@code lifecycle} — ResourceLifecycle.apply(resourceId, event) / .apply(resourceId,
 *       event, detail) / .clearInstanceId(resourceId) — implement ResourceLifecycle first
 *   <li>{@code providerClient} — .provision(clientToken, type, cpu, ramGb, simulateMode) -&gt;
 *       instanceId; .terminate(instanceId)
 *   <li>{@code billingClient} — .startMeter(resourceId, tenantId, cpu, rate, simulateMode);
 *       .stopMeter(resourceId)
 *   <li>{@code quotaClient} — .release(resourceId)
 *   <li>{@code notificationClient} — .send(tenantId, resourceId, eventType, message) — wrap in
 *       {@code notifyQuietly} so a failure here NEVER re-enters compensation
 *   <li>{@code reservationService} — .complete(reservationId); .fail(reservationId, reason)
 *   <li>ClientToken.of(resourceId) -&gt; stable token for FR-19
 *   <li>FailureReason.of(cause) -&gt; String (implement FailureReason first)
 *   <li>{@code ratePerCpuHour} — injected from {@code billing.rate-per-cpu-hour}
 *       (BILLING_RATE_PER_CPU_HOUR, default 0.12)
 * </ul>
 * ===================================================================================
 */
@Service
public class ProvisioningSaga {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningSaga.class);

    private final ResourceLifecycle lifecycle;
    private final ProviderClient providerClient;
    private final BillingClient billingClient;
    private final QuotaClient quotaClient;
    private final NotificationClient notificationClient;
    private final ReservationService reservationService;
    private final BigDecimal ratePerCpuHour;

    public ProvisioningSaga(ResourceLifecycle lifecycle, ProviderClient providerClient,
                            BillingClient billingClient, QuotaClient quotaClient,
                            NotificationClient notificationClient,
                            ReservationService reservationService,
                            @Value("${billing.rate-per-cpu-hour:0.12}") BigDecimal ratePerCpuHour) {
        this.lifecycle = lifecycle;
        this.providerClient = providerClient;
        this.billingClient = billingClient;
        this.quotaClient = quotaClient;
        this.notificationClient = notificationClient;
        this.reservationService = reservationService;
        this.ratePerCpuHour = ratePerCpuHour;
    }

    /**
     * Runs on the {@code sagaExecutor} pool (AsyncConfig). FIRST STATEMENT must re-seed the MDC
     * from {@code task.correlationId()} (nfr-design.md 3.3 layer (b) — the decorator is layer
     * (a), this field is the explicit backup), in a try/finally that clears it afterward.
     *
     * <p>Forward path (functional-design.md 9.5), tracking {@code instanceId} and
     * {@code meterStarted} as local variables so compensation knows exactly how far the saga got:
     * <ol>
     *   <li>Step 2 PROVISION: {@code lifecycle.apply(resourceId, PROVISION_STARTED)}; call
     *       {@code providerClient.provision(ClientToken.of(resourceId), ...)}; on success
     *       {@code lifecycle.apply(resourceId, INSTANCE_CREATED, instanceId)}
     *   <li>Step 3 START_BILLING: {@code billingClient.startMeter(...)}; on success set
     *       {@code meterStarted = true}
     *   <li>Step 4 ACTIVATE: {@code lifecycle.apply(resourceId, ACTIVATED)};
     *       {@code reservationService.complete(reservationId)}
     *   <li>Step 5 NOTIFY (best effort, FR-33): {@code notifyQuietly(task, "ACTIVE", ...)} —
     *       NEVER let a notification failure enter the catch below
     * </ol>
     * Any exception from steps 2-4 is caught and handed to {@link #compensate}.
     */
    @Async("sagaExecutor")
    public void run(ProvisioningTask task) {
        // Layer (b) of the async-boundary fix (nfr-design.md 3.3): re-seed the MDC from the task.
        if (task.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, task.correlationId());
        }
        String instanceId = null;      // step 2 progress
        boolean meterStarted = false;  // step 3 progress
        try {
            // ---- Step 2: PROVISION -------------------------------------------------
            lifecycle.apply(task.resourceId(), PROVISION_STARTED);                       // -> PROVISIONING
            instanceId = providerClient.provision(ClientToken.of(task.resourceId()),     // FR-19: stable token
                    task.type(), task.cpu(), task.ramGb(), task.simulate().forProvider());
            lifecycle.apply(task.resourceId(), INSTANCE_CREATED, instanceId);            // sets provider_instance_id

            // ---- Step 3: START_BILLING --------------------------------------------
            billingClient.startMeter(task.resourceId().toString(), task.tenantId(), task.cpu(),
                    ratePerCpuHour, task.simulate().forBilling());                       // forced 503 here in Challenge 3C
            meterStarted = true;

            // ---- Step 4: ACTIVATE --------------------------------------------------
            lifecycle.apply(task.resourceId(), ACTIVATED);                               // -> ACTIVE, activated_at (BR4)
            reservationService.complete(task.reservationId());                           // -> COMPLETED
            log.info("saga.completed resourceId={} instanceId={}", task.resourceId(), instanceId);

            // ---- Step 5: NOTIFY (best effort, outside compensation, FR-33) --------
            notifyQuietly(task, "ACTIVE", "Resource " + task.resourceId() + " is ready (" + instanceId + ")");

        } catch (Exception failure) {
            compensate(task, instanceId, meterStarted, failure);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    /**
     * BR6, AC-3.3: reverse order (3 -&gt; 2 -&gt; 1), and only for steps that actually completed.
     * Every compensating call is wrapped in {@code quietly(...)} so one failure does not stop
     * the rest — a half-compensated saga (resource stuck PROVISIONING, quota still held) is
     * worse than one that pushes through every step.
     */
    private void compensate(ProvisioningTask task, String instanceId, boolean meterStarted, Exception cause) {
        String reason = FailureReason.of(cause);
        log.error("saga.compensation.start step={} reason={}", failedStep(instanceId, meterStarted), reason, cause);
        String resourceId = task.resourceId().toString();

        // (1) Undo step 3 first, if it ever succeeded. Idempotent on resourceId.
        if (meterStarted) {
            quietly(() -> billingClient.stopMeter(resourceId), "billing");
        }
        // (2) Undo step 2. Idempotent on instance id; a 404 is success, not a failure.
        if (instanceId != null) {
            quietly(() -> providerClient.terminate(instanceId), "provider");
            quietly(() -> lifecycle.clearInstanceId(task.resourceId()), "clearInstanceId");   // deviation A11
        }
        // (3) Undo step 1 — the quota reservation taken synchronously before the 202 (BR6, AC-3.4).
        //     Exactly-once by the ledger's UNIQUE(resource_id) + state flip (BR7).
        quietly(() -> quotaClient.release(resourceId), "quota");

        // (4) Record the outcome. This is the queryable saga state (AC-3.1, AC-3.5).
        quietly(() -> lifecycle.apply(task.resourceId(), SAGA_FAILED, reason), "lifecycle");   // -> FAILED
        quietly(() -> reservationService.fail(task.reservationId(), reason), "reservation");   // -> FAILED

        // (5) Tell the tenant (AC-3.6). Best effort: a notification failure must not re-enter compensation.
        notifyQuietly(task, "FAILED", reason);
        log.error("saga.compensation.done resourceId={} status=FAILED", task.resourceId());
    }

    /** Which forward step broke, derived from how far the saga got (for the compensation log line). */
    private static String failedStep(String instanceId, boolean meterStarted) {
        if (meterStarted) {
            return "ACTIVATE";
        }
        return instanceId != null ? "START_BILLING" : "PROVISION";
    }

    /**
     * Runs {@code action}; on any exception, logs at ERROR and swallows it, so the remaining
     * compensation steps still run.
     */
    private void quietly(Runnable action, String label) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("saga.compensate.{} failed", label, e);
        }
    }

    /** Best-effort notification: a failure here is logged and NEVER re-enters compensation. */
    private void notifyQuietly(ProvisioningTask task, String eventType, String message) {
        try {
            notificationClient.send(task.tenantId(), task.resourceId().toString(), eventType, message);
        } catch (Exception e) {
            log.error("saga.notify.failed eventType={} resourceId={}", eventType, task.resourceId(), e);
        }
    }
}