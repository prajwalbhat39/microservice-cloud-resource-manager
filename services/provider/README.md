# Provider Mock Service (U3 - Vinayak)

Independent Java 21 / Spring Boot service implementing FR-19 and FR-39 through FR-44.
Default port: **8085**. Owns **provider_db**; no other service's database is accessed.

## Build and run

Create the database once using the PostgreSQL superuser:

```powershell
psql -U postgres -h localhost -f scripts\db\setup-u3.sql
```

From this service directory:

```powershell
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

On Linux/macOS use `sh mvnw verify` and `sh mvnw spring-boot:run`.
The root Maven aggregator also includes this service.

### Connect the orchestrator

U1's resource service still defaults to the WireMock peer at port 8090. Before
starting the resource service, point its existing provider URL override at U3:

```powershell
$env:PROVIDER_SERVICE_URL = "http://localhost:8085"
```

No orchestrator code changes are needed. Its retries reuse `clientToken` and its
targeted simulation modes are already forwarded to this API. Other U1 peer
defaults are unchanged.

### Operability endpoints

- `GET /health`: application and database connectivity.
- Swagger UI: `/swagger-ui.html`.
- Generated OpenAPI: `/v3/api-docs` and `/v3/api-docs.yaml`.

## API

### Provision

`POST /api/v1/provider/instances`

```json
{"clientToken":"resource-123","type":"VM","cpu":2,"ramGb":4}
```

Returns **201**, including on replay:

```json
{"instanceId":"i-mock-<uuid>","status":"ACTIVE"}
```

Types: `VM`, `CONTAINER`, `CLUSTER`. CPU must be 1-256, RAM 1-2048 GB, and
the nonblank client token must be at most 256 characters.

The first successful request owns the token. Replays return its persisted instance,
even if the new payload differs, without delay or failure injection. PostgreSQL's
unique `client_token` constraint and `INSERT ... ON CONFLICT DO NOTHING` protect
concurrent calls and retries across process restarts. SQL uses `JdbcTemplate` rather
than JPA so the atomic PostgreSQL conflict handling is explicit; Flyway alone owns DDL.
Failures before persistence create no instance.

### Terminate

`DELETE /api/v1/provider/instances/{id}` returns **200**:

```json
{"instanceId":"i-mock-<uuid>","status":"TERMINATED"}
```

An unknown ID returns **404** (`INSTANCE_NOT_FOUND`). Termination keeps the row and
client token. Repeated termination returns 200 without simulation and preserves the
original termination timestamp. Re-provisioning that token returns `TERMINATED`;
it never resurrects the instance. Use a new token for a new instance.

### Simulation

New provisioning and active-instance termination calls use a uniformly random,
inclusive delay between the configured bounds and then the configured failure rate.
`X-Simulate-Failure` overrides the behavior for one call:

| Value | Behavior |
|-------|----------|
| absent | Configured delay and failure rate |
| `always-fail` or `true` | Configured delay, then 503 `SIMULATED_FAILURE` |
| `always-succeed` or `false` | Configured delay, no random failure |
| `slow` | `PROVIDER_SLOW_DELAY_MS` delay, no random failure |

Values are trimmed and case-insensitive. Unknown values use the configured defaults
and are logged at DEBUG, consistent with the orchestrator. The orchestrator already
strips `provider:` from targeted modes before forwarding them here. Known-token and
already-terminated replays take precedence over simulation so retries remain reliable.
Interrupted waits restore the thread's interrupt flag and return 503 `PROVIDER_UNAVAILABLE`.

All MVC errors use RFC 9457 `application/problem+json`, with `code`, `correlationId`
and UTC `timestamp`. Validation errors include `errors` entries naming invalid fields.
`X-Correlation-Id` is echoed or generated and included in structured JSON logs.
Request payloads and credentials are not logged.

## Configuration

| Environment variable | Default | Meaning |
|----------------------|---------|---------|
| `SERVER_PORT` | `8085` | HTTP port |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `provider_db` | Owning database |
| `DB_USER` | `provider_user` | Dedicated database role |
| `DB_PASSWORD` | `changeme` | Local placeholder; override outside development |
| `DB_POOL_MAX` | `10` | Maximum database connections |
| `PROVIDER_FAILURE_RATE` | `0.2` | Probability in [0,1] |
| `PROVIDER_DELAY_MIN_MS` | `1000` | Minimum delay, nonnegative integer |
| `PROVIDER_DELAY_MAX_MS` | `3000` | Maximum delay, at least the minimum |
| `PROVIDER_SLOW_DELAY_MS` | `10000` | Nonnegative deterministic slow-mode delay |
| `LOG_LEVEL` | `INFO` | Root log level |
| `LOG_LEVEL_APP` | `INFO` | Application log level |

Invalid simulation configuration fails startup rather than silently using defaults.
All instance state and UTC timestamps are persisted in PostgreSQL using the
[Flyway migration](src/main/resources/db/migration/V1__baseline.sql).

## Verification

`verify` runs unit tests plus HTTP integration tests against a real, ephemeral
PostgreSQL server using the test-scoped Zonky embedded PostgreSQL dependency.
Docker and a pre-existing database are not required. Native PostgreSQL test binaries
are downloaded through Maven for the host platform; Linux must run tests as a
non-root user (PostgreSQL rejects root). The test database and HTTP port are isolated
and shut down with the test context; integration tests are not silently skipped.

Coverage includes probability boundaries, inclusive delay bounds, slow mode, forced
overrides, interrupted waits, validation, problem details, health, 16 simultaneous
same-token HTTP requests, the database uniqueness constraint, idempotent termination,
and no token resurrection.

Regenerate the committed [OpenAPI specification](openapi.yaml) from the running
integration-test service:

```powershell
.\mvnw.cmd verify "-Dprovider.export-openapi=openapi.yaml"
```

The export normalizes the random test server URL to the default port 8085.
