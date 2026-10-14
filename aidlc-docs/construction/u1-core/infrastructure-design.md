# Infrastructure Design — Unit U1 (Core Challenge Services)

**Stage**: CONSTRUCTION / Infrastructure Design
**Status**: APPROVED (2026-10-14)
**Unit**: U1 — Core Challenge Services
**Owner**: Prajwal (core owner)
**Services in scope**: Quota (`:8081`, `quota_db`, `quota_user`), Resource / Orchestrator (`:8082`, `resource_db`, `resource_user`)
**Depends on**: `construction/u1-core/functional-design.md` (query patterns and behaviour), `construction/u1-core/nfr-design.md` (config and Flyway conventions), `inception/design/application-design.md` §4.1, §4.2, §10 (frozen data models, ports, databases), `inception/units.md` §8, §9 (config variables, toolchain checkpoint)
**Consumed by**: Code Generation for U1, then Build and Test

Scope: the two databases, their Flyway baselines, seed data, the corrected PostgreSQL setup
script, the environment-variable tables, local run order, the WireMock setup that stands in for
U1's four peers, and the Testcontainers configuration for the BR1/BR3/BR7 tests.

**No cloud infrastructure, and no containerisation.** Docker and Minikube are Assignment 2;
Docker appears here only because Testcontainers needs a running daemon at test time. All SQL
and scripts below are specification content inside this document, not files in the repository
yet — Code Generation emits them.

---

## 1. Databases at a Glance

| Service | Database | User | Tables | Frozen in |
|---------|----------|------|--------|-----------|
| Quota | `quota_db` | `quota_user` | `tenants`, `tenant_quota`, `quota_reservations` | `application-design.md` §4.1, §10 |
| Orchestrator | `resource_db` | `resource_user` | `resources`, `reservations`, `idempotency_records` | `application-design.md` §4.2, §10 |

One PostgreSQL 16/17 server, a separate database and least-privilege user per service (NFR-2).
Foreign keys exist **only inside** a database; nothing references another service's data
(NFR-3, NFR-4).

```mermaid
erDiagram
    tenants ||--|| tenant_quota : "one quota row per tenant"
    tenants ||--o{ quota_reservations : "ledger rows"
    resources ||--|| reservations : "one request handle per resource"
    reservations ||--o| idempotency_records : "recorded response"
```

The two clusters of tables in that diagram live in two different databases and are joined only
over HTTP.

---

## 2. Flyway Baselines

### 2.1 `quota_db` — `services/quota/src/main/resources/db/migration/V1__baseline.sql`

```sql
-- =====================================================================
-- Quota service baseline. Owns tenants, tiers, quota limits/usage and
-- the reservation ledger. Enforces BR1 (never exceed limits), BR7
-- (release exactly once) and supports BR8 (tier policy, enforced in code).
-- =====================================================================

-- ---------------------------------------------------------------------
-- tenants: identity and subscription tier. Tier is immutable (FR-3):
-- there is no UPDATE path in the service, and no trigger is needed.
-- ---------------------------------------------------------------------
CREATE TABLE tenants (
    tenant_id   varchar(64)  PRIMARY KEY,
    name        varchar(200) NOT NULL,
    tier        varchar(20)  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_tenants_tier CHECK (tier IN ('FREE', 'STANDARD', 'ENTERPRISE')),
    CONSTRAINT ck_tenants_name_not_blank CHECK (length(btrim(name)) > 0)
);

COMMENT ON TABLE  tenants      IS 'FR-1, FR-2. Tenant identity and tier.';
COMMENT ON COLUMN tenants.tier IS 'FR-3 immutable after creation; FR-5 fixes the limits per tier.';

-- ---------------------------------------------------------------------
-- tenant_quota: THE contended resource (Condition C). One row per tenant,
-- created with the tenant. No version column: concurrency is handled by the
-- atomic conditional UPDATE (Challenge 1), not optimistic locking.
-- ---------------------------------------------------------------------
CREATE TABLE tenant_quota (
    tenant_id       varchar(64) PRIMARY KEY
                    REFERENCES tenants (tenant_id) ON DELETE RESTRICT,
    cpu_used        integer NOT NULL DEFAULT 0,
    cpu_limit       integer NOT NULL,
    ram_used_gb     integer NOT NULL DEFAULT 0,
    ram_limit_gb    integer NOT NULL,
    instance_used   integer NOT NULL DEFAULT 0,
    instance_limit  integer NOT NULL,

    -- Non-negative usage: a release can never drive usage below zero (BR7 + GREATEST floor).
    CONSTRAINT ck_tenant_quota_cpu_non_negative      CHECK (cpu_used      >= 0),
    CONSTRAINT ck_tenant_quota_ram_non_negative      CHECK (ram_used_gb   >= 0),
    CONSTRAINT ck_tenant_quota_instance_non_negative CHECK (instance_used >= 0),

    -- Positive limits: a zero-limit tier would be a configuration error, not a business case.
    CONSTRAINT ck_tenant_quota_cpu_limit_positive      CHECK (cpu_limit      > 0),
    CONSTRAINT ck_tenant_quota_ram_limit_positive      CHECK (ram_limit_gb   > 0),
    CONSTRAINT ck_tenant_quota_instance_limit_positive CHECK (instance_limit > 0),

    -- BR1, defence in depth. The atomic conditional UPDATE is the mechanism; these three
    -- constraints make "usage never exceeds the limit" structurally impossible, so a future
    -- code path that forgot the predicate would fail loudly instead of over-allocating.
    CONSTRAINT ck_tenant_quota_cpu_within_limit      CHECK (cpu_used      <= cpu_limit),
    CONSTRAINT ck_tenant_quota_ram_within_limit      CHECK (ram_used_gb   <= ram_limit_gb),
    CONSTRAINT ck_tenant_quota_instance_within_limit CHECK (instance_used <= instance_limit)
);

COMMENT ON TABLE tenant_quota IS
  'BR1 / Challenge 1. Updated only by the atomic conditional UPDATE in QuotaService.reserve.';

-- ---------------------------------------------------------------------
-- quota_reservations: the release-exactly-once ledger (BR7).
-- UNIQUE(resource_id) is what makes BOTH reserve and release idempotent:
-- a duplicate reserve collides here before touching tenant_quota, and a
-- repeated release finds state='RELEASED' and does nothing.
-- ---------------------------------------------------------------------
CREATE TABLE quota_reservations (
    reservation_id  uuid         PRIMARY KEY,
    tenant_id       varchar(64)  NOT NULL REFERENCES tenants (tenant_id) ON DELETE RESTRICT,
    resource_id     varchar(64)  NOT NULL,
    cpu             integer      NOT NULL,
    ram_gb          integer      NOT NULL,
    state           varchar(20)  NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    released_at     timestamptz,

    -- BR7: one ledger row per resource, for ever.
    CONSTRAINT uq_quota_reservations_resource_id UNIQUE (resource_id),

    CONSTRAINT ck_quota_reservations_state CHECK (state IN ('RESERVED', 'RELEASED')),
    CONSTRAINT ck_quota_reservations_cpu_positive CHECK (cpu    > 0),
    CONSTRAINT ck_quota_reservations_ram_positive CHECK (ram_gb > 0),

    -- The state and its timestamp can never disagree.
    CONSTRAINT ck_quota_reservations_released_at CHECK (
        (state = 'RESERVED' AND released_at IS NULL) OR
        (state = 'RELEASED' AND released_at IS NOT NULL))
);

COMMENT ON TABLE quota_reservations IS
  'BR7. UNIQUE(resource_id) + the RESERVED->RELEASED state flip give exactly-once release.';

-- Query patterns that justify the indexes (and nothing else):
--   1. findByResourceId(resourceId)            -> served by uq_quota_reservations_resource_id
--   2. count/list a tenant''s RESERVED rows for Challenge 1 evidence and demo queries
CREATE INDEX ix_quota_reservations_tenant_state ON quota_reservations (tenant_id, state);
```

