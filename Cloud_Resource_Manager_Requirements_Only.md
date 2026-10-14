# Cloud Resource Manager — Requirements Document
## Assignment 1: Microservices Design and Development
## SEZG583 Scalable Services | BITS Pilani MTech WILP

---

## 1. Assignment Context

This is **Assignment 1 of 2** for the Scalable Services course (SEZG583). 

- **Assignment 1 (13 marks):** Design and build a microservices application
- **Assignment 2 (7 marks):** Containerize the same application using Docker and deploy on Minikube

**Team size:** Maximum 4 students  
**Duration:** 3 weeks  
**Deliverables:** 
- GitHub repository with source code
- Design report (PDF, max 15 pages)
- Demo video (8-10 minutes)
- Evidence folder with test outputs

---

## 2. Mandatory Application Conditions

The application MUST satisfy **ALL FIVE** of these conditions:

### Condition A: Service Count
- At least **4 services** with distinct business responsibilities
- More services are allowed but don't split artificially
- Each service must have its own folder, dependency file, and database

### Condition B: Cross-Service Transaction
- At least **one business transaction** that spans **3 or more services**
- Example: place order → reserve stock → charge payment → notify user (4 services)

### Condition C: Contended Resource
- At least **one contended resource** that can be **over-allocated** if not protected
- Examples: theater seats, stock inventory, appointment slots, account balance, vehicle availability

### Condition D: Unsafe-to-Repeat Operation
- At least **one operation that is unsafe to repeat**
- Examples: payment processing, fund transfer, order placement
- Repeating must NOT cause duplicate effects

### Condition E: Unreliable Dependency
- At least **one dependency that can be slow or fail**
- Examples: payment gateway, SMS/email provider, external API
- Must be simulated with your own mock service (not a real external service)

**NOT ACCEPTABLE:** 
- Simple CRUD apps (to-do lists, blogs, student records)
- Services that merely wrap database tables
- Copies of tutorial projects

---

## 3. Mandatory Technical Requirements

### Services Architecture
- Minimum 4 independently runnable services
- Each service has its own folder and `pom.xml` (Maven) or equivalent
- Each service must be runnable independently

### Communication
- **REST over HTTP only** for inter-service communication
- Services interact **only through APIs**
- Services **NEVER read another service's database**

### Data Management
- **Database-per-service pattern** (mandatory)
- No shared tables between services
- No cross-service joins or foreign keys
- Services can only store IDs of entities from other services
- Separate databases with separate credentials are required

### API Quality
- All endpoints must use versioned paths: `/api/v1/...`
- Input validation on all requests
- Correct HTTP status codes (200, 201, 400, 404, 422, 503, etc.)
- Consistent JSON error format across all services
- OpenAPI/Swagger specification for each service

### Operability
- Every service must expose `GET /health` endpoint
- All configuration via environment variables (ports, URLs, DB connection strings)
- Structured logging with correlation IDs passed between services
- Configuration must allow running locally AND in Docker without code changes

### Business Rules
- Define at least **4 business rules** (BR1, BR2, BR3, BR4)
- Each rule must be traceable to:
  - Code implementation
  - Unit test that validates it

### Testing
- **One unit test per core business rule**
- **One scripted end-to-end test** of the main transaction
- Tests must be repeatable and documented

### Technology Stack
- **Language:** Java 17+
- **Framework:** Spring Boot 3.x (or any modern framework)
- Payment and notification systems must be **your own mocks** (no real integrations)

### Out of Scope
- User authentication (skip it)
- User interface (command-line or API testing tools are sufficient)
- Real payment gateway integration
- Real email/SMS providers

---

## 4. Engineering Challenges

You must implement **ALL THREE** of these challenges:

### Challenge 1: Concurrency Control (MANDATORY)
**Problem:** Simultaneous requests for the same contended resource must never over-allocate it.

**Requirements:**
- Use a proper mechanism: row locks, optimistic locking with version column, atomic conditional updates, or unique constraints
- A plain "check-then-update" in application code is NOT acceptable

