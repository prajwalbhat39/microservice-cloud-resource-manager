# =============================================================================
# Start every service for a live demo, each in its own titled window so you can
# switch to any service's log on camera.
#
# DEMO-SAFE DEFAULTS (the important part):
#   PROVIDER_FAILURE_RATE = 0     The provider mock defaults to failing 20% of
#                                 calls (FR-41). That is correct behaviour for the
#                                 resilience story, but on camera it means roughly
#                                 one happy-path demo in five fails for no reason.
#                                 Forced to 0 here; failures are injected ON PURPOSE
#                                 per-request with X-Simulate-Failure instead, which
#                                 is deterministic and explainable.
#   PROVIDER_DELAY_*              Kept at ~800-1500ms, not removed. The delay is
#                                 WHY the API returns 202 and the client polls, so
#                                 it is worth showing - just not 3s per call.
#   EXPIRY_ENABLED = false        The expiry worker would otherwise terminate demo
#                                 resources mid-sentence. Turn it on deliberately
#                                 for the expiry segment (see -WithExpiry).
#
# Usage:
#   scripts\demo\start-all.ps1                 # 5 stateful services + aggregator
#   scripts\demo\start-all.ps1 -WithExpiry     # also start the expiry worker
#   scripts\demo\start-all.ps1 -Core           # ONLY quota + resource + peer stub
#                                              # (U1-only fallback, no U2/U3/U4 DBs needed)
#
# Prerequisites:
#   - PostgreSQL on :5432 with the databases from scripts\db\setup-u1.sql AND
#     scripts\db\setup-u2-u4.sql
#   - jars built:  .\services\quota\mvnw.cmd -DskipTests package
# =============================================================================
#Requires -Version 7

param(
    [switch]$WithExpiry,
    [switch]$Core
)

$ErrorActionPreference = 'Stop'
$repo = Resolve-Path (Join-Path $PSScriptRoot '..\..')

