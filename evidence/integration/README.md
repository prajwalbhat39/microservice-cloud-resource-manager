# Integration results

Verified on 2026-10-25 against team commit `690afbc` plus the local integration changes.
The run used Java 21, PostgreSQL 16 and PowerShell 7.6.8. Five application databases and
separate test databases ran in an isolated cluster on 127.0.0.1:55435.

| Suite | Passed | Failed | Skipped |
|---|---:|---:|---:|
| Quota | 16 | 0 | 0 |
| Resource / Orchestrator | 55 | 0 | 0 |
| Billing | 13 | 0 | 0 |
| Provider | 36 | 0 | 0 |
| Notification | 10 | 0 | 0 |
| Usage Aggregator | 6 | 0 | 0 |
| Expiry Worker | 6 | 0 | 0 |
| Total | 142 | 0 | 0 |

[Maven summary](maven.txt) preserves the test counts and successful reactor results from the
full clean verification and the final 71-test Quota and Resource run. Verbose startup, SQL and
expected-failure stack traces are omitted.
Database-backed tests used real PostgreSQL; Docker was not required. `DB_POOL_MAX=2` was set
for the Maven runs to exercise recovery with a small application connection pool.

## Runtime checks

[Seven-service output](output.txt) · [Final database state](database.json) · [Script](../../scripts/integration.ps1)

All seven jars ran as separate processes with real HTTP peers. The test passed:

- Twenty concurrent quota requests: five admitted at capacity, fifteen rejected; release applies once.
- Concurrent and sequential request replay: one resource and meter; changed payload rejected.
- Provisioning, lifecycle notifications, usage composition and available zero cost.
- Manual termination: Provider shutdown, stable stopped cost and quota refund.
- Forced Billing 503: compensation, Provider cancellation, quota refund and FAILED notification.
- Scheduled expiry through the real worker and Resource API, including all cleanup effects.
- Resource restart during a Billing outage: persisted teardown resumes and completes.
- Partial and total peer outages: usage availability remains explicit and the worker keeps running.

The [database outage check](db-outage.json) stopped the isolated cluster. All five data-owning
services returned 503/DOWN and recovered to 200/UP after restart. A Billing write during the
outage returned a correlated 503 and created no meter.

A separate [capacity run](capacity.txt) completed twenty simultaneous distinct provisions and
terminated all twenty. The observed PostgreSQL connection peak was 30.

The documented [demo](demo.txt) and [database inspection and role isolation](footprint.txt) passed. The [isolated U4 process test](../u4/e2e-output.txt) also passed.
Challenge captures are linked from the [evidence index](../README.md).

## Reproduce

Use the database setup, test environment variables and Maven command in the [root README](../../README.md).
Then run `pwsh scripts/integration.ps1 -SkipBuild`. Pass `-DbHost` and `-DbPort` for another server.
The script stops its child processes and retains its unique tenant's rows for inspection.
