package com.crm.resource.domain.service;

import com.crm.resource.persistence.entity.IdempotencyRecord;

import java.util.UUID;

/**
 * The outcome of {@link IdempotencyService#beginOrReplay}, matching the decision table in
 * functional-design.md 9.2. Exactly one of the four factory methods is used per call.
 */
public final class IdempotencyDecision {

    public enum Kind { PROCEED, REPLAY, IN_FLIGHT }

    private final Kind kind;
    private final IdempotencyRecord storedRecord; // set for REPLAY
    private final UUID inFlightReservationId;      // set for IN_FLIGHT

    private IdempotencyDecision(Kind kind, IdempotencyRecord storedRecord, UUID inFlightReservationId) {
        this.kind = kind;
        this.storedRecord = storedRecord;
        this.inFlightReservationId = inFlightReservationId;
    }

    /** Step 3: the insert succeeded. Caller proceeds with fresh processing. */
    public static IdempotencyDecision fresh() {
        return new IdempotencyDecision(Kind.PROCEED, null, null);
    }

    /** Step 5: fingerprint matched a COMPLETED record. Caller returns the stored body verbatim. */
    public static IdempotencyDecision replay(IdempotencyRecord stored) {
        return new IdempotencyDecision(Kind.REPLAY, stored, null);
    }

    /** Step 6: fingerprint matched an IN_PROGRESS record. Caller returns 202 for this reservationId. */
    public static IdempotencyDecision inFlight(UUID reservationId) {
        return new IdempotencyDecision(Kind.IN_FLIGHT, null, reservationId);
    }

    public boolean proceed() {
        return kind == Kind.PROCEED;
    }

    public Kind kind() {
        return kind;
    }

    public IdempotencyRecord storedRecord() {
        return storedRecord;
    }

    public UUID inFlightReservationId() {
        return inFlightReservationId;
    }
}
