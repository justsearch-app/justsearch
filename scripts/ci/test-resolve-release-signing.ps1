#Requires -Version 5.1
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$subject = Join-Path $PSScriptRoot "resolve-release-signing.ps1"

function Resolve-Case([string]$Ref, [string]$Version, [bool]$Requested, [bool]$Configured, [bool]$Rehearsal, [string]$Remaining, [string]$Mode = "pfx") {
  $caseParams = @{ GitRef = $Ref; ProjectVersion = $Version; ProviderRemainingSignatures = $Remaining; Mode = $Mode }
  if ($Requested) { $caseParams.SignInput = $true }
  if ($Configured) {
    switch ($Mode) {
      "pfx" { $caseParams.PfxBase64 = "fixture"; $caseParams.PfxPassword = "fixture"; $caseParams.TimestampUrl = "https://timestamp.invalid" }
      "store" { $caseParams.Thumbprint = "fixture" }
      "command" { $caseParams.CommandTemplate = "fixture {file}" }
    }
  }
  if ($Rehearsal) { $caseParams.AllowUntrusted = $true }
  return (& $subject @caseParams | ConvertFrom-Json)
}

$unsigned = Resolve-Case 'refs/heads/main' '' $false $false $false ''
if ($unsigned.packageSwitch -ne 'None') { throw "unsigned branch did not remain unsigned" }

$signedBranch = Resolve-Case 'refs/heads/rehearsal' '' $true $true $false '12'
if ($signedBranch.packageSwitch -ne 'Sign') { throw "signed branch did not select -Sign" }

$selfSigned = Resolve-Case 'refs/heads/rehearsal' '' $true $true $true ''
if ($selfSigned.packageSwitch -ne 'Sign' -or -not $selfSigned.rehearsal) { throw "self-signed rehearsal was rejected" }

foreach ($tag in @('refs/tags/v0.2.1', 'refs/tags/v0.3.0-rc.1')) {
  $release = Resolve-Case $tag $tag.Substring('refs/tags/v'.Length) $false $true $false ''
  if ($release.packageSwitch -ne 'Release') { throw "$tag did not select -Release" }
}

$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
  $missingCredential = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $subject `
    -GitRef refs/tags/v0.2.1 -ProjectVersion 0.2.1 -ProviderRemainingSignatures 12 2>&1 | Out-String
  $missingCredentialExit = $LASTEXITCODE
  $lowBudget = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $subject `
    -GitRef refs/tags/v0.2.1 -ProjectVersion 0.2.1 -PfxBase64 fixture -PfxPassword fixture -TimestampUrl https://timestamp.invalid -ProviderRemainingSignatures 11 2>&1 | Out-String
  $lowBudgetExit = $LASTEXITCODE
  $releaseRehearsal = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $subject `
    -GitRef refs/tags/v0.2.1 -ProjectVersion 0.2.1 -PfxBase64 fixture -PfxPassword fixture -TimestampUrl https://timestamp.invalid -AllowUntrusted 2>&1 | Out-String
  $releaseRehearsalExit = $LASTEXITCODE
  $versionMismatch = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $subject `
    -GitRef refs/tags/v0.3.0 -ProjectVersion 0.2.1 -PfxBase64 fixture -PfxPassword fixture -TimestampUrl https://timestamp.invalid -ProviderRemainingSignatures 12 2>&1 | Out-String
  $versionMismatchExit = $LASTEXITCODE
  $wrongModeCredential = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $subject `
    -GitRef refs/heads/rehearsal -SignInput -Mode command -PfxBase64 stale -PfxPassword stale -TimestampUrl https://timestamp.invalid -ProviderRemainingSignatures 12 2>&1 | Out-String
  $wrongModeCredentialExit = $LASTEXITCODE
  $malformedCommand = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $subject `
    -GitRef refs/heads/rehearsal -SignInput -Mode command -CommandTemplate 'vendor-sign-without-placeholder' -ProviderRemainingSignatures 12 2>&1 | Out-String
  $malformedCommandExit = $LASTEXITCODE
  $untrustedCommand = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $subject `
    -GitRef refs/heads/rehearsal -SignInput -Mode command -CommandTemplate 'vendor-sign {file}' -AllowUntrusted 2>&1 | Out-String
  $untrustedCommandExit = $LASTEXITCODE
} finally {
  $ErrorActionPreference = $previous
}
if ($missingCredentialExit -eq 0 -or $missingCredential -notmatch 'missing its complete credential') { throw "missing credential did not fail closed" }
if ($lowBudgetExit -ne 0) { throw "release tag still required a provider-portal balance input" }
if ($releaseRehearsalExit -eq 0 -or $releaseRehearsal -notmatch 'rehearsal-only') { throw "release tag accepted rehearsal-only trust relaxation" }
if ($versionMismatchExit -eq 0 -or $versionMismatch -notmatch 'must exactly match') { throw "release tag accepted a different project version" }
if ($wrongModeCredentialExit -eq 0 -or $wrongModeCredential -notmatch "mode 'command'") { throw "command mode was admitted by stale PFX inputs" }
if ($malformedCommandExit -eq 0 -or $malformedCommand -notmatch '\{file\} placeholder') { throw "command mode admitted a malformed template" }
if ($untrustedCommandExit -eq 0 -or $untrustedCommand -notmatch 'Command signing mode cannot use JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED') { throw "command mode bypassed provider budget through rehearsal trust relaxation" }

Write-Host "test-resolve-release-signing: PASS"
