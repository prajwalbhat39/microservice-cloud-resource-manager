package com.crm.resource.domain.service;

import com.crm.resource.domain.ResourceEvent;
import com.crm.resource.domain.ResourceLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProvisioningCompletion {
    private final ResourceLifecycle lifecycle;
    private final ReservationService reservations;
    private final ResourceWork work;

    public ProvisioningCompletion(ResourceLifecycle lifecycle, ReservationService reservations, ResourceWork work) {
        this.lifecycle = lifecycle; this.reservations = reservations; this.work = work;
    }

    @Transactional
    public void complete(ProvisioningTask task) {
        lifecycle.apply(task.resourceId(), ResourceEvent.ACTIVATED);
        reservations.complete(task.reservationId());
        work.done(task.resourceId());
    }

    @Transactional
    public void failed(ProvisioningTask task, String reason) {
        lifecycle.fail(task.resourceId(), reason);
        reservations.fail(task.reservationId(), reason);
        work.done(task.resourceId());
    }
}
