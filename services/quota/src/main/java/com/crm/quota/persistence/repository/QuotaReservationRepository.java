package com.crm.quota.persistence.repository;

import com.crm.quota.persistence.entity.QuotaReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface QuotaReservationRepository extends JpaRepository<QuotaReservation, java.util.UUID> {

    /** Ledger lookup by the opaque resource id; served by uq_quota_reservations_resource_id (BR7). */
    Optional<QuotaReservation> findByResourceId(String resourceId);

    /** The database state predicate makes concurrent refunds apply once. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE quota_reservations
           SET state = 'RELEASED', released_at = now()
         WHERE resource_id = :resourceId
           AND state = 'RESERVED'
        """, nativeQuery = true)
    int markReleasedIfReserved(@Param("resourceId") String resourceId);

    /** Challenge 1 evidence / demo queries: a tenant's rows in a given state. */
    List<QuotaReservation> findByTenantIdAndState(String tenantId, String state);
}