No index is created on `created_at` or `released_at`: nothing queries by time. Indexes that
exist "just in case" cost write throughput on exactly the table Challenge 1 hammers.

### 2.2 `resource_db` — `services/resource/src/main/resources/db/migration/V1__baseline.sql`

```sql
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
    tenant_id             varchar(64)  NOT NULL,
    type                  varchar(20)  NOT NULL,
    cpu                   integer      NOT NULL,
    ram_gb                integer      NOT NULL,
    status                varchar(20)  NOT NULL,
    provider_instance_id  varchar(100),
    expires_at            timestamptz,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    activated_at          timestamptz,
    terminated_at         timestamptz,
    failure_reason        text,

    CONSTRAINT ck_resources_type CHECK (type IN ('VM', 'CONTAINER', 'CLUSTER')),

    -- BR4 support: the five states of the linear lifecycle. The transition GRAPH is enforced
    -- in ResourceLifecycle (FR-50); this constraint only bars a value off the enum entirely.
    CONSTRAINT ck_resources_status CHECK (
        status IN ('REQUESTED', 'PROVISIONING', 'ACTIVE', 'FAILED', 'TERMINATED')),

    CONSTRAINT ck_resources_cpu_positive CHECK (cpu    > 0),
    CONSTRAINT ck_resources_ram_positive CHECK (ram_gb > 0),

    -- State/timestamp invariants from functional-design.md §5.2. These make an impossible row
    -- unwritable, so a bug in the state machine surfaces as a failure, not as bad data.
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
-- (FR-24). A partial index keeps it small and skips the ~all rows with expires_at IS NULL.
CREATE INDEX ix_resources_active_expiry ON resources (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;

-- ---------------------------------------------------------------------
-- reservations: the request-level async handle returned by the 202 (FR-14, FR-16).
-- ---------------------------------------------------------------------
CREATE TABLE reservations (
    reservation_id    uuid         PRIMARY KEY,
    tenant_id         varchar(64)  NOT NULL,
    resource_id       uuid         REFERENCES resources (resource_id) ON DELETE RESTRICT,
    status            varchar(20)  NOT NULL,
    failure_reason    text,
    simulate_failure  varchar(40),
    created_at        timestamptz  NOT NULL DEFAULT now(),

    -- The DEFAULT covers the INSERT only. Every later write of this column is done by
    -- ReservationService.complete / .fail, which set status, failure_reason and
    -- updated_at = clock.instant() in one UPDATE (functional-design.md §5.2, §9.5).
    -- No trigger: the one code path that changes the row already has the value.
    updated_at        timestamptz  NOT NULL DEFAULT now(),

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

-- Query pattern: a tenant''s reservations for demo queries and troubleshooting.
CREATE INDEX ix_reservations_tenant ON reservations (tenant_id);

-- ---------------------------------------------------------------------
-- idempotency_records: Challenge 2. The PRIMARY KEY on the client-supplied key IS the
-- concurrency guard (BR2): of two simultaneous identical requests, exactly one INSERT wins.
-- request_fingerprint is what rejects a reused key with a different payload (BR3).
-- ---------------------------------------------------------------------
CREATE TABLE idempotency_records (
    idempotency_key      varchar(200) PRIMARY KEY,
    request_fingerprint  char(64)     NOT NULL,

    -- Deliberately NOT a foreign key, matching application-design.md §4.2, which lists this as
    -- a plain reference. Two reasons, both from the algorithm in functional-design.md §9.3:
    --   1. The record is inserted FIRST, with a reservation id minted before the reservations
    --      row exists (step (a) precedes step (c)). A non-deferrable FK would fail that insert
    --      on every first-time provisioning request.
    --   2. On the quota-rejection path the record is committed with a reservation that is never
    --      created at all, because the key owns the 422 answer rather than a reservation.
    reservation_id       uuid,

    response_status      integer,
    response_body        text,
    state                varchar(20)  NOT NULL,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    expires_at           timestamptz  NOT NULL,

    CONSTRAINT ck_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_idempotency_fingerprint_hex CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_completed_has_response CHECK (
        state <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL)),
    CONSTRAINT ck_idempotency_expiry_after_creation CHECK (expires_at > created_at)
);

COMMENT ON TABLE idempotency_records IS
  'BR2, BR3, Challenge 2. PK insert = concurrency guard; fingerprint compare = payload mismatch.
   expires_at is written at insert as created_at + IDEMPOTENCY_RETENTION_HOURS (default 24,
   requirements.md 6.2). Expiry is enforced by the scheduled sweep that deletes on this column,
   not by a comparison on the read path, so a key is reusable 24h..24h+one sweep interval after
   first use (functional-design.md 9.2, assumption A13).';

-- Query pattern: the retention sweep deletes WHERE expires_at < now().
CREATE INDEX ix_idempotency_expires_at ON idempotency_records (expires_at);
```

