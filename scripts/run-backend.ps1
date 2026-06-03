<#
.SYNOPSIS
    Build (if needed) and run the Stellar Compact backend against the local Postgres.

.DESCRIPTION
    Encodes the gotchas required to boot the backend on a fresh machine:
      1. The backend is Java 25 with PREVIEW features -> it MUST run with --enable-preview.
         JDK 25 is required; the path is read from $env:JAVA_HOME (never hardcoded).
      2. It needs PostgreSQL. This script brings up the compose `db` service (port 5433)
         if it is not already running, and waits for the healthcheck.
      3. The runnable jar is backend/app/target/app-0.1.0-SNAPSHOT.jar, built via
         `mvn -DskipTests install` from backend/. If the jar is missing we build it.

    DB connection is overridable via env vars (defaults match docker-compose.yml):
      STELLAR_DB_HOST (localhost), STELLAR_DB_PORT (5433),
      STELLAR_DB_NAME (stellar), STELLAR_DB_USER (stellar), STELLAR_DB_PASSWORD (stellar)

.PARAMETER SkipDb
    Do not touch docker compose; assume Postgres is already reachable.

.PARAMETER Rebuild
    Force `mvn -DskipTests install` even if the jar already exists.
#>
[CmdletBinding()]
param(
    [switch]$SkipDb,
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'

# --- Paths -------------------------------------------------------------------
$repoRoot   = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $repoRoot 'backend'
$jarPath    = Join-Path $backendDir 'app\target\app-0.1.0-SNAPSHOT.jar'

# --- DB connection (env-overridable, defaults from docker-compose.yml) --------
$dbHost = if ($env:STELLAR_DB_HOST)     { $env:STELLAR_DB_HOST }     else { 'localhost' }
$dbPort = if ($env:STELLAR_DB_PORT)     { $env:STELLAR_DB_PORT }     else { '5433' }
$dbName = if ($env:STELLAR_DB_NAME)     { $env:STELLAR_DB_NAME }     else { 'stellar' }
$dbUser = if ($env:STELLAR_DB_USER)     { $env:STELLAR_DB_USER }     else { 'stellar' }
$dbPass = if ($env:STELLAR_DB_PASSWORD) { $env:STELLAR_DB_PASSWORD } else { 'stellar' }
$jdbcUrl = "jdbc:postgresql://${dbHost}:${dbPort}/${dbName}"

# --- 1. Verify JAVA_HOME points at a JDK 25 ----------------------------------
if (-not $env:JAVA_HOME) {
    Write-Error "JAVA_HOME is not set. Stellar Compact requires JDK 25 (preview features). Set JAVA_HOME to a JDK 25 install, e.g. `$env:JAVA_HOME = 'C:\path\to\jdk-25'`."
}
$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
    Write-Error "No java.exe under JAVA_HOME ('$env:JAVA_HOME'). Point JAVA_HOME at a JDK 25 install."
}
$verRaw = & $javaExe -version 2>&1 | Out-String
if ($verRaw -notmatch 'version "?25') {
    Write-Error "JAVA_HOME ('$env:JAVA_HOME') is not a JDK 25. `java -version` reported:`n$verRaw`nStellar Compact uses Java 25 preview features and will not run on an older JDK."
}
Write-Host "[run-backend] Using JDK 25 at $env:JAVA_HOME" -ForegroundColor Green

# --- 2. Bring up Postgres (compose) ------------------------------------------
if (-not $SkipDb) {
    Write-Host "[run-backend] Ensuring Postgres '$dbName' is up on ${dbHost}:${dbPort} ..." -ForegroundColor Cyan
    Push-Location $repoRoot
    try {
        docker compose up -d db
        if ($LASTEXITCODE -ne 0) { Write-Error "docker compose up -d db failed (is Docker running?)." }

        # Wait for the healthcheck to report healthy (pg_isready inside the container).
        $deadline = (Get-Date).AddSeconds(90)
        do {
            Start-Sleep -Seconds 2
            $status = (docker inspect -f '{{.State.Health.Status}}' stellar-db 2>$null)
            Write-Host "[run-backend]   db health: $status"
        } while ($status -ne 'healthy' -and (Get-Date) -lt $deadline)

        if ($status -ne 'healthy') {
            Write-Error "Postgres did not become healthy within 90s. Check 'docker compose logs db'."
        }
    }
    finally {
        Pop-Location
    }
    Write-Host "[run-backend] Postgres is healthy." -ForegroundColor Green
}

# --- 3. Build the jar if missing (or if -Rebuild) ----------------------------
if ($Rebuild -or -not (Test-Path $jarPath)) {
    Write-Host "[run-backend] Building backend jar (mvn -DskipTests install) ..." -ForegroundColor Cyan
    Push-Location $backendDir
    try {
        mvn -DskipTests install
        if ($LASTEXITCODE -ne 0) { Write-Error "Maven build failed. Ensure 'mvn' is on PATH (Maven 3.6.3+) and JAVA_HOME is JDK 25." }
    }
    finally {
        Pop-Location
    }
}
if (-not (Test-Path $jarPath)) {
    Write-Error "Expected jar not found at $jarPath after build."
}

# --- 4. Run the app with --enable-preview and datasource args ----------------
Write-Host "[run-backend] Starting backend -> http://localhost:8080  (db $jdbcUrl)" -ForegroundColor Green
& $javaExe `
    --enable-preview `
    -jar $jarPath `
    "--spring.datasource.url=$jdbcUrl" `
    "--spring.datasource.username=$dbUser" `
    "--spring.datasource.password=$dbPass" `
    "--spring.flyway.url=$jdbcUrl" `
    "--spring.flyway.user=$dbUser" `
    "--spring.flyway.password=$dbPass"