**Minimum to pass:**
- A script that fires at least 20 parallel requests for a resource with capacity N (e.g., N=1 or N=5)
- Exactly N requests succeed
- Remaining requests are rejected with meaningful error messages
- Database state is consistent after the test

**Evidence required:**
- The test script
- Output showing success/failure counts
- Final database state
- Short explanation of why plain check-then-update would fail

---

### Challenge 2: Idempotency (MANDATORY)
**Problem:** Repeating an unsafe request with the same identifier must return the same result and cause no duplicate effect.

**Requirements:**
- Requests must include an `Idempotency-Key` header
- Store the key with a fingerprint (hash) of the request
- If same key arrives again:
  - With same payload → return cached response (no re-processing)
  - With different payload → reject with 409 Conflict or 422 Unprocessable Entity
- Must work for concurrent requests (two identical requests at the same moment)

**Minimum to pass:**
- Sequential duplicate requests return same result
- Concurrent duplicate requests handled correctly (database constraint prevents double-insert)
- Database shows exactly one record

**Evidence required:**
- Output of first call vs repeated calls
- Logs showing request replay detection
- Database showing single record
- Proof that concurrent duplicates are handled

---

### Challenge 3: Choose ONE of These

#### Option 3A: Resilience Patterns
**Requirements:**
- Implement timeout configuration
- Implement retry with exponential backoff and jitter
- Graceful failure when a downstream service is stopped
- Retries must not cause duplicates (links to Challenge 2)
- **Bonus:** Circuit breaker pattern

**Evidence:** Show service stopping and recovery, logs showing retries, proof of no duplicates

---

#### Option 3B: Time-Bound Resource Holds
**Requirements:**
- Resources held temporarily are released automatically after a configurable time (use ~30 seconds for demo)
- Manual cancellation also releases the resource
- Expiry job must be safe to run multiple times (idempotent)
- Expiry job must never release a resource that was already confirmed/paid

**Evidence:** Resource held → timeout expires → resource auto-released, logs showing cleanup

---

#### Option 3C: Saga with Compensation
**Requirements:**
- Multi-service transaction with explicit states (orchestrated or choreographed)
- Force a failure at step 3 of a 4-step saga
- Compensating actions undo steps 1 and 2 in reverse order
- System ends in a consistent state (no orphaned data)

**Evidence:** Saga state transitions in database, compensation execution, final consistent state

---

#### Option 3D: Event-Driven Flow
**Requirements:**
- Use a message broker (RabbitMQ, Kafka, Redis Streams) for at least one flow
- Duplicate messages handled safely
- Consumer that was down can catch up when restarted
- Note: Broker must be containerized in Assignment 2

**Evidence:** Message published → consumed → duplicate sent → ignored, consumer restart → catches up

---

## 5. What to Build: Problem Domain

### Domain Choice
Pick a domain you understand well. Your own work domain is fine, but **use no confidential data**.

The domain must naturally produce the required distributed system challenges (concurrency, idempotency, failure handling).

### Example Domains (for inspiration, not mandatory)

#### E-Commerce Checkout
- **Services:** Catalog, Inventory, Order, Payment, Notification
- **Contended resource:** Stock count
- **Cross-service flow:** Create order → reserve stock → charge payment → confirm → ship notification
- **Unsafe operation:** Payment charge

#### Hospital Appointment System
- **Services:** Doctor/Schedule, Appointment, Billing, Notification
- **Contended resource:** Doctor time slots
- **Cross-service flow:** Book slot → verify availability → confirm → charge → notify
- **Unsafe operation:** Slot booking

#### Food Delivery Platform
- **Services:** Restaurant/Menu, Order, Payment, Delivery, Notification
- **Contended resource:** Rider availability, restaurant item stock
- **Cross-service flow:** Place order → validate items → charge → assign rider → notify

#### Hotel Booking
- **Services:** Inventory, Reservation, Payment, Notification
- **Contended resource:** Room availability per date
- **Cross-service flow:** Hold room → validate dates → charge → confirm → notify

