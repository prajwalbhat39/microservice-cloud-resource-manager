package com.crm.resource.domain;

import com.crm.resource.api.dto.ProvisionResourceRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Hash canonical request fields; simulation headers are excluded from the replay identity. */
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
