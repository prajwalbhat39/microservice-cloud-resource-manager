[CmdletBinding()]
param([switch]$Postgres)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$wrapper = if ($IsWindows) { 'mvnw.cmd' } else { 'mvnw' }
$evidence = Join-Path $repo 'evidence/u4'
New-Item -ItemType Directory -Force $evidence | Out-Null
Start-Transcript -Path "$evidence/tests.txt" -Force | Out-Null
try {
    Write-Host "Tests $(Get-Date -AsUTC -Format o)"
    foreach ($service in 'notification', 'usage-aggregator', 'expiry-worker') {
        $project = Join-Path $repo "services/$service"
        $arguments = @('-B', '-ntp', '-f', "$project/pom.xml", 'clean', 'verify')
        if ($service -eq 'notification' -and $Postgres) { $arguments += '-Ppostgres' }
        Write-Host "COMMAND: $project/$wrapper $arguments"
        & "$project/$wrapper" @arguments
        if ($LASTEXITCODE -ne 0) { throw "$service verification failed" }
    }
} finally { Stop-Transcript | Out-Null }
