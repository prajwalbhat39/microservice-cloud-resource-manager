package com.crm.quota.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.hibernate.Session;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Savepoint;
import java.util.function.Supplier;

/**
 * Duplicate-key recovery inside one live transaction (functional-design.md 7.2.1).
 *
 * <p>When an INSERT may collide with a UNIQUE/PK constraint and the caller wants to recover
 * (read the existing row and decide replay vs conflict) WITHOUT the whole transaction aborting,
 * a plain try/catch is not enough: on PostgreSQL the failed statement poisons the transaction,
 * so the recovery read fails too. This guard wraps the insert in a SQL SAVEPOINT, rolls back to
 * it on a constraint violation, and lets the caller continue in the same transaction.
 *
 * <p>In Quota this backs the client-supplied tenantId collision (409 TENANT_ALREADY_EXISTS, A2)
 * and the ledger insert in QuotaService.reserve. In the orchestrator the same class backs the
 * idempotency-key insert (Challenge 2) and the reservations/resources inserts. Copied, not
 * shared (NFR-5).
 *
 * <p>Implementation note: the savepoint is taken directly on the JDBC connection that Hibernate
 * is using for the current transaction ({@code Session.doWork}). That connection is the same one
 * the surrounding {@code @Transactional} method is using, so this does not depend on the
 * transaction manager's nested-transaction setting.
 */
@Component
public class SavepointGuard {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Run {@code insert}; if it throws a constraint violation, roll back to the savepoint and run
     * {@code onConflict} in the same still-live transaction, returning its result.
     *
     * <p>The insert MUST flush inside the supplier (e.g. {@code saveAndFlush}); this method also
     * flushes once more as a safety net so a violation can never escape past the savepoint.
     *
     * @param insert     the insert that might collide
     * @param onConflict the recovery read + decision
     * @param <T>        result type
     * @return the insert's result, or the conflict handler's result on collision
     */
    public <T> T runWithSavepoint(Supplier<T> insert, Supplier<T> onConflict) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SavepointGuard requires an active transaction");
        }
        Session session = entityManager.unwrap(Session.class);
        Savepoint[] holder = new Savepoint[1];
        session.doWork(connection -> holder[0] = connection.setSavepoint());
        Savepoint savepoint = holder[0];

        try {
            T result = insert.get();
            entityManager.flush(); // the constraint fires here or not at all
            session.doWork(connection -> connection.releaseSavepoint(savepoint));
            return result;
        } catch (DataIntegrityViolationException | PersistenceException e) {
            if (!isConstraintViolation(e)) {
                throw e;
            }
            // Discard the failed statement; the surrounding transaction is usable again.
            session.doWork(connection -> connection.rollback(savepoint));
            // Forget the entity whose flush failed so the recovery read hits the database.
            entityManager.clear();
            return onConflict.get();
        }
    }

    private static boolean isConstraintViolation(RuntimeException e) {
        if (e instanceof DataIntegrityViolationException) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException) {
                return true;
            }
        }
        return false;
    }
}
