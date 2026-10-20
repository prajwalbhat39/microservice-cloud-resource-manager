package com.crm.quota.persistence.repository;

import com.crm.quota.persistence.entity.TenantQuota;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@code tenant_quota}. The reserve and release operations are expressed as native
 * atomic UPDATEs (Challenge 1, BR1, BR7) whose affected-row count is the decision.
 * DONE by the owner — see functional-design.md 7 and 8.
 */
public interface TenantQuotaRepository extends JpaRepository<TenantQuota, String> {

    /**
     * BR1 / Challenge 1: the atomic conditional reservation. One statement, no read-then-write.
     * Returns the number of rows updated: 1 = reserved, 0 = at least one limit would be exceeded.
     * Do NOT add a check-then-update in Java; the predicate in SQL is the whole point of Challenge 1.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
    UPDATE tenant_quota
       SET cpu_used      = cpu_used + :cpu,
           ram_used_gb   = ram_used_gb + :ram,
           instance_used = instance_used + 1
     WHERE tenant_id = :tenantId
       AND cpu_used      + :cpu <= cpu_limit
       AND ram_used_gb   + :ram <= ram_limit_gb
       AND instance_used + 1    <= instance_limit
    """, nativeQuery = true)
    int tryReserve(@Param("tenantId") String tenantId,
                   @Param("cpu") int cpu,
                   @Param("ram") int ramGb);

    /**
     * BR7: give back a resource's quota. Decrements usage, floored at zero via GREATEST(0, ...).
     * Called only after the ledger flip to RELEASED has been confirmed, so it runs at most once
     * per resource.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
    UPDATE tenant_quota
       SET cpu_used      = GREATEST(0, cpu_used - :cpu),
           ram_used_gb   = GREATEST(0, ram_used_gb - :ram),
           instance_used = GREATEST(0, instance_used - 1)
     WHERE tenant_id = :tenantId
    """, nativeQuery = true)
    int applyRelease(@Param("tenantId") String tenantId,
                 @Param("cpu") int cpu,
                 @Param("ram") int ramGb);
}
