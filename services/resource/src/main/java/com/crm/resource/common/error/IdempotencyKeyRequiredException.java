package com.crm.resource.common.error;

/** 400 IDEMPOTENCY_KEY_REQUIRED (FR-12): the Idempotency-Key header was absent or blank. */
public class IdempotencyKeyRequiredException extends BadRequestException {
    public IdempotencyKeyRequiredException() {
        super(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "The Idempotency-Key header is mandatory for this operation");
    }
}
