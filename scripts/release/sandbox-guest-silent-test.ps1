#Requires -Version 5.1
<#
.SYNOPSIS
  Guest-side silent install/uninstall verification for the NSIS installer (tempdoc 760 Phase 2
  item 1). Runs INSIDE a Windows Sandbox as the LogonCommand launched by
  sandbox-silent-install-test.ps1 -- never run this on a real machine, it installs and then
  uninstalls JustSearch using the DEFAULT per-user path (no /D override) and inspects registry +
  filesystem state along the way.

  *** DO NOT RUN THIS SCRIPT OUTSIDE WINDOWS SANDBOX. *** All of its lookups (candidate install
  dirs, the HKCU Uninstall registry key, the resolved UninstallString) are host-global, not
  scoped to any installer this script staged itself -- if a machine already has ANY product
  named "JustSearch" registered under Add/Remove Programs, this script WILL locate it and
  silently run ITS real uninstaller with /S, regardless of whether that install has anything to
  do with the -InstallerFileName this script was pointed at. This is empirically confirmed, not
  theoretical: an early validation pass of this harness was run directly on a real dev machine
  (outside Sandbox) using a non-functional placeholder installer file purely to check
  PowerShell/JSON plumbing, and it found and silently uninstalled a real pre-existing
  `F:\JustSearch-test` NSIS test install via its registry UninstallString -- removing its
  registry key, Start Menu/Desktop shortcuts, and uninstall.exe (a `resources\headless` remnant
  was left behind, uninstall was not 100% clean). Only ever run this from inside a disposable
  Windows Sandbox instance, never on a host with a real JustSearch install you care about.

