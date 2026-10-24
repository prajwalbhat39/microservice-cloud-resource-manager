# Demo video run sheet (DOC-5)

Target length **8–10 minutes**. Every member presents a segment (assignment
requirement; individual marks depend on demo participation).

All commands are copy-paste ready and were verified working on 2026-10-24.
Nothing in this demo is pre-recorded or faked — every number on screen comes
from a live call.

---

## Before you hit record

### One-time setup

```powershell
# 1. Databases. setup-u1.sql may already have been run; the second one is new.
psql -U postgres -h localhost -f scripts\db\setup-u1.sql      # quota_db, resource_db
psql -U postgres -h localhost -f scripts\db\setup-u2-u4.sql   # billing_db, provider_db, notification_db

# 2. Build everything (one command, NFR-6)
.\services\quota\mvnw.cmd -DskipTests package
```

### Each time, ~2 minutes before recording

```powershell
# Start every service, each in its own titled window
.\scripts\demo\start-all.ps1

# Wait for "All services healthy."
```

Then **reset to a clean state** so the numbers on camera are predictable:

```powershell
.\evidence\reset.ps1
```

### Pre-flight checklist

- [ ] `start-all.ps1` printed **All services healthy** (6 services UP)
- [ ] Docker Desktop running (only needed if you plan to show `mvn verify`)
- [ ] Terminal font size bumped so JSON is readable on video
- [ ] One terminal window per speaker, pre-`cd`'d to the repo root
- [ ] `evidence\reset.ps1` run
- [ ] Browser tab open on `http://localhost:8081/swagger-ui.html` (for the API segment)

> **Why `start-all.ps1` forces `PROVIDER_FAILURE_RATE=0`:** the provider mock
> defaults to failing 20% of calls, which is correct behaviour (FR-41) but means
> roughly one happy-path demo in five fails on camera for no reason. Failures in
> this demo are injected **deliberately** per-request with `X-Simulate-Failure`,
> which is deterministic and explainable. Say this out loud in segment 5 — it is a
> point in your favour, not something to hide.

---

## Segment plan

| # | Segment | Speaker | Time | Running total |
|---|---------|---------|------|---------------|
| 1 | Problem, architecture, service boundaries | Prajwal | 1:15 | 1:15 |
| 2 | Main flow live, end to end | Prajwal | 1:45 | 3:00 |
| 3 | Challenge 1 — concurrency | Prajwal | 1:15 | 4:15 |
| 4 | Challenge 2 — idempotency | Prajwal | 1:15 | 5:30 |
| 5 | Challenge 3C — saga + compensation (the failure case) | Prajwal | 1:30 | 7:00 |
| 6 | Billing service: metering and cost | Karan | 0:45 | 7:45 |
| 7 | Provider mock: unreliability on purpose | Vinayak | 0:40 | 8:25 |
| 8 | Notification + usage view + expiry sweep | Siddharth | 0:50 | 9:15 |
| 9 | Evidence, tests, honest limitations | Prajwal | 0:40 | 9:55 |

