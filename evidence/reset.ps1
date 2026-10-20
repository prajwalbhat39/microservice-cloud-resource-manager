# =============================================================================
# Evidence reset (TR-8: "every script is repeatable; a reset script restores a
# known starting state").
#
# Deletes ONLY the data created by the three evidence scripts. Those scripts name
# every tenant they create with a run-specific prefix, so this reset is precise:
#
#     tnt-br1-*   Challenge 1 (quota concurrency)
#     tnt-br2-*   Challenge 2 (idempotency)
#     tnt-br6-*   Challenge 3C (saga compensation)
#     tnt-exh-*   ad-hoc checks
#
# It does NOT drop any schema, does NOT touch Flyway's history, and does NOT touch
# tenant tnt-demo-001 (the hand-made walkthrough data) or anything a teammate may
# have created. Strictly speaking the evidence scripts do not NEED this - each run
# already uses fresh unique ids - but it keeps the databases from accumulating runs
# and gives a known-clean starting point before a demo.
#
# Requires: PostgreSQL on :5432 with quota_db and resource_db.
# Safe to run with the services up or down.
# =============================================================================
#Requires -Version 7

param(
    [string[]]$TenantPrefixes = @('tnt-br1-', 'tnt-br2-', 'tnt-br6-', 'tnt-exh-'),
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
$env:PGPASSWORD = 'changeme'

$likeClause = ($TenantPrefixes | ForEach-Object { "tenant_id LIKE '$_%'" }) -join ' OR '

function Res([string]$sql) { & psql -U resource_user -h localhost -p 5432 -d resource_db -t -A -c $sql }
function Quo([string]$sql) { & psql -U quota_user    -h localhost -p 5432 -d quota_db    -t -A -c $sql }

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

# ------------------------------------------------------------------ delete ----
# Order matters. In resource_db, reservations references resources, and the
# idempotency records are keyed by the client's key rather than by tenant, so they
# are removed via the reservation ids that are about to disappear.
Write-Host ""
Write-Host "deleting..."

$keys = Res "select distinct r.idempotency_key from idempotency_records r join reservations v on v.reservation_id = r.reservation_id where $($likeClause.Replace('tenant_id','v.tenant_id'));"
if ($keys) {
    $inList = ($keys | Where-Object { $_ } | ForEach-Object { "'$_'" }) -join ','
    if ($inList) { Res "delete from idempotency_records where idempotency_key in ($inList);" | Out-Null }
}
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
