# =============================================================================
# Challenge 1 / BR1 — Atomic quota reservation under genuine concurrency
#
# Proves: tenant quota usage can never exceed the tier limit, no matter how many
# requests arrive simultaneously. The mechanism is a single atomic conditional
# UPDATE in the database (TenantQuotaRepository.tryReserve), whose affected-row
# count is the accept/reject decision. There is no check-then-update in Java.
#
# A STANDARD tenant has a 20 CPU limit. 20 requests each asking for 4 CPU are
# fired at the same instant. Exactly 5 can fit (5 x 4 = 20); the other 15 must be
# rejected with 422 QUOTA_EXCEEDED, and usage must land exactly on 20 - never above.
#
# Requires: Quota service on :8081, PostgreSQL on :5432 with quota_db.
# Repeatable: every run creates its own uniquely-named tenants (TR-8).
# =============================================================================
#Requires -Version 7.4

param(
    [string]$QuotaUrl = 'http://localhost:8081',
    [string]$DbHost = 'localhost',
    [int]$DbPort = 5432,
    [string]$OutDir   = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$env:PGPASSWORD = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'changeme' }

$CPU_PER_REQUEST      = 4
$RAM_PER_REQUEST      = 8
$CONCURRENT_REQUESTS  = 20
$EXPECTED_SUCCESSES   = 5     # 20 CPU limit / 4 CPU each

$runId     = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$tenantId  = "tnt-br1-$runId"
$freeTenant = "tnt-br1-free-$runId"
$tmp       = Join-Path ([System.IO.Path]::GetTempPath()) "br1-$runId"
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

function Show($cmd) { Write-Host ""; Write-Host "`$ $cmd" }

function Quota-Sql([string]$sql, [string]$outFile) {
    if ($outFile) { & psql -v ON_ERROR_STOP=1 -U quota_user -h $DbHost -p $DbPort -d quota_db -c $sql | Out-File -FilePath $outFile -Encoding utf8 -Append }
    else          { & psql -v ON_ERROR_STOP=1 -U quota_user -h $DbHost -p $DbPort -d quota_db -c $sql }
}

function Write-Json($obj, $path) {
    ($obj | ConvertTo-Json -Compress) | Set-Content -Path $path -Encoding ascii -NoNewline
}

