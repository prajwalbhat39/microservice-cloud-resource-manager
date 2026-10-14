# Requirements: Cloud Resource Manager Platform

**Stage**: INCEPTION / Requirements Analysis
**Depth**: Comprehensive
**Status**: Awaiting approval
**Source**: `Cloud_Resource_Manager_Requirements_Only.md`
**Course**: SEZG583 Scalable Services, Assignment 1 of 2 (13 marks)

---

## 1. Purpose and Scope

### 1.1 What the system does

A management layer over compute infrastructure. Organisations (tenants) reserve compute
resources on demand against a fixed quota allocated by subscription tier. The platform
enforces that quota, provisions resources through an unreliable infrastructure provider,
meters cost, notifies tenants of lifecycle events, and reports usage.

It is a simplified analogue of AWS or Azure resource management.

### 1.2 Why it exists as a distributed system

The domain was chosen because it naturally produces the three distributed-system problems
the assignment grades:

| Problem | Where it appears here |
|---------|----------------------|
| Concurrent access to a contended resource | Tenant quota, which several requests can exhaust simultaneously |
| An operation unsafe to repeat | Resource provisioning, which must not create two instances on retry |
| Partial failure across services | A provisioning flow spanning quota, provider, billing, and notification |

### 1.3 Primary persona

**DevOps engineer or IT manager** at a tenant organisation. Goals: provision compute on
demand, stay within quota, track cost, and be notified of resource lifecycle events.

There is no authentication (out of scope), so the tenant identity is supplied explicitly in
each request. In a production system this would come from an authenticated principal.

### 1.4 Interface

API only. No user interface. Interaction is through HTTP clients and the PowerShell scripts
delivered with the system.

---

## 2. Mandatory Condition Traceability

The assignment requires all five conditions. Each is satisfied by design, not incidentally.

| ID | Condition | How it is satisfied | Verified by |
|----|-----------|--------------------|-------------|
| A | At least 4 services with distinct business responsibilities, each with its own folder, dependency file, and database | 4 business services (quota, resource/orchestrator, billing, notification) plus a provider mock. Boundaries confirmed in Application Design | Repository layout, 5 separate databases with separate credentials |
| B | A business transaction spanning 3 or more services | Provisioning spans 4: quota reserve, provider provision, billing meter start, notification send | E2E script, correlation-ID trace across 4 service logs |
| C | A contended resource that can be over-allocated if unprotected | Tenant quota (CPU, RAM, instance count) | Challenge 1 script: 20 parallel requests, exactly N succeed |
| D | An operation unsafe to repeat | Resource provisioning. A repeat must not create a second instance or charge | Challenge 2 script: sequential and concurrent duplicates |
| E | A dependency that can be slow or fail, simulated by our own mock | Provider mock: 1-3s delay, 20% failure rate, configurable | Provider mock source, timeout and retry logs |

**Note on Condition A.** There are exactly 4 business services against a minimum of 4, so
there is no slack. Section 11 records how the delivery model keeps this minimum on a
self-contained core.

---

## 3. Functional Requirements

Each requirement carries an ID for traceability into design, code, and tests.

### 3.1 Tenant management

| ID | Requirement |
|----|-------------|
| FR-1 | Create a tenant with a name and a subscription tier of FREE, STANDARD, or ENTERPRISE. Returns 201 with the tenant ID |
| FR-2 | Read a tenant by ID, returning its name, tier, and quota limits. Returns 404 if absent |
| FR-3 | A tenant's tier is immutable after creation. No upgrade or downgrade operation exists |
| FR-4 | A seed script creates a documented set of demo tenants, one per tier, with fixed IDs so scripts and evidence are repeatable |

Rejected: tier upgrade and downgrade. It would add a validation rule (a downgrade must not
set limits below current usage) without exercising any graded concern.

### 3.2 Quota management

| ID | Requirement |
|----|-------------|
| FR-5 | Quota limits are fixed per tier: FREE 2 CPU / 4 GB / 1 instance; STANDARD 20 CPU / 64 GB / 10 instances; ENTERPRISE 200 CPU / 512 GB / 100 instances |
| FR-6 | The instance limit counts every resource type (VM, CONTAINER, CLUSTER), not VMs alone |
| FR-7 | Reserve quota for a request, succeeding only if every one of the three limits still holds after the addition. Partial reservation is not possible |
| FR-8 | Release previously reserved quota for a named resource |
| FR-9 | Report current usage and remaining headroom for a tenant across all three dimensions |
| FR-10 | Quota reservation is a single atomic database operation. A read-then-write sequence in application code is prohibited |

