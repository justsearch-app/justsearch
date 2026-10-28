#Requires -Version 5.1
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$extra = @{}
$projectVersionLine = Get-Content -LiteralPath gradle.properties | Where-Object { $_ -match '^version=' }
if (@($projectVersionLine).Count -ne 1) { throw "gradle.properties must contain exactly one version= line." }
$projectVersion = ($projectVersionLine -split '=', 2)[1].Trim()
$allowUntrustedRaw = if ($env:JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED) { $env:JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED.Trim().ToLowerInvariant() } else { '' }
$decisionJson = & ./scripts/ci/resolve-release-signing.ps1 `
  -GitRef $env:GITHUB_REF `
  -ProjectVersion $projectVersion `
  -Mode $env:JUSTSEARCH_CODESIGN_MODE `
  -SignInput:($env:SIGN_REQUESTED -eq '1') `
  -PfxBase64 $env:JUSTSEARCH_CODESIGN_PFX_B64 `
  -PfxPassword $env:JUSTSEARCH_CODESIGN_PFX_PASSWORD `
  -TimestampUrl $env:JUSTSEARCH_CODESIGN_TIMESTAMP_URL `
  -Thumbprint $env:JUSTSEARCH_CODESIGN_THUMBPRINT `
  -CommandTemplate $env:JUSTSEARCH_CODESIGN_COMMAND `
  -AllowUntrusted:($allowUntrustedRaw -in @('1', 'true', 'yes')) `
  -SignatureMaximum 12
$signingDecision = $decisionJson | ConvertFrom-Json
if ($signingDecision.packageSwitch -eq 'Release') { $extra['Release'] = $true }
elseif ($signingDecision.packageSwitch -eq 'Sign') { $extra['Sign'] = $true }
else { Write-Host "Signing not requested for this non-tag dispatch; building unsigned." }
$signRequested = [bool]$signingDecision.signingRequested
if (Test-Path -LiteralPath $env:JUSTSEARCH_CODESIGN_LEDGER_PATH) {
  Remove-Item -LiteralPath $env:JUSTSEARCH_CODESIGN_LEDGER_PATH -Force
}
if (Test-Path -LiteralPath ($env:JUSTSEARCH_CODESIGN_LEDGER_PATH + '.attempts.jsonl')) {
  Remove-Item -LiteralPath ($env:JUSTSEARCH_CODESIGN_LEDGER_PATH + '.attempts.jsonl') -Force
}
# Signed-mirror pins (packaging/signed-mirrors.v1.json; tempdoc 760/772 §K sign-once). When a
# vendored payload has a committed, COMPLETE signed-mirror entry, point the matching gradle
# download-override property pair at the once-signed re-hosted copy so the build fetches it
# instead of the default upstream pin. Pins live in the committed file (not repo variables) so
# each bump is PR-reviewed supply-chain history. An absent/empty entry sets nothing =>
# default pins => byte-identical build. A half-complete entry (url XOR sha256)
# fails loudly HERE, before the build, mirroring the all-or-nothing pin in build.gradle.kts.
$mirrorsPath = "packaging/signed-mirrors.v1.json"
if (Test-Path $mirrorsPath) {
  $mirrors = (Get-Content -Raw $mirrorsPath | ConvertFrom-Json).mirrors
  # payload key in the file -> the two ORG_GRADLE_PROJECT_* override properties it drives
  $overrides = @{
    'llama-cpu' = @('llamaPrebuiltUrlOverride', 'llamaPrebuiltSha256Override')
    'tesseract' = @('tesseractSourceUrlOverride', 'tesseractSourceSha256Override')
  }
  foreach ($payload in $overrides.Keys) {
    $entry = if ($mirrors) { $mirrors.$payload } else { $null }
    if ($null -eq $entry) { continue }
    $hasUrl = $entry.url -and $entry.url.Trim()
    $hasSha = $entry.sha256 -and $entry.sha256.Trim()
    if ($hasUrl -and $hasSha) {
      $props = $overrides[$payload]
      Set-Item "env:ORG_GRADLE_PROJECT_$($props[0])" $entry.url.Trim()
      Set-Item "env:ORG_GRADLE_PROJECT_$($props[1])" $entry.sha256.Trim()
      Write-Host "Signed mirror active for '$payload' -> $($entry.url.Trim())"
    } elseif ($hasUrl -or $hasSha) {
      throw "signed-mirrors.v1.json entry '$payload' is half-complete: both 'url' and 'sha256' are required. A mirror URL with the default hash can never verify; the default URL with a mirror hash rejects the genuine pinned artifact. Provide BOTH or remove the entry."
    }
  }
}
if ($env:RELEASE_BUILD -eq 'true') { $extra['SignUpdaterArtifact'] = $true }
./scripts/ci/package-installer-win.ps1 -OutDir dist/installer -SkipVerify -AssembleReleaseAssets @extra
