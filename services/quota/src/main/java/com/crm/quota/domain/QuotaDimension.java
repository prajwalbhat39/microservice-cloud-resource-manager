package com.crm.quota.domain;

/**
 * The three dimensions a quota is tracked across. Returned in the {@code exhausted[]} extension
 * member of a 422 QUOTA_EXCEEDED body (AC-1.3) so the Challenge 1 script can assert WHICH limit
 * stopped each rejected request rather than parsing prose.
 */
public enum QuotaDimension {
    CPU,
    RAM_GB,
    INSTANCES
}
