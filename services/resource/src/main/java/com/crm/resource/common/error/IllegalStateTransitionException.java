package com.crm.resource.common.error;

import java.util.Map;

/**
 * 409 INVALID_STATE_TRANSITION (BR4, FR-22). Carries {@code currentStatus} as an RFC 9457
 * extension member so the body names the state the resource was actually in.
 *
 * <p>Also remembers the rejected {@code event} (when known) so the saga can write
 * {@code INVALID_STATE_TRANSITION: <from> --<event>--> rejected} into failure_reason.
 */
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
