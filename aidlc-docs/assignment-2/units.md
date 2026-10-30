# Assignment 2 — Containerization & Deployment: Work Units

Base application: the 7-service Cloud Resource Manager from Assignment 1.
**Total 12 marks** = four 3-mark buckets, one owner each. Everyone also writes the
Dockerfile for the service(s) they already own, their own report contribution, and
presents their own demo segment.

| Bucket | Marks | Owner |
|---|---|---|
| Task 1 — Containerization | 3 | **Prajwal** |
| Task 2 — Docker Compose deployment | 3 | **Vinayak** |
| Task 3 — Troubleshooting (situated learning) | 3 | **Karan** |
| Part B — Advanced + workflow verification | 3 | **Siddharth** |

Service ownership (unchanged from A1): quota 8081 + resource 8082 (Prajwal),
billing-service 8083 (Karan), provider 8085 (Vinayak), notification 8084 +
usage-aggregator 8086 + expiry-worker 8087 (Siddharth).

---

## Sequence & dependencies (read this first)

```
Prajwal (Dockerfiles + CONTRACT)  →  Vinayak (Compose up)  →  Karan + Siddharth
```

- **Prajwal publishes the CONTRACT on day 1** (service names, ports, env-var names and
  their in-container values) BEFORE finishing all Dockerfiles. That one document lets
  everyone prep in parallel.
- Vinayak's Compose needs Prajwal's images + contract.
- Karan (troubleshooting) and Siddharth (workflow/advanced) need the Compose stack
  **running**, i.e. they depend on Vinayak.

**What each can do in parallel before Compose is up:** Prajwal builds the 7 images and
verifies each with `docker run`; Vinayak drafts the compose skeleton against the contract;
Karan drafts his diagnosis plan + reflection outline and reads the resource peer-URL
config; Siddharth writes K8s manifests + Prometheus config from the contract.

---

## Prajwal — Task 1: Containerization (3 marks)

**Goal:** a reproducible, secret-free container image for every service.

**What to deliver**
- A **multi-stage Dockerfile** per service (Maven+JDK build stage → `eclipse-temurin:21-jre`
  runtime stage). Write the 7 Dockerfiles (coordinate; others may adapt the template for
  their own service).
- Publish the **day-1 CONTRACT**: service name = in-container hostname, each port, each
  env var with its in-container value (e.g. `PROVIDER_SERVICE_URL=http://provider:8085`,
  `DB_HOST=postgres`), the one-Postgres/five-DB setup, jar-path convention.
- `.env.example` with placeholder values only.

**Full marks checklist**
- [ ] One Dockerfile per service; each builds a **separate image** successfully.
- [ ] All config (DB URLs, peer URLs, ports) comes from **environment variables** — nothing hardcoded.
- [ ] No passwords, no real `.env`, no secrets in images or committed to git.
- [ ] Required database runs as a **container** (Postgres image).
- [ ] Evidence captured: images building (`docker build`, `docker images`), containers starting, all services running.
- [ ] You can explain every line of a Dockerfile.

**Tools / concepts**
- Multi-stage build keeps the final image small and lets `docker compose up --build`
  compile from source (no pre-built jar needed).
- Jar-name trap: U4 services use `<finalName>` (e.g. `notification.jar`), not
  `<artifactId>-<version>.jar`. Use `COPY --from=build /app/target/*.jar app.jar`.

---

## Vinayak — Task 2: Docker Compose deployment (3 marks)

**Goal:** the whole system comes up reproducibly with one command.

**What to deliver**
- `docker-compose.yml` starting all 7 services + 1 Postgres on a shared network.
- Postgres init that creates all 5 DBs + users (reuse `scripts/db/setup-u1.sql` +
  `setup-u2-u4.sql`), and a **named volume** so data survives restarts.
- Env wiring from the contract (peer URLs point at service names, not localhost).
- Healthchecks + `depends_on: condition: service_healthy`.

**Full marks checklist**
- [ ] `docker compose up --build` from a clean checkout brings up the **complete** app.
- [ ] All services + Postgres show running (`docker compose ps`).
- [ ] **Inter-service communication demonstrated** (saga reaches the peers by service name).
- [ ] **Main workflow demonstrated successfully** (provision → saga → ACTIVE) through Compose.
- [ ] Container logs and/or health status shown.
- [ ] **Stop and restart** (`docker compose down` → `up`) and verify it works again (volume persists the DBs).
- [ ] You can explain the Compose file.

**Tools / concepts**
- Compose on `up`: builds images → creates the network → creates volumes → starts
  containers in dependency order → injects env. Containers reach each other by **service
  name** (`http://provider:8085`), which is what replaces `localhost`.
- `depends_on` alone only waits for a container to *start*, not to be *ready* — use the
  healthcheck condition so apps don't boot before Postgres accepts connections.

---

## Karan — Task 3: Troubleshooting / situated learning (3 marks)

**Goal:** demonstrate one **genuine** deployment problem you hit, diagnosed and fixed.

