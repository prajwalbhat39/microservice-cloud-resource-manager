package com.crm.quota.domain;

import com.crm.quota.common.error.BusinessRuleViolationException;
import com.crm.quota.common.error.ErrorCode;

public final class TierPolicy {

    private TierPolicy() {
    }

    /** Parse tiers here so unknown values return the required business 422. */
    public static Tier parseTier(String raw) {
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        try {
            return Tier.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolationException(ErrorCode.INVALID_TIER, "Unknown tier: " + raw);
        }
    }


    public static void assertTypeAllowed(Tier tier, ResourceType type) {
        if (tier == Tier.FREE && type == ResourceType.CLUSTER) {
            throw new BusinessRuleViolationException(ErrorCode.TIER_FORBIDS_RESOURCE_TYPE,
                    "Cluster cannot be provisioned for tier " + tier);
        }
    }
}
