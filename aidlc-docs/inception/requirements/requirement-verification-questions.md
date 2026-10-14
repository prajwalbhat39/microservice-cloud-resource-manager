# Requirements Verification Questions

**Stage**: INCEPTION / Requirements Analysis (Comprehensive depth)
**Source**: `Cloud_Resource_Manager_Requirements_Only.md`

## How to answer

- Write a letter after each `[Answer]:` tag, for example `[Answer]: A`. Notes after the letter are welcome.
- "(Recommended)" marks my suggestion based on the requirements and the marking scheme. Choosing something else is fine.
- For `X) Other`, describe what you want after the tag.
- Tell me in chat when you're done. I'll check the answers for gaps or contradictions, ask follow-ups if needed, then write `requirements.md` for your approval.

Service boundaries, saga placement, data models, and endpoint lists are not asked here. Application Design proposes them with alternatives for you to apsprove.

---

# Part 1: Team and deliverables

## Question 1: Team size
How many people are on the team?

A) 1
B) 2
C) 3
D) 4
X) Other (please describe after [Answer]: tag below)

Optionally add member names after the letter. They feed the contribution table and service ownership.

[Answer]: 4

## Question 2: Work split and commits
Individual marks come from Git commit history, the contribution table, and the demo video, and every member must be able to explain the code they present. How should work map to people? (Skip if you're working alone.)

A) Each member owns one or more services. Units of work map to owners, and each owner reviews, understands, and commits their service from their own Git account (Recommended)
B) One person commits everything; contributions are recorded in the contribution table only
C) Decide during Units Generation
X) Other (please describe after [Answer]: tag below)

[Answer]: 

## Question 3: Report and demo video support
The flow always produces service code, tests, end-to-end and challenge scripts, OpenAPI files, a README, and evidence-capture scripts. How much help do you want with the report and video?

The assignment permits AI "for learning and boilerplate code generation" and requires an AI-use declaration. Check how much AI-drafted report text your instructor accepts.

A) Full Markdown draft of all 10 report sections plus diagrams, which the team rewrites and owns
B) Report skeleton: headings, tables (services, endpoints, data ownership, business-rule traceability), Mermaid diagrams, and bullet notes; the team writes the prose
C) B plus a demo-video run sheet: segment timings, who presents what, and which commands to run (Recommended)
D) None: code, tests, scripts, README, and OpenAPI only
X) Other (please describe after [Answer]: tag below)

[Answer]: A

---

# Part 2: Business scope

## Question 4: Engineering Challenge 3
Which option is the graded Challenge 3? The brief already requires compensation when provisioning fails, which covers most of 3C.

A) 3C Saga with Compensation: a 4-step saga (reserve quota, provision, start billing, notify) with a forced failure at step 3 (billing); compensation undoes provisioning, then quota, in reverse order. Basic timeouts and retries on the provider call are included but not evidenced as 3A (Recommended)
B) 3A Resilience Patterns: timeouts, retry with exponential backoff and jitter, and a circuit breaker. Compensation is still built but isn't the graded challenge
C) Both 3A and 3C, each with full evidence (more work; only one is required)
D) 3B Time-bound holds: quota is held temporarily and released automatically after about 30 seconds unless confirmed
E) 3D Event-driven flow with a message broker (adds RabbitMQ or Kafka, is an exception to the REST-only rule, and must be containerized in Assignment 2)
X) Other (please describe after [Answer]: tag below)

[Answer]: C

## Question 5: Tenant management
How much tenant management is in scope?

A) Create a tenant (name and tier) and read it; the tier is fixed after creation; a seed script creates demo tenants (Recommended)
B) Seed data only, with read-only tenant endpoints
C) A, plus tier upgrade and downgrade, with a rule that a downgrade can't set limits below current usage
X) Other (please describe after [Answer]: tag below)

[Answer]: A

## Question 6: Quota limits
Which limits are enforced per tier?

A) The brief's example values, with the instance limit counting every resource type: FREE 2 CPU / 4 GB / 1 instance, STANDARD 20 CPU / 64 GB / 10 instances, ENTERPRISE 200 CPU / 512 GB / 100 instances (Recommended)
B) The same values, but the instance limit counts VMs only; containers and clusters consume CPU and RAM only
C) CPU and RAM only, with no instance-count limit
X) Other (please describe after [Answer]: tag below)

[Answer]: A

## Question 7: Business rules
The assignment needs at least 4 rules, each traced to code and a unit test. Which set?

A) The brief's six: BR1 quota usage never exceeds tier limits; BR2 provisioning is idempotent; BR3 reusing a key with a different payload is rejected; BR4 only valid lifecycle transitions (ACTIVE only from PROVISIONING); BR5 billing stops when a resource is terminated; BR6 compensation refunds quota when provisioning fails
B) The six above plus BR7 a resource's quota is released exactly once (a repeated terminate or expiry never refunds twice) and BR8 FREE-tier tenants can't provision clusters (Recommended)
C) A minimal four: BR1, BR2 and BR3 combined, BR4, BR6
X) Other (please describe after [Answer]: tag below)

[Answer]: B

