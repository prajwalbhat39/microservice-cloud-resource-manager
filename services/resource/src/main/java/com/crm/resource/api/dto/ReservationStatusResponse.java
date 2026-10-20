package com.crm.resource.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * R2 response (FR-16). {@code instanceId} is NOT a column on reservations — it is read from the
 * linked resources row (provider_instance_id) in the same database. After a compensated saga it
 * is null (A11): the instance was terminated and the column cleared, which is the queryable
 * evidence that nothing is left running (AC-3.5).
 */
public record ReservationStatusResponse(
        UUID reservationId,
        String status,
        UUID resourceId,
        String instanceId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {
}
