# Integration findings — full-reactor build, 2026-10-24

Context: the root `pom.xml` had only ever listed three of the seven service modules
(`quota`, `resource`, `provider`). The other four were therefore never compiled or
tested by `mvn verify` at the repo root, so nothing below had been caught before.
All seven modules are now listed (NFR-6), which is how these surfaced.

Build command:

```powershell
# from the repo root
.\services\quota\mvnw.cmd -fae verify
```

`-fae` ("fail at end") matters: without it the reactor stops at the first failing
module and the modules after it report `SKIPPED`, which is how `expiry-worker`'s
failures stayed hidden even after `usage-aggregator` started failing.

Docker Desktop must be running — `quota` and `resource` use Testcontainers, and
their integration tests silently **skip** rather than fail when Docker is absent.

## Reactor result

| Module | Owner | Unit | Result | Tests |
|--------|-------|------|--------|-------|
| quota-service | Prajwal | U1 | SUCCESS | 6 unit + 7 IT |
| resource-service | Prajwal | U1 | SUCCESS | 33 unit + 12 IT |
| billing-service | Karan | U2 | SUCCESS | 9 unit |
| provider-service | Vinayak | U3 | SUCCESS | 19 unit + 16 IT |
| notification | Siddharth | U4 | SUCCESS | 4 unit |
| usage-aggregator | Siddharth | U4 | **FAILURE** | 6 unit, 1 failing |
| expiry-worker | Siddharth | U4 | **FAILURE** | 6 unit, 2 failing |

Total: 112 tests, 3 failing, all three in U4.

---

## Finding 1 — `usage-aggregator`: one malformed upstream section contaminates its siblings

**Severity: blocks the build. Violates FR-37.**

```
UsageHttpTest.malformedResourceEnvelopeMarksOnlyResourcesUnavailable:107
org.opentest4j.AssertionFailedError: Expecting value to be true but was false
```

The test stubs a *malformed* resources envelope — `{"items":{}}`, an object where an
array belongs — and then asserts selective degradation:

```java
peers.stubFor(get(urlPathEqualTo("/api/v1/resources")).willReturn(okJson("{\"items\":{}}")));
JsonNode result = usage();
assertThat(result.has("resources")).isFalse();                                     // passes
assertThat(result.path("availability").path("resources").asBoolean()).isFalse();   // passes
assertThat(result.has("quota")).isTrue();   // <-- line 107, FAILS
assertThat(result.has("cost")).isTrue();
```

So the aggregator correctly drops the bad `resources` section and flags it unavailable,
but it **also loses the `quota` section**, which came back perfectly fine from a
different service.

FR-37 requires the opposite: *"When a contributing service is unavailable, the response
returns the sections it could obtain and marks the remainder unavailable, rather than
failing entirely."* One peer returning junk must not cost the caller a healthy peer's data.

Note the sibling test `missingBillingDoesNotHideQuotaOrResources` passes, so a clean
**503** is handled correctly. The gap is specifically the *malformed 200* path — the
parse failure is evidently escaping wider than the per-section boundary it should be
confined to. Likely the JSON parse happens outside whatever per-section try/catch
handles the HTTP failure.

The test is well written and is catching a genuine bug; it should not be weakened.

---

## Finding 2 — `expiry-worker`: one failed termination aborts the entire sweep

**Severity: blocks the build. Violates the resilience intent of FR-25.**

Two failures, one root cause:

```
ExpirySweepTest.failedTerminationDoesNotBlockOtherResourcesOrTheNextSweep:83
ExpirySweepTest.staleCandidateConflictDoesNotBlockTheRest:96

VerificationException: Expected at least one request matching:
  { "url": "/api/v1/resources/e5f1a2b3-2222-4c2d-8e3f-9a0b1c2d3e4f/termination",
    "method": "POST" }
Requests received: [ ]
```

`Requests received: [ ]` is the whole story: the termination for the **second** expired
resource (`...2222...`) was never attempted. The sweep processes the first candidate,
that one fails (a 5xx in one test, a 409 state conflict in the other), and the loop
dies with it — so every remaining expired resource is skipped, and stays leaked until
someone intervenes.

Both test names state the intended contract: a failed termination *"does not block other
resources or the next sweep"*, and a stale candidate conflict *"does not block the rest"*.

This is the same shape of bug the orchestrator's saga already solves with its
`quietly(...)` helper — see `ProvisioningSaga.compensate`, where each compensating call
is individually wrapped so one failure is logged and the remaining steps still run:

```java
private void quietly(Runnable action, String label) {
    try {
        action.run();
    } catch (Exception e) {
        log.error("saga.compensate.{} failed", label, e);
    }
}
```

The fix is the same pattern: wrap the per-resource termination call inside the sweep
loop, log the failure, and continue to the next candidate. A 409 in particular is an
expected, benign outcome here — it just means someone else already terminated that
resource between the `/expired` query and the termination call, which is exactly the
race the orchestrator's idempotent termination gate is designed to absorb.

---

## Finding 3 — `billing-service`: `startMeter` is check-then-act, and no test covers the race

