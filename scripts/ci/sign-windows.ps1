#Requires -Version 5.1
[CmdletBinding()]
param(
  # The file to sign. (Tauri will pass the binary path as %1.)
  [Parameter(Mandatory = $true, Position = 0)]
  [string]$BinaryPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# PSModulePath edition fix: when a pwsh (PowerShell 7) parent — a CI `shell: pwsh` step, or the
# Tauri bundler running under one — spawns this 5.1 script, the inherited PSModulePath points at
# PS7's Core-edition modules and Windows PowerShell can no longer load its OWN
# Microsoft.PowerShell.Security ("module could not be loaded" on Get-AuthenticodeSignature —
# tempdoc 760 rehearsal run 29913294778, caught verbatim by the trap log). Reset to the Windows
# PowerShell defaults so built-in cmdlets resolve regardless of who spawned us.
if ($PSVersionTable.PSEdition -eq "Desktop") {
  $env:PSModulePath = ($env:ProgramFiles + "\WindowsPowerShell\Modules;" + $env:SystemRoot + "\System32\WindowsPowerShell\v1.0\Modules")
}

function To-Bool([string]$Value) {
  if (-not $Value) { return $false }
  switch ($Value.Trim().ToLowerInvariant()) {
    "1" { return $true }
    "true" { return $true }
    "yes" { return $true }
    default { return $false }
  }
}

# Tee everything to a log file too: Tauri's bundler swallows this script's stdout/stderr on
# failure ("failed to run powershell.exe" with zero detail — tempdoc 760 rehearsal run
# 29910543640), so the log file is the only way a CI failure here is diagnosable. The workflow
# prints it in an if:failure() step.
$script:signLogPath = Join-Path $env:TEMP "justsearch-sign-windows.log"

# Extension-shim state (see the "NSIS extension shim" block below). Initialised here, ahead of
# the trap and of Fail/Skip-Or-Fail, because Set-StrictMode -Version Latest makes reading an
# unassigned variable a terminating error.
$script:originalBinary = $null
$script:extensionShim = $null
$script:ledgerMutex = $null
$script:ledgerLockHeld = $false
$script:ledgerPath = $null
$script:ledgerMax = 0
$script:attemptLedgerPath = $null
$script:attemptOrdinal = 0

# Uninstaller-signing receipt (round-16 F3 follow-up). makensis does NOT check the exit code of a
# `!uninstfinalize` command, so this script failing -- or never being invoked -- on the uninstaller
# is completely silent and the bundle just ships one unsigned PE. The extension-shim path (the
# uninstaller TEMP file is its only consumer) therefore drops a receipt AFTER verification, and
# package-installer-win.ps1's signature_verify phase asserts it exists: absence covers both a
# failed hook and a hook that never ran. Resolved against the repo root (scripts/ci -> repo root)
# because the bundler invokes this script from an arbitrary working directory.
$script:receiptPath = Join-Path -Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) -ChildPath "dist\uninstaller-signing-receipt.json"

function Write-SignLog([string]$Message) {
  try { Add-Content -LiteralPath $script:signLogPath -Value ("[" + (Get-Date).ToString("HH:mm:ss") + "] " + $Message) } catch { }
}

# Defined ahead of the trap so EVERY exit path -- including a terminating error that only the trap
# sees -- can drop a stray shim copy of the binary in TEMP.
function Remove-ExtensionShim {
  if ($script:extensionShim) {
    try { Remove-Item -LiteralPath $script:extensionShim -Force -ErrorAction SilentlyContinue } catch { }
  }
}

function Exit-SigningBudget {
  if ($script:ledgerMutex) {
    if ($script:ledgerLockHeld) {
      try { $script:ledgerMutex.ReleaseMutex() } catch { }
    }
    try { $script:ledgerMutex.Dispose() } catch { }
  }
  $script:ledgerMutex = $null
  $script:ledgerLockHeld = $false
}

