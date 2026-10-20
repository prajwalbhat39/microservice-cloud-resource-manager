package com.crm.resource.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;

/**
 * FR-42, FR-45, FR-46: the parsed {@code X-Simulate-Failure} header, persisted on
 * {@code reservations.simulate_failure} and replayed by the background saga onto the matching
 * downstream call — the header itself is long gone by then (the client's HTTP exchange ended
 * at the 202).
 *
 * <p>Routing table (functional-design.md 9.4):
 * <pre>
 * Stored value                                Replayed to Provider   Replayed to Billing
 * (absent)                                    nothing                nothing
 * always-fail / always-succeed / slow         that value (bare       nothing
 *                                              form targets the
 *                                              provider, FR-42)
 * provider:&lt;mode&gt;                             &lt;mode&gt;                 nothing
 * billing:&lt;mode&gt;                              nothing                &lt;mode&gt;
 * anything else                               nothing                nothing (DEBUG log only)
 * </pre>
 *
 * <p>{@link #normalise(String)} lower-cases and trims; an unrecognised value is dropped (stored
 * as null, logged at DEBUG, never an error) so a stray header can never fail a genuine request.
 *
 * @param raw the normalised stored value, or null if absent/unrecognised
 */
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
