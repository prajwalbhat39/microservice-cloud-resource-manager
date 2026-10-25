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

/** Termination retries cleanup until complete; successful cleanup steps are not undone. */
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
