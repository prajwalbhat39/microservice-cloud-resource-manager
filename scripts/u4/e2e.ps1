#requires -Version 7.4
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [string]$DbHost = 'localhost',
    [int]$DbPort = 5432,
    [string]$DbName = 'notification_db',
    [string]$DbUser = 'notification_user',
    [string]$DbPassword = $(if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'changeme' }),
    [int]$NotificationPort = 18084,
    [int]$UsagePort = 18086,
    [int]$WorkerPort = 18087,
    [int]$StubPort = 18090
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$wrapper = if ($IsWindows) { 'mvnw.cmd' } else { 'mvnw' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/java.exe' } else { 'bin/java' }) } else { 'java' }
$evidence = Join-Path $repo 'evidence/u4'
New-Item -ItemType Directory -Force $evidence | Out-Null
$processes = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$ids = @{}
$stub = "http://localhost:$StubPort"
$notification = "http://localhost:$NotificationPort"
$usage = "http://localhost:$UsagePort"
$tenant = "u4-e2e-$([guid]::NewGuid().ToString('N'))"
$resourceId = [guid]::NewGuid().ToString()

function Require($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Wait-Http([string]$Url) {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 2 -SkipHttpErrorCheck
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        foreach ($process in $processes) {
            if ($process.HasExited) { throw "Child process $($process.Id) exited; inspect evidence/u4/*.log" }
        }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Url"
}

function Start-Java([string]$Name, [string]$Jar, [hashtable]$Environment, [string[]]$Extra = @()) {
    $process = Start-Process -FilePath $java -ArgumentList (@('-jar', "`"$Jar`"") + $Extra) -PassThru `
        -Environment $Environment -RedirectStandardOutput "$evidence/$Name.log" `
        -RedirectStandardError "$evidence/$Name-stderr.log"
    $processes.Add($process)
}

function Set-Stub([string]$Key, [string]$Method, [string]$Path, $Body, [int]$Status = 200) {
    $exists = $ids.ContainsKey($Key)
    if (-not $exists) { $ids[$Key] = [guid]::NewGuid().ToString() }
    $mapping = @{
        id = $ids[$Key]
        request = @{ method = $Method; urlPath = $Path }
        response = @{ status = $Status; headers = @{ 'Content-Type' = 'application/json' }; jsonBody = $Body }
    } | ConvertTo-Json -Depth 20
    $uri = if ($exists) { "$stub/__admin/mappings/$($ids[$Key])" } else { "$stub/__admin/mappings" }
    $verb = if ($exists) { 'Put' } else { 'Post' }
    Invoke-RestMethod -Uri $uri -Method $verb -ContentType 'application/json' -Body $mapping | Out-Null
}

function Send-Event([string]$Type) {
    $body = @{ tenantId = $tenant; resourceId = $resourceId; eventType = $Type; message = "U4 $Type fixture" } | ConvertTo-Json
    $result = Invoke-WebRequest -Uri "$notification/api/v1/notifications" -Method Post `
        -Headers @{ 'X-Correlation-Id' = 'u4-e2e-events' } -ContentType 'application/json' -Body $body
    Require ($result.StatusCode -eq 202) "Notification $Type was not accepted"
    Require (($result.Content | ConvertFrom-Json).status -eq 'SENT') "Notification $Type delivery failed"
}

Start-Transcript -Path "$evidence/e2e-output.txt" -Force | Out-Null
try {
    Write-Host "End-to-end test $(Get-Date -AsUTC -Format o)"
    Write-Host 'Services: Notification, Usage Aggregator, Expiry Worker. Dependencies: PostgreSQL and WireMock.'
    Write-Host "COMMAND: pwsh scripts/u4/e2e.ps1 -SkipBuild:$SkipBuild -DbHost $DbHost -DbPort $DbPort -DbName $DbName -DbUser $DbUser"
    foreach ($port in @($NotificationPort, $UsagePort, $WorkerPort, $StubPort)) {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
        try { $listener.Start() } finally { $listener.Stop() }
    }
    foreach ($service in 'notification-service', 'usage-aggregator', 'expiry-worker') {
        if (-not $SkipBuild) {
            & "$repo/services/$service/$wrapper" -B -ntp -f "$repo/services/$service/pom.xml" -DskipTests package
            if ($LASTEXITCODE -ne 0) { throw "$service build failed" }
        }
    }
    & "$repo/services/usage-aggregator/$wrapper" -B -ntp -f "$repo/services/usage-aggregator/pom.xml" `
        dependency:copy-dependencies -DincludeArtifactIds=wiremock-standalone -DincludeScope=test
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare WireMock test dependency' }
    $wiremockJar = (Get-ChildItem "$repo/services/usage-aggregator/target/dependency/wiremock-standalone-*.jar" | Select-Object -First 1).FullName
    Start-Java 'peers' $wiremockJar @{} @('--port', "$StubPort")
    Wait-Http "$stub/__admin/mappings"
    Set-Stub 'quota' 'GET' "/api/v1/tenants/$tenant/quota" @{ tenantId = $tenant; cpu = @{ used = 4; limit = 20; remaining = 16 } }
    Set-Stub 'resources' 'GET' '/api/v1/resources' @{ items = @(@{ resourceId = $resourceId; status = 'ACTIVE' }); count = 1 }
    Set-Stub 'cost' 'GET' "/api/v1/billing/tenants/$tenant/cost" @{ amount = '0.0000'; currency = 'USD' }
    Set-Stub 'expired' 'GET' '/api/v1/resources/expired' @{ items = @(); count = 0 }
    Set-Stub 'terminate' 'POST' "/api/v1/resources/$resourceId/termination" @{ resourceId = $resourceId; status = 'TERMINATED' } 202
    Start-Java 'notification' "$repo/services/notification-service/target/notification.jar" @{
        SERVER_PORT = "$NotificationPort"; DB_HOST = $DbHost; DB_PORT = "$DbPort"; DB_NAME = $DbName
        DB_USER = $DbUser; DB_PASSWORD = $DbPassword; NOTIFICATION_FAILURE_RATE = '0.0'
    }
    Start-Java 'usage' "$repo/services/usage-aggregator/target/usage-aggregator.jar" @{
        SERVER_PORT = "$UsagePort"; QUOTA_SERVICE_URL = $stub; RESOURCE_SERVICE_URL = $stub; BILLING_SERVICE_URL = $stub
    }
    Start-Java 'worker' "$repo/services/expiry-worker/target/expiry-worker.jar" @{
        SERVER_PORT = "$WorkerPort"; RESOURCE_SERVICE_URL = $stub; EXPIRY_CHECK_INTERVAL_MS = '500'
    }
    Wait-Http "$notification/health"
    Wait-Http "$usage/health"
    Wait-Http "http://localhost:$WorkerPort/health"
    Send-Event 'ACTIVE'
    $view = Invoke-RestMethod "$usage/api/v1/usage/$tenant"
    Require ($view.quota.cpu.used -eq 4 -and $view.resources[0].resourceId -eq $resourceId) 'Usage composition did not preserve upstream data'
    Require ($view.cost.amount -eq '0.0000' -and $view.availability.billing) 'Expected available zero cost'
    Write-Host 'PASS: notification recorded; usage contains quota, resources and zero cost'
    Set-Stub 'cost' 'GET' "/api/v1/billing/tenants/$tenant/cost" @{} 503
    $partial = Invoke-RestMethod "$usage/api/v1/usage/$tenant"
    Require (-not $partial.availability.billing -and $partial.availability.quota -and $partial.availability.resources) 'Billing outage lost healthy sections'
    Require ($null -eq $partial.cost) 'Expected cost to be omitted during a billing outage'
    Send-Event 'FAILED'
    Write-Host 'PASS: billing outage omits cost and preserves quota and resources'
    Set-Stub 'quota' 'GET' "/api/v1/tenants/$tenant/quota" @{} 503
    Set-Stub 'resources' 'GET' '/api/v1/resources' @{} 503
    $outage = Invoke-RestMethod "$usage/api/v1/usage/$tenant"
    Require (-not $outage.availability.quota -and -not $outage.availability.resources -and -not $outage.availability.billing) 'Expected all availability flags to be false during an outage'
    Write-Host 'PASS: all availability flags are false during an outage'
    Set-Stub 'expired' 'GET' '/api/v1/resources/expired' @{
        items = @(@{ resourceId = $resourceId; tenantId = $tenant; expiresAt = (Get-Date).ToUniversalTime().AddMinutes(-1).ToString('o') }); count = 1
    }
    $pattern = @{ method = 'POST'; urlPath = "/api/v1/resources/$resourceId/termination" } | ConvertTo-Json
    $deadline = (Get-Date).AddSeconds(10)
    do {
        $calls = Invoke-RestMethod "$stub/__admin/requests/count" -Method Post -ContentType 'application/json' -Body $pattern
        if ($calls.count -ge 1) { break }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    Require ($calls.count -ge 1) 'Worker did not request termination before the timeout'
    Set-Stub 'expired' 'GET' '/api/v1/resources/expired' @{ items = @(); count = 0 }
    Send-Event 'TERMINATED'
    $history = Invoke-RestMethod "$notification/api/v1/notifications?tenantId=$tenant"
    Require ($history.Count -eq 3 -and @($history | Where-Object status -ne 'SENT').Count -eq 0) 'Persisted lifecycle history is incomplete'
    Write-Host 'PASS: scheduled termination requested; notification history contains all three events'
    foreach ($entry in @(@('notification-service', $NotificationPort), @('usage-aggregator', $UsagePort), @('expiry-worker', $WorkerPort))) {
        $spec = Invoke-RestMethod "http://localhost:$($entry[1])/v3/api-docs"
        Require ($null -ne $spec.paths.'/health'.get.responses.'503') 'OpenAPI omitted unhealthy service response'
        if ($entry[0] -ne 'expiry-worker') {
            Require ($spec.components.schemas.ProblemDetail.required -contains 'correlationId') 'OpenAPI omitted required error correlation ID'
        }
        Invoke-WebRequest "http://localhost:$($entry[1])/v3/api-docs.yaml" -OutFile "$repo/services/$($entry[0])/openapi.yaml"
    }
    $journal = Invoke-RestMethod "$stub/__admin/requests"
    $journal | ConvertTo-Json -Depth 30 | Set-Content "$evidence/peer-requests.json"
    Require (@($journal.requests | Where-Object { $_.request.url -eq "/api/v1/resources/$resourceId/termination" -and $_.request.headers.'X-Correlation-Id' }).Count -ge 1) 'Scheduled worker did not forward a correlation ID'
    Write-Host 'PASS: OpenAPI exported; peer requests include correlation IDs'
    Write-Host 'RESULT: end-to-end test passed'
} finally {
    foreach ($process in $processes) { if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force } }
    Stop-Transcript | Out-Null
}
