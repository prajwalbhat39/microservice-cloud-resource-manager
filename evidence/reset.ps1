# Remove completed challenge fixtures; refuse active resources or pending recovery work.
#Requires -Version 7

param(
    [ValidatePattern('^tnt-(br1|br2|br6|exh)-[a-z0-9-]*$')][string[]]$TenantPrefixes = @('tnt-br1-', 'tnt-br2-', 'tnt-br6-', 'tnt-exh-'),
    [string]$DbHost = 'localhost',
    [int]$DbPort = 5432,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
$env:PGPASSWORD = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'changeme' }

$likeClause = ($TenantPrefixes | ForEach-Object { "tenant_id LIKE '$_%'" }) -join ' OR '

function Res([string]$sql) { & psql -U resource_user -h $DbHost -p $DbPort -d resource_db -t -A -v ON_ERROR_STOP=1 -c $sql; if ($LASTEXITCODE -ne 0) { throw "Database query failed" } }
function Quo([string]$sql) { & psql -U quota_user    -h $DbHost -p $DbPort -d quota_db    -t -A -v ON_ERROR_STOP=1 -c $sql; if ($LASTEXITCODE -ne 0) { throw "Database query failed" } }

Write-Host "============================================================================="
Write-Host "EVIDENCE RESET   $((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')) UTC"
Write-Host "============================================================================="
Write-Host "Targeting tenants matching: $($TenantPrefixes -join ', ')"
Write-Host "PRESERVING: tnt-demo-001 and every tenant not matching the prefixes above."
Write-Host ""

# ----------------------------------------------------------------- preview ----
$resCount  = [int](Res "select count(*) from resources where $likeClause;")
$rsvCount  = [int](Res "select count(*) from reservations where $likeClause;")
$tenCount  = [int](Quo "select count(*) from tenants where $likeClause;")
$ledCount  = [int](Quo "select count(*) from quota_reservations where $likeClause;")

Write-Host "rows that WOULD be deleted:"
Write-Host "  resource_db.resources          : $resCount"
Write-Host "  resource_db.reservations       : $rsvCount"
Write-Host "  quota_db.tenants (+tenant_quota): $tenCount"
Write-Host "  quota_db.quota_reservations    : $ledCount"
Write-Host ""

$preserved = [int](Quo "select count(*) from tenants where tenant_id = 'tnt-demo-001';")
Write-Host "tnt-demo-001 present before reset: $(if ($preserved -eq 1) { 'yes' } else { 'no' })"

if ($WhatIf) {
    Write-Host ""
    Write-Host "-WhatIf supplied: nothing was deleted."
    return
}

$active = [int](Res "select count(*) from resources where ($likeClause) and status not in ('FAILED','TERMINATED');")
$pending = [int](Res "select count(*) from resource_work where resource_id in (select resource_id from resources where $likeClause);")
if ($active -gt 0 -or $pending -gt 0) { throw 'Terminate active resources and wait for recovery before deleting evidence data.' }

# ------------------------------------------------------------------ delete ----
# Order matters. In resource_db, reservations references resources, and the
# idempotency records are keyed by the client's key rather than by tenant, so they
# are removed via the reservation ids that are about to disappear.
Write-Host ""
Write-Host "deleting..."

# Any record still pointing at a reservation about to be deleted (e.g. a run whose
# saga never created the reservation row) is cleared by reservation id too.
Res "delete from idempotency_records where reservation_id in (select reservation_id from reservations where $likeClause);" | Out-Null
Res "delete from reservations where $likeClause;" | Out-Null
Res "delete from resources    where $likeClause;" | Out-Null

Quo "delete from quota_reservations where $likeClause;" | Out-Null
Quo "delete from tenant_quota      where $likeClause;" | Out-Null
Quo "delete from tenants           where $likeClause;" | Out-Null

# ------------------------------------------------------------------ verify ----
Write-Host ""
Write-Host "after reset:"
Write-Host "  resource_db.resources          : $([int](Res "select count(*) from resources where $likeClause;"))"
Write-Host "  resource_db.reservations       : $([int](Res "select count(*) from reservations where $likeClause;"))"
Write-Host "  quota_db.tenants              : $([int](Quo "select count(*) from tenants where $likeClause;"))"
Write-Host "  quota_db.quota_reservations    : $([int](Quo "select count(*) from quota_reservations where $likeClause;"))"

$stillThere = [int](Quo "select count(*) from tenants where tenant_id = 'tnt-demo-001';")
Write-Host ""
Write-Host "tnt-demo-001 present after reset : $(if ($stillThere -eq 1) { 'yes - preserved as intended' } else { 'no' })"
if ($preserved -eq 1 -and $stillThere -ne 1) {
    Write-Error "RESET BUG: tnt-demo-001 was deleted. It should have been preserved."
    exit 1
}
Write-Host ""
Write-Host "reset complete."
Write-Host "============================================================================="
