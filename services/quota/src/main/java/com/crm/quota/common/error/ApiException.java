package com.crm.quota.common.error;

import java.util.Map;

/** Extensions become RFC 9457 problem members, such as {@code exhausted}. */
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
