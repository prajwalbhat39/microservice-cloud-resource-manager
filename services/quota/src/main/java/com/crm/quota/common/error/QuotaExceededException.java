package com.crm.quota.common.error;

import com.crm.quota.domain.QuotaDimension;

import java.util.List;
import java.util.Map;

/**
 * BR1 / AC-1.3: the atomic conditional UPDATE matched no row. Carries the exhausted dimension(s)
 * as the {@code exhausted[]} RFC 9457 extension member so the Challenge 1 script can assert which
 * limit stopped each rejected request.
 */
public class QuotaExceededException extends BusinessRuleViolationException {

    public QuotaExceededException(String detail, List<QuotaDimension> exhausted) {
        super(ErrorCode.QUOTA_EXCEEDED, detail, Map.of("exhausted", exhausted));
    }
}
