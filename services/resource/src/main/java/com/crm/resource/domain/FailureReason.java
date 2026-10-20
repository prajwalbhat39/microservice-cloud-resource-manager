package com.crm.resource.domain;

import com.crm.resource.common.error.DownstreamUnavailableException;
import com.crm.resource.common.error.ErrorCode;
import com.crm.resource.common.error.IllegalStateTransitionException;

/**
 * Builds the {@code resources.failure_reason} string from the exception that triggered saga
 * compensation. The prefix IS evidence (deviation A11): since compensation clears
 * provider_instance_id, this prefix is what tells a reader how far the saga got.
 *
 * <pre>
 * Cause                                          failure_reason                              Reachable with
 * Provider unreachable/503 after retries         PROVIDER_UNAVAILABLE: &lt;detail&gt; (&lt;status&gt;)    step 2 incomplete
 * Billing unreachable/503 after retries          BILLING_UNAVAILABLE: &lt;detail&gt; (&lt;status&gt;)     step 2 complete
 * Illegal lifecycle transition inside the saga   INVALID_STATE_TRANSITION: &lt;from&gt; --&lt;event&gt;--&gt; rejected
 * Any other exception                            INTERNAL_ERROR: &lt;exception class&gt;
 * </pre>
 * Never includes a stack trace or a request payload (NFR-17). Only the first two rows carry the
 * "how far did the saga get" signal; the other two deliberately say nothing about progress — that
 * evidence comes from the saga.compensation.start log line and the WireMock request journal instead.
 *
 * <p>The peer clients build the {@code <detail> (<status>)} part into the message of the
 * {@link DownstreamUnavailableException} they throw (e.g. "billing meter start failed after 3
 * attempts (503)"), so this class only has to add the code prefix.
 */
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