# Exception messages, source excerpts and native output can contain credentials.
# Keep structural diagnostics only, including when the trap handles a launch failure.
trap {
  $safeError = "Signing terminated: " + $_.Exception.GetType().Name + " at line " + $_.InvocationInfo.ScriptLineNumber
  Write-SignLog $safeError
  Remove-ExtensionShim
  Exit-SigningBudget
  Write-Error $safeError -ErrorAction Continue
  exit 1
}

# Written ONLY on the fully-verified shim path: Assert-Signed passed and the signed bytes are back
# on the path the caller named. Never records anything credential-bearing (paths + timestamp only).
function Write-SigningReceipt {
  $receiptDir = Split-Path -Parent $script:receiptPath
  if (-not (Test-Path -LiteralPath $receiptDir)) {
    New-Item -ItemType Directory -Force -Path $receiptDir | Out-Null
  }
  $receipt = [pscustomobject]@{
    target      = $script:originalBinary
    shim        = $script:extensionShim
    signedAtUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    verified    = $true
  }
  [System.IO.File]::WriteAllText($script:receiptPath, ($receipt | ConvertTo-Json -Depth 3), (New-Object System.Text.UTF8Encoding($false)))
  Info ("Signing receipt written: " + $script:receiptPath)
}

function Fail([string]$Message) {
  Write-SignLog ("FAIL: " + $Message)
  Remove-ExtensionShim
  Exit-SigningBudget
  Write-Error $Message -ErrorAction Continue
  exit 1
}

# Runs a native command with stderr made NON-TERMINATING and output discarded. Under
# $ErrorActionPreference=Stop with a piped stderr (exactly how Tauri's bundler runs this script),
# a native process's first stderr line becomes a terminating NativeCommandError BEFORE our
# exit-code check runs — the script dies mid-call and the real error is swallowed (tempdoc 760
# rehearsal run 29911439832: tee log ends at "Signing (pfx):", no FAIL line ever written).
# Neither arguments nor output have a safe diagnostic contract: vendor tools can echo
# individual or transformed credentials. Withhold them before any file/console sink.
function Invoke-Native {
  param(
    [Parameter(Mandatory = $true)][string]$Exe,
    [string[]]$Arguments = @()
  )
  $prevEap = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  $exit = -1
  try {
    & $Exe @Arguments 2>&1 | Out-Null
    $exit = $LASTEXITCODE
  } catch {
    $exit = -1
  } finally {
    $ErrorActionPreference = $prevEap
  }
  Write-SignLog ("Native signing tool exit=" + $exit + " (output withheld)")
  return [pscustomobject]@{ ExitCode = $exit }
}

# Sign attempts hit the timestamp server once per file; 100+ sequential requests from one CI IP
# is a realistic rate-limit shape, so allow a few attempts with a pause before failing.
function Invoke-NativeWithRetry {
  param(
    [Parameter(Mandatory = $true)][string]$Exe,
    [string[]]$Arguments = @(),
    [int]$Attempts = 3,
    [int]$DelaySec = 3,
    [Parameter(Mandatory = $true)][string]$SigningTarget,
    [Parameter(Mandatory = $true)][string]$SignerMode
  )
  for ($i = 1; $i -le $Attempts; $i++) {
    Enter-SigningBudget -Target $SigningTarget -SignerMode $SignerMode
    $res = Invoke-Native -Exe $Exe -Arguments $Arguments
    Write-SigningAttemptOutcome -Outcome $(if ($res.ExitCode -eq 0) { "vendor-exit-zero" } else { "vendor-failed" }) -ExitCode $res.ExitCode
    if ($res.ExitCode -eq 0) { return $res }
    if ($i -lt $Attempts) {
      Write-SignLog ("attempt " + $i + "/" + $Attempts + " failed (exit=" + $res.ExitCode + "), retrying in " + $DelaySec + "s")
      Start-Sleep -Seconds $DelaySec
    }
  }
  return $res
}

function Info([string]$Message) {
  Write-SignLog $Message
  Write-Host $Message
}

