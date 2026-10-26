package com.crm.resource.domain.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class SagaDispatcher {

    private final ResourceWorkRunner runner;
    private final ResourceWork work;

    public SagaDispatcher(ResourceWorkRunner runner, ResourceWork work) {
        this.runner = runner; this.work = work;
    }

    public void afterCommit(ProvisioningTask task) {
        work.enqueue(task.resourceId(), "PROVISION", task);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Not inside a transaction (e.g. a unit test): nothing to wait for, run now.
            dispatch(task.resourceId());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(task.resourceId());
            }
        });
    }
    private void dispatch(java.util.UUID id) {
        try { runner.dispatch(id); }
        catch (org.springframework.core.task.TaskRejectedException busy) {
            // The scheduled recovery owns work rejected by the in-memory executor.
        }
    }

}
