# Notification

Records ACTIVE, FAILED and TERMINATED lifecycle events in `notification_db` and simulates delivery.

## Run

Use the project's Java toolchain (`pom.xml`), a running PostgreSQL server, and the committed
Maven Wrapper. From the repository root, create the development database once:

```sh
psql -U YOUR_POSTGRES_ADMIN -d postgres -f scripts/u4/setup-notification.sql
cd services/notification-service
./mvnw spring-boot:run
```

On Windows use `mvnw.cmd`. Set `DB_PASSWORD` if the local development default was changed.
Flyway creates the schema at startup; Hibernate validates it. No other service is needed.

## API

| Method | Path | Result |
|---|---|---|
| POST | `/api/v1/notifications` | 202 after the event is persisted; delivery outcome in the response |
| GET | `/api/v1/notifications?tenantId=...` | 200, array of that tenant's notifications |
| GET | `/health` | 200 UP / 503 DOWN, including database connectivity |
| GET | `/v3/api-docs.yaml` | Generated OpenAPI; committed snapshot in `openapi.yaml` |

```sh
curl -i http://localhost:8084/api/v1/notifications \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: sid-demo' \
  -d '{"tenantId":"tnt-std-001","resourceId":"resource-001","eventType":"ACTIVE","message":"Resource is ready"}'
curl 'http://localhost:8084/api/v1/notifications?tenantId=tnt-std-001'
```

`tenantId` and `resourceId` are required opaque identifiers of at most 64 characters. `eventType`
is ACTIVE, FAILED or TERMINATED. `message` is nonblank, at most 2000 characters. Invalid requests
return 400 Problem Details; unavailable persistence returns 503 NOTIFICATION_UNAVAILABLE.

Delivery starts immediately. Success records SENT, attempts and sentAt. Failure remains PENDING
until the attempt budget is exhausted, then becomes FAILED. A scheduled retry finds up to 100
pending records and locks each row before attempting it. Repeated or concurrent retry of a SENT
row does nothing. Pending work survives restart. All outcomes remain readable in history.

Delivery is **at least once**. The existing POST contract has no stable event ID; a repeated
POST is a new notification. Retry after a delivery succeeded but the database commit failed can
also repeat the simulated send. The orchestrator retries HTTP requests; this service retries
persisted delivery attempts.

## Configuration and tests

`.env.example` lists the settings and defaults. It is a reference, not automatically loaded:
export values in the shell (`$env:NAME='value'` in PowerShell). Database pool size defaults to 5,
connection acquisition is bounded to 2000 ms, delivery has 3 total attempts with 30000 ms retry
interval, and simulated failure rate is 0.0. Set `NOTIFICATION_FAILURE_RATE=1.0` to demonstrate
exhaustion; values between 0 and 1 simulate intermittent failures. Change environment and restart
to change simulation. Logs contain event identifiers, not full notification messages or credentials.

```sh
./mvnw verify
```

For PostgreSQL integration tests, create a **dedicated test database** once from the repository root:

```sh
psql -U YOUR_POSTGRES_ADMIN -d postgres -f scripts/u4/setup-test-db.sql
cd services/notification-service
./mvnw -Ppostgres verify
```

The integration profile defaults to `notification_u4_test` / `notification_test_user` / `changeme`.
Override `U4_TEST_DB_URL`, `U4_TEST_DB_USER`, `U4_TEST_DB_PASSWORD` for another dedicated test
server. **Tests delete notification rows in that database. Never point them at an application database.**
The ordinary unit-test command needs neither PostgreSQL nor Docker.

[Design and integration notes](../../aidlc-docs/construction/u4-supporting/design.md)
