package com.crm.resource.persistence.repository;

import com.crm.resource.persistence.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@code resources}. {@code markTerminatedIfActive} is BR7's gate 1: one winner,
 * however many callers (user + expiry worker + a retry) arrive at once for the same resource.
 */
public interface ResourceRepository extends JpaRepository<Resource, UUID> {

    /** R4: list a tenant's resources, optionally filtered by status. */
    List<Resource> findByTenantIdAndStatus(String tenantId, String status);

    List<Resource> findByTenantId(String tenantId);

    /** R6: ACTIVE resources whose expiry has passed (FR-24). Served by the partial index. */
    @Query(value = """
        SELECT * FROM resources
         WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at < :now
        """, nativeQuery = true)
    List<Resource> findExpired(@Param("now") Instant now);

    /**
     * BR7 gate 1 (functional-design.md 9.6): one winner moves ACTIVE -> TERMINATED, however many
     * callers race. Returns 1 for the winner, 0 for every repeat (including a concurrent one).
     *
     * <p>The status predicate is evaluated by the database as part of the write, under the row
     * lock the write takes, so two simultaneous callers cannot both see ACTIVE and both win.
     * {@code clearAutomatically} drops stale managed copies so a re-read sees TERMINATED.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE resources
           SET status = 'TERMINATED', terminated_at = :now
         WHERE resource_id = :resourceId AND status = 'ACTIVE'
        """, nativeQuery = true)
    int markTerminatedIfActive(@Param("resourceId") UUID resourceId, @Param("now") Instant now);
}
