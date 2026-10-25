#requires -Version 7.4
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$registry = Join-Path $repo 'logs/processes.json'
if (-not (Test-Path $registry)) { Write-Host 'No registered services.'; return }
foreach ($entry in @(Get-Content $registry -Raw | ConvertFrom-Json)) {
    $process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime() -eq ([datetime]$entry.startedAt).ToUniversalTime()) {
        Stop-Process -Id $process.Id -Force
        Write-Host "Stopped $($entry.name)."
    }
}
Remove-Item $registry
