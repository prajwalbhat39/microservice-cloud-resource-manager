package com.crm.resource.domain;

import com.crm.resource.api.dto.ProvisionResourceRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** BR3 unit test (functional-design.md 9.1). */
class RequestFingerprintTest {

    private static ProvisionResourceRequest request(String tenantId, String type, int cpu, int ramGb, Long duration) {
        return new ProvisionResourceRequest(tenantId, type, cpu, ramGb, duration);
    }

    @Test
    void identicalPayloads_sameFingerprint() {
        // Two separate objects with the same values must hash the same, or a legitimate client
        // retry would be read as a payload mismatch.
        String a = RequestFingerprint.of(request("tnt-std-001", "VM", 4, 8, 7200L));
        String b = RequestFingerprint.of(request("tnt-std-001", "VM", 4, 8, 7200L));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void fingerprintIsLowercaseHexSha256() {
        // 64 lowercase hex chars: what the DB column's CHECK (~ '^[0-9a-f]{64}$') accepts.
        String fingerprint = RequestFingerprint.of(request("tnt-std-001", "VM", 4, 8, 7200L));

        assertThat(fingerprint).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    void differentPayloadsDifferentFingerprints() {
        String baseline = RequestFingerprint.of(request("tnt-std-001", "VM", 4, 8, 7200L));

        // Change any ONE field -> a different fingerprint. Every field is part of the intent.
        assertThat(RequestFingerprint.of(request("tnt-std-002", "VM", 4, 8, 7200L))).isNotEqualTo(baseline);
        assertThat(RequestFingerprint.of(request("tnt-std-001", "CONTAINER", 4, 8, 7200L))).isNotEqualTo(baseline);
        assertThat(RequestFingerprint.of(request("tnt-std-001", "VM", 8, 8, 7200L))).isNotEqualTo(baseline);
        assertThat(RequestFingerprint.of(request("tnt-std-001", "VM", 4, 16, 7200L))).isNotEqualTo(baseline);
        assertThat(RequestFingerprint.of(request("tnt-std-001", "VM", 4, 8, 3600L))).isNotEqualTo(baseline);
    }

    @Test
    void absentOptionalFieldIsItsOwnPayload() {
        // A field appearing or disappearing is a payload change (null renders as JSON null),
        // so "no expiry" and "expires in 7200s" can never share a key's answer.
        String withDuration = RequestFingerprint.of(request("tnt-std-001", "VM", 4, 8, 7200L));
        String withoutDuration = RequestFingerprint.of(request("tnt-std-001", "VM", 4, 8, null));

        assertThat(withDuration).isNotEqualTo(withoutDuration);
    }

    @Test
    void keyOrderAndCaseDoNotAffectFingerprint() {
        // Canonicalisation: "vm" vs "VM" is the same intent, and so is a padded tenantId.
        // Key order cannot vary at all, because the canonical JSON is written in a fixed
        // alphabetical order rather than serialised from a map — asserted directly below.
        String upper = RequestFingerprint.of(request("tnt-std-001", "VM", 4, 8, 7200L));
        String lower = RequestFingerprint.of(request("tnt-std-001", "vm", 4, 8, 7200L));
        String padded = RequestFingerprint.of(request("  tnt-std-001  ", "  Vm  ", 4, 8, 7200L));

        assertThat(lower).isEqualTo(upper);
        assertThat(padded).isEqualTo(upper);
    }

    @Test
    void canonicalFormIsTheFrozenShape() {
        // The exact canonical string from functional-design.md 9.1: fixed alphabetical key
        // order, no whitespace, type upper-cased. Pinned so a refactor cannot silently change
        // every stored fingerprint and invalidate live idempotency keys.
        String canonical = RequestFingerprint.canonicalJson(request("tnt-std-001", "vm", 4, 8, 7200L));

        assertThat(canonical).isEqualTo(
                "{\"cpu\":4,\"durationSeconds\":7200,\"ramGb\":8,\"tenantId\":\"tnt-std-001\",\"type\":\"VM\"}");
    }
}