### 3.3 Resource provisioning

| ID | Requirement |
|----|-------------|
| FR-11 | Accept a provisioning request specifying tenant, resource type, CPU cores, RAM in GB, and an optional duration |
| FR-12 | An `Idempotency-Key` request header is mandatory. A request without one is rejected with 400 |
| FR-13 | Quota is reserved synchronously, before the response. If quota is insufficient the request is rejected immediately with 422 and no resource or reservation is created |
| FR-14 | On successful quota reservation, respond 202 Accepted with a reservation ID and a `Location` header pointing at the reservation status resource |
| FR-15 | Provisioning, billing meter start, and notification proceed asynchronously after the response |
| FR-16 | A reservation status endpoint reports PENDING, COMPLETED, or FAILED, and on completion carries the resource ID and instance ID, or on failure a reason |
| FR-17 | Read a resource by ID, returning its type, size, lifecycle state, provider instance ID, creation time, and expiry time if set |
| FR-18 | List a tenant's resources, filterable by lifecycle state |
| FR-19 | Every provisioning call to the provider carries a client token derived from the resource identity, so a retried call returns the existing instance rather than creating a second one |

**Rationale for the asynchronous boundary (FR-13 to FR-15).** The provider takes 1-3 seconds
and fails 20% of the time, and the assignment's own guidance warns against long synchronous
chains. Reserving quota synchronously keeps the Challenge 1 outcome deterministic: each of
the 20 parallel requests receives an immediate accept or reject governed only by quota, with
no interference from provider latency or random provider failure.

### 3.4 Resource termination

| ID | Requirement |
|----|-------------|
| FR-20 | Terminate a resource on request. The provider instance is destroyed, the billing meter stops, and quota is released |
| FR-21 | Termination is idempotent. Repeating it on an already-terminated resource returns the terminated resource and performs no further billing or quota effect |
| FR-22 | Termination of a resource not in a terminable state is rejected with 409 and the current state in the error body |

### 3.5 Expiry

| ID | Requirement |
|----|-------------|
| FR-23 | A provisioning request may carry an optional duration. When present, the resource records an expiry time |
| FR-24 | A scheduled sweep runs at a configurable interval and terminates ACTIVE resources whose expiry time has passed, through the same path as FR-20 |
| FR-25 | The sweep is safe to run repeatedly and concurrently with itself. It never acts twice on the same resource and never touches a resource that is not ACTIVE |

### 3.6 Billing

| ID | Requirement |
|----|-------------|
| FR-26 | A billing meter starts when a resource reaches ACTIVE, recording the start instant and the resource's CPU count |
| FR-27 | A billing meter stops when the resource is terminated, recording the stop instant |
| FR-28 | Cost is 0.12 USD per CPU-hour for every resource type, prorated per second of metered time |
| FR-29 | Report month-to-date cost for a tenant, summing closed meters and accruing open ones to the current instant |
| FR-30 | Meter start is idempotent. A repeated start for the same resource does not create a second meter |
| FR-31 | Monthly invoice summaries are computed on request. No stored invoice or month-close operation exists |

Rejected: a stored invoice with an explicit month-close operation. It adds a second idempotent
state machine without exercising a concern the assignment grades.

### 3.7 Notification

| ID | Requirement |
|----|-------------|
| FR-32 | Notify the tenant when a resource becomes ACTIVE, when provisioning FAILS, and when a resource is TERMINATED, including termination by expiry |
| FR-33 | Notification is best effort. A failure is logged and retried, and never causes compensation or changes a resource's state |
| FR-34 | Notifications are recorded with their delivery outcome and are readable per tenant |
| FR-35 | No real email or SMS is sent. Delivery is simulated and logged |

Rejected: notification as a compensatable saga step. A failed notification rolling back a
successfully provisioned and paid-for resource is the wrong trade-off, and it would make the
saga's failure surface less clear.

Rejected: quota threshold alerts. Additional scope with no graded return.

### 3.8 Usage and billing view

