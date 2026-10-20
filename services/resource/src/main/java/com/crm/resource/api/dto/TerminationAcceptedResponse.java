package com.crm.resource.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * R5 response (202, FR-21). {@code alreadyTerminated=true} on a repeat call: no provider call,
 * no meter stop, no second quota release.
 */
public record TerminationAcceptedResponse(
        UUID resourceId,
        String status,
        Instant terminatedAt,
        boolean alreadyTerminated) {
}
