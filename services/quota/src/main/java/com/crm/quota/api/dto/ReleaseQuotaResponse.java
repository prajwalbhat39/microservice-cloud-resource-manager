package com.crm.quota.api.dto;

import java.time.Instant;

/**
 * Q5 response (BR7). {@code released=false} with {@code state=RELEASED} is the second-and-later
 * call: 200, no quota effect, idempotent. A resourceId with no ledger row at all is 404.
 */
public record ReleaseQuotaResponse(
        String resourceId,
        String state,
        boolean released,
        Instant releasedAt,
        UsageView usage) {
}
