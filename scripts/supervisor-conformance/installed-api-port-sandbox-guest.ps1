#Requires -Version 5.1
<# Runs only as the Windows Sandbox LogonCommand. Never execute this script on the host. #>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$InstallerPath,
  [Parameter(Mandatory = $true)][string]$ExpectedInstallerSha256,
  [Parameter(Mandatory = $true)][string]$NodePath,
  [Parameter(Mandatory = $true)][string]$ExpectedNodeSha256,
  [Parameter(Mandatory = $true)][string]$FixturePath,
  [Parameter(Mandatory = $true)][string]$WorkRoot,
  [Parameter(Mandatory = $true)][string]$InstallDir,
  [Parameter(Mandatory = $true)][string]$OutputDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# This guard must remain before every filesystem, registry, or process action.
$osUser = [Environment]::UserName
$windowsIdentity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$isSandboxUser = $osUser -eq "WDAGUtilityAccount"
$isSandboxIdentity = $windowsIdentity.EndsWith(
  "\WDAGUtilityAccount", [StringComparison]::OrdinalIgnoreCase)
if (-not $isSandboxUser -or -not $isSandboxIdentity) {
  Write-Error "REFUSING TO RUN: installed API-port guest proof requires WDAGUtilityAccount"
  exit 99
}

$resultPath = Join-Path $OutputDir "installed-api-port-sandbox-result.json"
$stdoutPath = Join-Path $OutputDir "fixture-stdout.log"
$stderrPath = Join-Path $OutputDir "fixture-stderr.log"
$receipt = [ordered]@{
  schema = "justsearch.installed-api-port-sandbox-result.v1"
  startedAt = (Get-Date).ToUniversalTime().ToString("o")
  completedAt = $null
  passed = $false
  passedScope = "product fixture and in-guest app cleanup; host must independently verify Sandbox exit"
  platformBlock = $null
  error = $null
  installer = $null
  node = $null
  installedBinary = $null
  fixture = [ordered]@{ exitCode = $null; stdout = $stdoutPath; stderr = $stderrPath }
  uninstall = [ordered]@{ attempted = $false; exitCode = $null; error = $null }
  sandboxShutdown = [ordered]@{ scheduled = $false; exitCode = $null; error = $null }
}

function Invoke-OwnedProcess {
  param(
    [Parameter(Mandatory = $true)][string]$FilePath,
    [string[]]$ArgumentList = @(),
    [int]$TimeoutSeconds = 300,
    [string]$RedirectStandardOutput,
    [string]$RedirectStandardError
  )
  $start = @{ FilePath = $FilePath; ArgumentList = $ArgumentList; PassThru = $true; WindowStyle = "Hidden" }
  if ($RedirectStandardOutput) { $start.RedirectStandardOutput = $RedirectStandardOutput }
  if ($RedirectStandardError) { $start.RedirectStandardError = $RedirectStandardError }
  $process = Start-Process @start
  if (-not $process.WaitForExit([int]($TimeoutSeconds * 1000))) {
    $cleanupError = $null
    try { $process.Kill() } catch { $cleanupError = $_.Exception.Message }
    $detail = if ($cleanupError) { "; exact-owned process cleanup failed: $cleanupError" } else { "" }
    throw "owned process timed out after ${TimeoutSeconds}s: $FilePath$detail"
  }
  return $process.ExitCode
}

