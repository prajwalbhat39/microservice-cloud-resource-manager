package com.crm.resource.api.dto;

import java.util.UUID;

/**
 * R1 success response (202, FR-14). 202 means quota is already reserved and the saga is queued —
 * never "we'll check your quota later" (the sync quota reserve ahead of this response is what
 * keeps the Challenge 1 count deterministic).
 */
public record ProvisionAcceptedResponse(
        UUID reservationId,
        UUID resourceId,
        String status,
        String statusUrl) {

    public static ProvisionAcceptedResponse pending(UUID reservationId, UUID resourceId) {
        return new ProvisionAcceptedResponse(reservationId, resourceId, "PENDING",
                "/api/v1/reservations/" + reservationId);
    }
}
