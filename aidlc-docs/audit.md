# AI-DLC Audit Log

User inputs are recorded as given, except where a note indicates an input was paraphrased to
keep this shared record neutral.

---

## Workflow Start: Initial Request
**Timestamp**: 2026-10-11
**Phase / Stage**: INCEPTION / Workspace Detection
**User Input**: "hello read the requirements doc given in this workspace and start the AIDLC flow"
**AI Action**: Read `Cloud_Resource_Manager_Requirements_Only.md` and `README.md`. Started AI-DLC with the Classic profile (v1-style Inception, Construction, Build and Test), run directly in chat. The AI-DLC 2.x native tooling is not installed in this workspace.

---

## Workspace Detection: Findings
**Timestamp**: 2026-10-11
**Findings**: No application code, build files, or existing `aidlc-docs/aidlc-state.md`. The workspace holds only `README.md` (title only) and the requirements document.
**Decision**: Greenfield. Reverse Engineering skipped.
**Next Stage**: Requirements Analysis

---

## Requirements Analysis: User Input on Toolchain
**Timestamp**: 2026-10-11
**User Input**: "so one thing is all those technologies may not be installed it needed fior the AIDLC flow? I was thinking I will install and run all those after construction"
**Context**: The AI had started checking for Java and Maven to tailor the tech-stack questions. The user cancelled those checks.
**AI Response**: No toolchain is needed for Inception or the Construction design stages. JDK, Maven, Docker, and PostgreSQL only matter for compiling and running code (Code Generation verification, Build and Test). Environment checks were not re-run. Installation timing is captured as Question 21.

---

## Requirements Analysis: Clarifying Questions Issued
**Timestamp**: 2026-10-11
**Intent Analysis**:
- Request type: new project (greenfield)
- Scope: multi-service system (at least 4 business services plus a provider mock)
- Complexity: high (cross-service transaction with compensation, concurrency control on quota, idempotent provisioning, unreliable dependency)
- Clarity: detailed requirements, with key decisions left open (Challenge 3 option, lifecycle semantics, API style, concurrency mechanism, tech stack specifics, team split)
**Depth**: Comprehensive. The assignment grades traceability from business rules to code, tests, and evidence.
**Artifact**: `aidlc-docs/inception/requirements/requirement-verification-questions.md` (24 questions)
**Status**: Waiting for user answers. Stage not approved.

---

## Requirements Analysis: Toolchain Timing Confirmed
**Timestamp**: 2026-10-11
**User Input**: "ok then before construction I will  instll all  the tech stack  for inception we dont  need right ?"
**AI Response**: Confirmed that Inception needs no toolchain. Recorded Q21 = C (install the full stack before Construction). An install checklist will be produced at the end of Inception from the Q17–Q20 answers.
**Status**: The other 23 questions are still unanswered.

---

## Requirements Analysis: Round 1 Answers Received
**Timestamp**: 2026-10-11
**User Input**: "ok done now check"
**Answers on disk**: Q1=4, Q2=blank, Q3=A, Q4=C, Q5=A, Q6=A, Q7=B, Q8=A, Q9=A, Q10=A, Q11=A, Q12=A, Q13=B, Q14=A, Q15=A, Q16=C, Q17=A, Q18=A, Q19=X ("how much ever tests mentioned in assignment"), Q20=C, Q21=C, Q22=A, Q23=B, Q24=A

**Validation findings**:
1. Q2 is blank and the team has 4 members, so a work split is needed. Individual marks depend on commit history and the contribution table.
2. Q19 answered freeform. Read as option B (required tests only). Conflict: Q15=A puts BR1 enforcement in a SQL predicate and Q7=B adds BR7, so neither rule can be validated by a unit test with a mocked repository. The assignment requires every rule to trace to a unit test.
3. Q16=C defines no source for the brief's mandated 20% failure rate and 1-3s delay, and the assignment requires configuration via environment variables. Separately, Challenge 3A requires a downstream service to be stopped, which no header can simulate.
4. Q4=C (both 3A and 3C) roughly adds 40% to Construction work when only one option is required. Flagged for explicit confirmation rather than assumed.

