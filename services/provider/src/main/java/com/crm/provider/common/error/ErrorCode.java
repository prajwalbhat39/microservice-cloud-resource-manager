package com.crm.provider.common.error;

public enum ErrorCode {
    VALIDATION_FAILED(400),
    MALFORMED_REQUEST(400),
    INSTANCE_NOT_FOUND(404),
    RESOURCE_NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    PROVIDER_UNAVAILABLE(503),
    SIMULATED_FAILURE(503),
    INTERNAL_ERROR(500);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
