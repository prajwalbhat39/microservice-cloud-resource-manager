package com.crm.quota.common.error;

/** 400 family: VALIDATION_FAILED, MALFORMED_REQUEST (and IDEMPOTENCY_KEY_REQUIRED in the orchestrator). */
public class BadRequestException extends ApiException {

    public BadRequestException(ErrorCode code, String detail) {
        super(code, detail);
    }
}
