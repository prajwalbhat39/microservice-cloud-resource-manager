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
import org.springframework.jdbc.core.JdbcTemplate;
import com.crm.quota.common.error.StateConflictException;

@Service
public class TenantService {
    private final TenantRepository tenantRepository;
    private final TenantQuotaRepository tenantQuotaRepository;
    private final JdbcTemplate jdbc;

    public TenantService(TenantRepository tenantRepository, TenantQuotaRepository tenantQuotaRepository, JdbcTemplate jdbc) {
        this.tenantRepository = tenantRepository;
        this.tenantQuotaRepository = tenantQuotaRepository;
        this.jdbc = jdbc;
    }

    @Transactional
    public TenantResponse create(CreateTenantRequest request) {
        Tier tier = TierPolicy.parseTier(request.tier());
        String id = request.tenantId() != null ? request.tenantId() : "tnt-" + UUID.randomUUID();

        int inserted = jdbc.update("INSERT INTO tenants(tenant_id, name, tier) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                id, request.name(), tier.name());
        if (inserted == 0) {
            throw new StateConflictException(ErrorCode.TENANT_ALREADY_EXISTS, "Tenant " + id + " already exists");
        }
        Tenant savedTenant = tenantRepository.findById(id).orElseThrow();
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
     *
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
     *
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
