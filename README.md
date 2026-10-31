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

### Docker Compose (Assignment 2)

Install Docker with Linux-container support (Docker Desktop on Windows) and Docker
Compose v2 with `up --wait` support. Allow roughly 6 GB of memory for seven JVMs plus
PostgreSQL. Host ports 8081-8087 and 5433 must be free. No host Java, Maven or PostgreSQL
installation is needed; each image builds its own service from source.

From the repository root:

```powershell
Copy-Item .env.example .env
# Optional: edit the local .env BEFORE first startup; never commit it.
docker compose config --quiet
docker compose up --build
```

All eight containers share the `crm` bridge network. Container-to-container calls use
Compose service names, for example `http://provider:8085`; `localhost` in a container
means that container, not a peer. PostgreSQL is `postgres:5432` inside the network and
`localhost:5433` on the host.

The `pgdata` named volume stores all five databases. On its first boot,
[init-compose.sh](scripts/db/init-compose.sh) runs the two existing database setup scripts
in order, passing `DB_PASSWORD` to create the five isolated service roles. Flyway creates
each service's tables. `.env` values override the local placeholder defaults; changing
passwords in `.env` does **not** rotate credentials in an existing volume. Rotate the
corresponding PostgreSQL roles as well, or use a separate disposable Compose project for
testing new initialization settings.

The YAML anchors share healthchecks, restart policy and `JAVA_TOOL_OPTIONS=-Xmx256m`.
Every runtime image includes `curl`. Application healthchecks call the local `/health`
endpoint, and the PostgreSQL TCP check cannot pass on its temporary initialization
server. `service_healthy` gates Resource on its four peers and the stateless services
on their peers. These dependencies gate startup, not continuous dependency recovery;
Docker marks unhealthy containers but does not restart them merely for being unhealthy.

#### Verify the workflow and restart persistence

The following PowerShell 5.1+ script builds and starts the stack, checks all eight
containers and five database logins, provisions a unique tenant/resource, and asserts
COMPLETED -> ACTIVE, quota allocation, an ACTIVE provider instance, an ACTIVE billing
meter, a recorded notification and fully available usage data.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo\verify-compose.ps1
```

By default it then runs `docker compose down` followed by `up`, verifies the **same**
records/identities survived in all five databases, and provisions a fresh resource.
It leaves the stack running for the other team members' demos. Use `-SkipBuild` to
reuse images, `-SkipRestart` when interrupting the stack is undesirable, or
`-WaitTimeoutSeconds 600` on a slower host. Resources remain ACTIVE for inspection;
the expiry worker is running but automatic expiry is disabled for the demo.

Timestamped evidence is saved under `evidence/compose/`: container states, before/after
workflow JSON, fresh-workflow JSON, a transcript and container logs. A failed check
throws and saves failure logs rather than reporting success. Review logs before sharing.
Running this script against a new checkout/empty volume is still required to establish
clean-checkout deployment evidence.

```powershell
docker compose ps --all
docker compose logs --tail 100 resource provider billing
docker compose exec resource curl --fail http://provider:8085/health
docker compose down
docker compose up -d --wait --wait-timeout 300
```

Do **not** add `-v` / `--volumes` to `down`: that deletes the persistent data.
For regression checks of the verification script without Docker, use Pester:

```powershell
Invoke-Pester .\scripts\demo\verify-compose.Tests.ps1
```

These tests mock Docker/HTTP; they are not substitutes for the live deployment demo.

### Local Java processes (Assignment 1)

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