**No conflict found** between Q13=B (async) and Q16=C: the simulation header is stored with the reservation and replayed on background saga calls.

**AI Action**: Wrote `aidlc-docs/inception/requirements/requirement-verification-questions-followup.md` with 4 follow-up questions. Round 1 answers recorded in a table in the same file.
**Status**: Waiting on follow-up answers. Requirements Analysis is not approved.

---

## Requirements Analysis: Work Split Discussion
**Timestamp**: 2026-10-11
**User Input (paraphrased)**: Asked about building a structural base first that the team then builds on, since service ownership was not yet assigned.
**User Input (paraphrased)**: Asked whether individual services can be built and tested in isolation, so the core owner could take the challenge-bearing services and others could take remaining services, documentation, and the video.

**AI Response**: Added options D (walking skeleton then handover) and E (D, plus the core owner keeps quota and the orchestrator) to Follow-up 1, along with a service dependency map and standalone testing techniques.

**Technical finding**: Only the orchestrator and the composite usage view make outbound calls. Four of six provisional services are leaves with zero dependencies, a consequence of database-per-service plus REST-only communication. Every service, the orchestrator included, can be developed and tested in isolation: domain unit tests need no database or network; leaf services need only their own database; the orchestrator uses WireMock in tests and WireMock standalone for manual runs, since service URLs are already environment variables. Contract-first design in Application Design removes development-time coupling.

**Recommendation**: Keep the orchestrator with the core owner. It carries the saga, compensation, and Challenge 3, the largest concentration of marks and the hardest component, so it is the best fit for the owner building the core first; the leaf services are the natural units to distribute.

**Note on marking**: Individual marks are judged on commit history and the contribution table, so every member needs code commits of their own; documentation and video alone are insufficient at the individual level.

**Status**: Waiting on the 4 follow-up answers.

---

## Requirements Analysis: Option E Detailed
**Timestamp**: 2026-10-11
**User Input**: "how does option E work exactly?"
**AI Action**: Expanded option E in the follow-up file with a 7-step sequence, git mechanics, a revised owner table, and a sub-decision (E1/E2/E3) on how a unit's starting point is produced.

**Design correction made**: Challenge 2 (idempotency) sits on the provisioning endpoint, which belongs to the orchestrator, not billing. The earlier owner table placed Challenge 2 on billing, which was wrong. Revised so the idempotency layer is a separable component (`idempotency_records` table, request filter, fingerprint hashing, unique constraint, replay path) alongside the billing service, where the idempotent meter-start repeats the pattern. This keeps two owners out of the same files.

