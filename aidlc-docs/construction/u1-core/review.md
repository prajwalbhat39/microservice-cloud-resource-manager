# U1 Construction design set — consistency and traceability review (pass 3)

The four Construction documents for U1 (Quota `:8081`/`quota_db`, Resource-Orchestrator
`:8082`/`resource_db`, owner Prajwal) were read end to end against the frozen Inception
contracts: `requirements.md` §5–§6, `application-design.md` §4.1/§4.2/§5/§5.7/§6/§7/§8/§12,
`units.md` §2–§3 and §9, and `workflow-plan.md` §1. This is a documentary check only — JDK,
Maven, PostgreSQL and Docker are deliberately absent, so nothing was built, compiled or run, and
every claim below comes from reading the documents. All five findings from pass 2 are closed,
each verified against the current text rather than taken on trust: the `provider_instance_id`
deviation is now assumption **A11** with its replacement evidence named, the lazy idempotency
expiry is **A13** with the 24–25 h window stated instead of rounded, the retention horizon is
computed from `IDEMPOTENCY_RETENTION_HOURS` at insert, the verbatim-replay rule has its own
section (`nfr-design.md` §2.5) that resolves the collision with §2.4, and R1's three added error
codes are deviation **A12**. No blocking finding remains.

**Watch for:** two documentation-level residues, neither blocking. The transition table attributes
the clearing of `provider_instance_id` to the `SAGA_FAILED` transition while the saga clears it
two steps earlier, in compensation step (2) (confirmed); and the `failure_reason` **prefix** that
A11 promotes to the "how far did the saga get" signal is only specified for the two peer failure
cases, leaving the internal-failure path of §6.3 without one (confirmed).

**Verdict**: APPROVED (gate value `APPROVED`)

## High-level view

Every frozen decision is intact, and the three that pass 2 put under pressure are now carried as
explicit, reversible deviations rather than silent readings. Challenge 1 is still the single
atomic conditional UPDATE reproduced verbatim from `application-design.md` §6, with the affected-row
count as the whole mechanism — no version column, no `SELECT … FOR UPDATE`, no retry loop — and the
CHECK constraints in the Flyway baseline are stated as defence in depth, not as the mechanism.
Challenge 2 is the mandatory `Idempotency-Key` with the primary-key INSERT as the guard and 24 h
retention. Challenge 3C has no `saga_state` or `saga_step` table — the baseline says so in a
header comment — and tracks outcome through `resources.status`, `reservations.status` and
`failure_reason`, with compensation as the `catch` block. The lifecycle is the five-state linear
graph; the `STOPPING`/`STOPPED` wording still in `requirements.md` §3.11 is recorded as A1.

Endpoints and columns agree with the frozen tables, enumerated below, with `/health` the only
path outside `/api/v1` (deviation N1) and exactly two data-model additions, both recorded —
`reservations.simulate_failure` for FR-46 (A7/I2) and the deliberate absence of a foreign key on
`idempotency_records.reservation_id`, justified against the §9.3 insert order. The three
error-list extensions on frozen endpoints now each have a §13 row: A2's `TENANT_ALREADY_EXISTS`,
A9's `QUOTA_RESERVATION_ALREADY_RELEASED` and A12's three R1 additions. Every code emitted
anywhere across the four documents appears once in the single vocabulary table in
`nfr-design.md` §2.2, and the one place where two cross-cutting rules genuinely conflict — a
replayed problem body — is resolved in favour of verbatim with the content type pinned.

Traceability is complete: BR1–BR8 each map to a class, a method, a named test and an evidence
file, with BR5 marked out of scope for U1 and the `requirements.md` DR-1 conflict recorded as A10
so the reassignment to U2 cannot read as a loss. NFR-1 to NFR-28 are all classified as binds /
partially binds / does not bind with a reason each, nothing omitted, with NFR-2, NFR-17 and
NFR-24 named as the retained security-baseline items.

The cross-cutting mechanisms the brief singles out are specified: the MDC crosses the async
boundary twice over (a `TaskDecorator` on the saga executor plus the correlation ID as a field on
the task payload, with the reason both exist), the circuit breaker is explicitly dropped with its
rationale, retries are justified by the idempotency properties they depend on, and the
PostgreSQL 15+ Flyway privilege correction appears in both `nfr-design.md` §6.1 and
`infrastructure-design.md` §3 with the same two reasons and the same
`ALTER SCHEMA public OWNER TO <service_user>` remedy, both stating that the approved `units.md`
is not edited.

<details>
<summary>Issues (2)</summary>

