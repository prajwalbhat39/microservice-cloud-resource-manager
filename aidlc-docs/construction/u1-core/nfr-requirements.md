# NFR Requirements — Unit U1 (Core Challenge Services)

**Stage**: CONSTRUCTION / NFR Requirements
**Status**: Awaiting approval
**Unit**: U1 — Core Challenge Services
**Owner**: Prajwal (core owner)
**Services in scope**: Quota (`:8081`, `quota_db`), Resource / Orchestrator (`:8082`, `resource_db`)
**Depends on**: `inception/requirements/requirements.md` §5 (approved, NFR-1 to NFR-28), `inception/design/application-design.md` §9 (approved), `construction/u1-core/functional-design.md` (this unit's behaviour)
**Consumed by**: `nfr-design.md` (how each obligation is met), `infrastructure-design.md`, Build and Test

This document goes through **every** NFR from NFR-1 to NFR-28 and classifies it as **binds U1**,
**partially binds U1**, or **does not bind U1**, with a reason in every case. Nothing is
dropped silently. For each binding NFR it states a concrete, checkable obligation on the Quota
service and on the Orchestrator service, and names the verification method.

Because the Quota service is the reference implementation for the other three units
(`units.md` DR-3, DR-4), several obligations here are stronger on Quota than the NFR strictly
requires: Quota must not merely comply, it must be the example U2, U3 and U4 copy.

---

## 1. Classification Summary

| NFR | Subject | Classification | One-line reason |
|-----|---------|----------------|-----------------|
| NFR-1 | Independently runnable Maven project per service | **Binds** | U1 ships two such projects |
| NFR-2 | One database, own credentials, least-privilege user per service | **Binds** (security baseline) | `quota_db`/`quota_user`, `resource_db`/`resource_user` |
| NFR-3 | No service reads another's database | **Binds** | Orchestrator must reach Quota only over HTTP |
| NFR-4 | Cross-service references stored as bare identifiers | **Binds** | `tenant_id`, `provider_instance_id`, `resource_id` are opaque text |
| NFR-5 | REST over HTTP only; no shared code library | **Binds** | Four peer clients; `common` package copied, not shared |
| NFR-6 | Root aggregator pom with no shared code | **Partially binds** | U1 contributes two modules; the root pom is a solution-level artefact |
| NFR-7 | Business endpoints versioned under `/api/v1/` | **Binds** | All 11 business endpoints of U1 |
| NFR-8 | All input validated; 400 names the offending fields | **Binds** | Four request-bearing endpoints (Q1, Q4, R1, R4) plus path variables |
| NFR-9 | Precise status codes | **Binds** | U1's business outcomes are 200/201/202/400/404/409/422/503, plus 405 and a last-resort 500 |
| NFR-10 | One error format everywhere: RFC 9457 + `code`, `correlationId`, `timestamp` | **Binds** (reference impl.) | Quota's handler is the template for all four units |
| NFR-11 | springdoc OpenAPI, `openapi.yaml` committed | **Binds** | Two specs to commit |
| NFR-12 | `GET /health` reporting status and DB connectivity | **Binds** | Both services have a datasource |
| NFR-13 | `X-Correlation-Id` read or generated | **Binds** (reference impl.) | Filter in both services |
| NFR-14 | Correlation ID on every downstream call, including after an async boundary | **Binds** | The saga is the async boundary; this is the hard case |
| NFR-15 | Structured JSON logs to stdout with `correlationId`, `service`, `level` | **Binds** (reference impl.) | Both services |
| NFR-16 | One correlation ID assembles the whole cross-service trace | **Partially binds** | U1 proves its own two legs plus the forwarded header into WireMock; the full four-service trace needs U2–U4 |
| NFR-17 | No secret, credential or full request payload in any log line | **Binds** (security baseline) | Payload-free logging; fingerprints, not bodies |
| NFR-18 | All configuration from environment variables | **Binds** | Ports, DSN, credentials, peer URLs, timeouts, retry, rate |
| NFR-19 | Every config value has a local default | **Binds** | Both services start with no environment set |
| NFR-20 | Same artefact runs locally and in a container, no code change | **Partially binds** | Design obligations only; containerisation is Assignment 2 |
| NFR-21 | No unrecoverable in-memory state | **Binds** | All state in `quota_db` / `resource_db`; the saga task queue is the honest exception |
| NFR-22 | UTC timestamps; decimal money | **Binds** | `timestamptz` + `Instant`; the rate passed to Billing is `BigDecimal` |
| NFR-23 | Flyway migrations; no ORM auto-DDL | **Binds** | Two `V1__baseline.sql` files; `ddl-auto: validate` |
| NFR-24 | Dependency versions pinned, no ranges | **Binds** (security baseline) | Two `pom.xml` files |
| NFR-25 | Connect and read timeouts on every outbound call, configurable | **Binds the Orchestrator**; **does not bind Quota** | Quota makes no outbound inter-service call |
| NFR-26 | Provider provisioning retried with exponential backoff and jitter | **Binds the Orchestrator**; **does not bind Quota** | The provider call is the orchestrator's |
| NFR-27 | Retries never duplicate effects, via the FR-19 client token | **Binds the Orchestrator**; **partially binds Quota** | Quota's contribution is `UNIQUE(resource_id)` making a retried reserve/release safe |
| NFR-28 | Unreachable downstream fails cleanly with a correlated error | **Binds the Orchestrator**; **does not bind Quota** | Quota has no downstream |

**Security baseline.** NFR-2, NFR-17 and NFR-24 are the three items retained from the security
extension, which was deliberately **not** enabled (decision Q23 in `requirements.md` §13). They
are in scope precisely because they overlap the assignment's own requirements; no other
security work (authentication, authorisation, TLS, secret management, dependency scanning) is
in scope for U1 or for the system. Credentials are therefore plain-text local defaults
(`changeme`), which is acceptable only because there is no authentication story at all and no
real data — and that is stated as a limitation in the report rather than passed off as secure.

---

## 2. Service Independence (NFR-1 to NFR-6)

| NFR | Obligation on **Quota** | Obligation on **Orchestrator** | Verified by |
|-----|------------------------|-------------------------------|-------------|
| NFR-1 **binds** | Own folder `services/quota`, own `pom.xml`, committed `mvnw`/`mvnw.cmd`; `./mvnw spring-boot:run` starts it with nothing else running | Own folder `services/resource`, own `pom.xml`, own wrapper; starts with only WireMock (or nothing, if no provisioning is attempted) | Manual check: start each service from a clean shell. **Needs the toolchain** → Build and Test |
| NFR-2 **binds** | Connects as `quota_user` to `quota_db` only. The user owns no object in any other database and has no `SUPERUSER`/`CREATEDB` | Connects as `resource_user` to `resource_db` only | `psql` check: `\du` shows no superuser roles for either; connecting `quota_user` to `resource_db` is refused. Script output captured in evidence |
| NFR-3 **binds** | Exposes tenants and quota **only** through its API; no view, no FDW, no second datasource | Has exactly **one** `DataSource` bean (`resource_db`). Tenant data is obtained only via `QuotaClient` | Code review: grep for a second datasource or JDBC URL; `application.yml` contains one URL per service |
| NFR-4 **binds** | `quota_reservations.resource_id` is `varchar` with **no** FK to anything outside `quota_db` | `resources.tenant_id`, `resources.provider_instance_id`, `reservations.tenant_id` are plain text with no FK | Migration review; `infrastructure-design.md` §2 states every FK and its target table (all intra-database) |
| NFR-5 **binds** | No dependency on any other service's code; `common` classes are copied into `com.crm.quota.common` | Peers reached only through `RestClient` over HTTP; no `crm-common` module on the classpath | `pom.xml` review: no inter-service module dependency in either project |
| NFR-6 **partially binds** | U1 registers `services/quota` as a module of the root aggregator pom and adds no shared code to it | U1 registers `services/resource` likewise | `./mvnw -q -DskipTests package` from the repository root builds both. **Needs the toolchain** → Build and Test |

NFR-6 is only *partial* because the root pom is a solution-level artefact that also lists U2–U4;
U1's obligation is to be buildable from it and to put nothing shareable in it.

---

## 3. API Quality (NFR-7 to NFR-11)

| NFR | Obligation on both services | Verified by |
|-----|----------------------------|-------------|
| NFR-7 **binds** | Every business path begins `/api/v1/`: Quota's 5 paths and the Orchestrator's 6 paths listed in `functional-design.md` §3 and §4. `/health` and `/v3/api-docs.yaml` are operability endpoints and stay outside the prefix — stated explicitly so the deviation is a decision, not an oversight | Controller-level `@RequestMapping` review; the committed `openapi.yaml` lists every path |
| NFR-8 **binds** | Bean Validation on all **four** request-bearing endpoints — Q1 and Q4 (request bodies), R1 (body plus the `Idempotency-Key` header), R4 (query parameters) — plus the path variables of Q2, Q3, Q5, R2, R3 and R5. A violation returns **400** with the offending field names in the problem body's `errors` array. Validation is syntactic only: `cpu <= 256` is validation, `cpu` fitting the tenant's quota is BR1 | Unit tests per DTO: `CreateTenantRequestValidationTest`, `ReserveQuotaRequestValidationTest`, `ProvisionResourceRequestValidationTest`, each asserting the field name appears in the response |
| NFR-9 **binds** | The **business** outcomes U1 returns are exactly: 200 (reads, reserve, release), 201 (tenant created), 202 (provision accepted, termination accepted), 400 (malformed/missing key), 404 (absent tenant/resource/reservation), 409 (state conflict: `INVALID_STATE_TRANSITION`, `TENANT_ALREADY_EXISTS`, `QUOTA_RESERVATION_ALREADY_RELEASED`), 422 (business-rule violations), 503 (downstream unavailable). Two further codes exist and are **not** business outcomes: **405** `METHOD_NOT_ALLOWED` for a wrong method on an existing path, and **500** `INTERNAL_ERROR` from the last-resort handler, which by definition covers only the unforeseen (`nfr-design.md` §2.1, §2.2). The obligation is therefore: no 200-with-error-in-body, and no 500 on any *foreseeable* condition — a 500 in a test run or a demo is a defect to be fixed, not a documented outcome | `openapi.yaml` review plus one controller test per status code; the three challenge scripts assert codes numerically; the 405 and 500 handlers are covered by `ErrorResponseContractTest` |
| NFR-10 **binds (reference impl.)** | One `@RestControllerAdvice` per service producing RFC 9457 Problem Details extended with `code`, `correlationId`, `timestamp`. **Every** error response, including framework-generated ones (unreadable JSON, missing header, wrong method, unmapped path), goes through it. The shared `code` vocabulary is published for U2–U4 | `ErrorResponseContractTest` asserts the five mandatory members on a sample of each error class; the vocabulary table in `nfr-design.md` §2.2 is the single source |
| NFR-11 **binds** | springdoc on both services; `openapi.yaml` exported to `services/<service>/openapi.yaml` and committed, regenerated whenever a controller changes. `@ApiResponse` annotations carry the error codes each operation can emit, so the spec matches `functional-design.md`. The springdoc properties, the export command and the determinism settings that make the diff meaningful are designed in `nfr-design.md` §5.5 | Diff check: the committed spec matches a freshly generated one. **Needs the toolchain** → Build and Test |

---

## 4. Observability (NFR-12 to NFR-17)

| NFR | Obligation on **Quota** | Obligation on **Orchestrator** | Verified by |
|-----|------------------------|-------------------------------|-------------|
| NFR-12 **binds** | `GET /health` returns 200 `{"status":"UP"}` with a `db` component reporting `quota_db` connectivity; 503 `DOWN` when the database is unreachable | Same for `resource_db`. Peer reachability is **not** part of health: a stubbed-out or stopped Billing must not make the orchestrator look dead (it degrades per NFR-28 instead) | `HealthEndpointTest` (200 + `db` present); manual check with PostgreSQL stopped. The negative case **needs the toolchain** |
| NFR-13 **binds (reference impl.)** | A servlet filter reads `X-Correlation-Id`, generates a UUID when absent, puts it in the MDC, echoes it on the response, and clears the MDC in a `finally` block | Same filter, copied | `CorrelationIdFilterTest`: supplied ID is reused; absent ID is generated; the MDC is empty after the request completes |
| NFR-14 **binds** | Not applicable to outbound calls (Quota makes none); the obligation is to **echo** the received ID so the orchestrator's logs and Quota's logs join | Every outbound call to Quota, Provider, Billing and Notification carries `X-Correlation-Id` — **including the calls made by the background saga and teardown tasks, after the HTTP request that started them has ended**. This is the genuinely hard case: the MDC is thread-local and a plain `@Async` hand-off loses it | `SagaCorrelationPropagationTest`: run the saga on the async executor and assert via the WireMock request journal that all three peer calls carry the originating ID |
| NFR-15 **binds (reference impl.)** | JSON to stdout, one object per line, every line carrying `correlationId`, `service` (`quota`), `level`, plus timestamp, logger and message. No file appender | Same, with `service: resource` | `LogFormatTest` parses captured stdout as JSON and asserts the three mandatory fields on every line |
| NFR-16 **partially binds** | Emit the ID on every line so a `grep` across service logs assembles the transaction | Same, and keep the ID stable across the async boundary so the pre-202 and post-202 halves of one provisioning share it | The E2E script greps one ID across both U1 logs **and the WireMock request journal**, proving the forwarded header. The complete four-service trace can only be assembled once U2–U4 run → **Build and Test** |
| NFR-17 **binds (security baseline)** | Never log the datasource password, never log a full request or response body. Log identifiers, counts, status codes and outcomes only | Additionally: never log the provisioning request body (log the 64-char fingerprint instead), never log a stored idempotency response body, and truncate any peer error body to a bounded length with no payload echo | `LogPayloadHygieneTest` asserts that captured output contains neither the configured password nor a known field value from a submitted body; plus a code-review pass on every `log.*` call |

---

## 5. Configuration and Portability (NFR-18 to NFR-24)

| NFR | Obligation on **Quota** | Obligation on **Orchestrator** | Verified by |
|-----|------------------------|-------------------------------|-------------|
| NFR-18 **binds** | Port, DB host/port/name/user/password and log level come from environment variables; no hard-coded host, port or credential anywhere in Java code | The same, plus the four peer base URLs, the four pairs of timeouts, the retry parameters and `BILLING_RATE_PER_CPU_HOUR`. The rate is configuration, not a constant, because FR-28's price is a business parameter | Code review (grep for `localhost`, `5432`, `http://` outside `application.yml`); the env-var tables in `infrastructure-design.md` §5 are the contract |
| NFR-19 **binds** | Every variable has a default via `${VAR:default}` in `application.yml`; `./mvnw spring-boot:run` with an empty environment starts the service against a local PostgreSQL | Same; with no environment set, peer URLs default to `localhost:8081/8083/8084/8085` so a full local run needs no exports | Manual check from a clean shell. **Needs the toolchain** → Build and Test |
| NFR-20 **partially binds** | Design obligations only: configuration exclusively through environment variables, logs to stdout, no reliance on a local file path, no hostname assumptions beyond the defaults | Same, plus peer addressing by name-and-port so a container network can substitute service names for `localhost` without a code change | Code review now; actually running in a container is **Assignment 2** and explicitly not verified here |
| NFR-21 **binds** | All state in `quota_db`. No cache of tenants or usage; no in-memory counter of any kind — a counter would be a second copy of the contended resource | All state in `resource_db`. The one in-memory structure is the `sagaExecutor` queue of pending tasks; losing it on restart loses in-flight provisionings, which is recorded as a known limitation (`functional-design.md` §13 A6), not papered over | Code review for `static` mutable state and for caches; restart test: stop and start a service and confirm every endpoint still answers from the database |
| NFR-22 **binds** | All timestamp columns `timestamptz`, all Java instants `java.time.Instant`, `UTC` session time zone, ISO-8601 `Z` in JSON. Quota holds no money | Same, plus `ratePerCpuHour` is a `BigDecimal` (scale 4) parsed from configuration and serialised as a JSON string. No `double` or `float` anywhere in U1 | `TimestampSerialisationTest`; code review for `double`/`float`; migration review for `timestamptz` |
| NFR-23 **binds** | Schema created only by Flyway `V1__baseline.sql`; `spring.jpa.hibernate.ddl-auto: validate` so a drifted entity fails fast instead of silently altering the schema | Same, with its own `V1__baseline.sql` | Startup fails if an entity does not match the migration — that failure *is* the test. Migration files reviewed in `infrastructure-design.md` §2 and §3 |
| NFR-24 **binds (security baseline)** | Every dependency **and plugin** version pinned, inherited from an explicitly versioned Spring Boot parent or stated in `<properties>`. No `LATEST`, no `RELEASE`, no open range, no snapshot. The pinning rules and what is exempt are designed in `nfr-design.md` §5.6 | Same | `pom.xml` review; `./mvnw dependency:tree` captured into `evidence/infra/dependency-tree.txt`. **Needs the toolchain** → Build and Test |

---

## 6. Resilience Baseline (NFR-25 to NFR-28)

Challenge 3A was **not** selected; these four NFRs are implemented as a baseline and described
in the report, but they carry no separate evidence set and are not separately graded (decision
F4). The circuit breaker is explicitly out of scope.

| NFR | Classification | Obligation | Verified by |
|-----|----------------|-----------|-------------|
| NFR-25 | **Binds the Orchestrator. Does not bind Quota** — Quota makes no outbound inter-service call, so there is no timeout to set. (Its JDBC pool timeouts are a datasource concern, not an inter-service one) | Each of the four peer clients has a configurable connect timeout and read timeout, with per-peer values (the provider needs the longest because it sleeps 1–3 s by design, FR-40). Concrete values in `nfr-design.md` §8.1 | `PeerTimeoutConfigTest` asserts the configured values reach the clients; a WireMock fixed-delay stub proves the read timeout fires rather than hanging |
| NFR-26 | **Binds the Orchestrator. Does not bind Quota** | Provider provisioning calls are retried with exponential backoff **and jitter**, up to a configurable attempt limit, retrying only on timeouts, connection failures and 503 — never on a 4xx, which is a final answer | `ProviderRetryTest` with a WireMock scenario failing twice then succeeding: asserts 3 request-journal entries and increasing gaps |
| NFR-27 | **Binds the Orchestrator; partially binds Quota** | Orchestrator: every provider provision call carries the same `ClientToken.of(resourceId)`, so a retry after an ambiguous timeout returns the existing instance instead of creating a second (FR-19, FR-43). Quota's share: `UNIQUE(resource_id)` on the ledger makes a retried reserve or release harmless (BR7) | `ClientTokenTest`; the WireMock journal shows one token across all attempts; `QuotaReleaseLedgerIT` covers the quota half |
| NFR-28 | **Binds the Orchestrator. Does not bind Quota** | An unreachable peer must produce a clean, correlated outcome, never a hang or a crash: on the **synchronous** path (Quota unreachable) a 503 `DOWNSTREAM_UNAVAILABLE` carrying the correlation ID, with the idempotency record rolled back; on the **asynchronous** path (Provider/Billing unreachable) saga compensation and `resources.status = FAILED` with a `failure_reason` naming the peer | `QuotaUnavailableTest` (WireMock stopped → 503, no record left behind); the Challenge 3C script covers the async path |

---

## 7. Items That Cannot Be Verified Without the Toolchain

JDK 21, Maven, PostgreSQL 16/17 and Docker are deliberately not installed at this stage, so the
following obligations are **designed now and verified in Build and Test**. They are listed here
so that stage picks them up as a checklist rather than rediscovering them.

| NFR | What is deferred | Why |
|-----|-----------------|-----|
| NFR-1, NFR-6 | Each service starting standalone; the root aggregator build | Needs JDK + Maven |
| NFR-2 | `quota_user` cannot reach `resource_db`; neither user is a superuser | Needs a running PostgreSQL |
| NFR-11 | Generated `openapi.yaml` matching the committed copy | Needs springdoc at runtime |
| NFR-12 | The 503 `DOWN` case with the database stopped | Needs a running PostgreSQL |
| NFR-14, NFR-16 | Correlation ID present on peer calls after the async boundary; one ID assembling the trace | Needs both services plus WireMock running |
| NFR-19 | A clean-shell start with no environment set | Needs the full local stack |
| NFR-23 | Flyway applying `V1__baseline.sql` and `ddl-auto: validate` passing | Needs PostgreSQL; the BR1/BR3/BR7 tests additionally need **Docker running** for Testcontainers |
| NFR-24 | `dependency:tree` evidence | Needs Maven |
| NFR-25, NFR-26 | Timeout firing and retry spacing | Needs WireMock with delay/scenario stubs |

Everything else in sections 2–6 is verifiable by unit test or code review at Code Generation
time.

Two of these rows are **verification** deferrals only, not design gaps: NFR-11's springdoc
configuration, export path and annotation convention are fixed in `nfr-design.md` §5.5, and
NFR-24's pinning rules in §5.6. What waits for the toolchain is running the export and the
dependency tree, because both need a JVM. The distinction matters because `workflow-plan.md` §1
scopes NFR Design to "validation, error model, correlation ID, logging, config, migrations", so
without saying this explicitly a reader could reasonably assume both obligations had simply been
left to Code Generation to invent.

---

## 8. Open Questions and Assumptions

| ID | Issue | Reading taken |
|----|-------|---------------|
| **N1** | NFR-7 says "every business endpoint" is versioned, but `GET /health` (NFR-12) and the springdoc paths are not business endpoints | Both are served outside `/api/v1`. Recorded as a deliberate deviation so a reviewer does not read it as a miss |
| **N2** | NFR-12 does not say whether peer reachability belongs in `/health` | It does not. Health reports only the service and its own database; peer health would make the orchestrator report `DOWN` whenever a stub is off, contradicting NFR-28's "fail cleanly" rather than "be unhealthy" |
| **N3** | NFR-21 ("no in-memory state that cannot be lost") versus the in-memory saga task queue | Accepted as a bounded exception and documented as a limitation: the queue is a work handle, not business state, and the business state it would have written is reconstructible from `resources.status = 'PROVISIONING'` |
| **N4** | NFR-25 refers to "every outbound inter-service call"; Quota has none | Classified as not binding Quota rather than vacuously satisfied, so the report's NFR matrix is honest about where resilience actually lives |

---

## 9. Stage Acceptance

This stage is complete when approved. Specifically:

- All 28 NFRs are classified as binds / partially binds / does not bind, each with a reason, and
  none is omitted (section 1).
- Every binding NFR is restated as a concrete checkable obligation on the Quota service and on
  the Orchestrator service separately (sections 2–6).
- Every obligation names its verification method: unit test, integration test, manual check,
  script output or code review.
- NFR-2, NFR-17 and NFR-24 are recorded explicitly as the retained security-baseline items, with
  the full security extension noted as deliberately not enabled (section 1).
- The obligations that cannot be verified without the toolchain are listed for the Build and Test
  stage (section 7).

**Next stage for U1**: `nfr-design.md` — how these obligations are met in code: validation, the
shared error model and its complete code vocabulary, the correlation ID including the async
boundary, structured logging, configuration, Flyway conventions, health, and the resilience
baseline.