.DESCRIPTION
  Empirically verifies, in one pass, the two facts tempdoc 760's Findings table flagged as
  "Never empirically verified" (CI always runs with -SkipVerify):
    1. `<installer>.exe /S` exits 0 and the app lands at the REAL default per-user install
       location a double-click install would produce (NOT a /D-overridden temp dir, which is
       what the existing scripts/ci/verify-installer-nsis-win.ps1 uses to test bundle contents).
    2. The matching uninstaller run with `/S` exits 0 and removes the install dir + registry
       uninstall key + shortcuts, leaving no leaked processes.

  Path/registry conventions asserted here (none guessed -- see tempdoc 760 addendum + this
  script's inline citations):
    - Install dir (currentUser, no /D):        $env:LOCALAPPDATA\JustSearch
      (tauri-bundler nsis/installer.nsi: `StrCpy $INSTDIR "$LOCALAPPDATA\${PRODUCTNAME}"` under
      the currentUser branch; PRODUCTNAME = "JustSearch" per tauri.conf.json:3.)
    - Main exe:                                 <installDir>\JustSearch.exe
      (tauri.conf.json:4 mainBinaryName; installer.nsi DisplayIcon/shortcut targets
      "$INSTDIR\${MAINBINARYNAME}.exe".)
    - Uninstall registry key:                   HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\JustSearch
      (installer.nsi: `!define UNINSTKEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PRODUCTNAME}"`,
      SHCTX resolves to HKCU for currentUser -- and independently confirmed already in THIS repo at
      scripts/ci/verify-installer-nsis-win.ps1:677, which hardcodes the identical literal key.)
    - Start Menu / Desktop shortcuts:           mirrors scripts/ci/verify-installer-nsis-win.ps1:685-686
      ($env:APPDATA\Microsoft\Windows\Start Menu\Programs\JustSearch\JustSearch.lnk,
      Desktop\JustSearch.lnk).
    - ProgramData policy dir (best-effort, non-recursive RMDir on uninstall):
      $env:ProgramData\JustSearch (modules/shell/src-tauri/nsis/installer-hooks.nsh:20-28,57-61).
    - Tauri "AI Home" / JUSTSEARCH_HOME (app_data_dir, created lazily on first RUN, not by NSIS
      install/uninstall at all): $env:APPDATA\io.justsearch.shell (Tauri v2 app_data_dir()
      resolves under FOLDERID_RoamingAppData + `identifier`, tauri.conf.json:6; lib.rs:331-334).
      NOTE: this is a DIFFERENT location than %LOCALAPPDATA% -- tempdoc 760's Phase 2 item 1
      wording says "clean %LOCALAPPDATA% removal", which only accurately describes the install
      dir itself. AI Home residue here is reported for visibility but does NOT gate PASS/FAIL:
      it is user data (models/logs), not installer-owned, and is not expected to exist at all
      for an install-then-uninstall run that never launches the app.

  Defensive by construction: every assertion is recorded independently (pass/fail + detail), a
  hard timeout guards every process wait (installer/uninstaller are force-killed and the step is
  marked FAILED rather than hanging the LogonCommand forever), and the full step/residue log is
  always written to a JSON file next to this script (i.e. in the mapped Sandbox share, so the
  host can read it after the sandbox closes) -- even if an unexpected exception occurs partway
  through.

.PARAMETER InstallTimeoutSec
  Hard timeout waiting for the installer process to exit. Force-kills + fails the step on expiry.

.PARAMETER UninstallTimeoutSec
  Hard timeout waiting for the uninstaller process to exit.

.PARAMETER InstallerFileName
  Literal installer filename to look for next to this script (the stable alias
  package-installer-win.ps1 stages into tmp/offline-installer-sandbox/share).

.PARAMETER ProductName
  Tauri productName (tauri.conf.json:3) -- also the NSIS PRODUCTNAME used for $INSTDIR and the
  registry uninstall key.

.PARAMETER MainBinaryName
  Tauri mainBinaryName (tauri.conf.json:4), i.e. the installed exe's filename.

.PARAMETER AppIdentifier
  Tauri identifier (tauri.conf.json:6) -- used to locate the Roaming AI Home dir for residue
  reporting only (see NOTE above; never gates PASS/FAIL).
#>
[CmdletBinding()]
param(
  [int]$InstallTimeoutSec = 300,
  [int]$UninstallTimeoutSec = 180,
  [string]$InstallerFileName = "JustSearch-LATEST-setup.exe",
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

# HARD GUARD, not just the header warning above: Windows Sandbox always runs its session as the
# built-in WDAGUtilityAccount user. Refuse to do ANYTHING on any other account -- this script
# uninstalls whatever "JustSearch" the machine has registered, so running it host-side is
# destructive by design (empirically: it silently uninstalled a real F:\JustSearch-test install
# during an early host-side validation run -- see header). No override switch on purpose: there
# is no legitimate host-side use; syntax-check with the AST parser instead of executing.
if ($env:USERNAME -ne "WDAGUtilityAccount") {
  Write-Error ("REFUSING TO RUN: not inside Windows Sandbox (USERNAME='" + $env:USERNAME + "', expected 'WDAGUtilityAccount'). " +
    "This script silently uninstalls the machine's registered JustSearch install and must only run as a Sandbox LogonCommand. " +
    "To validate syntax host-side use: [System.Management.Automation.Language.Parser]::ParseFile(...)")
  exit 99
}

$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }

$steps = New-Object System.Collections.Generic.List[object]
$residue = New-Object System.Collections.Generic.List[object]
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
    step       = $Name
    command    = $Command
    exitCode   = $ExitCode
    pass       = $Pass
    gating     = -not $NonGating.IsPresent
    detail     = $Detail
    timestamp  = (Get-Date).ToString("o")
  }) | Out-Null
  $status = if ($Pass) { "PASS" } else { "FAIL" }
  $gateTag = if ($NonGating.IsPresent) { "info" } else { "gate" }
  Write-Host ("[{0}][{1}] {2} -- {3}" -f $status, $gateTag, $Name, $Detail)
}

function Add-Residue {
  param([string]$Location, [bool]$Present, [string]$Note)
  $residue.Add([ordered]@{
    location = $Location
    present  = $Present
    note     = $Note
  }) | Out-Null
  if ($Present) {
    Write-Host ("[residue] {0} -- {1}" -f $Location, $Note)
  }
}

