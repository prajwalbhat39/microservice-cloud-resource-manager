package com.crm.resource.domain.service;

import com.crm.resource.common.error.IdempotencyPayloadMismatchException;
import com.crm.resource.persistence.entity.IdempotencyRecord;
import com.crm.resource.persistence.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercise fresh, replay, in-flight and payload-mismatch decisions from repository results. */
class IdempotencyServiceTest {

    private static final String KEY = "0f8c3a7e-provision-001";
    private static final String FINGERPRINT = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-10-14T09:12:05Z");

    private IdempotencyRecordRepository repository;
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        repository = mock(IdempotencyRecordRepository.class);
        service = new IdempotencyService(repository, Clock.fixed(NOW, ZoneOffset.UTC), 24);
    }

    /** Simulates the database accepting our insert: we are the first caller for this key. */
    private void stubInsertWins() {
        when(repository.insertIfAbsent(any(), any(), any(), any())).thenReturn(1);
    }

    /** Simulates ON CONFLICT DO NOTHING: the key was already taken, so 0 rows were inserted. */
    private void stubInsertLoses() {
        when(repository.insertIfAbsent(any(), any(), any(), any())).thenReturn(0);
    }

    private static IdempotencyRecord storedRecord(String fingerprint, UUID reservationId, String state) {
        IdempotencyRecord record = new IdempotencyRecord(KEY, fingerprint, reservationId, state,
                NOW.plus(24, ChronoUnit.HOURS));
        if ("COMPLETED".equals(state)) {
            record.setResponseStatus(202);
            record.setResponseBody("{\"reservationId\":\"" + reservationId + "\"}");
        }
        return record;
    }

    @Test
    void freshKeyProceeds() {
        // Step 3: no existing record for key K, so the PK insert succeeds and the caller
        // processes the request. The inserted row is IN_PROGRESS and carries the candidate
        // reservationId, so a duplicate arriving mid-flight has something to point at.
        UUID reservationId = UUID.randomUUID();
        stubInsertWins();

        IdempotencyDecision decision = service.beginOrReplay(KEY, FINGERPRINT, reservationId);

        assertThat(decision.proceed()).isTrue();
        assertThat(decision.kind()).isEqualTo(IdempotencyDecision.Kind.PROCEED);

        // The row is inserted with the candidate reservationId, so a duplicate arriving
        // mid-flight has a reservation to point at. expires_at comes from the CONFIGURED
        // retention, never hard-coded, so the row's horizon and what the sweep deletes can
        // never disagree (assumption A13).
        verify(repository).insertIfAbsent(KEY, FINGERPRINT, reservationId.toString(),
                NOW.plus(24, ChronoUnit.HOURS));
        // Nothing is read before writing: that is the whole point of the guard.
        verify(repository, never()).findById(any());
    }

    @Test
    void replayReturnsStoredResponseWithoutReprocessing() {
        // Step 5: the insert loses to an existing COMPLETED record with the SAME fingerprint.
        UUID originalReservationId = UUID.randomUUID();
        IdempotencyRecord stored = storedRecord(FINGERPRINT, originalReservationId, "COMPLETED");
        stubInsertLoses();
        when(repository.findById(KEY)).thenReturn(Optional.of(stored));

        IdempotencyDecision decision = service.beginOrReplay(KEY, FINGERPRINT, UUID.randomUUID());

        assertThat(decision.kind()).isEqualTo(IdempotencyDecision.Kind.REPLAY);
        assertThat(decision.proceed()).isFalse();        // AC-2.2: no reprocessing at all
        assertThat(decision.storedRecord()).isSameAs(stored);
        assertThat(decision.storedRecord().getResponseStatus()).isEqualTo(202);
        // AC-2.1: the replay points at the ORIGINAL reservation, not the candidate just minted.
        assertThat(decision.storedRecord().getReservationId()).isEqualTo(originalReservationId);
        verify(repository, never()).save(any(IdempotencyRecord.class));
    }

    @Test
    void inProgressDuplicateReturnsInFlight() {
        // Step 6: same fingerprint, but the winner has not finished yet. The duplicate is told
        // about the winner's reservationId rather than being given a new one.
        UUID winnersReservationId = UUID.randomUUID();
        stubInsertLoses();
        when(repository.findById(KEY))
                .thenReturn(Optional.of(storedRecord(FINGERPRINT, winnersReservationId, "IN_PROGRESS")));

        IdempotencyDecision decision = service.beginOrReplay(KEY, FINGERPRINT, UUID.randomUUID());

        assertThat(decision.kind()).isEqualTo(IdempotencyDecision.Kind.IN_FLIGHT);
        assertThat(decision.inFlightReservationId()).isEqualTo(winnersReservationId);
    }

    @Test
    void payloadMismatchIsRejectedWith422() {
        // Step 4: the key is taken and the stored fingerprint DIFFERS, so the key was burned on
        // a different payload. 422, and the original record is left untouched (BR3).
        stubInsertLoses();
        when(repository.findById(KEY))
                .thenReturn(Optional.of(storedRecord("b".repeat(64), UUID.randomUUID(), "COMPLETED")));

        assertThatThrownBy(() -> service.beginOrReplay(KEY, FINGERPRINT, UUID.randomUUID()))
                .isInstanceOf(IdempotencyPayloadMismatchException.class)
                .hasMessageContaining(KEY);

        verify(repository, never()).save(any(IdempotencyRecord.class));
    }

    @Test
    void completeFreezesTheAnswerOnTheKey() {
        // Step (d) of ProvisioningService.provision: once frozen, every replay is byte-identical.
        IdempotencyRecord record = storedRecord(FINGERPRINT, UUID.randomUUID(), "IN_PROGRESS");
        when(repository.findById(KEY)).thenReturn(Optional.of(record));

        service.complete(KEY, 202, "{\"status\":\"PENDING\"}");

        assertThat(record.getState()).isEqualTo("COMPLETED");
        assertThat(record.getResponseStatus()).isEqualTo(202);
        assertThat(record.getResponseBody()).isEqualTo("{\"status\":\"PENDING\"}");
        verify(repository).save(record);
    }

    @Test
    void sweepDeletesRowsPastTheirOwnHorizon() {
        // Expiry is enforced by DELETION, not by a comparison on the read path (A13): this sweep
        // is the only thing that makes a key reusable again.
        when(repository.deleteExpired(NOW)).thenReturn(3);

        service.sweepExpired();

        verify(repository).deleteExpired(NOW);
    }
}
