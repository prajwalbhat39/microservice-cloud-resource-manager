package com.crm.resource.persistence.repository;

import com.crm.resource.persistence.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Challenge 2. The PK insert (via {@code save}/{@code saveAndFlush}, wrapped by SavepointGuard)
 * IS the concurrency guard — nothing here needs a native query for that part.
 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    /**
     * BR2's concurrency guard (Challenge 2). Returns 1 when THIS caller inserted the row and 0
     * when the key was already taken — so the affected-row count, decided by the database, is
     * what tells fresh from duplicate. Nothing is read before writing.
     *
     * <p>Concurrency semantics are exactly what functional-design.md 9.2 describes: when the
     * conflicting row is still uncommitted, the second inserter BLOCKS on the primary key until
     * the first transaction ends, then reports 0 if it committed (so the loser replays) or
     * inserts successfully if it rolled back (so the loser proceeds).
     *
     * <p><b>Why ON CONFLICT rather than catching the violation.</b> Letting the duplicate key
     * raise a constraint violation through Hibernate's flush is unrecoverable in-transaction:
     * Hibernate marks its {@code EntityTransaction} rollback-only, and the commit then fails with
     * {@code UnexpectedRollbackException} no matter how the failed statement is rolled back. A
     * SAVEPOINT does not help, because that flag lives above JDBC and has no public reset. Making
     * the insert not fail in the first place keeps the transaction clean and lets the replay read
     * proceed normally. Recorded as a deviation from functional-design.md 7.2.1.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        INSERT INTO idempotency_records
               (idempotency_key, request_fingerprint, reservation_id, state, expires_at)
        VALUES (:key, :fingerprint, CAST(:reservationId AS uuid), 'IN_PROGRESS', :expiresAt)
        ON CONFLICT (idempotency_key) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("key") String key,
                       @Param("fingerprint") String fingerprint,
                       @Param("reservationId") String reservationId,
                       @Param("expiresAt") Instant expiresAt);

    /**
     * nfr-design.md 9, D5: the retention sweep. Deletes rows past their own expires_at, so the
     * row's horizon (computed at insert from IDEMPOTENCY_RETENTION_HOURS) and the sweep's
     * predicate can never disagree.
     *
     * <p>Called by {@code IdempotencyService.sweepExpired}, a {@code @Scheduled} job that runs
     * every IDEMPOTENCY_SWEEP_INTERVAL_MS. Must run inside a transaction (the caller provides it).
     */
    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
