#requires -Version 7.4
[CmdletBinding()]
param(
    [switch]$NoPause, [switch]$Core,
    [string]$QuotaUrl = 'http://localhost:8081',
    [string]$ResourceUrl = 'http://localhost:8082',
    [string]$BillingUrl = 'http://localhost:8083'
)
$ErrorActionPreference = 'Stop'
$tenant = "tnt-demo-$([guid]::NewGuid().ToString('N'))"
function Pause-Demo { if (-not $NoPause) { [void](Read-Host 'Press Enter to continue') } }
function Request([string]$Method, [string]$Url, [int]$Expected = 200, $Body = $null, [hashtable]$Headers = @{}) {
    $options = @{ Method=$Method; Uri=$Url; Headers=$Headers; TimeoutSec=15; SkipHttpErrorCheck=$true }
    if ($null -ne $Body) { $options.ContentType='application/json'; $options.Body=$Body | ConvertTo-Json }
    $response = Invoke-WebRequest @options
    if ($response.StatusCode -ne $Expected) { throw "$Method $Url returned $($response.StatusCode): $($response.Content)" }
    if ($response.Content) { return $response.Content | ConvertFrom-Json }
}
function Await-Reservation([string]$Id, [string]$Status) {
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        $result = Request Get "$ResourceUrl/api/v1/reservations/$Id"
        if ($result.status -eq $Status) { return $result }
        if ($result.status -ne 'PENDING') { throw "Reservation finished as $($result.status): $($result.failureReason)" }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for reservation $Id"
}
Write-Host "Tenant: $tenant"
Request Post "$QuotaUrl/api/v1/tenants" 201 @{tenantId=$tenant;name='Demo';tier='STANDARD'} | Format-List
Pause-Demo
$body = @{tenantId=$tenant;type='VM';cpu=4;ramGb=8;durationSeconds=7200}
$key = [guid]::NewGuid().ToString()
$accepted = Request Post "$ResourceUrl/api/v1/resources" 202 $body @{'Idempotency-Key'=$key}
Await-Reservation $accepted.reservationId 'COMPLETED' | Format-List
Request Get "$ResourceUrl/api/v1/resources/$($accepted.resourceId)" | Format-List
if (-not $Core) { Request Get "$BillingUrl/api/v1/billing/meters/$($accepted.resourceId)" | Format-List }
Pause-Demo
$replay = Request Post "$ResourceUrl/api/v1/resources" 202 $body @{'Idempotency-Key'=$key}
if ($replay.reservationId -ne $accepted.reservationId -or $replay.resourceId -ne $accepted.resourceId) { throw 'Replay created another operation' }
Write-Host 'Replay returned the original reservation and resource.'
Pause-Demo
$failed = Request Post "$ResourceUrl/api/v1/resources" 202 $body @{
    'Idempotency-Key'=[guid]::NewGuid().ToString();'X-Simulate-Failure'='billing:always-fail'
}
Await-Reservation $failed.reservationId 'FAILED' | Format-List
Pause-Demo
Request Post "$ResourceUrl/api/v1/resources/$($accepted.resourceId)/termination" 202 | Format-List
$deadline = [DateTime]::UtcNow.AddSeconds(90)
do {
    $quota = Request Get "$QuotaUrl/api/v1/tenants/$tenant/quota"
    if ($quota.cpu.used -eq 0) { break }
    Start-Sleep -Milliseconds 250
} while ([DateTime]::UtcNow -lt $deadline)
if ($quota.cpu.used -ne 0) { throw 'Termination did not release quota' }
if (-not $Core) {
    $meter = Request Get "$BillingUrl/api/v1/billing/meters/$($accepted.resourceId)"
    if ($meter.state -ne 'STOPPED') { throw 'Terminated resource is still billed' }
}
Write-Host 'Demo passed: provision, replay, compensation and termination.'
Write-Host "Inspect database records: pwsh scripts/demo/show-footprint.ps1 -TenantId $tenant -ProveIsolation"
