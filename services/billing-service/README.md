# Billing Service (Unit U2)

Spring Boot service for billing meters and cost computation.

## Port and Database
- Port: `8083`
- Database: `billing_db`

## Configuration
- `DEFAULT_CPU_RATE` (default `0.13`): applied when `ratePerCpuHour` is not sent in start request.
- `BILLING_FAILURE_RATE` (default `0`): random failure rate for `POST /api/v1/billing/meters`.
  - `0` = never fail
  - `1` = always fail
- `X-Simulate-Failure: true` header on start-meter always returns `503`.

## APIs
Base path: `/api/v1/billing`

### Start meter (idempotent on `resourceId`)
`POST /meters`

```json
{
  "resourceId": "res-123",
  "tenantId": "tenant-a",
  "cpu": 2,
  "ratePerCpuHour": 0.13
}
```

Returns:
- `201` when meter created
- `200` when same `resourceId` is replayed

### Stop meter (idempotent)
`POST /meters/{resourceId}/stop`

Returns `200` and keeps original stop timestamp on repeated calls.

### Read meter and current cost
`GET /meters/{resourceId}`

### Tenant month cost
`GET /tenants/{tenantId}/cost?period=YYYY-MM`

- `period` is required.
- Invalid format returns `400`.

## Cost Formula
Cost is computed as:

`cpu * ratePerCpuHour * activeSeconds / 3600`

- Prorated per second.
- Uses `BigDecimal` only (no float/double storage for money).

## BR5
Billing stops when a resource is terminated by calling:

`POST /api/v1/billing/meters/{resourceId}/stop`

## Run
```bash
cd billing-service
./mvnw spring-boot:run
```

If wrapper is not present:

```bash
mvn spring-boot:run
```

## Test
```bash
cd billing-service
mvn test
```

## Health and OpenAPI
- Health: `GET /health`
- OpenAPI endpoint: `GET /v3/api-docs.yaml`
- Committed spec file: `openapi.yaml`

