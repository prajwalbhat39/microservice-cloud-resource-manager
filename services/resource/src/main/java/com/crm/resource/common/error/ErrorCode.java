package com.crm.resource.common.error;

/**
 * The complete {@code code} vocabulary (nfr-design.md 2.2), copied verbatim from Quota so the
 * vocabulary is identical across services (NFR-5 permits duplication of this small file; a
 * shared library is what is forbidden).
 *
 * <p>Orchestrator emits: VALIDATION_FAILED, MALFORMED_REQUEST, IDEMPOTENCY_KEY_REQUIRED,
 * TENANT_NOT_FOUND (propagated), RESOURCE_NOT_FOUND, RESERVATION_NOT_FOUND,
 * INVALID_STATE_TRANSITION, QUOTA_EXCEEDED (propagated), TIER_FORBIDS_RESOURCE_TYPE
 * (propagated), IDEMPOTENCY_PAYLOAD_MISMATCH, DOWNSTREAM_UNAVAILABLE, PROVIDER_UNAVAILABLE,
 * BILLING_UNAVAILABLE, NOTIFICATION_UNAVAILABLE, METHOD_NOT_ALLOWED, INTERNAL_ERROR.
 */
public enum ErrorCode {

    // 400
    VALIDATION_FAILED(400),
    MALFORMED_REQUEST(400),
    IDEMPOTENCY_KEY_REQUIRED(400),

    // 404
    TENANT_NOT_FOUND(404),
    RESOURCE_NOT_FOUND(404),
    RESERVATION_NOT_FOUND(404),
    QUOTA_RESERVATION_NOT_FOUND(404),
    METER_NOT_FOUND(404),
    INSTANCE_NOT_FOUND(404),

    // 405
    METHOD_NOT_ALLOWED(405),

    // 409
    TENANT_ALREADY_EXISTS(409),
    INVALID_STATE_TRANSITION(409),
    QUOTA_RESERVATION_ALREADY_RELEASED(409),

    // 422
    QUOTA_EXCEEDED(422),
    TIER_FORBIDS_RESOURCE_TYPE(422),
    INVALID_TIER(422),
    IDEMPOTENCY_PAYLOAD_MISMATCH(422),

    // 503
    DOWNSTREAM_UNAVAILABLE(503),
    PROVIDER_UNAVAILABLE(503),
    BILLING_UNAVAILABLE(503),
    NOTIFICATION_UNAVAILABLE(503),
    SIMULATED_FAILURE(503),

    // 500
    INTERNAL_ERROR(500);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }

    public String typeUri() {
        return "https://crm.example/errors/" + name().toLowerCase().replace('_', '-');
    }
}
