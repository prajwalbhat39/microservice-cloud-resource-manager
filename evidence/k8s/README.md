# Verification results

Verified on 1 November 2026. [Captured results](results.json) contain the workflow
responses, pod/PVC identities, Ready Service endpoints and test counts.
[Deployment and demonstration instructions](../../k8s/README.md) describe the Part B workflow.

| Validation | Result |
|---|---|
| Java 21 Maven build, including the PostgreSQL integration profile | 142 passed; 0 failed, 0 skipped |
| Full seven-service integration scenario | Passed with real HTTP peers and isolated PostgreSQL databases |
| Cold application startup and Kubernetes provisioning | COMPLETED/ACTIVE; quota, Provider, Billing, Notification and Usage verified |
| Idempotency | Replay retains IDs; changed payload rejected |
| Provider recovery/scaling | New Ready pod UID; two distinct Ready Service endpoints; fresh workflows succeed |
| PostgreSQL persistence | New pod UID; same PVC, volume and workflow identities |
| Termination | Provider stopped, Billing stopped, quota released |
| Kubernetes failure paths | Billing compensation, partial peer outage and durable teardown after Resource restart passed |
| Kubernetes scheduled expiry | Worker termination, delivered notification, quota release and stable stopped cost passed |
| Host entrypoint | Provisioning through the Resource port-forward; health and Swagger UI passed |

The full integration scenario also checks 20 concurrent quota requests, concurrent
provisioning replay, failed provisioning compensation, scheduled expiry, teardown recovery,
and partial/total peer outages. It ran with Java 21.0.13, Maven 3.9.16 and PowerShell 7.6.9.
Only the repository/output paths were redirected in a temporary copy of
`scripts/integration.ps1`; its test logic was unchanged. Application/test databases were
isolated from the Kubernetes demonstration data.

Kubernetes validation used Minikube 1.39.0 and Kubernetes 1.37.0. The final Part B run
started after all application pods were stopped and recreated. Provider returned to one
replica; temporary Billing/Expiry settings were restored. Raw diagnostic runs remain in
ignored `runs/`; the submission contains one selected capture.

Persistence was tested across pod replacement on one local node. Scaling establishes
two Ready endpoints and a successful workflow, without claiming a throughput increase
or that both replicas received traffic. Automatic expiry is disabled in the default demo
configuration. The video and configuration explanation remain individual deliverables.
