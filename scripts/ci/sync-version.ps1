#Requires -Version 5.1
[CmdletBinding()]
param(
  [string]$RepoRoot,
  [switch]$RequireReleaseSemver,
  [switch]$WhatIf
)
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Keep the Windows entry point and switches; share the projections with Linux.
$nodeArgs = @((Join-Path $PSScriptRoot "sync-version.mjs"))
if ($RepoRoot) { $nodeArgs += @("--repo-root", $RepoRoot) }
if ($RequireReleaseSemver.IsPresent) { $nodeArgs += "--require-release-semver" }
if ($WhatIf.IsPresent) { $nodeArgs += "--what-if" }
& node @nodeArgs
if ($LASTEXITCODE -ne 0) { throw "sync-version.mjs failed (exit=$LASTEXITCODE)" }