New-Item -ItemType Directory -Force -Path $OutputDir, $WorkRoot | Out-Null
Set-Content -LiteralPath $stdoutPath -Value "" -Encoding UTF8
Set-Content -LiteralPath $stderrPath -Value "" -Encoding UTF8
$uninstaller = $null
try {
  foreach ($required in @($InstallerPath, $NodePath, $FixturePath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
      throw "required staged input is missing: $required"
    }
  }
  if (Test-Path -LiteralPath $InstallDir) {
    throw "guest-private install directory already exists: $InstallDir"
  }
  $installerSha256 =
    (Get-FileHash -Algorithm SHA256 -LiteralPath $InstallerPath).Hash.ToLowerInvariant()
  $expectedInstallerSha256 = $ExpectedInstallerSha256.Trim().ToLowerInvariant()
  $receipt.installer = [ordered]@{
    path = $InstallerPath
    sha256 = $installerSha256
    expectedSha256 = $expectedInstallerSha256
    matchesExpected = $installerSha256 -eq $expectedInstallerSha256
  }
  $nodeSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $NodePath).Hash.ToLowerInvariant()
  $expectedNodeSha256 = $ExpectedNodeSha256.Trim().ToLowerInvariant()
  $receipt.node = [ordered]@{
    path = $NodePath
    sha256 = $nodeSha256
    expectedSha256 = $expectedNodeSha256
    matchesExpected = $nodeSha256 -eq $expectedNodeSha256
  }
  if (-not $receipt.installer.matchesExpected) {
    throw "staged installer SHA-256 does not match the host receipt"
  }
  if (-not $receipt.node.matchesExpected) {
    throw "staged Node SHA-256 does not match the host receipt"
  }

  try {
    $installExit = Invoke-OwnedProcess -FilePath $InstallerPath `
      -ArgumentList @("/S", "/D=$InstallDir") -TimeoutSeconds 300
  } catch {
    $receipt.platformBlock = "candidate installer could not execute in Windows Sandbox (possible Smart App Control); no policy or registry bypass was attempted"
    throw
  }
  $installedExe = Join-Path $InstallDir "JustSearch.exe"
  if ($installExit -ne 0 -or -not (Test-Path -LiteralPath $installedExe -PathType Leaf)) {
    $receipt.platformBlock = "candidate installation failed or was blocked (possible Smart App Control); no policy or registry bypass was attempted"
    throw "silent candidate install failed: exit=$installExit executable=$installedExe"
  }
  $receipt.installedBinary = [ordered]@{
    path = $installedExe
    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $installedExe).Hash.ToLowerInvariant()
  }
  $uninstaller = Get-ChildItem -LiteralPath $InstallDir -Filter "*uninstall*.exe" -Recurse -File |
    Select-Object -First 1
  if ($null -eq $uninstaller) {
    throw "owned guest installation has no uninstaller under $InstallDir"
  }

  $knownAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::ApplicationData)
  if ([string]::IsNullOrWhiteSpace($knownAppData)) {
    throw "Windows returned no ApplicationData known-folder path"
  }
  $env:JUSTSEARCH_INSTALLED_EXE = $installedExe
  $env:JUSTSEARCH_INSTALLED_API_PORT_WORK = $WorkRoot
  $env:JUSTSEARCH_INSTALLED_KNOWN_APPDATA = $knownAppData
  $fixtureExit = Invoke-OwnedProcess -FilePath $NodePath -ArgumentList @($FixturePath) `
    -TimeoutSeconds 720 -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
  $receipt.fixture.exitCode = $fixtureExit
  $fixtureOutput = if (Test-Path -LiteralPath $stdoutPath) {
    Get-Content -Raw -LiteralPath $stdoutPath
  } else { "" }
  if ($fixtureExit -ne 0 -or $fixtureOutput -notmatch "INSTALLED_API_PORT_RESTART_PASS ") {
    throw "installed API-port fixture failed: exit=$fixtureExit"
  }
  $receipt.passed = $true
} catch {
  $receipt.error = $_.Exception.ToString()
} finally {
  if ($null -ne $uninstaller -and (Test-Path -LiteralPath $uninstaller.FullName -PathType Leaf)) {
    $receipt.uninstall.attempted = $true
    try {
      $receipt.uninstall.exitCode = Invoke-OwnedProcess -FilePath $uninstaller.FullName `
        -ArgumentList @("/S") -TimeoutSeconds 300
      if ($receipt.uninstall.exitCode -ne 0) {
        $receipt.uninstall.error = "owned uninstaller returned exit $($receipt.uninstall.exitCode)"
        $receipt.passed = $false
      } elseif (Test-Path -LiteralPath $InstallDir) {
        $receipt.uninstall.error = "owned uninstaller left the guest installation directory: $InstallDir"
        $receipt.passed = $false
      }
    } catch {
      $receipt.uninstall.error = $_.Exception.ToString()
      $receipt.passed = $false
    }
  }
}

$exitCode = if ($receipt.passed) { 0 } else { 1 }
try {
  $shutdown = Join-Path $env:SystemRoot "System32\shutdown.exe"
  $receipt.sandboxShutdown.exitCode = Invoke-OwnedProcess -FilePath $shutdown `
    -ArgumentList @("/s", "/t", "5") -TimeoutSeconds 15
  $receipt.sandboxShutdown.scheduled = $receipt.sandboxShutdown.exitCode -eq 0
  if (-not $receipt.sandboxShutdown.scheduled) {
    throw "shutdown.exe returned exit $($receipt.sandboxShutdown.exitCode)"
  }
} catch {
  $receipt.passed = $false
  $exitCode = 1
  $shutdownError = "guest Sandbox shutdown could not be scheduled: $($_.Exception.Message)"
  $receipt.sandboxShutdown.error = $shutdownError
  if ($receipt.error) { $receipt.error = "$($receipt.error)`n$shutdownError" }
  else { $receipt.error = $shutdownError }
}
$receipt.completedAt = (Get-Date).ToUniversalTime().ToString("o")
($receipt | ConvertTo-Json -Depth 8) | Set-Content -LiteralPath $resultPath -Encoding UTF8
exit $exitCode
