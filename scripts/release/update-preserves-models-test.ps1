#Requires -Version 5.1
<#
.SYNOPSIS
  Empirical N->N+1 update lane for tempdoc 617 D2: "an app update never touches the user's
  models". Installs a published installer N, seeds authored model state, installs N+1 over it,
  then uninstalls -- asserting the seeded bytes survive both halves.

  *** DO NOT RUN THIS SCRIPT ON A MACHINE WITH A JUSTSEARCH INSTALL YOU CARE ABOUT. ***
  Like scripts/release/sandbox-guest-silent-test.ps1, every lookup here (install dir, the HKCU
  Uninstall key, the resolved UninstallString) is host-global rather than scoped to the
  installers this script staged. It will locate whatever product named "JustSearch" the machine
  has registered and silently run ITS uninstaller. That script's header records a real incident
  where exactly this removed a live `F:\JustSearch-test` install during host-side validation.
  A hard guard below therefore refuses to run outside GitHub Actions, with no override switch --
  syntax-check host-side with the AST parser instead of executing.

.DESCRIPTION
  Tempdoc 617 D2 chose monolithic full-installer updates on the condition that the ~9 GB of
  models in the user's data directory are reused in place and never re-downloaded or deleted.
  Until this lane, the only enforcement was the STATIC gate scripts/ci/check-update-preserves-
  models.mjs, which reads the declared packaging surface (tauri.conf.json bundle.resources, the
  bundleSidecarResources staging task, installer-hooks.nsh) and explicitly cannot prove anything
  about a BUILT installer. Tempdoc 617 section 9 items 3-5 reserved that proof for a manual
  Windows Sandbox round that, as of 617:798-800 (2026-09-05), had never been executed.

  What this script asserts, and why each location was chosen (no path is guessed):

    GATING -- packaged-app model home: %APPDATA%\<identifier>\models
      The shipped Tauri shell passes `-Djustsearch.data.dir=<app_data_dir>` and
      `JUSTSEARCH_HOME=<app_data_dir>` to the backend, where app_data_dir is Tauri's
      app_data_dir() = %APPDATA%\io.justsearch.shell (modules/shell/src-tauri/src/lib.rs:770-791),
      and creates <app_data_dir>\models itself (lib.rs:430, lib.rs:575). This is where a real
      user's ~9 GB actually lives, so it is the property under test. Tempdoc
      374-app-packaging-and-distribution.md:2089 observed the same location holding ~3.2 GB.

    OBSERVATIONAL (non-gating) -- contract-default data dir: %LOCALAPPDATA%\<productName>\models
      PlatformPaths / platform_paths.rs resolve the data dir to %LOCALAPPDATA%\JustSearch when
      JUSTSEARCH_DATA_DIR is unset (modules/shell/src-tauri/src/platform_paths.rs:57-70), and
      ResolvedPathResolver.java:66 puts models at <dataDir>/models. That is ALSO the NSIS
      currentUser $INSTDIR (tauri.conf.json bundle.windows.nsis.installMode = "currentUser";
      tauri-bundler's `StrCpy $INSTDIR "$LOCALAPPDATA\${PRODUCTNAME}"`, cited at
      scripts/release/sandbox-guest-silent-test.ps1:35-38). Install tree and contract-default
      data dir therefore COLLIDE. Anything seeded there sits inside the directory the
      uninstaller owns, so its removal is not necessarily a defect -- but it has never been
      measured, so this lane records the outcome instead of assuming it.

  Uninstall expectation is ADR-0024, as recorded in tempdoc 374:2350 ("Uninstall preserves data
  | ADR-0024 verified, 1.1 GB user data preserved") and 374:1297-1299 ("`uninstall.exe /S`
  removes the install tree cleanly ... but user data outside the install tree is by design
  preserved"). So uninstall is asserted to preserve the gating seeds, not just upgrade.

  NOTE on log scanning. The lane's brief asked for a check that no seeded path appears in the
  installer/uninstaller logs as deleted. Tauri's NSIS template is not built with NSIS logging
  enabled and `/S` emits no log file, so there is no log to scan. This script substitutes a
  STRONGER filesystem check: it snapshots the full recursive file list of both user-data roots
  before and after each installer invocation and reports every path that disappeared. Seeded
  paths disappearing is a gating failure; other disappearances are reported for context.

.PARAMETER BaseInstaller
  Path to the already-published installer that plays version N.

.PARAMETER CandidateInstaller
  Path to the installer that plays version N+1 and is installed over N.

.PARAMETER BaseVersion
  Expected DisplayVersion after installing N. Empty = record observed value without gating.

.PARAMETER CandidateVersion
  Expected DisplayVersion after installing N+1. Empty = record observed value without gating.

.PARAMETER OutFile
  Path the evidence JSON is written to. Always written, including on unexpected exceptions.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$BaseInstaller,
  [Parameter(Mandatory = $true)][string]$CandidateInstaller,
  [string]$BaseVersion = "",
  [string]$CandidateVersion = "",
  [string]$OutFile = "update-preserves-models.v1.json",
  [int]$InstallTimeoutSec = 900,
  [int]$UninstallTimeoutSec = 300,
  [string]$ProductName = "JustSearch",
  [string]$MainBinaryName = "JustSearch.exe",
  [string]$AppIdentifier = "io.justsearch.shell"
)
# Test/benchmark cleanup is limited to a process this fixture actually started.
$script:fixtureProcesses = @{}
function Register-FixtureProcess($Process) {
  if (-not $Process -or $Process.HasExited) { return }
  $null = $Process.Handle # retain the original Windows handle, pinning its identity
  $birth = $Process.StartTime.ToUniversalTime().ToFileTimeUtc()
  $script:fixtureProcesses[$Process.Id] = @{ Process = $Process; Birth = $birth }
}
function Stop-FixtureProcess($Process) {
  if (-not $Process) { return }
  if ($Process.HasExited) { return }
  $owned = $script:fixtureProcesses[$Process.Id]
  if (-not $owned -or -not [object]::ReferenceEquals($owned.Process, $Process)) {
    throw "No start-time identity recorded for fixture process $($Process.Id)"
  }
  if ($Process.StartTime.ToUniversalTime().ToFileTimeUtc() -ne $owned.Birth) {
    throw "Fixture identity changed for $($Process.Id)"
  }
  $Process.Kill() # held handle, never tree enumeration or a fresh PID lookup
  $null = $Process.WaitForExit(10000)
}


Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# HARD GUARD (no override switch, mirroring sandbox-guest-silent-test.ps1's stance): this script
# silently uninstalls the machine's registered JustSearch. There is no legitimate host-side use.
if ($env:GITHUB_ACTIONS -ne "true") {
  Write-Error ("REFUSING TO RUN: GITHUB_ACTIONS is not 'true'. This lane installs and then " +
    "silently uninstalls whatever JustSearch the machine has registered, and is only safe on a " +
    "disposable CI runner. To validate syntax host-side use: " +
    "[System.Management.Automation.Language.Parser]::ParseFile(...)")
  exit 99
}

