package com.crm.resource.domain.service;

import com.crm.resource.api.dto.ExpiredResourceResponse;
import com.crm.resource.api.dto.ResourceListResponse;
import com.crm.resource.api.dto.ResourceResponse;
import com.crm.resource.common.error.BadRequestException;
import com.crm.resource.common.error.ErrorCode;
import com.crm.resource.common.error.NotFoundException;
import com.crm.resource.domain.ResourceStatus;
import com.crm.resource.persistence.entity.Resource;
import com.crm.resource.persistence.repository.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * FR-17, FR-18, FR-24 support (R3, R4, R6 — read-only, no state change).
 *
 *
 */
@Service
public class ResourceQueryService {

    private final ResourceRepository resourceRepository;
    private final Clock clock;

    public ResourceQueryService(ResourceRepository resourceRepository, Clock clock) {
        this.resourceRepository = resourceRepository;
        this.clock = clock;
    }

    /** R3: read one resource, 404 if absent. */
    @Transactional(readOnly = true)
    public ResourceResponse get(UUID resourceId) {
        return resourceRepository.findById(resourceId)
                .map(ResourceQueryService::toResponse)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Resource " + resourceId + " not found"));
    }

    /** Require tenant scope to prevent accidental cross-tenant listings. */
    @Transactional(readOnly = true)
    public ResourceListResponse list(String tenantId, String status) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "tenantId is required");
        }
        List<Resource> found;
        if (status == null || status.isBlank()) {
            found = resourceRepository.findByTenantId(tenantId);
        } else {
            ResourceStatus parsed = parseStatus(status);
            found = resourceRepository.findByTenantIdAndStatus(tenantId, parsed.name());
        }
        return ResourceListResponse.of(found.stream().map(ResourceQueryService::toResponse).toList());
    }

    /** R6: ACTIVE resources past their expiry, for the Expiry Worker (U4) to terminate. */
    @Transactional(readOnly = true)
    public List<ExpiredResourceResponse> expired() {
        return resourceRepository.findExpired(clock.instant()).stream()
                .map(r -> new ExpiredResourceResponse(r.getResourceId(), r.getTenantId(), r.getExpiresAt()))
                .toList();
    }

    private static ResourceStatus parseStatus(String status) {
        try {
            return ResourceStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "status must be one of " + Arrays.toString(ResourceStatus.values()));
        }
    }

    private static ResourceResponse toResponse(Resource r) {
        return new ResourceResponse(
                r.getResourceId(), r.getTenantId(), r.getType(), r.getCpu(), r.getRamGb(),
                r.getStatus(), r.getProviderInstanceId(), r.getCreatedAt(), r.getActivatedAt(),
                r.getExpiresAt(), r.getTerminatedAt(), r.getFailureReason());
    }
}