1. **Where `provider_instance_id` is cleared** — `functional-design.md` §6.1 gives the
   `PROVISIONING --SAGA_FAILED--> FAILED` row the side effect "clears `provider_instance_id`",
   but §9.5 clears it in compensation step (2), two steps before `SAGA_FAILED` is applied in
   step (4). Either fold the clear into the `SAGA_FAILED` side effect so the table stays the one
   description of writes to that column, or reword the §6.1 cell to name compensation step (2) as
   the writer. Non-blocking.
2. **`failure_reason` prefix vocabulary is incomplete** — A11, §7.3 of
   `infrastructure-design.md` and the `resources.failure_reason` column comment all rely on the
   prefix to say how far the saga got, but only `PROVIDER_UNAVAILABLE:` and `BILLING_UNAVAILABLE:`
   are defined, and §6.3's saga-driven illegal transition reaches `FAILED` with neither. Specify
   what `FailureReason.of` emits for a non-peer failure and what the evidence signal is in that
   case. Non-blocking.

</details>

<details>
<summary>Details</summary>

## The transition table and the saga disagree on who clears `provider_instance_id`

§6.1 is emphatic that the table *is* the implementation — "a transition is a lookup and an
illegal transition is a missing key" — and §6.2's rejected alternative explains that
`INSTANCE_CREATED` was made an explicit self-transition precisely to keep "the one place that
writes `provider_instance_id` inside the state machine, so compensation has a symmetric place to
clear it". The `SAGA_FAILED` row from `PROVISIONING` then lists "clears `provider_instance_id`
after the provider instance is terminated" as its side effect.

§9.5 does it differently. `compensate` calls `lifecycle.clearInstanceId(task.resourceId())` in
step (2), immediately after `providerClient.terminate`, and applies `SAGA_FAILED` in step (4),
after the quota release. So `clearInstanceId` is a `ResourceLifecycle` mutator that is not an
event on the graph, and the column is cleared while the row is still `PROVISIONING`.

The ordering in §9.5 is the better one — clearing only after the terminate call has returned is
what makes the null honest — so the fix is to the description, not the sequence. It is worth
closing because the window it opens is exactly the one A6 already reserves for manual handling: a
crash between step (2) and step (4) leaves a `PROVISIONING` row with no instance id, which the
§7.3 evidence rule would read as "step 2 never completed" while the WireMock journal says
otherwise. Naming the writer in §6.1 costs a sentence and keeps the state machine's claim about
itself true.

## A11's replacement signal is specified for two failure cases out of three

A11 trades §8.4's retained-instance-id evidence for the `failure_reason` prefix, and the trade is
reasonable: `BILLING_UNAVAILABLE:` is reachable only with step 2 complete and
`PROVIDER_UNAVAILABLE:` only with step 2 incomplete, so the prefix carries the same information
the column used to. `infrastructure-design.md` §7.3 builds the Challenge 3C assertion on it and
the `resources.failure_reason` column comment advertises it.

The gap is that `FailureReason.of(cause)` is never specified beyond those two examples, and §6.3
explicitly introduces a third way into `FAILED`: a saga-driven illegal transition — "a bug or a
late duplicate task" — caught by the saga's `catch`, which compensates and lands on `FAILED` with
a reason that names no peer at all. The same applies to a failure in step 4's `ACTIVATED`
transition or in `reservationService.complete`, both of which sit after the provider and billing
calls have succeeded. In those cases the FAILED row has a cleared `provider_instance_id` and a
prefix outside the vocabulary, so neither of A11's two signals answers how far the saga got; only
the WireMock journal does.

This is a one-line specification gap, not a design problem: name the prefix for a non-peer failure
(`INTERNAL_ERROR:` would match the §2.2 code that already exists for exactly this class of fault)
and state that the journal is the surviving signal there.

## Prior-pass findings, closed

The frozen-contract one is now a recorded deviation with a stated cost and a stated reversal
path. A11 (`functional-design.md` §13) names §8.4's second evidence case as unobservable in the
column, moves the signal to `failure_reason`, corroborates it with the WireMock journal, and says
what to revert if the retained id is wanted instead; §6.1, §4.2 and §9.5 all cross-reference it,
and `infrastructure-design.md` §7.3 carries the matching two-signal assertion table rather than a
bare null check.

The idempotency-expiry one is now stated rather than rounded. §9.2's step 7 reads "Still
authoritative, and still decided by steps 4–6, until the retention sweep deletes the row", the
accompanying paragraph names the 24-to-25-hour window on the defaults and argues why a
delete-then-proceed on the contended path is not worth it, A13 records the reading, and
`nfr-design.md` D5 says the same thing from the sweep's side. The horizon itself is now computed
at insert from `idempotency.retention-hours` (§9.2 comment, §5.2 of the domain model,
`nfr-design.md` §5.1/§5.3 and the `idempotency_records` table comment), so the record and the
sweep cannot disagree.

