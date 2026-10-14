# Workflow Plan

**Stage**: INCEPTION / Workflow Planning
**Profile**: Classic (Inception, Construction, Build and Test)
**Status**: APPROVED (2026-10-12)
**Depends on**: `inception/requirements/requirements.md` (approved)

This plan fixes the remaining stage route, the Construction build order, the design stages
that run per unit, and the single toolchain checkpoint. It makes no product decisions; those
belong to Application Design.

---

## 1. Remaining stage route

### Inception (document only, no toolchain)

| Stage | Runs | Output |
|-------|------|--------|
| Application Design | Yes | Service list, per-service endpoints, per-service data model and ownership, the error model, the saga contract, and frozen inter-service contracts |
| Units Generation | Yes | Units of work with an owner each, and the dependency DAG that fixes build order |

### Construction (per unit, then once across the solution)

Design stages run per unit. The set below is applied to each unit at the depth its unit brief
specifies; a stage self-skips when it does not apply to that unit.

| Stage | Applies to | Notes |
|-------|-----------|-------|
| Functional Design | Every unit | The unit's endpoints, domain model, state transitions, and the business rules it enforces |
| NFR Requirements | Every unit | The cross-cutting requirements from `requirements.md` section 5 that bind this unit |
| NFR Design | Every unit | How this unit meets them: validation, error model, correlation ID, logging, config, migrations |
| Infrastructure Design | Every unit | The unit's database, its Flyway baseline, and its environment variables. No cloud infrastructure; containerisation is Assignment 2 |
| Code Generation | Every unit | Source, tests, and the unit's `openapi.yaml`. Requires the toolchain (section 3) |

Run once after all units:

| Stage | Output |
|-------|--------|
| Build and Test | Full multi-service build, the end-to-end script, and the three challenge scripts with their evidence |

### Operation

Placeholder. Docker and Minikube deployment is Assignment 2.

---

## 2. Construction build order

Order follows the dependency DAG that Units Generation will produce. The provisional order,
driven by the delivery model in `requirements.md` section 11:

1. **Quota service** — built first and completely. It is the reference implementation for the
   cross-cutting patterns in `CONTRIBUTING.md` (error model, correlation-ID filter, logging,
   config, Flyway layout, package structure). Carries Challenge 1.
2. **Orchestrator and idempotency layer** — the saga, lifecycle state machine, and the
   idempotency component. Carries Challenges 2 and 3C. Built and tested against WireMock
   stubs of its four dependencies, so it needs no other service running.
3. **Billing service** — meter lifecycle and cost, including the idempotent meter start.
4. **Minimal notification service and minimal provider mock** — real services (own folder,
   project file, database, migration, health endpoint) doing the simple thing each needs to.
   This completes the self-contained core: every mandatory condition is satisfied and the
   system runs and demos end to end.
5. **Layered units** — provider mock beyond minimal plus the resilience baseline; notification
   beyond minimal (delivery states, retry); the composite usage view and the expiry sweep.
   Each builds on a frozen contract and a running core.

Steps 1 to 4 are the self-contained core. Step 5 improves services without being a
prerequisite for a working, demonstrable system.

---

## 3. Toolchain checkpoint

Per decision Q21, the full stack is installed before Construction. Concretely, it must be in
place before the first **Code Generation** stage, because that is the first stage that
compiles and runs code. Everything up to and including Application Design and Units Generation
needs no toolchain.

An install checklist with exact versions and commands is produced at the end of Inception
(it is already drafted in `requirements.md` section 7.1):

- JDK 21
- PostgreSQL 16 or 17
- Docker Desktop (for Testcontainers at test time, per decision F2)
- PowerShell 7
- Git

Maven is not required; the wrapper is committed per service.

---

## 4. Ceremony for this run

| Setting | Value | Source |
|---------|-------|--------|
| Depth | Comprehensive | Marking rewards traceability from rules to code, tests, and evidence |
| User Stories stage | Skipped | Decision Q22 |
| Security baseline extension | Not enabled | Decision Q23; overlapping items kept as NFR-2, NFR-17, NFR-24 |
| Reviews | One advisory pass per stage at its approval gate | Classic default |
| Approval gates | One per stage: Application Design, Units Generation, then per Construction unit, then Build and Test | Classic |

---

## 5. Open items carried forward

From `requirements.md` section 14, to be resolved at the stage noted:

- **OPEN-3** (whether the usage view and expiry sweep are separate deployables) — Application Design.
- **OPEN-2** (member names) — Units Generation.
- **OPEN-1** (how each unit's starting point is produced: generated implementation vs test skeleton and brief) — Units Generation.

None blocks Application Design.

---

## 6. Stage acceptance

This plan is complete when approved. On approval, the next stage is **Application Design**.
