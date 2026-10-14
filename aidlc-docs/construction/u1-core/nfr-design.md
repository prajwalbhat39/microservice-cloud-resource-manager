# NFR Design — Unit U1 (Core Challenge Services)

**Stage**: CONSTRUCTION / NFR Design
**Status**: Awaiting approval
**Unit**: U1 — Core Challenge Services
**Owner**: Prajwal (core owner)
**Services in scope**: Quota (`:8081`, `quota_db`), Resource / Orchestrator (`:8082`, `resource_db`)
**Depends on**: `construction/u1-core/nfr-requirements.md` (the obligations), `construction/u1-core/functional-design.md` (the behaviour), `inception/design/application-design.md` §5.7 and §9 (frozen error model and conventions)
**Consumed by**: `infrastructure-design.md`, Code Generation for U1, and `CONTRIBUTING.md` for units U2–U4

This is the *how* for the obligations in `nfr-requirements.md`: input validation, the shared
error model and its complete code vocabulary, the correlation ID including the hard case across
the async boundary, structured logging, configuration, Flyway conventions, the health endpoint,
and the resilience baseline.

Everything here is written to be **copied**: the Quota service is the reference implementation
for U2, U3 and U4 (`units.md` DR-3, DR-4), so each pattern is stated as a rule the other owners
can follow without re-deriving it.

---

## 1. Input Validation (NFR-8)

### 1.1 Strategy

Validation is declarative and lives on the DTO, so the rule is visible next to the field it
constrains and is testable without a web context.

- `spring-boot-starter-validation` on both services.
- Request bodies: `@Valid @RequestBody`. Path variables and query parameters: `@Validated` on
  the controller plus constraints on the parameters.
- DTOs are Java **records** with constraints on the components, which makes them immutable and
  keeps the validation adjacent to the contract.
- A custom `@ValueOfEnum` constraint validates enum-like strings (`type`, `status`) and lists
  the permitted values in its message, so a 400 tells the caller what to send instead.
- Validation is **syntactic only**. `cpu <= 256` is validation; `cpu` fitting the tenant's
  quota is BR1 and returns 422. Mixing the two would let a limit change become an API-breaking
  validation change.

```java
public record ProvisionResourceRequest(
        @NotBlank @Size(max = 64) String tenantId,
        @NotBlank @ValueOfEnum(ResourceType.class) String type,
        @NotNull @Min(1) @Max(256) Integer cpu,
        @NotNull @Min(1) @Max(2048) Integer ramGb,
        @Min(30) @Max(2_592_000) Long durationSeconds) { }
```

### 1.2 What a 400 body looks like

Field violations are collected into an RFC 9457 **extension member** called `errors`, one entry
per violation, rather than being flattened into prose. The caller gets machine-readable field
names (NFR-8 says "with the offending fields named") without losing the human-readable `detail`.

```json
// 400 Bad Request — POST /api/v1/resources
{
  "type": "https://crm.example/errors/validation-failed",
  "title": "Request validation failed",
  "status": 400,
  "detail": "2 field(s) are invalid",
  "instance": "/api/v1/resources",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "cpu",  "message": "must be greater than or equal to 1", "rejectedValue": 0 },
    { "field": "type", "message": "must be one of [VM, CONTAINER, CLUSTER]", "rejectedValue": "vmm" }
  ],
  "correlationId": "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e",
  "timestamp": "2026-10-14T09:12:05Z"
}
```

Mapping into the structure:

| Source | Spring exception | Becomes |
|--------|-----------------|---------|
| Body field constraint | `MethodArgumentNotValidException` | one `errors[]` entry per `FieldError`: `field`, `message`, `rejectedValue` |
| Query/path constraint | `ConstraintViolationException` | one entry per violation, `field` = the last path node |
| Missing query parameter | `MissingServletRequestParameterException` | one entry with the parameter name |
| Missing required header | `MissingRequestHeaderException` | `IDEMPOTENCY_KEY_REQUIRED` when the header is `Idempotency-Key`, otherwise `VALIDATION_FAILED` |
| Unparseable JSON / wrong type | `HttpMessageNotReadableException` | `MALFORMED_REQUEST`, no `errors[]` (the body never bound, so there are no fields to name) |

`rejectedValue` is included only for scalar values and is omitted for anything that could carry
sensitive content, which keeps NFR-17 intact while still being useful. No full request body is
ever echoed.

**Alternatives considered and rejected.**

| Choice | Alternative rejected | Reason |
|--------|---------------------|--------|
| Bean Validation on DTO records | Hand-written `if` checks in the controller | Four services must behave identically; annotations are the one form Spring already renders consistently, and they appear in the OpenAPI spec for free (NFR-11) |
| Field violations in an `errors` extension | Concatenating messages into `detail` | A script asserting "field `cpu` was rejected" would have to parse English; RFC 9457 explicitly allows extension members |
| `@Max(256)` as sanity only | Validating against the tenant's real limit | The real limit lives in Quota's database; replicating it in the orchestrator's annotations would break NFR-3 and give BR1 a second, divergent enforcement point |

---

## 2. The Shared Error Model (NFR-10)

Frozen shape from `application-design.md` §5.7: RFC 9457 Problem Details plus `code`,
`correlationId` and `timestamp`. Content type `application/problem+json`.

### 2.1 Exception hierarchy

One abstract root means the advice has one primary handler and the catalogue of failures is
enumerable by reading one file. Every subclass carries its `ErrorCode`, which carries the HTTP
status — so a code can never be returned with two different statuses by two different services.

