package com.crm.resource.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;

/** Persist the targeted failure directive so the async saga can forward it to the chosen peer. */
public record SimulateFailureDirective(String raw) {

    private static final Logger log = LoggerFactory.getLogger(SimulateFailureDirective.class);

    private static final Set<String> MODES = Set.of("always-fail", "always-succeed", "slow");
    private static final String PROVIDER_PREFIX = "provider:";
    private static final String BILLING_PREFIX = "billing:";

    public static SimulateFailureDirective none() {
        return new SimulateFailureDirective(null);
    }

    /**
     * Parse and normalise a raw {@code X-Simulate-Failure} header value for storage.
     * Recognises the bare modes (always-fail, always-succeed, slow) and the "peer:mode" form
     * (provider:..., billing:...); anything else -> none().
     */
    public static SimulateFailureDirective normalise(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return none();
        }
        String value = headerValue.trim().toLowerCase(Locale.ROOT);
        if (MODES.contains(value)) {
            return new SimulateFailureDirective(value);
        }
        if (value.startsWith(PROVIDER_PREFIX) && MODES.contains(value.substring(PROVIDER_PREFIX.length()))) {
            return new SimulateFailureDirective(value);
        }
        if (value.startsWith(BILLING_PREFIX) && MODES.contains(value.substring(BILLING_PREFIX.length()))) {
            return new SimulateFailureDirective(value);
        }
        log.debug("simulate-failure.ignored value={}", value);
        return none();
    }

    /** What to pass to the provider client's simulate-failure parameter, or null for nothing. */
    public String forProvider() {
        if (raw == null) {
            return null;
        }
        if (MODES.contains(raw)) {
            return raw;   // bare form targets the provider (FR-42)
        }
        return raw.startsWith(PROVIDER_PREFIX) ? raw.substring(PROVIDER_PREFIX.length()) : null;
    }

    /** What to pass to the billing client's simulate-failure parameter, or null for nothing. */
    public String forBilling() {
        if (raw != null && raw.startsWith(BILLING_PREFIX)) {
            return raw.substring(BILLING_PREFIX.length());
        }
        return null;
    }
}