# Command-mode signers are downloaded third-party executables. They must receive the credential
# material embedded in their rendered command line, but they have no reason to inherit release,
# updater, GitHub, or alternate-signer secrets from the packaging step. Keep the allowlisted
# operational environment (PATH, TEMP, JAVA_HOME, CODE_SIGN_TOOL_PATH, and similar ordinary
# variables) intact while removing the secrets JustSearch itself places in that step.
function Invoke-CommandSignerRestricted {
  param([Parameter(Mandatory = $true)][string]$BatchPath)

  $sensitiveNames = @(
    "JUSTSEARCH_CODESIGN_MODE",
    "JUSTSEARCH_CODESIGN_PFX_PATH",
    "JUSTSEARCH_CODESIGN_PFX_B64",
    "JUSTSEARCH_CODESIGN_PFX_PASSWORD",
    "JUSTSEARCH_CODESIGN_TIMESTAMP_URL",
    "JUSTSEARCH_CODESIGN_THUMBPRINT",
    "JUSTSEARCH_CODESIGN_STORE",
    "JUSTSEARCH_CODESIGN_COMMAND",
    "TAURI_SIGNING_PRIVATE_KEY",
    "TAURI_SIGNING_PRIVATE_KEY_PASSWORD",
    "JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PATH",
    "METADATA_PRIVATE_KEY_PEM",
    "GITHUB_TOKEN",
    "GH_TOKEN"
  )
  $saved = @{}
  try {
    foreach ($name in $sensitiveNames) {
      $saved[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
      [Environment]::SetEnvironmentVariable($name, $null, "Process")
    }
    return Invoke-Native -Exe $BatchPath
  } finally {
    foreach ($name in $sensitiveNames) {
      [Environment]::SetEnvironmentVariable($name, $saved[$name], "Process")
    }
  }
}

# Optional run-local spend guard. A named mutex is held from the first pre-invocation reservation
# until the verified ledger append (or failure), so concurrent signer children cannot both consume
# the final slot. The append-only attempt ledger is deliberately crash-conservative: every vendor
# invocation is reserved before it starts, while the separate verified ledger is written only after
# local signature verification. Neither ledger contains credential material.
function Enter-SigningBudget([string]$Target, [string]$SignerMode) {
  $configuredPath = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_LEDGER_PATH
  if (-not $configuredPath -or -not $configuredPath.Trim()) {
    if ($SignerMode -eq "command") {
      Fail "Command signing requires JUSTSEARCH_CODESIGN_LEDGER_PATH and a positive JUSTSEARCH_CODESIGN_MAX_SIGNATURES value."
    }
    return
  }

  $maxText = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_MAX_SIGNATURES
  $max = 0
  if (-not [int]::TryParse($maxText, [ref]$max) -or $max -lt 1) {
    Fail "JUSTSEARCH_CODESIGN_LEDGER_PATH requires a positive JUSTSEARCH_CODESIGN_MAX_SIGNATURES value."
  }

  if (-not $script:ledgerPath) {
    $path = [System.IO.Path]::GetFullPath($configuredPath)
    $parent = Split-Path -Parent $path
    if (-not (Test-Path -LiteralPath $parent)) {
      New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
      $pathHash = ([BitConverter]::ToString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($path)))).Replace("-", "").Substring(0, 32)
    } finally {
      $sha.Dispose()
    }
    $script:ledgerMutex = New-Object System.Threading.Mutex($false, ("Local\JustSearchCodeSignLedger-" + $pathHash))
    try {
      $script:ledgerLockHeld = $script:ledgerMutex.WaitOne([TimeSpan]::FromMinutes(2))
    } catch [System.Threading.AbandonedMutexException] {
      $script:ledgerLockHeld = $true
    }
    if (-not $script:ledgerLockHeld) {
      Fail "Timed out acquiring the signing-ledger lock for $path"
    }
    $script:ledgerPath = $path
    $script:attemptLedgerPath = $path + ".attempts.jsonl"
    $script:ledgerMax = $max
  }

  $count = 0
  if (Test-Path -LiteralPath $script:attemptLedgerPath) {
    $count = @(Get-Content -LiteralPath $script:attemptLedgerPath | Where-Object {
      if (-not $_ -or -not $_.Trim()) { return $false }
      try { return (($_ | ConvertFrom-Json).event -eq "attempt-start") } catch { Fail "Signing attempt ledger contains invalid JSON." }
    }).Count
  }
  if ($count -ge $max) {
    Fail "Signing ceiling reached before vendor invocation: ledger has $count reserved attempt(s), maximum is $max."
  }

  $script:attemptOrdinal = $count + 1
  $record = [ordered]@{
    schemaVersion = 1
    event = "attempt-start"
    attemptOrdinal = $script:attemptOrdinal
    target = [System.IO.Path]::GetFullPath($Target)
    signerMode = $SignerMode
    recordedAtUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
  }
  [System.IO.File]::AppendAllText(
    $script:attemptLedgerPath,
    (($record | ConvertTo-Json -Compress) + "`n"),
    (New-Object System.Text.UTF8Encoding($false)))
  Info ("Signing attempt reserved " + $script:attemptOrdinal + "/" + $max)
}