# -----------------------------------------------------------------------------
# Resolve java.exe ONCE, by absolute path, and hand that path to every service
# window.
#
# Why: the windows spawned by Start-Process below do not reliably inherit a PATH
# containing the JDK, so a bare `java -jar` died in all six with
# "java: The term 'java' is not recognized". JAVA_HOME is set at machine scope
# and mvnw.cmd resolves the JDK through it, which is why the BUILD worked while
# the RUN did not - a confusing combination to hit two minutes before recording.
# Resolving here also means the failure is reported once, with a clear message,
# instead of six times inside windows that scroll away.
# -----------------------------------------------------------------------------
function Resolve-Java {
    $candidates = @()

    $onPath = Get-Command java -CommandType Application -ErrorAction SilentlyContinue |
                  Select-Object -First 1 -ExpandProperty Source
    if ($onPath) { $candidates += $onPath }

    # NB: do not name this loop variable $home - that is a read-only PowerShell
    # automatic variable and assigning it is a hard error.
    foreach ($javaHome in @($env:JAVA_HOME,
                            [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User'),
                            [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine'))) {
        if ($javaHome) { $candidates += (Join-Path $javaHome.TrimEnd('\') 'bin\java.exe') }
    }

    foreach ($root in @("$env:ProgramFiles\Eclipse Adoptium",
                        "$env:ProgramFiles\Java",
                        "$env:ProgramFiles\Microsoft\jdk",
                        "$env:ProgramFiles\Amazon Corretto",
                        "$env:USERPROFILE\.jdks")) {
        if (Test-Path $root) {
            $candidates += (Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
                            ForEach-Object { Join-Path $_.FullName 'bin\java.exe' })
        }
    }

    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path }
    }
    return $null
}

$javaExe = Resolve-Java
if (-not $javaExe) {
    Write-Host ""
    Write-Host "FATAL: could not find java.exe." -ForegroundColor Red
    Write-Host "  Install a JDK 21, or set JAVA_HOME to point at one, then re-run." -ForegroundColor Red
    Write-Host "  Checked: PATH, JAVA_HOME (process/user/machine), and the usual install roots." -ForegroundColor Red
    Write-Host ""
    exit 1
}
Write-Host ""
Write-Host "  java: $javaExe" -ForegroundColor DarkGray

function Start-Svc {
    param([string]$Title, [string]$Jar, [hashtable]$Env = @{})

    $jarPath = Join-Path $repo $Jar
    if (-not (Test-Path $jarPath)) {
        # Tolerate a service changing its <finalName>: three U4 poms set
        # <finalName>${project.artifactId}</finalName>, which drops the version from the
        # jar name (notification.jar, not notification-1.0.0.jar). Hard-coding the name
        # made this script SKIP those services silently, which reads on screen as
        # "the service is broken" when the jar was built perfectly well.
        $dir = Split-Path $jarPath -Parent
        $cand = @()
        if (Test-Path $dir) {
            $cand = @(Get-ChildItem (Join-Path $dir '*.jar') -ErrorAction SilentlyContinue |
                      Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' })
        }
        if ($cand.Count -eq 1) {
            $jarPath = $cand[0].FullName
        } else {
            Write-Host "  SKIP  $Title - jar not found: $Jar" -ForegroundColor Yellow
            Write-Host "        build first:  .\services\quota\mvnw.cmd -f pom.xml -DskipTests package" -ForegroundColor Yellow
            return
        }
    }

    # Build a command that sets this service's env vars then runs it, in a window
    # titled so you can find it instantly while presenting.
    # java is invoked by ABSOLUTE path ($javaExe, resolved once above) because the
    # spawned window's PATH does not reliably contain the JDK.
    $sets = ($Env.GetEnumerator() | ForEach-Object { "`$env:$($_.Key)='$($_.Value)'" }) -join '; '
    if ($sets) { $sets = "$sets; " }
    #
    # PROOF-OF-CALL LOGGING: the peer services (billing in particular) log nothing when
    # a request arrives, so on camera their windows look idle even while they are being
    # called. Turning DispatcherServlet to DEBUG makes Spring MVC itself print one line
    # per request ('POST "/api/v1/billing/meters"') and one per result ('Completed 201
    # CREATED'). It is a JVM flag, so no teammate's code or config is touched (DR-7).
    # Passed as a -D system property rather than an env var because env-var map keys are
    # lower-cased and logger names are case-sensitive, which would silently not match.
    #
    # Output is also tee'd to logs\<service>.log (git-ignored by *.log) so the same
    # evidence can be attached to the report afterwards.
    $logName = ($Title -split ' ')[0]
    $logFile = Join-Path $repo "logs\$logName.log"
    New-Item -ItemType Directory -Path (Split-Path $logFile -Parent) -Force | Out-Null
    $inner = "`$host.UI.RawUI.WindowTitle='$Title'; $sets" +
             "& '$javaExe' '-Dlogging.level.org.springframework.web.servlet.DispatcherServlet=DEBUG' " +
             "-jar '$jarPath' 2>&1 | Tee-Object -FilePath '$logFile'"

    Start-Process pwsh -ArgumentList '-NoExit', '-Command', $inner -WorkingDirectory $repo | Out-Null
    Write-Host "  started  $Title" -ForegroundColor Green
}

function Wait-Health {
    param([string]$Name, [int]$Port, [int]$TimeoutSec = 90)

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $code = (& curl.exe -s -o NUL -w "%{http_code}" "http://localhost:$Port/health" 2>$null)
            if ($code -eq '200') {
                Write-Host ("  UP    {0,-22} :{1}" -f $Name, $Port) -ForegroundColor Green
                return $true
            }
        } catch { }
        Start-Sleep -Milliseconds 800
    }
    Write-Host ("  DOWN  {0,-22} :{1}  (not healthy after {2}s)" -f $Name, $Port, $TimeoutSec) -ForegroundColor Red
    return $false
}

Write-Host ""
Write-Host "=============================================================================" -ForegroundColor Cyan
Write-Host " Cloud Resource Manager - starting services for a live demo" -ForegroundColor Cyan
Write-Host "=============================================================================" -ForegroundColor Cyan
Write-Host ""

if ($Core) {
    # ---- U1-only fallback: the two core services plus the local peer stand-in ----
    Write-Host "CORE MODE: quota + resource + peer stub (no U2/U3/U4 databases needed)" -ForegroundColor Yellow
    Write-Host ""

    $stubPath = Join-Path $repo 'scripts\peer-stub.py'

    # Same absolute-path treatment as java: -Core is the ON-CAMERA FALLBACK, so it
    # must not be the thing that discovers python is missing from a child shell's PATH.
    $pythonExe = @('python', 'python3', 'py') |
        ForEach-Object { Get-Command $_ -CommandType Application -ErrorAction SilentlyContinue |
                         Select-Object -First 1 -ExpandProperty Source } |
        Where-Object { $_ } | Select-Object -First 1

    if (-not $pythonExe) {
        Write-Host "  SKIP  peer-stub :8090 - python not found on PATH" -ForegroundColor Yellow
        Write-Host "        the saga's peers will be unreachable; install Python or run without -Core" -ForegroundColor Yellow
    } else {
        Start-Process pwsh -ArgumentList '-NoExit', '-Command',
            "`$host.UI.RawUI.WindowTitle='peer-stub :8090'; & '$pythonExe' '$stubPath'" `
            -WorkingDirectory $repo | Out-Null
        Write-Host "  started  peer-stub :8090" -ForegroundColor Green
    }

    $stub = 'http://localhost:8090'
    Start-Svc 'quota :8081'    'services\quota\target\quota-service-1.0.0-SNAPSHOT.jar'
    Start-Svc 'resource :8082' 'services\resource\target\resource-service-1.0.0-SNAPSHOT.jar' @{
        PROVIDER_SERVICE_URL     = $stub
        BILLING_SERVICE_URL      = $stub
        NOTIFICATION_SERVICE_URL = $stub
    }

    Write-Host ""
    Write-Host "waiting for health..." -ForegroundColor Cyan
    Wait-Health 'quota'    8081 | Out-Null
    Wait-Health 'resource' 8082 | Out-Null
}
else {
    # ---- Full system: all real services ----
    Start-Svc 'quota :8081'        'services\quota\target\quota-service-1.0.0-SNAPSHOT.jar'
    Start-Svc 'billing :8083'      'services\billing-service\target\billing-service-0.0.1-SNAPSHOT.jar'
    Start-Svc 'notification :8084' 'services\notification-service\target\notification.jar'
    Start-Svc 'provider :8085'     'services\provider\target\provider-service-1.0.0-SNAPSHOT.jar' @{
        PROVIDER_FAILURE_RATE = '0'       # deterministic on camera; see header
        PROVIDER_DELAY_MIN_MS = '800'
        PROVIDER_DELAY_MAX_MS = '1500'
    }

    # Resource last of the stateful set: it is the one that calls the others.
    Start-Svc 'resource :8082'     'services\resource\target\resource-service-1.0.0-SNAPSHOT.jar'
    Start-Svc 'usage-aggregator :8086' 'services\usage-aggregator\target\usage-aggregator.jar'

    if ($WithExpiry) {
        Start-Svc 'expiry-worker :8087' 'services\expiry-worker\target\expiry-worker.jar' @{
            EXPIRY_ENABLED           = 'true'
            EXPIRY_CHECK_INTERVAL_MS = '15000'
        }
    } else {
        Write-Host "  (expiry-worker not started - pass -WithExpiry for the expiry segment)" -ForegroundColor DarkGray
    }

    Write-Host ""
    Write-Host "waiting for health (Spring Boot takes ~30s each, started in parallel)..." -ForegroundColor Cyan
    $ok = @()
    $ok += Wait-Health 'quota'        8081
    $ok += Wait-Health 'resource'     8082
    $ok += Wait-Health 'billing'      8083
    $ok += Wait-Health 'notification' 8084
    $ok += Wait-Health 'provider'     8085
    $ok += Wait-Health 'usage-aggregator' 8086
    if ($WithExpiry) { $ok += Wait-Health 'expiry-worker' 8087 }

    Write-Host ""
    if ($ok -contains $false) {
        Write-Host "SOME SERVICES ARE NOT UP. Common causes:" -ForegroundColor Red
        Write-Host "  - missing database: run scripts\db\setup-u2-u4.sql as the postgres superuser" -ForegroundColor Red
        Write-Host "  - jar not built: .\services\quota\mvnw.cmd -DskipTests package" -ForegroundColor Red
        Write-Host "  - check the failing service's own window for its startup error" -ForegroundColor Red
    } else {
        Write-Host "All services healthy." -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "Next:" -ForegroundColor Cyan
Write-Host "  main flow demo      scripts\demo\demo-main-flow.ps1"
Write-Host "  challenge 1         evidence\challenge-1-quota-concurrency\run.ps1"
Write-Host "  challenge 2         evidence\challenge-2-idempotency\run.ps1"
Write-Host "  challenge 3C        evidence\challenge-3c-saga-compensation\run.ps1"
Write-Host "  stop everything     scripts\demo\stop-all.ps1"
Write-Host ""