# ----------------------------------------------------------------- header -----
@"
=============================================================================
CHALLENGE 1 EVIDENCE - BR1: quota never exceeds tier limits under concurrency
=============================================================================
Run (UTC)        : $((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')) UTC
Run (local)      : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
Machine / OS     : $env:COMPUTERNAME / $([System.Environment]::OSVersion.VersionString)
PowerShell       : $($PSVersionTable.PSVersion)
Quota service    : $QuotaUrl
Database         : postgresql://${DbHost}:${DbPort}/quota_db
Test tenant      : $tenantId   (tier STANDARD => 20 CPU / 64 GB / 10 instances)
Scenario         : $CONCURRENT_REQUESTS simultaneous reservations of $CPU_PER_REQUEST CPU each
Expected         : exactly $EXPECTED_SUCCESSES x HTTP 200, exactly $($CONCURRENT_REQUESTS - $EXPECTED_SUCCESSES) x HTTP 422, final cpu_used = 20
=============================================================================
"@

# -------------------------------------------------- 1. create the tenant -----
Write-Host ""
Write-Host "--- STEP 1: create a STANDARD tenant -----------------------------------------"
$tenantBody = Join-Path $tmp 'tenant.json'
Write-Json @{ tenantId = $tenantId; name = "BR1 concurrency run $runId"; tier = 'STANDARD' } $tenantBody
Show "curl -s -w '\nHTTP %{http_code}' -X POST $QuotaUrl/api/v1/tenants -H 'Content-Type: application/json' --data '@tenant.json'"
& curl -sS -w "`nHTTP %{http_code}`n" -X POST "$QuotaUrl/api/v1/tenants" -H "Content-Type: application/json" --data "@$tenantBody"

# ------------------------------------------------------ 2. DB state before ----
Write-Host ""
Write-Host "--- STEP 2: database state BEFORE (also saved to db-before.txt) --------------"
$before = Join-Path $OutDir 'db-before.txt'
"CHALLENGE 1 - DB STATE BEFORE  ($((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')) UTC)`ntenant: $tenantId`n" | Set-Content $before -Encoding utf8
Quota-Sql "select tenant_id, cpu_used, cpu_limit, ram_used_gb, ram_limit_gb, instance_used, instance_limit from tenant_quota where tenant_id = '$tenantId';" $before
Quota-Sql "select count(*) as reserved_ledger_rows from quota_reservations where tenant_id = '$tenantId';" $before
Get-Content $before

# ------------------------------------------ 3. the concurrent reservations ----
Write-Host ""
Write-Host "--- STEP 3: fire $CONCURRENT_REQUESTS reservations SIMULTANEOUSLY ------------------------------"
Write-Host "Each worker prepares its request, then blocks until a shared start time, so the"
Write-Host "calls genuinely race rather than trickling in one after another."

# Shared start gun 3s out: enough for all 20 runspaces to spin up and be waiting.
$startAt = (Get-Date).AddSeconds(3)
Write-Host "start gun at: $($startAt.ToString('HH:mm:ss.fff'))"

$results = 1..$CONCURRENT_REQUESTS | ForEach-Object -ThrottleLimit $CONCURRENT_REQUESTS -Parallel {
    $i          = $_
    $tmpDir     = $using:tmp
    $resourceId = [guid]::NewGuid().ToString()
    $bodyFile   = Join-Path $tmpDir "req-$i.json"
    $respFile   = Join-Path $tmpDir "resp-$i.json"

    @{ tenantId = $using:tenantId; resourceId = $resourceId; type = 'VM'
       cpu = $using:CPU_PER_REQUEST; ramGb = $using:RAM_PER_REQUEST } |
        ConvertTo-Json -Compress | Set-Content -Path $bodyFile -Encoding ascii -NoNewline

    # Block until the shared instant.
    while ((Get-Date) -lt $using:startAt) { Start-Sleep -Milliseconds 2 }

    $sent = (Get-Date).ToString('HH:mm:ss.fff')
    $code = & curl -sS -o $respFile -w "%{http_code}" -X POST "$($using:QuotaUrl)/api/v1/quota/reservations" `
                 -H "Content-Type: application/json" --data "@$bodyFile"

    [pscustomobject]@{
        Worker = $i
        SentAt = $sent
        Status = [int]$code
        Body   = (Get-Content $respFile -Raw -ErrorAction SilentlyContinue)
    }
}

$ordered = $results | Sort-Object SentAt
Write-Host ""
Write-Host "per-request result (sorted by the instant the call left the client):"
$ordered | Format-Table Worker, SentAt, Status -AutoSize | Out-String -Width 200 | Write-Host

$spread = ([datetime]::ParseExact(($ordered[-1].SentAt), 'HH:mm:ss.fff', $null) -
           [datetime]::ParseExact(($ordered[0].SentAt),  'HH:mm:ss.fff', $null)).TotalMilliseconds
Write-Host "all $CONCURRENT_REQUESTS requests left the client within $spread ms of each other"

# ------------------------------------------------------- 4. counted result ----
$ok       = ($results | Where-Object Status -eq 200).Count
$rejected = ($results | Where-Object Status -eq 422).Count
$other    = ($results | Where-Object { $_.Status -ne 200 -and $_.Status -ne 422 }).Count

Write-Host ""
Write-Host "--- STEP 4: COUNTED RESULT ---------------------------------------------------"
Write-Host "HTTP 200 (reserved) : $ok      (expected $EXPECTED_SUCCESSES)"
Write-Host "HTTP 422 (rejected) : $rejected      (expected $($CONCURRENT_REQUESTS - $EXPECTED_SUCCESSES))"
Write-Host "any other status    : $other      (expected 0)"

Write-Host ""
Write-Host "a rejection body, showing WHICH limit was exhausted (AC-1.3):"
($results | Where-Object Status -eq 422 | Select-Object -First 1).Body

# -------------------------------------------------------- 5. DB state after ---
Write-Host ""
Write-Host "--- STEP 5: database state AFTER (also saved to db-after.txt) ----------------"
$after = Join-Path $OutDir 'db-after.txt'
"CHALLENGE 1 - DB STATE AFTER  ($((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')) UTC)`ntenant: $tenantId`n" | Set-Content $after -Encoding utf8
Quota-Sql "select tenant_id, cpu_used, cpu_limit, ram_used_gb, ram_limit_gb, instance_used, instance_limit from tenant_quota where tenant_id = '$tenantId';" $after
Quota-Sql "select state, count(*) as rows from quota_reservations where tenant_id = '$tenantId' group by state;" $after
Get-Content $after

Write-Host ""
Write-Host "usage as reported by the API:"
Show "curl -s $QuotaUrl/api/v1/tenants/$tenantId/quota"
& curl -sS "$QuotaUrl/api/v1/tenants/$tenantId/quota"
Write-Host ""

# --------------------------------------------- 6. FAILURE CASE (ER-4/TR-10) ---
Write-Host ""
Write-Host "--- STEP 6: FAILURE CASE - a FREE tenant (2 CPU limit) asking for 4 CPU ------"
Write-Host "BR1 must reject on business grounds with 422, naming the exhausted dimension,"
Write-Host "and must NOT fall back to a 400 or a 500."
$freeBody = Join-Path $tmp 'tenant-free.json'
Write-Json @{ tenantId = $freeTenant; name = "BR1 free run $runId"; tier = 'FREE' } $freeBody
& curl -sS -o $(if ($IsWindows) { 'NUL' } else { '/dev/null' }) -w "create FREE tenant -> HTTP %{http_code}`n" -X POST "$QuotaUrl/api/v1/tenants" -H "Content-Type: application/json" --data "@$freeBody"

$overBody = Join-Path $tmp 'reserve-over.json'
Write-Json @{ tenantId = $freeTenant; resourceId = [guid]::NewGuid().ToString(); type = 'VM'; cpu = 4; ramGb = 8 } $overBody
Show "curl -s -w '\nHTTP %{http_code}' -X POST $QuotaUrl/api/v1/quota/reservations --data '@reserve-over.json'"
& curl -sS -w "`nHTTP %{http_code}`n" -X POST "$QuotaUrl/api/v1/quota/reservations" -H "Content-Type: application/json" --data "@$overBody"

Write-Host ""
Write-Host "--- STEP 7: FAILURE CASE - BR8, a FREE tenant may not provision a CLUSTER ----"
$clusterBody = Join-Path $tmp 'reserve-cluster.json'
Write-Json @{ tenantId = $freeTenant; resourceId = [guid]::NewGuid().ToString(); type = 'CLUSTER'; cpu = 1; ramGb = 1 } $clusterBody
Show "curl -s -w '\nHTTP %{http_code}' -X POST $QuotaUrl/api/v1/quota/reservations --data '@reserve-cluster.json'"
& curl -sS -w "`nHTTP %{http_code}`n" -X POST "$QuotaUrl/api/v1/quota/reservations" -H "Content-Type: application/json" --data "@$clusterBody"

# ------------------------------------------------------------- 8. verdict -----
$pass = ($ok -eq $EXPECTED_SUCCESSES) -and ($rejected -eq ($CONCURRENT_REQUESTS - $EXPECTED_SUCCESSES)) -and ($other -eq 0)
Write-Host ""
Write-Host "============================================================================="
Write-Host ("VERDICT: {0}" -f $(if ($pass) { "PASS - exactly $EXPECTED_SUCCESSES of $CONCURRENT_REQUESTS concurrent reservations succeeded, quota not exceeded" } else { "FAIL - see counts above" }))
Write-Host "Finished (UTC): $((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss'))"
Write-Host "============================================================================="

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
if (-not $pass) { exit 1 }
