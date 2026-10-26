package com.crm.quota.domain.service;

import com.crm.quota.api.dto.ReleaseQuotaResponse;
import com.crm.quota.api.dto.ReserveQuotaRequest;
import com.crm.quota.api.dto.ReserveQuotaResponse;
import com.crm.quota.common.error.ErrorCode;
import com.crm.quota.common.error.NotFoundException;
import com.crm.quota.common.error.QuotaExceededException;
import com.crm.quota.common.error.StateConflictException;
import com.crm.quota.api.dto.DimensionView;
import com.crm.quota.api.dto.UsageView;
import com.crm.quota.domain.QuotaDimension;
import com.crm.quota.domain.ReservationState;
import com.crm.quota.domain.ResourceType;
import com.crm.quota.domain.Tier;
import com.crm.quota.domain.TierPolicy;
import com.crm.quota.persistence.entity.QuotaReservation;
import com.crm.quota.persistence.entity.Tenant;
import com.crm.quota.persistence.entity.TenantQuota;
import com.crm.quota.persistence.repository.QuotaReservationRepository;
import com.crm.quota.persistence.repository.TenantQuotaRepository;
import com.crm.quota.persistence.repository.TenantRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.crm.quota.common.error.BadRequestException;

/** Capacity is decided by an atomic conditional UPDATE; ledger state gates refunds. */
@Service
public class QuotaService {

    private final TenantRepository tenantRepository;
    private final TenantQuotaRepository tenantQuotaRepository;
    private final QuotaReservationRepository reservationRepository;
    private final JdbcTemplate jdbc;

    public QuotaService(TenantRepository tenantRepository,
                        TenantQuotaRepository tenantQuotaRepository,
                        QuotaReservationRepository reservationRepository, JdbcTemplate jdbc) {
        this.tenantRepository = tenantRepository;
        this.tenantQuotaRepository = tenantQuotaRepository;
        this.reservationRepository = reservationRepository;
        this.jdbc = jdbc;
    }

