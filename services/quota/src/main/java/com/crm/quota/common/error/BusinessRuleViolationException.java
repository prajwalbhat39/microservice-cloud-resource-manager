package com.crm.quota.common.error;

import java.util.Map;

/**
 * 422 family: QUOTA_EXCEEDED, TIER_FORBIDS_RESOURCE_TYPE, INVALID_TIER, IDEMPOTENCY_PAYLOAD_MISMATCH.
 * A business rule said no. Distinct from a 400 (syntactic) because the request was well-formed.
 */
public class BusinessRuleViolationException extends ApiException {

    public BusinessRuleViolationException(ErrorCode code, String detail) {
        super(code, detail);
    }

    public BusinessRuleViolationException(ErrorCode code, String detail, Map<String, Object> extensions) {
        super(code, detail, extensions);
    }
}
