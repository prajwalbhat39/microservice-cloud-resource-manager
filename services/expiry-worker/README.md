# Expiry Worker

Requests termination of expired resources through the orchestrator API. It has no database
and exposes `/health` and `/v3/api-docs.yaml`.

## Run

Use the project's Java toolchain (`pom.xml`) and committed Maven Wrapper:

```sh
cd services/expiry-worker
./mvnw spring-boot:run
curl http://localhost:8087/health
```

On Windows use `mvnw.cmd`. Every 30000 ms after startup and after the previous sweep completes:

1. Call `GET /api/v1/resources/expired`.
2. Read `items`, each with a UUID `resourceId` and UTC `expiresAt`.
3. Skip missing or future expiry values; call `POST /api/v1/resources/{id}/termination` for each
   expired candidate. No request body or Idempotency-Key is required.
4. Log acceptance or failure and continue with other candidates. The next sweep can retry
   candidates still returned by the orchestrator.

One correlation ID covers each sweep and its HTTP calls. Fetch errors, malformed JSON and read
timeouts defer to the next sweep. One failed or conflicting termination never stops the other
candidates. Concurrent workers and manual termination are safe **only because the orchestrator
owns the atomic transition and exactly-once quota-release guard**. A 202 acknowledges the
request; cleanup may still be pending.

Resource retains durable teardown work after accepting termination and retries failed cleanup
independently of the expired-candidate list. The worker does not duplicate that state.

## Configuration and tests

`.env.example` lists settings and defaults; export them in the shell rather than expecting
Spring to load the example file. `RESOURCE_SERVICE_URL` defaults to `http://localhost:8082`.
Connect/read timeouts default to 1000/2000 ms. `EXPIRY_CHECK_INTERVAL_MS` defaults to 30000;
`EXPIRY_ENABLED=false` disables the schedule. `SERVER_PORT` defaults to 8087.

```sh
./mvnw verify
```

HTTP tests cover candidate processing, sweep correlation, failure recovery on
the next sweep, stale conflicts, empty lists, invalid/future candidates, malformed responses,
timeouts and independent health. The process test also exercises the scheduler.
No PostgreSQL or Docker is needed for this service.

`GET /v3/api-docs.yaml` documents the health endpoint and is exported by `scripts/u4/e2e.ps1`.

[Design and integration notes](../../aidlc-docs/construction/u4-supporting/design.md)
