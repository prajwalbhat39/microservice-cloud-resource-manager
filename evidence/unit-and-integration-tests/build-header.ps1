# Prepends a provenance header (ER-2: command, result, timestamp) to each raw
# Maven log and copies it into this folder as the committed evidence file.
# Run from this folder after a `mvnw verify` in each service.
param(
    [Parameter(Mandatory)] [string]$RawLog,
    [Parameter(Mandatory)] [string]$Service,
    [Parameter(Mandatory)] [string]$Command,
    [Parameter(Mandatory)] [string]$OutFile
)
$ErrorActionPreference = 'Stop'

$raw     = Get-Content $RawLog -Raw
$summary = ($raw -split "`n" | Select-String -Pattern 'Tests run:.*-- in |^\[INFO\] Tests run:|BUILD' | ForEach-Object { $_.Line.Trim() })

$header = @()
$header += '============================================================================='
$header += "$Service - AUTOMATED TEST SUITE"
$header += '============================================================================='
$header += "Captured (UTC) : $((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')) UTC"
$header += "Command        : $Command"
$header += "Machine / OS   : $env:COMPUTERNAME / $([System.Environment]::OSVersion.VersionString)"
$header += "Docker         : $(docker version --format '{{.Server.Version}}')   (integration tests use Testcontainers)"
$header += "Java           : $((& java -version 2>&1 | Select-Object -First 1))"
$header += ''
$header += 'Unit tests (*Test) run under the surefire plugin; Testcontainers-backed'
$header += 'integration tests (*IT) run under the failsafe plugin. Both phases are'
$header += 'included in `mvnw verify`, so one command runs the whole suite.'
$header += ''
$header += 'SUMMARY (extracted from the full log below):'
$summary | ForEach-Object { $header += "  $_" }
$header += ''
$header += '============================================================================='
$header += 'FULL MAVEN OUTPUT, VERBATIM'
$header += '============================================================================='
$header += ''

($header -join "`r`n") | Set-Content $OutFile -Encoding utf8
$raw | Add-Content $OutFile -Encoding utf8
Write-Host "wrote $OutFile"
