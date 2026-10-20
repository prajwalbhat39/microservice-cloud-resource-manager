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
--   2. count/list a tenant's RESERVED rows for Challenge 1 evidence and demo queries
CREATE INDEX ix_quota_reservations_tenant_state ON quota_reservations (tenant_id, state);
