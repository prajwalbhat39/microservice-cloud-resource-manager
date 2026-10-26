# Local demonstration

Build the services and create their databases using the root README.

1. Start the services: `pwsh scripts/demo/start-all.ps1`.
2. Run `pwsh scripts/demo/demo-main-flow.ps1`. It pauses between provisioning, replay,
   compensation and termination. Use `-NoPause` for an uninterrupted run.
3. Use the printed tenant ID with `pwsh scripts/demo/show-footprint.ps1 -TenantId <id> -ProveIsolation`
   to inspect the five databases and check role isolation.
4. Run the three challenge scripts listed in `evidence/README.md` when separate challenge
   evidence is needed.
5. Stop the services: `pwsh scripts/demo/stop-all.ps1`.

Use `-WithExpiry` when starting services to include the expiry worker. Use
`pwsh scripts/integration.ps1 -SkipBuild` for the automated seven-service test, including expiry,
peer outages and recovery after restart. It starts and stops its own processes.

Logs are written under `logs/`. Database inspection uses each service's own role.
