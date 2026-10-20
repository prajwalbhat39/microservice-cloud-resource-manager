package com.crm.quota.domain.service;

import com.crm.quota.api.dto.CreateTenantRequest;
import com.crm.quota.api.dto.DimensionView;
import com.crm.quota.api.dto.QuotaUsageResponse;
import com.crm.quota.api.dto.TenantResponse;
import com.crm.quota.common.error.ErrorCode;
import com.crm.quota.common.error.NotFoundException;
import com.crm.quota.domain.Tier;
import com.crm.quota.domain.TierPolicy;
import com.crm.quota.persistence.entity.Tenant;
import com.crm.quota.persistence.entity.TenantQuota;
import com.crm.quota.persistence.repository.TenantQuotaRepository;
import com.crm.quota.persistence.repository.TenantRepository;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant lifecycle (FR-1, FR-2, FR-3). Creating a tenant also creates its tenant_quota row in the
 * same transaction with usage zero and the limits from {@link com.crm.quota.domain.Tier}.
 *
 * <p>All method bodies are intentionally stubbed: the owner (Prajwal) implements them from
 * functional-design.md 3. Signatures, dependencies and transaction boundaries are fixed here.
 *
 * <p>========================= BUILDING BLOCKS ALREADY CREATED =========================
 *
 * <p>Injected fields:
 * <ul>
 *   <li>{@code tenantRepository}       — TenantRepository
 *   <li>{@code tenantQuotaRepository}  — TenantQuotaRepository
 * </ul>
 *
 * <p>Repository methods:
 * <ul>
 *   <li>{@code tenantRepository.findById(String) } -> Optional&lt;Tenant&gt;
 *   <li>{@code tenantRepository.existsById(String) } -> boolean
 *   <li>{@code tenantRepository.save(Tenant) } -> Tenant
 *   <li>{@code tenantQuotaRepository.findById(String) } -> Optional&lt;TenantQuota&gt;
 *   <li>{@code tenantQuotaRepository.save(TenantQuota) } -> TenantQuota
 * </ul>
 *
 * <p>Entities (com.crm.quota.persistence.entity):
 * <ul>
 *   <li>new Tenant(String tenantId, String name, String tier);  getters: getTenantId(), getName(),
 *       getTier(), getCreatedAt()
 *   <li>new TenantQuota(String tenantId, int cpuLimit, int ramLimitGb, int instanceLimit);
 *       getters: getCpuUsed()/getCpuLimit()/getRamUsedGb()/getRamLimitGb()/getInstanceUsed()/getInstanceLimit()
 * </ul>
 *
 * <p>Domain helpers / enums (com.crm.quota.domain):
 * <ul>
 *   <li>TierPolicy.parseTier(String) -> Tier   (throws 422 INVALID_TIER on a bad string)
 *   <li>Tier: .name(), .cpuLimit(), .ramLimitGb(), .instanceLimit()
 * </ul>
 *
 * <p>Persistence helper (com.crm.quota.persistence):
 * <ul>
 *   <li>SavepointGuard.runWithSavepoint(insertSupplier, onConflictSupplier) — for the duplicate
 *       client-supplied id case (A2); implement SavepointGuard first
 * </ul>
 *
 * <p>DTOs (com.crm.quota.api.dto):
 * <ul>
 *   <li>IN  CreateTenantRequest:  .tenantId() (may be null), .name(), .tier()
 *   <li>OUT TenantResponse(String tenantId, String name, String tier,
 *           TenantResponse.Limits limits, Instant createdAt)
 *   <li>     new TenantResponse.Limits(int cpu, int ramGb, int instances)
 *   <li>OUT QuotaUsageResponse(String tenantId, String tier, DimensionView cpu,
 *           DimensionView ramGb, DimensionView instances)
 *   <li>DimensionView.of(int limit, int used)
 * </ul>
 *
 * <p>Exceptions (com.crm.quota.common.error):
 * <ul>
 *   <li>new NotFoundException(ErrorCode.TENANT_NOT_FOUND, msg)        -> 404
 *   <li>new StateConflictException(ErrorCode.TENANT_ALREADY_EXISTS, msg) -> 409
 * </ul>
 *
 * <p>Utilities: {@code java.util.UUID.randomUUID()} for a generated "tnt-" + uuid id when
 * request.tenantId() is null.
 * ===================================================================================
 */