```
RuntimeException
└── ApiException (abstract)                     // code, detail, optional extension members
    ├── NotFoundException               404     TENANT_NOT_FOUND, RESOURCE_NOT_FOUND,
    │                                           RESERVATION_NOT_FOUND, QUOTA_RESERVATION_NOT_FOUND
    ├── StateConflictException          409     INVALID_STATE_TRANSITION, TENANT_ALREADY_EXISTS,
    │   └── IllegalStateTransitionException     QUOTA_RESERVATION_ALREADY_RELEASED
    ├── BusinessRuleViolationException  422
    │   ├── QuotaExceededException              QUOTA_EXCEEDED            (+ exhausted[])
    │   ├── TierForbidsResourceTypeException    TIER_FORBIDS_RESOURCE_TYPE
    │   ├── InvalidTierException                INVALID_TIER
    │   └── IdempotencyPayloadMismatchException IDEMPOTENCY_PAYLOAD_MISMATCH
    ├── BadRequestException             400     VALIDATION_FAILED, MALFORMED_REQUEST
    │   └── IdempotencyKeyRequiredException     IDEMPOTENCY_KEY_REQUIRED
    └── DownstreamUnavailableException   503     DOWNSTREAM_UNAVAILABLE, PROVIDER_UNAVAILABLE,
                                                BILLING_UNAVAILABLE, NOTIFICATION_UNAVAILABLE
```

```java
// com.crm.quota.common.error  (copied verbatim into com.crm.resource.common.error)
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail onApi(ApiException e, HttpServletRequest req) {
        log.warn("api.error code={} status={} path={}", e.code(), e.code().status(), req.getRequestURI());
        return problem(e.code(), e.code().status(), e.getMessage(), req, e.extensions());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onBodyValidation(MethodArgumentNotValidException e, HttpServletRequest req) { ... }

    @ExceptionHandler(ConstraintViolationException.class)            // params and path variables
    @ExceptionHandler(MissingRequestHeaderException.class)           // -> IDEMPOTENCY_KEY_REQUIRED
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ExceptionHandler(HttpMessageNotReadableException.class)         // -> MALFORMED_REQUEST
    @ExceptionHandler(NoResourceFoundException.class)                // unmapped path -> 404
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)  // -> 405

    /** Last resort. Nothing foreseeable reaches here; if it does, it is a bug, so it is logged
        at ERROR with the stack trace and returned as an opaque 500 that leaks nothing. */
    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpected(Exception e, HttpServletRequest req) {
        log.error("api.error.unexpected path={}", req.getRequestURI(), e);
        return problem(INTERNAL_ERROR, 500, "An unexpected error occurred", req, Map.of());
    }
}
```

`ProblemDetailsFactory.problem(...)` is the only place a problem body is built. It sets `type`
(`https://crm.example/errors/<kebab-case-code>`), `title`, `status`, `detail`, `instance` (the
request URI), then the three mandatory extensions: `code`, `correlationId` (from the MDC) and
`timestamp` (UTC `Instant`, ISO-8601 with `Z`). A single construction point is what makes
NFR-10 a property of the service rather than a habit of whoever wrote the controller.

Two framework details that matter for "every error response" to really mean every:

- `spring.mvc.problemdetails.enabled: true` plus the explicit handlers above, so Spring's own
  errors (405, unreadable body, unmapped path) get the three extension members too.
- Spring Security is not on the classpath (no authentication is in scope), so there is no
  filter-chain error path that bypasses the advice.

### 2.2 Complete `code` vocabulary

This table is the single source of truth for the whole system and is republished unchanged in
`CONTRIBUTING.md` for U2–U4. A service may only emit a code listed against it.

| `code` | HTTP | Meaning | Emitted by |
|--------|------|---------|-----------|
| `VALIDATION_FAILED` | 400 | One or more request fields violated a syntactic constraint; `errors[]` names them | all services |
| `MALFORMED_REQUEST` | 400 | Body was not valid JSON or could not be bound at all | all services |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | `Idempotency-Key` header absent or blank on `POST /api/v1/resources` (FR-12) | Orchestrator |
| `TENANT_NOT_FOUND` | 404 | No tenant with that id | Quota; **propagated unchanged** by Orchestrator |
| `RESOURCE_NOT_FOUND` | 404 | No resource with that id | Orchestrator |
| `RESERVATION_NOT_FOUND` | 404 | No request-level reservation with that id | Orchestrator |
| `QUOTA_RESERVATION_NOT_FOUND` | 404 | No ledger row for that `resourceId` | Quota |
| `METER_NOT_FOUND` | 404 | No billing meter for that `resourceId` | Billing (U2) |
| `INSTANCE_NOT_FOUND` | 404 | No provider instance with that id | Provider mock (U3) |
| `TENANT_ALREADY_EXISTS` | 409 | A client-supplied `tenantId` is already taken | Quota |
| `INVALID_STATE_TRANSITION` | 409 | The requested lifecycle transition is not on the graph; body carries `currentStatus` (BR4, FR-22) | Orchestrator |
| `QUOTA_RESERVATION_ALREADY_RELEASED` | 409 | A reserve was attempted for a `resourceId` whose ledger row is already `RELEASED` | Quota |
| `QUOTA_EXCEEDED` | 422 | The atomic conditional update matched no row; body carries `exhausted[]` (BR1, AC-1.3) | Quota; **propagated** by Orchestrator |
| `TIER_FORBIDS_RESOURCE_TYPE` | 422 | FREE tier may not provision CLUSTER (BR8) | Quota; **propagated** by Orchestrator |
| `INVALID_TIER` | 422 | Tier string is not FREE/STANDARD/ENTERPRISE | Quota |
| `IDEMPOTENCY_PAYLOAD_MISMATCH` | 422 | Key reused with a different request fingerprint (BR3) | Orchestrator |
| `DOWNSTREAM_UNAVAILABLE` | 503 | A synchronous peer call failed after retries; the peer is named in `detail`, never in the code | Orchestrator, Usage aggregator (U4) |
| `PROVIDER_UNAVAILABLE` | 503 | Provider provisioning failed after retries. Used as a `failure_reason` prefix inside the saga, and as the provider mock's own simulated-failure code | Orchestrator (as `failure_reason`), Provider mock (U3) |
| `BILLING_UNAVAILABLE` | 503 | Billing meter start/stop failed after retries. Drives the Challenge 3C compensation | Orchestrator (as `failure_reason`), Billing (U2) |
| `NOTIFICATION_UNAVAILABLE` | 503 | Notification send failed. **Never** triggers compensation (FR-33); logged and left | Orchestrator (log only), Notification (U4) |
| `SIMULATED_FAILURE` | 503 | Deliberate failure injected by configuration or the `X-Simulate-Failure` header (FR-41, FR-42, FR-45) | Provider mock (U3), Billing (U2) |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP method for an existing path | all services |
| `INTERNAL_ERROR` | 500 | Unexpected failure; message is opaque and the stack trace stays in the log | all services |