#### Wallet/Transfer System
- **Services:** Account, Transfer, Ledger, Fraud-check, Notification
- **Contended resource:** Account balance
- **Cross-service flow:** Validate balance → debit sender → credit receiver → ledger entry → notify

**You are NOT limited to these examples.** Choose any domain that meets the five mandatory conditions.

---

## 6. Report Structure (15 pages max, excluding appendices)

Your design report must include:

1. **Problem and Scope**
   - Overview of the application
   - Assumptions made
   - Business rules (BR1, BR2, BR3, BR4, ...)
   - What is explicitly out of scope

2. **Service Decomposition**
   - Table of services with responsibilities
   - Why these boundaries were chosen
   - Alternatives considered and rejected
   - What each service owns (data ownership)

3. **Architecture**
   - Component diagram showing all services
   - Sequence diagram of the main transaction (happy path + one failure path)

4. **Data Design**
   - ER diagram per service
   - Data ownership table
   - How cross-service references are handled (store IDs, never foreign keys across services)

5. **API Design**
   - Table of endpoints per service
   - Sample request and response JSON
   - Error response model
   - Links to OpenAPI specification files

6. **Engineering Challenges**
   - For each challenge (1, 2, and your chosen challenge 3):
     - Problem description
     - Design choice made
     - Alternatives considered
     - Evidence of correctness
     - Known limitations

7. **Testing Strategy and Results**
   - Unit test approach
   - Integration test approach
   - Results summary

8. **Design Decisions and Trade-offs**
   - At least 3 significant decisions with:
     - Options considered
     - Trade-offs analyzed
     - Rationale for final choice
   - Examples: sync vs async, orchestration vs choreography, optimistic vs pessimistic locking

9. **Limitations and Future Work**
   - What wasn't implemented
   - Known issues
   - What you would improve with more time

10. **Links**
    - Link to Google Drive with video and other large files

