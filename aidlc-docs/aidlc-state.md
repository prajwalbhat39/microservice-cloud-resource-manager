# AI-DLC State Tracking

## Project Information
- **Project**: Cloud Resource Manager Platform (SEZG583 Scalable Services, Assignment 1)
- **Project Type**: Greenfield
- **Workflow Profile**: Classic (v1-style: Inception, Construction, Build and Test; one approval gate per stage)
- **Started**: 2026-10-11
- **Source Requirements**: `Cloud_Resource_Manager_Requirements_Only.md`

## Workspace State
- **Existing Code**: None (README.md with a title only, plus the requirements document)
- **Reverse Engineering Needed**: No
- **Workspace Root**: `c:\Users\USER\OneDrive\Documents\microservice-cloud-resource-manager`

## Code Location Rules
- **Application code**: workspace root, one folder per service. Never inside `aidlc-docs/`.
- **Documentation**: `aidlc-docs/` only.

## Environment
- JDK, Maven, Docker, and PostgreSQL are not confirmed as installed.
- Decision (Q21 = C): the user installs the full tech stack before Construction starts. Inception needs no toolchain.
- Action at the end of Inception: give the user an install checklist with versions, based on the answers to Q17–Q20.

## Stage Progress

### 🔵 INCEPTION PHASE
- [x] Workspace Detection: greenfield
- [x] Reverse Engineering: SKIPPED (greenfield)
- [x] Requirements Analysis: APPROVED
- [x] User Stories: SKIPPED (Q22=A)
- [x] Workflow Planning: APPROVED
- [x] Application Design: APPROVED (2026-10-13)
- [x] Units Generation: APPROVED (2026-10-13)

**INCEPTION PHASE COMPLETE.** All artifacts approved. Next gate: toolchain install.

### 🟢 CONSTRUCTION PHASE

**Unit U1 — Quota + Resource/Orchestrator (Prajwal)** — design stages APPROVED
- [x] Functional Design: APPROVED (2026-10-14)
- [x] NFR Requirements: APPROVED (2026-10-14)
- [x] NFR Design: APPROVED (2026-10-14)
- [x] Infrastructure Design: APPROVED (2026-10-14)
- [ ] **GATE: toolchain install** (JDK 21, PostgreSQL 16/17, Docker, PowerShell 7) — Git already present
- [ ] Code Generation

U1 design set converged after 3 review passes (19 findings closed). 29 assumptions recorded and
accepted; the six consequential ones (A1, A3, A5, A6, A10, A11) were reviewed by the user at the
gate. Two documentation residues fixed inline at approval: the §6.1-vs-§9.5 attribution of
clearing `provider_instance_id`, and the previously unspecified `FailureReason.of` prefix
mapping (now a four-row table in §9.5 covering the non-peer failure path).

**Unit U2 — Billing (Karan)** — not started
**Unit U3 — Provider mock (Vinayak)** — not started
**Unit U4 — Notification + Aggregator + Worker (Siddharth)** — not started

After all units:
- [ ] Build and Test

### 🟡 OPERATIONS PHASE
- [ ] Placeholder: Docker and Minikube deployment is Assignment 2

## Decisions Recorded (Requirements Analysis, both rounds)
- Team of 4. Delivery model F: a self-contained core (the challenge-bearing services plus minimal versions of the rest) is built first, so every mandatory condition rests on the core; substantive per-service work is layered on top.
- Challenge 3: 3C only is graded. 3A is a resilience baseline (timeouts, retry with backoff) with no separate evidence set. The circuit breaker is dropped.
- Lifecycle is linear; no stop/start API. A FAILED terminal state is added.
- 8 business rules (BR1-BR8).
- Provisioning: quota checked synchronously, then 202 Accepted with a reservation ID; the rest of the saga runs in the background and the client polls.
- Concurrency: atomic conditional update (single SQL statement, no retries).
- Idempotency: `Idempotency-Key` mandatory; 422 on a payload mismatch; 24-hour retention.
- Failure injection: environment-variable defaults (20% failure, 1-3s delay) with an `X-Simulate-Failure` per-request override. No admin endpoints. The header is persisted with the reservation and replayed across the async boundary.
- Tests: one per business rule, plus Testcontainers-backed tests for BR1, BR3, BR7 (SQL-enforced). One scripted E2E. Docker is therefore required at test time.
- Stack: Java 21, Spring Boot 3.5.x, PostgreSQL (one server, database and user per service), Flyway, springdoc, WireMock, Testcontainers, JUnit 5.
- Scripts: PowerShell 7.
- User Stories stage: SKIP (Q22=A).
- Security baseline extension: not enabled (Q23=B). Overlapping items retained as NFR-2, NFR-17, NFR-24.
- Report support: full Markdown draft of all 10 sections plus diagrams (Q3=A).

## Open Items (non-blocking)
- ~~OPEN-1: whether teammates receive generated implementations or test skeletons plus a brief.~~ RESOLVED: test skeletons for all (Option B).
- ~~OPEN-2: member names for the contribution table and owner map.~~ RESOLVED: Prajwal, Karan, Vinayak, Siddharth.
- ~~OPEN-3: whether the usage view and expiry sweep are separate deployables.~~ RESOLVED: separate deployables (aggregator :8086, worker :8087).

## Current Status
- **Phase**: CONSTRUCTION (unit U1)
- **Stage**: U1 design stages ALL APPROVED. Blocked on the toolchain gate before Code Generation.
- **Output dir**: `aidlc-docs/construction/u1-core/` (functional-design, nfr-requirements, nfr-design, infrastructure-design, plus review.md / review.json)
- **Toolchain**: NOT yet installed. Git present; JDK 21, PostgreSQL, Docker, PowerShell 7 outstanding. This is the only thing blocking Code Generation.
- **Next**: install toolchain, then Code Generation for U1 (emits skeletons: project structure, Flyway baselines, entities, repositories, controller signatures, test stubs — Prajwal writes the business logic)
- **Artifacts (all approved)**:
  - `inception/requirements/requirements.md`
  - `inception/workflow-plan.md`
  - `inception/design/application-design.md`
  - `inception/units.md`
- **Waiting on**: user confirms JDK 21, PostgreSQL 16/17, Docker, PowerShell 7, Git installed
- **Next**: Construction for unit U1 (Prajwal): Functional Design → NFR Requirements → NFR Design → Infrastructure Design → Code Generation

## Team and Unit Assignments (frozen at Units Generation)
| Unit | Owner | Services | Ports |
|------|-------|----------|-------|
| U1 | Prajwal | Quota, Resource/Orchestrator (+ WireMock stubs, all 3 challenges) | 8081, 8082 |
| U2 | Karan | Billing | 8083 |
| U3 | Vinayak | Provider mock | 8085 |
| U4 | Siddharth | Notification, Usage aggregator, Expiry worker | 8084, 8086, 8087 |

Starting point for every unit: test skeletons (OPEN-1 = Option B).
