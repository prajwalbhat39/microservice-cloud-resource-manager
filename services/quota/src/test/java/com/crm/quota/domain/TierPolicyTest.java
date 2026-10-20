package com.crm.quota.domain;

import com.crm.quota.common.error.BusinessRuleViolationException;
import com.crm.quota.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BR8 unit test (no Spring context, no database). Proves FREE may not provision CLUSTER and that
 * a bad tier string yields INVALID_TIER (functional-design.md 10, 3.1).
 */
class TierPolicyTest {

    @Test
    void br8_freeTierCannotProvisionCluster() {
        // Arrange: tier FREE, type CLUSTER
        // Act + Assert: throws BusinessRuleViolationException with TIER_FORBIDS_RESOURCE_TYPE (422)
        assertThatThrownBy(() -> TierPolicy.assertTypeAllowed(Tier.FREE, ResourceType.CLUSTER))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(e -> ((BusinessRuleViolationException) e).code())
                .isEqualTo(ErrorCode.TIER_FORBIDS_RESOURCE_TYPE);

        assertThat(ErrorCode.TIER_FORBIDS_RESOURCE_TYPE.status()).isEqualTo(422);
    }

    @Test
    void br8_freeTierMayProvisionVmAndContainer() {
        // BR8 forbids exactly one pairing; the other two types stay allowed on FREE.
        assertThatCode(() -> TierPolicy.assertTypeAllowed(Tier.FREE, ResourceType.VM))
                .doesNotThrowAnyException();
        assertThatCode(() -> TierPolicy.assertTypeAllowed(Tier.FREE, ResourceType.CONTAINER))
                .doesNotThrowAnyException();
    }

    @Test
    void br8_standardTierMayProvisionCluster() {
        // STANDARD + CLUSTER is allowed, no exception. Same for ENTERPRISE.
        assertThatCode(() -> TierPolicy.assertTypeAllowed(Tier.STANDARD, ResourceType.CLUSTER))
                .doesNotThrowAnyException();
        assertThatCode(() -> TierPolicy.assertTypeAllowed(Tier.ENTERPRISE, ResourceType.CLUSTER))
                .doesNotThrowAnyException();
    }

    @Test
    void parseTier_rejectsUnknownStringWith422() {
        // "gold" is not a tier: a business 422 INVALID_TIER, NOT a deserialisation 400.
        assertThatThrownBy(() -> TierPolicy.parseTier("gold"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(e -> ((BusinessRuleViolationException) e).code())
                .isEqualTo(ErrorCode.INVALID_TIER);

        assertThat(ErrorCode.INVALID_TIER.status()).isEqualTo(422);
    }

    @Test
    void parseTier_normalisesCaseAndWhitespace() {
        // The client's casing is not a business concern; the tier value is.
        assertThat(TierPolicy.parseTier("standard")).isEqualTo(Tier.STANDARD);
        assertThat(TierPolicy.parseTier("  Enterprise  ")).isEqualTo(Tier.ENTERPRISE);
        assertThat(TierPolicy.parseTier("FREE")).isEqualTo(Tier.FREE);
    }

    @Test
    void tierLimitsMatchTheFrozenTable() {
        // functional-design.md 10's table is the single source of the limit values (FR-5).
        assertThat(Tier.FREE.cpuLimit()).isEqualTo(2);
        assertThat(Tier.FREE.ramLimitGb()).isEqualTo(4);
        assertThat(Tier.FREE.instanceLimit()).isEqualTo(1);

        assertThat(Tier.STANDARD.cpuLimit()).isEqualTo(20);
        assertThat(Tier.STANDARD.ramLimitGb()).isEqualTo(64);
        assertThat(Tier.STANDARD.instanceLimit()).isEqualTo(10);

        assertThat(Tier.ENTERPRISE.cpuLimit()).isEqualTo(200);
        assertThat(Tier.ENTERPRISE.ramLimitGb()).isEqualTo(512);
        assertThat(Tier.ENTERPRISE.instanceLimit()).isEqualTo(100);
    }
}
