#Requires -Version 5.1
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$subjects = @(
  @{ Path = "scripts/ci/build-release-assets.ps1"; Flags = @("-AssembleUpdaterAssets") },
  @{ Path = "scripts/ci/package-installer-win.ps1";
     Flags = @("-AssembleUpdaterAssets", "-AssembleReleaseAssets", "-NoEvidence") }
)
$missing = Join-Path ([System.IO.Path]::GetTempPath()) ("missing-baseline-" + [guid]::NewGuid() + ".json")
$checks = 0
function Assert-RefusedBaseline($subject, $flags, $pattern) {
  $previous = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $script = Join-Path $repoRoot $subject.Path
    $output = & powershell.exe -NoProfile -File $script @flags 2>&1 | Out-String
    $exit = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previous
  }
  if ($exit -ne 1 -or $output -notmatch $pattern) {
    throw "$($subject.Path) did not reject predecessor evidence before staging/signing"
  }
}
foreach ($subject in $subjects) {
  $script = Join-Path $repoRoot $subject.Path
  $tokens = $null
  $parseErrors = $null
  $null = [System.Management.Automation.Language.Parser]::ParseFile(
    $script, [ref]$tokens, [ref]$parseErrors)
  if ($parseErrors.Count -ne 0) { throw "PowerShell parser rejected $($subject.Path)" }
  $checks++
  foreach ($flags in @($subject.Flags, ($subject.Flags + @("-CompatibilityBaselinePath", $missing)))) {
    Assert-RefusedBaseline $subject $flags 'requires an existing predecessor -CompatibilityBaselinePath'
    $checks++
  }
}
$tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$fixtureRoot = [System.IO.Path]::GetFullPath((Join-Path $tempRoot ("compatibility-baseline-test-" + [guid]::NewGuid())))
if (-not $fixtureRoot.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "Baseline test fixture escaped its temporary root"
}
$null = New-Item -ItemType Directory -Path $fixtureRoot
try {
  $malformed = Join-Path $fixtureRoot "malformed.json"
  [System.IO.File]::WriteAllText($malformed, '{not json', [System.Text.UTF8Encoding]::new($false))
  $register = Get-Content -LiteralPath (Join-Path $repoRoot "governance/store-recoverability.v1.json") -Raw | ConvertFrom-Json
  $store = $register.durableStores[0]
  $store.owner = "MISMATCH"
  $inconsistent = Join-Path $fixtureRoot "inconsistent.json"
  $baselineJson = @{ durableStores = @($store) } | ConvertTo-Json -Depth 12
  [System.IO.File]::WriteAllText($inconsistent, $baselineJson, [System.Text.UTF8Encoding]::new($false))
  foreach ($subject in $subjects) {
    Assert-RefusedBaseline $subject ($subject.Flags + @("-CompatibilityBaselinePath", $malformed)) 'Predecessor compatibility preflight failed'
    $checks++
    Assert-RefusedBaseline $subject ($subject.Flags + @("-CompatibilityBaselinePath", $inconsistent)) 'compatibility baseline store .* changes identity'
    $checks++
  }
} finally {
  $resolved = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $fixtureRoot).Path)
  if ($resolved -ne $fixtureRoot -or -not $resolved.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to remove an unexpected baseline test fixture"
  }
  Remove-Item -LiteralPath $resolved -Recurse -Force
}
Write-Host "test-release-compatibility-baseline: PASS ($checks checks)"
