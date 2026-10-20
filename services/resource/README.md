# Resource / Orchestrator Service

Port `8082`, database `resource_db`. The tenant-facing entry point: owns resource lifecycle,
async reservations and idempotency records; drives the provisioning saga. Carries
**Challenge 2** (idempotency) and **Challenge 3C** (saga with compensation). Enforces
**BR2, BR3, BR4, BR6**, plus the orchestrator half of **BR7**.

During U1, only Quota is a real peer (same unit, `:8081`). Provider, Billing and Notification
are stubbed with WireMock on `:8090` — see `../../scripts/start-wiremock.ps1` (to be added
alongside the WireMock stub mappings, infrastructure-design.md §8).

## Run

```powershell
# From this folder. Needs PostgreSQL running and resource_db/resource_user created
# (see ../../scripts/db/setup-u1.sql), plus Quota running on :8081 and WireMock on :8090
# for the saga to complete end to end. No local Maven install needed.
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
| `PROVIDER_SERVICE_URL` / `BILLING_SERVICE_URL` / `NOTIFICATION_SERVICE_URL` | `http://localhost:8090` | WireMock during U1 |
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

## Implemented by the owner (stubbed here)

This is the larger half of U1: Challenge 2's idempotency layer and Challenge 3C's saga and
compensation. Every stub's Javadoc lists the exact classes/methods already built for you to
call ("BUILDING BLOCKS ALREADY CREATED") and a numbered suggested-steps sequence. Recommended
build order (each depends only on what's above it):

1. `domain/RequestFingerprint.of` — SHA-256 of the canonical request body (BR3)
2. `domain/SimulateFailureDirective.normalise` / `.forProvider` / `.forBilling` — header routing
3. `domain/FailureReason.of` — the failure_reason prefix mapping (feeds A11's evidence)
4. `domain/ResourceLifecycle` — the transition table + `apply`/`canTransition`/`clearInstanceId` (BR4)
5. `persistence/SavepointGuard` — duplicate-key recovery (backs Challenge 2's concurrency guard)
6. `persistence/repository/ResourceRepository.markTerminatedIfActive` — BR7 gate 1's atomic UPDATE
7. `domain/service/IdempotencyService` — the Challenge 2 decision table (BR2, BR3)
8. `client/QuotaClient`, `client/ProviderClient`, `client/BillingClient`, `client/NotificationClient`
   — the four HTTP clients (`config/RestClientConfig` needs its four `@Bean` methods first)
9. `domain/service/ProvisioningService.provision` — the synchronous half (FR-13, FR-14)
10. `domain/service/SagaDispatcher`, `TeardownDispatcher` — after-commit dispatch
11. `domain/service/ProvisioningSaga` — the forward path and compensation (Challenge 3C, BR6)
12. `domain/service/TerminationService.terminate` — BR7 gate 1 at the service layer
13. `domain/service/ResourceQueryService`, `ReservationQueryService` — the read-only endpoints
14. `common/error/GlobalExceptionHandler` — the validation field-mapping handlers
15. `config/AsyncConfig` is already fully implemented (the MDC-propagating TaskDecorator) —
    nothing to do there

Tests: `RequestFingerprintTest`, `ResourceLifecycleTest`, `ClientTokenTest`,
`IdempotencyServiceTest`, `IdempotencyReplayTest`, `ProvisioningSagaCompensationTest` (unit);
`IdempotencyConcurrencyIT`, `IdempotencyPayloadMismatchIT`, `TerminationIdempotencyIT`
(Testcontainers — require Docker).

See `aidlc-docs/construction/u1-core/functional-design.md` for every algorithm in full.
