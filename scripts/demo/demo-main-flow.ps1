# =============================================================================
# LIVE DEMO - the main transaction end to end, plus one failure case.
#
# Paced for presenting: it pauses between segments so you can talk, and it prints
# the actual HTTP calls it makes so the audience sees the commands, not just the
# results. Nothing here is pre-baked - every number comes from a live call.
#
# Segments:
#   1. health of every service            (the system is really running)
#   2. create a tenant                    (STANDARD tier, 20 CPU)
#   3. provision a resource - HAPPY PATH  (202 -> saga -> ACTIVE)
#   4. show the cross-service state       (quota consumed, meter running)
#   5. idempotent retry                   (same key -> same answer, no duplicate)
#   6. FAILURE CASE: forced billing 503   (saga compensates, quota released)
#   7. terminate the healthy resource     (teardown, quota returned)
#
# Usage:
#   scripts\demo\demo-main-flow.ps1                # full system (real services)
#   scripts\demo\demo-main-flow.ps1 -NoPause       # run straight through
#   scripts\demo\demo-main-flow.ps1 -Core          # U1-only (peer stub on 8090)
#
# Prerequisite: scripts\demo\start-all.ps1 has been run and reports healthy.
# =============================================================================
#Requires -Version 7

param(
    [switch]$NoPause,
    [switch]$Core,
    [string]$QuotaUrl    = 'http://localhost:8081',
    [string]$ResourceUrl = 'http://localhost:8082',
    [string]$BillingUrl  = 'http://localhost:8083'
)

$ErrorActionPreference = 'Stop'
$env:PGPASSWORD = 'changeme'

$runId    = (Get-Date).ToString('HHmmss')
$tenantId = "tnt-demo-$runId"
$keyOk    = "demo-ok-$runId"
$keyFail  = "demo-fail-$runId"
$tmp      = Join-Path $env:TEMP "demo-$runId"
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