**Severity: not blocking (nothing fails today). Correctness risk under concurrency.**

`BillingServiceImpl.startMeter` reads before writing:

```java
return meterRepository.findByResourceId(command.resourceId())        // <- read
    .map(existing -> new StartMeterResult(toMeterView(existing), false))
    .orElseGet(() -> {                                                // <- then write
        ...
        BillingMeter saved = meterRepository.save(meter);
        ...
    });
```

Two simultaneous `startMeter` calls for the same `resourceId` can both read "no meter
exists", both proceed to insert, and the second will hit the schema's
`resource_id TEXT NOT NULL UNIQUE` and surface as a 500 instead of replaying the first
meter. Idempotency would hold sequentially and break under concurrency.

Why the current suite cannot catch it:

1. `idempotency_startSameResourceTwice_returnsSameMeter` calls `startMeter` **twice in
   sequence**, so the two calls never overlap.
2. The tests run on **H2** via `@DataJpaTest` (the pom carries `h2`, and there is no
   Testcontainers dependency), so PostgreSQL's real constraint and transaction-abort
   behaviour is never exercised.

That second point is worth dwelling on, because U1 hit exactly this blind spot: an
H2-backed test passed while the PostgreSQL behaviour it claimed to prove was broken.
U1's own `SavepointGuard` bug was invisible until a Testcontainers test ran the same
code against real PostgreSQL. The unit acceptance criteria for U2 asked for
*"Integration test: Real database test for meter idempotency (UNIQUE constraint)"* —
that test does not exist yet, and it is the one that would expose this.

Suggested fix, mirroring what U1 landed for the idempotency-key guard — let the database
arbitrate instead of reading first:

```sql
INSERT INTO billing_meters (meter_id, resource_id, tenant_id, cpu, rate_per_cpu_hour, state, started_at)
VALUES (...)
ON CONFLICT (resource_id) DO NOTHING
```

The affected-row count becomes the decision: `1` means this caller created the meter,
`0` means someone else already holds it, so read theirs and return it with
`created=false`. No exception is ever raised, so the transaction stays usable.

To add the integration test, copy the Testcontainers setup from `services/quota/pom.xml`,
including this property and the failsafe plugin block:

```xml
<docker.api.version>1.44</docker.api.version>
```

Docker Engine 29 raised its minimum accepted API version while the docker-java client
bundled with Testcontainers 1.21.x still negotiates 1.32 and is rejected with a bare
400 on `/info`. Without that pin, Testcontainers reports "Could not find a valid Docker
environment" even though Docker is running fine.

**What is already good in U2:** BR5 *is* covered, by a correctly-named test
(`br5_billingStopsWhenResourceIsTerminated_idempotentStop`) that asserts both the
idempotent stop and that no second STOP event accrues. Cost accuracy, proration,
failure simulation and tenant month-cost aggregation are all tested too. The gap is
narrowly the concurrency path, not the rule coverage.

---

## Finding 4 — `billing-service` hardcoded its port *(fixed)*

`application.yml` had `port: 8083` rather than `port: ${SERVER_PORT:8083}`, the only
value in the file not driven by an environment variable. NFR-18/NFR-19 require every
value to be env-supplied with a local default, and Assignment 2's containerisation needs
the port movable without a code change. Fixed in place.

---

## Also changed in this pass (U1 owner)

| Change | Why |
|--------|-----|
| Root `pom.xml` now lists all 7 modules | NFR-6 — one command must build every service. Four modules were being silently skipped. |
| `services/quota/openapi.yaml`, `services/resource/openapi.yaml` committed | NFR-11. All three teammates had already committed theirs; U1 had not. |
| Resource peer URLs now default to the real ports (provider 8085, billing 8083, notification 8084) | units.md §8. They still pointed at the `peer-stub.py` port 8090, so Phase 3 integration could not happen. Env-var overrides for the stub are documented in the file. |
| Removed the empty `billing-service/` directory at the repo root | Leftover empty dirs beside the real `services/billing-service`; 0 files, 0 git-tracked entries. |

## Still outstanding

- **Phase 3 integration run — now DONE, see the Phase 3 section below.** The claim that
  previously stood here, that Billing *"honours the same `X-Simulate-Failure` header
  (FR-45) so the script needs no change"*, turned out to be **wrong**. It was an
  inspection-level conclusion: the header name matches on both sides, but the accepted
  *values* do not. See Finding 5.
- The three failing U4 tests above.
- Report, diagrams, demo video and the remaining DOC-1..DOC-7 items.

---

# Phase 3 — first real seven-service integration run, 2026-10-24

All five databases created (`scripts/db/setup-u1.sql` + `setup-u2-u4.sql`), all seven
jars rebuilt from scratch, six services started with `scripts\demo\start-all.ps1` (no
`peer-stub.py` anywhere). Flyway created every schema on first boot with no manual DDL.
All six reported `/health` 200.

