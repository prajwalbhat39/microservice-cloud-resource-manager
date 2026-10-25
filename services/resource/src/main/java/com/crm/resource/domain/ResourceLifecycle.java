package com.crm.resource.domain;

import com.crm.resource.common.error.ErrorCode;
import com.crm.resource.common.error.IllegalStateTransitionException;
import com.crm.resource.common.error.NotFoundException;
import com.crm.resource.persistence.entity.Resource;
import com.crm.resource.persistence.repository.ResourceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Allowed resource transitions; missing pairs are rejected with 409. */
@Component
public class ResourceLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ResourceLifecycle.class);

    private final ResourceRepository resourceRepository;
    private final Clock clock;
    private final Map<ResourceStatus, Map<ResourceEvent, ResourceStatus>> transitions;

    public ResourceLifecycle(ResourceRepository resourceRepository, Clock clock) {
        this.resourceRepository = resourceRepository;
        this.clock = clock;
        this.transitions = buildTransitionTable();
    }

    private static Map<ResourceStatus, Map<ResourceEvent, ResourceStatus>> buildTransitionTable() {
        Map<ResourceStatus, Map<ResourceEvent, ResourceStatus>> table = new EnumMap<>(ResourceStatus.class);

        table.put(ResourceStatus.REQUESTED, immutable(
                ResourceEvent.PROVISION_STARTED, ResourceStatus.PROVISIONING,
                ResourceEvent.SAGA_FAILED, ResourceStatus.FAILED));

        table.put(ResourceStatus.PROVISIONING, immutable(
                ResourceEvent.INSTANCE_CREATED, ResourceStatus.PROVISIONING,   // self-transition (AC-3.1)
                ResourceEvent.ACTIVATED, ResourceStatus.ACTIVE,                // the ONLY path to ACTIVE (BR4)
                ResourceEvent.SAGA_FAILED, ResourceStatus.FAILED));

        // ACTIVE -> SAGA_FAILED is deliberately absent: once ACTIVE, the saga has committed (FR-33).
        table.put(ResourceStatus.ACTIVE, immutable(
                ResourceEvent.TERMINATE_REQUESTED, ResourceStatus.TERMINATED));

        // TERMINATED is absorbing: a repeated termination is legal and has no effect (FR-21).
        table.put(ResourceStatus.TERMINATED, immutable(
                ResourceEvent.TERMINATE_REQUESTED, ResourceStatus.TERMINATED));

        // FAILED is terminal: no outgoing events.
        table.put(ResourceStatus.FAILED, Map.of());

        return Collections.unmodifiableMap(table);
    }

    /** Builds one immutable row of the table from alternating (event, target) arguments. */
    private static Map<ResourceEvent, ResourceStatus> immutable(Object... eventTargetPairs) {
        Map<ResourceEvent, ResourceStatus> row = new EnumMap<>(ResourceEvent.class);
        for (int i = 0; i < eventTargetPairs.length; i += 2) {
            row.put((ResourceEvent) eventTargetPairs[i], (ResourceStatus) eventTargetPairs[i + 1]);
        }
        return Collections.unmodifiableMap(row);
    }

    @Transactional
    public void begin(UUID resourceId) {
        if (load(resourceId).getStatus().equals(ResourceStatus.REQUESTED.name())) {
            applyInternal(resourceId, ResourceEvent.PROVISION_STARTED, null);
        }
    }

    @Transactional
    public void fail(UUID resourceId, String reason) {
        if (!load(resourceId).getStatus().equals(ResourceStatus.FAILED.name())) {
            applyInternal(resourceId, ResourceEvent.SAGA_FAILED, reason);
        }
    }

    /** Is {@code event} legal from {@code current}? Used by TerminationService before the DB gate. */
    public boolean canTransition(ResourceStatus current, ResourceEvent event) {
        return transitions.getOrDefault(current, Map.of()).containsKey(event);
    }

    /** The resulting status, or throws IllegalStateTransitionException if not on the graph. */
    public ResourceStatus nextStatus(ResourceStatus current, ResourceEvent event) {
        ResourceStatus next = transitions.getOrDefault(current, Map.of()).get(event);
        if (next == null) {
            throw new IllegalStateTransitionException(
                    "Resource is " + current + "; event " + event + " is not allowed from this state",
                    current.name(), event.name());
        }
        return next;
    }


    @Transactional
    public void apply(UUID resourceId, ResourceEvent event) {
        applyInternal(resourceId, event, null);
    }

    /** Detail is the instance ID for INSTANCE_CREATED and the reason for SAGA_FAILED. */
    @Transactional
    public void apply(UUID resourceId, ResourceEvent event, String detail) {
        applyInternal(resourceId, event, detail);
    }

    private void applyInternal(UUID resourceId, ResourceEvent event, String detail) {
        Resource resource = load(resourceId);
        ResourceStatus current = ResourceStatus.valueOf(resource.getStatus());
        ResourceStatus next;
        try {
            next = nextStatus(current, event);
        } catch (IllegalStateTransitionException e) {
            // FR-50: log the attempted (from, event) pair with the correlation id (via MDC).
            log.error("lifecycle.transition.rejected resourceId={} from={} event={}", resourceId, current, event);
            throw e;
        }

        // TERMINATED --TERMINATE_REQUESTED--> TERMINATED is absorbing: no effect (FR-21).
        if (current == ResourceStatus.TERMINATED && event == ResourceEvent.TERMINATE_REQUESTED) {
            return;
        }

        switch (event) {
            case INSTANCE_CREATED -> resource.setProviderInstanceId(detail);
            case ACTIVATED -> resource.setActivatedAt(clock.instant());
            case SAGA_FAILED -> resource.setFailureReason(detail);
            case TERMINATE_REQUESTED -> resource.setTerminatedAt(clock.instant());
            case PROVISION_STARTED -> { /* status change only */ }
        }
        resource.setStatus(next.name());
        resourceRepository.save(resource);
        log.info("lifecycle.transition resourceId={} from={} event={} to={}", resourceId, current, event, next);
    }

    /**
     * Compensation step (2): clears provider_instance_id after the provider instance has been
     * terminated (deviation A11 — the column then correctly reads "nothing is running").
     */
    @Transactional
    public void clearInstanceId(UUID resourceId) {
        Resource resource = load(resourceId);
        resource.setProviderInstanceId(null);
        resourceRepository.save(resource);
    }

    private Resource load(UUID resourceId) {
        return resourceRepository.findById(resourceId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Resource " + resourceId + " not found"));
    }
}