Note the deliberate asymmetry on 503: `DOWNSTREAM_UNAVAILABLE` is what a *caller* sees, while
`PROVIDER_UNAVAILABLE` / `BILLING_UNAVAILABLE` identify *which* peer failed and are recorded in
`resources.failure_reason`. A tenant polling a reservation therefore learns which step broke
without the orchestrator leaking peer URLs or stack traces.

### 2.3 Worked example per error class

```json
// 404 — GET /api/v1/tenants/tnt-nope
{ "type": "https://crm.example/errors/tenant-not-found", "title": "Tenant not found",
  "status": 404, "detail": "No tenant with id tnt-nope", "instance": "/api/v1/tenants/tnt-nope",
  "code": "TENANT_NOT_FOUND", "correlationId": "7f1e...", "timestamp": "2026-10-14T09:30:00Z" }
```
```json
// 400 — POST /api/v1/resources with no Idempotency-Key
{ "type": "https://crm.example/errors/idempotency-key-required", "title": "Idempotency key required",
  "status": 400, "detail": "The Idempotency-Key header is mandatory for this operation",
  "instance": "/api/v1/resources", "code": "IDEMPOTENCY_KEY_REQUIRED",
  "correlationId": "7f1e...", "timestamp": "2026-10-14T09:30:01Z" }
```
```json
// 422 — key reused with an altered payload (BR3)
{ "type": "https://crm.example/errors/idempotency-payload-mismatch",
  "title": "Idempotency key reused with a different payload", "status": 422,
  "detail": "Idempotency-Key 0f8c3a7e-provision-001 was first used with a different request body",
  "instance": "/api/v1/resources", "code": "IDEMPOTENCY_PAYLOAD_MISMATCH",
  "correlationId": "7f1e...", "timestamp": "2026-10-14T09:30:02Z" }
```
```json
// 409 — terminate a PROVISIONING resource (BR4, FR-22)
{ "type": "https://crm.example/errors/invalid-state-transition", "title": "Invalid state transition",
  "status": 409, "detail": "Resource e5f1a2b3-... is PROVISIONING; termination requires ACTIVE",
  "instance": "/api/v1/resources/e5f1a2b3-1111-4c2d-8e3f-9a0b1c2d3e4f/termination",
  "code": "INVALID_STATE_TRANSITION", "currentStatus": "PROVISIONING",
  "correlationId": "7f1e...", "timestamp": "2026-10-14T09:30:03Z" }
```
```json
// 503 — Quota unreachable on the synchronous path (NFR-28)
{ "type": "https://crm.example/errors/downstream-unavailable", "title": "Downstream service unavailable",
  "status": 503, "detail": "Quota service did not respond after 3 attempts", "instance": "/api/v1/resources",
  "code": "DOWNSTREAM_UNAVAILABLE", "correlationId": "7f1e...", "timestamp": "2026-10-14T09:30:04Z" }
```

