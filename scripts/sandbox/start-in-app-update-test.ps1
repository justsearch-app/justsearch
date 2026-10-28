# SPDX-License-Identifier: Apache-2.0
<#
Starts the loopback release feed and launches the installed previous release
with the Sandbox-only updater trust overrides inherited by the child process.

The installed SOURCE build must contain the updater and have been compiled with
JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE=1 and the Tauri updater config override
dangerousInsecureTransportProtocol=true. Production builds reject this path.
#>
param(
    [string]$ShareRoot = $PSScriptRoot,
    [int]$Port = 8765,
    # Drive check -> install with no operator present, so the apply machinery (prepare, freeze,
    # witnessed shutdown, installer launch, restart reconciliation) can be qualified unattended.
    # The app honours this only when it was compiled with JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE=1;
    # a production build ignores it entirely. Consent is a separate claim and stays with the
    # operator-driven whole-product round.
    [switch]$Autorun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$configPath = Join-Path $ShareRoot "updater-qualification.v1.json"
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$feedRoot = Join-Path $ShareRoot "updater-release"
$serverScript = Join-Path $ShareRoot "serve-updater-feed.ps1"
$installedExe = Join-Path $env:LOCALAPPDATA "JustSearch\JustSearch.exe"

foreach ($required in @($feedRoot, $serverScript, $installedExe)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Missing in-app updater qualification prerequisite: $required"
    }
}

$serverLog = Join-Path $feedRoot "server.log"
$server = Start-Process -FilePath "powershell.exe" -WindowStyle Hidden -PassThru -ArgumentList @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", "`"$serverScript`"",
    "-FeedRoot", "`"$feedRoot`"",
    "-Port", "$Port",
    "-LogPath", "`"$serverLog`""
)
$server.Id | Set-Content -LiteralPath (Join-Path $feedRoot "server.pid") -Encoding ASCII

$env:JUSTSEARCH_RELEASE_DESCRIPTOR_URL = "http://127.0.0.1:$Port/release.v2.json"
$env:JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY = [string]$config.metadataRootPublicKey
$env:JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID = [string]$config.metadataRootKeyId

if ($Autorun.IsPresent) {
    $env:JUSTSEARCH_UPDATER_QUALIFICATION_AUTORUN = "1"
}

Start-Process -FilePath $installedExe
Write-Host "Updater feed: $env:JUSTSEARCH_RELEASE_DESCRIPTOR_URL"
Write-Host "Feed server PID: $($server.Id)"
Write-Host "Launched: $installedExe"
if ($Autorun.IsPresent) {
    Write-Host "Autorun requested: the app will check and apply without operator input."
    Write-Host "Verdict lands in the app data dir as upgrade\qualification-result.v1.json;"
    Write-Host "run .\collect-updater-evidence.ps1 after the target build restarts."
}
