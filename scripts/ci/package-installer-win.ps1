#Requires -Version 5.1
[CmdletBinding()]
param(
  # Skip building the installer (assume an existing *-setup.exe already exists).
  # When skipped, searches both target/x86_64-pc-windows-msvc/release/bundle/nsis (Tauri default)
  # and target/release/bundle/nsis (fallback) for an existing installer.
  [switch]$SkipBuild,

  # Skip running the deterministic installer verification gate (verify-installer-nsis-win.ps1).
  [switch]$SkipVerify,

  # Optional: override which setup exe to verify/copy. Bypasses automatic search entirely.
  [string]$SetupExePath,

  # Where to copy the produced installer for CI artifact upload.
  [string]$OutDir = "dist/installer",

  # Engage code signing WITHOUT release-versioning semantics. -Sign builds with the signing
  # overlay (tauri.signing.conf.json), sets JUSTSEARCH_REQUIRE_SIGNING=true, and runs the
  # signature_verify phase -- but does NOT force a strict x.y.z version, so CI can rehearse or
  # perform signing on non-tag dry-runs. -Release implies -Sign. (Tempdoc 760 Phase 2 item 3.)
  [switch]$Sign,

  # Require release-safe versioning + signing (intended for real distribution).
  # - Enforces gradle.properties version to be strict x.y.z (no suffix)
  # - Implies -Sign (signing overlay + JUSTSEARCH_CODESIGN_* required, fails if missing)
  [switch]$Release,

  # Root output for EvidenceBundle v1 capture (default is repo-local tmp/agent-evidence).
  [string]$EvidenceOutRoot = "tmp/agent-evidence",

  # Disable EvidenceBundle capture for this script.
  [switch]$NoEvidence,

  # If set, validate the produced EvidenceBundle v1 (always gates).
  [switch]$ValidateEvidenceBundle,

  # If set, assemble the full release asset set (installer + SHA256SUMS + MCPB bundle)
  # into -OutDir via build-release-assets.ps1 after the installer is staged. Default off,
  # so ordinary dev/smoke builds are unaffected. -Release additionally verifies that
  # server.json's version + asset URL match the gradle.properties version being cut.
  [switch]$AssembleReleaseAssets,

  # Produce Tauri updater signatures and the authenticated release descriptor/feed.
  # Requires -AssembleReleaseAssets and the release key/sequence parameters below.
  [switch]$AssembleUpdaterAssets,

  [long]$ReleaseSequence = 0,
  [string]$CompatibilityBaselinePath,
  [string]$InstallerUrl,
  [string]$ArtifactKeyId,
  [string]$ArtifactPublicKey,
  [string]$MetadataKeyId,
  [string]$MetadataPrivateKeyPath,
  [string]$MetadataPublicKeyPath,

  # If set (independent of -Release/signing), verify server.json version + asset URL match the
  # gradle.properties version during asset assembly. The release workflow passes this on v* tag
  # refs so a real release cut cannot ship a stale server.json version/URL. (Tempdoc 726 review.)
  [switch]$VerifyReleaseVersion
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent (Split-Path -Parent $scriptDir) # scripts/ci -> scripts -> repo root

if ($AssembleUpdaterAssets.IsPresent -and -not $AssembleReleaseAssets.IsPresent) {
  throw "-AssembleUpdaterAssets requires -AssembleReleaseAssets."
}
if ($Release.IsPresent -and $env:JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE) {
  throw "Production -Release builds must not enable JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE."
}
if ($AssembleUpdaterAssets.IsPresent) {
  if ([string]::IsNullOrWhiteSpace($CompatibilityBaselinePath) -or
      -not (Test-Path -LiteralPath $CompatibilityBaselinePath -PathType Leaf)) {
    throw "-AssembleUpdaterAssets requires an existing predecessor -CompatibilityBaselinePath."
  }
  $CompatibilityBaselinePath = (Resolve-Path -LiteralPath $CompatibilityBaselinePath).Path
  & node (Join-Path $repoRoot "scripts/release/app-release-assets.mjs") check-compatibility `
    --compatibility (Join-Path $repoRoot "governance/store-recoverability.v1.json") `
    --compat-baseline $CompatibilityBaselinePath
  if ($LASTEXITCODE -ne 0) { throw "Predecessor compatibility preflight failed; refusing build/signing." }
  foreach ($name in @(
      "JUSTSEARCH_RELEASE_DESCRIPTOR_URL",
      "JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY",
      "JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID")) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($value)) {
      throw "-AssembleUpdaterAssets requires compile-time release input $name."
    }
  }
}

