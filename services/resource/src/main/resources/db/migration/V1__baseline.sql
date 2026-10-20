-- =====================================================================
-- Resource / Orchestrator baseline. Owns resource lifecycle, request-level
-- reservations and idempotency records. Enforces BR2, BR3, BR4 (in code,
-- recorded here), BR6 and the orchestrator half of BR7.
-- There are deliberately NO saga_state / saga_step tables: saga state is
-- resources.status + reservations.status + resources.failure_reason.
-- =====================================================================

-- ---------------------------------------------------------------------
-- resources: the lifecycle aggregate AND the queryable saga outcome (AC-3.1).
-- tenant_id and provider_instance_id are opaque identifiers owned by other
-- services: no foreign keys across service boundaries (NFR-3, NFR-4).
-- ---------------------------------------------------------------------
CREATE TABLE resources (
    resource_id           uuid         PRIMARY KEY,
    tenant_id              varchar(64)  NOT NULL,
    type                   varchar(20)  NOT NULL,
    cpu                    integer      NOT NULL,
    ram_gb                 integer      NOT NULL,
    status                 varchar(20)  NOT NULL,
    provider_instance_id   varchar(100),
    expires_at             timestamptz,
    created_at             timestamptz  NOT NULL DEFAULT now(),
    activated_at           timestamptz,
    terminated_at          timestamptz,
    failure_reason         text,

    CONSTRAINT ck_resources_type CHECK (type IN ('VM', 'CONTAINER', 'CLUSTER')),

    -- BR4 support: the five states of the linear lifecycle. The transition GRAPH is enforced
    -- in ResourceLifecycle (FR-50); this constraint only bars a value off the enum entirely.
    CONSTRAINT ck_resources_status CHECK (
        status IN ('REQUESTED', 'PROVISIONING', 'ACTIVE', 'FAILED', 'TERMINATED')),

    CONSTRAINT ck_resources_cpu_positive CHECK (cpu    > 0),
    CONSTRAINT ck_resources_ram_positive CHECK (ram_gb > 0),

    -- State/timestamp invariants. These make an impossible row unwritable, so a bug in the
    -- state machine surfaces as a failure, not as bad data.
    CONSTRAINT ck_resources_active_has_activated_at   CHECK (status <> 'ACTIVE'     OR activated_at  IS NOT NULL),
    CONSTRAINT ck_resources_terminated_has_timestamp  CHECK (status <> 'TERMINATED' OR terminated_at IS NOT NULL),
    CONSTRAINT ck_resources_failed_has_reason         CHECK (status <> 'FAILED'     OR failure_reason IS NOT NULL)
);

COMMENT ON TABLE  resources        IS 'FR-17, FR-47 to FR-50. Also the queryable saga state (AC-3.1).';
COMMENT ON COLUMN resources.status IS 'REQUESTED -> PROVISIONING -> ACTIVE -> TERMINATED, plus FAILED (terminal).';
COMMENT ON COLUMN resources.failure_reason IS 'AC-3.5. Set with the peer code that failed, e.g. BILLING_UNAVAILABLE: ...';

-- Query patterns:
--   1. GET /resources?tenantId=&status=        -> (tenant_id, status)
--   2. GET /resources/expired (ACTIVE + past)  -> partial index, see below
CREATE INDEX ix_resources_tenant_status ON resources (tenant_id, status);

-- Partial index: the expiry sweep only ever asks for ACTIVE rows with an expiry in the past
-- (FR-24). A partial index keeps it small and skips the rows with expires_at IS NULL.
CREATE INDEX ix_resources_active_expiry ON resources (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;

-- ---------------------------------------------------------------------
-- reservations: the request-level async handle returned by the 202 (FR-14, FR-16).
-- ---------------------------------------------------------------------
CREATE TABLE reservations (
    reservation_id    uuid         PRIMARY KEY,
    tenant_id          varchar(64)  NOT NULL,
    resource_id         uuid         REFERENCES resources (resource_id) ON DELETE RESTRICT,
    status              varchar(20)  NOT NULL,
    failure_reason       text,
    simulate_failure     varchar(40),
    created_at           timestamptz  NOT NULL DEFAULT now(),

    -- The DEFAULT covers the INSERT only. Every later write of this column is done by
    -- ReservationService.complete / .fail, which set status, failure_reason and
    -- updated_at = clock.instant() in one UPDATE. No trigger: the one code path that
    -- changes the row already has the value.
    updated_at           timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_reservations_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_reservations_failed_has_reason CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL),

    -- One request handle per resource: a second reservation for the same resource would mean
    -- the idempotency layer had leaked.
    CONSTRAINT uq_reservations_resource_id UNIQUE (resource_id)
);

COMMENT ON COLUMN reservations.simulate_failure IS
  'FR-46. The X-Simulate-Failure header persisted at request time and replayed by the background
   saga on the matching downstream call, so injection survives the async boundary.';
COMMENT ON COLUMN reservations.updated_at IS
  'Written by ReservationService.complete/.fail on each status change; DEFAULT applies at insert only.';

-- Query pattern: a tenant's reservations for demo queries and troubleshooting.
CREATE INDEX ix_reservations_tenant ON reservations (tenant_id);

-- ---------------------------------------------------------------------
-- idempotency_records: Challenge 2. The PRIMARY KEY on the client-supplied key IS the
-- concurrency guard (BR2): of two simultaneous identical requests, exactly one INSERT wins.
-- request_fingerprint is what rejects a reused key with a different payload (BR3).
--
-- Deliberately NO foreign key on reservation_id: the record is inserted FIRST, with a
-- reservation id minted before the reservations row exists (functional-design.md 9.3).
-- On the quota-rejection path the record commits with a reservationId whose row is never
-- created at all, because the key owns the 422 answer, not a reservation.
-- ---------------------------------------------------------------------
CREATE TABLE idempotency_records (
    idempotency_key       varchar(200) PRIMARY KEY,
    request_fingerprint    char(64)     NOT NULL,
    reservation_id          uuid,
    response_status          integer,
    response_body            text,
    state                    varchar(20)  NOT NULL,
    created_at               timestamptz  NOT NULL DEFAULT now(),
    expires_at               timestamptz  NOT NULL,

    CONSTRAINT ck_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_idempotency_fingerprint_hex CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_completed_has_response CHECK (
        state <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL)),
    CONSTRAINT ck_idempotency_expiry_after_creation CHECK (expires_at > created_at)
);

COMMENT ON TABLE idempotency_records IS
  'BR2, BR3, Challenge 2. PK insert = concurrency guard; fingerprint compare = payload mismatch.
   expires_at is written at insert as created_at + IDEMPOTENCY_RETENTION_HOURS (default 24).
   Expiry is enforced by the scheduled sweep that deletes on this column, not by a comparison on
   the read path, so a key is reusable 24h..24h+one sweep interval after first use.';

-- Query pattern: the retention sweep deletes WHERE expires_at < now().
CREATE INDEX ix_idempotency_expires_at ON idempotency_records (expires_at);