| ID | Requirement |
|----|-------------|
| FR-36 | A single endpoint returns a tenant's quota usage, active resource list, and month-to-date cost, composed over REST calls to the owning services |
| FR-37 | When a contributing service is unavailable, the response returns the sections it could obtain and marks the remainder unavailable, rather than failing entirely |
| FR-38 | The response carries a per-section availability indicator so a client can distinguish absent data from zero |

This requirement exists specifically to demonstrate that no service reads another's database.
The three data sets are owned by three services and are joined only over HTTP.

### 3.9 Provider mock

| ID | Requirement |
|----|-------------|
| FR-39 | Simulate provisioning, returning a synthetic instance ID on success |
| FR-40 | Delay each call by a random interval between configurable minimum and maximum bounds, defaulting to 1000 ms and 3000 ms |
| FR-41 | Fail a configurable proportion of calls, defaulting to 0.2, with 503 Service Unavailable |
| FR-42 | An `X-Simulate-Failure` request header overrides the configured behaviour for a single call, supporting at least `always-fail`, `always-succeed`, and `slow` |
| FR-43 | Honour the client token from FR-19: a repeated call with a known token returns the existing instance and does not provision again |
| FR-44 | Simulate termination of an instance |

### 3.10 Failure injection

| ID | Requirement |
|----|-------------|
| FR-45 | The billing service accepts the same `X-Simulate-Failure` header, defaulting to never failing, so the saga's step-3 failure can be forced on demand |
| FR-46 | The orchestrator persists the simulation header with the reservation and replays it on the corresponding downstream call, so injection survives the asynchronous boundary of FR-15 |

**Why injection is header-based rather than configuration-based.** The three challenge
scripts each need a different failure profile, and a header-scoped override changes behaviour
per request without restarting a service mid-demo. Configuration supplies the defaults the
assignment mandates; the header supplies determinism for evidence.

### 3.11 Resource lifecycle

States: `REQUESTED` → `PROVISIONING` → `ACTIVE` → `STOPPING` → `STOPPED` → `TERMINATED`,
with `FAILED` as a terminal state reachable from `REQUESTED` or `PROVISIONING`.

| ID | Requirement |
|----|-------------|
| FR-47 | The lifecycle is linear. `STOPPING` and `STOPPED` are stages of termination, representing provider shutdown in progress and confirmed |
| FR-48 | There is no stop or start operation. A resource is either running or being terminated |
| FR-49 | `FAILED` means provisioning did not complete and compensation has run. Quota is released and no billing meter exists |
| FR-50 | Any transition not on the defined graph is rejected and logged. The state machine is implemented explicitly, not inferred from field values |

Rejected: stop and start as tenant operations. It would require deciding whether a stopped
resource still holds quota, which is a second contended-resource policy competing with the
one Challenge 1 demonstrates.

---

## 4. Business Rules

Eight rules. Each must be traceable to a code location and to a test that validates it. The
mapping below is completed during Construction.

| ID | Rule | Enforced in | Test type |
|----|------|-------------|-----------|
| BR1 | Tenant quota usage must never exceed the limits defined by its tier, under any degree of concurrency | Quota reservation, as a SQL predicate | Database-backed, Testcontainers |
| BR2 | Resource provisioning is idempotent. The same idempotency key with the same payload yields the original response and no additional effect | Idempotency layer at the provisioning endpoint | Unit, plus database-backed for the concurrent case |
| BR3 | An idempotency key presented with a payload differing from its first use is rejected with 422 | Idempotency layer, fingerprint comparison | Unit, plus database-backed for the unique constraint |
| BR4 | A resource transitions only along the defined lifecycle graph. In particular it reaches ACTIVE only from PROVISIONING | Resource domain state machine | Unit |
| BR5 | A billing meter stops when its resource is terminated, and accrues nothing thereafter | Billing domain | Unit |
| BR6 | When a saga step fails after quota has been reserved, compensation releases that quota in full | Saga compensation | Unit, plus the Challenge 3 script |
| BR7 | A resource's quota is released exactly once. A repeated termination or a repeated expiry sweep never releases it twice | Resource termination guard plus a database constraint | Database-backed, Testcontainers |
| BR8 | A FREE-tier tenant cannot provision a CLUSTER resource | Quota domain, tier policy | Unit |

BR1, BR3, and BR7 are enforced partly or wholly by the database, so a unit test with a mocked
repository cannot validate them. They receive database-backed tests. See section 8.

---

