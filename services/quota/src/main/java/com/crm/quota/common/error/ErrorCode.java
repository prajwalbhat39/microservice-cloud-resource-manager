package com.crm.quota.common.error;

/**
 * The complete {@code code} vocabulary (nfr-design.md 2.2). This enum is the single source of
 * truth for the whole system and is copied verbatim into every service; a service may only emit
 * the codes listed against it in the design. Each code carries its HTTP status, so a code can
 * never be returned with two different statuses by two different services.
 *
 * <p>The full catalogue is listed here (not only Quota's) so the copied class is identical
 * across services. Quota emits: VALIDATION_FAILED, MALFORMED_REQUEST, TENANT_NOT_FOUND,
 * QUOTA_RESERVATION_NOT_FOUND, TENANT_ALREADY_EXISTS, QUOTA_RESERVATION_ALREADY_RELEASED,
 * QUOTA_EXCEEDED, TIER_FORBIDS_RESOURCE_TYPE, INVALID_TIER, METHOD_NOT_ALLOWED, INTERNAL_ERROR.
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

    /** The RFC 9457 {@code type} URI, derived from the code: kebab-cased under a fixed base. */
    public String typeUri() {
        return "https://crm.example/errors/" + name().toLowerCase().replace('_', '-');
    }
}