function Invoke-ExeWithTimeout {
  param([Parameter(Mandatory = $true)][string]$FilePath, [string]$Arguments = "", [int]$TimeoutSec = 300)
  $result = [ordered]@{ exitCode = $null; timedOut = $false; error = $null }
  try {
    $procArgs = @{
      FilePath     = $FilePath
      PassThru     = $true
      WindowStyle  = "Hidden"
    }
    if ($Arguments) { $procArgs.ArgumentList = $Arguments }
    $proc = Start-Process @procArgs
    Register-FixtureProcess $proc
    $exited = $proc.WaitForExit([int]($TimeoutSec * 1000))
    if (-not $exited) {
      $result.timedOut = $true
      $result.error = "process did not exit within ${TimeoutSec}s; force-killed"
      try { Stop-FixtureProcess $proc } catch {}
    } else {
      $result.exitCode = $proc.ExitCode
    }
  } catch {
    $result.error = $_.Exception.Message
  }
  return $result
}

function Get-InstallCandidateDirs {
  @(
    (Join-Path -Path $env:LOCALAPPDATA -ChildPath $ProductName),
    (Join-Path -Path (Join-Path -Path $env:LOCALAPPDATA -ChildPath "Programs") -ChildPath $ProductName)
  )
}

function Get-UninstallRegistryState {
  # Primary: literal key matching tauri-bundler's UNINSTKEY template, independently confirmed at
  # scripts/ci/verify-installer-nsis-win.ps1:677 in this repo.
  $primaryKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$ProductName"
  if (Test-Path -LiteralPath $primaryKey) {
    $props = Get-ItemProperty -LiteralPath $primaryKey -ErrorAction SilentlyContinue
    return [ordered]@{ found = $true; keyPath = $primaryKey; props = $props }
  }
  # Defensive fallback: enumerate all HKCU Uninstall subkeys for a DisplayName match, in case a
  # different tauri-bundler version keys the entry differently (e.g. by GUID).
  $uninstallRoot = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall"
  if (Test-Path -LiteralPath $uninstallRoot) {
    $match = Get-ChildItem -LiteralPath $uninstallRoot -ErrorAction SilentlyContinue | ForEach-Object {
      $p = Get-ItemProperty -LiteralPath $_.PSPath -ErrorAction SilentlyContinue
      if ($p -and $p.PSObject.Properties['DisplayName'] -and $p.DisplayName -like "*$ProductName*") { $p }
    } | Select-Object -First 1
    if ($match) {
      return [ordered]@{ found = $true; keyPath = $match.PSPath; props = $match }
    }
  }
  return [ordered]@{ found = $false; keyPath = $primaryKey; props = $null }
}

# ---------------------------------------------------------------------------
# Step 0 (non-gating, best-effort): Smart App Control disable.
#
# A fresh Windows Sandbox boots with Smart App Control enforcing, which HARD-blocks unsigned
# cargo/NSIS payloads with no "Run anyway" option (confirmed current behaviour --
# scripts/sandbox/sandbox-launch.py:889-903 applies this exact mitigation for the same reason,
# and documents it as externally reported unreliable, not a JustSearch regression: the real fix
# is code signing, tracked separately). If this mitigation doesn't take effect, a subsequent
# installer-launch failure below is very likely SAC, not an installer defect -- see this script's
# "known limitations" note surfaced in the result JSON.
# ---------------------------------------------------------------------------
try {
  & reg.exe add "HKLM\SYSTEM\CurrentControlSet\Control\CI\Policy" /v VerifiedAndReputablePolicyState /t REG_DWORD /d 0 /f 2>&1 | Out-Null
  & CiTool.exe -r 2>&1 | Out-Null
  Record -Name "sac_disable_attempt" -Command "reg add ...VerifiedAndReputablePolicyState /d 0 && CiTool.exe -r" -Pass $true `
    -Detail "Best-effort Smart App Control disable attempted (externally reported unreliable; does not gate overall PASS)." -NonGating
} catch {
  Record -Name "sac_disable_attempt" -Command "reg add ...VerifiedAndReputablePolicyState /d 0 && CiTool.exe -r" -Pass $true `
    -Detail "SAC disable attempt threw (non-fatal, best-effort): $($_.Exception.Message)" -NonGating
}

