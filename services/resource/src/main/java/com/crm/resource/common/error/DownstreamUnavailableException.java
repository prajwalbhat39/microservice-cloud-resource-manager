package com.crm.resource.common.error;

/** Quota failures surface synchronously; Provider and Billing codes describe saga failures. */
public class DownstreamUnavailableException extends ApiException {
    public DownstreamUnavailableException(ErrorCode code, String detail) {
        super(code, detail);
    }
}
