# Cloud Resource Manager

Seven Java 21 services for tenant quotas, resource provisioning, billing, notifications,
usage reporting and scheduled expiry. Services communicate over HTTP. Each data-owning
service has its own PostgreSQL database and role.

| Service | Port | Database |
|---|---:|---|
| [Quota](services/quota/README.md) | 8081 | quota_db |
| [Resource / Orchestrator](services/resource/README.md) | 8082 | resource_db |
| [Billing](services/billing-service/README.md) | 8083 | billing_db |
| [Notification](services/notification-service/README.md) | 8084 | notification_db |
| [Provider](services/provider/README.md) | 8085 | provider_db |
| [Usage Aggregator](services/usage-aggregator/README.md) | 8086 | None |
| [Expiry Worker](services/expiry-worker/README.md) | 8087 | None |

## Run

Install Java 21, PostgreSQL 16+, PowerShell 7.4+ and `psql`. Create the databases once as an
administrator. The setup scripts use `changeme` for local development.

```powershell
psql -U postgres -h localhost -v ON_ERROR_STOP=1 -f scripts/db/setup-u1.sql -f scripts/db/setup-u2-u4.sql
./services/quota/mvnw -B -ntp -f pom.xml -DskipTests package
pwsh scripts/demo/start-all.ps1 -WithExpiry
pwsh scripts/demo/demo-main-flow.ps1 -NoPause
pwsh scripts/demo/stop-all.ps1
```

On Windows use `mvnw.cmd` in place of `mvnw`. Set `JAVA_HOME` to the Java 21 installation.
Service configuration uses environment variables; see each service's README. The launcher
uses deterministic Provider settings, writes logs under `logs/`, and records the processes it
starts. The stop script stops only those registered processes.

Health is available at `/health`; API specifications are available at `/v3/api-docs.yaml`.
Provider failures can be injected through `X-Simulate-Failure`. Resource accepts targeted
values such as `billing:always-fail` for compensation testing.

## Test

Create dedicated test databases once, then configure the database-backed suites:

```powershell
psql -U postgres -h localhost -v ON_ERROR_STOP=1 -f scripts/db/setup-tests.sql
$env:QUOTA_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/quota_test'
$env:RESOURCE_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/resource_test'
$env:BILLING_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/billing_test'
$env:U4_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/notification_u4_test'
./services/quota/mvnw -B -ntp -f pom.xml -Ppostgres verify
pwsh scripts/integration.ps1 -SkipBuild
```

Quota and Resource can also use Testcontainers when Docker is available. Provider tests start
an isolated PostgreSQL instance. Test databases must contain test data only. The process test
starts all seven jars on ports 18081–18087, creates a unique tenant, checks failure and recovery
paths, and stops its child processes. Use `-DbHost` and `-DbPort` for another PostgreSQL server.

## Design and evidence

Provisioning reserves quota synchronously, then performs Provider and Billing operations in
the background. Resource persists pending work and retries interrupted provisioning and
cleanup after restart. Compensation cancels uncertain effects before releasing quota.
Notification delivery is best effort and at least once. Usage reports unavailable sections
explicitly. The expiry worker requests termination through Resource.

- [Requirements](aidlc-docs/inception/requirements/requirements.md)
- [Service design](aidlc-docs/inception/design/application-design.md)
- [Team responsibilities](aidlc-docs/inception/units.md)
- [Integration test results](evidence/integration/README.md)
- [Challenge evidence](evidence/README.md)
- [Assignment report](PROJECT_REPORT.md)

Authentication and cloud deployment are outside Assignment 1.