## 5. Non-Functional and Operability Requirements

### 5.1 Service independence

| ID | Requirement |
|----|-------------|
| NFR-1 | Each service is an independently runnable Maven project with its own `pom.xml` and Maven Wrapper |
| NFR-2 | Each service owns exactly one database, with its own schema, its own credentials, and its own least-privilege database user |
| NFR-3 | No service reads or writes another service's database. There are no shared tables, no cross-service joins, and no foreign keys across service boundaries |
| NFR-4 | A service referencing an entity owned by another service stores only its identifier |
| NFR-5 | All inter-service communication is REST over HTTP. There is no shared code library between services |
| NFR-6 | A root `pom.xml` exists solely to build every service with one command. It carries no shared application code |

### 5.2 API quality

| ID | Requirement |
|----|-------------|
| NFR-7 | Every business endpoint is versioned under `/api/v1/` |
| NFR-8 | All request input is validated. Violations return 400 with the offending fields named |
| NFR-9 | Status codes are used precisely: 200 read, 201 created, 202 accepted for asynchronous work, 400 malformed, 404 absent, 409 state conflict, 422 business rule violation, 503 downstream unavailable |
| NFR-10 | Every error response in every service uses one format: RFC 9457 Problem Details, extended with `code`, `correlationId`, and `timestamp` |
| NFR-11 | Each service publishes an OpenAPI specification generated from its code by springdoc, with the generated `openapi.yaml` committed to the repository |

### 5.3 Observability

| ID | Requirement |
|----|-------------|
| NFR-12 | Every service exposes `GET /health` reporting its own status and its database connectivity |
| NFR-13 | A correlation identifier travels with every request as `X-Correlation-Id`. The first service to see a request without one generates it |
| NFR-14 | The correlation identifier is attached to every downstream call, including calls made after an asynchronous boundary |
| NFR-15 | Logs are structured JSON written to stdout. Every line carries the correlation identifier, the service name, and the level |
| NFR-16 | A single correlation identifier is sufficient to assemble the complete cross-service trace of one business transaction from the log files |
| NFR-17 | No secret, credential, or complete request payload appears in any log line |

### 5.4 Configuration and portability

| ID | Requirement |
|----|-------------|
| NFR-18 | All configuration is supplied by environment variables: ports, database connection strings, credentials, peer service URLs, timeouts, retry parameters, and simulation defaults |
| NFR-19 | Every configuration value has a local-development default, so a service starts with no environment set |
| NFR-20 | The same artefact runs locally and in a container with no code change. This is a prerequisite for Assignment 2 |
| NFR-21 | Services hold no in-memory state that cannot be lost. All state is in the owning database |
| NFR-22 | All timestamps are UTC. All monetary values are decimal, never floating point |
| NFR-23 | Database schema changes are applied by Flyway migrations, versioned and committed. No schema is created by ORM auto-generation |
| NFR-24 | Dependency versions are pinned. No open version ranges |

### 5.5 Resilience baseline

Applies even though resilience is not the graded Challenge 3.

| ID | Requirement |
|----|-------------|
| NFR-25 | Every outbound inter-service call has a connect timeout and a read timeout, both configurable |
| NFR-26 | Provider provisioning calls are retried with exponential backoff and jitter, up to a configurable attempt limit |
| NFR-27 | Retries never produce duplicate effects, guaranteed by the client token of FR-19 |
| NFR-28 | When a downstream service is unreachable, the caller fails cleanly with a correlated error rather than hanging or crashing |

---

## 6. Engineering Challenges

### 6.1 Challenge 1: Concurrency control

**Problem.** Simultaneous provisioning requests for one tenant must never allocate more quota
than the tier permits.

**Mechanism.** A single atomic conditional update. One SQL statement adds the requested usage
only if all three limits still hold afterwards, and the affected-row count decides the
outcome:

```
UPDATE tenant_quota
   SET cpu_used = cpu_used + :cpu,
       ram_used_gb = ram_used_gb + :ram,
       instance_count = instance_count + 1
 WHERE tenant_id = :tenantId
   AND cpu_used + :cpu <= cpu_limit
   AND ram_used_gb + :ram <= ram_limit_gb
   AND instance_count + 1 <= instance_limit
```

One row updated means reserved; zero rows means insufficient quota.

