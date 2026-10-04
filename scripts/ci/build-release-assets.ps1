#Requires -Version 5.1
<#
.SYNOPSIS
  Assemble the complete, hash-consistent release asset set into -OutDir.

.DESCRIPTION
  Given an already-built installer in -OutDir (produced by package-installer-win.ps1
  or the CI build step), this stages the full release asset set:

    - the installer *-setup.exe (already present; left in place)
    - justsearch-mcp.mcpb  (built deterministically from source by pack-mcpb.mjs -- NOT
      committed; the gate verifies the build matches server.json.fileSha256; tempdoc 726)
    - SHA256SUMS           (sha256sum(1)-compatible manifest over both assets)

  Before staging the bundle it runs the fail-closed consistency gate
  (scripts/ci/check-mcpb-consistency.mjs): sha256(bundle) must equal
  server.json.fileSha256, or MCP clients fail-closed on install. With
  -VerifyReleaseVersion it also asserts server.json.version + asset URL match the
  gradle.properties version being cut.

  The workflow then attaches dist/installer/* to the GitHub Release.

.NOTES
  Fails closed on any drift. Idempotent. Tempdoc 726.
#>
[CmdletBinding()]
param(
  # Directory holding the built installer; also where SHA256SUMS + the bundle are staged.
  [string]$OutDir = "dist/installer",

  # When set, also assert server.json.version + asset URL match the gradle.properties version.
  # Use for a real release cut (after gradle.properties has been bumped); off for smoke builds.
  [switch]$VerifyReleaseVersion,

  # Opt in to the authenticated updater asset set. Existing release/smoke callers
  # continue to stage only the installer, MCPB, and SHA256SUMS.
  [switch]$AssembleUpdaterAssets,

  [long]$ReleaseSequence = 0,
  [string]$CompatibilityBaselinePath,
  [string]$InstallerUrl,
  [string]$ArtifactKeyId,
  [string]$ArtifactPublicKey,
  [string]$MetadataKeyId,
  [string]$MetadataPrivateKeyPath,
  [string]$MetadataPublicKeyPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent (Split-Path -Parent $scriptDir) # scripts/ci -> scripts -> repo root

$packer = Join-Path $repoRoot "scripts\ci\pack-mcpb.mjs"
$gate = Join-Path $repoRoot "scripts\ci\check-mcpb-consistency.mjs"
$gradleProps = Join-Path $repoRoot "gradle.properties"
$releaseAssets = Join-Path $repoRoot "scripts\release\app-release-assets.mjs"
$compatibilityRegister = Join-Path $repoRoot "governance\store-recoverability.v1.json"

if ($AssembleUpdaterAssets.IsPresent) {
  if ([string]::IsNullOrWhiteSpace($CompatibilityBaselinePath) -or
      -not (Test-Path -LiteralPath $CompatibilityBaselinePath -PathType Leaf)) {
    throw "-AssembleUpdaterAssets requires an existing predecessor -CompatibilityBaselinePath."
  }
  $CompatibilityBaselinePath = (Resolve-Path -LiteralPath $CompatibilityBaselinePath).Path
  & node $releaseAssets check-compatibility --compatibility $compatibilityRegister --compat-baseline $CompatibilityBaselinePath
  if ($LASTEXITCODE -ne 0) { throw "Predecessor compatibility preflight failed; refusing asset staging." }
}

function Get-GradleVersion {
  if (-not (Test-Path -LiteralPath $gradleProps)) { throw "gradle.properties not found at $gradleProps" }
  $line = Get-Content -LiteralPath $gradleProps | Where-Object { $_ -match '^\s*version\s*=' } | Select-Object -First 1
  if (-not $line) { throw "No 'version=' line in gradle.properties" }
  return ($line -replace '^\s*version\s*=\s*', '').Trim()
}

Push-Location $repoRoot
try {
  $outDirPath = if ([System.IO.Path]::IsPathRooted($OutDir)) { $OutDir } else { Join-Path $repoRoot $OutDir }
  if (-not (Test-Path -LiteralPath $outDirPath -PathType Container)) {
    throw "OutDir not found: $outDirPath (build the installer first, e.g. package-installer-win.ps1)"
  }

  # 1. Locate the installer already staged in OutDir.
  $installer = Get-ChildItem -LiteralPath $outDirPath -Filter "*-setup.exe" -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $installer) {
    throw "No *-setup.exe in $outDirPath. Build/copy the installer there before assembling release assets."
  }

  # 2. Consistency gate (fail closed) BEFORE staging the bundle.
  $gateArgs = @($gate)
  if ($VerifyReleaseVersion.IsPresent) {
    $version = Get-GradleVersion
    Write-Host "Verifying release version $version against server.json ..."
    $gateArgs += @("--release-version", $version)
  }
  & node @gateArgs
  if ($LASTEXITCODE -ne 0) { throw "MCPB consistency gate failed (exit=$LASTEXITCODE); refusing to assemble an inconsistent asset set." }

  # 3. Build the bundle deterministically from source into OutDir (never committed).
  # The gate above already verified this build matches server.json.fileSha256.
  $bundleDest = Join-Path $outDirPath "justsearch-mcp.mcpb"
  & node $packer $bundleDest
  if ($LASTEXITCODE -ne 0) { throw "pack-mcpb.mjs failed (exit=$LASTEXITCODE)" }

  # 4. A real release cut also assembles and verifies the authenticated updater asset set.
  $updaterAssets = @()
  if ($AssembleUpdaterAssets.IsPresent) {
    if (-not $VerifyReleaseVersion.IsPresent) {
      throw "-AssembleUpdaterAssets requires -VerifyReleaseVersion."
    }
    if ($version -notmatch '^\d+\.\d+\.\d+$') {
      throw "Authenticated channel 'stable' requires a stable x.y.z version; prereleases do not publish updater metadata."
    }
    $artifactSignature = "$($installer.FullName).sig"
    foreach ($required in @(
        @{ Name = "installer signature"; Value = $artifactSignature },
        @{ Name = "metadata private key"; Value = $MetadataPrivateKeyPath },
        @{ Name = "metadata public key"; Value = $MetadataPublicKeyPath })) {
      if ([string]::IsNullOrWhiteSpace($required.Value) -or -not (Test-Path -LiteralPath $required.Value -PathType Leaf)) {
        throw "Missing $($required.Name): $($required.Value)"
      }
    }
    if ($ReleaseSequence -le 0) { throw "-ReleaseSequence must be positive for a release cut." }
    foreach ($requiredValue in @(
        @{ Name = "InstallerUrl"; Value = $InstallerUrl },
        @{ Name = "ArtifactKeyId"; Value = $ArtifactKeyId },
        @{ Name = "ArtifactPublicKey"; Value = $ArtifactPublicKey },
        @{ Name = "MetadataKeyId"; Value = $MetadataKeyId })) {
      if ([string]::IsNullOrWhiteSpace($requiredValue.Value)) {
        throw "-$($requiredValue.Name) is required for a release cut."
      }
    }

    & node $releaseAssets build `
      --installer $installer.FullName `
      --artifact-signature $artifactSignature `
      --metadata-private-key $MetadataPrivateKeyPath `
      --compatibility $compatibilityRegister `
      --compat-baseline $CompatibilityBaselinePath `
      --out-dir $outDirPath `
      --version $version `
      --sequence $ReleaseSequence `
      --url $InstallerUrl `
      --artifact-key-id $ArtifactKeyId `
      --artifact-public-key $ArtifactPublicKey `
      --metadata-key-id $MetadataKeyId
    if ($LASTEXITCODE -ne 0) { throw "Authenticated release descriptor assembly failed." }

    & node $releaseAssets verify `
      --installer $installer.FullName `
      --artifact-signature $artifactSignature `
      --metadata-public-key $MetadataPublicKeyPath `
      --metadata-key-id $MetadataKeyId `
      --metadata-root-public-key $env:JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY `
      --release-dir $outDirPath
    if ($LASTEXITCODE -ne 0) { throw "Authenticated release asset-set verification failed." }

    $updaterAssets = @(
      $artifactSignature,
      (Join-Path $outDirPath "latest.json"),
      (Join-Path $outDirPath "release.v1.json"),
      (Join-Path $outDirPath "release.v1.json.sig"))
  }

  # 5. Generate SHA256SUMS over the closed set -- basenames, so 'sha256sum -c' works from OutDir.
  $assets = @($installer.FullName, $bundleDest) + $updaterAssets
  $header = @(
    "# SHA-256 checksums for JustSearch release assets.",
    "#",
    "# Generated by scripts/ci/build-release-assets.ps1 (tempdoc 726).",
    "# Verify on Windows:   certutil -hashfile <file> SHA256",
    "# Verify (git-bash):   sha256sum -c SHA256SUMS",
    "#"
  )
  $lines = New-Object System.Collections.Generic.List[string]
  $header | ForEach-Object { $lines.Add($_) }
  foreach ($asset in $assets) {
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $asset).Hash.ToLowerInvariant()
    $name = [System.IO.Path]::GetFileName($asset)
    # sha256sum(1) format: hash + two spaces + filename
    $lines.Add("$hash  $name")
  }
  $sumsPath = Join-Path $outDirPath "SHA256SUMS"
  # UTF8 without BOM so 'sha256sum -c' on Linux/git-bash parses the first line cleanly.
  $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($sumsPath, ($lines -join "`n") + "`n", $utf8NoBom)

  Write-Host ""
  Write-Host "Release asset set staged in: $outDirPath" -ForegroundColor Green
  foreach ($asset in $assets) {
    $sz = (Get-Item -LiteralPath $asset).Length
    $base = [System.IO.Path]::GetFileName($asset)
    Write-Host ("  {0,-45} {1,14:N0} bytes" -f $base, $sz)
  }
  Write-Host ("  {0,-45} (checksum manifest)" -f "SHA256SUMS")
}
finally {
  Pop-Location
}
