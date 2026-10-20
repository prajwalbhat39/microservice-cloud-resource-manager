package com.crm.quota.common.error;

import java.util.Map;

/** 409 family: TENANT_ALREADY_EXISTS, INVALID_STATE_TRANSITION, QUOTA_RESERVATION_ALREADY_RELEASED. */
public class StateConflictException extends ApiException {

    public StateConflictException(ErrorCode code, String detail) {
        super(code, detail);
    }

    public StateConflictException(ErrorCode code, String detail, Map<String, Object> extensions) {
        super(code, detail, extensions);
    }
}
