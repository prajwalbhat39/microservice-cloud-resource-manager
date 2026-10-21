# Test results

22 tests passed with no failures or skips. Run date: 2026-10-18 UTC.

| Check | Result | Output |
|---|---|---|
| Notification service | 4 unit tests passed | [tests.txt](tests.txt) |
| Notification HTTP and PostgreSQL | 6 integration tests passed | [tests.txt](tests.txt) |
| Usage composition and failures | 6 tests passed | [tests.txt](tests.txt) |
| Expiry requests and failures | 6 tests passed | [tests.txt](tests.txt) |
| Packaged services and scheduler | Passed | [e2e-output.txt](e2e-output.txt) |
| Database outage and recovery | Passed: 503/DOWN, recovery, no inserted event, separate database access denied | [db-outage.txt](db-outage.txt) |

The process test uses PostgreSQL for Notification and WireMock for Quota, Resource and Billing.
Lifecycle events are posted directly to Notification. Full-system provisioning and cleanup still
need testing when U1–U3 implementations are available.

## Run

Follow the [database setup instructions](../../services/notification/README.md), then run:

```powershell
pwsh scripts/u4/verify.ps1 -Postgres
pwsh scripts/u4/e2e.ps1 -SkipBuild
```

The recorded run used PostgreSQL on 127.0.0.1:55434 with separate notification_u4_test and
notification_u4_e2e databases. Test connection settings use U4_TEST_DB_URL, U4_TEST_DB_USER and
U4_TEST_DB_PASSWORD; process-test settings use DbHost, DbPort, DbName and DbUser.
The outage check stopped and restarted that temporary cluster, with Notification on port 18184.

## Logs and API specifications

- [Notification](notification.log), [Usage](usage.log), [Worker](worker.log)
- [Peer HTTP requests](peer-requests.json)
- [Database outage service log](db-outage-service.log)
- OpenAPI: [Notification](../../services/notification/openapi.yaml),
  [Usage](../../services/usage-aggregator/openapi.yaml), [Worker](../../services/expiry-worker/openapi.yaml)