| Scenario | Against real peers | Result |
|----------|--------------------|--------|
| Main flow (`demo-main-flow.ps1`) | Provider, Billing, Notification | works end to end |
| Challenge 1 — concurrency | Quota only | **PASS** — 5×200, 15×422, `cpu_used` exactly 20 |
| Challenge 2 — idempotency | full saga | **PASS** — 10 concurrent duplicates → 1 resource, 1 meter |
| Challenge 3C — compensation | full saga | **blocked by Finding 5** |

The happy path is genuinely integrated: a real `provider_db` instance row, a real
`billing_db` meter accruing cost, and quota arithmetic correct across services.

---

## Finding 5 — `billing-service`: `X-Simulate-Failure` types the header as `boolean`, so the orchestrator's value yields 500 instead of 503 *(FIXED 2026-10-24)*

**Severity: blocked Challenge 3C evidence against the real Billing service. Violated FR-45/FR-46.**

**RESOLUTION:** Fixed in `billing-service/.../api/BillingController.java`. The `X-Simulate-Failure`
header is now bound as a `String`, and a `shouldSimulateFailure(...)` helper treats both `true`
(billing's own test value) and `always-fail` (the orchestrator's value) as a simulated outage →
503. `BillingServiceImpl.startMeter(command, boolean)` is unchanged, so `BillingControllerTest`
still passes. Verified live: `always-fail` → 503, `true` → 503, no header → 201. Challenge 3C,
recaptured against the real services after the fix, now records
`BILLING_UNAVAILABLE: billing meter start failed after 3 attempts (503)` with the retry storm
visible in `evidence/challenge-3c-saga-compensation/{saga-logs,peer-calls}.txt`. The change is
in Karan's unit (U2); per DR-7 the commit is attributed accordingly.


The header *name* matches on both sides, which is why an inspection-level contract check
passed. The accepted **values** do not.

`BillingController.startMeter` binds the header straight to a primitive:

```java
@RequestHeader(name = "X-Simulate-Failure", required = false, defaultValue = "false")
boolean simulateFailure
```

The orchestrator does not send `true`. `SimulateFailureDirective.forBilling()` strips the
`billing:` prefix and sends the **mode**, so `BillingClient` puts `always-fail` on the wire
(asserted in `ProvisioningSagaCompensationTest`: the billing stub must receive exactly
`always-fail`). Spring cannot convert `always-fail` to a boolean, the resulting binding
exception is swallowed by Billing's generic handler, and the caller gets a 500.

Measured directly against the running service:

| `X-Simulate-Failure` | Sent by | Billing responds |
|----------------------|---------|------------------|
| `always-fail` | the orchestrator's saga | **500** `INTERNAL_ERROR` "Unexpected error" |
| `true` | `BillingControllerTest` only | 503 `DOWNSTREAM_UNAVAILABLE` "Simulated failure" |
| *(absent)* | normal path | 201, meter ACTIVE |

### Why this matters beyond a wrong status code

`PeerRetry` retries **503 and connection failures only** — every other status is "a final
answer, never retried" and is rethrown immediately. So against real Billing:

1. **No retry happens.** The demo narration "watch it retry billing three times, then
   compensate" is false. Only 503 would be retried.
2. **`failure_reason` is wrong.** The 500 never becomes a `DownstreamUnavailableException`,
   so instead of `BILLING_UNAVAILABLE: billing meter start failed after 3 attempts (503)`
   the reservation records `INTERNAL_ERROR: InternalServerError` — observed live.
3. **Challenge 3C's verdict fails.** `run.ps1` asserts `failure_reason LIKE
   'BILLING_UNAVAILABLE%'`, which is the evidence that the saga got as far as step 2
   before failing (deviation A11). That assertion now fails, so the script exits 1.

Compensation itself still behaves correctly — quota went 4 → 8 → 4, the ledger row flipped
to `RELEASED`, and `provider_instance_id` was cleared — so this is a reporting and
retry-classification defect, not a consistency defect. But the graded artefact and the
spoken narrative are both wrong, and "it says INTERNAL_ERROR because the header didn't
parse" is not a story worth telling an examiner.

### Suggested fix (U2, ~3 lines, backward compatible)

Accept the header as a `String` and treat the orchestrator's mode vocabulary as a
simulated outage. `true` keeps working, so `BillingControllerTest` is unaffected:

```java
@RequestHeader(name = "X-Simulate-Failure", required = false) String simulateFailure
...
boolean simulate = simulateFailure != null
        && Set.of("true", "always-fail").contains(simulateFailure.trim().toLowerCase(Locale.ROOT));
```

Billing already throws `ServiceUnavailableException` → 503 for a simulated failure, which
is exactly what `PeerRetry` retries and what produces `BILLING_UNAVAILABLE`. Only the
parsing of the header value needs to change.

Fixing it on the orchestrator side instead (sending `true`) was considered and rejected:
the same header carries `slow` and `always-succeed` to the Provider, which does speak the
mode vocabulary, so collapsing it to a boolean for one peer would split the contract in
two and contradict FR-42.

**Ownership (DR-7):** `billing-service` is Karan's unit (U2), so this is written up here
rather than silently patched.
