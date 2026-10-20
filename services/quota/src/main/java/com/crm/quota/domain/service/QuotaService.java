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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Challenge 1 (concurrency), BR1, BR7, BR8. The reserve path is a single atomic conditional
 * UPDATE whose affected-row count is the decision (no read-then-write); the release path flips the
 * ledger row exactly once. All bodies are stubbed for the owner; algorithms in functional-design.md
 * 7 (reserve) and 8 (release). The SQL belongs in TenantQuotaRepository.
 *
 * <p>========================= BUILDING BLOCKS ALREADY CREATED =========================
 *
 * <p>Injected fields (available in every method):
 * <ul>
 *   <li>{@code tenantRepository}        — TenantRepository
 *   <li>{@code tenantQuotaRepository}   — TenantQuotaRepository
 *   <li>{@code reservationRepository}   — QuotaReservationRepository
 * </ul>
 *
 * <p>Repository methods you can call:
 * <ul>
 *   <li>{@code tenantRepository.findById(String) } -> Optional&lt;Tenant&gt;
 *   <li>{@code tenantQuotaRepository.findById(String) } -> Optional&lt;TenantQuota&gt;
 *   <li>{@code tenantQuotaRepository.tryReserve(tenantId, cpu, ram) } -> int  (1=reserved, 0=rejected) [implement its SQL first]
 *   <li>{@code tenantQuotaRepository.applyRelease(tenantId, cpu, ram) } -> int [implement its SQL first]
 *   <li>{@code reservationRepository.findByResourceId(String) } -> Optional&lt;QuotaReservation&gt;
 *   <li>{@code reservationRepository.save(QuotaReservation) }
 * </ul>
 *
 * <p>Entities (com.crm.quota.persistence.entity):
 * <ul>
 *   <li>Tenant:          getTenantId(), getName(), getTier()  (tier is a String like "STANDARD")
 *   <li>TenantQuota:     getCpuUsed(), getCpuLimit(), getRamUsedGb(), getRamLimitGb(),
 *                        getInstanceUsed(), getInstanceLimit()
 *   <li>QuotaReservation: new QuotaReservation(UUID id, String tenantId, String resourceId,
 *                        int cpu, int ramGb, String state); getReservationId(), getState(),
 *                        getCpu(), getRamGb(), setState(String), setReleasedAt(Instant)
 * </ul>
 *
 * <p>Domain helpers / enums (com.crm.quota.domain):
 * <ul>
 *   <li>Tier.valueOf(String) -> Tier; then .cpuLimit(), .ramLimitGb(), .instanceLimit()
 *   <li>ResourceType.valueOf(String)
 *   <li>ReservationState.RESERVED / ReservationState.RELEASED  (use .name() for the String column)
 *   <li>QuotaDimension.CPU / RAM_GB / INSTANCES
 *   <li>TierPolicy.parseTier(String) -> Tier;  TierPolicy.assertTypeAllowed(Tier, ResourceType)
 * </ul>
 *
 * <p>DTOs (com.crm.quota.api.dto):
 * <ul>
 *   <li>IN  ReserveQuotaRequest:  .tenantId(), .resourceId(), .type(), .cpu(), .ramGb()
 *   <li>OUT ReserveQuotaResponse(UUID reservationId, String tenantId, String resourceId,
 *           String state, int cpu, int ramGb, boolean replay, UsageView usage)
 *   <li>OUT ReleaseQuotaResponse(String resourceId, String state, boolean released,
 *           Instant releasedAt, UsageView usage)
 *   <li>UsageView(DimensionView cpu, DimensionView ramGb, DimensionView instances)
 *   <li>DimensionView.of(int limit, int used)   // computes remaining = limit - used
 * </ul>
 *
 * <p>Exceptions (com.crm.quota.common.error) — throwing any of these yields the right HTTP status:
 * <ul>
 *   <li>new NotFoundException(ErrorCode.TENANT_NOT_FOUND, msg)                      -> 404
 *   <li>new NotFoundException(ErrorCode.QUOTA_RESERVATION_NOT_FOUND, msg)           -> 404
 *   <li>new StateConflictException(ErrorCode.QUOTA_RESERVATION_ALREADY_RELEASED, msg) -> 409
 *   <li>new QuotaExceededException(msg, List&lt;QuotaDimension&gt; exhausted)        -> 422 (adds exhausted[])
 *   <li>new BusinessRuleViolationException(ErrorCode.TIER_FORBIDS_RESOURCE_TYPE, msg) -> 422 (thrown by TierPolicy)
 * </ul>
 * ===================================================================================
 */
