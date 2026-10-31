$scriptPath = Join-Path $PSScriptRoot 'verify-compose.ps1'

function global:docker {
    $arguments = @($args)
    $global:LASTEXITCODE = 0
    $state = $global:ComposeTestState
    $state.Commands.Add(($arguments -join ' ')) | Out-Null
    if ($arguments -contains 'version') { return 'Docker Compose test double' }
    if ($arguments -contains 'config') {
        if ($state.ConfigFails) { $global:LASTEXITCODE = 1 }
        return
    }
    if ($arguments -contains 'down') { $state.Restarts++; return }
    if ($arguments -contains 'up') { return }
    if ($arguments -contains 'logs') { return 'Test container log' }
    if ($arguments -contains 'ps') {
        if ($arguments -notcontains 'json') { return 'Eight test containers' }
        $containers = @('postgres', 'quota', 'resource', 'billing', 'notification', 'provider', 'usage-aggregator', 'expiry-worker') |
            ForEach-Object {
                $health = 'healthy'
                if ($state.Unhealthy -and $_ -eq 'billing') { $health = 'unhealthy' }
                @{ Service = $_; State = 'running'; Health = $health }
            }
        if ($state.JsonArray) { return ConvertTo-Json -InputObject @($containers) -Compress }
        return $containers | ForEach-Object { $_ | ConvertTo-Json -Compress }
    }
    if ($arguments -contains 'exec') {
        if ($arguments -contains 'provider_db' -and $arguments -contains 'psql') {
            return @($state.Resources.Values | ForEach-Object { $_.providerInstanceId })
        }
        if ($state.LoginFails) { $global:LASTEXITCODE = 1; return }
        return '1'
    }
    throw "Unexpected Docker invocation: $($arguments -join ' ')"
}