**Key property identified**: Step 2 (walking skeleton with hardcoded success responses) means the system runs end to end before any business logic exists. Combined with step 4 (the core's two services complete Challenges 1 and 3C against WireMock), the mandatory conditions and the highest-mark challenges are satisfied by the core itself. A unit left at its minimal version degrades to a stub rather than blocking the demo.

**Integrity point raised**: The assignment treats presenting code one cannot explain as an integrity violation, so whether a unit starts from a generated implementation (E1), test skeletons and a brief to implement against (E2), or a mix (E3) is a decision to make explicitly, not a default.

**Status**: Waiting on Follow-ups 1 (with the E sub-decision), 2, 3, and 4.

---

## Requirements Analysis: Option F Added
**Timestamp**: 2026-10-11
**User Input**: "Or what If I just take up like 2 or 3 services which invples the challenges and use mocks to test them and give the rest to them ?"
**AI Action**: Added option F to Follow-up 1 and marked it the recommendation while service ownership is still being assigned.

**Critical finding**: The provisional service list has exactly 4 business services (quota, orchestrator, billing, notification) against Condition A's minimum of 4, so there is no slack. If only two business services are completed, the repository holds 2 business services and Condition A fails outright. The mandatory conditions are pass/fail, not graded, so this is a different class of risk from losing marks. Option E did not have this exposure because its walking-skeleton step creates every service.

**Mitigation designed**: Conditions B, C, D and Challenges 1, 2, and 3C are all satisfied by the core services. Only Condition A (service count) and Condition E (provider mock exists) rest on the two remaining services, and both are about existence rather than quality. Notification (stores a row, writes a log line) and the provider mock (random delay, configured failure rate, fake instance ID) are roughly an hour each, so the core owner commits minimal real versions of both. Substantive improvements are then layered on top rather than owning existence.

**Second finding**: The assignment grades a consistent JSON error format across all services and correlation-ID propagation. Independent scaffolding across services causes drift. Mitigation: build quota first and completely as the reference service, plus a `CONTRIBUTING.md` recording the error model, correlation-ID filter, logging format, environment-variable naming, Flyway layout, and package structure.

**Recommended split under F**: core owner builds quota, orchestrator with the idempotency layer, and billing, plus minimal notification and provider mock. Member 2 takes the provider mock beyond its minimal version and the resilience baseline. Member 3 takes the notification service. Member 4 takes the composite usage view and the expiry job.

**Status**: Waiting on Follow-ups 1 (including the E/F sub-decision on generated code), 2, 3, and 4.

---

## Requirements Analysis: Round 2 Answers and Artifact Generation
**Timestamp**: 2026-10-11
**User Input**: "done answered all the follow up questions"
**Answers on disk**: F1=F, F1 sub-answer (E1/E2/E3)=blank, F2=A, F3=A, F4=B

**Interpretation**:
- F1=F: delivery model F. The core owner builds quota, orchestrator with idempotency layer, and billing, plus minimal real versions of notification and the provider mock, so Conditions A and E are satisfied by the core itself.
- F2=A: required tests plus Testcontainers-backed tests for BR1, BR3, BR7. Consequence: Docker is now required at test time, which it was not under F2=B or F2=C.
- F3=A: environment-variable defaults (PROVIDER_FAILURE_RATE=0.2, delay 1000-3000 ms) with an `X-Simulate-Failure` per-request override. No admin endpoints.
- F4=B: 3C only is the graded Challenge 3. Timeouts and retry with backoff survive as a resilience baseline (NFR-25 to NFR-28) with no separate evidence set. The circuit breaker is dropped. This supersedes Q4=C.

**Knock-on change made**: F4=B removed Challenge 3A as a graded deliverable, which had been the member 2 unit's assignment. The member 2 unit was re-scoped to the provider mock beyond its minimal version plus the resilience baseline.

**Correction made**: earlier chat messages referred to BR9 and BR12 for the expiry sweep and notification best-effort behaviour. The agreed rule set from Q7=B is BR1 to BR8 only. Expiry and notification behaviour are recorded as functional requirements (FR-23 to FR-25, FR-32 to FR-35), not numbered business rules, which keeps the "one test per business rule" count at 8.

**Artifact written**: `aidlc-docs/inception/requirements/requirements.md`, 15 sections. Contains 50 functional requirements, 28 non-functional requirements, 8 business rules with enforcement points and test types, 10 testing requirements, 6 evidence requirements, 9 delivery requirements, 7 documentation requirements, 22 acceptance criteria across the three challenges, mandatory-condition traceability, an explicit out-of-scope list, the full decision log, and 3 open items.

**Open items recorded as non-blocking**: OPEN-1 (generated code vs test skeletons for teammates, needed at Units Generation), OPEN-2 (member names, needed at Units Generation), OPEN-3 (whether the usage view and expiry sweep are separate deployables, a design question).

**Status**: Requirements Analysis awaiting approval.

---

## Requirements Analysis: APPROVED
**Timestamp**: 2026-10-11
**User Input**: "ok I am ok with the requirements we can move to the next flow ?"
**AI Action**: Marked Requirements Analysis approved. Advancing to Workflow Planning.

---

## Workflow Planning: Stage Started
**Timestamp**: 2026-10-11
**Phase / Stage**: INCEPTION / Workflow Planning
**AI Action**: Produced the construction plan artifact `aidlc-docs/inception/workflow-plan.md`: the ordered design stages per unit, the unit build order, and the toolchain install checkpoint before Code Generation.
**Status**: Awaiting approval.

---

## Shared-Repo Scrub of Delivery-Model Framing
**Timestamp**: 2026-10-11
**User Input**: "one thing dont mention anywhere that the other team members are not responsive and all becuase I will be comitting to this to the commion github repo which everyone will use  also you can proceed to the next stage"
**AI Action**: Removed every reference to team members being unresponsive or not delivering from all committed docs, since the repo is shared. Reframed delivery model F in neutral engineering terms: a self-contained core built first, with substantive work layered on top. Files edited: `requirements.md` (section 2 note, section 11 model and DR-1/2/3/5/9, allocation table, section 15), `aidlc-state.md`, `audit.md` (this file, including paraphrasing two user inputs and the risk notes), and `requirement-verification-questions-followup.md` (replaced the verbose worksheet with a concise neutral decision record). A final grep across `aidlc-docs` confirmed no sensitive framing remains. A Kiro session snapshot of the old follow-up file exists outside the workspace and is not part of the repo.

---

## Workflow Planning: Artifact Produced
**Timestamp**: 2026-10-11
**Phase / Stage**: INCEPTION / Workflow Planning
**AI Action**: Wrote `aidlc-docs/inception/workflow-plan.md`: the remaining stage route (Application Design, Units Generation, then per-unit Construction design stages, then Build and Test), the Construction build order (quota reference service first, then orchestrator/idempotency, billing, minimal notification and provider mock to complete the self-contained core, then the layered units), the toolchain checkpoint before the first Code Generation, the Classic ceremony settings, and the carried-forward open items.
**Status**: Awaiting approval.

---

## Workflow Planning: APPROVED
**Timestamp**: 2026-10-11
**User Input**: "ok we can proceed"
**AI Action**: Marked Workflow Planning approved. Advancing to Application Design.

---

## Application Design: Artifact Produced
**Timestamp**: 2026-10-11
**Phase / Stage**: INCEPTION / Application Design
**AI Action**: Wrote `aidlc-docs/inception/design/application-design.md`: service decomposition with rationale and rejected alternatives, a component diagram, the data-ownership table, per-service data models, per-service API tables, the RFC 9457 error model, the concurrency contract (atomic conditional update), the idempotency contract, the saga contract with happy-path and forced-failure sequence diagrams, cross-cutting conventions, the ports/databases table, business-rule placement, and the frozen-contract declaration.

**OPEN-3 resolved**: The usage aggregator and the expiry worker are separate, data-less deployables rather than being folded into the orchestrator. Rationale: the aggregator owns no data (so it cannot break the no-shared-database rule) and the expiry worker is stateless (safety enforced by the orchestrator's idempotent terminate path and BR7). This keeps both as clean independent units out of the core owner's files. Alternative (fold both into the orchestrator) recorded and rejected.

**Service list fixed**: 4 data-owning business services (Quota, Resource/Orchestrator, Billing, Notification) satisfy Condition A; Provider mock satisfies Condition E; Usage aggregator and Expiry worker are additional data-less deployables. Ports 8081-8087.

**Key design points**: idempotency records owned by the orchestrator (not billing) because the unsafe operation is the provisioning endpoint; quota release-exactly-once via a ledger table unique on resource_id (BR7); quota uses no version column since concurrency is the atomic conditional update, not optimistic locking; saga is orchestrated with quota reserved synchronously before the 202 and steps 2-4 asynchronous.

**Open items remaining**: OPEN-1 (unit starting point) and OPEN-2 (member names), both for Units Generation.
**Status**: Application Design awaiting approval.

---


## Decision: Simplify Saga Design (Remove Saga State Tables)
**Date**: 2026-10-12  
**Stage**: Application Design (revision after clarification)  
**Context**: The initial Application Design included separate `saga_state` and `saga_step` tables with full orchestration state machine (RUNNING, COMPENSATING, COMPENSATED) and per-step audit trail. User questioned whether this complexity was necessary for the assignment.

**Question**: Is the heavy saga design with separate state tables required, or is a simpler design sufficient?

**Analysis**:
- **Assignment requirement (Challenge 3C)**: "Saga state transitions in database, compensation execution, final consistent state"
- **AC-3.1 (initial)**: "Saga state transitions are persisted and queryable, not merely logged"
- The assignment does NOT explicitly require separate saga coordination tables
- The saga outcome can be tracked through `resources.status` (REQUESTED → PROVISIONING → ACTIVE or FAILED)
- Compensation logic can be implemented as try-catch exception handling rather than a state machine

**Options**:

| Option | Tables | Complexity | Satisfies AC-3.1? | Implementation |
|--------|--------|------------|-------------------|----------------|
| **A. Heavy (original)** | `saga_state`, `saga_step`, `resources`, `reservations` | High | ✅ Full step-level audit | State machine with explicit COMPENSATING status |
| **B. Simple (revised)** | `resources`, `reservations` only | Low | ✅ Resource-level audit | Try-catch with reverse calls on exception |
| **C. Hybrid** | `saga_step` log only, no `saga_state` | Medium | ✅ Step audit, simpler coordination | Append-only log with try-catch coordination |

**Decision**: **Option B (Simple)**

**Rationale**:
1. **Assignment compliance**: The simpler design fully satisfies Challenge 3C requirements — saga with compensation, queryable state, consistent outcome
2. **AC-3.1 satisfied**: `resources.status` transitions are persisted and queryable (not just logged)
3. **Evidence sufficiency**: Can show before/after database state, compensation logs, quota refunded, no orphaned resources
4. **Reduced implementation burden**: Removes ~40% of saga-related code while maintaining all functional requirements
5. **Audit capability**: The `resources` table shows the outcome; detailed step-by-step progress is visible in application logs with correlation IDs (NFR-13)

**Trade-offs**:
- ✅ **Simpler**: Fewer tables, less code, easier to test
- ✅ **Still correct**: Compensation works, state is queryable, idempotent
- ⚠️ **Less granular audit**: Cannot query "show all sagas that failed at billing step" (can query "show all failed resources")
- ⚠️ **Step-level timing lost**: Can see start/end times for the whole saga, not per-step (unless parsing logs)

**Impact**:
- `resource_db` schema: Remove `saga_state` and `saga_step` tables
- Keep: `resources`, `reservations`, `idempotency_records`
- Add: `resources.failure_reason` field to store compensation reason
- Section 8 (Saga Contract) rewritten to show try-catch style compensation
- AC-3.1 updated to reference `resources.status` instead of saga tables
- AC-3.8 updated to reference resources table evidence instead of saga_state table

**Artifacts updated**:
- `inception/design/application-design.md` — Section 4.2 (data model) and Section 8 (saga contract)
- `inception/requirements/requirements.md` — Challenge 3C state explanation and AC-3.1, AC-3.8

**Conclusion**: The simpler saga design is sufficient for the assignment, reduces implementation complexity significantly, and maintains all required evidence and functional correctness.


## Decision: Units Generation - Starting Point and Owner Assignments
**Date**: 2026-10-13  
**Stage**: Units Generation  
**Context**: Defined 4 units (7 services) with owner assignments. Resolved OPEN-1 (starting point) and OPEN-2 (member names).

**OPEN-1 Resolution: Starting Point for Code Generation**

**Question**: Should team members receive full generated implementations or test skeletons?

**Options**:
- A: Full generated implementation (working code, tests, everything)
- B: Test skeletons + interface stubs + briefs (owners implement logic)
- C: Hybrid - minimal working implementation + enhancement tasks

**Decision**: **Option B (Test Skeletons for All)**

**Rationale**:
1. **Deeper learning**: Owners write the business logic themselves, better understanding
2. **Flexibility**: If team members don't deliver, Prajwal can implement from same skeletons
3. **Consistency**: All skeletons follow identical structure
4. **Academic integrity**: Team members can explain code they wrote (demo requirement)

**Skeleton contents**:
- ✅ Project structure (Spring Boot, Maven wrapper)
- ✅ Database schema (Flyway migrations)
- ✅ JPA entities with annotations
- ✅ Repository interfaces (Spring Data)
- ✅ Controller method signatures (empty implementations)
- ✅ Service layer stubs (method signatures with TODOs)
- ✅ Test class structure (method names, no assertions)
- ✅ Configuration templates
- ✅ README with build/run instructions
- ❌ No business logic implementation
- ❌ No test assertions
- ❌ No error handling details

**OPEN-2 Resolution: Team Member Names**

**Question**: Use generic names, actual first names, or initials for contribution table?

**Decision**: **Actual First Names (Option 2)**

**Team roster**:
- **Prajwal**: Core owner, builds Quota + Resource/Orchestrator (U1)
- **Karan**: Builds Billing service (U2)
- **Vinayak**: Builds Provider mock service (U3)
- **Siddharth**: Builds Notification + Aggregator + Worker (U4)

**Unit Distribution Decision: Core Owner Scope**

**Question**: Should core owner (Prajwal) build Billing service or leave it to team?

**Options**:
- Choice 1: Prajwal builds Quota + Orchestrator + Billing (self-contained core)
- Choice 2: Prajwal builds Quota + Orchestrator only, team builds Billing

**Decision**: **Choice 2 (Billing by team, Prajwal uses WireMock)**

**Rationale**:
1. **Flexibility**: If Karan doesn't deliver, Prajwal can implement from skeleton later
2. **Work distribution**: More balanced across team
3. **Challenge 3C feasible**: WireMock stub for Billing is acceptable for saga evidence (forced 503 response demonstrates compensation logic)
4. **Fallback safety**: Prajwal has same skeleton and can take over if needed

**WireMock Strategy**:
- Prajwal delivers Challenge 1, 2, 3C evidence using WireMock stubs for Provider, Billing, Notification
- If team delivers, replace WireMock with real services for integration testing
- If team doesn't deliver, Prajwal's evidence is still valid (demonstrates saga understanding)

**Dependencies**:
- Challenge 1 (Concurrency): No dependencies, Quota service standalone
- Challenge 2 (Idempotency): Requires Quota service (real) + optional WireMock for saga
- Challenge 3C (Saga): Requires Quota (real) + WireMock stubs for Provider, Billing, Notification

**Build Order**:
1. **Phase 1 (Week 1-2)**: Prajwal builds U1 (core) with WireMock stubs, delivers all challenge evidence
2. **Phase 2 (Week 2-3)**: Team builds U2, U3, U4a in parallel (Billing, Provider, Notification)
3. **Phase 3 (Week 3)**: Integration testing, replace mocks with real services
4. **Phase 4 (Week 3)**: Siddharth builds U4b (Aggregator + Worker) after integration

**Estimated effort**:
- U1 (Prajwal): ~1500 LOC, Challenge 1+2+3C implementation, WireMock config
- U2 (Karan): ~400 LOC, Billing service from skeleton
- U3 (Vinayak): ~300 LOC, Provider mock from skeleton
- U4 (Siddharth): ~500 LOC, 3 services from skeletons

**Impact**: Units artifact created at `aidlc-docs/inception/units.md` with full unit definitions, owner assignments, dependency DAG, and acceptance criteria per unit.


## Gate: Units Generation APPROVED — Inception Phase Closed
**Date**: 2026-10-13
**Approved by**: user (Prajwal)

Units Generation approved as written. With this, every Inception stage is closed:

| Stage | Outcome |
|-------|---------|
| Workspace Detection | greenfield |
| Reverse Engineering | skipped (greenfield) |
| Requirements Analysis | APPROVED (50 FR, 28 NFR, 8 BR, 22 AC) |
| User Stories | skipped (Q22=A) |
| Workflow Planning | APPROVED |
| Application Design | APPROVED (7 services, simplified saga) |
| Units Generation | APPROVED (4 units, 4 owners, build DAG) |

All three open items are resolved: OPEN-1 (test skeletons for every unit), OPEN-2 (Prajwal,
Karan, Vinayak, Siddharth), OPEN-3 (aggregator and expiry worker are separate data-less
deployables).

Frozen from this point: service boundaries, per-service API tables, the RFC 9457 error model,
the saga contract, unit-to-owner assignments, and the build order. A change to any of these
after this gate is a new, logged decision.

**Next gate**: toolchain install (JDK 21, PostgreSQL 16/17 with five databases and users,
Docker Desktop, PowerShell 7, Git). This blocks Code Generation only; the four design stages of
Construction need no toolchain.


## Gate: U1 Construction Design Set APPROVED
**Date**: 2026-10-14
**Approved by**: user (Prajwal)
**Artifacts**: `aidlc-docs/construction/u1-core/{functional-design,nfr-requirements,nfr-design,infrastructure-design}.md`

Four design stages for unit U1 (Quota :8081 + Resource/Orchestrator :8082) authored together and
approved under a single unit gate, per `workflow-plan.md` §4 ("one approval gate per Construction
unit"). Converged after three independent review passes closing 19 findings; final verdict
APPROVED with full agreement checks passing — 11 endpoints match `application-design.md`
§5.1/§5.2 path-for-path, all six tables match §4.1/§4.2 column-for-column, the error-code
vocabulary is closed and single-sourced, BR1–BR8 all trace to class/method/test/evidence, and
NFR-1 to NFR-28 are each classified with a reason.

**Frozen decisions verified intact**: single atomic conditional UPDATE with no version column and
no retry; mandatory `Idempotency-Key` with the PK-insert as concurrency guard; no saga
coordination tables; five-state linear lifecycle; synchronous quota reserve then 202 then async
saga; persisted `X-Simulate-Failure` replayed across the async boundary; RFC 9457 error model;
circuit breaker explicitly dropped.

### Consequential assumptions reviewed at the gate

| ID | Reading accepted | Why it matters |
|----|------------------|----------------|
| **A11** | Compensation clears `provider_instance_id`; the "how far did the saga get" signal moves to `failure_reason` prefixes plus the WireMock journal | Deviates from frozen `application-design.md` §8.4. Changes the shape of the Challenge 3C evidence: AC-3.5 "no orphaned data" now reads as a null check |
| **A1** | Five lifecycle states; `STOPPING`/`STOPPED` dropped | `requirements.md` §3.11 / FR-47 list them, `application-design.md` §4.2 does not. Followed the later frozen document. FR-48 (no stop/start operation) holds either way |
| **A10** | BR5 reassigned to U2 (Karan), marked out of scope for U1 rather than dropped | `requirements.md` §11 DR-1 put Billing and BR1–BR7 on the core owner; `units.md` §2 scopes U1 to two services. Followed `units.md`. The `units.md` §5 fallback keeps Billing as Prajwal's contingency |
| **A6** | No crash-recovery scanner. A process death mid-saga leaves `status = 'PROVISIONING'` with quota held, resolved manually | The direct, accepted cost of removing the saga tables. Documented as a limitation for the report rather than hidden |
| **A5** | Failure injection uses `<peer>:<mode>` grammar; Challenge 3C sends `billing:always-fail`; bare `true` is ignored | `units.md` §3's shorthand `true` cannot name which peer should fail. Affects the challenge scripts only |
| **A3** | Business rejections (404/422) are stored against the idempotency key and replayed; infrastructure failures (503/timeout) roll the record back so the key is reusable | Means a quota rejection is permanently bound to that key |

The remaining 23 assumptions (A2, A4, A7–A9, A12, A13, D1–D6, I1–I6, N1–N4) were accepted as
reasonable defaults without change. Notable among them: the PG15+ Flyway grant correction (D3/I1)
lives in the Construction documents and does **not** edit the approved, frozen `units.md` §9.

### Two residues fixed inline at approval

1. `functional-design.md` §6.1 attributed clearing `provider_instance_id` to the `SAGA_FAILED`
   transition, while §9.5 clears it in compensation step (2). The transition-table row now states
   that compensation has already cleared the column and that the transition does not touch it.
2. `FailureReason.of(cause)` was unspecified beyond two examples, leaving the A11 evidence prefix
   undefined for the non-peer path that §6.3 can reach. §9.5 now fixes the mapping as a four-row
   table (`PROVIDER_UNAVAILABLE`, `BILLING_UNAVAILABLE`, `INVALID_STATE_TRANSITION`,
   `INTERNAL_ERROR`), states that only the first two carry the "how far" signal, and names the
   compensation-start log line and the WireMock journal as the evidence for the other two. Both
   added codes already existed in the `nfr-design.md` §2.2 vocabulary.

**Next gate**: toolchain install (JDK 21, PostgreSQL 16/17, Docker Desktop, PowerShell 7; Git
already present). This blocks Code Generation for U1 and nothing before it.
