<#
.SYNOPSIS
    Install (if needed) and run the Stellar Compact Angular dev server on :4200.

.DESCRIPTION
    Runs 'npm install' only when node_modules is absent, then 'npm start'
    (the Angular dev server). The dev server proxies /api and /ws to the
    backend on :8080 via frontend/proxy.conf.json (already wired into
    angular.json) - no extra flags needed here.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repoRoot    = Split-Path -Parent $PSScriptRoot
$frontendDir = Join-Path $repoRoot 'frontend'

if (-not (Test-Path (Join-Path $frontendDir 'package.json'))) {
    Write-Error "No package.json under $frontendDir - is the frontend checked out?"
}

Push-Location $frontendDir
try {
    if (-not (Test-Path (Join-Path $frontendDir 'node_modules'))) {
        Write-Host "[run-frontend] Installing npm dependencies (first run) ..." -ForegroundColor Cyan
        npm install
        if ($LASTEXITCODE -ne 0) { Write-Error "npm install failed." }
    }
    Write-Host "[run-frontend] Starting Angular dev server -> http://localhost:4200" -ForegroundColor Green
    npm start
}
finally {
    Pop-Location
}
