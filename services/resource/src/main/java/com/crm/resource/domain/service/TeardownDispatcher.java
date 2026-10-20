package com.crm.resource.domain.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Mirrors SagaDispatcher: dispatches a {@link TerminationTask} only after the enclosing
 * transaction commits (functional-design.md 9.6). The teardown itself ({@link TerminationTeardown#run})
 * is {@code @Async("sagaExecutor")}: provider.terminate (skipped if no instance), billing.stopMeter,
 * quota.release, notification.send("TERMINATED"). Every step is idempotent; a failed step is
 * logged and NOT retried-as-compensation (termination is not a rollback — "nothing to roll back").
 */
@Component
public class TeardownDispatcher {

    private final TerminationTeardown teardown;

    public TeardownDispatcher(TerminationTeardown teardown) {
        this.teardown = teardown;
    }

    public void afterCommit(TerminationTask task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            teardown.run(task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                teardown.run(task);
            }
        });
    }
}