@Service
public class QuotaService {

    private final TenantRepository tenantRepository;
    private final TenantQuotaRepository tenantQuotaRepository;
    private final QuotaReservationRepository reservationRepository;

    public QuotaService(TenantRepository tenantRepository,
                        TenantQuotaRepository tenantQuotaRepository,
                        QuotaReservationRepository reservationRepository) {
        this.tenantRepository = tenantRepository;
        this.tenantQuotaRepository = tenantQuotaRepository;
        this.reservationRepository = reservationRepository;
    }

    /**
     * Q4 / Challenge 1 / BR1, BR8. In one transaction: assert the tenant exists (404), enforce BR8
     * (TierPolicy.assertTypeAllowed -> 422), insert the ledger row (UNIQUE(resource_id) guards
     * duplicates -> replay), then run the atomic conditional UPDATE. Zero rows updated -> 422
     * QUOTA_EXCEEDED naming the exhausted dimension(s). A duplicate resourceId whose row is already
     * RELEASED -> 409 QUOTA_RESERVATION_ALREADY_RELEASED (A9).
     *
     * <p>Suggested steps (see the class-level BUILDING BLOCKS list for exact signatures):
     * <ol>
     *   <li>tenantRepository.findById(request.tenantId()).orElseThrow(... TENANT_NOT_FOUND ...)
     *   <li>Tier tier = Tier.valueOf(tenant.getTier()); ResourceType type = ResourceType.valueOf(request.type());
     *       TierPolicy.assertTypeAllowed(tier, type);
     *   <li>reservationRepository.findByResourceId(...) — if present: RELEASED -> 409, else replay (no usage added)
     *   <li>int rows = tenantQuotaRepository.tryReserve(tenantId, cpu, ramGb); if 0 -> QuotaExceededException
     *   <li>reservationRepository.save(new QuotaReservation(UUID.randomUUID(), ..., ReservationState.RESERVED.name()))
     *   <li>build ReserveQuotaResponse with a UsageView from the fresh TenantQuota row
     * </ol>
     */
    @Transactional
    public ReserveQuotaResponse reserve(ReserveQuotaRequest request) {
        Tenant tenant = tenantRepository.findById(request.tenantId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.TENANT_NOT_FOUND,
                        "No tenant with id " + request.tenantId()));

        Tier tier = Tier.valueOf(tenant.getTier());
        ResourceType type = ResourceType.valueOf(request.type());
        TierPolicy.assertTypeAllowed(tier, type);

        Optional<QuotaReservation> existing = reservationRepository.findByResourceId(request.resourceId());
        if (existing.isPresent()) {
            QuotaReservation row = existing.get();
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

    /**
     * AC-1.3: names WHICH limit(s) stopped a rejected reservation, for the {@code exhausted[]}
     * extension member of the 422 body, so a client or a test script can act on the answer
     * instead of parsing the prose in {@code detail}.
     *
     * <p><b>Diagnostic only.</b> This re-reads the quota row AFTER the atomic UPDATE has already
     * made the decision (functional-design.md 7.2). It never decides anything, so the fact that
     * the row could in principle have moved again between the UPDATE and this read is harmless:
     * the mechanism is the affected-row count, and this is only the message. Deliberately keeping
     * it out of the decision is what stops it becoming a second, divergent enforcement point.
     *
     * <p>All three dimensions are checked, so a request that busts several reports all of them.
     */
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

    /**
     * Q5 / BR7. Flip the ledger row RESERVED->RELEASED exactly once (guarded by its state), then
     * decrement usage. A second call returns released=false with no effect. Unknown resource -> 404.
     *
     * <p>Suggested steps:
     * <ol>
     *   <li>reservationRepository.findByResourceId(resourceId).orElseThrow(... QUOTA_RESERVATION_NOT_FOUND ...)
     *   <li>if row.getState() equals RELEASED.name(): return ReleaseQuotaResponse with released=false (no-op)
     *   <li>else: row.setState(RELEASED.name()); row.setReleasedAt(Instant.now()); save;
     *       tenantQuotaRepository.applyRelease(tenantId, row.getCpu(), row.getRamGb())
     *   <li>build ReleaseQuotaResponse with a UsageView from the fresh TenantQuota row
     * </ol>
     */
    @Transactional
    public ReleaseQuotaResponse release(String resourceId) {
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
}
