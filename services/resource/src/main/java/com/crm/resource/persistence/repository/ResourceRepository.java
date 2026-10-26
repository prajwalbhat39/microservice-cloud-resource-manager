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

    /** Require tenant scope to prevent accidental cross-tenant listings. */
    List<Resource> findByTenantIdAndStatus(String tenantId, String status);

    List<Resource> findByTenantId(String tenantId);

    /** R6: ACTIVE resources whose expiry has passed (FR-24). Served by the partial index. */
    @Query(value = """
        SELECT * FROM resources
         WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at < :now
        """, nativeQuery = true)
    List<Resource> findExpired(@Param("now") Instant now);

    /** The conditional UPDATE selects one termination winner; clear stale managed copies afterward. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE resources
           SET status = 'TERMINATED', terminated_at = :now
         WHERE resource_id = :resourceId AND status = 'ACTIVE'
        """, nativeQuery = true)
    int markTerminatedIfActive(@Param("resourceId") UUID resourceId, @Param("now") Instant now);
}