The replay-versus-`correlationId` collision has its own section. `nfr-design.md` §2.5 states that
verbatim wins as the single documented exception to §2.4, gives the reason (AC-2.1 compares
bodies), pins the content type to `application/problem+json` when `response_status >= 400` with
no extra column, keeps the replaying request traceable through `Idempotency-Replayed: true` and
the `originCorrelationId` field on the `idempotency.replay` log line, and names the test. D6
records it, `nfr-requirements.md` §3's NFR-10 row admits it as the one exception, and §9.2 of the
functional design says the same from the other side.

R1's three additions are A12, in the same form as A2 and A9, with the propagation rule and NFR-28
as the justification and the alternative (a pre-flight tenant lookup) priced.

## What was checked and matched

Endpoints, one by one against `application-design.md` §5.1/§5.2: `POST /api/v1/tenants` (201),
`GET /api/v1/tenants/{id}` (200/404), `GET /api/v1/tenants/{id}/quota` (200/404),
`POST /api/v1/quota/reservations` (200), `POST /api/v1/quota/reservations/{resourceId}/release`
(200), `POST /api/v1/resources` (202 + `Location`), `GET /api/v1/reservations/{id}` (200/404),
`GET /api/v1/resources/{id}` (200/404), `GET /api/v1/resources?tenantId=&status=` (200/400),
`POST /api/v1/resources/{id}/termination` (202, 404, 409), `GET /api/v1/resources/expired` (200).
No path, method or success code diverges; the required/optional split on the list filter is A8,
and R1's `Idempotency-Key` / `X-Simulate-Failure` header contract is carried through. Eleven
business paths, which is the count `nfr-requirements.md` §1 and §3 both state.

Columns, one by one against §4.1/§4.2: `tenants` (4), `tenant_quota` (7, no version column),
`quota_reservations` (8 with `UNIQUE(resource_id)`), `resources` (12), `reservations` (8 = the
frozen 7 plus `simulate_failure`), `idempotency_records` (8). I3 records the type choices,
including why `resource_id` is `uuid` in `resource_db` but `varchar(64)` in `quota_db`.
Constraints and indexes beyond the frozen set are additive, justified by a named query pattern,
and mapped to rules in §2.3.

Error codes: every code emitted in any of the four documents appears once in `nfr-design.md` §2.2
with a single status — the 400s, the four 404s, the three 409s, the four 422s, the four 503s, plus
`METHOD_NOT_ALLOWED`, `INTERNAL_ERROR` and `SIMULATED_FAILURE`. D1 records that
`application-design.md` §5.7's list was illustrative and that none of the additions contradicts it.

Headers and acceptance: all four documents carry stage, status, unit, owner, services-in-scope and
dependencies; each has a Stage Acceptance section; each separates the two services explicitly (the
NFR documents do it per obligation row); and each has an Open Questions table rather than resolving
frozen-input gaps silently. The four also cover what `workflow-plan.md` §1 assigns them —
endpoints, domain model, transitions and rules; the §5 NFRs that bind; validation, error model,
correlation ID, logging, config, migrations; and the database, Flyway baseline and environment
variables with no cloud or container work.

</details>

<details>
<summary>Documents reviewed</summary>

| Document | What it carries |
|----------|-----------------|
| `functional-design.md` | Endpoints Q1–Q6 / R1–R7, DTOs and validation, domain model, the five-state lifecycle table, the four challenge algorithms including `SavepointGuard`, BR/FR traceability with evidence files, thirteen recorded assumptions |
| `nfr-requirements.md` | NFR-1 to NFR-28 classified with per-service obligations and verification methods, the security-baseline note, the toolchain-deferred checklist |
| `nfr-design.md` | Validation and the 400 shape, the exception hierarchy and the complete `code` vocabulary, peer-rejection propagation and the verbatim-replay exception, correlation ID across the async boundary, logging, both `application.yml` files and the env-var mapping, springdoc and pinning, Flyway plus the PG15+ privilege correction, health, the resilience baseline and the dropped circuit breaker |
| `infrastructure-design.md` | Both `V1__baseline.sql` files with the constraint → rule map, the corrected setup script, seed and reset data with the Challenge 1 arithmetic (N = 5), environment-variable tables, local run order, WireMock in both modes, the challenge scripts and evidence file names, Testcontainers setup |

Frozen inputs used as the baseline: `inception/requirements/requirements.md`,
`inception/design/application-design.md`, `inception/units.md`, `inception/workflow-plan.md`.

</details>
