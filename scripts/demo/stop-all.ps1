# =============================================================================
# Stop everything started by start-all.ps1.
#
# Kills the Java processes running the service jars and the python peer stub.
# Deliberately narrow: it only targets processes whose command line points at
# this repository, so a Java process you are running for something else is left
# alone.
# =============================================================================
#Requires -Version 7

$ErrorActionPreference = 'Continue'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

Write-Host ""
Write-Host "stopping services started from $repo" -ForegroundColor Cyan
Write-Host ""

$stopped = 0

# Java services: match on the jar path belonging to this repo.
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -like "*$repo*" } |
    ForEach-Object {
        $jar = if ($_.CommandLine -match '([^\\/]+\.jar)') { $Matches[1] } else { 'java' }
        Write-Host ("  killing pid {0,-7} {1}" -f $_.ProcessId, $jar)
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        $stopped++
    }

# The python peer stub.
Get-CimInstance Win32_Process -Filter "Name = 'python.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -like '*peer-stub.py*' } |
    ForEach-Object {
        Write-Host ("  killing pid {0,-7} peer-stub.py" -f $_.ProcessId)
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        $stopped++
    }

Write-Host ""
if ($stopped -eq 0) {
    Write-Host "nothing was running." -ForegroundColor Yellow
} else {
    Write-Host "stopped $stopped process(es)." -ForegroundColor Green
}

Write-Host ""
Write-Host "ports still listening (should be empty):" -ForegroundColor Cyan
foreach ($p in 8081,8082,8083,8084,8085,8086,8087,8090) {
    $busy = (Test-NetConnection -ComputerName localhost -Port $p -WarningAction SilentlyContinue).TcpTestSucceeded
    if ($busy) { Write-Host "  :$p still in use" -ForegroundColor Yellow }
}
Write-Host ""
