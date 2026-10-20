package com.crm.resource.domain.service;

import com.crm.resource.api.dto.ProvisionAcceptedResponse;
import com.crm.resource.common.error.ApiException;

import java.util.UUID;

/**
 * What {@link ProvisioningService#provision} hands back to the controller. Exactly one shape:
 * <ul>
 *   <li><b>accepted</b> — a fresh 202; {@link #body()} is the typed body;
 *   <li><b>stored response</b> — the exact status and body text frozen on the idempotency key,
 *       either replayed ({@link #isReplayed()} true, {@code Idempotency-Replayed: true}) or the
 *       fresh business rejection that was just frozen on the key. The controller returns the
 *       stored text verbatim (verbatim-replay rule, nfr-design.md 2.5);
 *   <li><b>in-flight</b> — a 202 pointing at the same reservationId (decision-table step 6).
 * </ul>
 */
public final class ProvisionOutcome {

    private final ProvisionAcceptedResponse body;
    private final ApiException rejection;
    private final boolean replayed;
    private final Integer storedStatus;
    private final String storedBody;
    private final UUID reservationId;

    private ProvisionOutcome(ProvisionAcceptedResponse body, ApiException rejection, boolean replayed,
                             Integer storedStatus, String storedBody, UUID reservationId) {
        this.body = body;
        this.rejection = rejection;
        this.replayed = replayed;
        this.storedStatus = storedStatus;
        this.storedBody = storedBody;
        this.reservationId = reservationId;
    }

    public static ProvisionOutcome accepted(ProvisionAcceptedResponse body) {
        return new ProvisionOutcome(body, null, false, null, null, body.reservationId());
    }

    /**
     * Step 5/6 of the decision table: a stored or in-flight 202 is being replayed.
     * REPLAY carries the stored status and body text unchanged; IN_FLIGHT builds a PENDING body
     * for the same reservationId (the resourceId is not known to a duplicate that arrives before
     * the winner commits, so it is null in that body).
     */
    public static ProvisionOutcome of(IdempotencyDecision decision) {
        return switch (decision.kind()) {
            case REPLAY -> new ProvisionOutcome(null, null, true,
                    decision.storedRecord().getResponseStatus(),
                    decision.storedRecord().getResponseBody(),
                    decision.storedRecord().getReservationId());
            case IN_FLIGHT -> new ProvisionOutcome(
                    ProvisionAcceptedResponse.pending(decision.inFlightReservationId(), null),
                    null, true, null, null, decision.inFlightReservationId());
            case PROCEED -> throw new IllegalArgumentException(
                    "A PROCEED decision is not an outcome; the caller must continue processing");
        };
    }

    /**
     * A business rejection from a peer (404 / 422). {@code storedStatus}/{@code storedBody} is the
     * exact problem document just frozen on the idempotency key.
     */
    public static ProvisionOutcome rejected(ApiException rejection, int storedStatus, String storedBody) {
        return new ProvisionOutcome(null, rejection, false, storedStatus, storedBody, null);
    }

    public ProvisionAcceptedResponse body() {
        return body;
    }

    public ApiException rejection() {
        return rejection;
    }

    public boolean isRejected() {
        return rejection != null;
    }

    public boolean isReplayed() {
        return replayed;
    }

    /** True when the response must be written from the stored status/body text. */
    public boolean hasStoredResponse() {
        return storedBody != null;
    }

    public Integer storedStatus() {
        return storedStatus;
    }

    public String storedBody() {
        return storedBody;
    }

    /** The reservation this outcome refers to (for the Location header), or null for a rejection. */
    public UUID reservationId() {
        return reservationId;
    }
}