# Receipt dropped by scripts/ci/sign-windows.ps1 after it signs AND verifies the uninstaller.
# Path must stay in sync with that script's $script:receiptPath.
$uninstallerReceiptPath = Join-Path -Path $repoRoot -ChildPath "dist\uninstaller-signing-receipt.json"

$evidenceEnabled = -not $NoEvidence.IsPresent
$summaryDir = Join-Path -Path $repoRoot -ChildPath "tmp\\agent-evidence\\_summaries"
$null = New-Item -ItemType Directory -Force -Path $summaryDir
$timestamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$transcriptPath = Join-Path -Path $summaryDir -ChildPath ("package-installer-win-" + $timestamp + ".transcript.log")
$installerMetaPath = Join-Path -Path $summaryDir -ChildPath ("package-installer-win-" + $timestamp + ".installer.json")
$transcriptStarted = $false
$installerDest = $null
$mainError = $null

# Timing infrastructure for build observability
$phaseTimings = [ordered]@{}
$totalSw = [System.Diagnostics.Stopwatch]::StartNew()

function Format-ElapsedMs([int64]$ms) {
  if ($ms -lt 1000) { return "${ms}ms" }
  $secs = [math]::Round($ms / 1000.0, 1)
  if ($secs -lt 60) { return "${secs}s" }
  $mins = [math]::Floor($secs / 60)
  $remSecs = [math]::Round($secs - ($mins * 60), 0)
  return "${mins}m ${remSecs}s"
}

function Measure-Phase {
  param([string]$Name, [scriptblock]$Block)
  Write-Host "`n=== $Name ===" -ForegroundColor Cyan
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  $failed = $false
  try { & $Block }
  catch {
    $failed = $true
    throw
  }
  finally {
    $sw.Stop()
    $script:phaseTimings[$Name] = @{ ms = $sw.ElapsedMilliseconds; failed = $failed }
    $timeStr = Format-ElapsedMs $sw.ElapsedMilliseconds
    if ($failed) {
      Write-Host ("  [FAILED after $timeStr]") -ForegroundColor Red
    } else {
      Write-Host ("  [$timeStr]") -ForegroundColor DarkGray
    }
  }
}

function Skip-Phase {
  param([string]$Name, [string]$Reason = "")
  $script:phaseTimings[$Name] = $null
  Write-Host "`n=== $Name ===" -ForegroundColor Cyan
  $msg = if ($Reason) { "[SKIPPED - $Reason]" } else { "[SKIPPED]" }
  Write-Host ("  " + $msg) -ForegroundColor Yellow
}

# Round-16 F3 follow-up: makensis ignores the exit code of its `!uninstfinalize` command, so
# sign-windows.ps1 failing on the uninstaller -- or never being invoked at all -- ships an unsigned
# uninstaller with no error anywhere. Signing the setup exe cannot detect that: the uninstaller is
# compressed INSIDE it. The receipt is the only signal, and sign-windows.ps1 writes it strictly
# after Assert-Signed passed and the signed bytes were written back, so absence covers both a
# failed hook and one that never ran.
function Assert-UninstallerSigningReceipt {
  param([Parameter(Mandatory = $true)][string]$ReceiptPath)
  $diagnosis = "Diagnose with the sign log the bundler writes: %TEMP%\justsearch-sign-windows.log. " +
    "If Tauri/NSIS changed how the uninstaller is handed to the sign hook (e.g. it now arrives " +
    "with a signable extension, bypassing the extension-shim path), update the receipt write in " +
    "scripts/ci/sign-windows.ps1 -- do not drop this check."
  if (-not (Test-Path -LiteralPath $ReceiptPath)) {
    throw ("Uninstaller signing receipt missing: " + $ReceiptPath + "`n" +
      "The NSIS uninstaller sign hook did not complete, so this bundle would ship an UNSIGNED uninstaller.`n" +
      $diagnosis)
  }
  $receipt = Get-Content -Raw -LiteralPath $ReceiptPath | ConvertFrom-Json
  $verifiedProp = $receipt.PSObject.Properties['verified']
  if (-not $verifiedProp -or $verifiedProp.Value -ne $true) {
    throw ("Uninstaller signing receipt is not verified: " + $ReceiptPath + "`n" + $diagnosis)
  }
  $target = if ($receipt.PSObject.Properties['target']) { [string]$receipt.target } else { "(unknown)" }
  $signedAt = if ($receipt.PSObject.Properties['signedAtUtc']) { [string]$receipt.signedAtUtc } else { "(unknown)" }
  Write-Host ("  uninstaller signing receipt OK (target=" + $target + ", signedAtUtc=" + $signedAt + ")")
}