### 2.3 Constraint → business rule map

| Constraint | Rule | What it guarantees |
|-----------|------|--------------------|
| `uq_quota_reservations_resource_id` | **BR7** (and BR1's duplicate-reserve case) | One ledger row per resource for ever, so quota is added once and released once |
| `ck_quota_reservations_released_at` | **BR7** | `RELEASED` and `released_at` can never disagree, so the "released exactly once" evidence is trustworthy |
| `ck_tenant_quota_*_within_limit` | **BR1** (defence in depth) | Usage above the limit is unwritable. The atomic UPDATE remains the mechanism — these catch a future code path that bypassed it |
| `idempotency_records_pkey` | **BR2** | Of N simultaneous identical requests, exactly one proceeds; the rest replay |
| `tenants_pkey` | — (A2) | A client-supplied `tenantId` cannot be taken twice; the duplicate insert is recovered through `SavepointGuard` and rendered as 409 `TENANT_ALREADY_EXISTS` (`functional-design.md` §12.3) |
| `ck_idempotency_fingerprint_hex` + `request_fingerprint NOT NULL` | **BR3** | A record always has a comparable fingerprint, so a mismatch is always detectable |
| `uq_reservations_resource_id` | **BR2** | A second request handle for one resource is impossible |
| `ck_resources_status` + `ck_resources_*_has_*` | **BR4** | Only the five lifecycle states exist, and an `ACTIVE` row without `activated_at` (or a `FAILED` row without a reason) cannot be written |

---

## 3. PostgreSQL Setup Script (corrected for PostgreSQL 15+)

### 3.1 Why the draft in `units.md` §9 is insufficient

The approved draft grants only `GRANT ALL PRIVILEGES ON DATABASE quota_db TO quota_user`. On
PostgreSQL 15 or later, Flyway then fails at the first migration with a permission error on
`CREATE TABLE`, for two independent reasons:

1. **`GRANT ALL ON DATABASE` is not what it sounds like.** At database level PostgreSQL has
   exactly three privileges: `CONNECT`, `TEMPORARY` and `CREATE` — and that `CREATE` means
   "create a *schema* in this database", not "create objects inside an existing schema". It
   confers nothing on `public`.
2. **Since PostgreSQL 15 the `public` schema is locked down.** It is owned by
   `pg_database_owner` and no longer grants `CREATE` to the `PUBLIC` role. Before 15 every user
   could create tables in `public`, which is why this script pattern used to appear to work.

Flyway must create `flyway_schema_history` plus every table, constraint and index as the service
user, so the user needs `CREATE` **inside the schema it uses**. Schema privileges are
per-database, so the grants must be issued while connected to that database — a `\connect` (or
a separate `psql -d`) is mandatory, not stylistic.

The frozen `units.md` is approved and is **not edited**; this section is the correction, and
`nfr-design.md` §6.1 records the reasoning.

### 3.2 The corrected script (U1's two databases)

`scripts/db/setup-u1.sql`, run once as a superuser: `psql -U postgres -f scripts/db/setup-u1.sql`

```sql
-- =====================================================================
-- U1 database setup. PostgreSQL 16/17.
-- One database and one least-privilege user per service (NFR-2).
-- Passwords are local-development defaults; override via DB_PASSWORD.
-- =====================================================================

-- ---------- Quota service ----------
CREATE DATABASE quota_db;
CREATE USER quota_user WITH PASSWORD 'changeme';

-- Database level: connect only. No CREATE (the user does not need to make new schemas),
-- no TEMPORARY (nothing uses temp tables).
REVOKE ALL      ON DATABASE quota_db FROM PUBLIC;   -- stop every other role connecting
GRANT  CONNECT  ON DATABASE quota_db TO quota_user;

-- Schema level: this is the part the units.md draft was missing.
\connect quota_db
ALTER SCHEMA public OWNER TO quota_user;            -- the service owns everything Flyway creates
REVOKE ALL ON SCHEMA public FROM PUBLIC;            -- redundant on PG15+, explicit for PG13/14
GRANT  USAGE, CREATE ON SCHEMA public TO quota_user;

-- ---------- Resource / Orchestrator service ----------
\connect postgres
CREATE DATABASE resource_db;
CREATE USER resource_user WITH PASSWORD 'changeme';

REVOKE ALL     ON DATABASE resource_db FROM PUBLIC;
GRANT  CONNECT ON DATABASE resource_db TO resource_user;

\connect resource_db
ALTER SCHEMA public OWNER TO resource_user;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT  USAGE, CREATE ON SCHEMA public TO resource_user;
```

Why `ALTER SCHEMA public OWNER TO <service_user>` rather than a longer grant list: the service
user then **owns** every object its migrations create, so no `ALTER DEFAULT PRIVILEGES` is
needed for future migrations, and NFR-2's boundary becomes structural (one database, one owner)
instead of a list someone has to maintain. Least privilege is preserved where it matters — the
role is not a superuser, has no `CREATEDB`/`CREATEROLE`, and cannot connect to any other
service's database. A service that cannot run its own migrations cannot start, so "no DDL
rights" is not an option here.

Verification commands for the evidence folder (NFR-2):

```powershell
# Each user reaches only its own database.
psql "postgresql://quota_user:changeme@localhost:5432/quota_db"    -c "select current_database(), current_user;"
psql "postgresql://quota_user:changeme@localhost:5432/resource_db" -c "select 1;"   # must FAIL
# Neither user is privileged.
psql -U postgres -c "select rolname, rolsuper, rolcreatedb, rolcreaterole from pg_roles where rolname like '%_user';"
```

The U2–U4 databases (`billing_db`, `notification_db`, `provider_db`) follow the identical
pattern; their script is their own unit's deliverable, and `CONTRIBUTING.md` points at this
section so nobody repeats the PG15 mistake.

---

## 4. Seed and Reference Data

**Decision: seed data ships as a separate re-runnable script, not as a Flyway migration.**

A versioned migration runs once per database, but AC-1.5 requires a reset script that restores
the starting state so a challenge run is repeatable, and the Testcontainers tests need fixtures
built per test. Data in `V2__seed.sql` would also mean production-shaped schema history
carrying demo rows for ever. Flyway's repeatable migrations (`R__`) were considered and rejected
too: they re-run on checksum change, not on demand, which is not the trigger a demo needs.

`scripts/db/seed-u1.sql` (invoked by `scripts/seed.ps1`), idempotent by construction:

```sql
-- Demo tenants with FIXED ids (FR-4), so scripts and evidence are repeatable.
INSERT INTO tenants (tenant_id, name, tier) VALUES
    ('tnt-free-001',  'Free Tier Demo Org',   'FREE'),
    ('tnt-std-001',   'Acme Corp',            'STANDARD'),
    ('tnt-ent-001',   'Globex Enterprise',    'ENTERPRISE'),
    ('tnt-chal1-001', 'Concurrency Test Org', 'STANDARD')
ON CONFLICT (tenant_id) DO NOTHING;

-- Limits come from FR-5 per tier; usage starts at zero.
INSERT INTO tenant_quota (tenant_id, cpu_limit, ram_limit_gb, instance_limit) VALUES
    ('tnt-free-001',    2,   4,   1),
    ('tnt-std-001',    20,  64,  10),
    ('tnt-ent-001',   200, 512, 100),
    ('tnt-chal1-001',  20,  64,  10)
ON CONFLICT (tenant_id) DO NOTHING;
```

| Tenant | Tier | Limits (CPU / GB / instances) | Used by |
|--------|------|-------------------------------|---------|
| `tnt-free-001` | FREE | 2 / 4 / 1 | BR8 demo (CLUSTER → 422) and the instance-limit demo |
| `tnt-std-001` | STANDARD | 20 / 64 / 10 | Happy path, Challenge 2, Challenge 3C |
| `tnt-ent-001` | ENTERPRISE | 200 / 512 / 100 | Headroom for ad-hoc demos |
| `tnt-chal1-001` | STANDARD | 20 / 64 / 10 | **Challenge 1 only**, kept separate so a failed run cannot poison the other scenarios |

**Challenge 1 arithmetic (AC-1.1, AC-1.2).** 20 parallel requests of 4 CPU / 8 GB each against
`tnt-chal1-001`: CPU admits 5 (5 × 4 = 20 = limit), RAM would admit 8 (64 / 8) and instances
10. CPU is therefore the binding dimension and **N = 5**: exactly 5 requests succeed, 15 are
rejected with 422 `QUOTA_EXCEEDED` and `exhausted: ["CPU"]`.

`scripts/db/reset-u1.sql` (invoked by `scripts/reset.ps1`) restores that starting state
(AC-1.5, TR-8):

```sql
-- quota_db
TRUNCATE TABLE quota_reservations;
UPDATE tenant_quota SET cpu_used = 0, ram_used_gb = 0, instance_used = 0;
-- resource_db
TRUNCATE TABLE idempotency_records, reservations, resources;
```

`reservations` and `resources` must be truncated **in the same statement** (or with `CASCADE`):
`reservations.resource_id` is a real foreign key to `resources`, and PostgreSQL refuses to
truncate a referenced table on its own. `idempotency_records` carries no foreign key (§2.2), so
it is listed only for convenience — one statement is simpler to keep correct than three.
Tenants and limits are left alone: resetting usage is enough, and keeping the tenants makes a
rerun one step shorter.

---

## 5. Environment Variables

Defaults live in each service's `application.yml` as `${VAR:default}` (NFR-18, NFR-19); the
rationale and the full `application.yml` shape are in `nfr-design.md` §5. Repeated here as the
operational contract.

### 5.1 Quota service (`:8081`)

| Variable | Purpose | Default | Required |
|----------|---------|---------|----------|
| `SERVER_PORT` | HTTP port | `8081` | optional |
| `DB_HOST` | PostgreSQL host | `localhost` | optional |
| `DB_PORT` | PostgreSQL port | `5432` | optional |
| `DB_NAME` | database name | `quota_db` | optional |
| `DB_USER` | least-privilege user | `quota_user` | optional |
| `DB_PASSWORD` | user password | `changeme` | optional locally, **required** elsewhere |
| `DB_POOL_MAX` | Hikari maximum pool size. **25, not the Hikari default of 10**: Challenge 1 sends 20 concurrent reserve calls, each holding one connection for the atomic UPDATE (§5.3) | `25` | optional |
| `LOG_LEVEL` | root log level | `INFO` | optional |
| `LOG_LEVEL_APP` | `com.crm` log level | `INFO` | optional |

### 5.2 Orchestrator service (`:8082`)

| Variable | Purpose | Default | Required |
|----------|---------|---------|----------|
| `SERVER_PORT` | HTTP port | `8082` | optional |
| `DB_HOST` / `DB_PORT` | PostgreSQL location | `localhost` / `5432` | optional |
| `DB_NAME` | database name | `resource_db` | optional |
| `DB_USER` / `DB_PASSWORD` | credentials | `resource_user` / `changeme` | optional locally |
| `DB_POOL_MAX` | Hikari maximum pool size. **25** (§5.3) | `25` | optional |
| `LOG_LEVEL` / `LOG_LEVEL_APP` | log levels | `INFO` | optional |
| `QUOTA_SERVICE_URL` | real peer (same unit) | `http://localhost:8081` | optional |
| `PROVIDER_SERVICE_URL` | peer; point at WireMock during U1 | `http://localhost:8085` | optional |
| `BILLING_SERVICE_URL` | peer; point at WireMock during U1 | `http://localhost:8083` | optional |
| `NOTIFICATION_SERVICE_URL` | peer; point at WireMock during U1 | `http://localhost:8084` | optional |
| `QUOTA_CONNECT_TIMEOUT_MS` / `QUOTA_READ_TIMEOUT_MS` | NFR-25 | `2000` / `2000` | optional |
| `PROVIDER_CONNECT_TIMEOUT_MS` / `PROVIDER_READ_TIMEOUT_MS` | NFR-25 (provider sleeps 1–3 s) | `2000` / `10000` | optional |
| `BILLING_CONNECT_TIMEOUT_MS` / `BILLING_READ_TIMEOUT_MS` | NFR-25 | `2000` / `3000` | optional |
| `NOTIFICATION_CONNECT_TIMEOUT_MS` / `NOTIFICATION_READ_TIMEOUT_MS` | NFR-25 | `2000` / `2000` | optional |
| `RETRY_MAX_ATTEMPTS` | NFR-26, first attempt included | `3` | optional |
| `RETRY_INITIAL_BACKOFF_MS` | NFR-26 | `200` | optional |
| `RETRY_MULTIPLIER` | NFR-26 | `2.0` | optional |
| `RETRY_MAX_BACKOFF_MS` | NFR-26 | `2000` | optional |
| `RETRY_JITTER_RATIO` | NFR-26 jitter (±50 %) | `0.5` | optional |
| `BILLING_RATE_PER_CPU_HOUR` | rate sent to Billing (FR-28) | `0.12` | optional |
| `SAGA_POOL_CORE` / `SAGA_POOL_MAX` / `SAGA_POOL_QUEUE` | saga executor sizing | `4` / `8` / `100` | optional |
| `IDEMPOTENCY_RETENTION_HOURS` | record retention | `24` | optional |
| `IDEMPOTENCY_SWEEP_INTERVAL_MS` | expired-record sweep period | `3600000` | optional |

Every variable is optional for a local run: both services start against a local PostgreSQL with
an empty environment (NFR-19). `DB_PASSWORD` is the one that must be supplied anywhere that is
not a developer laptop.

### 5.3 Why `DB_POOL_MAX` is 25 and not the Hikari default of 10

Challenge 1 fires **20 simultaneous** `POST /api/v1/resources` (§7.3). Each of those requests
holds one orchestrator connection for the whole synchronous transaction — the idempotency-key
insert, the blocking HTTP call to Quota, and the two row inserts — and causes one concurrent
reserve on Quota, which holds one Quota connection for the atomic conditional UPDATE. With a
pool of 10, both services would run at half concurrency: Hikari does not fail, it **queues**
(up to `connectionTimeout`, 30 s by default), so the counts in the evidence would still be
correct but the wall-clock timings would be a queueing artefact rather than the behaviour the
timeout analysis in `nfr-design.md` §8.1 describes. A reviewer reading a slow run should not
have to work out which.

25 is 20 plus headroom for the polling reads, `/health` and the idempotency sweep that run
alongside. It is well inside PostgreSQL's default `max_connections` of 100 even with both U1
services plus the U2–U4 services at the same default. The pool is still bounded, deliberately:
the point of the frozen design is that over-allocation is prevented by the SQL predicate, not by
restricting how many requests can arrive at once.

---

## 6. Local Run

### 6.1 Prerequisites (the toolchain checkpoint, `units.md` §9)

JDK 21, PostgreSQL 16/17, PowerShell 7, Git, and Docker Desktop **running** for the
Testcontainers tests. Maven is not needed: the wrapper is committed.

### 6.2 Start order

| Step | What | Command (PowerShell 7) | Why this order |
|------|------|------------------------|----------------|
| 1 | PostgreSQL server | (service, already running) | Both services fail fast without it |
| 2 | Databases and users, **once** | `psql -U postgres -f scripts/db/setup-u1.sql` | Flyway needs the database and the schema grants of §3 |
| 3 | WireMock (stands in for Provider, Billing, Notification) | `scripts/start-wiremock.ps1` | The orchestrator does not need it to start, but the saga does to complete |
| 4 | Quota | `cd services/quota; ./mvnw spring-boot:run` | Flyway applies `V1__baseline.sql`; the orchestrator's synchronous reserve needs it up |
| 5 | Seed demo tenants | `scripts/seed.ps1` | Needs Quota's schema to exist (step 4 created it) |
| 6 | Orchestrator | `cd services/resource; ./mvnw spring-boot:run` | Last: it is the only service with peers |
| 7 | Smoke check | `curl http://localhost:8081/health; curl http://localhost:8082/health` | Both must report `UP` with a `db` component |

Steps 4 and 6 start services in the foreground; use two shells. Quota can run with nothing else
on the machine except PostgreSQL — which is the point of it being the reference implementation.

### 6.3 Pointing the orchestrator at WireMock instead of real peers

One WireMock process serves all three stubbed peers (their paths do not collide), so only the
three URLs change:

```powershell
$env:PROVIDER_SERVICE_URL     = "http://localhost:8090"
$env:BILLING_SERVICE_URL      = "http://localhost:8090"
$env:NOTIFICATION_SERVICE_URL = "http://localhost:8090"
# QUOTA_SERVICE_URL is left alone: Quota is real, it is in this unit.
cd services/resource; ./mvnw spring-boot:run
```

When U2–U4 are delivered, those three exports are dropped and the defaults (`:8083`, `:8084`,
`:8085`) take over. No code change, no rebuild, no profile — which is NFR-18 doing its job and a
rehearsal for Assignment 2.

---

## 7. WireMock Setup for U1

Two modes, both used, for different jobs.

### 7.1 Standalone, for demos and the challenge scripts

One `wiremock-standalone` jar on port **8090**, started by `scripts/start-wiremock.ps1`:

```powershell
java -jar tools/wiremock-standalone-<pinned-version>.jar `
     --port 8090 `
     --root-dir stubs/u1 `
     --verbose          # request journal in the console = evidence for AC-2.2 and AC-3.3
```

`stubs/u1/mappings/` holds one JSON file per stub. One process is enough because the three peers
have disjoint paths; a separate process per peer on its real port was considered and rejected as
three windows to manage during a recorded demo, with no extra fidelity.

| Peer | Stub | Returns |
|------|------|---------|
| Provider | `POST /api/v1/provider/instances` (default) | 201 `{ "instanceId": "i-mock-{{randomValue type='ALPHANUMERIC' length=8}}", "status": "ACTIVE" }`, fixed delay 1200 ms to imitate FR-40 |
| Provider | same path, header `X-Simulate-Failure: always-fail` | 503 `{ "code": "SIMULATED_FAILURE", ... }` — drives compensation at step 2 |
| Provider | `DELETE /api/v1/provider/instances/{id}` | 200. The compensation target (AC-3.3) |
| Billing | `POST /api/v1/billing/meters` (default) | 201 `{ "meterId": "...", "state": "ACTIVE" }` |
| Billing | **same path, header `X-Simulate-Failure: always-fail`** | **503 `{ "code": "SIMULATED_FAILURE" }` — the forced step-3 failure that produces the Challenge 3C compensation evidence** |
| Billing | `POST /api/v1/billing/meters/{resourceId}/stop` | 200 |
| Notification | `POST /api/v1/notifications` | 202 |

The forced-503 Billing stub, matched on the header rather than on a scenario, is what makes
Challenge 3C deterministic and repeatable: the same request without the header succeeds, so one
script can show both paths. Header matching also proves FR-46 end to end — the header was
accepted before the 202, persisted on the reservation, and replayed by a background thread
minutes of wall-clock later; if propagation were broken, the stub would simply return 201 and
the saga would succeed, so the evidence is self-checking.

```json
// stubs/u1/mappings/billing-start-meter-forced-failure.json
{
  "priority": 1,
  "request": {
    "method": "POST",
    "urlPath": "/api/v1/billing/meters",
    "headers": { "X-Simulate-Failure": { "equalTo": "always-fail" } }
  },
  "response": {
    "status": 503,
    "headers": { "Content-Type": "application/problem+json" },
    "jsonBody": {
      "type": "https://crm.example/errors/simulated-failure",
      "title": "Simulated failure", "status": 503,
      "detail": "Billing meter start failed by injection",
      "code": "SIMULATED_FAILURE"
    }
  }
}
```

The default Billing stub carries `"priority": 5`, so the header-matched stub wins when the header
is present.

### 7.2 Programmatic, inside the tests

`@WireMockTest(httpPort = 0)` (random port) with `@DynamicPropertySource` overriding the three
peer URLs, so the saga tests own their stubs and run in CI order without a shared process:

```java
@SpringBootTest
@WireMockTest
class ProvisioningSagaCompensationTest {
    @DynamicPropertySource
    static void peers(DynamicPropertyRegistry r, WireMockRuntimeInfo wm) {
        r.add("peers.provider.url",     wm::getHttpBaseUrl);
        r.add("peers.billing.url",      wm::getHttpBaseUrl);
        r.add("peers.notification.url", wm::getHttpBaseUrl);
    }
}
```

Tests assert against the **request journal**, which is the only way to prove negatives such as
AC-2.2 ("the repeat performs no second provider call") and AC-3.3 (reverse order):

```java
verify(1, postRequestedFor(urlPathEqualTo("/api/v1/provider/instances"))
        .withHeader("X-Correlation-Id", equalTo(correlationId)));       // NFR-14 across async
verify(1, deleteRequestedFor(urlPathMatching("/api/v1/provider/instances/.*")));
InOrder order = inOrder(/* journal-derived sequence */);                 // provider delete BEFORE quota release
```

### 7.3 How the three challenge scripts use it

| Script | WireMock role | Asserted output |
|--------|---------------|-----------------|
| `scripts/challenge1-concurrency.ps1` | Default stubs; 20 parallel `POST /api/v1/resources` for `tnt-chal1-001`, each 4 CPU / 8 GB, a **distinct** `Idempotency-Key` per request so Challenge 2 does not mask Challenge 1 | 5 × 202, 15 × 422 `QUOTA_EXCEEDED`; `cpu_used = 20`; 5 `RESERVED` ledger rows (AC-1.2 to AC-1.4) |
| `scripts/challenge2-idempotency.ps1` | Default stubs; 10 sequential repeats of one key, then **10 concurrent** repeats of a second key (AC-2.3 asks for ten, so the script fires ten — matching `IdempotencyConcurrencyIT`), then one reuse with an altered body | identical 202 bodies incl. the same `resourceId`; exactly 1 `resources` row and 1 `idempotency_records` row per key; 422 `IDEMPOTENCY_PAYLOAD_MISMATCH` on the altered body; one provider call in the journal (AC-2.1 to AC-2.4) |
| `scripts/challenge3-saga.ps1` | **Forced-503 Billing stub** via `X-Simulate-Failure: billing:always-fail` | `resources.status = FAILED` with `failure_reason` **prefixed `BILLING_UNAVAILABLE:`**; `provider_instance_id` cleared; `DELETE /provider/instances/{id}` then quota release in the journal, in that order; `tenant_quota` back to its pre-request values; `quota_reservations.state = RELEASED`; `saga.compensation.step order=1,2` log lines (AC-3.1 to AC-3.8) |

**How far the saga got, now that the instance id is cleared.** `provider_instance_id IS NULL` on
the FAILED row asserts AC-3.5 (nothing orphaned), but it no longer distinguishes a saga that
failed *before* provisioning from one that failed *after* — the second case of
`application-design.md` §8.4. The script therefore asserts the two surviving signals explicitly,
and `evidence/challenge-3/` carries both:

| Signal | Where the script reads it | What it proves |
|--------|---------------------------|----------------|
| `failure_reason` prefix | `db-after.txt` | `BILLING_UNAVAILABLE:` is reachable only with step 2 complete, so provisioning had succeeded and had to be undone. A step-2 failure would read `PROVIDER_UNAVAILABLE:` instead |
| WireMock journal | `output.txt` | The `DELETE /api/v1/provider/instances/{id}` names the instance that existed and was terminated, i.e. the id the column no longer holds |

Recorded as deviation **A11** in `functional-design.md` §13, so the reason the column is cleared
and the replacement evidence are in one place.

Each script captures command, output and a timestamp, plus the before/after `SELECT`s, into
`evidence/challenge-<n>/` (ER-1 to ER-4). All three are re-runnable after `scripts/reset.ps1`.

### 7.4 Evidence file names

Fixed here so the traceability table in `functional-design.md` §12.1 can name files rather than
folders, which is what DOC-4 and ER-5 ask for.

| Folder | Files | Written by |
|--------|-------|-----------|
| `evidence/challenge-1/` | `output.txt` (counted 202/422 results), `db-before.txt`, `db-after.txt` (`tenant_quota` + ledger rows), `explanation.md` (why check-then-update fails, AC-1.6) | `challenge1-concurrency.ps1` |
| `evidence/challenge-2/` | `output.txt` (first call, sequential repeats, 10 concurrent repeats, the mismatch 422), `db-after.txt`, `replay-logs.txt` (`idempotency.replay` lines, AC-2.5) | `challenge2-idempotency.ps1` |
| `evidence/challenge-3/` | `output.txt`, `db-before.txt`, `db-after.txt` (resource `FAILED`, quota restored, ledger `RELEASED`), `compensation-logs.txt` (`saga.compensation.step order=1,2`, AC-3.3) | `challenge3-saga.ps1` |
| `evidence/e2e/` | `e2e-output.txt` (main transaction plus one failure case, including the WireMock journal) | `e2e.ps1` (TR-6) |
| `evidence/unit-tests/` | `u1-test-report.txt` (Surefire summary for both services, naming the BR tests) | the Build and Test stage |
| `evidence/infra/` | `db-privileges.txt` (the §3.2 verification commands, NFR-2), `dependency-tree.txt` (NFR-24) | the Build and Test stage |

Every path above is a forward commitment, not an artefact that exists yet: the scripts
themselves are emitted at Code Generation and run in Build and Test.

---

## 8. Testcontainers Configuration (BR1, BR3, BR7)

TR-3 requires database-backed tests for the three SQL-enforced rules, because a mocked
repository cannot exercise a conditional UPDATE or a unique constraint.

```java
// src/test/java/.../support/PostgresTestBase.java   (one per service)
@SpringBootTest
@Testcontainers
abstract class PostgresTestBase {

    // Singleton pattern: one container for the whole suite. Starting a container per test class
    // would add ~5 s each and the concurrency tests need a warm pool, not a cold start.
    @Container
    @ServiceConnection                       // Spring Boot wires url/user/password automatically
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withReuse(true);

    // Flyway runs against the container exactly as it does in production (NFR-23), so the test
    // exercises the real constraints from V1__baseline.sql — not a Hibernate-generated schema.
}
```

| Test | Rule | Shape |
|------|------|-------|
| `QuotaReservationAtomicityIT` | **BR1** | 20 threads, one `CountDownLatch`, 4 CPU / 8 GB each against a 20-CPU tenant → exactly 5 succeed, `cpu_used = 20`, 5 ledger rows (TR-4) |
| `QuotaReleaseLedgerIT` | **BR7** | 10 threads release the same `resourceId` → exactly one `released: true`, usage back to its pre-reservation value, `released_at` set once |
| `IdempotencyConcurrencyIT` | **BR2** | 10 concurrent identical `POST /api/v1/resources` (WireMock peers) → 1 `resources` row, 1 `idempotency_records` row, identical bodies (TR-5) |
| `IdempotencyPayloadMismatchIT` | **BR3** | same key, altered body → 422, and the first record unchanged |
| `TerminationIdempotencyIT` | **BR7** (orchestrator gate) | concurrent + repeated terminations → one teardown, one quota release call in the journal |
| `TenantCreationConflictIT` | A2's 409 | duplicate client-supplied `tenantId` → 409 `TENANT_ALREADY_EXISTS`, one `tenants` row (`functional-design.md` §12.3) |

Two of these also prove the savepoint recovery of `functional-design.md` §7.2.1 rather than just
the constraint: `IdempotencyConcurrencyIT` and `QuotaReleaseLedgerIT.reserveAfterReleaseIsRejected`
only pass if the loser of the duplicate-key race can still read inside its transaction. A stub
guard in the unit tests cannot show that, which is precisely why these two are database-backed.

**Docker must be running** for all six. Without a reachable daemon they fail at container
startup, not at an assertion — so the README states the prerequisite, and `scripts/verify-toolchain.ps1`
checks `docker ps` before the suite is suggested. `.withReuse(true)` needs
`testcontainers.reuse.enable=true` in `~/.testcontainers.properties`; without it the container
is simply recreated, which is slower but correct, so no test depends on reuse.

Tests clean their own fixtures (`@Sql` per test or an explicit truncate in `@BeforeEach`) rather
than relying on `@Transactional` rollback: a `@Transactional` test would roll back the very
commits the concurrency assertions need to observe from other threads. That detail is the usual
reason a "working" concurrency test silently proves nothing.

---

## 9. Forward Compatibility with Assignment 2

No Docker work is done now. These four properties are what make it a configuration exercise
later rather than a rewrite, and each is already an obligation in `nfr-requirements.md`:

| Property | Where it already holds | What Assignment 2 will do with it |
|----------|------------------------|-----------------------------------|
| Logs to stdout as JSON, no file appender (NFR-15) | `nfr-design.md` §4.1 | The container runtime collects stdout; no volume mount, no log rotation |
| All configuration from environment variables with defaults (NFR-18, NFR-19) | §5 above | An env block per container swaps `localhost` for service names; the same jar, unrebuilt |
| `GET /health` with a real database check (NFR-12) | `nfr-design.md` §7 | Container liveness and readiness probes |
| Stateless services; all state in the owning database (NFR-21) | §2 above | Containers are replaceable; the one caveat is the in-flight saga queue, recorded as a limitation in `functional-design.md` §13 (A6) |

One database per service also means Assignment 2 can run a single PostgreSQL container with
five databases, or five containers, without touching application code.

---

## 10. Open Questions and Assumptions

| ID | Issue | Reading taken |
|----|-------|---------------|
| **I1** | `units.md` §9's grant script is insufficient on PostgreSQL 15+ (see §3.1) | Corrected here and in `nfr-design.md` §6.1, with `ALTER SCHEMA public OWNER TO <service_user>`. The approved `units.md` is **not** edited |
| **I2** | `application-design.md` §4.2 lists no column for the persisted `X-Simulate-Failure`, but FR-46 requires persisting it | Added `reservations.simulate_failure varchar(40)`. Internal only; no frozen API shape changes (same as `functional-design.md` §13 A7) |
| **I3** | The frozen data models give column lists without types | Types chosen here: `varchar(64)` for externally-supplied ids, `uuid` for ids this unit mints, `timestamptz` for all instants (NFR-22), `text` for free-form reasons, `char(64)` for the SHA-256 fingerprint. `resource_id` is `uuid` in `resource_db` but `varchar(64)` in `quota_db`, because to Quota it is an opaque foreign identifier (NFR-4) and typing it as `uuid` there would assert knowledge Quota should not have |
| **I4** | Neither document says whether seed data is a migration | Separate re-runnable script (§4), because AC-1.5's reset must be repeatable and migrations must not carry demo rows |
| **I5** | WireMock port is not fixed anywhere in the frozen inputs | `8090` for the standalone process (outside the 8081–8087 service range so it can never be mistaken for a real peer); random ports in tests |
| **I6** | `tenant_quota` has no `tier` column, yet the quota response reports the tier | Read from `tenants` by primary key in the same database and the same transaction. Duplicating the tier onto `tenant_quota` was rejected: two copies of an immutable fact that could still drift |

---

## 11. Stage Acceptance

This stage is complete when approved. Specifically:

- The complete `V1__baseline.sql` for `quota_db` and for `resource_db` is specified with exact
  types, `NOT NULL`, defaults, primary keys, the unique constraints enforcing BR3 and BR7,
  CHECK constraints for every enum-like column, and only those indexes the functional design's
  query patterns justify — with each constraint's business rule called out inline and summarised
  in §2.3.
- Seed and reference data is defined with fixed demo tenant IDs, the Challenge 1 arithmetic that
  makes N = 5, and the decision that seeds ship as a re-runnable script rather than a migration
  (§4).
- The PostgreSQL setup script is corrected for PostgreSQL 15+ schema privileges, with the reason
  the approved `units.md` draft is insufficient stated explicitly (§3).
- Every environment variable for both services is tabulated with purpose, default and whether it
  is required, including the connection-pool sizing that Challenge 1's concurrency demands (§5).
- Local run instructions give start order, ports and the exact exports that point the
  orchestrator at WireMock instead of real peers (§6).
- The WireMock setup is specified in both modes, with the stub mappings for Provider, Billing and
  Notification including the forced-503 Billing stub that produces the Challenge 3C compensation
  evidence, how each of the three challenge scripts drives it, and the evidence file names the
  traceability table in `functional-design.md` §12.1 points at (§7, §7.4).
- The Testcontainers configuration for the BR1/BR3/BR7 tests is specified, with the Docker
  prerequisite stated plainly (§8).
- Forward-compatibility notes for Assignment 2 are recorded without any Docker work being done
  (§9).

**Next stage for U1**: Code Generation — two Spring Boot skeletons with these migrations, the
configuration templates, controller and service method signatures with TODOs, and test classes
whose names assert the rules above, for Prajwal to implement. The toolchain checkpoint
(`units.md` §9, corrected by §3 here) must be completed first, because Code Generation is the
first stage that compiles and runs code.



