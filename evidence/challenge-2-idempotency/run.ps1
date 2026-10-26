# =============================================================================
# Challenge 2 / BR2 + BR3 — Idempotent provisioning
#
# BR2: the same Idempotency-Key with the same payload yields the ORIGINAL response
#      and no additional effect - no second resource, no second quota reservation.
# BR3: the same key with a DIFFERENT payload is rejected with 422.
#
# The guard is the database, not the application: the provisioning request inserts
# a row keyed on the client's Idempotency-Key using
#     INSERT ... ON CONFLICT (idempotency_key) DO NOTHING
# and reads the affected-row count. 1 = we are first, process it. 0 = someone holds
# this key already, so read their stored answer and replay it verbatim. Nothing is
# read before writing, so two simultaneous callers cannot both conclude "I'm first".
#
# Requires Quota, Resource and real peers from scripts/demo/start-all.ps1.
# =============================================================================
#Requires -Version 7.4

param(
    [string]$ResourceUrl = 'http://localhost:8082',
    [string]$QuotaUrl    = 'http://localhost:8081',
    [string]$DbHost = 'localhost',
    [int]$DbPort = 5432,
    [string]$OutDir      = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$env:PGPASSWORD = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'changeme' }

$CONCURRENT_DUPLICATES = 10

$runId    = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$tenantId = "tnt-br2-$runId"
$keySeq   = "key-sequential-$runId"
$keyConc  = "key-concurrent-$runId"
$tmp      = Join-Path ([System.IO.Path]::GetTempPath()) "br2-$runId"
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

function Show($cmd) { Write-Host ""; Write-Host "`$ $cmd" }
function Res-Sql([string]$sql, [string]$outFile) {
    if ($outFile) { & psql -v ON_ERROR_STOP=1 -U resource_user -h $DbHost -p $DbPort -d resource_db -c $sql | Out-File -FilePath $outFile -Encoding utf8 -Append }
    else          { & psql -v ON_ERROR_STOP=1 -U resource_user -h $DbHost -p $DbPort -d resource_db -c $sql }
}
function Quota-Sql([string]$sql, [string]$outFile) {
    if ($outFile) { & psql -v ON_ERROR_STOP=1 -U quota_user -h $DbHost -p $DbPort -d quota_db -c $sql | Out-File -FilePath $outFile -Encoding utf8 -Append }
    else          { & psql -v ON_ERROR_STOP=1 -U quota_user -h $DbHost -p $DbPort -d quota_db -c $sql }
}
function Write-Json($obj, $path) { ($obj | ConvertTo-Json -Compress) | Set-Content -Path $path -Encoding ascii -NoNewline }