    /** Reservation, quota increment and replay handling share one transaction. */
    @Transactional
    public ReserveQuotaResponse reserve(ReserveQuotaRequest request) {
        lock(request.resourceId());
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM quota_cancellations WHERE resource_id = ?)",
                Boolean.class, request.resourceId()))) {
            throw new StateConflictException(ErrorCode.QUOTA_RESERVATION_ALREADY_RELEASED, "Resource allocation was cancelled");
        }
        Tenant tenant = tenantRepository.findById(request.tenantId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.TENANT_NOT_FOUND,
                        "No tenant with id " + request.tenantId()));

        Tier tier = Tier.valueOf(tenant.getTier());
        ResourceType type = ResourceType.valueOf(request.type());
        TierPolicy.assertTypeAllowed(tier, type);

        Optional<QuotaReservation> existing = reservationRepository.findByResourceId(request.resourceId());
        if (existing.isPresent()) {
            QuotaReservation row = existing.get();
            if (!row.getTenantId().equals(request.tenantId()) || row.getCpu() != request.cpu()
                    || row.getRamGb() != request.ramGb()) {
                throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "resourceId belongs to a different quota allocation");
            }
            if (row.getState().equals(ReservationState.RELEASED.name())) {
                throw new StateConflictException(ErrorCode.QUOTA_RESERVATION_ALREADY_RELEASED,
                        "Quota for resource " + request.resourceId() + " was already released");
            }
            // Already RESERVED -> this is a retry. Return the SAME result, add NO new usage.
            TenantQuota existingQuota = tenantQuotaRepository.findById(request.tenantId()).orElseThrow();
            return toResponse(row, existingQuota, true);
        }

        int rows = tenantQuotaRepository.tryReserve(request.tenantId(), request.cpu(), request.ramGb());
        if (rows == 0) {
            throw new QuotaExceededException(
                    "Requested " + request.cpu() + " CPU / " + request.ramGb()
                            + " GB exceeds remaining quota for tenant " + request.tenantId(),
                    exhaustedDimensions(request));
        }

        QuotaReservation row = new QuotaReservation(UUID.randomUUID(), tenant.getTenantId(),
                request.resourceId(), request.cpu(), request.ramGb(), ReservationState.RESERVED.name());
        reservationRepository.save(row);

        TenantQuota quota = tenantQuotaRepository.findById(request.tenantId()).orElseThrow();
        return toResponse(row, quota, false);
    }

    /** Diagnostic only: this read describes a rejection already decided by the atomic UPDATE. */
    private List<QuotaDimension> exhaustedDimensions(ReserveQuotaRequest request) {
        TenantQuota quota = tenantQuotaRepository.findById(request.tenantId()).orElse(null);
        if (quota == null) {
            return List.of();
        }
        List<QuotaDimension> exhausted = new ArrayList<>(3);
        if (quota.getCpuUsed() + request.cpu() > quota.getCpuLimit()) {
            exhausted.add(QuotaDimension.CPU);
        }
        if (quota.getRamUsedGb() + request.ramGb() > quota.getRamLimitGb()) {
            exhausted.add(QuotaDimension.RAM_GB);
        }
        if (quota.getInstanceUsed() + 1 > quota.getInstanceLimit()) {
            exhausted.add(QuotaDimension.INSTANCES);
        }
        return exhausted;
    }

    /** Shared response builder: both the replay path and the fresh-reservation path need this. */
    private ReserveQuotaResponse toResponse(QuotaReservation row, TenantQuota quota, boolean replay) {
        UsageView usage = new UsageView(
                DimensionView.of(quota.getCpuLimit(), quota.getCpuUsed()),
                DimensionView.of(quota.getRamLimitGb(), quota.getRamUsedGb()),
                DimensionView.of(quota.getInstanceLimit(), quota.getInstanceUsed()));
        return new ReserveQuotaResponse(row.getReservationId(), row.getTenantId(), row.getResourceId(),
                row.getState(), row.getCpu(), row.getRamGb(), replay, usage);
    }

    /** Only the first RESERVED-to-RELEASED transition decrements usage. */
    @Transactional(noRollbackFor = NotFoundException.class)
    public ReleaseQuotaResponse release(String resourceId) {
        lock(resourceId);
        jdbc.update("INSERT INTO quota_cancellations(resource_id) VALUES (?) ON CONFLICT DO NOTHING", resourceId);
        QuotaReservation row = reservationRepository.findByResourceId(resourceId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.QUOTA_RESERVATION_NOT_FOUND,
                        "No quota reservation for resource " + resourceId));

        // Copy what the winner will need: the conditional UPDATE below clears the persistence
        // context, which detaches `row`.
        String tenantId = row.getTenantId();
        int cpu = row.getCpu();
        int ramGb = row.getRamGb();

        // THE GATE (BR7, functional-design.md 8). Exactly one caller can ever see 1 here, however
        // many call concurrently. A read-then-save would let all of them refund the same quota.
        int flipped = reservationRepository.markReleasedIfReserved(resourceId);

        if (flipped == 0) {
            // Already RELEASED: idempotent no-op, and deliberately NO arithmetic on tenant_quota
            // (BR7, AC-3.7). released_at is re-read so a repeat reports the ORIGINAL instant.
            QuotaReservation current = reservationRepository.findByResourceId(resourceId).orElseThrow();
            TenantQuota quota = tenantQuotaRepository.findById(tenantId).orElseThrow();
            return new ReleaseQuotaResponse(resourceId, current.getState(), false,
                    current.getReleasedAt(), usageView(quota));
        }

        // Only the winner subtracts, and it subtracts the amounts stored in the LEDGER row, never
        // amounts supplied by the caller, so a wrong caller cannot corrupt usage.
        tenantQuotaRepository.applyRelease(tenantId, cpu, ramGb);

        QuotaReservation released = reservationRepository.findByResourceId(resourceId).orElseThrow();
        TenantQuota quota = tenantQuotaRepository.findById(tenantId).orElseThrow();
        return new ReleaseQuotaResponse(resourceId, released.getState(), true,
                released.getReleasedAt(), usageView(quota));
    }

    /** Shared usage-figures builder for both release() branches. */
    private UsageView usageView(TenantQuota quota) {
        return new UsageView(
                DimensionView.of(quota.getCpuLimit(), quota.getCpuUsed()),
                DimensionView.of(quota.getRamLimitGb(), quota.getRamUsedGb()),
                DimensionView.of(quota.getInstanceLimit(), quota.getInstanceUsed()));
    }

    private void lock(String resourceId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> { }, resourceId);
    }
}
