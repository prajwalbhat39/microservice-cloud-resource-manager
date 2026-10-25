# Quota Service

Port `8081`, database `quota_db`. Owns tenants, tiers, quota limits/usage and the reservation
ledger. Carries **Challenge 1** (concurrency control) and enforces **BR1, BR7, BR8**. This is the
reference implementation of the cross-cutting patterns the other services copy.

## Run

```powershell
# From this folder. Needs PostgreSQL running and quota_db/quota_user created
# (see ../../scripts/db/setup-u1.sql). No local Maven install needed — the wrapper is committed.
./mvnw spring-boot:run
```

All configuration is via environment variables with local defaults (so an empty environment
works against a local PostgreSQL). See the table below; full rationale in
`aidlc-docs/construction/u1-core/infrastructure-design.md` §5.1.

| Variable | Default | Purpose |
|----------|---------|---------|
| `SERVER_PORT` | `8081` | HTTP port |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` | PostgreSQL location |
| `DB_NAME` | `quota_db` | database |
| `DB_USER` / `DB_PASSWORD` | `quota_user` / `changeme` | credentials |
| `DB_POOL_MAX` | `25` | Hikari pool (sized for Challenge 1's 20 concurrent calls) |
| `LOG_LEVEL` / `LOG_LEVEL_APP` | `INFO` | log levels |

## Endpoints

- `POST /api/v1/tenants` — create a tenant (FR-1)
- `GET  /api/v1/tenants/{id}` — read a tenant (FR-2)
- `GET  /api/v1/tenants/{id}/quota` — usage and headroom (FR-9)
- `POST /api/v1/quota/reservations` — reserve quota atomically (FR-7, Challenge 1)
- `POST /api/v1/quota/reservations/{resourceId}/release` — release exactly once (FR-8, BR7)
- `GET  /health` — liveness + DB connectivity (NFR-12)

## OpenAPI

With the service running, the spec is served live and exported to a committed file:

```powershell
curl http://localhost:8081/v3/api-docs.yaml -o openapi.yaml
```

## Consistency and tests

Tenant creation inserts only when the ID is absent; a duplicate returns 409 without changing
limits or usage. Quota capacity is reserved by one conditional SQL update. Operations for the
same resource ID are serialized; a replay must match its tenant and allocation. Release is
idempotent. A cancellation record prevents delayed requests from reserving released capacity.

Run `./mvnw verify`. PostgreSQL tests use Testcontainers, or `QUOTA_TEST_DB_URL` with optional
`QUOTA_TEST_DB_USER` and `QUOTA_TEST_DB_PASSWORD`. Use a dedicated test database. See the root
README for the full setup and test command.
