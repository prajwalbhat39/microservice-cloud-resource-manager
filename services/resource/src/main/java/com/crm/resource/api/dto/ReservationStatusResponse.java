package com.crm.resource.api.dto;

import java.time.Instant;
import java.util.UUID;

/** The instance ID comes from the linked resource and is cleared after compensation. */
public record ReservationStatusResponse(
        UUID reservationId,
        String status,
        UUID resourceId,
        String instanceId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {
}
