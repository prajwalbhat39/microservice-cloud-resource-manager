package com.crm.resource.common.error;

/** 422 IDEMPOTENCY_PAYLOAD_MISMATCH (BR3): the key was reused with a different request fingerprint. */
public class IdempotencyPayloadMismatchException extends BusinessRuleViolationException {
    public IdempotencyPayloadMismatchException(String key) {
        super(ErrorCode.IDEMPOTENCY_PAYLOAD_MISMATCH,
                "Idempotency-Key " + key + " was already used with a different request body");
    }
}
