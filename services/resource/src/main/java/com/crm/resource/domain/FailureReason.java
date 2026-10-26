package com.crm.resource.domain;

import com.crm.resource.common.error.DownstreamUnavailableException;
import com.crm.resource.common.error.ErrorCode;
import com.crm.resource.common.error.IllegalStateTransitionException;

/** Record a stable failure code and safe detail, never a stack trace or request payload. */
public final class FailureReason {

    private static final int MAX_LENGTH = 500;

    private FailureReason() {
    }

    public static String of(Throwable cause) {
        String reason;
        if (cause instanceof DownstreamUnavailableException e
                && (e.code() == ErrorCode.PROVIDER_UNAVAILABLE || e.code() == ErrorCode.BILLING_UNAVAILABLE)) {
            reason = e.code().name() + ": " + e.getMessage();
        } else if (cause instanceof IllegalStateTransitionException e) {
            reason = ErrorCode.INVALID_STATE_TRANSITION.name() + ": " + e.currentStatus()
                    + " --" + (e.event() == null ? "?" : e.event()) + "--> rejected";
        } else {
            String type = cause == null ? "unknown" : cause.getClass().getSimpleName();
            reason = ErrorCode.INTERNAL_ERROR.name() + ": " + type;
        }
        return reason.length() <= MAX_LENGTH ? reason : reason.substring(0, MAX_LENGTH);
    }
}
