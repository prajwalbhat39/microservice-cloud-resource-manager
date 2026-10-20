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

    /**
     * BR7, the release gate (functional-design.md 8). Returns 1 for the first release and 0 for
     * every repeat, including a concurrent one.
     *
     * <p>The {@code state = 'RESERVED'} predicate is evaluated by the database as part of the
     * write, under the row lock the write itself takes, so there is no window between deciding
     * and acting. A read-then-save in Java here is NOT equivalent: ten simultaneous callers
     * would all read {@code RESERVED}, all conclude they were first, and all refund the quota.
     */
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
