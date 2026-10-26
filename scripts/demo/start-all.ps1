#requires -Version 7.4
[CmdletBinding()]
param([switch]$WithExpiry, [switch]$Core)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$logDir = Join-Path $repo 'logs'
$registry = Join-Path $logDir 'processes.json'
if (Test-Path $registry) { throw 'A run is already registered. Run scripts/demo/stop-all.ps1 first.' }
New-Item -ItemType Directory -Force $logDir | Out-Null
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/java.exe' } else { 'bin/java' }) } else { (Get-Command java -ErrorAction Stop).Source }
$definitions = if ($Core) {
    @(@{name='quota';port=8081}, @{name='resource';port=8082}, @{name='peer-stub';port=8090})
} else {
    @(@{name='quota';port=8081}, @{name='resource';port=8082}, @{name='billing-service';port=8083},
      @{name='notification-service';port=8084}, @{name='provider';port=8085}, @{name='usage-aggregator';port=8086})
}
if ($WithExpiry -and -not $Core) { $definitions += @{name='expiry-worker';port=8087} }
$started = @()
try {
    foreach ($entry in $definitions) {
        $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $entry.port)
        try { $probe.Start() } finally { $probe.Stop() }
        if ($entry.name -eq 'peer-stub') {
            $entry.executable = (Get-Command python3,python -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
            if (-not $entry.executable) { throw 'Python is required for -Core.' }
            $entry.arguments = @(('"' + (Join-Path $repo 'scripts/peer-stub.py') + '"'))
        } else {
            $jars = @(Get-ChildItem "$repo/services/$($entry.name)/target/*.jar")
            if ($jars.Count -ne 1) { throw "Build $($entry.name) first; expected one application jar." }
            $entry.executable = $java
            $entry.arguments = @('-Xmx256m', '-jar', ('"' + $jars[0].FullName + '"'))
        }
    }
    foreach ($entry in $definitions) {
        $settings = @{SERVER_PORT="$($entry.port)"; PROVIDER_FAILURE_RATE='0'; PROVIDER_DELAY_MIN_MS='800'; PROVIDER_DELAY_MAX_MS='1500'}
        if ($Core -and $entry.name -eq 'resource') {
            $settings.PROVIDER_SERVICE_URL='http://localhost:8090'
            $settings.BILLING_SERVICE_URL='http://localhost:8090'
            $settings.NOTIFICATION_SERVICE_URL='http://localhost:8090'
        }
        $process = Start-Process $entry.executable -ArgumentList $entry.arguments -WorkingDirectory $repo -Environment $settings `
            -PassThru -RedirectStandardOutput "$logDir/$($entry.name).log" -RedirectStandardError "$logDir/$($entry.name)-stderr.log"
        $started += @{name=$entry.name; pid=$process.Id; startedAt=$process.StartTime.ToUniversalTime().ToString('o')}
        ConvertTo-Json -InputObject @($started) | Set-Content $registry
    }
    foreach ($entry in $definitions | Where-Object name -ne 'peer-stub') {
        $deadline = [DateTime]::UtcNow.AddSeconds(90)
        $healthy = $false
        do {
            try {
                $answer = Invoke-WebRequest "http://localhost:$($entry.port)/health" -TimeoutSec 2 -SkipHttpErrorCheck
                $healthy = $answer.StatusCode -eq 200
            } catch { }
            if ($healthy) { break }
            if (-not (Get-Process -Id ($started | Where-Object name -eq $entry.name).pid -ErrorAction SilentlyContinue)) { throw "$($entry.name) exited; inspect logs." }
            Start-Sleep -Milliseconds 300
        } while ([DateTime]::UtcNow -lt $deadline)
        if (-not $healthy) { throw "$($entry.name) did not become healthy; inspect logs." }
        Write-Host "$($entry.name): http://localhost:$($entry.port)"
    }
    Write-Host 'All services healthy. Logs: logs/. Stop: pwsh scripts/demo/stop-all.ps1'
} catch {
    & "$PSScriptRoot/stop-all.ps1"
    throw
}