Describe 'Compose deployment verification' {
    BeforeEach {
        $global:ComposeTestState = @{
            Commands = New-Object 'System.Collections.Generic.List[string]'
            Restarts = 0; Resources = @{}; Tenants = @{}
            JsonArray = $false; Unhealthy = $false; ConfigFails = $false
            LoginFails = $false; DegradedUsage = $false; FailedSaga = $false
            LoseMeter = $false; LoseProvider = $false
        }
        Mock Start-Transcript {}
        Mock Stop-Transcript {}
        Mock Start-Sleep {}
        Mock Invoke-WebRequest {
            param($Method, $Uri, $Body, $Headers)
            $state = $global:ComposeTestState
            $url = "$Uri"
            $status = 200
            $data = $null
            if ($Method -eq 'Post' -and $url -eq 'http://localhost:8081/api/v1/tenants') {
                $data = $Body | ConvertFrom-Json
                $state.Tenants[$data.tenantId] = $data
                $status = 201
            } elseif ($Method -eq 'Post' -and $url -eq 'http://localhost:8082/api/v1/resources') {
                $request = $Body | ConvertFrom-Json
                $id = [guid]::NewGuid().ToString()
                $state.Resources[$id] = @{
                    resourceId = $id; tenantId = $request.tenantId; status = 'ACTIVE'
                    providerInstanceId = "inst-$id"
                }
                $data = @{ resourceId = $id; reservationId = $id }
                $status = 202
            } elseif ($url -match '/api/v1/reservations/([^/]+)$') {
                $data = @{ status = 'COMPLETED'; reservationId = $Matches[1] }
                if ($state.FailedSaga) { $data.status = 'FAILED'; $data.failureReason = 'Test failure' }
            } elseif ($url -match '/api/v1/resources/([^/]+)$') {
                $data = $state.Resources[$Matches[1]].Clone()
                if ($state.LoseProvider -and $state.Restarts -gt 0) { $data.providerInstanceId = 'lost-instance' }
            } elseif ($url -match '/api/v1/tenants/([^/]+)/quota$') {
                $data = @{ cpu = @{ used = 4 }; ramGb = @{ used = 8 }; instances = @{ used = 1 } }
            } elseif ($url -match '/api/v1/billing/meters/([^/]+)$') {
                $id = $Matches[1]
                $data = @{ meterId = "meter-$id"; state = 'ACTIVE'; tenantId = $state.Resources[$id].tenantId }
                if ($state.LoseMeter -and $state.Restarts -gt 0) { $data.meterId = 'lost-meter' }
            } elseif ($url -match '/api/v1/notifications\?tenantId=(.+)$') {
                $tenant = $Matches[1]
                $resource = @($state.Resources.Values | Where-Object { $_.tenantId -eq $tenant })[0]
                $data = @(@{ notificationId = "notification-$($resource.resourceId)"
                    resourceId = $resource.resourceId; eventType = 'ACTIVE' })
            } elseif ($url -match '/api/v1/usage/([^/]+)$') {
                $tenant = $Matches[1]
                $data = @{
                    availability = @{ quota = $true; resources = $true; billing = -not $state.DegradedUsage }
                    resources = @($state.Resources.Values | Where-Object { $_.tenantId -eq $tenant })
                }
            } else {
                throw "Unexpected HTTP request: $Method $url"
            }
            @{ StatusCode = $status; Content = ConvertTo-Json -InputObject $data -Depth 10 }
        }
    }

    It 'checks all eight services, five database logins, persistence and a new saga with NDJSON ps output' {
        $directory = Join-Path $TestDrive 'full'
        & $scriptPath -SkipBuild -EvidenceDirectory $directory
        $global:ComposeTestState.Restarts | Should Be 1
        $global:ComposeTestState.Resources.Count | Should Be 2
        @(Get-ChildItem $directory -Filter '*workflow.json').Count | Should Be 3
        @($global:ComposeTestState.Commands | Where-Object { $_ -match 'SELECT 1' }).Count | Should Be 10
        ($global:ComposeTestState.Commands -join "`n") | Should Not Match 'down.*(-v|--volumes)'
    }

    It 'supports JSON array ps output and the no-restart option' {
        $global:ComposeTestState.JsonArray = $true
        & $scriptPath -SkipBuild -SkipRestart -EvidenceDirectory (Join-Path $TestDrive 'array')
        $global:ComposeTestState.Restarts | Should Be 0
        $global:ComposeTestState.Resources.Count | Should Be 1
    }

    It 'builds images by default' {
        & $scriptPath -SkipRestart -EvidenceDirectory (Join-Path $TestDrive 'build')
        ($global:ComposeTestState.Commands -join "`n") | Should Match 'up .*--build'
    }

    It 'rejects invalid Compose configuration before startup' {
        $global:ComposeTestState.ConfigFails = $true
        { & $scriptPath -EvidenceDirectory (Join-Path $TestDrive 'config') } | Should Throw
        @($global:ComposeTestState.Commands | Where-Object { $_ -match ' up ' }).Count | Should Be 0
    }

    It 'rejects an unhealthy container and saves failure logs' {
        $global:ComposeTestState.Unhealthy = $true
        $directory = Join-Path $TestDrive 'unhealthy'
        { & $scriptPath -SkipBuild -EvidenceDirectory $directory } | Should Throw
        Test-Path (Join-Path $directory 'failure-containers.log') | Should Be $true
    }

    It 'rejects failed service database authentication' {
        $global:ComposeTestState.LoginFails = $true
        { & $scriptPath -SkipBuild -EvidenceDirectory (Join-Path $TestDrive 'login') } | Should Throw
    }

    It 'rejects a failed saga instead of reporting success' {
        $global:ComposeTestState.FailedSaga = $true
        { & $scriptPath -SkipBuild -EvidenceDirectory (Join-Path $TestDrive 'saga') } | Should Throw
    }

    It 'rejects a success-shaped degraded usage response' {
        $global:ComposeTestState.DegradedUsage = $true
        { & $scriptPath -SkipBuild -EvidenceDirectory (Join-Path $TestDrive 'usage') } | Should Throw
    }

    It 'detects a billing identity that did not persist through restart' {
        $global:ComposeTestState.LoseMeter = $true
        { & $scriptPath -SkipBuild -EvidenceDirectory (Join-Path $TestDrive 'meter') } | Should Throw
    }

    It 'detects a provider instance that did not persist through restart' {
        $global:ComposeTestState.LoseProvider = $true
        { & $scriptPath -SkipBuild -EvidenceDirectory (Join-Path $TestDrive 'provider') } | Should Throw
    }
}

Remove-Item Function:\docker
Remove-Variable ComposeTestState -Scope Global
