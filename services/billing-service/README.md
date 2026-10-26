# Billing

Port 8083, database `billing_db`, role `billing_user`. Start and stop resource meters and calculate
cost with decimal arithmetic. The default rate is 0.12 per CPU-hour.

## Run

Create the database using `scripts/db/setup-u2-u4.sql`, then run from this directory:

```sh
./mvnw spring-boot:run
```

On Windows use `mvnw.cmd`. Configuration: `SERVER_PORT` defaults to 8083; `DB_HOST`/`DB_PORT` to
localhost/5432; `DB_NAME` to billing_db; `DB_USER` to billing_user; `DB_PASSWORD` to changeme.
`DEFAULT_CPU_RATE` defaults to 0.12 and `BILLING_FAILURE_RATE` to 0.

## API

| Method | Path | Behavior |
|---|---|---|
| POST | `/api/v1/billing/meters` | Start by resourceId; 201 new, 200 replay |
| POST | `/api/v1/billing/meters/{resourceId}/stop` | Idempotent stop; unknown meter returns 404 and prevents a late start |
| GET | `/api/v1/billing/meters/{resourceId}` | Meter and current cost |
| GET | `/api/v1/billing/tenants/{tenantId}/cost?period=YYYY-MM` | UTC calendar-month total |
| GET | `/health` | Database-aware health |
| GET | `/v3/api-docs.yaml` | Generated API specification |

Start requests contain `resourceId`, `tenantId`, `cpu` and optional `ratePerCpuHour`.
Rates must fit NUMERIC(10,4). Cost is `cpu * rate * elapsedSeconds / 3600`, rounded to six decimal
places. Stops preserve the first timestamp and cost. Concurrent starts/stops have one local
business effect. A cancelled start returns 409.

`X-Simulate-Failure: true` or `always-fail` forces a new start to return 503. Persisted replays
return the existing meter before simulation. Failure injection is intended for new operations.

## Test

Set `BILLING_TEST_DB_URL` to a dedicated PostgreSQL database; credentials default to billing_user
and changeme, overridden by `BILLING_TEST_DB_USER` and `BILLING_TEST_DB_PASSWORD`. Run `./mvnw verify`.
The suite includes a database-free stopped-cost check, HTTP validation and PostgreSQL lifecycle,
concurrent start/stop, replay and late-start cancellation tests. See the root README for setup.
