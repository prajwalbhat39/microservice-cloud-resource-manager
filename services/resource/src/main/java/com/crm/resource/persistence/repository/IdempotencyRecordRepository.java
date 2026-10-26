package com.crm.resource.persistence.repository;

import com.crm.resource.persistence.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    /** ON CONFLICT serializes duplicate keys without marking the transaction rollback-only. */
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

    /** Delete by each row's stored expiry inside the caller's transaction. */
    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
