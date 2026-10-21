package com.crm.provider.common.error;

public class ProviderException extends RuntimeException {
    private final ErrorCode code;

    public ProviderException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