**Alternatives considered and rejected.** A custom `{ "error": ..., "message": ... }` envelope
was rejected: RFC 9457 is a standard with a registered media type, and NFR-10 names it. Mapping
peer errors straight through (returning Quota's raw body from the orchestrator) was rejected
except for the two cases where the business answer genuinely belongs to Quota
(`QUOTA_EXCEEDED`, `TIER_FORBIDS_RESOURCE_TYPE`, plus `TENANT_NOT_FOUND`); everything else is
translated so the orchestrator's `instance` and `correlationId` stay its own.

---

## 3. Correlation ID (NFR-13, NFR-14, NFR-16)

### 3.1 Inbound: a servlet filter

```java
// com.crm.quota.common.web.CorrelationIdFilter   (copied into com.crm.resource.common.web)
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)          // must run before anything that logs
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String incoming = req.getHeader(HEADER);
        String id = (incoming != null && !incoming.isBlank() && incoming.length() <= 64)
                ? incoming                            // reuse what the caller gave us (NFR-13)
                : UUID.randomUUID().toString();       // first service to see it generates it
        MDC.put(MDC_KEY, id);
        res.setHeader(HEADER, id);                    // echo, so a client can quote it in a bug report
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);                      // ALWAYS, or a pooled thread leaks it to the next request
        }
    }
}
```

The length cap and blank check matter: the ID lands in every log line, so an unbounded
caller-supplied header would be a log-injection and log-bloat vector. The `finally` block is
not optional — Tomcat reuses threads, so a missed cleanup would silently attribute one tenant's
request to another's trace.

### 3.2 Outbound: a `RestClient` request initializer

```java
// com.crm.resource.config.RestClientConfig
RestClient.Builder peerClient(String baseUrl, Duration connect, Duration read) {
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(timeouts(connect, read))
        .requestInitializer(req -> {                  // runs on every request, every peer
            String id = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (id != null) req.getHeaders().add(CorrelationIdFilter.HEADER, id);
        });
}
```

One initializer registered on the shared builder covers all four peer clients, so a new peer
cannot be added without the header. A per-call `header(...)` argument was rejected for exactly
that reason.

### 3.3 The hard case: carrying the ID across the async boundary (NFR-14)

The MDC is a `ThreadLocal`. `POST /api/v1/resources` returns 202 and the saga then runs on a
pooled thread, so a plain `@Async` hand-off arrives with an **empty** MDC and every log line and
every peer call in the saga would lose the ID — exactly the trace that matters most, because it
spans Provider, Billing and Notification.

Two mechanisms, deliberately both:

**(a) An MDC-propagating `TaskDecorator` on the saga executor** — the general fix, so any future
background work inherits it.

```java
// com.crm.resource.config.AsyncConfig
@Bean("sagaExecutor")
ThreadPoolTaskExecutor sagaExecutor(
        @Value("${saga.pool.core:4}") int core,
        @Value("${saga.pool.max:8}") int max,
        @Value("${saga.pool.queue:100}") int queue) {
    var ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(core); ex.setMaxPoolSize(max); ex.setQueueCapacity(queue);
    ex.setThreadNamePrefix("saga-");
    ex.setTaskDecorator(runnable -> {
        Map<String, String> captured = MDC.getCopyOfContextMap();   // captured on the WEB thread
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (captured != null) MDC.setContextMap(captured);  // restored on the POOL thread
                runnable.run();
            } finally {
                if (previous != null) MDC.setContextMap(previous); else MDC.clear();
            }
        };
    });
    ex.initialize();
    return ex;
}
```

**(b) The ID also travels as a field on the task payload** —
`ProvisioningTask(..., String correlationId)` and `TerminationTask(..., String correlationId)`,
read from the MDC at dispatch time (`functional-design.md` §9.3 step (e)). The saga re-seeds the
MDC from that field as its first statement:

```java
@Async("sagaExecutor")
public void run(ProvisioningTask task) {
    MDC.put(CorrelationIdFilter.MDC_KEY, task.correlationId());
    try { ...saga... } finally { MDC.remove(CorrelationIdFilter.MDC_KEY); }
}
```

Why both? The decorator is invisible: if someone later submits a task through a different
executor, or if a retry runs the task from a queue, the propagation silently disappears and the
trace breaks with no error. The explicit field makes the ID part of the task's data — the same
reason `X-Simulate-Failure` is persisted on the reservation rather than kept in a thread
(FR-46). The decorator covers code that forgets; the field covers configuration that changes.

**Alternatives considered and rejected.**

| Alternative | Reason rejected |
|-------------|-----------------|
| `@Async` with no decorator and no field | The saga's three peer calls and all its log lines lose the ID — a direct NFR-14 failure, and the one trace the report needs most |
| Micrometer Tracing / OpenTelemetry context propagation | Correct and more capable, but it adds a tracing stack, exporters and a sampling story for a requirement that asks for one header in the logs. Out of proportion for the assignment, and harder for teammates to copy |
| Pass the ID as a method parameter through every saga method | Works, but every peer client call site would have to remember it; the initializer in §3.2 reads the MDC, so seeding the MDC once covers logging and HTTP together |
| Store the correlation ID on the `reservations` row | Tempting (it would survive a restart), but it is diagnostic data in a business table, and nothing reads it after the saga ends. Rejected as scope creep; the log line already carries it |

---

## 4. Structured Logging (NFR-15, NFR-16, NFR-17)

### 4.1 Encoder choice

**Spring Boot's built-in structured logging** (3.4+, so available on the frozen 3.5.x):

```yaml
logging:
  structured:
    format:
      console: ecs            # one JSON object per line on stdout
  level:
    root: ${LOG_LEVEL:INFO}
    com.crm: ${LOG_LEVEL_APP:INFO}
```

It writes JSON to stdout with MDC entries included, needs **no extra dependency**, and is
configured by one property — which matters because three other owners have to reproduce it.
`logstash-logback-encoder` was rejected for that reason (an extra pinned dependency and a
`logback-spring.xml` to copy in four services); a hand-rolled `String.format` JSON layout was
rejected because it breaks the first time a message contains a quote or a newline.

Logs go to stdout only: no file appender anywhere, which is both NFR-15 and a prerequisite for
Assignment 2.

### 4.2 Mandatory fields on every line

| Field | Source | Why |
|-------|--------|-----|
| `correlationId` | MDC, via the filter (§3.1) or the saga re-seed (§3.3) | NFR-15, NFR-16 |
| `service` | `spring.application.name` = `quota` / `resource`, exported as a static MDC-style field | NFR-15: a line must identify its origin when logs are concatenated |
| `level` | logging framework | NFR-15 |
| `@timestamp` | logging framework, UTC | NFR-22 |
| `logger`, `message` | logging framework | readability |
| `event` | the first token of every application message, e.g. `saga.compensation.start` | lets a script `grep` for an event class instead of prose, which is what AC-2.5 and AC-3.8 need |

A line with no `correlationId` is a defect: it means something logged outside a request and
outside a decorated task. `LogFormatTest` parses captured stdout and fails if the field is
missing on any `com.crm` line.

### 4.3 Log levels per event class

| Event class | Level | Examples |
|-------------|-------|----------|
| Request accepted / completed, state transition, saga step boundary | `INFO` | `provision.accepted`, `resource.status.changed`, `saga.step.start`, `saga.step.done` |
| Idempotency replay served, quota rejection, invalid transition, validation failure | `WARN` | `idempotency.replay`, `quota.rejected`, `api.error` — expected outcomes, but each one a thing a human may want to count |
| Compensation start/finish, peer failure after retries, notification failure, unexpected exception | `ERROR` | `saga.compensation.start`, `peer.failed`, `notification.failed`, `api.error.unexpected` |
| Retry attempt, timing, directive parsing, pool queue depth | `DEBUG` | `peer.retry`, `simulate.directive.ignored` |

Business rejections are `WARN`, not `ERROR`: 15 of 20 requests being rejected in Challenge 1 is
the system working. Reserving `ERROR` for genuine faults keeps the compensation evidence easy to
find in a noisy demo log.

### 4.4 NFR-17: what is never logged

- **No credentials.** The datasource password comes from an environment variable and is never
  logged; Spring's startup banner and the actuator env endpoint are not exposed.
- **No full request or response payload.** The provisioning body is logged as its 64-char
  fingerprint; a stored idempotency response body is never logged, only its status and length.
- **No peer response bodies.** A peer error is logged as status + code + a `detail` truncated to
  200 characters, never as a raw body.
- **Identifiers, counts and outcomes are fine** — they are what makes the trace useful, and
  tenant ids here are synthetic.

### 4.5 Example log lines

Happy path (one correlation ID spanning the synchronous half and the async saga):

```json
{"@timestamp":"2026-10-14T09:12:05.118Z","level":"INFO","service":"resource","correlationId":"b1c2d3e4","logger":"c.c.r.a.ResourceController","message":"provision.received idempotencyKey=0f8c…001 fingerprint=9f2c1a…"}
{"@timestamp":"2026-10-14T09:12:05.141Z","level":"INFO","service":"quota","correlationId":"b1c2d3e4","logger":"c.c.q.d.s.QuotaService","message":"quota.reserved tenantId=tnt-std-001 resourceId=e5f1a2b3 cpu=4 ramGb=8 cpuUsed=20/20"}
{"@timestamp":"2026-10-14T09:12:05.152Z","level":"INFO","service":"resource","correlationId":"b1c2d3e4","logger":"c.c.r.d.s.ProvisioningService","message":"provision.accepted reservationId=3b9a1c2d resourceId=e5f1a2b3 status=202"}
{"@timestamp":"2026-10-14T09:12:05.160Z","level":"INFO","service":"resource","correlationId":"b1c2d3e4","logger":"c.c.r.d.s.ProvisioningSaga","message":"saga.step.start step=2 name=PROVISION resourceId=e5f1a2b3"}
{"@timestamp":"2026-10-14T09:12:07.903Z","level":"INFO","service":"resource","correlationId":"b1c2d3e4","logger":"c.c.r.d.s.ProvisioningSaga","message":"saga.step.done step=2 name=PROVISION instanceId=i-mock-7f3a9c21"}
{"@timestamp":"2026-10-14T09:12:07.961Z","level":"INFO","service":"resource","correlationId":"b1c2d3e4","logger":"c.c.r.d.s.ProvisioningSaga","message":"resource.status.changed from=PROVISIONING to=ACTIVE resourceId=e5f1a2b3"}
```

Compensation path (Challenge 3C, forced billing 503 — reverse order is visible in the lines):

```json
{"@timestamp":"2026-10-14T09:14:06.410Z","level":"DEBUG","service":"resource","correlationId":"9d8c7b6a","logger":"c.c.r.c.BillingClient","message":"peer.retry peer=billing attempt=3 status=503"}
{"@timestamp":"2026-10-14T09:14:06.418Z","level":"ERROR","service":"resource","correlationId":"9d8c7b6a","logger":"c.c.r.c.BillingClient","message":"peer.failed peer=billing code=BILLING_UNAVAILABLE attempts=3 detail=\"simulated failure\""}
{"@timestamp":"2026-10-14T09:14:06.421Z","level":"ERROR","service":"resource","correlationId":"9d8c7b6a","logger":"c.c.r.d.s.ProvisioningSaga","message":"saga.compensation.start failedStep=3 name=START_BILLING resourceId=e5f1a2b3"}
{"@timestamp":"2026-10-14T09:14:06.498Z","level":"INFO","service":"resource","correlationId":"9d8c7b6a","logger":"c.c.r.d.s.ProvisioningSaga","message":"saga.compensation.step order=1 action=PROVIDER_TERMINATE instanceId=i-mock-7f3a9c21 outcome=OK"}
{"@timestamp":"2026-10-14T09:14:06.552Z","level":"INFO","service":"quota","correlationId":"9d8c7b6a","logger":"c.c.q.d.s.QuotaService","message":"quota.released resourceId=e5f1a2b3 released=true cpuUsed=16/20"}
{"@timestamp":"2026-10-14T09:14:06.561Z","level":"INFO","service":"resource","correlationId":"9d8c7b6a","logger":"c.c.r.d.s.ProvisioningSaga","message":"saga.compensation.step order=2 action=QUOTA_RELEASE resourceId=e5f1a2b3 outcome=OK"}
{"@timestamp":"2026-10-14T09:14:06.570Z","level":"ERROR","service":"resource","correlationId":"9d8c7b6a","logger":"c.c.r.d.s.ProvisioningSaga","message":"saga.compensation.done resourceId=e5f1a2b3 status=FAILED reason=\"BILLING_UNAVAILABLE: billing meter start failed after 3 attempts (503)\""}
```

The `order=1 … order=2` fields are what AC-3.3 ("compensation executes in reverse order") is
asserted against — the script greps `saga.compensation.step` and checks the sequence, rather
than a human reading timestamps.

---

## 5. Configuration (NFR-18, NFR-19, NFR-20)

### 5.1 Where defaults live

Every value is read from an environment variable with an inline default, in
`src/main/resources/application.yml`, using `${VAR:default}`. There is **one** profile — the
default one. No `application-local.yml`, no `application-docker.yml`: a profile per environment
is how a service ends up behaving differently in the environment nobody tested.

```yaml
server:
  port: ${SERVER_PORT:8081}
spring:
  application:
    name: quota
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:quota_db}
    username: ${DB_USER:quota_user}
    password: ${DB_PASSWORD:changeme}
    hikari:
      maximum-pool-size: ${DB_POOL_MAX:10}
  jpa:
    hibernate:
      ddl-auto: validate            # NFR-23: Flyway owns the schema
    properties:
      hibernate.jdbc.time_zone: UTC # NFR-22
  flyway:
    enabled: true
    locations: classpath:db/migration
```

### 5.2 Quota service variables

| Variable | Purpose | Default | Required |
|----------|---------|---------|----------|
| `SERVER_PORT` | HTTP port | `8081` | optional |
| `DB_HOST` | PostgreSQL host | `localhost` | optional |
| `DB_PORT` | PostgreSQL port | `5432` | optional |
| `DB_NAME` | database | `quota_db` | optional |
| `DB_USER` | least-privilege user (NFR-2) | `quota_user` | optional |
| `DB_PASSWORD` | that user's password | `changeme` | optional locally, **required** anywhere real |
| `DB_POOL_MAX` | Hikari max pool size | `10` | optional |
| `LOG_LEVEL` | root log level | `INFO` | optional |
| `LOG_LEVEL_APP` | `com.crm` log level | `INFO` | optional |

### 5.3 Orchestrator variables

| Variable | Purpose | Default | Required |
|----------|---------|---------|----------|
| `SERVER_PORT` | HTTP port | `8082` | optional |
| `DB_HOST` / `DB_PORT` | PostgreSQL location | `localhost` / `5432` | optional |
| `DB_NAME` / `DB_USER` / `DB_PASSWORD` | database and credentials | `resource_db` / `resource_user` / `changeme` | optional locally |
| `DB_POOL_MAX` | Hikari max pool size | `10` | optional |
| `LOG_LEVEL` / `LOG_LEVEL_APP` | log levels | `INFO` | optional |
| `QUOTA_SERVICE_URL` | real peer (U1) | `http://localhost:8081` | optional |
| `PROVIDER_SERVICE_URL` | peer; WireMock during U1 | `http://localhost:8085` | optional |
| `BILLING_SERVICE_URL` | peer; WireMock during U1 | `http://localhost:8083` | optional |
| `NOTIFICATION_SERVICE_URL` | peer; WireMock during U1 | `http://localhost:8084` | optional |
| `QUOTA_CONNECT_TIMEOUT_MS` / `QUOTA_READ_TIMEOUT_MS` | NFR-25 | `2000` / `2000` | optional |
| `PROVIDER_CONNECT_TIMEOUT_MS` / `PROVIDER_READ_TIMEOUT_MS` | NFR-25; long read because the provider sleeps 1–3 s (FR-40) | `2000` / `10000` | optional |
| `BILLING_CONNECT_TIMEOUT_MS` / `BILLING_READ_TIMEOUT_MS` | NFR-25 | `2000` / `3000` | optional |
| `NOTIFICATION_CONNECT_TIMEOUT_MS` / `NOTIFICATION_READ_TIMEOUT_MS` | NFR-25 | `2000` / `2000` | optional |
| `RETRY_MAX_ATTEMPTS` | NFR-26 attempt limit (first try included) | `3` | optional |
| `RETRY_INITIAL_BACKOFF_MS` | NFR-26 | `200` | optional |
| `RETRY_MULTIPLIER` | NFR-26 exponential factor | `2.0` | optional |
| `RETRY_MAX_BACKOFF_MS` | NFR-26 cap | `2000` | optional |
| `RETRY_JITTER_RATIO` | NFR-26 jitter, ±50 % of the computed delay | `0.5` | optional |
| `BILLING_RATE_PER_CPU_HOUR` | the rate sent to Billing (FR-28) | `0.12` | optional |
| `SAGA_POOL_CORE` / `SAGA_POOL_MAX` / `SAGA_POOL_QUEUE` | background saga executor | `4` / `8` / `100` | optional |
| `IDEMPOTENCY_RETENTION_HOURS` | record retention (Challenge 2) | `24` | optional |
| `IDEMPOTENCY_SWEEP_INTERVAL_MS` | expired-record sweep period | `3600000` | optional |

### 5.4 Why no code change is needed between local and Docker (NFR-20)

Three properties of the design, no Docker work:

1. **Addressing is configuration, not code.** Peer base URLs and the database host are
   variables; in a container network only their values change (`http://billing:8083`,
   `DB_HOST=postgres`). No class contains a host name.
2. **Logs go to stdout.** No file path to mount, no log rotation to configure; a container
   runtime collects stdout by default.
3. **No local disk or in-memory state.** All state is in the owning database (NFR-21), and
   `/health` lets an orchestrator decide readiness without inside knowledge.

Forward reference: Assignment 2 supplies the same jar with a different environment block, plus
`/health` as the container probe. Nothing in U1 is to be changed then — and nothing about Docker
is being built now.

---

## 6. Flyway Migration Conventions (NFR-23)

| Rule | Detail |
|------|--------|
| Location | `src/main/resources/db/migration` per service |
| Naming | `V<n>__<snake_case_description>.sql`, two underscores. U1 ships `V1__baseline.sql` in each service |
| What V1 contains | The complete schema for that service and nothing else: tables, primary keys, unique constraints, check constraints, indexes, comments. No data |
| Immutability | Once committed and applied, a migration is never edited — Flyway's checksum would fail. A correction is `V2__…`. During U1's own development, before anything is shared, the baseline may be edited and the local database dropped; after hand-off it is frozen |
| No ORM DDL | `spring.jpa.hibernate.ddl-auto: validate` in both services. `validate` is chosen over `none` deliberately: it makes a drifted entity a startup failure rather than a runtime surprise |
| Data | Demo and test data is **not** a migration (see `infrastructure-design.md` §4): a versioned migration cannot be re-run, and the reset script needed by AC-1.5 has to be re-runnable |
| Review | Every constraint in V1 carries an inline comment naming the business rule it enforces (BR1, BR3, BR7), so the migration is itself traceability evidence |

### 6.1 Flyway and the least-privilege user on PostgreSQL 15+

Flyway runs as the application's own user (`quota_user`, `resource_user`) at startup and must
create tables, its own `flyway_schema_history` table, constraints and indexes.

**The draft in `units.md` §9 is not sufficient on PostgreSQL 15 or later.** It grants only:

```sql
GRANT ALL PRIVILEGES ON DATABASE quota_db TO quota_user;
```

Two reasons that fails:

1. `GRANT ALL ON DATABASE` grants exactly three database-level privileges — `CONNECT`,
   `TEMPORARY` and `CREATE` (meaning *create a schema*). It grants nothing **inside** any
   schema, so `CREATE TABLE public.tenants` is still refused.
2. Since PostgreSQL 15, the `public` schema no longer grants `CREATE` to `PUBLIC`; it is owned
   by `pg_database_owner` and ordinary users get `USAGE` only. On PostgreSQL 13/14 the same
   script appeared to work, which is exactly why this bites at the first startup on a current
   server.

The additional grants required, run **while connected to the target database** (schema
privileges are per-database, so a `\connect` is mandatory):

```sql
\connect quota_db
GRANT USAGE, CREATE ON SCHEMA public TO quota_user;
-- or, cleaner for a single-tenant database: make the service user own the schema
ALTER SCHEMA public OWNER TO quota_user;
```

U1 uses `ALTER SCHEMA public OWNER TO <service_user>`: the service user then owns every object
Flyway creates, so no `ALTER DEFAULT PRIVILEGES` is needed and the "one database, one owner"
boundary of NFR-2 is structural rather than a list of grants. The full corrected script, with
`REVOKE` hardening, is in `infrastructure-design.md` §3.

Least privilege is still real: the user is not a superuser, has no `CREATEDB` or `CREATEROLE`,
and cannot connect to any other service's database. "Least privilege" here means *scoped to one
database*, not *unable to create its own tables* — a service that cannot run its own migrations
cannot start.

---

## 7. Health Endpoint (NFR-12)

Spring Boot Actuator, re-based so the path is `/health` rather than `/actuator/health`:

```yaml
management:
  endpoints:
    web:
      base-path: /
      exposure:
        include: health          # health only; no env, no beans, no metrics endpoints exposed
  endpoint:
    health:
      show-components: always    # the db component must be visible (NFR-12)
      show-details: always
```

```json
// 200 OK — GET /health
{ "status": "UP", "components": { "db": { "status": "UP", "details": { "database": "PostgreSQL", "validationQuery": "isValid()" } },
                                  "ping": { "status": "UP" } } }
```

- 200 `UP` when the service is up **and** `DataSource.getConnection().isValid()` succeeds.
- 503 `DOWN` with `components.db.status = DOWN` when the database is unreachable — the
  datasource indicator is contributed automatically by the JDBC starter.
- **Peer reachability is deliberately excluded.** A stopped Billing stub must not make the
  orchestrator report unhealthy; NFR-28 says degrade cleanly, and a restart (which is what a
  `DOWN` probe invites) would not fix a peer outage.
- `/health` is unauthenticated and outside `/api/v1` — recorded in `nfr-requirements.md` §8 (N1)
  as a deliberate deviation from NFR-7's versioning rule.

---

## 8. Resilience Baseline (NFR-25 to NFR-28; decision 3A as baseline, not graded)

Challenge 3C is the graded challenge. These patterns are implemented, described in the report,
and carry **no separate evidence set**.

### 8.1 Timeouts (NFR-25)

| Peer | Connect | Read | Why this value |
|------|---------|------|----------------|
| Quota | 2 000 ms | 2 000 ms | On the synchronous path, inside the transaction that holds the idempotency key (`functional-design.md` §9.2). A tight read timeout bounds how long a concurrent duplicate can block |
| Provider | 2 000 ms | 10 000 ms | The provider sleeps 1–3 s by design (FR-40) and `slow` injection stretches that; a short read timeout would manufacture failures the saga would then compensate for no reason |
| Billing | 2 000 ms | 3 000 ms | Background path, fast operation |
| Notification | 2 000 ms | 2 000 ms | Best effort; a slow notifier must not hold a saga thread |

Set on the request factory, not per call, so no call site can forget. Every value is an
environment variable (§5.3).

### 8.2 Retry with exponential backoff and jitter (NFR-26)

| Parameter | Value | Variable |
|-----------|-------|----------|
| Max attempts (including the first) | 3 | `RETRY_MAX_ATTEMPTS` |
| Initial backoff | 200 ms | `RETRY_INITIAL_BACKOFF_MS` |
| Multiplier | 2.0 → 200, 400 ms | `RETRY_MULTIPLIER` |
| Max backoff | 2 000 ms | `RETRY_MAX_BACKOFF_MS` |
| Jitter | ±50 % of the computed delay, uniform | `RETRY_JITTER_RATIO` |

Retried: connect/read timeouts, connection refused, and HTTP **503**. Not retried: any other
4xx or 5xx — a 422 is a final business answer, and retrying it would just delay the saga's
compensation. Applied to the Provider client (NFR-26 names it), and to Billing and
Notification; the Quota client retries **connect** failures only, because a read timeout there
is ambiguous (the reservation may have been taken) and the ledger, not a retry, is what makes
that safe.

Jitter matters here specifically because Challenge 1 fires 20 simultaneous requests: without it,
20 retries would re-collide in lockstep.

### 8.3 Why retries are safe (NFR-27)

Retries are safe only because of properties established elsewhere, which is the cross-challenge
interaction the report should make explicit:

- **Provider**: every attempt carries the same `ClientToken.of(resourceId)`, so a retry after an
  ambiguous timeout returns the *existing* instance rather than creating a second (FR-19,
  FR-43). Without it, a timed-out-but-successful provision followed by a retry would leak an
  instance the orchestrator does not know about.
- **Billing**: meter start is idempotent on `resource_id` (FR-30), so a retried start cannot
  double-charge.
- **Quota**: reserve and release are idempotent on `resource_id` via the ledger's unique
  constraint and state flip (BR7).
- **The client's own retry** of `POST /api/v1/resources` is safe because of Challenge 2: the
  `Idempotency-Key` makes a repeat return the original 202 with no second reservation
  (`requirements.md` §6.4).

### 8.4 Clean failure (NFR-28)

| Path | Behaviour |
|------|-----------|
| Quota unreachable (synchronous) | After retries: 503 `DOWNSTREAM_UNAVAILABLE` with the correlation ID; the transaction rolls back, so no idempotency record, no resource, no reservation, and the key is reusable |
| Provider or Billing unreachable (async) | After retries: saga compensation (`functional-design.md` §9.5), `resources.status = FAILED`, `failure_reason` naming the peer, reservation `FAILED`, tenant notified |
| Notification unreachable | Logged at `ERROR`, outcome unchanged (FR-33). Never triggers compensation |
| Anything unexpected | Caught by the saga's `catch`, or by the advice's last-resort handler; never a hang, never a 500 with a stack trace in the body |

### 8.5 The circuit breaker is dropped

Stated explicitly because the brief lists it as a bonus: **no circuit breaker is implemented**
(decision F4, `requirements.md` §10). With a single provider instance and a 20 % synthetic
failure rate, a breaker would trip on simulated faults and suppress the very failure path the
Challenge 3C evidence needs; and a half-open probe adds a state machine with no graded return.
Timeouts plus bounded retries plus compensation already keep the system responsive, which is the
property a breaker is meant to protect.

---

## 9. Open Questions and Assumptions

| ID | Issue | Reading taken |
|----|-------|---------------|
| **D1** | `application-design.md` §5.7 lists the `code` vocabulary with "e.g.", so it is illustrative rather than complete | §2.2 here is the complete vocabulary and becomes the single source for all four units. Codes added beyond the examples: `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `TENANT_NOT_FOUND`, `RESOURCE_NOT_FOUND`, `RESERVATION_NOT_FOUND`, `QUOTA_RESERVATION_NOT_FOUND`, `TENANT_ALREADY_EXISTS`, `QUOTA_RESERVATION_ALREADY_RELEASED`, `INVALID_TIER`, `TIER_FORBIDS_RESOURCE_TYPE`, `BILLING_UNAVAILABLE`, `NOTIFICATION_UNAVAILABLE`, `SIMULATED_FAILURE`, `METER_NOT_FOUND`, `INSTANCE_NOT_FOUND`, `METHOD_NOT_ALLOWED`, `INTERNAL_ERROR`. None contradicts the frozen examples |
| **D2** | NFR-15 requires `service` on every line, but Spring Boot's ECS format emits `service.name` from `spring.application.name` | Treated as satisfied: the field is present and carries the service name. A reader asserting the literal key `service` should read `service.name` — noted so the Build and Test stage asserts the right key |
| **D3** | `units.md` §9's grant script is insufficient on PostgreSQL 15+ | Corrected in §6.1 and in `infrastructure-design.md` §3, with the reason stated. The inception document itself is **not** edited (it is approved and frozen); the correction lives here |
| **D4** | Nothing in the frozen inputs fixes the saga executor's size or queue depth | `SAGA_POOL_CORE=4`, `SAGA_POOL_MAX=8`, `SAGA_POOL_QUEUE=100`, all configurable. Enough for 20 parallel Challenge 1 requests, of which at most 5 proceed to a saga |
| **D5** | `requirements.md` §5.4 does not say who deletes expired idempotency records | A `@Scheduled` sweep on the orchestrator, hourly by default (`IDEMPOTENCY_SWEEP_INTERVAL_MS`), deleting rows past `expires_at`. It is a housekeeping job, not business state, so it needs no coordination; a double run is harmless |

---

## 10. Stage Acceptance

This stage is complete when approved. Specifically:

- The validation strategy, the 400 body and the mapping from each Spring exception into the
  RFC 9457 structure are fixed (section 1).
- The error model is fully specified: the exception hierarchy, one `@RestControllerAdvice`, one
  construction point for problem bodies, the **complete** `code` → status → meaning → emitting
  service vocabulary, and a worked example body per error class (section 2).
- Correlation-ID handling is specified inbound, outbound and **across the async boundary**, with
  both the MDC task decorator and the task-payload field, and with the reason both exist
  (section 3).
- Structured JSON logging is specified: encoder choice, mandatory fields, levels per event class,
  the NFR-17 prohibitions, and example happy-path and compensation-path lines (section 4).
- Every environment variable for both services is listed with its default and where defaults
  live, with the NFR-20 argument for why no code changes between local and container (section 5).
- Flyway conventions are fixed, including the PostgreSQL 15+ privilege correction and why the
  `units.md` draft is insufficient (section 6).
- `/health` content and the deliberate exclusion of peer health are fixed (section 7).
- The resilience baseline is fixed with concrete timeout, retry and jitter values, the safety
  argument for retries, and the explicit statement that the circuit breaker is dropped (section 8).

**Next stage for U1**: `infrastructure-design.md` — the two Flyway baselines, seed data, the
corrected PostgreSQL setup script, environment-variable tables, local run order, WireMock stubs
and the Testcontainers configuration.




