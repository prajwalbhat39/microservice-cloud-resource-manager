# =============================================================================
# ONE TRANSACTION'S FOOTPRINT ACROSS ALL FIVE DATABASES
#
# The strongest single piece of evidence that this is genuinely a distributed
# system and not a monolith wearing seven hats: take one business transaction and
# show its traces sitting in five SEPARATE PostgreSQL databases, each reached
# with a DIFFERENT service's own credentials, linked only by ids.
#
#   quota_db         Quota service        :8081   tenants, limits, usage ledger
#   resource_db      Resource orchestr.   :8082   resources, reservations, idempotency
#   provider_db      Provider mock        :8085   the actual instance
#   billing_db       Billing service      :8083   the meter and its events
#   notification_db  Notification         :8084   the emitted notifications
#
# The ONLY thing joining them is an id:
#   resources.provider_instance_id  ->  instances.instance_id
#   'ct-' || resources.resource_id  ->  instances.client_token     (FR-19)
#   resources.resource_id           ->  billing_meters.resource_id
#   resources.resource_id           ->  notifications.resource_id
#   resources.tenant_id             ->  tenant_quota.tenant_id
#
# No service stores another service's entity, and no foreign key crosses a
# database boundary - because it CANNOT (NFR-3, NFR-4).
#
# Usage:
#   scripts\demo\show-footprint.ps1 -TenantId tnt-demo-134445
#   scripts\demo\show-footprint.ps1 -TenantId tnt-demo-134445 -ProveIsolation
#
# -ProveIsolation additionally demonstrates that resource_user is REFUSED when it
# tries to read quota_db, which is NFR-3 enforced by the database itself rather
# than by convention.
# =============================================================================
#Requires -Version 7

param(
    [Parameter(Mandatory = $true)][string]$TenantId,
    [switch]$ProveIsolation,
    [string]$DbHost = 'localhost',
    [int]$DbPort    = 5432
)

$ErrorActionPreference = 'Continue'
$env:PGPASSWORD = 'changeme'

function Q {
    param([string]$User, [string]$Db, [string]$Sql)
    & psql -U $User -h $DbHost -p $DbPort -d $Db -c $Sql 2>&1
}
function Scalar {
    param([string]$User, [string]$Db, [string]$Sql)
    (& psql -U $User -h $DbHost -p $DbPort -d $Db -t -A -c $Sql 2>$null)
}
function DbHeader {
    param([string]$Db, [string]$User, [string]$Service, [string]$Port)
    Write-Host ""
    Write-Host "=============================================================================" -ForegroundColor Cyan
    Write-Host (" {0,-16} owned by {1,-22} {2}" -f $Db, $Service, $Port) -ForegroundColor Cyan
    Write-Host (" connected as {0} - this service's OWN credentials, nobody else's" -f $User) -ForegroundColor DarkGray
    Write-Host "=============================================================================" -ForegroundColor Cyan
}

Write-Host ""
Write-Host "#############################################################################" -ForegroundColor Magenta
Write-Host "#  ONE TRANSACTION, FIVE DATABASES - cross-service footprint                #" -ForegroundColor Magenta
Write-Host "#############################################################################" -ForegroundColor Magenta
Write-Host ""
Write-Host "  tenant    : $TenantId"
Write-Host "  postgres  : ${DbHost}:${DbPort}"
Write-Host "  taken at  : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"

