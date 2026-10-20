package com.crm.resource.common.error;

/** 400 family: VALIDATION_FAILED, MALFORMED_REQUEST. */
public class BadRequestException extends ApiException {
    public BadRequestException(ErrorCode code, String detail) {
        super(code, detail);
    }
}
