# Evidence

Captured evidence for the three engineering challenges and the automated test suites
of Unit U1 (the Quota service and the Resource/orchestrator service). Satisfies the
assignment's evidence requirements ER-1 to ER-6 and testing requirements TR-1 to TR-10.

Everything here was produced by actually running the services and the scripts; the
`output.txt`, `db-*.txt`, `*-logs.txt` and `*-tests.txt` files are captured console
and database output, reproduced verbatim.

> **Scope.** U1 is the Quota and Resource/orchestrator services — two of the system's seven.
> These two carry all three graded challenges and business rules BR1-BR7. As of the first full
> seven-service integration run (2026-10-24), the Provider, Billing and Notification services
> (owned by other team members) are **built and running**, and the evidence below was
> recaptured against those **real services** — not the peer stub. `../scripts/peer-stub.py`
> remains only as the `-Core` fallback for running U1 in isolation. See
> `challenge-3c-saga-compensation/NOTE.md` for the per-service breakdown and the one contract
> bug the integration run uncovered and fixed (Finding 5).

---

## Folder layout

```
evidence/
  README.md                        <- this file
  reset.ps1                        <- restores a clean starting state (TR-8)
  challenge-1-quota-concurrency/   <- BR1, Challenge 1
  challenge-2-idempotency/         <- BR2 + BR3, Challenge 2
  challenge-3c-saga-compensation/  <- BR6, Challenge 3C
  unit-and-integration-tests/      <- the full automated suite for both services
```

Each challenge folder contains:

| File | Contents |
|------|----------|
| `run.ps1` | the repeatable script for that challenge |
| `output.txt` | its captured console output, with a UTC-timestamped header (ER-2) |
| `db-before.txt` / `db-after.txt` | the relevant database rows before and after the run (ER-1) |

Challenge 3C additionally has `saga-logs.txt` (the orchestrator's own structured logs for
the compensated transaction), `peer-calls.txt` (the downstream call journal), and `NOTE.md`.

---

## Prerequisites

1. **PostgreSQL** reachable on `localhost:5432`, with all five databases and least-privilege
   users created by the two setup scripts (run once as the postgres superuser):
   ```powershell
   psql -U postgres -h localhost -f ../scripts/db/setup-u1.sql      # quota_db, resource_db
   psql -U postgres -h localhost -f ../scripts/db/setup-u2-u4.sql   # billing_db, provider_db, notification_db
   ```
   All users are `<svc>_user` / password `changeme`; the evidence scripts set `PGPASSWORD`
   themselves.
2. **All services running.** The simplest way is the demo launcher, which starts every service
   in its own window with demo-safe settings and waits for health:
   ```powershell
   ../scripts/demo/start-all.ps1
   ```
   Each service runs Flyway on startup, so tables are created automatically on first boot.
   Challenge 1 only needs Quota (`:8081`); Challenges 2 and 3C drive the full saga and so need
   the Resource orchestrator plus the real Provider (`:8085`), Billing (`:8083`) and
   Notification (`:8084`).
3. **U1-only fallback (optional).** To run the two U1 services in isolation without the U2/U3/U4
   databases, use the peer stub instead of the real services:
   ```powershell
   ../scripts/demo/start-all.ps1 -Core     # quota + resource + scripts/peer-stub.py on :8090
   ```
   The challenge scripts behave identically; only the peer identity differs.
4. **PowerShell 7+** (`ForEach-Object -Parallel` is used for the genuinely-concurrent
   cases in Challenges 1 and 2) and **Docker** (only for the Testcontainers integration
   tests under `unit-and-integration-tests/`, not for the challenge scripts).

---

## Running each challenge

| Challenge | Command | Expected headline result |
|-----------|---------|--------------------------|
| 1 | `evidence/challenge-1-quota-concurrency/run.ps1` | 20 concurrent reservations of 4 CPU against a 20 CPU tenant -> exactly **5 x HTTP 200, 15 x HTTP 422**, final `cpu_used = 20`. Verdict line prints `PASS`. |
| 2 | `evidence/challenge-2-idempotency/run.ps1` | replay returns the identical body with `Idempotency-Replayed: true`; a changed payload on the same key -> **422**; 10 concurrent duplicates -> **one** resource; changed-body rejected. Verdict `PASS`. |
| 3C | `evidence/challenge-3c-saga-compensation/run.ps1` | billing forced to fail -> resource ends **FAILED**, provider instance terminated, **quota released in full** (back to its pre-provision value), ledger row **RELEASED**. Verdict `PASS`. |

Each script exits with code 0 on PASS and 1 on FAIL, so it is CI-checkable. Each creates
its own uniquely-named tenants and idempotency keys per run, so re-running never collides
with a previous run (TR-8).

### Resetting

```powershell
evidence/reset.ps1            # delete only this evidence's test data
evidence/reset.ps1 -WhatIf    # preview what would be deleted, delete nothing
```