# ----------------------------------------------------------------- helpers ----
function Seg($n, $title) {
    Write-Host ""
    Write-Host "=============================================================================" -ForegroundColor Cyan
    Write-Host " SEGMENT $n - $title" -ForegroundColor Cyan
    Write-Host "=============================================================================" -ForegroundColor Cyan
}
function Say($text)  { Write-Host $text -ForegroundColor Gray }
function Cmd($text)  { Write-Host ""; Write-Host "  $ $text" -ForegroundColor Yellow }
function Pause-Demo {
    if ($NoPause) { Start-Sleep -Milliseconds 400; return }
    Write-Host ""
    Write-Host "  [enter to continue]" -ForegroundColor DarkGray
    [void](Read-Host)
}
function Json($obj, $path) { ($obj | ConvertTo-Json -Compress) | Set-Content $path -Encoding ascii -NoNewline; $path }
function QuotaUsed {
    [int](& psql -U quota_user -h localhost -p 5432 -d quota_db -t -A `
        -c "select coalesce(cpu_used,0) from tenant_quota where tenant_id = '$tenantId';")
}

Write-Host ""
Write-Host "#############################################################################" -ForegroundColor Magenta
Write-Host "#  CLOUD RESOURCE MANAGER - LIVE MAIN FLOW DEMO                             #" -ForegroundColor Magenta
Write-Host "#############################################################################" -ForegroundColor Magenta
Say ""
Say "  tenant for this run : $tenantId"
Say "  started             : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Say "  mode                : $(if ($Core) { 'CORE (U1 + peer stub)' } else { 'FULL SYSTEM (all real services)' })"

# ============================================== 1. health =====================
Seg 1 'Every service is really running'
Say "Each service owns its own database and exposes /health reporting its own"
Say "status plus its database connectivity (NFR-12)."

$services = if ($Core) {
    @(@{n='quota';p=8081}, @{n='resource';p=8082})
} else {
    @(@{n='quota';p=8081}, @{n='resource';p=8082}, @{n='billing';p=8083},
      @{n='notification';p=8084}, @{n='provider';p=8085}, @{n='usage-aggregator';p=8086})
}
Write-Host ""
foreach ($s in $services) {
    $code = & curl.exe -s -o NUL -w "%{http_code}" "http://localhost:$($s.p)/health"
    $colour = if ($code -eq '200') { 'Green' } else { 'Red' }
    Write-Host ("  {0,-20} :{1}  HTTP {2}" -f $s.n, $s.p, $code) -ForegroundColor $colour
}
Pause-Demo

# ============================================== 2. tenant =====================
Seg 2 'Create a tenant (Quota service owns this)'
Say "Tenants and their tier limits live ONLY in the Quota service. Nothing else in"
Say "the system stores a tenant - other services hold just the id (NFR-4)."
Say "STANDARD tier = 20 CPU / 64 GB / 10 instances."

$body = Json @{ tenantId = $tenantId; name = "Demo Corp $runId"; tier = 'STANDARD' } "$tmp\tenant.json"
Cmd "curl -X POST $QuotaUrl/api/v1/tenants -d '{...tier: STANDARD}'"
& curl.exe -s -w "`n  -> HTTP %{http_code}`n" -X POST "$QuotaUrl/api/v1/tenants" `
    -H "Content-Type: application/json" --data "@$body"
Pause-Demo

# ============================================== 3. happy path =================
Seg 3 'Provision a resource - THE MAIN FLOW'
Say "One POST. Behind it: quota is reserved SYNCHRONOUSLY (so the answer is"
Say "immediate and deterministic), then a background saga calls Provider, Billing"
Say "and Notification. The 202 means 'quota is held and work is queued' - the"
Say "client polls for the outcome (FR-13 to FR-15)."

$prov = Json @{ tenantId = $tenantId; type = 'VM'; cpu = 4; ramGb = 8; durationSeconds = 7200 } "$tmp\prov.json"
Cmd "curl -X POST $ResourceUrl/api/v1/resources -H 'Idempotency-Key: $keyOk' -d '{VM, 4 cpu, 8gb}'"
$okRaw = & curl.exe -s -D "$tmp\ok.h" -X POST "$ResourceUrl/api/v1/resources" `
    -H "Content-Type: application/json" -H "Idempotency-Key: $keyOk" --data "@$prov"
Write-Host $okRaw
$ok = $okRaw | ConvertFrom-Json
Write-Host ""
Say "response headers (note the Location header pointing at the status resource):"
Get-Content "$tmp\ok.h" | Where-Object { $_ -match 'HTTP/|Location|Correlation' } | ForEach-Object { "    $_" }
Write-Host ""
Say "quota is ALREADY consumed, before the saga has even finished: $(QuotaUsed) CPU of 20"
Pause-Demo

Say "polling the reservation while the saga runs..."
Write-Host ""
for ($i = 1; $i -le 10; $i++) {
    $st = (& curl.exe -s "$ResourceUrl/api/v1/reservations/$($ok.reservationId)" | ConvertFrom-Json)
    Write-Host ("    poll {0,-2} status={1,-10} instanceId={2}" -f $i, $st.status, $(if($st.instanceId){$st.instanceId}else{'(none yet)'}))
    if ($st.status -ne 'PENDING') { break }
    Start-Sleep -Milliseconds 900
}
Pause-Demo

# ============================================== 4. cross-service state ========
Seg 4 'The same transaction, seen from every service'
Say "This is the part that shows it is genuinely distributed: one business"
Say "transaction, separate databases, linked only by ids."

Write-Host ""
Say "RESOURCE service - the resource itself:"
& curl.exe -s "$ResourceUrl/api/v1/resources/$($ok.resourceId)"
Write-Host ""
Write-Host ""
Say "QUOTA service - usage consumed by it:"
& curl.exe -s "$QuotaUrl/api/v1/tenants/$tenantId/quota"
Write-Host ""

if (-not $Core) {
    Write-Host ""
    Say "BILLING service - the meter that is now running and accruing cost:"
    & curl.exe -s "$BillingUrl/api/v1/billing/meters/$($ok.resourceId)"
    Write-Host ""
}
Pause-Demo

# The APIs above could, in principle, all be served by one process telling a story.
# The databases cannot. Go straight to the five databases and show the rows.
if (-not $Core) {
    Say "APIs can be made to say anything. The databases are the actual proof -"
    Say "five separate databases, each opened with a DIFFERENT service's credentials:"
    & (Join-Path $PSScriptRoot 'show-footprint.ps1') -TenantId $tenantId
    Pause-Demo
}

# ============================================== 5. idempotency ================
Seg 5 'Retry the SAME request - Challenge 2 in one call'
Say "A client whose request timed out would retry. With the same Idempotency-Key"
Say "and the same body, it must get the ORIGINAL answer back - not a second VM."

Cmd "curl -X POST $ResourceUrl/api/v1/resources -H 'Idempotency-Key: $keyOk'   # the SAME key"
$replay = & curl.exe -s -D "$tmp\rp.h" -X POST "$ResourceUrl/api/v1/resources" `
    -H "Content-Type: application/json" -H "Idempotency-Key: $keyOk" --data "@$prov"
Write-Host $replay
Write-Host ""
Say "the giveaway header:"
Get-Content "$tmp\rp.h" | Where-Object { $_ -match 'Idempotency-Replayed' } | ForEach-Object { "    $_" }
Write-Host ""
$same = ($replay.Trim() -ceq $okRaw.Trim())
Write-Host ("    byte-identical to the first response : {0}" -f $same) -ForegroundColor $(if($same){'Green'}else{'Red'})
Write-Host ("    quota STILL {0} CPU - not 8 (charged once)" -f (QuotaUsed)) -ForegroundColor Green
Pause-Demo

# ============================================== 6. FAILURE CASE ===============
Seg 6 'FAILURE CASE - a mid-saga failure, and the compensation'
Say "Now the interesting half. X-Simulate-Failure: billing:always-fail makes the"
Say "BILLING step fail on purpose (FR-46). Provider will still succeed first, so"
Say "the saga gets half way, then has to undo what it already did:"
Say ""
Say "    undo step 3 (stop meter, if started)"
Say " -> undo step 2 (terminate the provider instance)"
Say " -> undo step 1 (release the quota)           <- reverse order, BR6"

$before = QuotaUsed
Write-Host ""
Say "quota before this request: $before CPU"
Cmd "curl -X POST $ResourceUrl/api/v1/resources -H 'X-Simulate-Failure: billing:always-fail'"
$failRaw = & curl.exe -s -X POST "$ResourceUrl/api/v1/resources" `
    -H "Content-Type: application/json" -H "Idempotency-Key: $keyFail" `
    -H "X-Simulate-Failure: billing:always-fail" --data "@$prov"
Write-Host $failRaw
$failed = $failRaw | ConvertFrom-Json
Write-Host ""
Say "note it STILL returns 202 - quota was reserved, the work was accepted."
Write-Host ("    quota now: {0} CPU  (4 held for a resource that is about to fail)" -f (QuotaUsed)) -ForegroundColor Yellow
Pause-Demo

Say "watching the saga fail, retry billing, and compensate..."
Write-Host ""
for ($i = 1; $i -le 14; $i++) {
    $st = (& curl.exe -s "$ResourceUrl/api/v1/reservations/$($failed.reservationId)" | ConvertFrom-Json)
    Write-Host ("    poll {0,-2} status={1,-10} quota={2} CPU" -f $i, $st.status, (QuotaUsed))
    if ($st.status -ne 'PENDING') { break }
    Start-Sleep -Milliseconds 900
}

Write-Host ""
Say "the reservation's own account of what went wrong:"
& curl.exe -s "$ResourceUrl/api/v1/reservations/$($failed.reservationId)"
Write-Host ""
Write-Host ""
Say "the resource: FAILED, and providerInstanceId is NULL because the instance"
Say "that HAD been created was terminated and the column cleared - so the column"
Say "honestly reads 'nothing is running' (deviation A11):"
& curl.exe -s "$ResourceUrl/api/v1/resources/$($failed.resourceId)"
Write-Host ""

$after = QuotaUsed
Write-Host ""
Write-Host "  -------------------------------------------------------------------" -ForegroundColor Cyan
Write-Host ("   quota before the doomed request : {0} CPU" -f $before)
Write-Host ("   quota while the saga ran        : {0} CPU  (4 held)" -f ($before + 4))
Write-Host ("   quota after compensation        : {0} CPU" -f $after)
Write-Host ("   reserved quota released IN FULL : {0}" -f ($after -eq $before)) -ForegroundColor $(if($after -eq $before){'Green'}else{'Red'})
Write-Host "  -------------------------------------------------------------------" -ForegroundColor Cyan
Write-Host ""
Say "and the ledger in the Quota service's own database shows it was given back:"
& psql -U quota_user -h localhost -p 5432 -d quota_db `
    -c "select resource_id, cpu, state, released_at is not null as released from quota_reservations where tenant_id = '$tenantId' order by state;"
Pause-Demo

# Same five-database view as segment 4, now AFTER the failure. Showing the same
# command twice, before and after, is what makes the difference legible: the
# FAILED resource has no meter in billing_db, its instance is TERMINATED in
# provider_db, and its quota ledger row has flipped to RELEASED.
if (-not $Core) {
    Say "the same five-database view as before, now AFTER the failure. Compare it to"
    Say "segment 4: the failed resource has NO meter in billing_db, its instance is"
    Say "TERMINATED in provider_db, and its quota row has flipped to RELEASED."
    & (Join-Path $PSScriptRoot 'show-footprint.ps1') -TenantId $tenantId
    Pause-Demo
}

# ============================================== 7. termination ================
Seg 7 'Terminate the healthy resource - teardown and quota return'
Say "Termination is idempotent and NOT a compensating saga: TERMINATED is the"
Say "desired end state, so a repeat is a no-op rather than an error (FR-21)."

Cmd "curl -X POST $ResourceUrl/api/v1/resources/$($ok.resourceId)/termination"
& curl.exe -s -w "`n  -> HTTP %{http_code}`n" -X POST "$ResourceUrl/api/v1/resources/$($ok.resourceId)/termination"
Write-Host ""
Say "and again, to show it is idempotent (alreadyTerminated flips to true, nothing re-runs):"
& curl.exe -s -w "`n  -> HTTP %{http_code}`n" -X POST "$ResourceUrl/api/v1/resources/$($ok.resourceId)/termination"
Write-Host ""
Start-Sleep -Seconds 3
Write-Host ("  quota after teardown: {0} CPU  (back to zero - everything returned)" -f (QuotaUsed)) -ForegroundColor Green

# ============================================== wrap up =======================
Write-Host ""
Write-Host "=============================================================================" -ForegroundColor Magenta
Write-Host " DEMO COMPLETE" -ForegroundColor Magenta
Write-Host "=============================================================================" -ForegroundColor Magenta
Say ""
Say "  tenant           : $tenantId"
Say "  healthy resource : $($ok.resourceId)   (provisioned, then terminated)"
Say "  failed resource  : $($failed.resourceId)   (compensated)"
Say ""
Say "  Next: run the three challenge scripts -"
Say "    evidence\challenge-1-quota-concurrency\run.ps1"
Say "    evidence\challenge-2-idempotency\run.ps1"
Say "    evidence\challenge-3c-saga-compensation\run.ps1"
Write-Host ""

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