**Full marks checklist (this is the exact rubric)**
- [ ] **Problem (0.5):** clearly describe the deployment problem and the observed symptom.
- [ ] **Diagnosis (1.0):** show the commands, logs, health checks, or other evidence used to find the cause.
- [ ] **Solution + validation (1.0):** explain the change made and demonstrate the app works after the fix.
- [ ] **Learning reflection (0.5):** what it taught you about containerized deployment and how you'd apply it in a real deployment.
- [ ] The problem must be **real** (encountered during the assignment), not invented.

**Your two likely genuine problems — pick the one you actually hit**
1. **`localhost` peer URLs.** The resource saga calls billing/provider/notification at
   `localhost` — but inside a container `localhost` is the container itself, so the calls
   fail ("works locally, fails in containers"). Symptom: "Connection refused" in
   `docker compose logs resource`. Fix: env vars pointing at service names. (Config lives
   in Prajwal's resource service — pair with him.)
2. **Postgres not ready at startup.** A service boots before Postgres accepts connections
   → Hikari/Flyway timeout, container keeps restarting. Fix: Postgres healthcheck +
   `depends_on: condition: service_healthy`.

**Diagnosis toolkit (use these for evidence)**
```
docker compose ps            # states: Up / Exited / unhealthy / restarting
docker compose logs <svc>    # the stack trace / "Connection refused"
docker inspect <container>   # env vars, network, health, exit code, restart count
docker network inspect <net> # who is on the network, their IPs
docker compose exec <svc> sh # get inside; curl http://provider:8085/health
```

**Demo segment:** show it broken, diagnose live with the commands above, apply the fix,
show the workflow succeeding.

---

## Siddharth — Part B: Advanced + workflow verification (3 marks)

**Core anchor (always counts, even if advanced slips):** demonstrate the **main workflow
runs through the deployed stack** — provision → saga → ACTIVE across the containers, with
captured evidence. The handout stresses this as a minimum expectation.

**Advanced (the 3 marks — pick to total 3; working evidence required)**
- [ ] **Minikube / Kubernetes base deployment (2):** Deployments + Services for the app;
      the **main workflow must work *through* K8s** (pods merely Running is NOT enough).
      Needs ConfigMap (URLs/ports), Secret (DB password), a PVC for Postgres, and an entry
      point exposed via `minikube service resource` or `kubectl port-forward`.
- [ ] **+ one of (1 mark each):** self-healing/scaling (`kubectl delete pod` recovers;
      `kubectl scale`), observability (Prometheus scraping `/actuator/prometheus` + Grafana),
      rolling update + rollback (`kubectl set image` / `rollout undo`), HPA autoscaling, or
      distributed tracing (OpenTelemetry + Jaeger).

**Full marks checklist**
- [ ] Main workflow verified inside the deployment (not just screenshots of running pods/containers).
- [ ] The chosen advanced capability is demonstrated with **working evidence**.
- [ ] You can explain the K8s / observability configuration.

**Tools / concepts**
- K8s objects map to your app: **Pod** (one container), **Deployment** (keeps N replicas +
  self-healing + rolling updates), **Service** (stable name + load-balances to Pods — the
  K8s version of Compose service-DNS), **ConfigMap/Secret** (env/secrets), **PVC** (Postgres
  storage). The reconciliation loop continuously heals drift.
- `kubectl apply -f k8s/`, `kubectl get pods,deploy,svc`, `kubectl logs`, `kubectl describe`.
- **Memory warning:** Minikube + 7 Java Deployments + Postgres is brutal on a 6 GB machine.
  Keep `replicas: 1`, set `JAVA_TOOL_OPTIONS=-Xmx256m`, and demo scaling/self-healing on one
  light service (provider) rather than all seven. Do Compose first; Part B is optional.

---

## Shared by everyone (needed for individual marks)

- Write the Dockerfile for the service(s) you own (Task 1 base labor, parallel).
- Write your own **individual contribution summary + reflection** in the report.
- Present your own **demo segment** in the 6–8 minute video.
- Be able to **explain** your part on the spot — the handout requires every member to
  explain their Dockerfiles, Compose config, troubleshooting evidence, or advanced config.

## Deliverables (team)

- **GitHub repo:** source, all Dockerfiles, `docker-compose.yml`, `.env.example`
  (placeholders only), README with build/deploy steps, K8s/observability configs only if
  advanced attempted.
- **Report (≤5 pages):** overview + container/deployment architecture diagram;
  containerization + Compose; the situated-learning problem (symptom/diagnosis/fix/
  validation/learning); advanced task + evidence if attempted; **individual contribution
  summary + AI-use declaration**.
- **Demo video (6–8 min):** build + deploy, running services, successful main workflow,
  the problem + diagnosis + fix, advanced demo if attempted.

## Decisions locked up front (avoid rework)
1. Multi-stage Dockerfiles that build from source (the handout runs `docker compose up --build`).
2. `COPY --from=build /app/target/*.jar app.jar` — jar names aren't uniform.
3. One Postgres container, five databases via the two init scripts (matches A1, preserves the NFR-3 isolation story, fits 6 GB RAM).
4. `JAVA_TOOL_OPTIONS=-Xmx256m` per service in Compose — 7 JVMs + Postgres on ~6 GB is tight.
5. `.env.example` placeholders only; never commit a real `.env`.
6. Billing `/health` is a static custom controller (won't fail when its DB is down, unlike the other six) — note it when writing its healthcheck.
