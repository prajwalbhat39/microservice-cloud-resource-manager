package com.crm.resource.common.error;

import java.util.Map;

/** Include the current state and rejected event in an illegal transition error. */
public class IllegalStateTransitionException extends StateConflictException {

    private final String currentStatus;
    private final String event;

    public IllegalStateTransitionException(String detail, String currentStatus) {
        this(detail, currentStatus, null);
    }

    public IllegalStateTransitionException(String detail, String currentStatus, String event) {
        super(ErrorCode.INVALID_STATE_TRANSITION, detail, Map.of("currentStatus", currentStatus));
        this.currentStatus = currentStatus;
        this.event = event;
    }

    public String currentStatus() {
        return currentStatus;
    }

    /** The event that was rejected, or null if the caller did not supply it. */
    public String event() {
        return event;
    }
}