function Write-SigningAttemptOutcome([string]$Outcome, [int]$ExitCode) {
  if (-not $script:attemptLedgerPath -or $script:attemptOrdinal -lt 1) { return }
  if (-not $script:ledgerLockHeld) { Fail "Signing ledger lock was lost before attempt outcome append." }
  $record = [ordered]@{
    schemaVersion = 1
    event = "attempt-finish"
    attemptOrdinal = $script:attemptOrdinal
    outcome = $Outcome
    exitCode = $ExitCode
    recordedAtUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
  }
  [System.IO.File]::AppendAllText(
    $script:attemptLedgerPath,
    (($record | ConvertTo-Json -Compress) + "`n"),
    (New-Object System.Text.UTF8Encoding($false)))
}

function Write-SigningLedger([string]$Target, [string]$SignerMode) {
  if (-not $script:ledgerPath) { return }
  if (-not $script:ledgerLockHeld) { Fail "Signing ledger lock was lost before verified append." }

  $existing = 0
  if (Test-Path -LiteralPath $script:ledgerPath) {
    $existing = @(Get-Content -LiteralPath $script:ledgerPath | Where-Object { $_ -and $_.Trim() }).Count
  }
  $record = [ordered]@{
    schemaVersion = 1
    ordinal = $existing + 1
    target = [System.IO.Path]::GetFullPath($Target)
    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Target).Hash.ToUpperInvariant()
    signerMode = $SignerMode
    attemptOrdinal = $script:attemptOrdinal
    signedAtUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    verified = $true
  }
  [System.IO.File]::AppendAllText(
    $script:ledgerPath,
    (($record | ConvertTo-Json -Compress) + "`n"),
    (New-Object System.Text.UTF8Encoding($false)))
  Info ("Signing ledger appended: " + $record.ordinal + "/" + $script:ledgerMax + " " + $record.target)
  Exit-SigningBudget
}

# Credential mode selects how the key is presented (all additive; no env renames):
#   pfx     - PFX file/base64 + password + timestamp (default; exactly today's behavior).
#   store   - cert in the Windows cert store by thumbprint; how USB-token/HSM CSP-backed
#             nonexportable keys present locally (signtool /sha1 <thumbprint> /s <store>).
#   command - a full command-line template with a {file} placeholder, run per file. Lets any
#             vendor CLI (Azure Trusted Signing, eSigner, ...) plug in with no further changes.
$mode = $env:JUSTSEARCH_CODESIGN_MODE
if (-not $mode -or -not $mode.Trim()) { $mode = "pfx" } else { $mode = $mode.Trim().ToLowerInvariant() }

