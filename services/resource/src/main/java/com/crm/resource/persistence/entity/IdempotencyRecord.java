package com.crm.resource.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** No reservation FK: a rejected request can have an idempotency row without a reservation. */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", length = 200, nullable = false, updatable = false)
    private String idempotencyKey;

    /** Match PostgreSQL char(64) so Hibernate schema validation accepts the fingerprint column. */
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

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String idempotencyKey, String requestFingerprint, UUID reservationId,
                             String state, Instant expiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.reservationId = reservationId;
        this.state = state;
        this.expiresAt = expiresAt;
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
