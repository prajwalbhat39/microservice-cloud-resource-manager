# =============================================================================
# Challenge 3C / BR6 — Saga with compensation
#
# Provisioning spans four steps across services:
#   1. reserve quota            (Quota service, SYNCHRONOUS, before the 202)
#   2. create provider instance (Provider)
#   3. start the billing meter  (Billing)
#   4. activate + notify        (local + Notification)
# Steps 2-4 run in a background saga AFTER the 202 has already been returned.
#
# BR6: when a step fails after quota has been reserved, compensation must release
# that quota IN FULL and leave no orphaned data. Compensation runs in REVERSE
# order and only for steps that actually completed:
#   undo 3 (stop meter, if started) -> undo 2 (terminate instance) -> undo 1 (release quota)
#
# The failure is injected per-request with the X-Simulate-Failure header (FR-46),
# which the orchestrator persists on the reservation and replays onto the matching
# downstream call - so the injection survives the asynchronous boundary.
#
# Requires: Resource :8082, Quota :8081, peer stub :8090, PostgreSQL :5432.
# Repeatable: every run uses its own tenant and keys (TR-8).
# =============================================================================
#Requires -Version 7

param(
    [string]$ResourceUrl = 'http://localhost:8082',
    [string]$QuotaUrl    = 'http://localhost:8081',
    [string]$OutDir      = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'
$env:PGPASSWORD = 'changeme'

$runId    = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$tenantId = "tnt-br6-$runId"
$keyHappy = "key-happy-$runId"
$keyFail  = "key-compensated-$runId"
$tmp      = Join-Path $env:TEMP "br6-$runId"
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

function Show($cmd) { Write-Host ""; Write-Host "`$ $cmd" }
function Res-Sql([string]$sql, [string]$outFile) {
    if ($outFile) { & psql -U resource_user -h localhost -p 5432 -d resource_db -c $sql | Out-File -FilePath $outFile -Encoding utf8 -Append }
    else          { & psql -U resource_user -h localhost -p 5432 -d resource_db -c $sql }
}
function Quota-Sql([string]$sql, [string]$outFile) {
    if ($outFile) { & psql -U quota_user -h localhost -p 5432 -d quota_db -c $sql | Out-File -FilePath $outFile -Encoding utf8 -Append }
    else          { & psql -U quota_user -h localhost -p 5432 -d quota_db -c $sql }
}
function Write-Json($obj, $path) { ($obj | ConvertTo-Json -Compress) | Set-Content -Path $path -Encoding ascii -NoNewline }
function QuotaUsed { [int](& psql -U quota_user -h localhost -p 5432 -d quota_db -t -A -c "select cpu_used from tenant_quota where tenant_id = '$tenantId';") }

# ----------------------------------------------------------------- header -----
@"
=============================================================================
CHALLENGE 3C EVIDENCE - BR6: saga compensation releases reserved quota in full
=============================================================================
Run (UTC)        : $((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')) UTC
Run (local)      : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
Machine / OS     : $env:COMPUTERNAME / $([System.Environment]::OSVersion.VersionString)
PowerShell       : $($PSVersionTable.PSVersion)
Resource service : $ResourceUrl
Quota service    : $QuotaUrl
Peer endpoints   : http://localhost:8090  (Provider / Billing / Notification)
Databases        : postgresql://localhost:5432/{resource_db, quota_db}
Test tenant      : $tenantId   (tier STANDARD => 20 CPU / 64 GB / 10 instances)
Happy-path key   : $keyHappy
Compensated key  : $keyFail
Injection        : X-Simulate-Failure: billing:always-fail  (forces saga step 3 to fail)
=============================================================================
"@

# ---------------------------------------------------------------- 0. setup ----
Write-Host ""
Write-Host "--- STEP 0: create the tenant ------------------------------------------------"
$tenantBody = Join-Path $tmp 'tenant.json'
Write-Json @{ tenantId = $tenantId; name = "BR6 run $runId"; tier = 'STANDARD' } $tenantBody
& curl.exe -s -o NUL -w "create tenant -> HTTP %{http_code}`n" -X POST "$QuotaUrl/api/v1/tenants" -H "Content-Type: application/json" --data "@$tenantBody"

$before = Join-Path $OutDir 'db-before.txt'
"CHALLENGE 3C - DB STATE BEFORE  ($((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')) UTC)`ntenant: $tenantId`n" | Set-Content $before -Encoding utf8
"--- quota_db: usage starts at zero ---" | Out-File $before -Encoding utf8 -Append
Quota-Sql "select tenant_id, cpu_used, cpu_limit, ram_used_gb, instance_used from tenant_quota where tenant_id = '$tenantId';" $before
"--- quota_db: no ledger rows yet ---" | Out-File $before -Encoding utf8 -Append
Quota-Sql "select count(*) as ledger_rows from quota_reservations where tenant_id = '$tenantId';" $before
"--- resource_db: no resources yet ---" | Out-File $before -Encoding utf8 -Append
Res-Sql "select count(*) as resources from resources where tenant_id = '$tenantId';" $before
Write-Host ""
Write-Host "--- database state BEFORE (also saved to db-before.txt) ----------------------"
Get-Content $before

$provBody = Join-Path $tmp 'provision.json'
Write-Json @{ tenantId = $tenantId; type = 'VM'; cpu = 4; ramGb = 8; durationSeconds = 7200 } $provBody

# ============================== PART A: HAPPY PATH ===========================
Write-Host ""
Write-Host "##############################################################################"
Write-Host "## PART A - HAPPY PATH (no injection). Every saga step succeeds.            ##"
Write-Host "##############################################################################"
Show "curl.exe -s -X POST $ResourceUrl/api/v1/resources -H 'Idempotency-Key: $keyHappy' --data '@provision.json'"
$happyBody = & curl.exe -s -X POST "$ResourceUrl/api/v1/resources" -H "Content-Type: application/json" `
                  -H "Idempotency-Key: $keyHappy" --data "@$provBody"
Write-Host $happyBody
$happy = $happyBody | ConvertFrom-Json
Write-Host ""
Write-Host "quota consumed immediately (reserved SYNCHRONOUSLY, before the 202): $(QuotaUsed) CPU"

Write-Host ""
Write-Host "waiting 5s for the background saga to finish..."
Start-Sleep -Seconds 5

Write-Host ""
Write-Host "reservation status:"
& curl.exe -s "$ResourceUrl/api/v1/reservations/$($happy.reservationId)"
Write-Host ""
Write-Host "resource:"
& curl.exe -s "$ResourceUrl/api/v1/resources/$($happy.resourceId)"
Write-Host ""
$happyQuota = QuotaUsed
Write-Host ""
Write-Host "quota after the happy path: $happyQuota CPU  (stays consumed - the resource is live)"

# ===================== PART B: FORCED FAILURE + COMPENSATION =================
Write-Host ""
Write-Host "##############################################################################"
Write-Host "## PART B - FAILURE CASE. Billing step 3 forced to fail; saga compensates.  ##"
Write-Host "##############################################################################"
Write-Host "Quota before this provision: $happyQuota CPU"
Show "curl.exe -s -X POST $ResourceUrl/api/v1/resources -H 'Idempotency-Key: $keyFail' -H 'X-Simulate-Failure: billing:always-fail' --data '@provision.json'"
$failBody = & curl.exe -s -X POST "$ResourceUrl/api/v1/resources" -H "Content-Type: application/json" `
                 -H "Idempotency-Key: $keyFail" -H "X-Simulate-Failure: billing:always-fail" --data "@$provBody"
Write-Host $failBody
$failed = $failBody | ConvertFrom-Json

$peakQuota = QuotaUsed
Write-Host ""
Write-Host "quota right after the 202, BEFORE compensation runs: $peakQuota CPU"
Write-Host "  (the 202 means quota IS already held - that is what must be given back)"

Write-Host ""
Write-Host "waiting 8s for the saga to fail, retry billing, and compensate..."
Start-Sleep -Seconds 8

Write-Host ""
Write-Host "--- reservation status: expect FAILED with a BILLING_UNAVAILABLE reason ------"
& curl.exe -s "$ResourceUrl/api/v1/reservations/$($failed.reservationId)"
Write-Host ""
Write-Host ""
Write-Host "--- resource: expect status FAILED and providerInstanceId NULL ---------------"
Write-Host "providerInstanceId is deliberately CLEARED after the instance is terminated, so"
Write-Host "the column honestly reads 'nothing is running' (recorded deviation A11). The"
Write-Host "failure_reason prefix is what records how far the saga got."
& curl.exe -s "$ResourceUrl/api/v1/resources/$($failed.resourceId)"
Write-Host ""

$finalQuota = QuotaUsed
Write-Host ""
Write-Host "--- STEP: THE KEY ASSERTION (BR6 / AC-3.4) -----------------------------------"
Write-Host "  quota before this provision : $happyQuota CPU"
Write-Host "  quota while saga in flight  : $peakQuota CPU   (4 CPU held for the doomed resource)"
Write-Host "  quota after compensation    : $finalQuota CPU   (expected back to $happyQuota)"
$released = ($finalQuota -eq $happyQuota)
Write-Host "  reserved quota released IN FULL: $released"

# -------------------------------------------------------- DB state after ------
Write-Host ""
Write-Host "--- database state AFTER (also saved to db-after.txt) ------------------------"
$after = Join-Path $OutDir 'db-after.txt'
"CHALLENGE 3C - DB STATE AFTER  ($((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')) UTC)`ntenant: $tenantId`n" | Set-Content $after -Encoding utf8
"--- resource_db: the ACTIVE happy-path resource and the FAILED compensated one ---" | Out-File $after -Encoding utf8 -Append
Res-Sql "select resource_id, status, provider_instance_id, failure_reason from resources where tenant_id = '$tenantId' order by status;" $after
"--- resource_db: reservations - one COMPLETED, one FAILED ---" | Out-File $after -Encoding utf8 -Append
Res-Sql "select reservation_id, status, failure_reason, simulate_failure from reservations where tenant_id = '$tenantId' order by status;" $after
"--- quota_db: ledger - the happy one RESERVED, the compensated one RELEASED ---" | Out-File $after -Encoding utf8 -Append
Quota-Sql "select resource_id, cpu, ram_gb, state, released_at from quota_reservations where tenant_id = '$tenantId' order by state;" $after
"--- quota_db: usage reflects ONLY the surviving resource (4 CPU, 1 instance) ---" | Out-File $after -Encoding utf8 -Append
Quota-Sql "select cpu_used, ram_used_gb, instance_used from tenant_quota where tenant_id = '$tenantId';" $after
Get-Content $after

# ------------------------------------------------------------- verdict --------
$failedRes = & psql -U resource_user -h localhost -p 5432 -d resource_db -t -A -c "select status, coalesce(provider_instance_id,'NULL'), left(coalesce(failure_reason,''),22) from resources where resource_id = '$($failed.resourceId)';"
$parts = $failedRes -split '\|'
$statusFailed   = ($parts[0] -eq 'FAILED')
$instanceClear  = ($parts[1] -eq 'NULL')
$reasonBilling  = ($parts[2] -like 'BILLING_UNAVAILABLE*')
$ledgerReleased = [int](& psql -U quota_user -h localhost -p 5432 -d quota_db -t -A -c "select count(*) from quota_reservations where resource_id = '$($failed.resourceId)' and state = 'RELEASED';")

$pass = $released -and $statusFailed -and $instanceClear -and $reasonBilling -and ($ledgerReleased -eq 1)

Write-Host ""
Write-Host "============================================================================="
Write-Host "FINAL CHECKS (compensated resource $($failed.resourceId))"
Write-Host "  resource ended FAILED                               : $statusFailed"
Write-Host "  provider_instance_id cleared (no orphan, AC-3.5)    : $instanceClear"
Write-Host "  failure_reason names the failed step                : $reasonBilling"
Write-Host "  quota ledger row flipped to RELEASED (BR7)          : $($ledgerReleased -eq 1)"
Write-Host "  reserved quota returned in full (BR6, AC-3.4)       : $released"
Write-Host ("VERDICT: {0}" -f $(if ($pass) { 'PASS - the saga compensated correctly and left the system consistent' } else { 'FAIL - see checks above' }))
Write-Host "Finished (UTC): $((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss'))"
Write-Host "============================================================================="
Write-Host ""
Write-Host "Compensated resourceId : $($failed.resourceId)"
Write-Host "Compensated reservation: $($failed.reservationId)"
Write-Host "Happy-path resourceId  : $($happy.resourceId)"
Write-Host "(use these ids to locate the matching saga log lines in saga-logs.txt)"

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
if (-not $pass) { exit 1 }