**Why not check-then-update.** Two requests reading 8 of 10 CPUs used both conclude that 2
more fit, and both write 10, leaving 12 allocated against a limit of 10. The read and the
write are separate operations with a window between them. The statement above has no window:
the condition is evaluated by the database as part of the write, under the row lock the write
itself takes.

**Why not optimistic locking.** The brief suggests it, and it is a correct solution with
bounded retries. It was rejected because without retries a request can fail on a version
conflict while capacity remains, which breaks the "exactly N succeed" criterion, and with
retries it adds a retry loop, a backoff policy, and a retry-exhaustion failure mode for no
behavioural gain in a single-row, single-predicate update. Recorded as a trade-off for the
report.

**Why not pessimistic locking.** Correct, but it serialises all requests for a tenant behind
a row lock held for the duration of a transaction, and introduces lock ordering as a concern.

**Acceptance criteria.**

| ID | Criterion |
|----|-----------|
| AC-1.1 | A script fires at least 20 parallel provisioning requests against a tenant whose remaining quota admits exactly N |
| AC-1.2 | Exactly N requests succeed. Not N-1, not N+1 |
| AC-1.3 | Every rejected request returns 422 with an error identifying which limit was exhausted |
| AC-1.4 | Final database state shows usage exactly at the limit, with resource rows matching the success count |
| AC-1.5 | The test is repeatable: a reset script restores the starting state and a rerun produces the same counts |
| AC-1.6 | Evidence includes the script, its output with success and failure counts, the final database state, and the written explanation of why check-then-update fails |

### 6.2 Challenge 2: Idempotency

**Problem.** A retried provisioning request, whether from a client retry or a network
timeout, must not create a second resource or a second charge.

**Mechanism.** An idempotency record keyed by the `Idempotency-Key` header, storing a
fingerprint of the request body and the response once known. A unique constraint on the key
is what makes concurrent duplicates safe: the second concurrent insert fails at the database,
not in application logic.

| Case | Behaviour |
|------|-----------|
| Key absent | 400 |
| Key unseen | Process normally, store key, fingerprint, and response |
| Key seen, fingerprint matches, original complete | Return the stored response unchanged, with no reprocessing |
| Key seen, fingerprint matches, original still in flight | Return 202 pointing at the same reservation |
| Key seen, fingerprint differs | 422, naming the conflict |

Records are retained 24 hours.

**Acceptance criteria.**

| ID | Criterion |
|----|-----------|
| AC-2.1 | A repeated identical request returns a response identical to the first, including the same resource ID |
| AC-2.2 | The repeat performs no reprocessing: no second provider call, no second meter, no second quota reservation |
| AC-2.3 | Ten concurrent identical requests result in exactly one resource row and one idempotency record |
| AC-2.4 | A reused key with an altered payload returns 422 |
| AC-2.5 | Logs show replay detection explicitly, distinguishing a served replay from fresh processing |
| AC-2.6 | Evidence includes first-call and repeat-call output, the concurrent-duplicate result, the single database row, and the replay log lines |

### 6.3 Challenge 3C: Saga with compensation

**Selected option.** 3C, Saga with Compensation. The domain already requires compensation
when provisioning fails after quota is reserved, so this is the option with the least
artificial fit.

**Not selected.** 3A Resilience Patterns. Timeouts and retries are still implemented as
NFR-25 to NFR-28 and described in the report, but they carry no separate evidence set and are
not graded. A circuit breaker is explicitly out of scope. 3B and 3D were not selected.

**Saga definition.** Orchestrated. One service owns the saga state and drives each step,
because services communicate only over REST and the state must be inspectable.

| Step | Action | Compensating action |
|------|--------|---------------------|
| 1 | Reserve quota | Release quota |
| 2 | Provision via provider | Terminate the provider instance |
| 3 | Start billing meter | Stop and void the meter |
| 4 | Send notification | None. Best effort per FR-33 |

The forced failure for evidence is at step 3, billing. Compensation then undoes step 2, then
step 1, in reverse order, which is exactly the shape the assignment describes.

**State.** Resource lifecycle state and saga outcome are persisted in the `resources` table. The
status field (REQUESTED → PROVISIONING → ACTIVE or FAILED) shows the saga outcome, and the
presence/absence of `provider_instance_id` and `activated_at` reveals whether compensation
happened before or after provider provisioning completed. This satisfies the "queryable, not
merely logged" requirement with a simpler design than separate saga coordination tables.

