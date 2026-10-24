# Cloud Resource Manager

A tenant-aware cloud resource provisioning system built as **seven independent Spring Boot
microservices**. Tenants reserve compute (VMs, containers, clusters) against a fixed per-tier
quota; an orchestrator drives provisioning across a provider, a billing meter and notifications
using a **saga with compensation**, so a failure half-way through never leaves quota held or an
instance orphaned.

Each service owns exactly one database, no service reads another's tables, and all cross-service
traffic is REST. The interesting engineering lives in three challenges: atomic quota reservation
under concurrency, idempotent provisioning, and saga compensation on failure.

> **Status:** Assignment 1 (build the services, run them together locally) is complete — all
> seven services run against local PostgreSQL and have been exercised end to end. Deployment
> (containers + cloud) is Assignment 2 and is not done yet; everything here runs on `localhost`.

---

## Quick start (Windows / PowerShell 7)

```powershell
# 1. Create the databases and least-privilege users (run ONCE, as the postgres superuser)
psql -U postgres -h localhost -f scripts\db\setup-u1.sql      # quota_db, resource_db
psql -U postgres -h localhost -f scripts\db\setup-u2-u4.sql   # billing_db, provider_db, notification_db

# 2. Build all seven services (there is no wrapper at the repo root - borrow quota's)
.\services\quota\mvnw.cmd -f pom.xml -DskipTests package

# 3. Start every service, each in its own titled window, with demo-safe settings
.\scripts\demo\start-all.ps1
#    wait for "All services healthy."

# 4. See it work end to end
.\scripts\demo\demo-main-flow.ps1

# 5. Stop everything when done
.\scripts\demo\stop-all.ps1
```

Prerequisites: **JDK 21**, **PostgreSQL 15+** on `localhost:5432`, **PowerShell 7+**
(the concurrency tests use `ForEach-Object -Parallel`). **Python 3** is only needed for the
`-Core` fallback (the peer stub), and **Docker** only for the Testcontainers integration tests.

Flyway creates every table on first boot, so once the databases exist you never run DDL by hand.

---

## Architecture at a glance

```
          Client
            |  POST /api/v1/resources (Idempotency-Key)
            v
   +------------------+      reserve quota (sync)     +--------------+
   |  Resource        |  ---------------------------> |  Quota       |  :8081  quota_db
   |  (orchestrator)  |  <--- 202 Accepted            +--------------+
   |  :8082           |
   |  resource_db     |     background saga (async):
   +------------------+        1. provision instance  -> Provider      :8085  provider_db
            |                   2. start billing meter -> Billing       :8083  billing_db
            |                   3. activate + notify   -> Notification  :8084  notification_db
            |
            |  on failure: compensate in REVERSE order
            |  (stop meter -> terminate instance -> release quota)
```

Two more services sit outside the provisioning path: **usage-aggregator** (`:8086`) composes a
read-only usage view across services and degrades gracefully if one is down, and **expiry-worker**
(`:8087`) sweeps expired resources and terminates them. Both are stateless (no database).

---

## The seven services

| Service | Port | Database | Unit / owner | Role |
|---|---|---|---|---|
| `services/quota` | 8081 | `quota_db` | U1 (Prajwal) | tenants, tier limits, atomic quota reservation ledger |
| `services/resource` | 8082 | `resource_db` | U1 (Prajwal) | the **orchestrator** — provisioning, idempotency, the saga |
| `services/billing-service` | 8083 | `billing_db` | U2 (Karan) | per-resource metering and cost |
| `services/notification-service` | 8084 | `notification_db` | U4 (Siddharth) | best-effort notifications |
| `services/provider` | 8085 | `provider_db` | U3 (Vinayak) | mock cloud provider (deliberately unreliable) |
| `services/usage-aggregator` | 8086 | — (stateless) | U4 (Siddharth) | cross-service usage view, graceful degradation |
| `services/expiry-worker` | 8087 | — (stateless) | U4 (Siddharth) | terminates expired resources |

Every service exposes `GET /health`. Each is a self-contained Maven project with its own
`mvnw` wrapper and inherits `spring-boot-starter-parent` directly; the root `pom.xml` is a
non-parent aggregator that lists all seven so one command builds everything.

---

## Repository map

