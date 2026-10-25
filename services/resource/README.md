# Resource / Orchestrator Service

Port `8082`, database `resource_db`. The tenant-facing entry point: owns resource lifecycle,
async reservations and idempotency records; drives the provisioning saga. Carries
**Challenge 2** (idempotency) and **Challenge 3C** (saga with compensation). Enforces
**BR2, BR3, BR4, BR6**, plus the orchestrator half of **BR7**.

Defaults use real peers: Quota on 8081, Billing on 8083, Provider on 8085 and Notification on 8084.
Set the peer URL variables to `http://localhost:8090` for isolated stub demonstrations.

## Run

```powershell
# From this folder. Needs PostgreSQL running and resource_db/resource_user created
# (see ../../scripts/db/setup-u1.sql), with the four peers running.
./mvnw spring-boot:run
```

| Variable | Default | Purpose |
|----------|---------|---------|
| `SERVER_PORT` | `8082` | HTTP port |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` | PostgreSQL location |
| `DB_NAME` | `resource_db` | database |
| `DB_USER` / `DB_PASSWORD` | `resource_user` / `changeme` | credentials |
| `DB_POOL_MAX` | `25` | Hikari pool (Challenge 1's 20 concurrent provisions each hold one connection) |
| `QUOTA_SERVICE_URL` | `http://localhost:8081` | real peer |
| `PROVIDER_SERVICE_URL` / `BILLING_SERVICE_URL` / `NOTIFICATION_SERVICE_URL` | ports `8085` / `8083` / `8084` on localhost | peer APIs |
| `*_CONNECT_TIMEOUT_MS` / `*_READ_TIMEOUT_MS` | per peer, see `application.yml` | NFR-25 |
| `RETRY_MAX_ATTEMPTS` / `RETRY_INITIAL_BACKOFF_MS` / `RETRY_MULTIPLIER` / `RETRY_MAX_BACKOFF_MS` / `RETRY_JITTER_RATIO` | see `application.yml` | NFR-26 |
| `BILLING_RATE_PER_CPU_HOUR` | `0.12` | sent to Billing on meter start |
| `SAGA_POOL_CORE` / `SAGA_POOL_MAX` / `SAGA_POOL_QUEUE` | `4` / `8` / `100` | saga executor sizing |
| `IDEMPOTENCY_RETENTION_HOURS` | `24` | Challenge 2 record retention |
| `IDEMPOTENCY_SWEEP_INTERVAL_MS` | `3600000` | expired-record sweep period |

## Endpoints

- `POST /api/v1/resources` — provision (main entry, FR-11). Headers: `Idempotency-Key`
  **required**, `X-Simulate-Failure` optional
- `GET  /api/v1/reservations/{id}` — poll reservation status (FR-16)
- `GET  /api/v1/resources/{id}` — read a resource (FR-17)
- `GET  /api/v1/resources?tenantId=&status=` — list a tenant's resources (FR-18)
- `POST /api/v1/resources/{id}/termination` — terminate, idempotent (FR-20 to FR-22)
- `GET  /api/v1/resources/expired` — internal, for the Expiry Worker (FR-24 support)
- `GET  /health` — liveness + DB connectivity

## OpenAPI

```powershell
curl http://localhost:8082/v3/api-docs.yaml -o openapi.yaml
```

## Recovery and tests

Provisioning records quota recovery intent before the remote reserve, then commits the
accepted resource, reservation and work record together. Activation and reservation completion
share a transaction. Work survives executor rejection and process restart.

Compensation stops uncertain Billing effects and cancels Provider provisioning by client token.
Failed peer operations remain pending; identifiers are retained until cleanup succeeds. Quota
is released after peer cleanup is confirmed. Termination also records durable cleanup work.
A 202 acknowledges the desired terminal state while that work can still be pending.

Recovery runs every 30000 ms by default; set `RECOVERY_INTERVAL_MS` to change it. Coordination
uses separate short-lived PostgreSQL connections so the application connection pool is available
to the work being recovered.

Run `./mvnw verify`. PostgreSQL tests use Testcontainers or `RESOURCE_TEST_DB_URL`, with optional
`RESOURCE_TEST_DB_USER` and `RESOURCE_TEST_DB_PASSWORD`. See the root README for dedicated test
setup and the seven-service integration test.