**Acceptance criteria.**

| ID | Criterion |
|----|-----------|
| AC-3.1 | Resource state transitions are persisted and queryable via resources.status (REQUESTED → PROVISIONING → ACTIVE or FAILED), showing the saga outcome |
| AC-3.2 | A forced failure at step 3 (billing) triggers compensation automatically |
| AC-3.3 | Compensation executes in reverse order: the provider instance is terminated, then quota is released |
| AC-3.4 | After compensation, tenant quota is identical to its pre-request value |
| AC-3.5 | No orphaned data remains: no running provider instance, no open meter, and the resource is FAILED |
| AC-3.6 | The tenant is notified of the failure |
| AC-3.7 | Compensation is idempotent. A repeated compensation pass causes no further effect |
| AC-3.8 | Evidence includes the resources table before and after, compensation log lines showing reverse-order execution, the quota value restored, and the provider's instance list showing the terminated instance |

### 6.4 Cross-challenge interaction

Worth stating because it is a likely report discussion point: Challenge 2 is what makes
NFR-26's retries safe. Without idempotency, a retry after a timeout where the original
actually succeeded would provision twice. The client token of FR-19 extends the same guarantee
to the provider boundary, where the orchestrator cannot know whether a timed-out call took
effect.

---

## 7. Technology Decisions

| Concern | Decision | Notes |
|---------|----------|-------|
| Language | Java 21 LTS | |
| Framework | Spring Boot 3.5.x | Satisfies the assignment's "Spring Boot 3.x" |
| Build | Maven, one project per service, Maven Wrapper committed | Maven need not be installed |
| Database | PostgreSQL, one server, a separate database and user per service | Satisfies separate credentials |
| Migrations | Flyway | |
| API documentation | springdoc-openapi, `openapi.yaml` exported and committed per service | |
| Service stubbing | WireMock | Lets the orchestrator be developed and tested with no other service running |
| Unit tests | JUnit 5 | |
| Database-backed tests | Testcontainers against real PostgreSQL | Docker required when tests run |
| Scripts | PowerShell 7 | End-to-end, challenge, seed, reset, and evidence capture |
| Payment or messaging infrastructure | None | No broker, no payment gateway |

### 7.1 Required toolchain

Installed before Construction, per the decision recorded in section 13:

| Tool | Version | Needed for |
|------|---------|-----------|
| JDK | 21 LTS | Compiling and running every service |
| PostgreSQL | 16 or 17 | The five service databases |
| Docker Desktop | current | Testcontainers in database-backed tests |
| PowerShell | 7.x | The delivered scripts |
| Git | current | Per-member commit history |

Maven is not required; the wrapper is committed. An exact install checklist with commands is
produced at the end of Inception.

---

## 8. Testing Requirements

| ID | Requirement |
|----|-------------|
| TR-1 | One test per business rule, BR1 through BR8, each named so the rule it validates is unambiguous |
| TR-2 | BR4, BR5, BR6, and BR8 are unit tests against the domain layer with no database and no network |
| TR-3 | BR1, BR3, and BR7 are database-backed tests using Testcontainers, because they are enforced by SQL predicates and constraints that a mocked repository cannot exercise |
| TR-4 | The BR1 test includes a genuinely concurrent case, proving the atomic update under parallel load rather than only its single-threaded behaviour |
| TR-5 | The BR2 and BR3 tests include a concurrent duplicate case, proving the unique constraint rather than only the application-level check |
| TR-6 | One scripted end-to-end test exercises the main transaction across all services and asserts the final state of each |
| TR-7 | Each challenge has its own script, producing counted, machine-checkable output rather than prose |
| TR-8 | Every script is repeatable. A reset script restores a known starting state |
| TR-9 | Every test and script is documented in the README with its command and its expected output |
| TR-10 | Evidence is captured for failure paths, not only success paths |

Beyond this, no integration tests on services whose rules are fully covered by unit tests, and
no property-based testing. The scope is the assignment's required set plus the minimum
addition that keeps every business rule traceable to a test that genuinely validates it.

---

## 9. Evidence Requirements

