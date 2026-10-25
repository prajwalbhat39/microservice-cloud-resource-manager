# Evidence

[Current integration results](integration/README.md) contain the full Maven verification,
seven-service process test, restart recovery, database state and documented demo checks.

## Engineering challenges

| Challenge | Behavior | Files and claims |
|---|---|---|
| 1 | Twenty concurrent quota requests: five admitted at capacity, fifteen rejected | [Evidence](challenge-1-quota-concurrency/README.md) |
| 2 | Sequential and concurrent replay; changed payload rejected | [Evidence](challenge-2-idempotency/README.md) |
| 3C | Forced Billing failure, Provider compensation and quota refund | [Evidence](challenge-3c-saga-compensation/README.md) |

Each challenge README maps its claims to scripts, output, database state and required log excerpts.
Full runtime logs and duplicate historical captures are excluded.

From the repository root, start the services using the root README, then run:

```powershell
pwsh evidence/challenge-1-quota-concurrency/run.ps1
pwsh evidence/challenge-2-idempotency/run.ps1
pwsh evidence/challenge-3c-saga-compensation/run.ps1
```

Scripts accept `-DbHost` and `-DbPort` for another PostgreSQL server. They create fresh test tenants
and return a failing exit code when their checks fail. The default local development password is
changeme; set `DB_PASSWORD` to override it.

## Supporting records

- [Isolated supporting-service tests](u4/README.md)
- [Current seven-service verification](integration/README.md)

`reset.ps1 -WhatIf` previews removal of the challenge tenant prefixes. Actual removal refuses
active resources or pending recovery work. Terminate resources and wait for cleanup first.
Expired idempotency records are removed by the service's retention sweep.
