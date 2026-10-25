#requires -Version 7.4
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [string]$DbHost = 'localhost',
    [int]$DbPort = 5432,
    [string]$DbPassword = $(if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'changeme' }),
    [int]$BasePort = 18080
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/..").Path
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/java.exe' } else { 'bin/java' }) } else { 'java' }
$wrapper = if ($IsWindows) { 'mvnw.cmd' } else { 'mvnw' }
$evidence = Join-Path $repo 'evidence/integration'
New-Item -ItemType Directory -Force $evidence | Out-Null
$processes = @{}
$services = @('quota', 'resource', 'billing-service', 'notification-service', 'provider', 'usage-aggregator', 'expiry-worker')
$urls = @{}
for ($i = 0; $i -lt $services.Count; $i++) { $urls[$services[$i]] = "http://localhost:$($BasePort + $i + 1)" }
$tenant = "integration-$([guid]::NewGuid().ToString('N'))"
$correlation = "integration-$([guid]::NewGuid().ToString('N'))"
$previousPassword = $env:PGPASSWORD

function Require($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Call([string]$Method, [string]$Uri, [int]$Status = 200, $Body = $null, [hashtable]$Headers = @{}) {
    $Headers['X-Correlation-Id'] = $correlation
    $arguments = @{ Method = $Method; Uri = $Uri; Headers = $Headers; TimeoutSec = 15; SkipHttpErrorCheck = $true }
    if ($null -ne $Body) { $arguments.ContentType = 'application/json'; $arguments.Body = ConvertTo-Json -InputObject $Body -Depth 10 }
    $response = Invoke-WebRequest @arguments
    $content = if ($response.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($response.Content) } else { $response.Content }
    Require ($response.StatusCode -eq $Status) "$Method $Uri returned $($response.StatusCode): $content"
    if ($content) { return ConvertFrom-Json -InputObject $content -NoEnumerate }
}

function Wait-For([string]$Description, [scriptblock]$Check, [int]$Seconds = 60) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        if (& $Check) { return }
        foreach ($childName in $processes.Keys) {
            if ($processes[$childName].HasExited) { throw "$childName exited; inspect evidence/integration/$childName.log" }
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $Description"
}

function Start-ServiceJar([string]$Name) {
    $jar = @(Get-ChildItem "$repo/services/$Name/target/*.jar")
    Require ($jar.Count -eq 1) "Expected one packaged jar for $Name; run the build first"
    $settings = @{
        SERVER_PORT = "$($BasePort + [array]::IndexOf($services, $Name) + 1)"
        DB_HOST = $DbHost; DB_PORT = "$DbPort"; DB_PASSWORD = $DbPassword
        QUOTA_SERVICE_URL = $urls.quota; RESOURCE_SERVICE_URL = $urls.resource
        BILLING_SERVICE_URL = $urls.'billing-service'; NOTIFICATION_SERVICE_URL = $urls.'notification-service'
        PROVIDER_SERVICE_URL = $urls.provider; PROVIDER_FAILURE_RATE = '0'
        PROVIDER_DELAY_MIN_MS = '0'; PROVIDER_DELAY_MAX_MS = '0'; BILLING_FAILURE_RATE = '0'
        NOTIFICATION_FAILURE_RATE = '0'; EXPIRY_CHECK_INTERVAL_MS = '500'; RECOVERY_INTERVAL_MS = '500'
        LOG_LEVEL = 'INFO'; LOG_LEVEL_APP = 'INFO'; LOGGING_LEVEL_ROOT = 'INFO'
    }
    if ($Name -in @('quota', 'resource', 'billing-service', 'notification-service', 'provider')) {
        $db = $Name -replace '-service$', ''
        $settings.DB_NAME = "${db}_db"; $settings.DB_USER = "${db}_user"
    }
    $processes[$Name] = Start-Process -FilePath $java -ArgumentList @('-Xmx256m', '-jar', "`"$($jar[0].FullName)`"") `
        -Environment $settings -PassThru -RedirectStandardOutput "$evidence/$Name.log" -RedirectStandardError "$evidence/$Name-stderr.log"
}

function Read-Db([string]$Name, [string]$Sql) {
    $result = & psql -h $DbHost -p $DbPort -U "${Name}_user" -d "${Name}_db" -At -v ON_ERROR_STOP=1 -c $Sql
    if ($LASTEXITCODE -ne 0) { throw "Database check failed for $Name" }
    return $result
}

function Wait-Event([string]$ResourceId, [string]$EventType) {
    Wait-For "$EventType notification for $ResourceId" {
        $history = Call Get "$($urls.'notification-service')/api/v1/notifications?tenantId=$tenant"
        @($history | Where-Object { $_.resourceId -eq $ResourceId -and $_.eventType -eq $EventType -and $_.status -eq 'SENT' }).Count -ge 1
    }
}

function Wait-Cleanup([string]$ResourceId, [string]$InstanceId) {
    Wait-For "meter stop and quota release for $ResourceId" {
        $meter = Call Get "$($urls.'billing-service')/api/v1/billing/meters/$ResourceId"
        $quota = Call Get "$($urls.quota)/api/v1/tenants/$tenant/quota"
        $meter.state -eq 'STOPPED' -and $quota.cpu.used -eq 0 -and $quota.ramGb.used -eq 0 -and $quota.instances.used -eq 0
    }
    Require ((Read-Db provider "SELECT status FROM instances WHERE instance_id = '$InstanceId'") -eq 'TERMINATED') 'Provider instance is still active'
    Require ((Read-Db quota "SELECT state FROM quota_reservations WHERE resource_id = '$ResourceId'") -eq 'RELEASED') 'Quota ledger was not released'
    $stopped = Call Get "$($urls.'billing-service')/api/v1/billing/meters/$ResourceId"
    Start-Sleep -Milliseconds 1100
    $later = Call Get "$($urls.'billing-service')/api/v1/billing/meters/$ResourceId"
    Require ($stopped.currentCost -eq $later.currentCost -and $stopped.stoppedAt -eq $later.stoppedAt) 'Billing increased after termination'
}

Start-Transcript -Path "$evidence/output.txt" -Force | Out-Null
try {
    $env:PGPASSWORD = $DbPassword
    Write-Host "Integration test $([DateTime]::UtcNow.ToString('o')); tenant=$tenant"
    Write-Host 'Seven service processes, five PostgreSQL databases, no HTTP stubs.'
    if (-not $SkipBuild) {
        & "$repo/services/notification-service/$wrapper" -B -ntp -f "$repo/pom.xml" -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    }
    foreach ($name in $services) {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $BasePort + [array]::IndexOf($services, $name) + 1)
        try { $listener.Start() } finally { $listener.Stop() }
    }
    foreach ($name in $services) { Start-ServiceJar $name }
    foreach ($name in $services) {
        $health = '/health'
        Wait-For "$name startup" {
            try { (Call Get "$($urls[$name])$health").status -eq 'UP' } catch { $false }
        } 120
    }
    Call Post "$($urls.quota)/api/v1/tenants" 201 @{ tenantId = $tenant; name = 'Integration test'; tier = 'STANDARD' } | Out-Null
    $empty = Call Get "$($urls.'usage-aggregator')/api/v1/usage/$tenant"
    Require ($empty.availability.quota -and $empty.availability.resources -and $empty.availability.billing -and $empty.resources.Count -eq 0 -and $empty.cost.totalCost -eq 0) 'Empty tenant usage or available zero cost is wrong'

    $quotaUrl = $urls.quota
    $releaseAt = [DateTime]::UtcNow.AddSeconds(4)
    $reservations = 1..20 | ForEach-Object -Parallel {
        $start = $using:releaseAt
        while ([DateTime]::UtcNow -lt $start) { Start-Sleep -Milliseconds 25 }
        $payload = @{ tenantId = $using:tenant; resourceId = [guid]::NewGuid().ToString(); type = 'VM'; cpu = 4; ramGb = 8 } | ConvertTo-Json
        $answer = Invoke-WebRequest "$using:quotaUrl/api/v1/quota/reservations" -Method Post -ContentType 'application/json' `
            -Body $payload -SkipHttpErrorCheck -TimeoutSec 15
        @{ Status = $answer.StatusCode; Data = ($answer.Content | ConvertFrom-Json) }
    } -ThrottleLimit 20
    $winners = @($reservations | Where-Object Status -eq 200)
    Require ($winners.Count -eq 5 -and @($reservations | Where-Object Status -eq 422).Count -eq 15) 'Concurrent quota reservations exceeded or underfilled the CPU limit'
    foreach ($winner in $winners) {
        Call Post "$quotaUrl/api/v1/quota/reservations/$($winner.Data.resourceId)/release" | Out-Null
        $duplicate = Call Post "$quotaUrl/api/v1/quota/reservations/$($winner.Data.resourceId)/release"
        Require (-not $duplicate.released) 'Repeated release changed quota twice'
    }
    Write-Host 'PASS: 20 concurrent quota requests, five accepted at capacity, 15 rejected; releases apply once'

    $body = @{ tenantId = $tenant; type = 'VM'; cpu = 2; ramGb = 4 }
    $key = [guid]::NewGuid().ToString()
    $resourceUrl = $urls.resource
    $payload = $body | ConvertTo-Json
    $duplicates = 1..5 | ForEach-Object -Parallel {
        Invoke-RestMethod "$using:resourceUrl/api/v1/resources" -Method Post -ContentType 'application/json' `
            -Headers @{ 'Idempotency-Key' = $using:key; 'X-Correlation-Id' = $using:correlation } -Body $using:payload -TimeoutSec 15
    } -ThrottleLimit 5
    $accepted = $duplicates[0]
    Require ($duplicates.Count -eq 5 -and @($duplicates | Where-Object { $_.resourceId -ne $accepted.resourceId -or $_.reservationId -ne $accepted.reservationId }).Count -eq 0) 'Concurrent provisions did not share one reservation and resource'
    $resourceId = $accepted.resourceId
    Wait-For 'provisioning completion' { (Call Get "$($urls.resource)/api/v1/reservations/$($accepted.reservationId)").status -eq 'COMPLETED' }
    $resource = Call Get "$($urls.resource)/api/v1/resources/$resourceId"
    Wait-Event $resourceId 'ACTIVE'
    $replay = Call Post "$($urls.resource)/api/v1/resources" 202 $body @{ 'Idempotency-Key' = $key }
    Require ($replay.resourceId -eq $resourceId -and $replay.reservationId -eq $accepted.reservationId) 'Idempotent replay created a new resource'
    $changed = @{ tenantId = $tenant; type = 'VM'; cpu = 3; ramGb = 4 }
    $mismatch = Call Post "$($urls.resource)/api/v1/resources" 422 $changed @{ 'Idempotency-Key' = $key }
    Require ($mismatch.code -eq 'IDEMPOTENCY_PAYLOAD_MISMATCH') 'Payload mismatch was not rejected'
    $view = Call Get "$($urls.'usage-aggregator')/api/v1/usage/$tenant"
    Require ($view.resources.Count -eq 1 -and $view.resources[0].resourceId -eq $resourceId -and $view.quota.cpu.used -eq 2 -and $view.availability.billing) 'Usage did not compose the active resource and quota'
    Require ((Read-Db billing "SELECT count(*) FROM billing_meters WHERE resource_id = '$resourceId'") -eq 1) 'Replay created duplicate meters'
    Write-Host 'PASS: provisioning, ACTIVE notification, usage, available zero cost, replay and payload mismatch'

    Call Post "$($urls.resource)/api/v1/resources/$resourceId/termination" 202 | Out-Null
    Wait-Cleanup $resourceId $resource.providerInstanceId
    Wait-Event $resourceId 'TERMINATED'
    $repeat = Call Post "$($urls.resource)/api/v1/resources/$resourceId/termination" 202
    Require $repeat.alreadyTerminated 'Repeated termination was not idempotent'
    Require ((Read-Db billing "SELECT count(*) FROM billing_events WHERE resource_id = '$resourceId' AND event_type = 'STOP'") -eq 1) 'Repeated termination duplicated billing stop'
    Write-Host 'PASS: manual termination, provider shutdown, stable stopped cost, quota release, TERMINATED notification'

    $failed = Call Post "$($urls.resource)/api/v1/resources" 202 $body @{ 'Idempotency-Key' = [guid]::NewGuid().ToString(); 'X-Simulate-Failure' = 'billing:always-fail' }
    Wait-For 'billing failure compensation' { (Call Get "$($urls.resource)/api/v1/reservations/$($failed.reservationId)").status -eq 'FAILED' }
    $failure = Call Get "$($urls.resource)/api/v1/reservations/$($failed.reservationId)"
    Require ($failure.failureReason -like 'BILLING_UNAVAILABLE:*503*') "Wrong billing failure: $($failure.failureReason)"
    Wait-Event $failed.resourceId 'FAILED'
    Require ((Read-Db quota "SELECT state FROM quota_reservations WHERE resource_id = '$($failed.resourceId)'") -eq 'RELEASED') 'Saga failed to refund quota'
    Require ((Read-Db provider "SELECT status FROM instances WHERE client_token = 'ct-$($failed.resourceId)'") -eq 'TERMINATED') 'Saga did not terminate its provider instance'
    Require ((Read-Db billing "SELECT count(*) FROM billing_meters WHERE resource_id = '$($failed.resourceId)'") -eq 0) 'Failed billing created a meter'
    Write-Host 'PASS: real Billing 503, saga compensation, provider cleanup, quota refund and FAILED notification'

    $expiring = @{ tenantId = $tenant; type = 'VM'; cpu = 2; ramGb = 4; durationSeconds = 30 }
    $expiry = Call Post "$($urls.resource)/api/v1/resources" 202 $expiring @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
    Wait-For 'expiring resource activation' { (Call Get "$($urls.resource)/api/v1/reservations/$($expiry.reservationId)").status -eq 'COMPLETED' }
    $held = Call Get "$($urls.resource)/api/v1/resources/$($expiry.resourceId)"
    Wait-Event $expiry.resourceId 'ACTIVE'
    Wait-For 'scheduled expiry termination' { (Call Get "$($urls.resource)/api/v1/resources/$($expiry.resourceId)").status -eq 'TERMINATED' } 60
    Wait-Cleanup $expiry.resourceId $held.providerInstanceId
    Wait-Event $expiry.resourceId 'TERMINATED'
    $final = Call Get "$($urls.'usage-aggregator')/api/v1/usage/$tenant"
    Require ($final.resources.Count -eq 0 -and $final.quota.cpu.used -eq 0 -and $final.cost.totalCost -gt 0) 'Final usage did not reflect termination and accrued cost'
    Write-Host 'PASS: scheduled expiry through real Resource, provider shutdown, meter stop, quota release and notification'

    $recovering = Call Post "$($urls.resource)/api/v1/resources" 202 $body @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
    Wait-For 'recovery fixture activation' { (Call Get "$($urls.resource)/api/v1/reservations/$($recovering.reservationId)").status -eq 'COMPLETED' }
    $recoveringResource = Call Get "$($urls.resource)/api/v1/resources/$($recovering.resourceId)"
    $processes['billing-service'].Kill($true); $processes['billing-service'].WaitForExit(); $processes.Remove('billing-service')
    Call Post "$($urls.resource)/api/v1/resources/$($recovering.resourceId)/termination" 202 | Out-Null
    $processes.resource.Kill($true); $processes.resource.WaitForExit(); $processes.Remove('resource')
    Start-ServiceJar 'resource'
    Start-ServiceJar 'billing-service'
    Wait-For 'Resource restart' { try { (Call Get "$($urls.resource)/health").status -eq 'UP' } catch { $false } }
    Wait-For 'Billing restart' { try { (Call Get "$($urls.'billing-service')/health").status -eq 'UP' } catch { $false } }
    Wait-Cleanup $recovering.resourceId $recoveringResource.providerInstanceId
    Wait-Event $recovering.resourceId 'TERMINATED'
    Write-Host 'PASS: durable teardown survives Resource restart during Billing outage'

    $processes['billing-service'].Kill($true); $processes['billing-service'].WaitForExit(); $processes.Remove('billing-service')
    $partial = Call Get "$($urls.'usage-aggregator')/api/v1/usage/$tenant"
    Require (-not $partial.availability.billing -and $partial.availability.quota -and $partial.availability.resources -and $null -eq $partial.cost) 'Billing outage lost healthy usage sections'
    foreach ($name in @('quota', 'resource')) { $processes[$name].Kill($true); $processes[$name].WaitForExit(); $processes.Remove($name) }
    $unavailable = Call Get "$($urls.'usage-aggregator')/api/v1/usage/$tenant"
    Require (-not $unavailable.availability.billing -and -not $unavailable.availability.quota -and -not $unavailable.availability.resources) 'Total outage availability is wrong'
    Require ((Call Get "$($urls.'expiry-worker')/health").status -eq 'UP') 'Worker stopped during Resource outage'
    Write-Host 'PASS: partial and total peer outage; worker remains running'
    Write-Host 'RESULT: integration test passed'
} finally {
    foreach ($process in $processes.Values) { if (-not $process.HasExited) { $process.Kill($true); $process.WaitForExit() } }
    $env:PGPASSWORD = $previousPassword
    Stop-Transcript | Out-Null
}