Prajwal carries the three graded challenges because U1 owns them; segments 6–8
let each member present their own unit (DR-8: *"a named deliverable they can
explain unaided"*).

---

## Segment 1 — Problem and architecture (Prajwal, 1:15)

**Show:** the component diagram, then `services/` in the file tree.

**Say:**
- Tenants reserve compute against a fixed quota by tier. Seven services, each
  owning exactly one database.
- No service reads another's database (NFR-3). A service referencing something it
  does not own stores only the id (NFR-4). All cross-service traffic is REST (NFR-5).
- Point at the boundary that matters: **the orchestrator does not know what a
  tenant is.** It holds a `tenantId` string and asks Quota. That is why "does this
  tenant exist" and "does it have room" are answered by the *same* single call.

**Command (optional, shows the one-command build requirement):**
```powershell
Get-Content pom.xml | Select-String '<module>'
```

---

## Segment 2 — Main flow live (Prajwal, 1:45)

```powershell
.\scripts\demo\demo-main-flow.ps1
```

It pauses between segments — press enter when you have finished talking. Pass
`-NoPause` to run straight through if you would rather narrate over it.

**Say, as each part lands:**
- **Tenant** — created in Quota only. STANDARD = 20 CPU.
- **Provision** — one POST, `202 Accepted` with a `Location` header. Call out that
  quota is **already consumed before the saga finishes**: the 202 means "capacity is
  held and the work is queued", never "we'll check later". That synchronous quota
  reservation is what makes Challenge 1 deterministic (FR-13).
- **Polling** — the provider takes ~1s by design, which is *why* the API is async
  and the client polls. Status goes PENDING → COMPLETED.
- **Cross-service view** — the same transaction from three services: the resource
  from the orchestrator, the usage from Quota, the running meter from Billing.
  Three databases, linked only by ids.
- **Idempotent retry** — same key, same body, `Idempotency-Replayed: true`, byte-identical
  response, and **quota still 4, not 8**. One line: *"if your network dropped our
  response and you retried, you get your VM back — not a second one you'd be billed for."*
- **Failure case** (this is the "one failure case" the brief requires) — see segment 5
  for the talking points; the script shows it here too, so you can either cover it
  now or defer.
- **Termination** — idempotent: `alreadyTerminated` flips to true, nothing re-runs,
  quota returns to 0.

**If it fails on camera:** re-run with `-Core` (uses `peer-stub.py` instead of the
real Provider/Billing/Notification, needs no extra databases). Say: *"switching to
the stubbed peers for time"* and carry on — the orchestrator logic being demonstrated
is identical.

---

## Segment 3 — Challenge 1, concurrency (Prajwal, 1:15)

```powershell
.\evidence\challenge-1-quota-concurrency\run.ps1
```

**Say:**
- 20 requests for 4 CPU each, against a 20 CPU limit, fired **simultaneously** —
  all 20 workers block on a shared start time so they genuinely race.
- Expect **exactly 5 × HTTP 200 and 15 × HTTP 422**, and `cpu_used = 20` exactly.
- The mechanism, and this is the whole point: the decision is **one SQL statement**.

```sql
UPDATE tenant_quota
   SET cpu_used = cpu_used + :cpu, ...
 WHERE tenant_id = :tenantId
   AND cpu_used + :cpu <= cpu_limit
```

- *"The predicate is evaluated by PostgreSQL as part of the write, under the row lock
  the write itself takes. There is no window. If we had read the row, decided in Java,
  then written — which is the obvious way — two requests would both read 16-of-20,
  both conclude 4 more fits, and we would allocate 24 against a limit of 20."*
- Note the `exhausted: ["CPU"]` in the 422 body — the rejection names *which* limit
  ran out, so a client can act on it without parsing prose (AC-1.3).
- Then the failure cases it prints: a FREE tenant over its limit, and BR8 (a FREE
  tenant may not provision a CLUSTER at all).

---

## Segment 4 — Challenge 2, idempotency (Prajwal, 1:15)

```powershell
.\evidence\challenge-2-idempotency\run.ps1
```

**Say:**
- Sequential replay first: identical body, `Idempotency-Replayed: true`.
- Then the case a sequential test cannot reach: **10 simultaneous duplicates sharing
  one key**. All 10 get 202, all report the **same** resourceId, and the database ends
  with exactly **one** resource, **one** reservation, **one** idempotency record.
- The mechanism:

```sql
INSERT INTO idempotency_records (idempotency_key, ...) VALUES (...)
ON CONFLICT (idempotency_key) DO NOTHING
```

- *"The affected-row count is the verdict. 1 means we're first, process it. 0 means
  someone already owns this key, so read their answer and replay it. Nothing is read
  before writing, so two callers can't both conclude they were first."*
- **Worth saying if you have the time** — it earns credit for honesty: *"our first
  implementation caught the duplicate-key exception and read the row in the catch
  block. That cannot work: in PostgreSQL a constraint violation poisons the whole
  transaction, and Hibernate marks it rollback-only, so the commit fails even after a
  savepoint rollback. We only found that because the Testcontainers test ran it against
  real PostgreSQL — an H2 test passed happily."*
- Finally the failure case: same key, **changed** body → `422 IDEMPOTENCY_PAYLOAD_MISMATCH`,
  and the original record untouched. *"A key is a receipt for one specific request, not
  a blank cheque."*

---

## Segment 5 — Challenge 3C, saga and compensation (Prajwal, 1:30)

```powershell
.\evidence\challenge-3c-saga-compensation\run.ps1
```

**Say:**
- Four steps across services: reserve quota → create instance → start meter → activate.
  Steps 2–4 run **after** the 202, in a background saga.
- We force step 3 to fail with `X-Simulate-Failure: billing:always-fail`. Note the
  header is **persisted on the reservation** and replayed from the background task —
  the client's HTTP exchange ended at the 202, so the header itself is long gone (FR-46).
- Watch the quota number: **4 → 8 → 4**. Held, then released in full.
- Compensation runs in **reverse order, and only for steps that actually completed**:
  stop meter (skipped — it never started), terminate instance, release quota.
- Point at `providerInstanceId: null` on the FAILED resource: *"the instance that was
  created got terminated, and we clear the column so it honestly reads 'nothing is
  running'. The `failure_reason` prefix carries the 'how far did we get' signal instead
  — `BILLING_UNAVAILABLE` is only reachable with step 2 already complete."*
- Then show the corroboration from the other side of the wire:

```powershell
Get-Content .\evidence\challenge-3c-saga-compensation\peer-calls.txt | Select-Object -Last 12
```

  The `DELETE /provider/instances/i-mock-...` carries the **same id** the POST returned,
  and there is **no** meter-stop call — proving compensation undid exactly what had
  happened and nothing more.

---

## Segment 6 — Billing (Karan, 0:45)

```powershell
# a meter's life, against the real Billing service
curl.exe -s http://localhost:8083/api/v1/billing/meters/<resourceId>
curl.exe -s "http://localhost:8083/api/v1/billing/tenants/<tenantId>/cost?period=2026-10"
```

**Say:** meter start is idempotent on `resource_id`; cost is prorated per second at
`rate × cpu × seconds / 3600`; money is `NUMERIC`, never floating point (NFR-22);
BR5 — the meter stops on termination and accrues nothing after, with the test named
`br5_billingStopsWhenResourceIsTerminated_idempotentStop`.

---

## Segment 7 — Provider mock (Vinayak, 0:40)

```powershell
# idempotent on client_token: same token twice -> the SAME instance
curl.exe -s -X POST http://localhost:8085/api/v1/provider/instances `
  -H "Content-Type: application/json" `
  -d '{\"clientToken\":\"demo-token-1\",\"type\":\"VM\",\"cpu\":2,\"ramGb\":4}'
```

**Say:** simulates an unreliable provider — 1–3s delay and a configurable 20% failure
rate (turned off for this recording so the demo is deterministic; failures are injected
on purpose instead). The `client_token` UNIQUE constraint is what makes the
orchestrator's retries safe: a retry after an ambiguous timeout returns the *existing*
instance rather than creating a second one (FR-19, NFR-27).

---

## Segment 8 — Notification, usage view, expiry (Siddharth, 0:50)

```powershell
curl.exe -s "http://localhost:8084/api/v1/notifications?tenantId=<tenantId>"
curl.exe -s "http://localhost:8086/api/v1/usage/<tenantId>"
```

**Say:** notifications are best-effort and sit **outside** the compensation path — a
failing notifier must never roll back a provisioned, paid-for resource (FR-33). The
usage view composes three services and degrades gracefully: if one is down you get the
sections it could obtain plus an availability flag, never a hard failure (FR-37). The
expiry worker sweeps `GET /resources/expired` and terminates each one, which is safe
because termination is idempotent.

**To show graceful degradation live** (nice moment if time allows):
```powershell
# kill Billing's window, then:
curl.exe -s "http://localhost:8086/api/v1/usage/<tenantId>"   # quota + resources still present, cost marked unavailable
```

---

## Segment 9 — Evidence and limitations (Prajwal, 0:40)

**Show:** the `evidence/` tree, then `evidence/README.md`'s BR traceability table.

**Say:**
- Every challenge has its script, its captured output with timestamps, the database
  state before and after, and at least one failure case.
- 112 automated tests across seven services; the rules enforced by SQL (BR1, BR3, BR7)
  have Testcontainers tests against real PostgreSQL, because a mocked repository cannot
  validate a database constraint.
- **Be honest about the limits** — this reads as competence, not weakness:
  - BR5 belongs to Billing and is tested there, not in the orchestrator's evidence.
  - If the orchestrator process dies mid-saga, the resource stays `PROVISIONING` with
    quota held. There is no recovery scanner; that was a deliberate choice to avoid
    reintroducing saga-coordination tables. It is visible with one SQL query and the fix
    is manual termination.
  - No circuit breaker (decision F4) — with a single provider and a synthetic failure
    rate, a breaker would trip on simulated faults and suppress the very failure path
    Challenge 3C needs to show.

---

## Known issues to be aware of while recording

As of 2026-10-24 the full reactor build has **3 failing tests, all in U4**
(`usage-aggregator` 1, `expiry-worker` 2) — see `aidlc-docs/integration-findings.md`.
Both are genuine bugs on the *failure* paths; the happy paths used in segment 8 work.

**Do not run `mvn verify` on camera until those are fixed** — it ends in `BUILD FAILURE`.
If you want to show the test suite passing, run the two U1 modules only:

```powershell
.\services\quota\mvnw.cmd -pl services/quota,services/resource verify
```

---

## After recording

```powershell
.\scripts\demo\stop-all.ps1
```
