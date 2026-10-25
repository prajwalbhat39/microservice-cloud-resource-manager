# Provisioning idempotency

Repeated requests with one key return the original operation. Ten concurrent duplicates create
one resource; a changed payload returns 422.

| Claim | Evidence |
|---|---|
| Sequential replay, concurrent replay and payload mismatch | [Script](run.ps1), [output](output.txt) |
| One resource and quota allocation per key | [Before](db-before.txt), [after](db-after.txt) |
| Replay is logged explicitly | [Raw replay excerpts](replay-logs.txt) |

The log excerpts come from the PostgreSQL-backed HTTP tests on 2026-10-25, using their own
sequential and concurrent fixtures. They are separate from the challenge script's captured run.
Test results are in the [Maven summary](../integration/maven.txt).
