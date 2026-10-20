package com.crm.resource.common.error;

/**
 * 503 family: the orchestrator's clean-failure answer when a peer is unreachable after retries
 * (NFR-28). {@code DOWNSTREAM_UNAVAILABLE} is what a caller sees on the SYNCHRONOUS path (Quota
 * unreachable); {@code PROVIDER_UNAVAILABLE}/{@code BILLING_UNAVAILABLE} are used as
 * {@code failure_reason} prefixes inside the saga, not as HTTP responses.
 */
public class DownstreamUnavailableException extends ApiException {
    public DownstreamUnavailableException(ErrorCode code, String detail) {
        super(code, detail);
    }
}