```
microservice-cloud-resource-manager/
├─ README.md                     <- you are here
├─ pom.xml                       <- aggregator POM: lists all 7 modules (one-command build)
│
├─ services/                     <- the 7 microservices (see table above)
│   └─ <svc>/
│       ├─ src/main/java/...     <- application code
│       ├─ src/main/resources/   <- application.yml + Flyway migrations (db/migration)
│       ├─ src/test/java/...     <- unit (*Test) and integration (*IT) tests
│       ├─ mvnw.cmd              <- per-service Maven wrapper
│       └─ target/*.jar          <- fat jar after `package` (git-ignored)
│
├─ scripts/
│   ├─ db/
│   │   ├─ setup-u1.sql          <- creates quota_db + resource_db and their users
│   │   ├─ setup-u2-u4.sql       <- creates billing_db, provider_db, notification_db
│   │   └─ setup-u3.sql
│   ├─ demo/
│   │   ├─ start-all.ps1         <- start every service in its own window (demo-safe env)
│   │   ├─ stop-all.ps1          <- stop only this repo's service processes
│   │   ├─ demo-main-flow.ps1    <- paced end-to-end walkthrough (happy path + failure case)
│   │   ├─ show-footprint.ps1    <- one transaction's rows across all 5 databases
│   │   └─ RUN-SHEET.md          <- the demo run sheet (segments, timings, talking points)
│   └─ peer-stub.py              <- dependency-free stand-in for provider/billing/notification
│
├─ evidence/                     <- captured proof for the 3 challenges + test suites (see below)
│
├─ aidlc-docs/                   <- requirements, design, and engineering notes
│   ├─ inception/
│   │   ├─ requirements/         <- requirements.md and verification Q&A
│   │   ├─ design/               <- application-design.md (incl. saga sequence diagrams)
│   │   └─ units.md              <- unit boundaries and owners
│   ├─ construction/             <- per-unit functional / infrastructure / NFR designs
│   └─ integration-findings.md   <- bugs found during full-system integration (and fixes)
│
└─ logs/                         <- per-service run logs written by start-all.ps1 (git-ignored)
```

---

## Running it

### Start / stop

```powershell
.\scripts\demo\start-all.ps1              # all stateful services + usage-aggregator
.\scripts\demo\start-all.ps1 -WithExpiry  # also start the expiry worker
.\scripts\demo\start-all.ps1 -Core        # U1 only: quota + resource + peer stub (no U2/U3/U4 DBs)
.\scripts\demo\stop-all.ps1               # stop only processes started from this repo
```

`start-all.ps1` resolves `java.exe` by absolute path (so it works even when the JDK is not on
`PATH`), forces demo-safe settings (`PROVIDER_FAILURE_RATE=0`, expiry off), opens one titled
window per service, and tees each service's output to `logs\<service>.log`.

### The main flow

```powershell
.\scripts\demo\demo-main-flow.ps1          # pauses between segments so you can talk
.\scripts\demo\demo-main-flow.ps1 -NoPause # run straight through
```

Creates a tenant, provisions a resource (202 → saga → ACTIVE), shows the same transaction across
all five databases, replays the request to prove idempotency, forces a billing failure to show
compensation, then terminates — all against the real services.

### Cross-service footprint

```powershell
.\scripts\demo\show-footprint.ps1 -TenantId <tenantId> -ProveIsolation
```

Reads one tenant's rows from all five databases, each with that service's **own** credentials.
`-ProveIsolation` additionally shows PostgreSQL refusing one service's user access to another's
database (NFR-3 enforced by the database, not by convention).

---

## API specs and health endpoints

### OpenAPI specs

Every service has a committed OpenAPI spec at the root of its folder:

```
services/quota/openapi.yaml                 services/provider/openapi.yaml
services/resource/openapi.yaml              services/usage-aggregator/openapi.yaml
services/billing-service/openapi.yaml       services/expiry-worker/openapi.yaml
services/notification-service/openapi.yaml
```

These are static snapshots exported from each service via springdoc. When a service is running
you can also get the spec live, or browse it interactively:

```powershell
curl.exe -s http://localhost:8081/v3/api-docs.yaml   # raw spec (YAML; use /v3/api-docs for JSON)
# Swagger UI in a browser:  http://localhost:8081/swagger-ui.html
```

To refresh a committed snapshot from a running service:

```powershell
curl.exe -s http://localhost:8081/v3/api-docs.yaml -o services/quota/openapi.yaml
```

### Health endpoints

Every service answers `GET http://localhost:<port>/health` with HTTP 200 when healthy:

