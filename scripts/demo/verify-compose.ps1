#requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipRestart,
    [ValidateRange(60, 1800)][int]$WaitTimeoutSeconds = 300,
    [string]$EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $EvidenceDirectory) {
    $EvidenceDirectory = Join-Path $repoRoot "evidence\compose\$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))"
}
$EvidenceDirectory = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($EvidenceDirectory)
$composeFile = Join-Path $repoRoot 'docker-compose.yml'
$services = @('postgres', 'quota', 'resource', 'billing', 'notification', 'provider', 'usage-aggregator', 'expiry-worker')

function Invoke-Compose {
    param([string[]]$Arguments)
    $output = & docker compose --project-directory $repoRoot -f $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') failed (exit $LASTEXITCODE)."
    }
    return $output
}

function Start-Stack {
    $arguments = @('up', '-d', '--wait', '--wait-timeout', "$WaitTimeoutSeconds")
    if (-not $SkipBuild) { $arguments += '--build' }
    Invoke-Compose $arguments | Out-Host
}

function Assert-Stack([string]$Phase) {
    $raw = Invoke-Compose @('ps', '--all', '--format', 'json')
    $json = $raw -join "`n"
    if ($json.TrimStart().StartsWith('[')) {
        $parsed = $json | ConvertFrom-Json
        $containers = @($parsed)
    } else {
        $containers = @($raw | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
    }
    if ($containers.Count -ne $services.Count) { throw "Expected eight containers, found $($containers.Count)." }
    foreach ($service in $services) {
        $container = @($containers | Where-Object { $_.Service -eq $service })
        if ($container.Count -ne 1 -or $container[0].State -ne 'running' -or $container[0].Health -ne 'healthy') {
            throw "$service is not running and healthy: $($container | ConvertTo-Json -Compress)"
        }
    }
    $containers | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $EvidenceDirectory "$Phase-containers.json")
    Invoke-Compose @('ps', '--all') | Out-Host
    foreach ($service in @('quota', 'resource', 'billing', 'provider', 'notification')) {
        $command = 'PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U "$1" -d "$2" -v ON_ERROR_STOP=1 -Atc "SELECT 1"'
        $result = Invoke-Compose @('exec', '-T', 'postgres', 'sh', '-c', $command, 'sh', "${service}_user", "${service}_db")
        if (($result -join '').Trim() -ne '1') { throw "Database login failed for $service." }
    }
    Write-Host 'All eight containers are healthy; all five service database logins work.'
}

function Request([string]$Method, [string]$Url, [int]$Expected = 200, $Body = $null, [hashtable]$Headers = @{}) {
    $options = @{ Method = $Method; Uri = $Url; Headers = $Headers; TimeoutSec = 15; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $options.ContentType = 'application/json'
        $options.Body = $Body | ConvertTo-Json
    }
    $response = Invoke-WebRequest @options
    if ([int]$response.StatusCode -ne $Expected) {
        throw "$Method $Url returned $($response.StatusCode): $($response.Content)"
    }
    return $response.Content | ConvertFrom-Json
}

function Assert-Workflow($Accepted, [string]$Tenant) {
    $resource = Request Get "http://localhost:8082/api/v1/resources/$($Accepted.resourceId)"
    if ($resource.status -ne 'ACTIVE' -or -not $resource.providerInstanceId) {
        throw "Resource is not ACTIVE with a provider instance: $($resource | ConvertTo-Json -Compress)"
    }
    $instances = @(Invoke-Compose @('exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'provider_db',
        '-v', 'ON_ERROR_STOP=1', '-Atc', "SELECT instance_id FROM instances WHERE status = 'ACTIVE'"))
    if ($instances -notcontains $resource.providerInstanceId) { throw 'Provider did not retain the ACTIVE instance.' }
    $quota = Request Get "http://localhost:8081/api/v1/tenants/$Tenant/quota"
    if ($quota.cpu.used -ne 4 -or $quota.ramGb.used -ne 8 -or $quota.instances.used -ne 1) {
        throw 'Quota does not reflect the provisioned resource.'
    }
    $meter = Request Get "http://localhost:8083/api/v1/billing/meters/$($Accepted.resourceId)"
    if ($meter.state -ne 'ACTIVE' -or $meter.tenantId -ne $Tenant) { throw 'Billing meter is not ACTIVE for this tenant.' }
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        $notifications = @(Request Get "http://localhost:8084/api/v1/notifications?tenantId=$Tenant")
        $active = @($notifications | Where-Object { $_.resourceId -eq $Accepted.resourceId -and $_.eventType -eq 'ACTIVE' })
        if ($active.Count -gt 0) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($active.Count -eq 0) { throw 'No ACTIVE lifecycle notification was recorded.' }
    $usage = Request Get "http://localhost:8086/api/v1/usage/$Tenant"
    if (-not $usage.availability.quota -or -not $usage.availability.resources -or -not $usage.availability.billing) {
        throw 'Usage aggregator reported an unavailable peer.'
    }
    if (@($usage.resources | Where-Object { $_.resourceId -eq $Accepted.resourceId }).Count -ne 1) {
        throw 'Usage aggregator did not return the ACTIVE resource.'
    }
    return @{
        tenantId = $Tenant; accepted = $Accepted; resource = $resource; quota = $quota
        meter = $meter; notification = $active[0]; usage = $usage
    }
}