## Question 8: Meaning of STOPPING and STOPPED
The brief lists REQUESTED → PROVISIONING → ACTIVE → STOPPING → STOPPED → TERMINATED but only describes a terminate operation. What do the stop states mean?

A) Linear, as written: STOPPING and STOPPED are steps of termination (provider shutdown in progress, then confirmed). No separate stop or start API (Recommended)
B) Stop and start are user operations: STOPPED pauses billing but keeps quota reserved; start resumes billing; terminate is separate
C) Stop and start are user operations, and STOPPED also releases quota; start must reserve quota again and can be rejected
X) Other (please describe after [Answer]: tag below)

[Answer]: A

## Question 9: Expiry
The brief allows an optional expiry time that auto-terminates a resource. How should it work?

A) Duration is optional in the request. A scheduled job with a configurable interval terminates expired ACTIVE resources through the normal terminate flow and is safe to run repeatedly (Recommended)
B) Duration is required on every request; same job as A
C) Store the expiry time only; auto-termination is future work
X) Other (please describe after [Answer]: tag below)

[Answer]: A

## Question 10: Cost calculation
The brief's example is 4 CPUs × $0.12 = $0.48 per hour. How is cost calculated?

A) $0.12 per CPU-hour for every resource type, prorated per second of ACTIVE time, so cost shows up even in a short demo (Recommended)
B) $0.12 per CPU-hour, rounded up to whole minutes
C) $0.12 per CPU-hour, charged per started hour (1-hour minimum)
D) CPU plus RAM pricing (for example, an extra rate per GB-hour)
X) Other (please describe after [Answer]: tag below)

[Answer]: A

## Question 11: Usage and billing view (Operation 3)
This view combines data owned by three services: quota, resources, and billing. How should it be served?

A) One endpoint returns quota usage, active resources, and month-to-date cost, composed through REST calls. If a service is down, it returns what it has and marks the rest unavailable. Monthly invoice summaries are computed on request (Recommended)
B) Separate endpoints per service, combined by the client or script. Monthly invoice summaries are computed on request
C) A, plus monthly invoices stored by an explicit, idempotent "close month" operation
X) Other (please describe after [Answer]: tag below)

[Answer]: A

## Question 12: Notifications
Which events notify the tenant, and can a failed notification undo provisioning?

A) Notify on ACTIVE, FAILED, and TERMINATED (including expiry). Best effort: a failed notification is logged and retried but never rolls back a resource (Recommended)
B) The same events, but notification is a mandatory saga step whose failure triggers compensation
C) A, plus quota alerts (for example, at 80% usage)
X) Other (please describe after [Answer]: tag below)

[Answer]: A

---

# Part 3: API behaviour

## Question 13: Provisioning request style
The provider mock takes 1 to 3 seconds and fails 20% of the time, and the guidelines warn against long synchronous chains. How should the provisioning request behave?

A) Synchronous: the request waits for the whole saga and returns the final result. Simplest, but a call can take several seconds with retries
B) Quota reserved synchronously, the rest in the background: the request returns 202 Accepted with a reservation ID, or 422 straight away if quota is short. Provisioning, billing, and notification continue in the background, and the client polls `GET /api/v1/reservations/{id}`. The 20-request quota test gets an immediate accept or reject per request (Recommended)
C) Fully asynchronous: every request returns 202, including ones that later fail on quota
X) Other (please describe after [Answer]: tag below)

[Answer]: B

## Question 14: Idempotency rules
Rules for the provisioning request. Whatever you choose, concurrent duplicates are stopped by a database unique constraint, as the assignment requires.

A) `Idempotency-Key` header is mandatory (missing returns 400). Same key and same payload replays the original response; same key with a different payload returns 422. Keys are kept for 24 hours (Recommended)
B) The same as A, but a different payload returns 409 Conflict
C) The header is optional; requests without it get no duplicate protection
X) Other (please describe after [Answer]: tag below)

[Answer]: A

---

# Part 4: Technical context

## Question 15: Concurrency control for quota
The brief suggests optimistic locking. Without retries, optimistic locking can let fewer than N of 20 parallel requests succeed, because conflicting requests fail even when capacity remains, which breaks "exactly N succeed". A plain check-then-update isn't allowed. Which mechanism?

A) Atomic conditional update: one SQL statement adds the usage only if every limit still holds. No retries needed, and exactly N succeed (Recommended)
B) Optimistic locking (version column) with bounded retries on conflict, as the brief suggests
C) Pessimistic row lock (`SELECT ... FOR UPDATE`) inside a transaction
X) Other (please describe after [Answer]: tag below)

[Answer]: A


## Question 16: Failure simulation
Random 20% failures make the 20-request quota test and the forced step-3 failure unpredictable. How should failures be controlled?

A) Environment variables set the failure rate and delay range. A runtime admin endpoint on the provider mock, and a failure switch on billing that's off by default, change the mode between RANDOM, ALWAYS_SUCCEED, ALWAYS_FAIL, and SLOW for scripted demos (Recommended)
B) Environment variables only; restart the service to change behavior
C) A request header (for example `X-Simulate-Failure`) passed through the saga to force outcomes per request
X) Other (please describe after [Answer]: tag below)

