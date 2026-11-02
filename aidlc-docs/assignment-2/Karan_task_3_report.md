Problem (0.5):
While running the Docker Compose deployment, billing-service started before PostgreSQL was ready to accept connections.
Observed symptom: billing-service became unhealthy/restarted, and billing endpoints were unavailable.

Diagnosis (1.0):
I diagnosed the issue using deployment evidence:
docker compose ps showed unstable state for billing-service.
docker compose logs billing-service showed startup DB connection/Flyway/Hikari failures.
docker compose logs postgres showed PostgreSQL still initializing when billing-service attempted startup.
docker inspect billing-service confirmed restart/health behavior.
These steps identified readiness timing (not code logic) as the root cause.

Solution + validation (1.0):
I fixed startup ordering by ensuring Postgres readiness gating in Compose:
Postgres healthcheck enabled.
billing-service depends on Postgres with depends_on: condition: service_healthy.

Validation after fix:
docker compose ps shows stable/healthy containers.
billing-service logs no DB startup failures.
GET /actuator/health for billing returns healthy.
Billing API requests succeed (start meter + read meter).

Learning reflection (0.5):
This taught me that container start order is not the same as service readiness. In real deployments, I will always use readiness/health checks and dependency conditions to prevent race conditions during startup and restarts.

Authenticity statement:
This was a genuine issue encountered during assignment deployment and resolved through diagnosis and Compose configuration fixes.

**Demo segment**

cd /Users/karanrawat/microservice-cloud-resource-manager

docker compose ps
docker compose logs billing-service | tail -n 80
docker compose logs postgres | tail -n 80

docker compose down
docker compose up -d

docker compose ps
curl -i http://localhost:8083/actuator/health

BASE_URL="http://localhost:8083/api/v1/billing"
curl -i -X POST "$BASE_URL/meters" \
-H "Content-Type: application/json" \
-d '{"resourceId":"task3-res-1","tenantId":"task3-tenant","cpu":1}'

curl -i "$BASE_URL/meters/task3-res-1"