package com.crm.resource.domain;

import com.crm.resource.api.dto.ProvisionResourceRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * BR2/BR3: canonicalise the provisioning request body, then SHA-256 it, so the comparison is
 * insensitive to things that are not the payload (key order, whitespace, "VM" vs "vm") and
 * sensitive to everything that is (any value change, a field appearing or disappearing).
 *
 * <p>Fingerprint covers the REQUEST BODY ONLY. Headers, including X-Simulate-Failure, are
 * excluded (assumption A4) — so the same key with the same body but a different simulate
 * header replays the original response and does not re-inject.
 *
 * <p>Implementation (functional-design.md 9.1):
 * <ol>
 *   <li>Build a canonical JSON object with keys in fixed alphabetical order
 *       (cpu, durationSeconds, ramGb, tenantId, type), no whitespace, absent optional fields
 *       written as null, type upper-cased, tenantId trimmed. Example:
 *       {@code {"cpu":4,"durationSeconds":7200,"ramGb":8,"tenantId":"tnt-std-001","type":"VM"}}
 *   <li>UTF-8 encode those bytes
 *   <li>SHA-256 hash them
 *   <li>Render as lowercase hex (64 characters) — matches the DB column's
 *       CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
 * </ol>
 * The JSON is written by hand rather than via an ObjectMapper so the canonical form can never
 * change because of a Jackson setting or a version upgrade.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(ProvisionResourceRequest request) {
        String canonical = canonicalJson(request);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return toLowerHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java platform; this cannot happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Package-visible so tests can assert the exact canonical form. */
    static String canonicalJson(ProvisionResourceRequest r) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"cpu\":").append(r.cpu());
        sb.append(",\"durationSeconds\":").append(r.durationSeconds());   // null renders as "null"
        sb.append(",\"ramGb\":").append(r.ramGb());
        sb.append(",\"tenantId\":");
        appendString(sb, r.tenantId() == null ? null : r.tenantId().trim());
        sb.append(",\"type\":");
        appendString(sb, r.type() == null ? null : r.type().trim().toUpperCase(Locale.ROOT));
        sb.append('}');
        return sb.toString();
    }

    private static void appendString(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static String toLowerHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0f];
        }
        return new String(out);
    }
}