[Answer]: C

## Question 17: Java and Spring Boot
Which Java and Spring Boot versions?

A) Java 21 (LTS) with Spring Boot 3.5.x, the latest 3.x line, matching the assignment's "Spring Boot 3.x" (Recommended)
B) Java 17 with Spring Boot 3.x
C) Java 25 (LTS) with Spring Boot 4.x: newer, outside the literal "3.x", but covered by "any modern framework"
X) Other (please describe after [Answer]: tag below)

[Answer]: A

## Question 18: Database
Each service needs its own database with its own credentials. Which database setup?

A) PostgreSQL: one server (Docker or a native install) with a separate database and user per service (Recommended)
B) PostgreSQL: one container per service
C) MySQL, with the same per-service database and user layout as A
D) H2 embedded per service: nothing to install, but weaker evidence for locks and constraints, and not representative for Assignment 2
X) Other (please describe after [Answer]: tag below)

[Answer]: A

## Question 19: Test depth
Required: one unit test per business rule and one scripted end-to-end test of the main transaction. How far beyond that?

A) The required tests plus integration tests against real PostgreSQL using Testcontainers, which needs Docker when the tests run (Recommended)
B) The required tests only
C) A, plus property-based tests (jqwik) for quota arithmetic and lifecycle transitions
X) Other (please describe after [Answer]: tag below)

[Answer]: X-how much ever tests mentioned in assignment

## Question 20: Script language for end-to-end and challenge tests
These scripts fire 20 parallel requests, print success and failure counts, and capture evidence. Which language?

A) Python 3 with `requests` and a thread pool: cross-platform, easy parallelism, readable summaries (Recommended)
B) Bash with curl and jq: classic, but needs Git Bash or WSL on Windows
C) PowerShell 7: native on Windows and runs elsewhere through `pwsh`
D) Postman/Newman collections, plus a separate script for the parallel burst
X) Other (please describe after [Answer]: tag below)

[Answer]: C

## Question 21: When to install the toolchain
Inception and the Construction design stages only produce documents, so nothing needs installing yet. The cost of waiting until after Construction: Code Generation can't compile or run tests, so mistakes only surface at Build and Test. When will you install?

A) Install a JDK before Code Generation so each service is compiled and unit-tested as it's generated. Each service ships the Maven Wrapper, so Maven itself isn't needed. Install Docker and PostgreSQL before Build and Test (Recommended)
B) Install everything after Construction; all compiling and testing happens at Build and Test
C) Install everything before Construction starts
X) Other (please describe after [Answer]: tag below)

[Answer]: C (answered in chat: the user will install the full tech stack before Construction) 

---

# Part 5: AI-DLC process

## Question 22: User Stories stage
Should the User Stories stage run?

A) Skip it: one persona and an API-only system, so the requirements and business rules already describe the behavior (Recommended)
B) Run it: personas plus user stories with acceptance criteria
X) Other (please describe after [Answer]: tag below)

[Answer]: A

## Question 23: Security baseline extension
AI-DLC can apply a security baseline as blocking rules during design and code generation. Authentication stays out of scope either way. Enable it?

A) Yes, a lightweight baseline: validate all input, credentials only through environment variables, a least-privilege database user per service, no secrets or full payloads in logs, and pinned dependency versions (Recommended)
B) No: only what the assignment requires
X) Other (please describe after [Answer]: tag below)

[Answer]: B

## Question 24: Working assumptions
Confirm or correct these defaults.

1. The provider mock is its own runnable service (folder, pom.xml, database) but doesn't count toward the 4-service minimum. There will be at least 4 business services.
2. The saga is orchestrated: one service owns the saga state and drives each step, because services talk only over REST. Where it lives is decided in Application Design.
3. A FAILED terminal state is added for provisioning that fails and gets compensated. TERMINATED remains the normal end of life.
4. Terminate is idempotent by design: repeating it returns the already-terminated resource, and billing stop and quota release happen once.
5. Every provisioning call to the provider mock carries a client token, so retries never create a second instance.
6. Resource types are VM, CONTAINER, and CLUSTER, sharing one lifecycle and one pricing model.
7. Billing is metering only. No payment is collected and no payment gateway is involved.
8. `X-Correlation-Id` is created by the first service if missing, sent on every downstream call, and included in every log line. Logs are structured JSON on stdout.
9. All services share one error format: RFC 9457 Problem Details plus `code`, `correlationId`, and `timestamp` fields.
10. Business endpoints live under `/api/v1/...`, every service exposes `GET /health`, and OpenAPI is generated from code (springdoc) with an exported `openapi.yaml` committed per service.
11. Each service is an independent Maven project (its own pom.xml and Maven Wrapper) with separate API, domain, and persistence layers and Flyway migrations. There's no shared code library; a root pom only builds everything with one command.
12. All configuration comes from environment variables with local defaults. Times are UTC and money is decimal USD.
13. No authentication. The tenant ID is passed explicitly in requests.

A) All assumptions hold
B) Some need changing (list the numbers and the changes after [Answer]: tag below)

[Answer]: A
