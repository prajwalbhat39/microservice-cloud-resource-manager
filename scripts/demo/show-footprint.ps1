#requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9-]{1,63}$')][string]$TenantId,
    [switch]$ProveIsolation,
    [string]$DbHost = 'localhost',
    [int]$DbPort = 5432
)
$ErrorActionPreference = 'Stop'
$previousPassword = $env:PGPASSWORD
function Query([string]$Service, [string]$Sql, [switch]$Scalar) {
    [string[]]$flags = if ($Scalar) { @('-At') } else { @() }
    $output = & psql -h $DbHost -p $DbPort -U "${Service}_user" -d "${Service}_db" -v ON_ERROR_STOP=1 @flags -c $Sql
    if ($LASTEXITCODE -ne 0) { throw "Query failed: ${Service}_db" }
    return $output
}
try {
    $env:PGPASSWORD = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'changeme' }
    $ids = @(Query resource "SELECT resource_id FROM resources WHERE tenant_id = '$TenantId'" -Scalar)
    if (-not $ids) { throw "No resources for $TenantId" }
    $resources = ($ids | ForEach-Object { "'$_'" }) -join ','
    $tokens = ($ids | ForEach-Object { "'ct-$_'" }) -join ','
    Write-Host 'Quota database'
    Query quota "SELECT * FROM tenant_quota WHERE tenant_id = '$TenantId'"
    Query quota "SELECT resource_id, cpu, ram_gb, state FROM quota_reservations WHERE tenant_id = '$TenantId'"
    Write-Host 'Resource database'
    Query resource "SELECT resource_id, status, provider_instance_id, failure_reason FROM resources WHERE tenant_id = '$TenantId'"
    Query resource "SELECT reservation_id, resource_id, status FROM reservations WHERE tenant_id = '$TenantId'"
    Write-Host 'Provider database'
    Query provider "SELECT instance_id, client_token, status FROM instances WHERE client_token IN ($tokens)"
    Write-Host 'Billing database'
    Query billing "SELECT resource_id, state, rate_per_cpu_hour, started_at, stopped_at FROM billing_meters WHERE resource_id IN ($resources)"
    Write-Host 'Notification database'
    Query notification "SELECT resource_id, event_type, status, attempts FROM notifications WHERE tenant_id = '$TenantId'"
    if ($ProveIsolation) {
        $answer = & psql -h $DbHost -p $DbPort -U resource_user -d quota_db -c 'SELECT count(*) FROM tenant_quota' 2>&1
        if ($LASTEXITCODE -eq 0) { throw 'Isolation failed: Resource role can read Quota database' }
        Write-Host 'Isolation passed: Resource role was denied access to Quota database.'
    }
} finally { $env:PGPASSWORD = $previousPassword }
