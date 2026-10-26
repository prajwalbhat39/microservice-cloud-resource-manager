package com.crm.resource.domain.service;

import com.crm.resource.common.error.IdempotencyPayloadMismatchException;
import com.crm.resource.persistence.entity.IdempotencyRecord;
import com.crm.resource.persistence.repository.IdempotencyRecordRepository;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String STATE_COMPLETED = "COMPLETED";

    private final IdempotencyRecordRepository repository;
    private final Clock clock;
    private final long retentionHours;

    public IdempotencyService(IdempotencyRecordRepository repository, Clock clock,
                              @Value("${idempotency.retention-hours:24}") long retentionHours) {
        this.repository = repository;
        this.clock = clock;
        this.retentionHours = retentionHours;
    }

    public IdempotencyDecision beginOrReplay(String key, String fingerprint, UUID candidateReservationId) {
        // The primary-key insert IS the guard: nothing is read before writing. The database
        // decides who was first, and the affected-row count is that decision.
        //
        // expires_at = now + idempotency.retention-hours, read from config and never hard-coded,
        // so the record's own horizon and what the sweep deletes can never disagree (A13).
        Instant now = clock.instant();
        int inserted = repository.insertIfAbsent(key, fingerprint, candidateReservationId.toString(),
                now.plus(retentionHours, ChronoUnit.HOURS));

        if (inserted == 1) {
            return IdempotencyDecision.fresh();        // step 3: we are the first caller
        }
        // The key was already taken. Safe to read it: no statement failed, so this transaction
        // is healthy (see IdempotencyRecordRepository.insertIfAbsent for why that matters).
        return decideFromStoredRecord(key, fingerprint);
    }

    private IdempotencyDecision decideFromStoredRecord(String key, String fingerprint) {
        IdempotencyRecord stored = repository.findById(key).orElseThrow();
        if (!stored.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyPayloadMismatchException(key);               // step 4 -> 422
        }
        if (STATE_COMPLETED.equals(stored.getState())) {
            log.info("idempotency.replay key={}", key);
            return IdempotencyDecision.replay(stored);                        // step 5
        }
        log.info("idempotency.in-flight key={} reservationId={}", key, stored.getReservationId());
        return IdempotencyDecision.inFlight(stored.getReservationId());       // step 6
    }

    /**
     * Step (d)/(b) of ProvisioningService.provision: freeze the final response on the key so
     * every replay is byte-identical (AC-2.1). Sets state=COMPLETED, response_status, response_body.
     */
    public void complete(String key, int responseStatus, String responseBody) {
        IdempotencyRecord record = repository.findById(key).orElseThrow();
        record.setState(STATE_COMPLETED);
        record.setResponseStatus(responseStatus);
        record.setResponseBody(responseBody);
        repository.save(record);
    }

    /** Expired keys become reusable after the retention sweep removes their rows. */
    @Scheduled(fixedDelayString = "${idempotency.sweep-interval-ms:3600000}",
            initialDelayString = "${idempotency.sweep-interval-ms:3600000}")
    @Transactional
    public void sweepExpired() {
        int deleted = repository.deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("idempotency.sweep deleted={}", deleted);
        }
    }
}