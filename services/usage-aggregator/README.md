# Usage Aggregator

Composes a tenant's quota, ACTIVE resources and current UTC month's cost through REST.
No database, cached reporting data, quota decisions or cost calculation.

## Run

Use the project's Java toolchain (`pom.xml`) and committed Maven Wrapper:

```sh
cd services/usage-aggregator
./mvnw spring-boot:run
curl http://localhost:8086/api/v1/usage/tnt-std-001
```

On Windows use `mvnw.cmd`. Upstream services need not be present for startup or `/health`.
Without them the usage endpoint returns 200 with all availability flags false.

The upstream calls are:

- Quota: `GET /api/v1/tenants/{tenantId}/quota`
- Resource: `GET /api/v1/resources?tenantId={tenantId}&status=ACTIVE`
- Billing: `GET /api/v1/billing/tenants/{tenantId}/cost?period=YYYY-MM`

Resource's `items` array is extracted from its envelope. Quota and cost JSON objects are
preserved, including decimal strings in the cost response.
The requested period is computed with a UTC clock. The tenant ID is nonblank, at most 64 characters.

Example with Billing unavailable:

```json
{
  "quota": {"cpu":{"limit":20,"used":4,"remaining":16}},
  "resources": [{"resourceId":"resource-001","status":"ACTIVE"}],
  "availability": {"quota":true,"resources":true,"billing":false}
}
```

Unavailable sections are **omitted**. A valid empty resource array and an available zero cost
remain present with availability true. All upstream HTTP errors (including 404), empty or invalid
responses, malformed JSON and timeouts degrade the affected section. This API does not make a
separate tenant-existence call; it cannot issue an authoritative tenant-not-found response.
The availability key for `cost` is `billing`.

Calls are sequential, with connect and read timeouts and no retries. Incoming correlation IDs
are forwarded to all three peers.

## Configuration and tests

`.env.example` lists environment settings and defaults. Export them in the shell; the example
file is not automatically loaded. Peer URLs default to ports 8081, 8082 and 8083. Each connect
limit is 1000 ms and read limit 2000 ms. Override `<PEER>_CONNECT_TIMEOUT_MS` and
`<PEER>_READ_TIMEOUT_MS` as needed. `SERVER_PORT` defaults to 8086; `LOG_LEVEL` defaults to INFO.

```sh
./mvnw verify
```

Tests start the Spring HTTP server and WireMock peers on random ports. They cover full
composition, available zero cost, partial/total outage, malformed response, timeout, correlation
propagation, UTC month selection and validation. No database or Docker is needed.

`GET /health` reports this service's health; peer outages belong in availability flags.
`GET /v3/api-docs.yaml` is exported to `openapi.yaml` by `scripts/u4/e2e.ps1`.

[Design and integration notes](../../aidlc-docs/construction/u4-supporting/design.md)
