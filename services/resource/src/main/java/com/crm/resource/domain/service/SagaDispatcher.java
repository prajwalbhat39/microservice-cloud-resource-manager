package com.crm.resource.domain.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Dispatches a {@link ProvisioningTask} to the saga executor ONLY AFTER the enclosing
 * transaction commits (functional-design.md 9.3, step (e)) — dispatching inside the transaction
 * is a classic bug: the pooled thread can start before the {@code resources} row is visible to
 * it (different connection) and "cannot find" a resource being inserted on another connection.
 *
 * <p>Registers an {@code afterCommit()} synchronization that calls {@code ProvisioningSaga.run(task)}.
 * {@code run} is {@code @Async("sagaExecutor")}, so the call returns immediately on the web thread.
 * If the transaction rolls back, the callback never fires and the saga never starts.
 */
@Component
public class SagaDispatcher {

    private final ProvisioningSaga saga;

    public SagaDispatcher(ProvisioningSaga saga) {
        this.saga = saga;
    }

    public void afterCommit(ProvisioningTask task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Not inside a transaction (e.g. a unit test): nothing to wait for, run now.
            saga.run(task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                saga.run(task);
            }
        });
    }
}
