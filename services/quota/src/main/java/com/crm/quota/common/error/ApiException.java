package com.crm.quota.common.error;

import java.util.Map;

/**
 * Abstract root of every anticipated API failure. One root means the advice has one primary
 * handler and the catalogue of failures is enumerable by reading this package. Every subclass
 * carries its {@link ErrorCode}, which carries the HTTP status.
 *
 * <p>{@code extensions} are RFC 9457 extension members added to the problem body (e.g.
 * {@code exhausted[]} for QUOTA_EXCEEDED, {@code currentStatus} for INVALID_STATE_TRANSITION).
 */
public abstract class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> extensions;

    protected ApiException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    protected ApiException(ErrorCode code, String detail, Map<String, Object> extensions) {
        super(detail);
        this.code = code;
        this.extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> extensions() {
        return extensions;
    }
}