$requireSigning = To-Bool $env:JUSTSEARCH_REQUIRE_SIGNING
# Rehearsal relaxation: accept a hash-valid but chain-untrusted (e.g. self-signed) signature so
# the pipeline can be dry-run end-to-end without a production cert. Off => behavior unchanged.
$allowUntrusted = To-Bool $env:JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED

# pfx-mode inputs (also the back-compat default set).
# Trailing CR/LF stripping: secrets piped into `gh secret set` from files easily pick up a
# trailing newline (Set-Content/echo both append one), and signtool then fails with "The
# specified PFX password is not correct" while the bundler swallows this script's output —
# an opaque CI failure for a whitespace bug (empirically hit, tempdoc 760 rehearsal run
# 29910543640). A trailing newline is never a legitimate part of any of these values; inner
# and leading characters are preserved (passwords may contain spaces).
function Strip-TrailingNewlines([string]$Value) {
  if ($null -eq $Value) { return $Value }
  return $Value.TrimEnd("`r", "`n")
}

$pfxPath = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_PFX_PATH
$pfxB64 = $env:JUSTSEARCH_CODESIGN_PFX_B64
$pfxPassword = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_PFX_PASSWORD
$timestampUrl = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_TIMESTAMP_URL

# store-mode inputs.
$thumbprint = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_THUMBPRINT
$certStore = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_STORE
if (-not $certStore -or -not $certStore.Trim()) { $certStore = "My" }

# command-mode input.
$commandTemplate = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_COMMAND

# Command mode is the vendor-spend boundary. Enforce its trust and budget invariants here rather
# than relying on a workflow-only resolver: Tauri and direct callers reach this script too.
if ($mode -eq "command") {
  if ($allowUntrusted) {
    Fail "Command signing cannot use JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED; vendor signing requires trusted verification."
  }
  $commandLedgerPath = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_LEDGER_PATH
  $commandMaximumText = Strip-TrailingNewlines $env:JUSTSEARCH_CODESIGN_MAX_SIGNATURES
  $commandMaximum = 0
  if (-not $commandLedgerPath -or -not $commandLedgerPath.Trim() -or
      -not [int]::TryParse($commandMaximumText, [ref]$commandMaximum) -or $commandMaximum -lt 1) {
    Fail "Command signing requires JUSTSEARCH_CODESIGN_LEDGER_PATH and a positive JUSTSEARCH_CODESIGN_MAX_SIGNATURES value."
  }
}

if (-not $BinaryPath) {
  Fail "BinaryPath is required"
}

$resolvedBinary = if ([System.IO.Path]::IsPathRooted($BinaryPath)) { $BinaryPath } else { (Resolve-Path -LiteralPath $BinaryPath).Path }
if (-not (Test-Path -LiteralPath $resolvedBinary)) {
  Fail "Binary not found: $resolvedBinary"
}

# NSIS extension shim (round-16 F3). Tauri routes uninstaller signing through NSIS's
# `!uninstfinalize`, which hands the freshly compiled uninstaller over as a TEMP file named
# `...\nstXXXX.tmp`. SSL.com's CodeSignTool dispatches on the file EXTENSION and refuses it:
# "Error: Unsupported file format for signing - tmp" (CI run 31606590694, tauri_build phase).
# makensis does not check a finalize command's exit code, so that failure was invisible and the
# bundle shipped exactly one unsigned PE -- the uninstaller. Sign an `.exe`-named copy instead,
# then write the signed bytes back over the path NSIS embeds.
$signableExtensions = @(".exe", ".dll", ".sys", ".ocx", ".msi", ".msix", ".appx", ".cab", ".cat", ".ps1")
$binaryExtension = [System.IO.Path]::GetExtension($resolvedBinary).ToLowerInvariant()
if ($signableExtensions -notcontains $binaryExtension) {
  $script:originalBinary = $resolvedBinary
  $script:extensionShim = Join-Path -Path $env:TEMP -ChildPath ("justsearch-codesign-shim-" + [guid]::NewGuid().ToString("N") + ".exe")
  Copy-Item -LiteralPath $resolvedBinary -Destination $script:extensionShim -Force
  Info ("Extension shim ('" + $binaryExtension + "' is not signable): " + $resolvedBinary + " -> " + $script:extensionShim)
  $resolvedBinary = $script:extensionShim
}

