# Kubernetes deployment

Assignment 2, Part B: deploy the seven services on Minikube and demonstrate Provider
self-healing and scaling. PostgreSQL runs as a StatefulSet with a persistent volume.
Kustomize reuses the existing database setup scripts. A ConfigMap supplies peer URLs
and runtime settings; a Secret supplies database passwords.

## Prerequisites

Docker with Linux containers, Minikube and kubectl. Commands below use a POSIX
shell from the repository root. Java 21 and Maven run inside the existing build images.

For macOS with Homebrew and Colima:

```sh
brew install colima docker docker-compose docker-buildx minikube
colima start crm --cpus 8 --memory 12 --disk 80 --activate=false --ssh-config=false
export DOCKER_CONTEXT=colima-crm
```

For Homebrew Docker, add the Homebrew `lib/docker/cli-plugins` directory to
`cliPluginsExtraDirs` in `~/.docker/config.json`, preserving existing settings.
The allocations above provide 12 GiB to the container runtime; the cluster below uses 8 GiB.

## Build and deploy

Create `k8s/.env` from the root `.env.example` and replace the password placeholders
before the first deployment. The file is ignored by Git. Preserve an existing `.env`:
changing it does not rotate credentials in an initialized database.

```sh
minikube start -p crm --driver=docker --container-runtime=containerd --cpus=6 --memory=8192 --keep-context --listen-address=127.0.0.1

set -e
for service in quota resource billing notification provider usage-aggregator expiry-worker; do
  directory=$service
  case "$service" in
    billing|notification) directory="$service-service" ;;
  esac
  docker build -t "crm/$service:local" "services/$directory"
  minikube -p crm image load --daemon "crm/$service:local"
done

kubectl --context=crm apply -k k8s
kubectl --context=crm -n crm rollout status statefulset/postgres --timeout=300s
kubectl --context=crm -n crm rollout status deployment --timeout=300s
```

Application images stay in the local node cache. After rebuilding under the same
`:local` tags, reload them and run `kubectl --context=crm -n crm rollout restart deployment`.
Readiness checks include database health; liveness checks use Spring Boot's liveness
health group. Database applications wait for PostgreSQL initialization before starting.

## Demonstrate the workflow

Use a new tenant ID and Idempotency-Key for each demonstration. These requests run
inside the cluster and use Kubernetes Service DNS:

```sh
kubectl --context=crm -n crm exec deployment/resource -c app -- curl --fail-with-body -sS \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"sid-demo-01","name":"Part B demonstration","tier":"STANDARD"}' \
  http://quota:8081/api/v1/tenants

kubectl --context=crm -n crm exec deployment/resource -c app -- curl --fail-with-body -sS \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: sid-demo-01-provision' \
  -d '{"tenantId":"sid-demo-01","type":"VM","cpu":4,"ramGb":8,"durationSeconds":86400}' \
  http://resource:8082/api/v1/resources
```

The response contains `reservationId`, `resourceId` and `statusUrl`. Replace the example
IDs below with those returned. Poll the reservation until COMPLETED, then check ACTIVE:

```sh
kubectl --context=crm -n crm exec deployment/resource -c app -- curl --fail-with-body -sS \
  http://resource:8082/api/v1/reservations/RESERVATION_ID
kubectl --context=crm -n crm exec deployment/resource -c app -- curl --fail-with-body -sS \
  http://resource:8082/api/v1/resources/RESOURCE_ID
```

Check quota allocation, Billing's ACTIVE meter, the ACTIVE notification and Usage through
the endpoints in the service READMEs. [Captured results](../evidence/k8s/README.md) include
these responses and the Provider instance identity for the completed demonstration.

## Demonstrate recovery and scaling

```sh
kubectl --context=crm -n crm get pods -l app=provider -o wide
kubectl --context=crm -n crm delete pod PROVIDER_POD_NAME
kubectl --context=crm -n crm rollout status deployment/provider --timeout=300s
kubectl --context=crm -n crm get pods -l app=provider -o wide

kubectl --context=crm -n crm scale deployment/provider --replicas=2
kubectl --context=crm -n crm rollout status deployment/provider --timeout=300s
kubectl --context=crm -n crm get endpointslices -l kubernetes.io/service-name=provider
```

Use the pod name from the first command. Confirm a replacement Ready pod, then two Ready
Provider replicas and endpoints after scaling. Run a fresh provisioning workflow through
the Resource Service. Return Provider to one replica afterward:

```sh
kubectl --context=crm -n crm scale deployment/provider --replicas=1
kubectl --context=crm -n crm rollout status deployment/provider --timeout=300s
```

PostgreSQL uses the `data-postgres-0` PVC. The captured persistence test replaced its pod
and confirmed the same volume and original workflow records. Automatic expiry is disabled
so demonstration resources remain available.

## Access and stop

```sh
kubectl --context=crm -n crm port-forward service/resource 18082:8082 --address=127.0.0.1
```

Keep the forwarding process running. Open `http://127.0.0.1:18082/swagger-ui/index.html`
or query `/health` and `/api/v1/resources/{resourceId}`. Restart the forwarding process
if its selected Resource pod is replaced.

To stop the local stack without removing data:

```sh
DOCKER_CONTEXT=colima-crm minikube -p crm stop
colima stop crm
```

Resume with the same start commands. Deleting the profile, namespace or PVC can remove
its database data. Scaling evidence verifies two Ready endpoints and a working workflow;
it does not establish a throughput increase or even distribution across replicas.

## References

[Local image loading](https://minikube.sigs.k8s.io/docs/handbook/pushing/),
[Kustomize](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/),
[health probes](https://kubernetes.io/docs/concepts/workloads/pods/probes/),
[Deployments](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/).