| Port | Service | How `/health` is served |
|---|---|---|
| 8081 | quota | Spring Boot Actuator, remapped to `/health` (reports DB connectivity, 503 if the DB is down) |
| 8082 | resource | Actuator at `/health` (reports DB connectivity) |
| 8083 | billing | custom `HealthController` — returns a static `{"status":"UP"}` (does **not** check the DB) |
| 8084 | notification | Actuator at `/health` (reports DB connectivity) |
| 8085 | provider | Actuator at `/health` (reports DB connectivity) |
| 8086 | usage-aggregator | Actuator at `/health` |
| 8087 | expiry-worker | Actuator at `/health` |

The Actuator services expose health at the root (`management.endpoints.web.base-path: /`), not at
the default `/actuator/health`. Billing is the exception: its `/health` comes from a hand-written
`HealthController`, so judging it from `application.yml` alone is misleading.

---

## Evidence and the three challenges

All proof lives in **`evidence/`** — start at [`evidence/README.md`](evidence/README.md), which
maps every business rule (BR1–BR8) to the file that proves it.

| Challenge | What it proves | Run it |
|---|---|---|
| **1 — concurrency** | Quota never exceeds the tier limit under a 20-way simultaneous race (one atomic conditional `UPDATE`) | `evidence\challenge-1-quota-concurrency\run.ps1` |
| **2 — idempotency** | Same key + same body replays the original answer; changed body → 422; 10 concurrent duplicates → one resource | `evidence\challenge-2-idempotency\run.ps1` |
| **3C — saga compensation** | A forced billing failure rolls back in reverse order; quota released in full, no orphaned instance | `evidence\challenge-3c-saga-compensation\run.ps1` |

Each script creates uniquely-named test data per run, prints a `VERDICT: PASS/FAIL`, and exits 0
on pass / 1 on fail (CI-checkable). Each challenge folder holds the `run.ps1`, a captured
`output.txt`, and `db-before.txt` / `db-after.txt`. Challenge 3C additionally has `saga-logs.txt`,
`peer-calls.txt`, and a `NOTE.md` explaining the setup. `evidence\reset.ps1` clears only the
scripts' own test data.

---

## Building and testing

```powershell
# Build every service (fat jars under services/*/target)
.\services\quota\mvnw.cmd -f pom.xml -DskipTests package

# Build + run the full test suite for all modules (-fae = report every module, not just the first failure)
.\services\quota\mvnw.cmd -f pom.xml -fae verify

# Just the two U1 modules
.\services\quota\mvnw.cmd -pl services/quota,services/resource verify
```

Unit tests (`*Test`) run under surefire; Testcontainers-backed database tests (`*IT`) run under
failsafe and **require Docker**. Without Docker they skip (the build still reports success), so
check the per-class "Skipped" counts before claiming the DB-backed rules were verified. Captured
test output is in `evidence/unit-and-integration-tests/`.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `java: The term 'java' is not recognized` in a service window | The JDK isn't on that shell's `PATH`. `start-all.ps1` already resolves `java.exe` by absolute path; if you launch a jar by hand, use a full path to `java.exe` or set `JAVA_HOME`. |
| A service window shows `SKIP ... jar not found` | That service isn't built yet. Run the `package` command above. |
| `Unable to rename ...jar to ...jar.original` during a build | A service is still running and locking its own jar. Stop it (`stop-all.ps1`) before rebuilding. |
| A service won't start with a database error | The database doesn't exist. Run both `scripts\db\setup-*.sql` as the postgres superuser. |
| Roughly 1 in 5 happy-path provisions fails for no reason | The provider mock defaults to a 20% failure rate (FR-41). `start-all.ps1` sets `PROVIDER_FAILURE_RATE=0`; inject failures deliberately with the `X-Simulate-Failure` header instead. |
| Demo resources terminate mid-flow | The expiry worker is running. Don't pass `-WithExpiry` unless you're demoing expiry. |
| `mvn verify` ends in `BUILD FAILURE` | Known failing tests on U4 failure paths — see `aidlc-docs/integration-findings.md`. Use the U1-only `-pl` command to show a green suite. |

---

## Documentation

- **Requirements:** `aidlc-docs/inception/requirements/requirements.md`
- **Design (incl. saga sequence diagrams):** `aidlc-docs/inception/design/application-design.md`
- **Unit boundaries and owners:** `aidlc-docs/inception/units.md`
- **Integration findings and fixes:** `aidlc-docs/integration-findings.md`
- **Demo run sheet:** `scripts/demo/RUN-SHEET.md`
- **Evidence guide:** `evidence/README.md`