**Appendices (not counted in 15 pages):**
- Contribution table (who did what)
- Individual reflection (half-page per student: what you built, hardest problem, what you'd change)
- AI-use declaration (which tools, for what purpose)

---

## 7. Demo Video (8-10 minutes)

**Structure:**
- **1 minute:** Team introduction, application overview, who owned which service
- **2 minutes:** Architecture explanation and decomposition rationale
- **3 minutes:** Live demo of main flow including one failure case (terminal output, not slides)
- **3 minutes:** Run challenge scripts live and explain results

**Requirements:**
- Every team member must speak
- Show real terminal execution (API calls, database queries, logs)
- Show both success and failure scenarios

---

## 8. Quality Guidelines

### Design Principles
- Split by **business capability**, not technical layer
- No "controller service" or "database service"
- Each service explainable in one sentence
- If two services always change together → probably a distributed monolith, consider merging
- Avoid long synchronous chains (A → B → C → D) in one request
- Make state machines explicit (PENDING → CONFIRMED → COMPLETED → CANCELLED)

### Code Quality
- Separate API layer, business logic layer, and data access layer within each service
- Keep business rules in one place and unit-test them
- README must let a stranger run everything

### Testing and Evidence
- Show failures as well as successes
- Screenshots must show: command, output, timestamp
- Include raw logs as text files
- Evidence that shows only happy paths earns minimal marks

### Report Quality
- Draw diagrams yourself (draw.io, Mermaid, PlantUML)
- Label arrows with direction and protocol
- Explain **WHY**, not just **what**
- Marks go to reasoning and trade-off analysis

### Common Mistakes That Lose Marks
- Shared database or cross-service table reads
- Microservices that are just CRUD wrappers with no business logic
- Concurrency "handled" by check-then-update with no atomicity
- Idempotency tested only with sequential repeats (not concurrent)
- Evidence showing only success paths
- Code copied without understanding
- Repository that doesn't run from its own README

---

## 9. Evaluation Criteria (13 marks total)

| Criterion | Marks | What We Assess |
|-----------|-------|----------------|
| **Problem and Decomposition** | 2 | Meaningful application, sound service boundaries, clear data ownership, justified decisions |
| **Architecture and API Design** | 2 | Clear diagrams, communication choices, OpenAPI quality, error handling, state machines |
| **Implementation Quality** | 3 | Working services, business rules enforced, validation, environment config, tests, code hygiene |
| **Engineering Challenges** | 3 | Challenges 1, 2, and chosen 3: correctness, repeatability, honest analysis of limitations |
| **Report, Video, Evidence** | 3 | Clarity, completeness, traceability of claims to evidence |

**Individual marks:** Team mark × individual factor (0 to 1) based on:
- Git commit history
- Contribution table
- Demo video participation

---

## 10. Academic Integrity

- AI tools may be used for learning and boilerplate code generation
- AI usage must be declared in report appendix
- Submitting code you cannot explain in the demo is an academic integrity violation
- Late submission and plagiarism follow institute policy

---

## 11. Pre-Submission Checklist

- [ ] Application meets all 5 mandatory conditions (A through E)
- [ ] At least 4 services, each with own database
- [ ] No service reads another's database
- [ ] All endpoints versioned (`/api/v1/...`)
- [ ] Every service has `/health` endpoint
- [ ] All config via environment variables
- [ ] OpenAPI spec available for each service
- [ ] At least 4 business rules documented and tested
- [ ] Challenge 1 script + evidence
- [ ] Challenge 2 script + evidence
- [ ] Challenge 3 (chosen option) script + evidence
- [ ] At least one failure case shown per challenge
- [ ] Report has all 10 sections
- [ ] Report includes architecture and sequence diagrams
- [ ] Report includes ER diagram per service
- [ ] Report includes at least 3 design trade-offs
- [ ] Video is 8-10 minutes
- [ ] Video shows live execution (not just slides)
- [ ] Every team member speaks in video
- [ ] Contribution table in appendix
- [ ] Individual reflections in appendix
- [ ] AI-use declaration in appendix
- [ ] Fresh clone runs from README alone

---

## 12. Link to Assignment 2

**Important:** The same application will be containerized and deployed on Docker and Minikube in Assignment 2.

**Design with this in mind:**
- Use environment variables for all configuration
- Implement `/health` endpoints
- Log to stdout (not files)
- Make services stateless (no in-memory state that can't be lost)

---

## 13. Suggested 3-Week Timeline

### Week 1: Design and Setup
- Pick domain
- Define services, business rules, data ownership
- Create ER diagrams and API design drafts
- Set up GitHub repository
- Create service skeletons with `/health` endpoints

**Checkpoint:** Take guidance from instructor if needed

### Week 2: Implementation
- Implement services and main transaction flow
- Build Challenges 1 and 2 with test scripts
- Capture evidence

**Checkpoint:** Main flow works end-to-end

### Week 3: Challenge 3, Testing, Documentation
- Implement chosen Challenge 3
- Write all tests
- Capture all evidence
- Write report
- Record video
- Test from fresh clone on another team member's machine

**Checkpoint:** Final submission ready

---

*This is a requirements document only. Design and implementation decisions are left to the development team (you and your AI-assisted development workflow).*

*Version: 1.0 | Course: SEZG583 Scalable Services | BITS Pilani WILP*


---

# PART B: PROJECT BRIEF — WHAT WE'RE BUILDING

---

## Project: Cloud Resource Manager Platform

### Problem Statement

Organizations need to rent compute resources (virtual machines, containers, clusters) on demand from cloud infrastructure providers. However, they need a management layer that:

1. **Tracks their resource usage** against allocated quotas
2. **Prevents over-provisioning** (using more than allowed)
3. **Handles failures gracefully** when infrastructure providers are slow or unavailable
4. **Prevents duplicate charges** when requests are retried
5. **Tracks costs** and generates usage reports

**Think of it as:** A simplified version of AWS/Azure resource management system, or Databricks cluster management interface.

---

## User Persona

**Primary User:** DevOps Engineer or IT Manager at a company

**User Goals:**
- Provision compute resources (VMs, containers) on demand
- Stay within allocated resource quotas
- Track resource costs
- Receive notifications about resource lifecycle events

---

## Core Business Operations

### Operation 1: Reserve and Provision Compute Resources
**Trigger:** User submits a reservation request  
**Input:** Tenant ID, resource type (VM/Container/Cluster), CPU cores, RAM, duration  
**Process:** 
- Check if tenant has sufficient quota
- Deduct quota from available allocation
- Provision the resource via infrastructure provider
- Start billing meter
- Send confirmation notification

**Output:** Reservation confirmed with resource details

**What can go wrong:**
- Insufficient quota → Reject with error
- Infrastructure provider fails → Compensate (refund quota)
- Concurrent requests → Risk of quota over-allocation
- Duplicate request → Must not create duplicate resources

---

### Operation 2: Terminate Resources
**Trigger:** User requests resource termination OR resource expires  
**Input:** Resource ID  
**Process:**
- Mark resource as terminated
- Stop billing meter
- Refund quota back to tenant
- Send notification

**Output:** Resource terminated, quota restored

---

### Operation 3: View Usage and Billing
**Trigger:** User wants to see current usage  
**Input:** Tenant ID  
**Output:** 
- Current quota usage (X out of Y CPUs used)
- Active resources list
- Current month's cost

---

## Business Constraints

### Quota Rules
- Each tenant (organization) has quota limits based on their subscription tier:
  - **FREE tier:** Small quota (e.g., 2 CPUs, 4GB RAM, 1 VM max)
  - **STANDARD tier:** Medium quota (e.g., 20 CPUs, 64GB RAM, 10 VMs max)
  - **ENTERPRISE tier:** Large quota (e.g., 200 CPUs, 512GB RAM, 100 VMs max)

- **Quota must be enforced strictly** — tenant cannot exceed their limits
- **Quota tracking must be accurate** even with concurrent requests
- Quota is "reserved" when resource is provisioned, "released" when terminated

### Cost Tracking
- Each resource has an hourly billing rate based on CPU cores
- Billing meter starts when resource becomes ACTIVE
- Billing meter stops when resource is TERMINATED
- Monthly invoice summarizes all usage

### Resource Lifecycle
- Resources go through states: REQUESTED → PROVISIONING → ACTIVE → STOPPING → STOPPED → TERMINATED
- Only valid state transitions are allowed
- Resources can have optional expiry time (auto-terminate after duration)

---

## Technical Constraints

### Infrastructure Provider
- We need to call an external infrastructure provider API to actually provision VMs/containers
- **For this assignment:** Simulate with a mock service that:
  - Has random delays (1-3 seconds)
  - Randomly fails (20% failure rate)
  - Returns a fake instance ID on success

### Idempotency Requirement
- Resource provisioning requests must be idempotent
- If the same request is sent twice (network retry), only one resource is created
- Use a unique request identifier (`Idempotency-Key`) to detect duplicates

### Concurrency Requirement
- Multiple users from the same organization can submit requests simultaneously
- System must prevent quota over-allocation using proper locking mechanisms
- Database must remain consistent

### Failure Handling
- If any step in the provisioning process fails, all previous steps must be undone
- Example: If billing fails, the provisioned resource must be terminated and quota refunded
- This is a distributed transaction problem (saga pattern)

---

## What's Out of Scope

- ❌ User authentication and authorization
- ❌ User interface (UI)
- ❌ Real integration with AWS/Azure/GCP
- ❌ Real payment processing
- ❌ Real email/SMS sending
- ❌ Complex pricing models (flat hourly rate is sufficient)
- ❌ Resource migration between providers
- ❌ Auto-scaling or load balancing

---

## Success Criteria

The system is successful if:

1. ✅ **Quota enforcement works:** 20 concurrent requests for limited quota → only allowed number succeed
2. ✅ **Idempotency works:** Same request sent 10 times → only 1 resource created
3. ✅ **Failure recovery works:** If provisioning fails mid-way → system compensates and returns to consistent state
4. ✅ **Services are independent:** Each service has its own database, communicates only via REST APIs
5. ✅ **System is observable:** Logs with correlation IDs allow tracing requests across services
6. ✅ **System is Docker-ready:** All configuration via environment variables, health checks implemented

---

## Key Distributed Systems Challenges We're Solving

| Challenge | Real-World Example | How We Solve It |
|-----------|-------------------|-----------------|
| **Race Conditions** | Two users book the last seat simultaneously | Optimistic locking with version numbers |
| **Duplicate Requests** | User clicks "Pay" twice due to slow response | Idempotency keys with request fingerprints |
| **Partial Failures** | Payment succeeds but order creation fails | Saga pattern with compensating transactions |
| **Cascading Failures** | Infrastructure provider down, affects all services | Timeouts, retries, circuit breakers (optional) |
| **Data Consistency** | Each service has own DB, no distributed transactions | Eventual consistency via saga orchestration |

---

## Example User Journey

### Happy Path: Create a VM

```
1. User (DevOps Engineer at Acme Corp):
   "I need a VM with 4 CPUs and 8GB RAM for 2 hours"

2. System checks:
   - Acme Corp has STANDARD tier quota: 20 CPUs, 64GB RAM
   - Currently using: 10 CPUs, 32GB RAM
   - Available: 10 CPUs, 32GB RAM
   - Requested: 4 CPUs, 8GB RAM
   - ✅ Approved

3. System deducts quota:
   - New usage: 14 CPUs, 40GB RAM
   - New available: 6 CPUs, 24GB RAM

4. System provisions VM:
   - Calls infrastructure provider (simulated)
   - Receives instance ID: "i-mock-abc123"
   - Resource state: ACTIVE

5. System starts billing:
   - Meter started at 10:30 AM
   - Rate: $0.48/hour (4 CPUs × $0.12/CPU)

6. System sends notification:
   - "Your VM res-xyz-789 is ready (i-mock-abc123)"

7. User receives:
   - Reservation ID: res-req-999
   - Resource ID: res-xyz-789
   - Status: COMPLETED
```

### Failure Path: Infrastructure Provider Down

```
1. User requests VM (same as above)

2. Quota deducted: ✅
   - Usage: 14 CPUs, 40GB RAM

3. Provision VM: ❌ FAILED
   - Infrastructure provider returns 503 Service Unavailable

4. System detects failure → Compensation:
   - Refund quota: 14 CPUs → 10 CPUs (back to original)
   - Mark saga as COMPENSATED

5. User receives:
   - Status: FAILED
   - Message: "Infrastructure unavailable, please retry later"
   - Quota: Fully refunded (10 CPUs, 32GB RAM available)
```

---

## Business Rules to Implement

You must define and implement at least 4 business rules. Here are suggestions:

- **BR1:** Tenant quota usage must never exceed defined limits
- **BR2:** Resource provisioning is idempotent (same key = same result)
- **BR3:** Idempotency key reused with different payload must be rejected
- **BR4:** Resource can only transition to ACTIVE from PROVISIONING state
- **BR5:** Billing meter must stop when resource is terminated
- **BR6:** Saga compensation must refund quota if provisioning fails after deduction

(Feel free to define your own business rules based on your design)

---

## Questions to Guide Your Design

When working with AIDLC or designing the system, consider:

### Service Decomposition
- What are the distinct business capabilities?
- Should quota management be separate from tenant management?
- Should billing be its own service?
- Do we need a separate orchestration service?

### Data Ownership
- Who owns quota data?
- Who owns resource lifecycle data?
- Who owns billing data?
- Can services share tables?

### API Design
- What operations does each service expose?
- Should quota deduction be POST or PATCH?
- What should error responses look like?
- How do we version our APIs?

### Concurrency
- How do we prevent quota over-allocation?
- Optimistic or pessimistic locking?
- Where do we need transactions?

### Failure Handling
- What if a service is down?
- What if infrastructure provider is slow?
- How do we ensure data consistency across services?
- Do we use orchestration or choreography for sagas?

---

**This is the problem you're solving. Now design and build the solution!**

---

*End of Requirements Document*