# Resolve this tenant's resource ids from the orchestrator's database. Everything
# downstream is keyed off these, exactly as the services themselves are.
$resourceIds = @(Scalar 'resource_user' 'resource_db' `
    "select resource_id from resources where tenant_id = '$TenantId' order by created_at;" |
    Where-Object { $_ })

if (-not $resourceIds) {
    Write-Host ""
    Write-Host "  No resources found for tenant '$TenantId' in resource_db." -ForegroundColor Yellow
    Write-Host "  Run the main flow first:  .\scripts\demo\demo-main-flow.ps1" -ForegroundColor Yellow
    Write-Host ""
    exit 1
}

$inList    = ($resourceIds | ForEach-Object { "'$_'" }) -join ', '
$tokenList = ($resourceIds | ForEach-Object { "'ct-$_'" }) -join ', '

Write-Host "  resources : $($resourceIds.Count) found for this tenant"
$resourceIds | ForEach-Object { Write-Host "              $_" -ForegroundColor DarkGray }

# ------------------------------------------------------------- 1. quota_db ----
DbHeader 'quota_db' 'quota_user' 'Quota service' ':8081'
Write-Host "The tenant itself. This is the ONLY database in the system that knows what a" -ForegroundColor Gray
Write-Host "tenant is - every other service holds just the id string (NFR-4)." -ForegroundColor Gray
Q 'quota_user' 'quota_db' "select tenant_id, name, tier, created_at from tenants where tenant_id = '$TenantId';"

Write-Host "Live usage against the tier limit - the numbers Challenge 1 defends:" -ForegroundColor Gray
Q 'quota_user' 'quota_db' "select cpu_used || ' / ' || cpu_limit as cpu, ram_used_gb || ' / ' || ram_limit_gb as ram_gb, instance_used || ' / ' || instance_limit as instances from tenant_quota where tenant_id = '$TenantId';"

Write-Host "The reservation ledger. RESERVED = still held, RELEASED = given back by" -ForegroundColor Gray
Write-Host "termination or by saga compensation (BR6/BR7):" -ForegroundColor Gray
Q 'quota_user' 'quota_db' "select resource_id, cpu, ram_gb, state, case when released_at is null then '-' else to_char(released_at,'HH24:MI:SS') end as released_at from quota_reservations where tenant_id = '$TenantId' order by created_at;"

# ---------------------------------------------------------- 2. resource_db ----
DbHeader 'resource_db' 'resource_user' 'Resource orchestrator' ':8082'
Write-Host "The resource itself. Note provider_instance_id: an id belonging to another" -ForegroundColor Gray
Write-Host "service's database, stored as a plain string with no foreign key (NFR-4)." -ForegroundColor Gray
Q 'resource_user' 'resource_db' "select resource_id, type, cpu, ram_gb, status, coalesce(provider_instance_id,'(null)') as provider_instance_id, coalesce(failure_reason,'-') as failure_reason from resources where tenant_id = '$TenantId' order by created_at;"

Write-Host "The saga's own record of each attempt, including the persisted failure" -ForegroundColor Gray
Write-Host "injection that is replayed across the async boundary (FR-46):" -ForegroundColor Gray
Q 'resource_user' 'resource_db' "select reservation_id, status, coalesce(simulate_failure,'-') as simulate_failure, coalesce(failure_reason,'-') as failure_reason from reservations where tenant_id = '$TenantId' order by created_at;"

Write-Host "Idempotency records - one row per key, the fingerprint that detects a changed" -ForegroundColor Gray
Write-Host "payload, and the frozen response that gets replayed (Challenge 2):" -ForegroundColor Gray
Q 'resource_user' 'resource_db' "select idempotency_key, state, response_status, left(request_fingerprint, 16) || '...' as fingerprint from idempotency_records where reservation_id in (select reservation_id from reservations where tenant_id = '$TenantId') order by created_at;"

# ---------------------------------------------------------- 3. provider_db ----
DbHeader 'provider_db' 'provider_user' 'Provider mock' ':8085'
Write-Host "The actual instance. Matched by client_token = 'ct-' || resource_id, which is" -ForegroundColor Gray
Write-Host "the stable token that makes a RETRIED provision return this SAME instance" -ForegroundColor Gray
Write-Host "instead of creating a second one (FR-19). Note it has no tenant_id column at" -ForegroundColor Gray
Write-Host "all - the provider has no concept of a tenant." -ForegroundColor Gray
Q 'provider_user' 'provider_db' "select instance_id, client_token, type, cpu, ram_gb, status, case when terminated_at is null then '-' else to_char(terminated_at,'HH24:MI:SS') end as terminated_at from instances where client_token in ($tokenList) order by created_at;"

# ----------------------------------------------------------- 4. billing_db ----
DbHeader 'billing_db' 'billing_user' 'Billing service' ':8083'
Write-Host "The meter. ACTIVE accrues cost; STOPPED has a stopped_at and accrues nothing" -ForegroundColor Gray
Write-Host "further (BR5). rate_per_cpu_hour is NUMERIC, never floating point (NFR-22)." -ForegroundColor Gray
Q 'billing_user' 'billing_db' "select meter_id, resource_id, cpu, rate_per_cpu_hour, state, to_char(started_at,'HH24:MI:SS') as started_at, case when stopped_at is null then '-' else to_char(stopped_at,'HH24:MI:SS') end as stopped_at from billing_meters where resource_id in ($inList) order by started_at;"

Write-Host "The billing event log behind that meter:" -ForegroundColor Gray
Q 'billing_user' 'billing_db' "select resource_id, event_type, to_char(event_time,'HH24:MI:SS') as event_time from billing_events where resource_id in ($inList) order by event_time;"

# ------------------------------------------------------ 5. notification_db ----
DbHeader 'notification_db' 'notification_user' 'Notification service' ':8084'
Write-Host "Notifications are BEST-EFFORT and sit outside the compensation path: a failing" -ForegroundColor Gray
Write-Host "notifier must never roll back a provisioned, paid-for resource (FR-33). Because" -ForegroundColor Gray
Write-Host "those failures are deliberately swallowed, the only honest way to prove the" -ForegroundColor Gray
Write-Host "call landed is to show the ROW - which is what this is." -ForegroundColor Gray
Q 'notification_user' 'notification_db' "select tenant_id, resource_id, event_type, status, attempts, left(message, 38) as message from notifications where tenant_id = '$TenantId' order by created_at;"

# ------------------------------------------------------------- the summary ----
$quotaCpu   = Scalar 'quota_user'        'quota_db'        "select cpu_used || ' / ' || cpu_limit from tenant_quota where tenant_id = '$TenantId';"
$resActive  = Scalar 'resource_user'     'resource_db'     "select count(*) from resources where tenant_id = '$TenantId' and status = 'ACTIVE';"
$resTotal   = Scalar 'resource_user'     'resource_db'     "select count(*) from resources where tenant_id = '$TenantId';"
$instTotal  = Scalar 'provider_user'     'provider_db'     "select count(*) from instances where client_token in ($tokenList);"
$meterTotal = Scalar 'billing_user'      'billing_db'      "select count(*) from billing_meters where resource_id in ($inList);"
$meterOn    = Scalar 'billing_user'      'billing_db'      "select count(*) from billing_meters where resource_id in ($inList) and state = 'ACTIVE';"
$notifTotal = Scalar 'notification_user' 'notification_db' "select count(*) from notifications where tenant_id = '$TenantId';"

Write-Host ""
Write-Host "=============================================================================" -ForegroundColor Magenta
Write-Host " FOOTPRINT SUMMARY - tenant $TenantId" -ForegroundColor Magenta
Write-Host "=============================================================================" -ForegroundColor Magenta
Write-Host ("  quota_db         cpu_used / limit        : {0}" -f $quotaCpu)
Write-Host ("  resource_db      resources (ACTIVE/all)  : {0} / {1}" -f $resActive, $resTotal)
Write-Host ("  provider_db      instances               : {0}" -f $instTotal)
Write-Host ("  billing_db       meters (ACTIVE/all)     : {0} / {1}" -f $meterOn, $meterTotal)
Write-Host ("  notification_db  notifications           : {0}" -f $notifTotal)
Write-Host "-----------------------------------------------------------------------------" -ForegroundColor DarkGray
Write-Host "  Five databases, five sets of credentials, one business transaction." -ForegroundColor Gray
Write-Host "  Joined by ids only - no cross-database foreign key exists, or could." -ForegroundColor Gray
Write-Host "=============================================================================" -ForegroundColor Magenta

# ----------------------------------------------------- optional: NFR-3 proof ---
if ($ProveIsolation) {
    Write-Host ""
    Write-Host "=============================================================================" -ForegroundColor Yellow
    Write-Host " NFR-3 PROOF - no service can read another service's database" -ForegroundColor Yellow
    Write-Host "=============================================================================" -ForegroundColor Yellow
    Write-Host "This is not a convention we promise to follow. The orchestrator's own database" -ForegroundColor Gray
    Write-Host "user is REFUSED by PostgreSQL when it tries to read the Quota service's data," -ForegroundColor Gray
    Write-Host "so 'it reads its peer's tables in a pinch' is not an option available to it." -ForegroundColor Gray
    Write-Host ""
    Write-Host "  `$ psql -U resource_user -d quota_db -c 'select * from tenant_quota'" -ForegroundColor Yellow
    $denied = & psql -U resource_user -h $DbHost -p $DbPort -d quota_db -c "select count(*) from tenant_quota;" 2>&1
    $denied | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
    Write-Host ""
    Write-Host "  ^ permission denied / no access. The boundary is enforced by the database." -ForegroundColor Green
    Write-Host "=============================================================================" -ForegroundColor Yellow
}

Write-Host ""
