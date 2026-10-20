package com.crm.quota.domain;

/**
 * The kinds of resource a tenant can request. Relevant to Quota for BR8 (FREE tier may not
 * provision CLUSTER), enforced in {@link TierPolicy}.
 */
public enum ResourceType {
    VM,
    CONTAINER,
    CLUSTER
}
