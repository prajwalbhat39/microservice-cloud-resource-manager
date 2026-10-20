package com.crm.resource.common.error;

import java.util.Map;

/** 409 family: INVALID_STATE_TRANSITION. */
public class StateConflictException extends ApiException {
    public StateConflictException(ErrorCode code, String detail) {
        super(code, detail);
    }

    public StateConflictException(ErrorCode code, String detail, Map<String, Object> extensions) {
        super(code, detail, extensions);
    }
}