@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final TenantQuotaRepository tenantQuotaRepository;

    public TenantService(TenantRepository tenantRepository, TenantQuotaRepository tenantQuotaRepository) {
        this.tenantRepository = tenantRepository;
        this.tenantQuotaRepository = tenantQuotaRepository;
    }

    /**
     * FR-1. Validate the tier (TierPolicy.parseTier -> 422 INVALID_TIER), generate an id if none
     * was supplied, insert the tenant and its quota row in one transaction. A client-supplied id
     * that collides is recovered via SavepointGuard into 409 TENANT_ALREADY_EXISTS (A2).
     *
     * <p>Suggested steps:
     * <ol>
     *   <li>Tier tier = TierPolicy.parseTier(request.tier());
     *   <li>String id = request.tenantId() != null ? request.tenantId() : "tnt-" + UUID.randomUUID();
     *   <li>tenantRepository.save(new Tenant(id, request.name(), tier.name()));
     *   <li>tenantQuotaRepository.save(new TenantQuota(id, tier.cpuLimit(), tier.ramLimitGb(), tier.instanceLimit()));
     *   <li>return a TenantResponse with Limits from the tier
     *   <li>(hardening: wrap the inserts in SavepointGuard to turn a PK collision into 409 TENANT_ALREADY_EXISTS)
     * </ol>
     */
    @Transactional
    public TenantResponse create(CreateTenantRequest request) {
        Tier tier = TierPolicy.parseTier(request.tier());
        String id = request.tenantId() != null ? request.tenantId() : "tnt-" + UUID.randomUUID();

        Tenant savedTenant = tenantRepository.save(new Tenant(id, request.name(), tier.name()));
        tenantQuotaRepository.save(new TenantQuota(id, tier.cpuLimit(), tier.ramLimitGb(), tier.instanceLimit()));

        return new TenantResponse(
                savedTenant.getTenantId(),
                savedTenant.getName(),
                savedTenant.getTier(),
                new TenantResponse.Limits(tier.cpuLimit(), tier.ramLimitGb(), tier.instanceLimit()),
                savedTenant.getCreatedAt());
    }

    /**
     * FR-2: read a tenant and its tier limits, or 404 TENANT_NOT_FOUND.
     *
     * <p>Suggested steps: findById(tenantId).orElseThrow(... TENANT_NOT_FOUND ...), then map the
     * Tenant plus its Tier limits into a TenantResponse.
     */
    @Transactional(readOnly = true)
    public TenantResponse get(String tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.TENANT_NOT_FOUND,
                        "No tenant with id " + tenantId));

        Tier tier = Tier.valueOf(tenant.getTier());

        return new TenantResponse(
                tenant.getTenantId(),
                tenant.getName(),
                tenant.getTier(),
                new TenantResponse.Limits(tier.cpuLimit(), tier.ramLimitGb(), tier.instanceLimit()),
                tenant.getCreatedAt());
    }

    /**
     * FR-9: current usage and headroom across all three dimensions, or 404 TENANT_NOT_FOUND.
     *
     * <p>Suggested steps: load TenantQuota via tenantQuotaRepository.findById(tenantId) (404 if
     * absent), then build QuotaUsageResponse using DimensionView.of(limit, used) for each of cpu,
     * ramGb and instances.
     */
    @Transactional(readOnly = true)
    public QuotaUsageResponse usage(String tenantId) {
        TenantQuota quota =  tenantQuotaRepository.findById(tenantId).orElseThrow(() -> new NotFoundException(ErrorCode.TENANT_NOT_FOUND, "No tenannt with id" + tenantId));
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow( () -> new NotFoundException(ErrorCode.TENANT_NOT_FOUND, "No tenant found with id" + tenantId));
        return new QuotaUsageResponse(
                quota.getTenantId(),
                tenant.getTier(),
                DimensionView.of(quota.getCpuLimit(), quota.getCpuUsed()),
                DimensionView.of(quota.getRamLimitGb(), quota.getRamUsedGb()),
                DimensionView.of(quota.getInstanceLimit(), quota.getInstanceUsed()));
    }
}