# ---------------------------------------------------------------------------
# Step 1: locate the installer next to this script.
# ---------------------------------------------------------------------------
$installerPath = Join-Path -Path $scriptDir -ChildPath $InstallerFileName
if (-not (Test-Path -LiteralPath $installerPath)) {
  $fallback = Get-ChildItem -LiteralPath $scriptDir -Filter "*setup*.exe" -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($fallback) { $installerPath = $fallback.FullName }
}
$installerFound = Test-Path -LiteralPath $installerPath
Record -Name "locate_installer" -Command "Test-Path $installerPath" -Pass $installerFound `
  -Detail $(if ($installerFound) { "Found: $installerPath" } else { "No installer found under $scriptDir (expected $InstallerFileName)" })

# ---------------------------------------------------------------------------
# Step 2 (non-gating): pre-install baseline -- a genuinely clean sandbox should show neither.
# Recorded for evidence context, not gated: this harness's job is install-then-uninstall
# correctness regardless of starting state.
# ---------------------------------------------------------------------------
$preCandidateDirs = Get-InstallCandidateDirs
$prePresentDirs = $preCandidateDirs | Where-Object { Test-Path -LiteralPath $_ }
$preReg = Get-UninstallRegistryState
Record -Name "pre_install_baseline" -Pass $true -NonGating `
  -Detail "Pre-existing install dirs: $(if ($prePresentDirs) { $prePresentDirs -join ', ' } else { 'none' }); pre-existing registry key: $($preReg.found)"

$overallResult = [ordered]@{
  schema        = "justsearch.sandbox-silent-install-test.v1"
  generatedAt   = (Get-Date).ToString("o")
  productName   = $ProductName
  mainBinaryName = $MainBinaryName
  appIdentifier = $AppIdentifier
  installerPath = $installerPath
  steps         = $steps
  residue       = $residue
  overallPass   = $false
  knownLimitations = @(
    "SAC (Smart App Control) may block the unsigned installer even after the best-effort disable attempt -- if run_installer_silent fails with a suspiciously immediate non-zero exit and no files land under any install dir candidate, treat SAC as the prime suspect, not an installer defect (see tempdoc 760 Findings: signing is not yet drop-in).",
    "This is a fully headless install/uninstall pass with no interactive GUI verification of the finish page, tray icon, or first-run UX -- only a live GUI session inside the actual Sandbox can confirm those.",
    "The app is never launched during this test, so app-runtime residue (e.g. JUSTSEARCH_HOME/AI Home contents beyond an empty dir, llama-server child processes, worker process) is out of scope here by design."
  )
}

function Write-ResultAndExit {
  param([int]$ExitCode)
  $overallResult.overallPass = $script:overallPass
  $overallResult.steps = $steps
  $overallResult.residue = $residue
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $resultPath = Join-Path -Path $scriptDir -ChildPath "silent-test-result-$stamp.json"
  try {
    ($overallResult | ConvertTo-Json -Depth 8) | Set-Content -LiteralPath $resultPath -Encoding UTF8
    Write-Host "Result written: $resultPath"
  } catch {
    Write-Host "ERROR: failed to write result JSON to ${resultPath}: $($_.Exception.Message)"
  }
  if ($script:overallPass) { Write-Host "PASS" } else { Write-Host "FAIL" }
  exit $ExitCode
}

if (-not $installerFound) {
  Write-ResultAndExit -ExitCode 1
}