$dataRootAppData = Join-Path -Path $env:APPDATA -ChildPath $AppIdentifier
$dataRootLocal = Join-Path -Path $env:LOCALAPPDATA -ChildPath $ProductName
$uninstallRoot = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall"
$primaryUninstallKey = Join-Path -Path $uninstallRoot -ChildPath $ProductName

$steps = New-Object System.Collections.Generic.List[object]
$phases = New-Object System.Collections.Generic.List[object]
# Initialized here, not at seeding time: every exit path runs the finally block, which publishes
# this into the evidence file. Left unset, an early exit (missing installer) dies in the finally
# under Set-StrictMode instead of writing the evidence explaining why it bailed.
$script:seeds = New-Object System.Collections.Generic.List[object]
$script:overallPass = $true

function Record {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [string]$Command = "",
    $ExitCode = $null,
    [Parameter(Mandatory = $true)][bool]$Pass,
    [string]$Detail = "",
    [switch]$NonGating
  )
  if (-not $NonGating.IsPresent) {
    $script:overallPass = $script:overallPass -and $Pass
  }
  $steps.Add([ordered]@{
    step      = $Name
    command   = $Command
    exitCode  = $ExitCode
    pass      = $Pass
    gating    = -not $NonGating.IsPresent
    detail    = $Detail
    timestamp = (Get-Date).ToString("o")
  }) | Out-Null
  $status = if ($Pass) { "PASS" } else { "FAIL" }
  $gateTag = if ($NonGating.IsPresent) { "info" } else { "gate" }
  Write-Host ("[{0}][{1}] {2} -- {3}" -f $status, $gateTag, $Name, $Detail)
}

