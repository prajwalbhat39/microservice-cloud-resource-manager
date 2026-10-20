package com.crm.resource.common.error;

import java.util.Map;

/**
 * 422 family: IDEMPOTENCY_PAYLOAD_MISMATCH (local); QUOTA_EXCEEDED/TIER_FORBIDS_RESOURCE_TYPE
 * (propagated from Quota, together with Quota's extension members such as {@code exhausted[]};
 * nfr-design.md 2.4).
 */
public class BusinessRuleViolationException extends ApiException {
    public BusinessRuleViolationException(ErrorCode code, String detail) {
        super(code, detail);
    }

    public BusinessRuleViolationException(ErrorCode code, String detail, Map<String, Object> extensions) {
        super(code, detail, extensions);
    }
}
