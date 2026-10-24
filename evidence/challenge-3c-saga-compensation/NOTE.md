# Challenge 3C — notes on the test setup

## What was under test

The saga and its compensation logic in the Resource/orchestrator service:

- `domain/service/ProvisioningSaga.run(...)` — the forward path (steps 2→4)
- `domain/service/ProvisioningSaga.compensate(...)` — the reverse path (undo 3→2→1)
- `domain/ResourceLifecycle` — the state transitions the saga drives
- `domain/FailureReason` — the `failure_reason` prefix that records how far the saga got
- `client/PeerRetry` — retry with exponential backoff and jitter before giving up

All of that is this service's own code, and it is what the evidence exercises.

## How the failure was injected

Per-request, via the `X-Simulate-Failure: billing:always-fail` header (FR-46).

The orchestrator parses that header into `SimulateFailureDirective`, persists it on
`reservations.simulate_failure`, and replays it onto the matching downstream call from the
background saga. That indirection matters: the client's HTTP exchange has already ended at the
202 by the time the saga runs, so the header itself is long gone. `db-after.txt` shows the
persisted value (`simulate_failure = billing:always-fail`) on the compensated reservation,
which is the evidence that the injection genuinely survived the asynchronous boundary rather
than being applied inline.

Note the routing: the directive is `billing:always-fail`, and only billing failed. The provider
call in the same saga succeeded normally (`peer-calls.txt` shows the provider provision at 201).
The prefix targets one peer, which is what `SimulateFailureDirective.forProvider()` /
`.forBilling()` implement.

## Which services were real

This evidence is from the **first full seven-service integration run (2026-10-24)** — every
peer is the teammate's real service, launched with `scripts\demo\start-all.ps1`. The
`scripts/peer-stub.py` stand-in is NOT used anywhere here.

| Service | In this run | Notes |
|---|---|---|
| Resource / orchestrator | **real**, `java -jar`, port 8082 | the code under test |
| Quota | **real**, `java -jar`, port 8081 | the quota release (undo of step 1) is a real HTTP call to a real service against a real database |
| Provider | **real**, `java -jar`, port 8085 | Vinayak's U3 service; provisioned and then terminated a real `provider_db` instance row |
| Billing | **real**, `java -jar`, port 8083 | Karan's U2 service; returned the 503 that drove compensation (see Finding 5 below) |
| Notification | **real**, `java -jar`, port 8084 | Siddharth's U4 service; wrote the real FAILED notification row |
| PostgreSQL | **real**, local instance, port 5432 | `resource_db` and `quota_db`, schemas created by Flyway on first boot; all state assertions read actual committed rows |

An earlier capture of this challenge (2026-10-20) ran the three peers through
`scripts/peer-stub.py` on port 8090, because those services did not exist yet — the design
anticipated exactly that (`infrastructure-design.md` §8 specifies a single-port WireMock stand-in,
and `DR-5` has the orchestrator developed against stubs so each unit can progress independently).
This run supersedes it: the peers are now real.

## Finding 5 — the billing header-value bug this run uncovered and fixed

The first real run exposed a contract mismatch that the stub had hidden. The orchestrator sends
the failure MODE as the header value (`always-fail`, after stripping the `billing:` prefix, per
FR-42). Billing originally bound `X-Simulate-Failure` to a **boolean**, so it could not parse
`always-fail` and returned a **500 INTERNAL_ERROR** instead of a 503. Because `PeerRetry` only
retries 503, the orchestrator did not retry and recorded `INTERNAL_ERROR` rather than
`BILLING_UNAVAILABLE` — the compensation still ran correctly, but the reason text and the retry
story were both wrong.

The fix (billing `BillingController`, ~3 lines): accept the header as a `String` and treat both
`true` (billing's own test value) and `always-fail` (the orchestrator's value) as a simulated
outage → 503. Full write-up in `aidlc-docs/integration-findings.md`, Finding 5. The evidence
below is from **after** that fix, which is why the reason reads `BILLING_UNAVAILABLE` again —
now genuinely produced by the real billing service, not a stub.

The substitution of real peers does not change what compensation proves: it reacts to an **HTTP
response code**, not to the identity of whatever produced it. A 503 from a stub and a 503 from
the real billing service enter `ProvisioningSaga` through the same `catch` block and drive the
same reverse-order undo. What only the real run adds is confidence that the real billing service
actually emits that 503 for this header — which the stub could never prove.

## Evidence files

| File | What it contains |
|---|---|
| `run.ps1` | the script, re-runnable; creates its own tenant and keys per run |
| `output.txt` | the captured console output of this real run, with timestamps |
| `db-before.txt` | quota usage, ledger rows and resources before anything ran |
| `db-after.txt` | the same tables after, showing RESERVED vs RELEASED side by side |
| `saga-logs.txt` | the orchestrator's own structured log lines for the compensated transaction |
| `peer-calls.txt` | the downstream calls seen from the peers' OWN logs — corroborates the saga log from the other side of the wire |

## Headline result from the captured run

```
quota before this provision : 4 CPU
quota while saga in flight  : 8 CPU   (4 CPU held for the doomed resource)
quota after compensation    : 4 CPU   (released in full)

resource ended FAILED                            : True
provider_instance_id cleared (no orphan, AC-3.5) : True
quota ledger row flipped to RELEASED (BR7)       : True
reserved quota returned in full (BR6, AC-3.4)    : True
VERDICT: PASS
```

`failure_reason` on the compensated resource reads:

```
BILLING_UNAVAILABLE: billing meter start failed after 3 attempts (503)
```

The `BILLING_UNAVAILABLE` prefix is load-bearing. Because compensation clears
`provider_instance_id` after terminating the instance (recorded deviation **A11**), that column
can no longer distinguish "failed before an instance existed" from "failed after one was
created and then cleaned up". The prefix carries that signal instead: `BILLING_UNAVAILABLE` is
only reachable with step 2 already complete, whereas `PROVIDER_UNAVAILABLE` is only reachable
with step 2 incomplete. `peer-calls.txt` corroborates it — the provider terminate carries the
same instance id the provision returned, proving an instance existed and was explicitly removed.