function Invoke-ExeWithTimeout {
  param([Parameter(Mandatory = $true)][string]$FilePath, [string]$Arguments = "", [int]$TimeoutSec = 600)
  $result = [ordered]@{ exitCode = $null; timedOut = $false; error = $null }
  try {
    $procArgs = @{ FilePath = $FilePath; PassThru = $true; WindowStyle = "Hidden" }
    if ($Arguments) { $procArgs.ArgumentList = $Arguments }
    $proc = Start-Process @procArgs
    Register-FixtureProcess $proc
    $exited = $proc.WaitForExit([int]($TimeoutSec * 1000))
    if (-not $exited) {
      $result.timedOut = $true
      $result.error = "process did not exit within ${TimeoutSec}s; force-killed"
      try { Stop-FixtureProcess $proc } catch {}
    } else {
      # NSIS detaches an elevated/rebranded child in some modes; give the tree a beat to settle
      # before the filesystem is inspected, so a PASS is not read off a half-finished install.
      $proc.WaitForExit()
      $result.exitCode = $proc.ExitCode
    }
  } catch {
    $result.error = $_.Exception.Message
  }
  return $result
}

function Get-UninstallEntries {
  # All HKCU Add/Remove-Programs entries whose DisplayName mentions the product. Counting these
  # is how the lane distinguishes a genuine in-place UPGRADE (still exactly one entry) from a
  # side-by-side second install (two entries), which would make a "models survived" PASS
  # meaningless -- nothing would have been upgraded over.
  $found = New-Object System.Collections.Generic.List[object]
  if (-not (Test-Path -LiteralPath $uninstallRoot)) { return $found }
  foreach ($sub in (Get-ChildItem -LiteralPath $uninstallRoot -ErrorAction SilentlyContinue)) {
    $p = Get-ItemProperty -LiteralPath $sub.PSPath -ErrorAction SilentlyContinue
    if (-not $p) { continue }
    $displayName = if ($p.PSObject.Properties['DisplayName']) { [string]$p.DisplayName } else { "" }
    if ($displayName -like "*$ProductName*") {
      $found.Add([ordered]@{
        keyPath         = [string]$sub.PSPath
        displayName     = $displayName
        displayVersion  = if ($p.PSObject.Properties['DisplayVersion']) { [string]$p.DisplayVersion } else { "" }
        uninstallString = if ($p.PSObject.Properties['UninstallString']) { [string]$p.UninstallString } else { "" }
        installLocation = if ($p.PSObject.Properties['InstallLocation']) { [string]$p.InstallLocation } else { "" }
      }) | Out-Null
    }
  }
  return $found
}

function Wait-ForRegistryState {
  # A silent NSIS uninstall does NOT finish when the process you launched exits. The uninstaller
  # copies itself to $TEMP and relaunches from there so it can delete its own directory; the
  # ORIGINAL process returns 0 almost immediately. Sampling the filesystem at that moment reads a
  # half-done uninstall -- and would score a VACUOUS PASS, because the seeded files "survive" an
  # uninstaller that has not deleted anything yet. Poll for the observable completion signal (the
  # Add/Remove Programs entry appearing or disappearing) instead of trusting the exit code.
  param(
    [Parameter(Mandatory = $true)][ValidateSet("absent", "version")][string]$Until,
    [string]$ExpectedVersion = "",
    [int]$TimeoutSec = 240
  )
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    $entries = @(Get-UninstallEntries)
    $satisfied = if ($Until -eq "absent") {
      $entries.Count -eq 0
    } else {
      ($entries.Count -eq 1) -and ($entries[0].displayVersion -eq $ExpectedVersion)
    }
    if ($satisfied) {
      # Let the final batch of file operations drain before anything is hashed.
      Start-Sleep -Seconds 5
      return $true
    }
    Start-Sleep -Milliseconds 500
  }
  return $false
}

function New-SeedFile {
  # Instant large-file creation: write a random header (so the blob has a distinctive hash), then
  # SetLength to the target size. On NTFS this sets EOF without writing the tail, so a 256 MB
  # "weight blob" costs milliseconds and near-zero real disk.
  param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][long]$SizeBytes)
  $dir = Split-Path -Parent $Path
  if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
  $headerLength = [Math]::Min([long]4096, $SizeBytes)
  $header = New-Object byte[] $headerLength
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($header) } finally { $rng.Dispose() }
  $fs = [System.IO.File]::Create($Path)
  try {
    $fs.Write($header, 0, $header.Length)
    if ($SizeBytes -gt $fs.Length) { $fs.SetLength($SizeBytes) }
  } finally { $fs.Dispose() }
}