try {
  # ---------------------------------------------------------------------------
  # Step 3: silent install with the DEFAULT per-user path (no /D override) -- this is the
  # literal path a real end user's double-click-then-/S (or winget) install takes.
  # ---------------------------------------------------------------------------
  $installResult = Invoke-ExeWithTimeout -FilePath $installerPath -Arguments "/S" -TimeoutSec $InstallTimeoutSec
  $installPass = (-not $installResult.timedOut) -and ($installResult.exitCode -eq 0)
  $installDetail = if ($installResult.timedOut) {
    $installResult.error
  } elseif ($null -ne $installResult.error) {
    "Start-Process threw: $($installResult.error)"
  } else {
    "Exit code: $($installResult.exitCode)"
  }
  Record -Name "run_installer_silent" -Command "`"$installerPath`" /S" -ExitCode $installResult.exitCode -Pass $installPass -Detail $installDetail

  # ---------------------------------------------------------------------------
  # Step 4: assert the app landed at the default per-user install dir + main exe present.
  # ---------------------------------------------------------------------------
  $candidateDirs = Get-InstallCandidateDirs
  $foundInstallDir = $candidateDirs | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
  $dirFound = $null -ne $foundInstallDir
  Record -Name "assert_install_dir_present" -Command "Test-Path <candidates>" -Pass $dirFound `
    -Detail "Checked: $($candidateDirs -join ' | '). Found: $(if ($dirFound) { $foundInstallDir } else { 'none' })"

  $exePresent = $false
  $exePath = $null
  if ($dirFound) {
    $exePath = Join-Path -Path $foundInstallDir -ChildPath $MainBinaryName
    $exePresent = Test-Path -LiteralPath $exePath
  }
  Record -Name "assert_main_exe_present" -Command "Test-Path <installDir>\$MainBinaryName" -Pass $exePresent `
    -Detail $(if ($exePresent) { "Found: $exePath" } elseif ($dirFound) { "Not found: $exePath" } else { "Skipped -- no install dir found" })

  # ---------------------------------------------------------------------------
  # Step 5: assert the registry Add/Remove Programs uninstall key exists post-install.
  # ---------------------------------------------------------------------------
  $regState = Get-UninstallRegistryState
  Record -Name "assert_registry_uninstall_key_present" -Command "Test-Path $($regState.keyPath)" -Pass $regState.found `
    -Detail $(if ($regState.found) { "Found: $($regState.keyPath); DisplayName=$($regState.props.DisplayName); DisplayVersion=$($regState.props.DisplayVersion)" } else { "Not found: $($regState.keyPath) (also scanned all HKCU Uninstall subkeys for a DisplayName match)" })

  # ---------------------------------------------------------------------------
  # Step 6: locate the uninstaller. Registry UninstallString first (what Control Panel /
  # Programs-and-Features / winget actually use); filesystem glob under the install dir as a
  # defensive fallback (mirrors scripts/ci/verify-installer-nsis-win.ps1:433).
  # ---------------------------------------------------------------------------
  $uninstallerPath = $null
  $uninstallerSource = $null
  if ($regState.found -and $regState.props -and $regState.props.PSObject.Properties['UninstallString'] -and $regState.props.UninstallString) {
    $candidate = $regState.props.UninstallString.Trim().Trim('"')
    if (Test-Path -LiteralPath $candidate) {
      $uninstallerPath = $candidate
      $uninstallerSource = "registry UninstallString"
    }
  }
  if (-not $uninstallerPath -and $dirFound) {
    $globMatch = Get-ChildItem -LiteralPath $foundInstallDir -Filter "*uninstall*.exe" -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($globMatch) {
      $uninstallerPath = $globMatch.FullName
      $uninstallerSource = "filesystem glob under install dir"
    }
  }
  $uninstallerFound = $null -ne $uninstallerPath -and (Test-Path -LiteralPath $uninstallerPath)
  Record -Name "assert_uninstaller_present" -Command "locate uninstaller (registry then filesystem glob)" -Pass $uninstallerFound `
    -Detail $(if ($uninstallerFound) { "Found via ${uninstallerSource}: $uninstallerPath" } else { "Not found via registry or filesystem glob under $foundInstallDir" })

  if ($uninstallerFound) {
    # ---------------------------------------------------------------------------
    # Step 7: silent uninstall.
    # ---------------------------------------------------------------------------
    $uninstallResult = Invoke-ExeWithTimeout -FilePath $uninstallerPath -Arguments "/S" -TimeoutSec $UninstallTimeoutSec
    $uninstallPass = (-not $uninstallResult.timedOut) -and ($uninstallResult.exitCode -eq 0)
    $uninstallDetail = if ($uninstallResult.timedOut) {
      $uninstallResult.error
    } elseif ($null -ne $uninstallResult.error) {
      "Start-Process threw: $($uninstallResult.error)"
    } else {
      "Exit code: $($uninstallResult.exitCode)"
    }
    Record -Name "run_uninstaller_silent" -Command "`"$uninstallerPath`" /S" -ExitCode $uninstallResult.exitCode -Pass $uninstallPass -Detail $uninstallDetail

    # ---------------------------------------------------------------------------
    # Step 8: assert clean removal -- install dir(s), registry key, no leaked processes.
    # ---------------------------------------------------------------------------
    $postCandidateDirs = Get-InstallCandidateDirs
    $postPresentDirs = @($postCandidateDirs | Where-Object { Test-Path -LiteralPath $_ })
    $dirsClean = $postPresentDirs.Count -eq 0
    Record -Name "assert_install_dir_removed" -Command "Test-Path <candidates>" -Pass $dirsClean `
      -Detail $(if ($dirsClean) { "All candidate install dirs absent." } else { "Still present: $($postPresentDirs -join ', ')" })

    $postRegState = Get-UninstallRegistryState
    $regClean = -not $postRegState.found
    Record -Name "assert_registry_uninstall_key_removed" -Command "Test-Path $($postRegState.keyPath)" -Pass $regClean `
      -Detail $(if ($regClean) { "Registry uninstall key absent." } else { "Still present: $($postRegState.keyPath)" })

    $leaked = @()
    try {
      $searchRoots = @($foundInstallDir) + $postPresentDirs | Where-Object { $_ } | Select-Object -Unique
      if ($searchRoots.Count -gt 0) {
        $procs = Get-CimInstance Win32_Process -ErrorAction Stop
        foreach ($root in $searchRoots) {
          $leaked += @($procs | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase) })
        }
      }
    } catch {
      Record -Name "assert_no_leaked_processes" -Pass $true -NonGating -Detail "Could not query processes (best-effort): $($_.Exception.Message)"
      $leaked = $null
    }
    if ($null -ne $leaked) {
      $noLeaks = $leaked.Count -eq 0
      Record -Name "assert_no_leaked_processes" -Command "Get-CimInstance Win32_Process | Where ExecutablePath under install dir" -Pass $noLeaks `
        -Detail $(if ($noLeaks) { "No leaked processes." } else { ($leaked | ForEach-Object { "PID=$($_.ProcessId) EXE=$($_.ExecutablePath)" }) -join "; " })
      if (-not $noLeaks) {
        # Leaked processes are reported above; no held fixture start identity exists.
      }
    }

    # ---------------------------------------------------------------------------
    # Residue enumeration (non-gating informational; see NOTE in header re: %LOCALAPPDATA% vs
    # the actual AI Home location).
    # ---------------------------------------------------------------------------
    $startMenuLnk = Join-Path -Path ([Environment]::GetFolderPath("Programs")) -ChildPath "$ProductName\$ProductName.lnk"
    Add-Residue -Location $startMenuLnk -Present (Test-Path -LiteralPath $startMenuLnk) -Note "Start Menu shortcut (mirrors verify-installer-nsis-win.ps1:685)"

    $startMenuFolder = Join-Path -Path ([Environment]::GetFolderPath("Programs")) -ChildPath $ProductName
    Add-Residue -Location $startMenuFolder -Present (Test-Path -LiteralPath $startMenuFolder) -Note "Start Menu folder (mirrors verify-installer-nsis-win.ps1:694)"

    $desktopLnk = Join-Path -Path ([Environment]::GetFolderPath("Desktop")) -ChildPath "$ProductName.lnk"
    Add-Residue -Location $desktopLnk -Present (Test-Path -LiteralPath $desktopLnk) -Note "Desktop shortcut (mirrors verify-installer-nsis-win.ps1:686)"

    $programDataDir = Join-Path -Path $env:ProgramData -ChildPath $ProductName
    Add-Residue -Location $programDataDir -Present (Test-Path -LiteralPath $programDataDir) -Note "Machine policy dir -- best-effort, non-recursive RMDir on uninstall (installer-hooks.nsh:57-61); non-empty dir would legitimately remain"

    $aiHomeDir = Join-Path -Path $env:APPDATA -ChildPath $AppIdentifier
    Add-Residue -Location $aiHomeDir -Present (Test-Path -LiteralPath $aiHomeDir) -Note "Tauri app_data_dir / AI Home (lib.rs:331-334) -- Roaming, NOT %LOCALAPPDATA%; not installer-owned, not expected to exist since the app is never launched by this test"
  }
} catch {
  Record -Name "unexpected_exception" -Pass $false -Detail "$($_.Exception.GetType().FullName): $($_.Exception.Message)`n$($_.ScriptStackTrace)"
} finally {
  Write-ResultAndExit -ExitCode $(if ($script:overallPass) { 0 } else { 1 })
}
