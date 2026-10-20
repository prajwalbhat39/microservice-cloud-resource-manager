package com.crm.resource.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code idempotency_records}: Challenge 2. PK insert = concurrency guard (BR2);
 * fingerprint compare = payload-mismatch detection (BR3). Deliberately NO foreign key on
 * {@code reservationId} — see functional-design.md 9.3 for why (the record is written before the
 * reservations row exists, and on the quota-rejection path that row never gets created at all).
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @Column(name = "idempotency_key", length = 200, nullable = false, updatable = false)
    private String idempotencyKey;

    /**
     * BR3. Declared CHAR, not VARCHAR, to match {@code request_fingerprint char(64)} in
     * V1__baseline.sql — Flyway owns the schema (NFR-23), so the mapping conforms to it rather
     * than the other way round. Without the explicit JDBC type, {@code ddl-auto: validate}
     * rejects the whole EntityManagerFactory at startup, because PostgreSQL reports {@code bpchar}
     * as {@code Types#CHAR} while a plain String maps to {@code Types#VARCHAR}.
     *
     * <p>CHAR's space-padding is harmless here: the value is always exactly 64 hex characters,
     * which the column's own CHECK ({@code ~ '^[0-9a-f]{64}$'}) guarantees.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_fingerprint", length = 64, nullable = false, updatable = false)
    private String requestFingerprint;

    @Column(name = "reservation_id", updatable = false)
    private UUID reservationId;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "state", length = 20, nullable = false)
    private String state;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /**
     * CRITICAL for BR2 (functional-design.md 9.2): marks this instance as a NEW row so
     * {@code save}/{@code saveAndFlush} issues {@code persist} (INSERT) rather than
     * {@code merge}.
     *
     * <p>The idempotency key is client-supplied, so the id is never null, and Spring Data's
     * default {@code isNew()} — "id == null" — would classify every insert as an update. The
     * repository would then {@code merge}, which SELECTs and UPDATEs the row that already
     * exists. That silently destroys the entire Challenge 2 guard: the duplicate-key violation
     * never happens, so a replayed request overwrites the first caller's fingerprint and gets
     * processed as if it were fresh, and a payload mismatch is never detected.
     *
     * <p>Not persisted ({@code @Transient}); cleared once the row exists, so the later
     * {@code complete(...)} update still goes through {@code merge} as it must.
     */
    @Transient
    private boolean newRecord;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String idempotencyKey, String requestFingerprint, UUID reservationId,
                             String state, Instant expiresAt) {
        this.newRecord = true;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.reservationId = reservationId;
        this.state = state;
        this.expiresAt = expiresAt;
    }

    /** Convenience factory matching functional-design.md 9.2's {@code IdempotencyRecord.inProgress}. */
    public static IdempotencyRecord inProgress(String key, String fingerprint, UUID reservationId,
                                               Instant now, Instant expiresAt) {
        return new IdempotencyRecord(key, fingerprint, reservationId, "IN_PROGRESS", expiresAt);
    }

    @Override
    public String getId() {
        return idempotencyKey;
    }

    /** True only for an instance that has not been written yet — see {@link #newRecord}. */
    @Override
    public boolean isNew() {
        return newRecord;
    }

    /** Once the row exists (freshly inserted or loaded), subsequent saves must be updates. */
    @PostPersist
    @PostLoad
    void markNotNew() {
        this.newRecord = false;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