# ----------------------------------------------------------------- header -----
@"
=============================================================================
CHALLENGE 2 EVIDENCE - BR2 (idempotent replay) and BR3 (payload mismatch)
=============================================================================
Run (UTC)        : $((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')) UTC
Run (local)      : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
Machine / OS     : $env:COMPUTERNAME / $([System.Environment]::OSVersion.VersionString)
PowerShell       : $($PSVersionTable.PSVersion)
Resource service : $ResourceUrl
Quota service    : $QuotaUrl
Databases        : postgresql://${DbHost}:${DbPort}/{resource_db, quota_db}
Test tenant      : $tenantId   (tier STANDARD => 20 CPU / 64 GB / 10 instances)
Sequential key   : $keySeq
Concurrent key   : $keyConc
=============================================================================
"@

# --------------------------------------------------- 0. tenant + DB before ----
Write-Host ""
Write-Host "--- STEP 0: create the tenant quota will be reserved against -----------------"
$tenantBody = Join-Path $tmp 'tenant.json'
Write-Json @{ tenantId = $tenantId; name = "BR2 run $runId"; tier = 'STANDARD' } $tenantBody
& curl -sS -o $(if ($IsWindows) { 'NUL' } else { '/dev/null' }) -w "create tenant -> HTTP %{http_code}`n" -X POST "$QuotaUrl/api/v1/tenants" -H "Content-Type: application/json" --data "@$tenantBody"

$before = Join-Path $OutDir 'db-before.txt'
"CHALLENGE 2 - DB STATE BEFORE  ($((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')) UTC)`ntenant: $tenantId`n" | Set-Content $before -Encoding utf8
"--- resource_db: rows for this tenant ---" | Out-File $before -Encoding utf8 -Append
Res-Sql "select count(*) as resources from resources where tenant_id = '$tenantId';" $before
Res-Sql "select count(*) as reservations from reservations where tenant_id = '$tenantId';" $before
Res-Sql "select count(*) as idempotency_records from idempotency_records where idempotency_key in ('$keySeq','$keyConc');" $before
"--- quota_db: usage for this tenant ---" | Out-File $before -Encoding utf8 -Append
Quota-Sql "select cpu_used, instance_used from tenant_quota where tenant_id = '$tenantId';" $before
Write-Host ""
Write-Host "--- database state BEFORE (also saved to db-before.txt) ----------------------"
Get-Content $before

# --------------------------------------------------- 1. the FIRST request -----
$provBody = Join-Path $tmp 'provision.json'
Write-Json @{ tenantId = $tenantId; type = 'VM'; cpu = 4; ramGb = 8; durationSeconds = 7200 } $provBody

Write-Host ""
Write-Host "--- STEP 1: FIRST provision with key '$keySeq' ------"
Show "curl -s -D - -X POST $ResourceUrl/api/v1/resources -H 'Idempotency-Key: $keySeq' --data '@provision.json'"
$firstRaw = & curl -sS -D "$tmp/first.headers" -o "$tmp/first.body" -w "%{http_code}" `
                -X POST "$ResourceUrl/api/v1/resources" -H "Content-Type: application/json" `
                -H "Idempotency-Key: $keySeq" --data "@$provBody"
Write-Host "response headers:"
Get-Content "$tmp/first.headers"
Write-Host "response body:"
$firstBody = Get-Content "$tmp/first.body" -Raw
Write-Host $firstBody
$first = $firstBody | ConvertFrom-Json
Write-Host "HTTP $firstRaw"

Start-Sleep -Seconds 3   # let the background saga finish so the resource settles

# -------------------------------------------------- 2. the REPLAY request -----
Write-Host ""
Write-Host "--- STEP 2: REPEAT the IDENTICAL request with the SAME key (BR2) -------------"
Write-Host "Expected: HTTP 202, header 'Idempotency-Replayed: true', and the SAME"
Write-Host "reservationId/resourceId - byte-for-byte the first answer, not a new one."
Show "curl -s -D - -X POST $ResourceUrl/api/v1/resources -H 'Idempotency-Key: $keySeq' --data '@provision.json'"
$replayRaw = & curl -sS -D "$tmp/replay.headers" -o "$tmp/replay.body" -w "%{http_code}" `
                 -X POST "$ResourceUrl/api/v1/resources" -H "Content-Type: application/json" `
                 -H "Idempotency-Key: $keySeq" --data "@$provBody"
Write-Host "response headers:"
Get-Content "$tmp/replay.headers"
Write-Host "response body:"
$replayBody = Get-Content "$tmp/replay.body" -Raw
Write-Host $replayBody
Write-Host "HTTP $replayRaw"

$replayedHeader = (Select-String -Path "$tmp/replay.headers" -Pattern 'Idempotency-Replayed' -SimpleMatch).Line
$bodiesIdentical = ($firstBody.Trim() -ceq $replayBody.Trim())
Write-Host ""
Write-Host "CHECK  replay header present : $(if ($replayedHeader) { "YES -> $($replayedHeader.Trim())" } else { 'NO' })"
Write-Host "CHECK  bodies byte-identical : $bodiesIdentical"

# ---------------------------------------- 3. FAILURE CASE: BR3 mismatch -------
Write-Host ""
Write-Host "--- STEP 3: FAILURE CASE - same key, CHANGED payload (BR3) -------------------"
Write-Host "Expected: HTTP 422 IDEMPOTENCY_PAYLOAD_MISMATCH as application/problem+json."
Write-Host "A key is a receipt for ONE specific request, not a blank cheque."
$mismatchBody = Join-Path $tmp 'provision-changed.json'
Write-Json @{ tenantId = $tenantId; type = 'VM'; cpu = 8; ramGb = 8; durationSeconds = 7200 } $mismatchBody
Show "curl -s -D - -X POST $ResourceUrl/api/v1/resources -H 'Idempotency-Key: $keySeq' --data '@provision-changed.json'  # cpu 4 -> 8"
$mmRaw = & curl -sS -D "$tmp/mm.headers" -o "$tmp/mm.body" -w "%{http_code}" `
             -X POST "$ResourceUrl/api/v1/resources" -H "Content-Type: application/json" `
             -H "Idempotency-Key: $keySeq" --data "@$mismatchBody"
Write-Host "response headers:"
Get-Content "$tmp/mm.headers"
Write-Host "response body:"
Get-Content "$tmp/mm.body" -Raw
Write-Host "HTTP $mmRaw"

Write-Host ""
Write-Host "the original record must be UNTOUCHED by the rejected attempt:"
Res-Sql "select idempotency_key, state, response_status, request_fingerprint from idempotency_records where idempotency_key = '$keySeq';"

# ------------------------------- 4. CONCURRENT duplicates (TR-5) -------------
Write-Host ""
Write-Host "--- STEP 4: $CONCURRENT_DUPLICATES SIMULTANEOUS duplicates, one shared key (TR-5) --------------"
Write-Host "This is the case a sequential test cannot reach: all $CONCURRENT_DUPLICATES callers race for the"
Write-Host "same primary key. Exactly one may win and do the work; the rest must replay it."
$concBody = Join-Path $tmp 'provision-conc.json'
Write-Json @{ tenantId = $tenantId; type = 'CONTAINER'; cpu = 2; ramGb = 4 } $concBody

$startAt = (Get-Date).AddSeconds(3)
Write-Host "start gun at: $($startAt.ToString('HH:mm:ss.fff'))"

$concResults = 1..$CONCURRENT_DUPLICATES | ForEach-Object -ThrottleLimit $CONCURRENT_DUPLICATES -Parallel {
    $i       = $_
    $tmpDir  = $using:tmp
    $hdr     = Join-Path $tmpDir "conc-$i.headers"
    $bdy     = Join-Path $tmpDir "conc-$i.body"

    while ((Get-Date) -lt $using:startAt) { Start-Sleep -Milliseconds 2 }

    $sent = (Get-Date).ToString('HH:mm:ss.fff')
    $code = & curl -sS -D $hdr -o $bdy -w "%{http_code}" `
                 -X POST "$($using:ResourceUrl)/api/v1/resources" -H "Content-Type: application/json" `
                 -H "Idempotency-Key: $($using:keyConc)" --data "@$($using:concBody)"

    $json = $null
    try { $json = (Get-Content $bdy -Raw) | ConvertFrom-Json } catch { }
    [pscustomobject]@{
        Worker     = $i
        SentAt     = $sent
        Status     = [int]$code
        ResourceId = $json.resourceId
        Replayed   = [bool](Select-String -Path $hdr -Pattern 'Idempotency-Replayed' -SimpleMatch -Quiet)
    }
}

Write-Host ""
$concResults | Sort-Object SentAt | Format-Table Worker, SentAt, Status, Replayed, ResourceId -AutoSize | Out-String -Width 220 | Write-Host

$conc202     = ($concResults | Where-Object Status -eq 202).Count
$distinctRes = ($concResults | Where-Object { $_.ResourceId } | Select-Object -ExpandProperty ResourceId -Unique)
$replayCount = ($concResults | Where-Object Replayed).Count

Write-Host "COUNTED RESULT"
Write-Host "  HTTP 202 responses        : $conc202   (expected $CONCURRENT_DUPLICATES)"
Write-Host "  distinct resourceIds seen : $($distinctRes.Count)   (expected 1 - all callers describe ONE resource)"
Write-Host "  flagged Idempotency-Replayed: $replayCount   (the winner is not flagged; the losers are)"
Write-Host "  the single resourceId     : $($distinctRes -join ', ')"

Start-Sleep -Seconds 3

# -------------------------------------------------------- 5. DB state after ---
Write-Host ""
Write-Host "--- STEP 5: database state AFTER (also saved to db-after.txt) ----------------"
$after = Join-Path $OutDir 'db-after.txt'
"CHALLENGE 2 - DB STATE AFTER  ($((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')) UTC)`ntenant: $tenantId`n" | Set-Content $after -Encoding utf8
"--- idempotency_records for this run's two keys (expect exactly 2: one per key) ---" | Out-File $after -Encoding utf8 -Append
Res-Sql "select idempotency_key, state, response_status from idempotency_records where idempotency_key in ('$keySeq','$keyConc') order by idempotency_key;" $after
"--- resources for this tenant (expect exactly 2: one sequential + one concurrent) ---" | Out-File $after -Encoding utf8 -Append
Res-Sql "select resource_id, type, cpu, ram_gb, status from resources where tenant_id = '$tenantId' order by type;" $after
"--- reservations for this tenant (expect exactly 2) ---" | Out-File $after -Encoding utf8 -Append
Res-Sql "select reservation_id, status from reservations where tenant_id = '$tenantId';" $after
"--- quota_db: usage. 4+2=6 CPU, 2 instances. NOT 4+(10x2) and NOT doubled ---" | Out-File $after -Encoding utf8 -Append
Quota-Sql "select cpu_used, ram_used_gb, instance_used from tenant_quota where tenant_id = '$tenantId';" $after
"--- quota_db: ledger rows for this tenant (expect exactly 2) ---" | Out-File $after -Encoding utf8 -Append
Quota-Sql "select count(*) as ledger_rows from quota_reservations where tenant_id = '$tenantId';" $after
Get-Content $after

# ------------------------------------------------------------- 6. verdict -----
$resCount = [int](& psql -v ON_ERROR_STOP=1 -U resource_user -h $DbHost -p $DbPort -d resource_db -t -A -c "select count(*) from resources where tenant_id = '$tenantId';")
$idemCount = [int](& psql -v ON_ERROR_STOP=1 -U resource_user -h $DbHost -p $DbPort -d resource_db -t -A -c "select count(*) from idempotency_records where idempotency_key in ('$keySeq','$keyConc');")
$cpuUsed  = [int](& psql -v ON_ERROR_STOP=1 -U quota_user -h $DbHost -p $DbPort -d quota_db -t -A -c "select cpu_used from tenant_quota where tenant_id = '$tenantId';")

$pass = $replayedHeader -and $bodiesIdentical -and ($mmRaw -eq '422') -and
        ($conc202 -eq $CONCURRENT_DUPLICATES) -and ($distinctRes.Count -eq 1) -and
        ($resCount -eq 2) -and ($idemCount -eq 2) -and ($cpuUsed -eq 6)

Write-Host ""
Write-Host "============================================================================="
Write-Host "FINAL CHECKS"
Write-Host "  BR2 sequential replay returned the identical body   : $bodiesIdentical"
Write-Host "  BR2 replay flagged via Idempotency-Replayed header  : $([bool]$replayedHeader)"
Write-Host "  BR3 changed payload rejected with 422               : $($mmRaw -eq '422')"
Write-Host "  BR2 all $CONCURRENT_DUPLICATES concurrent duplicates got one resource    : $($distinctRes.Count -eq 1)"
Write-Host "  resources rows for tenant = 2 (not 12)              : $($resCount -eq 2)   [actual $resCount]"
Write-Host "  idempotency_records = 2 (one per key)               : $($idemCount -eq 2)   [actual $idemCount]"
Write-Host "  quota cpu_used = 6 (4+2, charged once each)         : $($cpuUsed -eq 6)   [actual $cpuUsed]"
Write-Host ("VERDICT: {0}" -f $(if ($pass) { 'PASS - provisioning is idempotent; a reused key with a changed payload is rejected' } else { 'FAIL - see checks above' }))
Write-Host "Finished (UTC): $((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss'))"
Write-Host "============================================================================="

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
if (-not $pass) { exit 1 }
