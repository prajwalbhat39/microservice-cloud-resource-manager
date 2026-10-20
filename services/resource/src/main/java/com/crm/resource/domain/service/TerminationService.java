package com.crm.resource.domain.service;

import com.crm.resource.common.error.ErrorCode;
import com.crm.resource.common.error.IllegalStateTransitionException;
import com.crm.resource.common.error.NotFoundException;
import com.crm.resource.domain.ResourceEvent;
import com.crm.resource.domain.ResourceLifecycle;
import com.crm.resource.domain.ResourceStatus;
import com.crm.resource.persistence.entity.Resource;
import com.crm.resource.persistence.repository.ResourceRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * FR-20 to FR-22, BR7 gate 1. Termination is NOT a compensating saga (functional-design.md 9.6):
 * TERMINATED is the desired end state, every teardown step is idempotent, and a failed step is
 * logged and retried, never undone — there is nothing to roll back.
 *
 * <p>========================= BUILDING BLOCKS ALREADY CREATED =========================
 * <ul>
 *   <li>{@code resourceRepository} — .findById inherited;
 *       .markTerminatedIfActive(resourceId, now) -&gt; int (1=winner, 0=already terminated;
 *       implement its SQL in ResourceRepository first)
 *   <li>{@code lifecycle} — .canTransition(status, event) -&gt; boolean (pre-check before the DB gate)
 *   <li>new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND, msg) -&gt; 404
 *   <li>new IllegalStateTransitionException(detail, currentStatus) -&gt; 409
 *   <li>{@code teardownDispatcher} — TeardownDispatcher.afterCommit(TerminationTask)
 * </ul>
 * ===================================================================================
 */
@Service
public class TerminationService {

    private final ResourceRepository resourceRepository;
    private final ResourceLifecycle lifecycle;
    private final TeardownDispatcher teardownDispatcher;
    private final Clock clock;

    public TerminationService(ResourceRepository resourceRepository, ResourceLifecycle lifecycle,
                              TeardownDispatcher teardownDispatcher, Clock clock) {
        this.resourceRepository = resourceRepository;
        this.lifecycle = lifecycle;
        this.teardownDispatcher = teardownDispatcher;
        this.clock = clock;
    }

    /**
     * Follow functional-design.md 9.6 exactly:
     * <ol>
     *   <li>findById(resourceId).orElseThrow(404 RESOURCE_NOT_FOUND)
     *   <li>if status == TERMINATED: return TerminationOutcome.alreadyTerminated(resource) (202,
     *       FR-21 — a repeat is NOT an error)
     *   <li>if !lifecycle.canTransition(status, TERMINATE_REQUESTED): throw
     *       IllegalStateTransitionException (409, FR-22, BR4) naming the current status
     *   <li>THE GATE: {@code int flipped = resourceRepository.markTerminatedIfActive(resourceId,
     *       clock.instant());} — one winner, however many callers (user + expiry worker +
     *       retries) race. If 0: return alreadyTerminated (a concurrent caller already won)
     *   <li>{@code teardownDispatcher.afterCommit(new TerminationTask(...))} — reads
     *       {@code MDC.get("correlationId")}
     *   <li>return TerminationOutcome.terminated(resource) — 202
     * </ol>
     */
    @Transactional
    public TerminationOutcome terminate(UUID resourceId) {
        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Resource " + resourceId + " not found"));                          // 404

        ResourceStatus current = ResourceStatus.valueOf(resource.getStatus());
        if (current == ResourceStatus.TERMINATED) {
            return TerminationOutcome.alreadyTerminated(resource);                          // 202, no effects (FR-21)
        }
        if (!lifecycle.canTransition(current, ResourceEvent.TERMINATE_REQUESTED)) {
            throw new IllegalStateTransitionException(                                      // 409 (FR-22, BR4)
                    "Resource " + resourceId + " is " + current + "; termination requires ACTIVE",
                    current.name(), ResourceEvent.TERMINATE_REQUESTED.name());
        }

        // The gate: one winner, however many callers (user + expiry worker + retries) arrive at once.
        int flipped = resourceRepository.markTerminatedIfActive(resourceId, clock.instant());
        Resource fresh = resourceRepository.findById(resourceId).orElseThrow();            // re-read the committed state
        if (flipped == 0) {
            return TerminationOutcome.alreadyTerminated(fresh);                             // a concurrent caller won
        }

        teardownDispatcher.afterCommit(new TerminationTask(resourceId,
                resource.getTenantId(), resource.getProviderInstanceId(), MDC.get("correlationId")));
        return TerminationOutcome.terminated(fresh);                                        // 202
    }
}
