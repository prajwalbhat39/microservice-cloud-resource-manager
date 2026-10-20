package com.crm.quota.domain;

import com.crm.quota.common.error.BusinessRuleViolationException;
import com.crm.quota.common.error.ErrorCode;

/**
 * BR8 and tier parsing. Kept as a named domain class (not inline in a controller) so each rule
 * traces to one testable location (DOC-4). Unit-tested with no Spring context and no database.
 *
 * <p>========================= BUILDING BLOCKS ALREADY CREATED =========================
 * <ul>
 *   <li>Tier enum:         Tier.FREE, Tier.STANDARD, Tier.ENTERPRISE; Tier.values();
 *                          each has .cpuLimit(), .ramLimitGb(), .instanceLimit()
 *   <li>ResourceType enum: ResourceType.VM, ResourceType.CONTAINER, ResourceType.CLUSTER
 *   <li>Exception:         new BusinessRuleViolationException(ErrorCode code, String detail)
 *   <li>ErrorCode:         ErrorCode.INVALID_TIER (422), ErrorCode.TIER_FORBIDS_RESOURCE_TYPE (422)
 *   <li>Enum.valueOf:      Tier.valueOf("STANDARD") throws IllegalArgumentException on an unknown
 *                          name — catch it and rethrow as INVALID_TIER so the client gets a 422
 * </ul>
 * ===================================================================================
 */
public final class TierPolicy {

    private TierPolicy() {
    }

    /**
     * Parse a client-supplied tier string into {@link Tier}, raising a business 422 INVALID_TIER
     * (not a 400) when it is not one of FREE/STANDARD/ENTERPRISE. Binding the DTO field as a
     * String and converting here is what lets the contract's 422 be returned instead of
     * Jackson's deserialisation 400 (functional-design.md 3.1).
     *
     * <p>Suggested approach: normalise (trim + uppercase) the input, then try Tier.valueOf(...);
     * catch IllegalArgumentException (and null) and throw
     * new BusinessRuleViolationException(ErrorCode.INVALID_TIER, "...").
     *
     * @param raw the tier string from the request
     * @return the parsed tier
     * @throws BusinessRuleViolationException INVALID_TIER if the string is not a known tier
     */
    public static Tier parseTier(String raw) {
        String normalized = raw.trim().toUpperCase();
        try {
            return Tier.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolationException(ErrorCode.INVALID_TIER, "Unknown tier: " + raw);
        }
    }

    /**
     * BR8: the FREE tier may not provision CLUSTER. Returns normally when the pairing is allowed
     * and throws 422 TIER_FORBIDS_RESOURCE_TYPE when it is not.
     *
     * <p>Suggested approach: if (tier == Tier.FREE and type == ResourceType.CLUSTER) throw
     * new BusinessRuleViolationException(ErrorCode.TIER_FORBIDS_RESOURCE_TYPE, "...").
     *
     * @param tier the tenant's tier
     * @param type the requested resource type
     * @throws BusinessRuleViolationException TIER_FORBIDS_RESOURCE_TYPE when FREE requests CLUSTER
     */
    public static void assertTypeAllowed(Tier tier, ResourceType type) {
        if (tier == Tier.FREE && type == ResourceType.CLUSTER) {
            throw new BusinessRuleViolationException(ErrorCode.TIER_FORBIDS_RESOURCE_TYPE,
                    "Cluster cannot be provisioned for tier " + tier);
        }
    }
}