| ID | Requirement |
|----|-------------|
| ER-1 | One evidence folder per challenge, containing the script, its captured output, the relevant log excerpts, and the database state before and after |
| ER-2 | Captured output shows the command, its result, and a timestamp |
| ER-3 | Raw logs are committed as text, not as screenshots |
| ER-4 | Each challenge's evidence includes at least one failure case |
| ER-5 | Every claim in the report traces to a named evidence file |
| ER-6 | A fresh clone runs everything from the README alone, with no undocumented step |

---

## 10. Out of Scope

Explicitly excluded, per the assignment and the decisions recorded here:

- Authentication and authorisation. Tenant identity is passed explicitly in requests
- Any user interface
- Real integration with AWS, Azure, or GCP
- Real payment processing. Billing is metering only; no funds move
- Real email or SMS delivery
- Pricing beyond a flat per-CPU-hour rate
- Resource migration between providers
- Auto-scaling and load balancing
- Tier upgrade and downgrade
- Stop and start as tenant operations
- Stored monthly invoices and a month-close operation
- Quota threshold alerts
- A message broker and any event-driven flow
- A circuit breaker
- Containerisation and Minikube deployment, which are Assignment 2

---

## 11. Delivery Model

Team of 4. Individual marks are the team mark scaled by an individual factor from 0 to 1,
assessed on commit history, the contribution table, and demo participation.

**Model selected: F.** One member builds a self-contained core first: the services carrying
all three graded challenges, plus minimal but complete versions of the two remaining
services, so that every mandatory condition is satisfied by a core that integrates and
demos on its own. Each member then owns substantive work layered on top, rather than owning
the bare existence of a service. This sequencing keeps the challenge work off the critical
path of inter-service integration and lets each service be developed and tested in isolation.

| ID | Requirement |
|----|-------------|

| DR-1 | The core owner builds the quota service, the resource/saga orchestrator including the idempotency layer, and the billing service. These carry Challenges 1, 2, and 3C, and BR1 through BR7 |
| DR-2 | The core owner also commits working minimal versions of the notification service and the provider mock, each a real service with its own folder, project file, database, migrations, and health endpoint. Conditions A and E are thereby satisfied by the core itself |
| DR-3 | The quota service is built first and completely, serving as the reference implementation for cross-cutting patterns that every other service follows |
| DR-4 | A `CONTRIBUTING.md` records the shared patterns every service must follow: the error model, the correlation-ID filter, the logging format, environment-variable naming, the Flyway layout, and the package structure. This exists because NFR-10 and NFR-13 are graded as consistency properties across services, and independent scaffolding by four people would cause drift |
| DR-5 | The orchestrator is developed and tested against WireMock stubs throughout, so each service can progress independently of the others |
| DR-6 | Service contracts are frozen at the end of Application Design. Each member codes against the frozen contract and stubs the rest |
| DR-7 | Each member works on their own branch and commits from their own Git account. No member's work is committed on their behalf |
| DR-8 | Each member's assigned unit includes real business logic, its own tests, and a named deliverable they can explain unaided |
| DR-9 | The core is independently complete at every point: with only the minimal versions of the two remaining services in place, the system still runs end to end and no mandatory condition fails. Substantive work layered on top improves each service without being a prerequisite for a working, demonstrable system |

Provisional member allocation, confirmed during Units Generation:

| Member | Unit | Deliverable |
|--------|------|-------------|
| Core owner | Quota service | Challenge 1 |
| Core owner | Orchestrator and idempotency layer | Challenges 2 and 3C |
| Core owner | Billing service | FR-26 to FR-31, BR5 |
| Member 2 | Provider mock, beyond the minimal version | FR-40 to FR-44, the resilience baseline NFR-25 to NFR-28 |
| Member 3 | Notification service, beyond the minimal version | FR-32 to FR-35, delivery states and retry |
| Member 4 | Composite usage view and the expiry sweep | FR-23 to FR-25, FR-36 to FR-38 |

Member names are assigned during Units Generation. Every member also presents in the demo
video, per the assignment.

---

## 12. Report and Video Requirements

| ID | Requirement |
|----|-------------|
| DOC-1 | A full Markdown draft of all 10 report sections, with diagrams, is produced for the team to review, rewrite, and own |
| DOC-2 | Diagrams are authored as Mermaid or PlantUML source in the repository: a component diagram, a sequence diagram of the main transaction on both the happy path and a failure path, and an ER diagram per service |
| DOC-3 | At least three significant design decisions are documented with the options considered, the trade-offs, and the rationale. The concurrency mechanism, the synchronous/asynchronous boundary, and saga orchestration versus choreography are the candidates |
| DOC-4 | A business-rule traceability table maps each of BR1 to BR8 to its code location, its test, and its evidence file |
| DOC-5 | A demo video run sheet gives segment timings, speaker assignment, and the exact commands for each segment |
| DOC-6 | The report appendices carry the contribution table, per-member reflections, and an AI-use declaration |
| DOC-7 | The report is at most 15 pages excluding appendices |

