# Saga compensation

Captured on 2026-10-25 against real Quota, Resource, Provider, Billing and Notification services,
with isolated PostgreSQL on 127.0.0.1:55435.

The script provisions one resource normally, then forces Billing to return 503 for another.
Resource cancels the Provider request, reconciles any uncertain meter start and refunds quota
before completing the reservation as FAILED.

| Claim | Evidence |
|---|---|
| Repeatable Billing failure and compensation | [Script](run.ps1), [output](output.txt) |
| Failed resource, cleared instance ID and RELEASED quota ledger | [Before](db-before.txt), [after](db-after.txt) |
| Retry attempts and reverse cleanup order | [Resource log excerpts](saga-logs.txt) |
| Peer responses with the same correlation ID | [Peer log excerpts](peer-calls.txt) |

Log excerpts preserve the original lines and timestamps. [Integration results](../integration/README.md)
also cover cleanup after a peer outage and Resource restart.