function Find-SignTool {
  $cmd = Get-Command "signtool.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($cmd -and $cmd.Path) { return $cmd.Path }

  $roots = @()
  if ($env:ProgramFiles -and $env:ProgramFiles.Trim()) {
    $roots += (Join-Path $env:ProgramFiles "Windows Kits\\10\\bin")
    $roots += (Join-Path $env:ProgramFiles "Windows Kits\\11\\bin")
  }
  $pf86 = [Environment]::GetEnvironmentVariable("ProgramFiles(x86)")
  if ($pf86 -and $pf86.Trim()) {
    $roots += (Join-Path $pf86 "Windows Kits\\10\\bin")
    $roots += (Join-Path $pf86 "Windows Kits\\11\\bin")
  }

  foreach ($root in ($roots | Select-Object -Unique)) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    $versions = Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
    foreach ($v in $versions) {
      $candidate = Join-Path $v.FullName "x64\\signtool.exe"
      if (Test-Path -LiteralPath $candidate) { return $candidate }
      $candidate2 = Join-Path $v.FullName "signtool.exe"
      if (Test-Path -LiteralPath $candidate2) { return $candidate2 }
    }
  }
  return $null
}

# Not-configured => skip (exit 0) unless JUSTSEARCH_REQUIRE_SIGNING, in which case fail-closed.
function Skip-Or-Fail([string]$SkipMessage) {
  if ($requireSigning) {
    Fail ("Signing is required but inputs are missing. " + $SkipMessage)
  }
  Info $SkipMessage
  Remove-ExtensionShim
  exit 0
}

# Post-sign verification. Default is today's strict `signtool verify /pa` hard-check. With
# JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED a present-and-hash-valid but chain-untrusted signature
# is accepted (SignerCertificate present AND status != NotSigned); NotSigned still fails.
function Assert-Signed([string]$Path, [string]$SigntoolPath) {
  Write-SignLog ("Assert-Signed enter (allowUntrusted=" + $allowUntrusted + "): " + $Path)
  if ($allowUntrusted) {
    # Freshly-signed bundler outputs can be transiently locked/settling (CI run 29912444815 died
    # exactly here with nothing logged: signtool exit=0, then silent death before any Info).
    # Read the signature defensively: catch + retry a few times, never die without a log line.
    $sig = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
      try {
        $sig = Get-AuthenticodeSignature -FilePath $Path -ErrorAction Stop
        break
      } catch {
        Write-SignLog ("Get-AuthenticodeSignature attempt " + $attempt + "/3 failed: " + $_.Exception.GetType().Name)
        if ($attempt -lt 3) { Start-Sleep -Milliseconds 500 }
      }
    }
    if ($null -eq $sig) {
      Fail ("Get-AuthenticodeSignature failed 3x for " + $Path + " (see sign log)")
    }
    if ($sig.Status -eq "Valid") {
      Info "Signed OK: $Path"
      return
    }
    if ($sig.SignerCertificate -and ([string]$sig.Status -ne "NotSigned")) {
      Info ("Signed (chain untrusted, status=" + $sig.Status + ") - accepted under JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED: " + $Path)
      return
    }
    Fail ("Authenticode signature missing after signing (status=" + $sig.Status + ") for " + $Path)
  }

  if (-not $SigntoolPath) {
    Fail "signtool.exe not found; cannot verify signature for $Path"
  }
  $verifyRes = Invoke-Native -Exe $SigntoolPath -Arguments @("verify", "/pa", "/v", $Path)
  if ($verifyRes.ExitCode -ne 0) {
    Fail ("signtool verify failed (exit=" + $verifyRes.ExitCode + ") for " + $Path)
  }
  Info "Signed OK: $Path"
}

