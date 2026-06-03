<#
.SYNOPSIS
    One-command local boot: Postgres + backend + frontend.

.DESCRIPTION
    Brings up the compose `db` service, launches the backend in a new window
    (run-backend.ps1, which builds the jar if missing and waits for the DB),
    then runs the frontend dev server in THIS window (npm start stays in the
    foreground so Ctrl+C stops it).

    URLs:
      UI  -> http://localhost:4200
      API -> http://localhost:8080

    Endpoints (for reference): POST /api/games, /api/games/{id}/start,
    /state, /events, /leaderboard.

    Written for Windows PowerShell 5.1: no '&&', no ternary operator.

.PARAMETER NoFrontend
    Start only DB + backend (useful when developing the frontend separately).
#>
[CmdletBinding()]
param(
    [switch]$NoFrontend
)

$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
$repoRoot  = Split-Path -Parent $scriptDir

Write-Host "==> Stellar Compact: local stack" -ForegroundColor Magenta

# 1. Backend (this brings up the DB itself and waits for health, then builds + runs).
#    Launch it in a separate PowerShell window so its logs stay readable and this
#    script can move on to the frontend.
$backendScript = Join-Path $scriptDir 'run-backend.ps1'
Write-Host "==> Launching backend in a new window (DB + build + run) ..." -ForegroundColor Cyan
Start-Process -FilePath 'powershell.exe' `
    -ArgumentList '-NoExit', '-ExecutionPolicy', 'Bypass', '-File', $backendScript `
    -WorkingDirectory $repoRoot

Write-Host ""
Write-Host "    UI  -> http://localhost:4200" -ForegroundColor Green
Write-Host "    API -> http://localhost:8080" -ForegroundColor Green
Write-Host ""

if ($NoFrontend) {
    Write-Host "==> -NoFrontend set; backend launching in its own window. Done." -ForegroundColor Magenta
    return
}

# 2. Frontend in the foreground (npm start). The backend may still be building;
#    the dev server starts immediately and the proxy will connect once :8080 is up.
$frontendScript = Join-Path $scriptDir 'run-frontend.ps1'
Write-Host "==> Starting frontend in this window (Ctrl+C to stop) ..." -ForegroundColor Cyan
& $frontendScript
