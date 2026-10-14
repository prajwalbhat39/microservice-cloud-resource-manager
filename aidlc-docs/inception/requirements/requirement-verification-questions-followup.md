# Requirements Verification: Follow-up Questions (Round 2)

**Stage**: INCEPTION / Requirements Analysis
**Status**: Answered and incorporated into `requirements.md`

This round resolved four items left open after round 1. The full rationale and the resulting
requirements live in `requirements.md`; this file records the questions and the decisions.

---

## Follow-up 1: Delivery model for a team of 4

The design produces 5 to 6 services. How should the build be sequenced and owned so that the
five mandatory conditions are satisfied by a self-contained core, and each member has
substantive, independently verifiable work?

- **A** Each member owns one or more services outright, mapped during Units Generation.
- **D** Walking skeleton first (one owner builds the structural base for every service), then each member picks up a service's business logic, tests, and a challenge.
- **E** D, with the core owner also keeping the two services that carry Challenges 1 and 3 (quota and the orchestrator).
- **F** The core owner builds the challenge-bearing services (quota, orchestrator, billing) plus minimal but complete versions of notification and the provider mock, so the mandatory conditions rest on a self-contained core. Each member then owns substantive improvements on top.

**Chosen: F.** It keeps the challenge work and the mandatory conditions on a core that
integrates and demos on its own, and lets every service be developed and tested in isolation
against WireMock stubs. See `requirements.md` section 11 for the delivery requirements and the
provisional owner allocation.

### Sub-decision: how a unit's starting point is produced

The assignment permits AI for learning and boilerplate generation, requires an AI-use
declaration, and treats presenting code one cannot explain as an integrity violation. So for
each member's unit:

- **E1** A full generated implementation to read, understand, test, and commit.
- **E2** A test skeleton, a unit brief, and interface stubs, with the member writing the implementation against the tests.
- **E3** E2 for other members' units, E1 for the core owner's own.

**Deferred to Units Generation** (recorded as OPEN-1 in `requirements.md`), since it only
matters when a unit is handed over.

[Sub-answer]: *(deferred to Units Generation)*

---

## Follow-up 2: Test depth

Question 19 was answered freeform as "however many tests the assignment mentions". The
required set is one unit test per business rule plus one end-to-end script. BR1, BR3, and BR7
are enforced by SQL predicates and a unique constraint (because Q15=A chose an atomic
conditional update and Q7=B added BR7), so a unit test with a mocked repository cannot
validate them.

- **A** Required tests, plus database-backed tests for the three SQL-enforced rules (BR1, BR3, BR7) using Testcontainers. Everything else is a plain unit test.
- **B** Required tests only; cover BR1 and BR7 through the challenge scripts and note it as a limitation.
- **C** Required tests, with the SQL-enforced rules tested against a locally started PostgreSQL rather than Testcontainers.
- **D** Full integration tests on every service.

**Chosen: A.** Keeps every business rule traceable to a test that genuinely validates it.
Consequence: Docker must be running when tests run. See `requirements.md` section 8.

---

## Follow-up 3: Failure injection

Question 16 chose a per-request `X-Simulate-Failure` header. The brief also mandates a 20%
failure rate and 1-3s delay configured via environment variables, and Challenge 3's resilience
baseline needs a downstream service that can actually be stopped.

- **A** Environment-variable defaults (`PROVIDER_FAILURE_RATE=0.2`, delay 1000-3000 ms) with the header overriding per request. No admin endpoints. The 3A-style script stops and restarts the provider process.
- **B** Header only, with the brief's defaults hardcoded.
- **C** Environment variables plus runtime admin endpoints, no header.

**Chosen: A.** The header is persisted with the reservation and replayed across the
asynchronous boundary. See `requirements.md` section 3.10 (FR-45, FR-46).

---

## Follow-up 4: Challenge 3 scope

Question 4 selected both 3A and 3C. Only one is required for the 3 marks.

- **A** Build and evidence both 3A and 3C.
- **B** 3C graded. Timeouts and retry with backoff are still implemented and described, but 3A gets no separate evidence set.
- **C** 3C graded, 3A built but marked bonus with lighter evidence.

**Chosen: B.** 3C (saga with compensation) is the graded challenge. Timeouts and retry with
backoff survive as a resilience baseline (NFR-25 to NFR-28); the circuit breaker is dropped.
See `requirements.md` section 6.3.

---

## Recorded answers (round 1)

| Q | Answer | Meaning |
|---|--------|---------|
| 1 | 4 | Team of 4 |
| 2 | see F1 | Delivery model F |
| 3 | A | Full Markdown draft of all 10 report sections plus diagrams |
| 4 | C -> F4 | Narrowed to 3C graded only |
| 5 | A | Create and read a tenant, tier fixed after creation, seed script |
| 6 | A | Brief's tier values, instance limit counts every resource type |
| 7 | B | 8 business rules, BR1 to BR8 |
| 8 | A | Linear lifecycle, no stop/start API |
| 9 | A | Optional duration, idempotent scheduled sweep |
| 10 | A | $0.12 per CPU-hour, prorated per second |
| 11 | A | One composite usage endpoint, degrades gracefully |
| 12 | A | Notify on ACTIVE, FAILED, TERMINATED, best effort |
| 13 | B | Quota synchronous, then 202, saga in background, client polls |
| 14 | A | Idempotency-Key mandatory, 422 on mismatch, 24-hour retention |
| 15 | A | Atomic conditional update |
| 16 | C -> F3 | Header override on environment-variable defaults |
| 17 | A | Java 21, Spring Boot 3.5.x |
| 18 | A | PostgreSQL, database and user per service |
| 19 | X -> F2 | Required tests plus database-backed tests for BR1, BR3, BR7 |
| 20 | C | PowerShell 7 |
| 21 | C | Full tech stack installed before Construction |
| 22 | A | Skip the User Stories stage |
| 23 | B | No security baseline extension |
| 24 | A | All 13 working assumptions hold |

## Recorded answers (round 2)

| Follow-up | Answer |
|-----------|--------|
| F1 delivery model | F |
| F1 sub-decision | Deferred to Units Generation (OPEN-1) |
| F2 test depth | A |
| F3 failure injection | A |
| F4 Challenge 3 scope | B |