if ($evidenceEnabled) {
  try {
    Start-Transcript -Path $transcriptPath -Force | Out-Null
    $transcriptStarted = $true
  } catch {
    $transcriptStarted = $false
    Write-Warning "Start-Transcript failed (non-fatal): $($_.Exception.Message)"
  }
}

$outDirPath = if ([System.IO.Path]::IsPathRooted($OutDir)) { $OutDir } else { Join-Path -Path $repoRoot -ChildPath $OutDir }
New-Item -ItemType Directory -Force -Path $outDirPath | Out-Null

try {
  Push-Location $repoRoot
  try {
  # Ensure rustup shims take precedence over any system-installed Rust (e.g., chocolatey GNU toolchain).
  # This avoids Tauri builds accidentally linking with MinGW when MSVC is required.
  $rustupBin = Join-Path -Path $env:USERPROFILE -ChildPath ".cargo\\bin"
  if (Test-Path -LiteralPath $rustupBin) {
    $env:Path = "$rustupBin;$env:Path"
  }

  function Get-RustcHostTriple {
    try {
      $out = & rustc -Vv 2>$null
      $m = $out | Select-String -Pattern '^host:\s+' -ErrorAction SilentlyContinue | Select-Object -First 1
      if ($m -and $m.Line -match '^host:\s*(\S+)\s*$') {
        return $Matches[1]
      }
    } catch {}
    return $null
  }

  function Find-NsisSetupExe {
    param([Parameter(Mandatory = $true)][string]$TauriSrcDir)

    # Tauri builds to target/<triple>/release when using explicit target; otherwise target/release.
    # The x86_64-pc-windows-msvc triple is Tauri's default on Windows with MSVC toolchain.
    $candidateDirs = @(
      (Join-Path -Path $TauriSrcDir -ChildPath "target\\x86_64-pc-windows-msvc\\release\\bundle\\nsis"),
      (Join-Path -Path $TauriSrcDir -ChildPath "target\\release\\bundle\\nsis")
    )

    foreach ($dir in $candidateDirs) {
      if (Test-Path -LiteralPath $dir -PathType Container) {
        $found = Get-ChildItem -LiteralPath $dir -Filter "*-setup.exe" -File -ErrorAction SilentlyContinue |
                 Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($found) {
          Write-Host "Found installer in: $dir" -ForegroundColor Green
          return $found.FullName
        }
      }
    }
    return $null
  }

  function Assert-TauriWindowsBuildPrereqs {
    # 1) Rust toolchain must be MSVC (GNU will fail at link time for Tauri on Windows).
    # NOTE: PowerShell variables are case-insensitive; `$Host` is a built-in read-only variable.
    # Do not use `$host` here.
    $rustHost = Get-RustcHostTriple
    if (-not $rustHost) {
      throw "rustc not found (or rustc -Vv failed). Install Rust via rustup and ensure '$($env:USERPROFILE)\\.cargo\\bin' is on PATH."
    }
    if ($rustHost -like '*windows-gnu') {
      $rustcPaths = @()
      try { $rustcPaths = @(where.exe rustc 2>$null) } catch {}
      $pathsMsg = if ($rustcPaths.Count -gt 0) { "`nrustc resolved to:`n  - " + ($rustcPaths -join "`n  - ") } else { "" }
      throw ("Rust host triple is '$rustHost' (GNU). Tauri on Windows requires MSVC. Fix:`n" +
        "  - rustup toolchain install stable-x86_64-pc-windows-msvc`n" +
        "  - rustup default stable-x86_64-pc-windows-msvc`n" +
        "Also ensure rustup shims take precedence over any Chocolatey/MSYS Rust in PATH." + $pathsMsg)
    }

    # 2) MSVC C++ build tools should be installed (prefer vswhere to avoid relying on cl.exe being on PATH).
    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\\Installer\\vswhere.exe"
    if (Test-Path -LiteralPath $vswhere) {
      # `-all` is required: vswhere hides installs reporting isComplete=0 / isLaunchable=0,
      # a common state for VS Build Tools that are still functionally usable (cl.exe present,
      # Rust links fine). Without `-all` the query returns empty and we'd wrongly fail
      # "MSVC not found" on a working toolchain. (tempdoc 562 item 1.)
      $installPath = & $vswhere -latest -all -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath 2>$null
      if (-not $installPath) {
        # Secondary guard before failing: an on-PATH cl.exe means MSVC is usable even if
        # vswhere can't report it (component-metadata quirks).
        $clOnPath = $null
        try { $clOnPath = where.exe cl.exe 2>$null } catch {}
        if (-not $clOnPath) {
          throw "MSVC C++ Build Tools not found (Desktop development with C++). Install Visual Studio Build Tools / VS and re-run."
        }
      }
    } else {
      # Best-effort fallback when vswhere isn't available.
      try {
        $null = where.exe cl.exe 2>$null
      } catch {
        Write-Warning "vswhere.exe not found and cl.exe not on PATH; if Tauri build fails, install MSVC Build Tools (Desktop development with C++)."
      }
    }

    # 3) Smart App Control (SAC) enforcement blocks unsigned, freshly-compiled cargo
    # build-scripts with `os error 4551` ~15 min into the Rust compile. Detect it up front
    # and fail fast with an actionable message. No-op on CI runners and non-SAC machines
    # (the policy key is absent or 0). Override with JUSTSEARCH_SKIP_SAC_CHECK=1. (tempdoc 562 item 2.)
    if (-not $env:JUSTSEARCH_SKIP_SAC_CHECK) {
      $sacState = $null
      try {
        $sacState = (Get-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy' -Name VerifiedAndReputablePolicyState -ErrorAction Stop).VerifiedAndReputablePolicyState
      } catch { $sacState = $null }
      if ($sacState -eq 1) {
        throw ("Windows Smart App Control is enforcing (VerifiedAndReputablePolicyState=1).`n" +
          "It blocks unsigned cargo build-scripts, so the Tauri/Rust build will fail with`n" +
          "'os error 4551' partway through the Rust compile. Fix one of:`n" +
          "  - Disable Smart App Control: Windows Security > App & browser control > Smart App Control > Off`n" +
          "  - Or build on CI (no SAC): gh workflow run build-installer.yml`n" +
          "Set JUSTSEARCH_SKIP_SAC_CHECK=1 to bypass this preflight.")
      }
    }
  }

  # -Release implies -Sign: both engage the signing overlay + JUSTSEARCH_REQUIRE_SIGNING + the
  # signature_verify phase; only -Release additionally forces strict release-semver (below).
  # (Tempdoc 760 Phase 2 item 3.)
  $doSign = $Sign.IsPresent -or $Release.IsPresent

  # Keep versions in sync (Gradle -> Tauri/Cargo/npm). This is deterministic and cheap.
  Measure-Phase "version_sync" {
    $syncArgs = @()
    if ($Release.IsPresent) { $syncArgs += "-RequireReleaseSemver" }
    & powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\ci\sync-version.ps1 @syncArgs
    if ($LASTEXITCODE -ne 0) { throw "sync-version.ps1 failed (exit=$LASTEXITCODE)" }
  }

  # -Sign (implied by -Release): require signing during bundling (Tauri signCommand reads this).
  if ($doSign) {
    $env:JUSTSEARCH_REQUIRE_SIGNING = "true"
    # Drop any receipt left by an earlier run so the signature_verify assert below can only be
    # satisfied by THIS build's sign hook.
    if (Test-Path -LiteralPath $uninstallerReceiptPath) {
      Remove-Item -LiteralPath $uninstallerReceiptPath -Force
    }
  }

  if (-not $SkipBuild.IsPresent) {
    Assert-TauriWindowsBuildPrereqs

    Measure-Phase "tauri_build" {
      # Build NSIS installer (tauri.conf.json enforces ui-web build + bundleSidecar via hooks).
      $tauriArgs = @("--prefix", ".\modules\shell", "run", "tauri", "--", "build", "--bundles", "nsis")
      if ($AssembleUpdaterAssets.IsPresent) {
        if ([string]::IsNullOrWhiteSpace($env:TAURI_SIGNING_PRIVATE_KEY)) {
          throw "-AssembleUpdaterAssets requires TAURI_SIGNING_PRIVATE_KEY so Tauri emits a verifiable NSIS signature."
        }
        $updaterConfig = ".\src-tauri\tauri.updater.conf.json"
        if ($doSign) {
          # Tauri accepts one config overlay. Compose the release-only updater overlay with the
          # independent Authenticode signCommand overlay in memory so neither concern leaks into
          # the base development config.
          $merged = Get-Content -Raw -LiteralPath ".\modules\shell\src-tauri\tauri.updater.conf.json" | ConvertFrom-Json
          $signing = Get-Content -Raw -LiteralPath ".\modules\shell\src-tauri\tauri.signing.conf.json" | ConvertFrom-Json
          $merged.bundle | Add-Member -MemberType NoteProperty -Name windows -Value $signing.bundle.windows -Force
          $tauriArgs += @("--config", ($merged | ConvertTo-Json -Depth 20 -Compress))
        } else {
          $tauriArgs += @("--config", $updaterConfig)
        }
      } elseif ($doSign) {
        $tauriArgs += @("--config", ".\src-tauri\tauri.signing.conf.json")
      } else {
        # Dev/CI smoke: avoid requiring updater signing or Windows SDK SignTool.
        $tauriArgs += "--no-sign"
      }
      & npm @tauriArgs
      if ($LASTEXITCODE -ne 0) { throw "tauri build failed (exit=$LASTEXITCODE)" }
    }
  } else {
    Skip-Phase "tauri_build" "SkipBuild flag"
  }

  if ($SetupExePath) {
    if (-not [System.IO.Path]::IsPathRooted($SetupExePath)) {
      $SetupExePath = (Resolve-Path -LiteralPath $SetupExePath).Path
    }
  } else {
    $tauriSrcDir = Join-Path -Path $repoRoot -ChildPath "modules\\shell\\src-tauri"
    $SetupExePath = Find-NsisSetupExe -TauriSrcDir $tauriSrcDir
    if (-not $SetupExePath) {
      throw ("No NSIS *-setup.exe found in:`n" +
             "  - target\\x86_64-pc-windows-msvc\\release\\bundle\\nsis`n" +
             "  - target\\release\\bundle\\nsis`n" +
             "Run without -SkipBuild or specify -SetupExePath explicitly.")
    }
  }

  if (-not (Test-Path -LiteralPath $SetupExePath)) {
    throw "Setup exe not found: $SetupExePath"
  }

  if (-not $SkipVerify.IsPresent) {
    Measure-Phase "installer_verify" {
      & powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\ci\verify-installer-nsis-win.ps1 -SetupExePath $SetupExePath
      if ($LASTEXITCODE -ne 0) { throw "verify-installer-nsis-win.ps1 failed (exit=$LASTEXITCODE)" }
    }
  } else {
    Skip-Phase "installer_verify" "SkipVerify flag"
  }

  if ($doSign) {
    Measure-Phase "signature_verify" {
      & powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\ci\verify-windows-signature.ps1 $SetupExePath
      if ($LASTEXITCODE -ne 0) { throw "verify-windows-signature.ps1 failed (exit=$LASTEXITCODE)" }
      & powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\ci\verify-pe-signatures.ps1 `
        -Path $SetupExePath -Extract -IncludeOuter
      if ($LASTEXITCODE -ne 0) { throw "verify-pe-signatures.ps1 failed (exit=$LASTEXITCODE)" }
      if ($SkipBuild.IsPresent) {
        Write-Host "  uninstaller signing receipt: not asserted (-SkipBuild: the NSIS sign hook did not run in this invocation)" -ForegroundColor Yellow
      } else {
        Assert-UninstallerSigningReceipt -ReceiptPath $uninstallerReceiptPath
      }
    }
  } else {
    Skip-Phase "signature_verify" "not a signing build"
  }

  $sourceInstallerHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $SetupExePath).Hash.ToLowerInvariant()
  $sourceInstallerSize = (Get-Item -LiteralPath $SetupExePath).Length
  $dest = Join-Path -Path $outDirPath -ChildPath ([System.IO.Path]::GetFileName($SetupExePath))
  if (-not [string]::Equals($SetupExePath, $dest, [System.StringComparison]::OrdinalIgnoreCase)) {
    $stagedDest = "$dest.staging-$PID"
    try {
      Copy-Item -LiteralPath $SetupExePath -Destination $stagedDest -Force
      $stagedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $stagedDest).Hash.ToLowerInvariant()
      $stagedSize = (Get-Item -LiteralPath $stagedDest).Length
      if ($stagedHash -ne $sourceInstallerHash -or $stagedSize -ne $sourceInstallerSize) {
        throw "Staged installer failed post-copy digest/size verification."
      }
      Move-Item -LiteralPath $stagedDest -Destination $dest -Force
    } finally {
      if (Test-Path -LiteralPath $stagedDest) {
        Remove-Item -LiteralPath $stagedDest -Force -ErrorAction SilentlyContinue
      }
    }
  }
  $destHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $dest).Hash.ToLowerInvariant()
  $destSize = (Get-Item -LiteralPath $dest).Length
  if ($destHash -ne $sourceInstallerHash -or $destSize -ne $sourceInstallerSize) {
    throw "Published installer staging failed digest/size verification."
  }

  if ($AssembleUpdaterAssets.IsPresent) {
    $sourceSignature = "$SetupExePath.sig"
    if (-not (Test-Path -LiteralPath $sourceSignature -PathType Leaf)) {
      throw "Tauri updater signature not found beside NSIS installer: $sourceSignature"
    }
    $destSignature = "$dest.sig"
    if (-not [string]::Equals($sourceSignature, $destSignature, [System.StringComparison]::OrdinalIgnoreCase)) {
      $signatureBytes = [System.IO.File]::ReadAllBytes($sourceSignature)
      [System.IO.File]::WriteAllBytes($destSignature, $signatureBytes)
      $stagedSignatureBytes = [System.IO.File]::ReadAllBytes($destSignature)
      if (
        $signatureBytes.Length -ne $stagedSignatureBytes.Length -or
        (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceSignature).Hash -ne
          (Get-FileHash -Algorithm SHA256 -LiteralPath $destSignature).Hash
      ) {
        throw "Published updater signature failed byte-for-byte staging verification."
      }
    }
  }
  $installerSizeBytes = (Get-Item -LiteralPath $dest).Length
  $installerSizeMB = [math]::Round($installerSizeBytes / 1MB, 1)
  Write-Host "Installer ready: $dest"
  Write-Host ("Installer size: {0} MB ({1:N0} bytes)" -f $installerSizeMB, $installerSizeBytes)
  $installerDest = $dest

  # Convenience: keep the Windows Sandbox share staged with the newest installer so the existing .wsb
  # (which maps tmp/offline-installer-sandbox/share) always sees a fresh setup exe without requiring
  # re-running the staging/launcher script.
  Measure-Phase "sandbox_stage" {
    $stagingSuccess = $true
    try {
      $sandboxShare = Join-Path -Path $repoRoot -ChildPath "tmp\\offline-installer-sandbox\\share"
      New-Item -ItemType Directory -Force -Path $sandboxShare | Out-Null

      # IMPORTANT: The mapped share can keep file handles open while Sandbox is running, which makes
      # overwriting a stable alias fail ("file is being used by another process"). To make staging
      # robust, always write a unique alias and then best-effort update the stable alias.
      $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
      $uniqueAliasFile = "JustSearch-LATEST-setup-$stamp.exe"
      $uniqueAlias = Join-Path -Path $sandboxShare -ChildPath $uniqueAliasFile
      Copy-Item -LiteralPath $dest -Destination $uniqueAlias -Force

      $stableAliasFile = "JustSearch-LATEST-setup.exe"
      $stableAlias = Join-Path -Path $sandboxShare -ChildPath $stableAliasFile
      try {
        Copy-Item -LiteralPath $dest -Destination $stableAlias -Force
      } catch {
        $stagingSuccess = $false
        Write-Warning "Failed to update stable Sandbox alias (non-fatal): $($_.Exception.Message)"
      }

      # Write a tiny breadcrumb so it's obvious which build was staged.
      $meta = @(
        "source=$dest",
        "aliasFile=$uniqueAliasFile",
        "stableAliasFile=$stableAliasFile",
        "alias=$uniqueAlias",
        "stableAlias=$stableAlias",
        "stagedAt=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
      )
      $meta | Set-Content -LiteralPath (Join-Path -Path $sandboxShare -ChildPath "latest-installer.txt") -Encoding UTF8

      # Keep the Sandbox logon script in sync with repo changes so it can pick up the newest staged installer.
      try {
        $startScript = Join-Path -Path $repoRoot -ChildPath "scripts\\sandbox\\offline-installer-sandbox-start.ps1"
        $stepsFile = Join-Path -Path $repoRoot -ChildPath "scripts\\sandbox\\offline-installer-sandbox-steps.txt"
        if (Test-Path -LiteralPath $startScript) {
          Copy-Item -LiteralPath $startScript -Destination (Join-Path -Path $sandboxShare -ChildPath "offline-installer-sandbox-start.ps1") -Force
        }
        if (Test-Path -LiteralPath $stepsFile) {
          Copy-Item -LiteralPath $stepsFile -Destination (Join-Path -Path $sandboxShare -ChildPath "offline-installer-sandbox-steps.txt") -Force
        }
      } catch {
        $stagingSuccess = $false
        Write-Warning "Failed to stage Sandbox helper scripts (non-fatal): $($_.Exception.Message)"
      }

      Write-Host "Sandbox share staged: $uniqueAlias"
    } catch {
      $stagingSuccess = $false
      Write-Warning "Failed to stage Sandbox share (non-fatal): $($_.Exception.Message)"
    }
    if (-not $stagingSuccess) {
      Write-Host "  (staging had partial errors)" -ForegroundColor Yellow
    }
  }

  # Assemble the full release asset set (installer + SHA256SUMS + MCPB bundle) into OutDir.
  # Opt-in (-AssembleReleaseAssets) so dev/smoke builds are unaffected. Fails closed on any
  # MCPB hash drift. -Release also verifies server.json version/URL match the cut version. (726)
  if ($AssembleReleaseAssets.IsPresent) {
    Measure-Phase "release_assets" {
      $assetArgs = @("-OutDir", $outDirPath)
      if ($Release.IsPresent -or $VerifyReleaseVersion.IsPresent) { $assetArgs += "-VerifyReleaseVersion" }
      if ($AssembleUpdaterAssets.IsPresent) {
        if ([string]::IsNullOrWhiteSpace($InstallerUrl)) {
          throw "-AssembleUpdaterAssets requires -InstallerUrl (use {filename} as an optional placeholder)."
        }
        $resolvedInstallerUrl = $InstallerUrl.Replace(
          "{filename}",
          [Uri]::EscapeDataString([System.IO.Path]::GetFileName($dest)))
        $assetArgs += @(
          "-AssembleUpdaterAssets",
          "-ReleaseSequence", $ReleaseSequence,
          "-CompatibilityBaselinePath", $CompatibilityBaselinePath,
          "-InstallerUrl", $resolvedInstallerUrl,
          "-ArtifactKeyId", $ArtifactKeyId,
          "-ArtifactPublicKey", $ArtifactPublicKey,
          "-MetadataKeyId", $MetadataKeyId,
          "-MetadataPrivateKeyPath", $MetadataPrivateKeyPath,
          "-MetadataPublicKeyPath", $MetadataPublicKeyPath)
      }
      & powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\ci\build-release-assets.ps1 @assetArgs
      if ($LASTEXITCODE -ne 0) { throw "build-release-assets.ps1 failed (exit=$LASTEXITCODE)" }
    }
  } else {
    Skip-Phase "release_assets" "AssembleReleaseAssets flag"
  }
  } finally {
    Pop-Location
  }
} catch {
  $mainError = $_
  throw
} finally {
  # Print timing summary BEFORE stopping transcript so it's captured in evidence
  $totalSw.Stop()
  $phaseTimings['total'] = $totalSw.ElapsedMilliseconds

  Write-Host "`n=== Build Timing Summary ===" -ForegroundColor Cyan
  foreach ($phase in $phaseTimings.Keys) {
    $entry = $phaseTimings[$phase]
    $label = $phase.PadRight(20)
    if ($null -eq $entry) {
      Write-Host ("  " + $label + "[SKIPPED]") -ForegroundColor Yellow
    } elseif ($entry -is [hashtable]) {
      $timeStr = Format-ElapsedMs $entry.ms
      if ($entry.failed) {
        Write-Host ("  " + $label + "[FAILED after $timeStr]") -ForegroundColor Red
      } else {
        Write-Host ("  " + $label + $timeStr)
      }
    } else {
      # Plain milliseconds (e.g., total)
      Write-Host ("  " + $label + (Format-ElapsedMs $entry))
    }
  }

  if ($evidenceEnabled) {
    if ($transcriptStarted) {
      try { Stop-Transcript | Out-Null } catch { }
    }

    # Best-effort: write a small metadata JSON about the produced installer (do NOT copy the installer into EBv1).
    try {
      if ($installerDest -and (Test-Path -LiteralPath $installerDest)) {
        $meta = [ordered]@{
          schema = "installer-artifact.v1"
          path = $installerDest
          filename = [System.IO.Path]::GetFileName($installerDest)
          bytes = (Get-Item -LiteralPath $installerDest).Length
          sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $installerDest).Hash.ToLowerInvariant()
          generatedAt = (Get-Date).ToString("o")
          release = [bool]$Release.IsPresent
        }
        $meta | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $installerMetaPath -Encoding UTF8
      }
    } catch {
      Write-Warning "Failed to write installer metadata (non-fatal): $($_.Exception.Message)"
    }

    # Capture EBv1 on PASS/FAIL (no HTTP API here; use api-base-url=none).
    # Guard: capture-evidence-bundle.mjs was removed in a frontend refactor
    # but this code path still references it. Skip gracefully when absent
    # (non-fatal) so the installer is delivered regardless.
    try {
      $captureScript = Join-Path -Path $repoRoot -ChildPath "modules\\ui-web\\scripts\\capture-evidence-bundle.mjs"
      $captureScriptPresent = Test-Path -LiteralPath $captureScript
      if (-not $captureScriptPresent) {
        Write-Warning "Evidence capture script not found; skipping EBv1 capture (non-fatal): $captureScript"
      }
      $captureArgs = @(
        "--scenario=package-installer-win",
        "--api-base-url=none",
        "--out-root=$EvidenceOutRoot",
        "--trace=false",
        "--attach-label=package_installer"
      )

      foreach ($p in @($transcriptPath, $installerMetaPath)) {
        if ($p -and (Test-Path -LiteralPath $p)) {
          $captureArgs += "--attach-file=$p"
        }
      }

      if ($mainError) {
        $captureArgs += "--external-status=failed"
        $captureArgs += ("--external-error=package-installer-win failed: " + $mainError.Exception.Message)
      } else {
        $captureArgs += "--external-status=passed"
      }

      $bundleDirRaw = $null
      $capExit = 0
      if ($captureScriptPresent) {
        Push-Location $repoRoot
        try {
          $bundleDirRaw = & node $captureScript @captureArgs
          $capExit = $LASTEXITCODE
        } finally {
          Pop-Location
        }
      }
      # Defensive: $bundleDirRaw can be $null if node exits on error (e.g.,
      # module not found). Under strict mode, .Trim() on $null throws and
      # masks the underlying failure.
      $bundleDir = if ($null -eq $bundleDirRaw) { "" } else { ([string]$bundleDirRaw).Trim() }

      if ($captureScriptPresent -and [string]::IsNullOrWhiteSpace($bundleDir)) {
        $msg = "Evidence capture produced no bundle path (exit=$capExit)."
        if ($mainError) { Write-Warning $msg } else { throw $msg }
      }

      # NOTE: capture-evidence-bundle may exit non-zero when external-status=failed; that's expected.
      if ($captureScriptPresent -and -not $mainError -and $capExit -ne 0) {
        throw "Evidence capture reported failed status (exit=$capExit). bundle=$bundleDir"
      }

      if ($ValidateEvidenceBundle.IsPresent -and -not [string]::IsNullOrWhiteSpace($bundleDir)) {
        Push-Location $repoRoot
        try {
          & node (Join-Path -Path $repoRoot -ChildPath "scripts\\evidence\\validate-evidencebundle-v1.mjs") $bundleDir
          if ($LASTEXITCODE -ne 0) { throw "validate-evidencebundle-v1 failed (exit=$LASTEXITCODE)" }

          & node (Join-Path -Path $repoRoot -ChildPath "scripts\\evidence\\validate-determinism-budget-v1.mjs") $bundleDir | Out-Null
        } finally {
          Pop-Location
        }
      }

      if (-not [string]::IsNullOrWhiteSpace($bundleDir)) {
        Write-Host ("EvidenceBundle: " + $bundleDir)
      }
    } catch {
      # Do not override the primary error if the main run already failed.
      if ($mainError) {
        Write-Warning ("Evidence capture/validation failed (non-fatal): " + $_.Exception.Message)
      } else {
        throw
      }
    }
  }
}