function Get-SeedObservation {
  param([Parameter(Mandatory = $true)][string]$Path)
  if (-not (Test-Path -LiteralPath $Path)) {
    return [ordered]@{ present = $false; sha256 = $null; sizeBytes = $null; lastWriteUtc = $null }
  }
  $item = Get-Item -LiteralPath $Path
  return [ordered]@{
    present      = $true
    sha256       = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    sizeBytes    = [long]$item.Length
    lastWriteUtc = $item.LastWriteTimeUtc.ToString("o")
  }
}

function Get-TreeSnapshot {
  param([Parameter(Mandatory = $true)][string[]]$Roots)
  $files = New-Object System.Collections.Generic.List[string]
  foreach ($root in $Roots) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    Get-ChildItem -LiteralPath $root -Recurse -File -Force -ErrorAction SilentlyContinue |
      ForEach-Object { $files.Add($_.FullName) | Out-Null }
  }
  return $files
}

# ---------------------------------------------------------------------------------------------
# Seed plan. `gating` marks the packaged-app model home, which is the property under test; the
# %LOCALAPPDATA% entries are the install-tree collision and are observational only (see header).
# ---------------------------------------------------------------------------------------------
$seedPlan = @(
  [ordered]@{ id = "appdata_chat_weights";  root = "appdata"; relative = "models\chat\model.gguf";               sizeBytes = 268435456; gating = $true },
  [ordered]@{ id = "appdata_onnx_weights";  root = "appdata"; relative = "models\onnx\ner\model.onnx";           sizeBytes = 1048576;   gating = $true },
  [ordered]@{ id = "appdata_onnx_manifest"; root = "appdata"; relative = "models\onnx\ner\build.json";           sizeBytes = 512;       gating = $true },
  [ordered]@{ id = "appdata_models_index";  root = "appdata"; relative = "models\manifest.json";                 sizeBytes = 2048;      gating = $true },
  [ordered]@{ id = "appdata_authored_state"; root = "appdata"; relative = "index\authored-state.marker";         sizeBytes = 4096;      gating = $true },
  [ordered]@{ id = "localappdata_weights";  root = "local";   relative = "models\chat\model.gguf";               sizeBytes = 67108864;  gating = $false },
  [ordered]@{ id = "localappdata_manifest"; root = "local";   relative = "models\manifest.json";                 sizeBytes = 512;       gating = $false }
)

$evidence = [ordered]@{
  schema           = "justsearch.update-preserves-models.v1"
  generatedAt      = (Get-Date).ToString("o")
  productName      = $ProductName
  appIdentifier    = $AppIdentifier
  runnerOs         = [string]([System.Environment]::OSVersion.VersionString)
  githubRunId      = [string]$env:GITHUB_RUN_ID
  githubSha        = [string]$env:GITHUB_SHA
  base             = [ordered]@{ installer = $BaseInstaller; expectedVersion = $BaseVersion; observedVersion = $null }
  candidate        = [ordered]@{ installer = $CandidateInstaller; expectedVersion = $CandidateVersion; observedVersion = $null }
  dataRoots        = [ordered]@{
    gatingAppData         = $dataRootAppData
    observationalLocal    = $dataRootLocal
    installDirCollidesWith = "observationalLocal"
  }
  # Bound by reference, never reassigned. Assigning an ARRAY into an OrderedDictionary key throws
  # System.ArgumentException ("Argument types do not match") because PowerShell reads it as a slice
  # assignment against the dictionary's int indexer -- which previously destroyed the evidence file
  # in the finally block. Populating the same List in place sidesteps the indexer entirely.
  seeds            = $script:seeds
  phases           = $phases
  steps            = $steps
  verdict          = "UNKNOWN"
  overallPass      = $false
  coverage         = [ordered]@{
    proves = @(
      "A published installer N installs silently (/S) on a hosted windows-latest runner with no admin prompt (installMode=currentUser).",
      "Installing published installer N+1 over N is a genuine in-place upgrade (single Add/Remove-Programs entry, DisplayVersion advances) and leaves every seeded byte in the packaged-app model home identical (sha256 + mtime).",
      "Running the resulting uninstaller with /S also leaves those seeded bytes identical, per ADR-0024."
    )
    doesNotProve = @(
      "Nothing about the app at runtime: the installed app is never launched, so this says nothing about whether the app re-downloads models after an update.",
      "Nothing about the in-app updater (tempdoc 617 section 9 item 4): this is the installer-over-release half only.",
      "Nothing about model state larger than the seeded fixtures; the ~9 GB figure is mimicked by a sparse blob, not reproduced.",
      "Nothing about %LOCALAPPDATA%\\JustSearch\\models, which is recorded but non-gating because it is inside the NSIS install tree."
    )
    logScanSubstitution = "Tauri's NSIS template emits no log under /S, so 'seeded path absent from the installer log' is unverifiable; a full recursive before/after file-list diff of both data roots is recorded instead."
  }
}