switch ($mode) {
  "pfx" {
    $hasPfx = $false
    $resolvedPfxPath = $null
    if ($pfxPath -and $pfxPath.Trim()) {
      $resolvedPfxPath = if ([System.IO.Path]::IsPathRooted($pfxPath)) { $pfxPath } else { (Resolve-Path -LiteralPath $pfxPath).Path }
      if (-not (Test-Path -LiteralPath $resolvedPfxPath)) {
        Fail "JUSTSEARCH_CODESIGN_PFX_PATH points to a missing file: $resolvedPfxPath"
      }
      $hasPfx = $true
    } elseif ($pfxB64 -and $pfxB64.Trim()) {
      $hasPfx = $true
    }

    if (-not $hasPfx -or -not $pfxPassword -or -not $timestampUrl) {
      Skip-Or-Fail "Signing skipped for '$resolvedBinary' (missing JUSTSEARCH_CODESIGN_PFX_PATH or JUSTSEARCH_CODESIGN_PFX_B64 / JUSTSEARCH_CODESIGN_PFX_PASSWORD / JUSTSEARCH_CODESIGN_TIMESTAMP_URL)."
    }

    # Locate signtool (Windows SDK). Prefer PATH but also search Windows Kits default locations.
    $signtoolPath = Find-SignTool
    if (-not $signtoolPath) {
      Skip-Or-Fail "Signing skipped for '$resolvedBinary' (signtool.exe not found on PATH. Install the Windows SDK (SignTool) or add it to PATH.)"
    }

    $tmpPfx = Join-Path -Path $env:TEMP -ChildPath ("justsearch-codesign-" + [guid]::NewGuid().ToString("N") + ".pfx")
    try {
      $pfxToUse = $tmpPfx
      if ($resolvedPfxPath) {
        $pfxToUse = $resolvedPfxPath
      } else {
        [byte[]]$bytes = [Convert]::FromBase64String($pfxB64)
        [System.IO.File]::WriteAllBytes($tmpPfx, $bytes)
      }

      Info "Signing (pfx): $resolvedBinary"
      $signingTarget = if ($script:originalBinary) { $script:originalBinary } else { $resolvedBinary }
      $signRes = Invoke-NativeWithRetry -Exe $signtoolPath -SigningTarget $signingTarget -SignerMode $mode -Arguments @(
        "sign", "/fd", "SHA256", "/td", "SHA256", "/tr", $timestampUrl,
        "/f", $pfxToUse, "/p", $pfxPassword, $resolvedBinary)
      if ($signRes.ExitCode -ne 0) {
        Fail ("signtool sign failed (exit=" + $signRes.ExitCode + ") for " + $resolvedBinary)
      }

      Assert-Signed $resolvedBinary $signtoolPath
    } finally {
      # Only delete temp PFX when we created it from base64.
      if (-not $resolvedPfxPath) {
        try { Remove-Item -LiteralPath $tmpPfx -Force -ErrorAction SilentlyContinue } catch { }
      }
    }
  }

  "store" {
    if (-not $thumbprint -or -not $thumbprint.Trim()) {
      Skip-Or-Fail "Signing skipped for '$resolvedBinary' (missing JUSTSEARCH_CODESIGN_THUMBPRINT for store mode)."
    }
    $thumb = $thumbprint.Trim()

    $signtoolPath = Find-SignTool
    if (-not $signtoolPath) {
      Skip-Or-Fail "Signing skipped for '$resolvedBinary' (signtool.exe not found on PATH. Install the Windows SDK (SignTool) or add it to PATH.)"
    }

    # /sha1 <thumbprint> /s <store> selects the store cert (CSP/HSM keys stay nonexportable).
    # Timestamp is optional here; include /tr only when a URL is configured.
    $signArgs = @("sign", "/sha1", $thumb, "/s", $certStore, "/fd", "SHA256")
    if ($timestampUrl -and $timestampUrl.Trim()) {
      $signArgs += @("/td", "SHA256", "/tr", $timestampUrl.Trim())
    }
    $signArgs += $resolvedBinary

    Info ("Signing (store '" + $certStore + "', thumbprint " + $thumb + "): " + $resolvedBinary)
    $signingTarget = if ($script:originalBinary) { $script:originalBinary } else { $resolvedBinary }
    $signRes = Invoke-NativeWithRetry -Exe $signtoolPath -SigningTarget $signingTarget -SignerMode $mode -Arguments $signArgs
    if ($signRes.ExitCode -ne 0) {
      Fail ("signtool sign failed (exit=" + $signRes.ExitCode + ") for " + $resolvedBinary)
    }

    Assert-Signed $resolvedBinary $signtoolPath
  }

  "command" {
    if (-not $commandTemplate -or -not $commandTemplate.Trim()) {
      Skip-Or-Fail "Signing skipped for '$resolvedBinary' (missing JUSTSEARCH_CODESIGN_COMMAND for command mode)."
    }
    if ($commandTemplate -notmatch '\{file\}') {
      Fail "JUSTSEARCH_CODESIGN_COMMAND must contain a {file} placeholder."
    }

    $signingTarget = if ($script:originalBinary) { $script:originalBinary } else { $resolvedBinary }
    Enter-SigningBudget -Target $signingTarget -SignerMode $mode

    $rendered = $commandTemplate.Replace("{file}", $resolvedBinary)
    # Run the rendered command line via a temp .cmd to avoid PowerShell/cmd quoting hazards with
    # paths that contain spaces. `& $batch` propagates the vendor CLI's exit code to $LASTEXITCODE.
    $batch = Join-Path -Path $env:TEMP -ChildPath ("justsearch-codesign-cmd-" + [guid]::NewGuid().ToString("N") + ".cmd")
    try {
      [System.IO.File]::WriteAllText($batch, ("@echo off`r`n" + $rendered + "`r`n"), [System.Text.Encoding]::ASCII)
      # NEVER log the rendered command: the template embeds vendor credentials, and once {file}
      # is substituted the line no longer exactly matches the stored secret string, so CI
      # secret-masking cannot redact it (credential leak observed in the on-failure log dump of
      # run 31603929359). Even a template's first token is not guaranteed safe.
      Info ("Signing (command): " + $resolvedBinary)
      $cmdRes = Invoke-CommandSignerRestricted -BatchPath $batch
      $cmdExit = $cmdRes.ExitCode
      Write-SigningAttemptOutcome -Outcome $(if ($cmdExit -eq 0) { "vendor-exit-zero" } else { "vendor-failed" }) -ExitCode $cmdExit
    } finally {
      try { Remove-Item -LiteralPath $batch -Force -ErrorAction SilentlyContinue } catch { }
    }
    if ($cmdExit -ne 0) {
      Fail ("Signing command failed (exit=" + $cmdExit + ") for " + $resolvedBinary)
    }

    # A vendor CLI may or may not ship signtool; locate it for the strict verify path (the
    # ALLOW_UNTRUSTED path uses Get-AuthenticodeSignature and needs no signtool).
    Assert-Signed $resolvedBinary (Find-SignTool)
  }

  default {
    Fail "Unknown JUSTSEARCH_CODESIGN_MODE '$mode' (expected pfx | store | command)."
  }
}

# Only reached when the signature above verified: every failure path exits through Fail. Put the
# signed bytes back on the path the caller named -- for `!uninstfinalize` that is the file makensis
# compresses into the installer, so this write is what makes the shipped uninstaller signed.
if ($script:extensionShim) {
  [System.IO.File]::Copy($script:extensionShim, $script:originalBinary, $true)
  Remove-ExtensionShim
  Info ("Signed shim written back to " + $script:originalBinary)
  Write-SigningReceipt
}

$ledgerTarget = if ($script:originalBinary) { $script:originalBinary } else { $resolvedBinary }
Write-SigningLedger -Target $ledgerTarget -SignerMode $mode