The AI-use declaration must state that this requirements document, the design documents, and
generated code were produced with AI assistance under the AI-DLC workflow, and that each
member reviewed and can explain the code they present.

---

## 13. Decision Log

Recorded verbatim from the two question rounds. Numbers refer to
`requirement-verification-questions.md` and `requirement-verification-questions-followup.md`.

| # | Decision | Choice |
|---|----------|--------|
| Q1 | Team size | 4 |
| Q3 | Report support | Full Markdown draft of all 10 sections plus diagrams |
| Q4 | Challenge 3 option | Both 3A and 3C, later narrowed by F4 |
| Q5 | Tenant management | Create and read; tier immutable; seed script |
| Q6 | Quota limits | Brief's values; instance limit counts all resource types |
| Q7 | Business rules | 8 rules, BR1 to BR8 |
| Q8 | Stop semantics | Linear lifecycle; STOPPING and STOPPED are termination stages |
| Q9 | Expiry | Optional duration; idempotent scheduled sweep |
| Q10 | Cost | 0.12 USD per CPU-hour, prorated per second |
| Q11 | Usage view | One composite endpoint, degrading gracefully |
| Q12 | Notifications | ACTIVE, FAILED, TERMINATED; best effort |
| Q13 | Provisioning style | Synchronous quota check, then 202, saga asynchronous, client polls |
| Q14 | Idempotency | Key mandatory; 422 on payload mismatch; 24-hour retention |
| Q15 | Concurrency | Atomic conditional update |
| Q16 | Failure injection | `X-Simulate-Failure` header, refined by F3 |
| Q17 | Platform | Java 21, Spring Boot 3.5.x |
| Q18 | Database | PostgreSQL, one server, database and user per service |
| Q19 | Test depth | Freeform, resolved by F2 |
| Q20 | Scripts | PowerShell 7 |
| Q21 | Toolchain timing | Installed before Construction |
| Q22 | User Stories stage | Skipped |
| Q23 | Security baseline extension | Not enabled. Items that overlap the assignment's own requirements are retained as NFR-17, NFR-24, and NFR-2 |
| Q24 | 13 working assumptions | All confirmed |
| F1 | Delivery model | F: author owns the challenge-bearing services plus minimal versions of the rest |
| F2 | Test depth | Required tests, plus database-backed tests for BR1, BR3, and BR7 |
| F3 | Failure injection | Header override on top of environment-variable defaults; no admin endpoints |
| F4 | Challenge 3 | 3C only is graded. 3A is implemented as a resilience baseline without a separate evidence set, and the circuit breaker is dropped |

---

## 14. Open Items

| ID | Item | Needed by |
|----|------|-----------|
| OPEN-1 | Whether teammates receive generated implementations to review and commit, or test skeletons and a written brief to implement themselves. The sub-decision under Follow-up 1 was left blank | Units Generation, before any Construction work is handed over |
| OPEN-2 | Member names, for the contribution table and the unit owner map | Units Generation |
| OPEN-3 | Whether the composite usage view and the expiry sweep are separate deployables or capabilities inside existing services. This affects the service count but not Condition A, which the four business services already satisfy | Application Design |

None of these block this stage. OPEN-3 is a design question by nature, and OPEN-1 and OPEN-2
are needed only when work is allocated.

---

## 15. Stage Acceptance

This stage is complete when the requirements above are approved. Specifically:

- All five mandatory conditions trace to a requirement and a verification method, section 2
- Eight business rules are defined, each with a stated enforcement point and test type, section 4
- Three engineering challenges have explicit, countable acceptance criteria, section 6
- Scope exclusions are explicit, section 10
- The delivery model keeps every mandatory condition on a self-contained core, section 11

**Next stage**: Application Design. It fixes service boundaries, the endpoint set per service,
the data model and ownership per service, the error model, and the frozen contracts, with
alternatives and rationale presented for approval.