`reset.ps1` removes only tenants whose ids start with `tnt-br1-`, `tnt-br2-`, `tnt-br6-`
or `tnt-exh-` (the prefixes the scripts use) together with their resources, reservations,
idempotency records and ledger rows. It never drops a schema and never touches the
`tnt-demo-001` walkthrough data or any teammate's data.

---

## Automated test suites

`unit-and-integration-tests/` holds the verbatim output of the full suites:

| File | Command | Result captured |
|------|---------|-----------------|
| `quota-tests.txt` | `cd services\quota ; .\mvnw.cmd verify` | 6 unit + 7 integration, 0 failures |
| `resource-tests.txt` | `cd services\resource ; ..\quota\mvnw.cmd verify` | 33 unit + 12 integration, 0 failures |
| `summary.md` | - | the pass/fail counts and which test proves which rule |

Unit tests (`*Test`) run under surefire; the Testcontainers-backed database tests (`*IT`)
run under failsafe and require Docker. Both phases run in one `verify`.

---

## Business-rule traceability (DOC-4)

Every business rule, mapped to the evidence file that proves it. This is deliberately
honest about what is and is not covered in U1.

| Rule | Statement | Proven by | Where |
|------|-----------|-----------|-------|
| **BR1** | Quota never exceeds tier limits under any concurrency | Challenge 1 script (20-way race) **and** `Br1ConcurrencyIT` | `challenge-1-quota-concurrency/`, `unit-and-integration-tests/quota-tests.txt` |
| **BR2** | Provisioning is idempotent; same key + same payload replays, no extra effect | Challenge 2 script (sequential replay + 10-way concurrent) **and** `IdempotencyServiceTest`, `IdempotencyReplayTest`, `IdempotencyConcurrencyIT` | `challenge-2-idempotency/`, `unit-and-integration-tests/resource-tests.txt` |
| **BR3** | Same key + different payload is rejected with 422 | Challenge 2 script (mismatch case) **and** `RequestFingerprintTest`, `IdempotencyPayloadMismatchIT` | `challenge-2-idempotency/`, `unit-and-integration-tests/resource-tests.txt` |
| **BR4** | A resource follows only the defined lifecycle graph; ACTIVE only from PROVISIONING | `ResourceLifecycleTest` (unit, TR-2) | `unit-and-integration-tests/resource-tests.txt` |
| **BR5** | A billing meter stops on termination and accrues nothing after | Now covered in the **Billing service's own unit** by `br5_billingStopsWhenResourceIsTerminated_idempotentStop` (asserts the idempotent stop and that no further cost accrues). It is a rule about Billing's internal state, so it lives in U2's evidence, not U1's. | `services/billing-service` tests (U2) |
| **BR6** | Saga compensation releases reserved quota in full | Challenge 3C script **and** `ProvisioningSagaCompensationTest` | `challenge-3c-saga-compensation/`, `unit-and-integration-tests/resource-tests.txt` |
| **BR7** | A resource's quota is released exactly once | Challenge 3C script (ledger RELEASED) **and** `Br7ReleaseExactlyOnceIT`, `TerminationIdempotencyIT` | `challenge-3c-saga-compensation/`, `unit-and-integration-tests/` |
| **BR8** | A FREE-tier tenant cannot provision a CLUSTER | `TierPolicyTest` (unit, TR-2); also shown as a failure case in the Challenge 1 script | `unit-and-integration-tests/quota-tests.txt`, `challenge-1-quota-concurrency/output.txt` |

**Honest coverage notes:**

- **BR4 and BR8** are proven by unit tests, not by the challenge scripts. The challenge-1
  script exercises BR8 as a bonus failure case, but the authoritative proof is `TierPolicyTest`.
  No challenge script exercises an illegal lifecycle transition; `ResourceLifecycleTest` is the
  proof for BR4.
- **BR5** is now covered by the Billing service's own test (`br5_billingStopsWhenResourceIsTerminated_idempotentStop`),
  verified once that service was built for the integration run. It is Billing's rule, so the proof
  lives in U2's evidence rather than here — not in U1's challenge scripts.
- Each challenge folder also includes at least one **failure case** (ER-4 / TR-10): Challenge 1
  rejects an over-limit FREE tenant; Challenge 2 rejects a changed-payload key reuse; Challenge
  3C is itself a forced-failure-and-recovery scenario, shown alongside the happy path for contrast.

---

## A note on what "evidence" means here

The two kinds of evidence are complementary, not redundant:

- the **challenge scripts** prove the system behaves correctly **end to end** - real services,
  real HTTP, a real database - which is what ER-1 to ER-6 ask for;
- the **test suites** prove each business rule is verified **individually and repeatably in
  code**, which is what TR-1 to TR-3 ask for, and they cover rules (BR4, BR8) that the
  end-to-end scripts do not.