function Provision-Resource {
    $tenant = "tnt-compose-$([guid]::NewGuid().ToString('N'))"
    Request Post 'http://localhost:8081/api/v1/tenants' 201 @{
        tenantId = $tenant; name = 'Compose verification'; tier = 'STANDARD'
    } | Out-Null
    $accepted = Request Post 'http://localhost:8082/api/v1/resources' 202 @{
        tenantId = $tenant; type = 'VM'; cpu = 4; ramGb = 8; durationSeconds = 86400
    } @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        $reservation = Request Get "http://localhost:8082/api/v1/reservations/$($accepted.reservationId)"
        if ($reservation.status -eq 'COMPLETED') { break }
        if ($reservation.status -ne 'PENDING') {
            throw "Saga finished as $($reservation.status): $($reservation.failureReason)"
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($reservation.status -ne 'COMPLETED') { throw 'Timed out waiting for the saga to complete.' }
    $result = Assert-Workflow $accepted $tenant
    Write-Host "Saga COMPLETED -> ACTIVE: tenant=$tenant resource=$($accepted.resourceId)"
    return $result
}

Get-Command docker -ErrorAction Stop | Out-Null
Invoke-Compose @('version') | Out-Host
# Validate without printing the interpolated configuration (which contains passwords).
Invoke-Compose @('config', '--quiet') | Out-Null
New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
Start-Transcript -Path (Join-Path $EvidenceDirectory 'verification.log') | Out-Null
try {
    Start-Stack
    Assert-Stack 'before-restart'
    $before = Provision-Resource
    $before | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $EvidenceDirectory 'before-restart-workflow.json')
    Invoke-Compose @('logs', '--no-color', '--tail', '100') |
        Set-Content (Join-Path $EvidenceDirectory 'before-restart-containers.log')
    if (-not $SkipRestart) {
        Write-Host 'Stopping and recreating this Compose stack WITHOUT deleting pgdata.'
        Invoke-Compose @('down') | Out-Host
        Start-Stack
        Assert-Stack 'after-restart'
        $reservation = Request Get "http://localhost:8082/api/v1/reservations/$($before.accepted.reservationId)"
        if ($reservation.status -ne 'COMPLETED') { throw 'Reservation did not survive restart.' }
        $after = Assert-Workflow $before.accepted $before.tenantId
        if ($after.resource.providerInstanceId -ne $before.resource.providerInstanceId -or
            $after.meter.meterId -ne $before.meter.meterId -or
            $after.notification.notificationId -ne $before.notification.notificationId) {
            throw 'Persisted provider, billing or notification identity changed after restart.'
        }
        $after | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $EvidenceDirectory 'after-restart-workflow.json')
        $fresh = Provision-Resource
        $fresh | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $EvidenceDirectory 'fresh-workflow.json')
        Write-Host 'Restart passed: records in all five databases persisted, and a fresh saga completed.'
    }
    Invoke-Compose @('logs', '--no-color', '--tail', '100') |
        Set-Content (Join-Path $EvidenceDirectory 'containers.log')
    Write-Host "Compose verification passed. Stack left running. Evidence: $EvidenceDirectory"
} catch {
    $failure = $_
    Write-Warning "Compose verification failed: $($failure.Exception.Message)"
    try {
        Invoke-Compose @('ps', '--all') | Out-Host
        Invoke-Compose @('logs', '--no-color', '--tail', '100') |
            Set-Content (Join-Path $EvidenceDirectory 'failure-containers.log')
    } catch {
        Write-Warning "Could not collect container diagnostics: $($_.Exception.Message)"
    }
    throw $failure
} finally {
    Stop-Transcript | Out-Null
}