$script:published = $false

function Write-EvidenceAndExit {
  param([int]$ExitCode)
  # An early `exit` from inside the try re-enters the finally, which calls this again. Publish once
  # so the log carries a single verdict.
  if ($script:published) { exit $ExitCode }
  $script:published = $true
  $evidence.overallPass = $script:overallPass
  $evidence.verdict = if ($script:overallPass) { "PASS" } else { "FAIL" }
  $evidence.steps = $steps
  $evidence.phases = $phases
  $outDir = Split-Path -Parent $OutFile
  if ($outDir -and -not (Test-Path -LiteralPath $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
  }
  try {
    ($evidence | ConvertTo-Json -Depth 12) | Set-Content -LiteralPath $OutFile -Encoding UTF8
    Write-Host "Evidence written: $OutFile"
  } catch {
    Write-Host "ERROR: failed to write evidence JSON to ${OutFile}: $($_.Exception.Message)"
  }
  $verdict = $evidence.verdict
  Write-Host ("VERDICT {0}: update-preserves-models {1} -> {2}" -f $verdict, $BaseVersion, $CandidateVersion)
  if ($env:GITHUB_STEP_SUMMARY) {
    $line = "**update-preserves-models: $verdict** - base ``$BaseVersion`` -> candidate ``$CandidateVersion``; " +
      "gating seeds in ``$dataRootAppData``."
    Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Value $line
  }
  exit $ExitCode
}

try {
  # -------------------------------------------------------------------------------------------
  # Phase 0 -- preflight. A pre-existing install would make every later assertion ambiguous
  # (we could be measuring someone else's tree), so this fails closed rather than adapting.
  # -------------------------------------------------------------------------------------------
  Record -Name "locate_base_installer" -Command "Test-Path $BaseInstaller" `
    -Pass (Test-Path -LiteralPath $BaseInstaller) -Detail "Base (N) installer: $BaseInstaller"
  Record -Name "locate_candidate_installer" -Command "Test-Path $CandidateInstaller" `
    -Pass (Test-Path -LiteralPath $CandidateInstaller) -Detail "Candidate (N+1) installer: $CandidateInstaller"
  if (-not (Test-Path -LiteralPath $BaseInstaller) -or -not (Test-Path -LiteralPath $CandidateInstaller)) {
    Write-EvidenceAndExit -ExitCode 1
  }

  $preEntries = @(Get-UninstallEntries)
  $cleanRunner = ($preEntries.Count -eq 0) -and -not (Test-Path -LiteralPath $dataRootLocal)
  Record -Name "preflight_clean_runner" -Command "enumerate HKCU Uninstall + Test-Path $dataRootLocal" -Pass $cleanRunner `
    -Detail $(if ($cleanRunner) { "No pre-existing JustSearch install." } else { "Pre-existing state found (entries=$($preEntries.Count), installDir=$(Test-Path -LiteralPath $dataRootLocal)); refusing to measure an ambiguous machine." })
  if (-not $cleanRunner) { Write-EvidenceAndExit -ExitCode 1 }

  # -------------------------------------------------------------------------------------------
  # Phase 1 -- install N silently.
  # -------------------------------------------------------------------------------------------
  $installBase = Invoke-ExeWithTimeout -FilePath $BaseInstaller -Arguments "/S" -TimeoutSec $InstallTimeoutSec
  $installBasePass = (-not $installBase.timedOut) -and ($installBase.exitCode -eq 0)
  Record -Name "install_base_silent" -Command "`"$BaseInstaller`" /S" -ExitCode $installBase.exitCode -Pass $installBasePass `
    -Detail $(if ($installBase.timedOut) { $installBase.error } elseif ($installBase.error) { "Start-Process threw: $($installBase.error)" } else { "Exit code: $($installBase.exitCode)" })

  $baseExe = Join-Path -Path $dataRootLocal -ChildPath $MainBinaryName
  Record -Name "assert_base_main_exe_present" -Command "Test-Path $baseExe" -Pass (Test-Path -LiteralPath $baseExe) `
    -Detail "Expected default currentUser install dir: $dataRootLocal"

  $baseEntries = @(Get-UninstallEntries)
  $baseSingle = $baseEntries.Count -eq 1
  Record -Name "assert_base_single_uninstall_entry" -Command "count HKCU Uninstall entries matching $ProductName" -Pass $baseSingle `
    -Detail "Entries: $($baseEntries.Count)"
  $observedBaseVersion = if ($baseSingle) { $baseEntries[0].displayVersion } else { "" }
  $evidence.base.observedVersion = $observedBaseVersion
  if ($BaseVersion) {
    Record -Name "assert_base_display_version" -Command "HKCU Uninstall DisplayVersion" -Pass ($observedBaseVersion -eq $BaseVersion) `
      -Detail "Expected '$BaseVersion', observed '$observedBaseVersion'"
  } else {
    Record -Name "assert_base_display_version" -Pass $true -NonGating -Detail "No expected base version supplied; observed '$observedBaseVersion'"
  }
  $phases.Add([ordered]@{ phase = "install_base"; version = $observedBaseVersion; installDir = $dataRootLocal }) | Out-Null

  if (-not $installBasePass) { Write-EvidenceAndExit -ExitCode 1 }

  # -------------------------------------------------------------------------------------------
  # Phase 2 -- seed authored model state and record its exact identity.
  # -------------------------------------------------------------------------------------------
  foreach ($plan in $seedPlan) {
    $root = if ($plan.root -eq "appdata") { $dataRootAppData } else { $dataRootLocal }
    $abs = Join-Path -Path $root -ChildPath $plan.relative
    New-SeedFile -Path $abs -SizeBytes ([long]$plan.sizeBytes)
    $seeded = Get-SeedObservation -Path $abs
    $seeds.Add([ordered]@{
      id           = $plan.id
      gating       = [bool]$plan.gating
      root         = $plan.root
      path         = $abs
      seeded       = $seeded
      afterUpgrade = $null
      afterUninstall = $null
    }) | Out-Null
  }
  $allSeeded = @($seeds | Where-Object { -not $_.seeded.present }).Count -eq 0
  Record -Name "seed_authored_model_state" -Command "create $($seeds.Count) seed files" -Pass $allSeeded `
    -Detail (($seeds | ForEach-Object { "$($_.id)=$($_.seeded.sizeBytes)B" }) -join "; ")
  if (-not $allSeeded) {
    $evidence.seeds = $seeds
    Write-EvidenceAndExit -ExitCode 1
  }

  $preUpgradeTree = @(Get-TreeSnapshot -Roots @($dataRootAppData, $dataRootLocal))

  # -------------------------------------------------------------------------------------------
  # Phase 3 -- install N+1 over N. This is the monolithic update a real user receives.
  # -------------------------------------------------------------------------------------------
  $installCand = Invoke-ExeWithTimeout -FilePath $CandidateInstaller -Arguments "/S" -TimeoutSec $InstallTimeoutSec
  $installCandPass = (-not $installCand.timedOut) -and ($installCand.exitCode -eq 0)
  Record -Name "install_candidate_over_base_silent" -Command "`"$CandidateInstaller`" /S" -ExitCode $installCand.exitCode -Pass $installCandPass `
    -Detail $(if ($installCand.timedOut) { $installCand.error } elseif ($installCand.error) { "Start-Process threw: $($installCand.error)" } else { "Exit code: $($installCand.exitCode)" })

  $candSettled = Wait-ForRegistryState -Until "version" -ExpectedVersion $CandidateVersion -TimeoutSec $InstallTimeoutSec
  Record -Name "await_upgrade_completion" -Command "poll HKCU DisplayVersion until '$CandidateVersion'" -Pass $candSettled `
    -Detail $(if ($candSettled) { "Upgrade settled at DisplayVersion '$CandidateVersion'." } else { "Timed out waiting for DisplayVersion '$CandidateVersion'; the upgrade did not complete, so any survival result below would be vacuous." })

  $candEntries = @(Get-UninstallEntries)
  $candSingle = $candEntries.Count -eq 1
  Record -Name "assert_upgrade_not_side_by_side" -Command "count HKCU Uninstall entries matching $ProductName" -Pass $candSingle `
    -Detail "Entries after upgrade: $($candEntries.Count) (2+ would mean a side-by-side install, not an upgrade, making a survival PASS meaningless)"
  $observedCandVersion = if ($candSingle) { $candEntries[0].displayVersion } else { "" }
  $evidence.candidate.observedVersion = $observedCandVersion
  Record -Name "assert_installed_version_advanced" -Command "HKCU Uninstall DisplayVersion" `
    -Pass ($observedCandVersion -and ($observedCandVersion -ne $observedBaseVersion)) `
    -Detail "Base '$observedBaseVersion' -> candidate '$observedCandVersion'"
  if ($CandidateVersion) {
    Record -Name "assert_candidate_display_version" -Command "HKCU Uninstall DisplayVersion" -Pass ($observedCandVersion -eq $CandidateVersion) `
      -Detail "Expected '$CandidateVersion', observed '$observedCandVersion'"
  }
  $phases.Add([ordered]@{ phase = "install_candidate_over_base"; version = $observedCandVersion; entries = $candEntries.Count }) | Out-Null

  # -------------------------------------------------------------------------------------------
  # Phase 4 -- the property: every gating seed byte-identical after the upgrade.
  # -------------------------------------------------------------------------------------------
  foreach ($seed in $seeds) { $seed.afterUpgrade = Get-SeedObservation -Path $seed.path }
  foreach ($seed in $seeds) {
    $intact = $seed.afterUpgrade.present -and
      ($seed.afterUpgrade.sha256 -eq $seed.seeded.sha256) -and
      ($seed.afterUpgrade.lastWriteUtc -eq $seed.seeded.lastWriteUtc)
    $detail = if (-not $seed.afterUpgrade.present) {
      "DELETED by the upgrade: $($seed.path)"
    } elseif ($seed.afterUpgrade.sha256 -ne $seed.seeded.sha256) {
      "REWRITTEN: sha256 $($seed.seeded.sha256) -> $($seed.afterUpgrade.sha256)"
    } elseif ($seed.afterUpgrade.lastWriteUtc -ne $seed.seeded.lastWriteUtc) {
      "TOUCHED: mtime $($seed.seeded.lastWriteUtc) -> $($seed.afterUpgrade.lastWriteUtc)"
    } else {
      "Identical sha256 + mtime ($($seed.seeded.sizeBytes) B)"
    }
    if ($seed.gating) {
      Record -Name "assert_seed_survives_upgrade__$($seed.id)" -Command "sha256 + mtime compare" -Pass $intact -Detail $detail
    } else {
      Record -Name "observe_seed_after_upgrade__$($seed.id)" -Pass $intact -NonGating -Detail "$detail (install-tree collision; observational)"
    }
  }

  $postUpgradeTree = @(Get-TreeSnapshot -Roots @($dataRootAppData, $dataRootLocal))
  $upgradeDeletions = @($preUpgradeTree | Where-Object { $postUpgradeTree -notcontains $_ })
  Record -Name "record_upgrade_deletions" -Pass $true -NonGating `
    -Detail "Paths present before the upgrade and absent after: $($upgradeDeletions.Count)"
  $phases.Add([ordered]@{ phase = "upgrade_deletions"; count = $upgradeDeletions.Count; paths = @($upgradeDeletions | Select-Object -First 200) }) | Out-Null

  # -------------------------------------------------------------------------------------------
  # Phase 5 -- silent uninstall via the registry UninstallString (what Add/Remove Programs runs).
  # -------------------------------------------------------------------------------------------
  $uninstallerPath = $null
  if ($candSingle -and $candEntries[0].uninstallString) {
    $candidatePath = $candEntries[0].uninstallString.Trim().Trim('"')
    if (Test-Path -LiteralPath $candidatePath) { $uninstallerPath = $candidatePath }
  }
  Record -Name "locate_uninstaller" -Command "HKCU UninstallString" -Pass ($null -ne $uninstallerPath) `
    -Detail $(if ($uninstallerPath) { $uninstallerPath } else { "Could not resolve UninstallString to an existing file" })

  if ($uninstallerPath) {
    $preUninstallTree = @(Get-TreeSnapshot -Roots @($dataRootAppData, $dataRootLocal))
    $uninstall = Invoke-ExeWithTimeout -FilePath $uninstallerPath -Arguments "/S" -TimeoutSec $UninstallTimeoutSec
    $uninstallPass = (-not $uninstall.timedOut) -and ($uninstall.exitCode -eq 0)
    Record -Name "run_uninstaller_silent" -Command "`"$uninstallerPath`" /S" -ExitCode $uninstall.exitCode -Pass $uninstallPass `
      -Detail $(if ($uninstall.timedOut) { $uninstall.error } elseif ($uninstall.error) { "Start-Process threw: $($uninstall.error)" } else { "Exit code: $($uninstall.exitCode)" })

    # Gating, and the reason the survival assertions below mean anything: the uninstall must be
    # OBSERVED complete. Without this the seeds trivially "survive" an uninstaller still starting up.
    $uninstallSettled = Wait-ForRegistryState -Until "absent" -TimeoutSec $UninstallTimeoutSec
    Record -Name "await_uninstall_completion" -Command "poll HKCU Uninstall until the entry disappears" -Pass $uninstallSettled `
      -Detail $(if ($uninstallSettled) { "Uninstall observed complete (Add/Remove Programs entry gone)." } else { "Timed out after ${UninstallTimeoutSec}s with the entry still present; the uninstall did not complete, so the survival result below would be vacuous." })

    # -----------------------------------------------------------------------------------------
    # Phase 6 -- ADR-0024: uninstall must preserve user data too, not just the upgrade.
    # -----------------------------------------------------------------------------------------
    foreach ($seed in $seeds) { $seed.afterUninstall = Get-SeedObservation -Path $seed.path }
    foreach ($seed in $seeds) {
      $intact = $seed.afterUninstall.present -and
        ($seed.afterUninstall.sha256 -eq $seed.seeded.sha256) -and
        ($seed.afterUninstall.lastWriteUtc -eq $seed.seeded.lastWriteUtc)
      $detail = if (-not $seed.afterUninstall.present) {
        "DELETED by the uninstaller: $($seed.path)"
      } elseif ($seed.afterUninstall.sha256 -ne $seed.seeded.sha256) {
        "REWRITTEN: sha256 $($seed.seeded.sha256) -> $($seed.afterUninstall.sha256)"
      } else {
        "Identical sha256 + mtime"
      }
      if ($seed.gating) {
        Record -Name "assert_seed_survives_uninstall__$($seed.id)" -Command "sha256 + mtime compare" -Pass $intact -Detail "$detail (ADR-0024: uninstall preserves user data)"
      } else {
        Record -Name "observe_seed_after_uninstall__$($seed.id)" -Pass $intact -NonGating -Detail "$detail (install-tree collision; observational)"
      }
    }

    $postUninstallTree = @(Get-TreeSnapshot -Roots @($dataRootAppData, $dataRootLocal))
    $uninstallDeletions = @($preUninstallTree | Where-Object { $postUninstallTree -notcontains $_ })
    Record -Name "record_uninstall_deletions" -Pass $true -NonGating `
      -Detail "Paths present before the uninstall and absent after: $($uninstallDeletions.Count)"
    $phases.Add([ordered]@{ phase = "uninstall_deletions"; count = $uninstallDeletions.Count; paths = @($uninstallDeletions | Select-Object -First 200) }) | Out-Null

    $postEntries = @(Get-UninstallEntries)
    Record -Name "assert_uninstall_registry_key_removed" -Command "enumerate HKCU Uninstall" -Pass ($postEntries.Count -eq 0) `
      -Detail "Remaining entries: $($postEntries.Count)"
  }

} catch {
  Record -Name "unexpected_exception" -Pass $false -Detail "$($_.Exception.GetType().FullName): $($_.Exception.Message)`n$($_.ScriptStackTrace)"
} finally {
  # Nothing here may throw: this is the only path that publishes the evidence file, and an
  # exception raised in a finally replaces the real result with an unexplained stack trace.
  # $evidence.seeds is bound to $script:seeds by reference at construction, so there is nothing
  # to copy here.
  $exitCode = if ($script:overallPass) { 0 } else { 1 }
  Write-EvidenceAndExit -ExitCode $exitCode
}
