# run-headless-api.ps1
# Runs the headless API server for web UI development
# Usage: .\scripts\dev\run-headless-api.ps1 [-Port 33221]
#
# This script configures paths for the dev environment:
#   - JUSTSEARCH_SERVER_EXE: Path to llama-server.exe
#   - JUSTSEARCH_MODELS_DIR: Path to models directory
#
# For production, these paths are resolved differently (bundled with app).

param(
    [int]$Port = 33221,
    [switch]$FixLocks,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# ============================================================================
# Configure AI/Inference paths for development
# ============================================================================
# The headless app runs from modules/ui, but in dev the models and native
# binaries are at the project root. Set environment variables so the app
# can find them.

$llamaServerPath = Join-Path $scriptRoot "native-bin\llama-server\llama-server.exe"
$modelsDir = Join-Path $scriptRoot "models"

# Only set if the paths exist (allows running without AI features)
if (Test-Path $llamaServerPath) {
    $env:JUSTSEARCH_SERVER_EXE = $llamaServerPath
}
if (Test-Path $modelsDir) {
    $env:JUSTSEARCH_MODELS_DIR = $modelsDir
}

Write-Host "=======================================" -ForegroundColor Cyan
Write-Host " JustSearch Headless API Server" -ForegroundColor Cyan  
Write-Host "=======================================" -ForegroundColor Cyan
Write-Host "Working directory: $scriptRoot"
Write-Host "API Port: $Port"
Write-Host ""

# Show AI configuration status
if ($env:JUSTSEARCH_SERVER_EXE) {
    Write-Host "AI Server: $env:JUSTSEARCH_SERVER_EXE" -ForegroundColor Green
} else {
    Write-Host "AI Server: NOT FOUND (AI features disabled)" -ForegroundColor Yellow
}
if ($env:JUSTSEARCH_MODELS_DIR) {
    Write-Host "Models Dir: $env:JUSTSEARCH_MODELS_DIR" -ForegroundColor Green
} else {
    Write-Host "Models Dir: NOT FOUND (AI features disabled)" -ForegroundColor Yellow
}
Write-Host ""

Write-Host "After startup, access:" -ForegroundColor Gray
Write-Host "  API Status:  http://localhost:$Port/api/status" -ForegroundColor Gray
Write-Host "  Debug State: http://localhost:$Port/api/debug/state" -ForegroundColor Gray
Write-Host "  Dashboard:   http://localhost:$Port/api/debug/dashboard" -ForegroundColor Gray
Write-Host ""
Write-Host "To test with UI, in another terminal run:" -ForegroundColor Gray
Write-Host "  cd modules/ui-web && npm run dev" -ForegroundColor Gray
Write-Host "  Then open: http://localhost:5173/?api_port=$Port" -ForegroundColor Gray
Write-Host ""

Push-Location $scriptRoot
try {
    # Step 1: Fix dependency locks if requested
    if ($FixLocks) {
        Write-Host "[1/3] Updating dependency locks..." -ForegroundColor Yellow
        & .\gradlew.bat resolveAndLockAll --write-locks 2>&1 | Tee-Object -Variable lockOutput
        if ($LASTEXITCODE -ne 0) {
            Write-Host "ERROR: Failed to update dependency locks" -ForegroundColor Red
            Write-Host $lockOutput
            exit 1
        }
        Write-Host "Dependency locks updated." -ForegroundColor Green
    }

    # Step 2: Build (unless skipped)
    if (-not $SkipBuild) {
        Write-Host "[2/3] Building project..." -ForegroundColor Yellow
        # Dev runs use Vite's dev server separately, so bundling ui-web here is unnecessary and can be memory-heavy on Windows.
        & .\gradlew.bat -PskipWebBuild=true :modules:ui:classes 2>&1 | Tee-Object -Variable buildOutput
        if ($LASTEXITCODE -ne 0) {
            Write-Host "=======================================" -ForegroundColor Red
            Write-Host " BUILD FAILED" -ForegroundColor Red
            Write-Host "=======================================" -ForegroundColor Red
            Write-Host ""
            Write-Host "Build output:" -ForegroundColor Yellow
            Write-Host $buildOutput
            Write-Host ""
            Write-Host "Common fixes:" -ForegroundColor Cyan
            Write-Host "  1. Run with -FixLocks to update dependency locks"
            Write-Host "  2. Check for compilation errors in the output above"
            Write-Host "  3. Run: .\gradlew.bat --no-daemon :modules:adapters-lucene:compileJava"
            exit 1
        }
        Write-Host "Build successful." -ForegroundColor Green
    }

    # Step 3: Run the headless server
    Write-Host "[3/3] Starting headless API server on port $Port..." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Host " Server starting on port $Port" -ForegroundColor Green
    Write-Host " Engine logs: $env:LOCALAPPDATA\JustSearch\logs\engine.log" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "Press Ctrl+C to stop" -ForegroundColor Gray
    Write-Host ""
    
    $env:JUSTSEARCH_API_PORT = $Port
    & .\gradlew.bat --no-daemon -PskipWebBuild=true :modules:ui:runHeadless 2>&1
    
} finally {
    Pop-Location
}

