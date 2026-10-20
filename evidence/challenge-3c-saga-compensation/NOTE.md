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
call in the same saga succeeded normally (`peer-calls.txt` line `POST /api/v1/provider/instances
-> 201`). The prefix targets one peer, which is what `SimulateFailureDirective.forProvider()` /
`.forBilling()` implement.

## Which services were real

| Service | In this run | Notes |
|---|---|---|
| Resource / orchestrator | **real**, `java -jar`, port 8082 | the code under test |
| Quota | **real**, `java -jar`, port 8081 | the quota release (undo of step 1) is a real HTTP call to a real service against a real database |
| PostgreSQL | **real**, local instance, port 5432 | `resource_db` and `resource_db` schemas created by Flyway; all state assertions read actual committed rows |
| Provider | `scripts/peer-stub.py`, port 8090 | owned by another team member (unit U2/U3), not yet built |
| Billing | `scripts/peer-stub.py`, port 8090 | owned by another team member, not yet built |
| Notification | `scripts/peer-stub.py`, port 8090 | owned by another team member, not yet built |

The three stubbed peers are stubbed because those services do not exist yet — they are other
members' deliverables. The project's own design anticipates exactly this: `infrastructure-design.md`
§8 specifies WireMock on a single port (8090) standing in for all three during this unit, and
`DR-5` states the orchestrator "is developed and tested against WireMock stubs throughout, so
each service can progress independently of the others." `scripts/peer-stub.py` fills that role
for a live run rather than a test run.

The substitution does not weaken what is proven here. Compensation reacts to an **HTTP response
code**, not to the identity of whatever produced it: a 503 from a stub and a 503 from a real
Billing service enter `ProvisioningSaga` through the same `catch` block and drive the same
reverse-order undo. What the stub cannot prove is anything about Billing's own internal
behaviour — notably **BR5** ("a billing meter stops when its resource is terminated and accrues
nothing thereafter"), which is a rule about the Billing service's own state and belongs to that
service's evidence, not this one.

When the real Billing service lands, these scripts run unchanged: FR-45 requires it to accept
the same `X-Simulate-Failure` header with the same semantics, so `run.ps1` needs no edit.

## Evidence files

| File | What it contains |
|---|---|
| `run.ps1` | the script, re-runnable; creates its own tenant and keys per run |
| `output.txt` | the captured console output of a real run, with timestamps |
| `db-before.txt` | quota usage, ledger rows and resources before anything ran |
| `db-after.txt` | the same tables after, showing RESERVED vs RELEASED side by side |
| `saga-logs.txt` | the orchestrator's own structured log lines for the compensated transaction |
| `peer-calls.txt` | the downstream call journal — corroborates the log from the other side of the wire |

## Headline result from the captured run

```
quota before this provision : 4 CPU
quota while saga in flight  : 8 CPU   (4 CPU held for the doomed resource)
quota after compensation    : 4 CPU   (released in full)

resource ended FAILED                            : True
provider_instance_id cleared (no orphan, AC-3.5) : True
failure_reason names the failed step             : True
quota ledger row flipped to RELEASED (BR7)       : True
reserved quota returned in full (BR6, AC-3.4)    : True
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
with step 2 incomplete. `peer-calls.txt` corroborates it — the `DELETE /api/v1/provider/instances/
i-mock-3a033fe` proves an instance existed and was explicitly removed by id.
