# Supporting-service evidence

The [current full-project verification](../integration/README.md) includes Notification,
Usage Aggregator and Expiry Worker with all real peers.

The isolated packaged-service test was rerun on 2026-10-25. It uses PostgreSQL for Notification
and WireMock for Quota, Resource and Billing. It checks event history, usage composition,
partial and total peer outages, scheduled termination and outbound correlation IDs.
See [output](e2e-output.txt) and [peer requests](peer-requests.json).

Current test counts and the five-service database outage check are in the integration results.

## Run

Follow the [database setup instructions](../../services/notification-service/README.md), then run:

```powershell
pwsh scripts/u4/verify.ps1 -Postgres
pwsh scripts/u4/e2e.ps1 -SkipBuild
```

Test connections use `U4_TEST_DB_URL`, `U4_TEST_DB_USER` and `U4_TEST_DB_PASSWORD`.
The process script accepts `DbHost`, `DbPort`, `DbName` and `DbUser`, plus service port overrides.
The recorded process run used PostgreSQL on 127.0.0.1:55435 and ports 18184, 18186, 18187 and 18190.

OpenAPI: [Notification](../../services/notification-service/openapi.yaml),
[Usage](../../services/usage-aggregator/openapi.yaml), [Worker](../../services/expiry-worker/openapi.yaml).
