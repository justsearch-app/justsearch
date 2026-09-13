#Requires -Version 5.1
[CmdletBinding()]
param([string]$SignScript)

# No certificate, SDK, provider, runner masking, or real secret is needed.
# Exercise the actual command-mode failure path and its on-failure log dump.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $SignScript) { $SignScript = Join-Path $PSScriptRoot 'sign-windows.ps1' }
$root = Join-Path ([IO.Path]::GetTempPath()) ('sign-diagnostics-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($root) | Out-Null
$saved = @{}
$names = @('TEMP', 'TMP', 'GITHUB_TOKEN', 'GH_TOKEN', 'TAURI_SIGNING_PRIVATE_KEY',
  'TAURI_SIGNING_PRIVATE_KEY_PASSWORD', 'METADATA_PRIVATE_KEY_PEM')
$names += @(Get-ChildItem Env: | Where-Object Name -like 'JUSTSEARCH_CODESIGN_*' | ForEach-Object Name)
$names += @('JUSTSEARCH_REQUIRE_SIGNING', 'JUSTSEARCH_CODESIGN_MODE', 'JUSTSEARCH_CODESIGN_COMMAND',
  'JUSTSEARCH_CODESIGN_LEDGER_PATH', 'JUSTSEARCH_CODESIGN_MAX_SIGNATURES')
$marker = 'FAKE_SIGNING_CREDENTIAL_956'
function Assert-Check([bool]$Condition, [string]$Name) {
  if (-not $Condition) { throw "FAIL: $Name (sensitive output withheld)" }
  Write-Host "PASS: $Name"
}
function Invoke-Child([string]$File, [string[]]$ChildArgs = @()) {
  $ErrorActionPreference = 'Continue'
  $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $File @ChildArgs 2>&1 | Out-String
  return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output }
}
try {
  foreach ($name in ($names | Select-Object -Unique)) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
  }
  $env:TEMP = $root
  $env:TMP = $root
  $env:JUSTSEARCH_REQUIRE_SIGNING = 'true'
  $env:JUSTSEARCH_CODESIGN_MODE = 'command'
  $env:JUSTSEARCH_CODESIGN_LEDGER_PATH = Join-Path $root 'ledger.jsonl'
  $env:JUSTSEARCH_CODESIGN_MAX_SIGNATURES = '1'
  $vendor = Join-Path $root 'fake-vendor.cmd'
  [IO.File]::WriteAllText($vendor, "@echo off`r`necho %*`r`necho %* 1>&2`r`nexit /b 42`r`n")
  $target = Join-Path $root 'unsigned.exe'
  [IO.File]::WriteAllText($target, 'synthetic input; failure precedes verification')
  $env:JUSTSEARCH_CODESIGN_COMMAND = '"' + $vendor + '" ' + $marker + ' "{file}"'
  $result = Invoke-Child $SignScript @($target)
  $log = Get-Content -LiteralPath (Join-Path $root 'justsearch-sign-windows.log') -Raw
  Assert-Check ($result.ExitCode -eq 1) 'command failure exits nonzero'
  Assert-Check ($result.Output -match 'exit=42') 'vendor exit code survives'
  Assert-Check (-not ($result.Output + $log).Contains($marker)) 'stdout stderr and workflow log dump withhold credential components'
  $events = @(Get-Content -LiteralPath ($env:JUSTSEARCH_CODESIGN_LEDGER_PATH + '.attempts.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
  Assert-Check (($events.Count -eq 2) -and ($events[1].exitCode -eq 42)) 'failed attempt retains budget accounting'

  # Extract unchanged production definitions for otherwise difficult launch/trap paths.
  $tokens = $null; $errors = $null
  $ast = [Management.Automation.Language.Parser]::ParseFile($SignScript, [ref]$tokens, [ref]$errors)
  Assert-Check ($errors.Count -eq 0) 'production script parses'
  $functions = $ast.FindAll({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -in @('Write-SignLog', 'Invoke-Native', 'Remove-ExtensionShim', 'Exit-SigningBudget')
  }, $true)
  $definitions = ($functions | ForEach-Object { $_.Extent.Text }) -join "`r`n"
  $traps = @($ast.FindAll({param($node) $node -is [Management.Automation.Language.TrapStatementAst]}, $true))
  Assert-Check ($traps.Count -eq 1) 'single production trap selected'
  $prefix = '$script:signLogPath = Join-Path $env:TEMP "probe.log"; $script:extensionShim = $null; $script:ledgerMutex = $null;' + "`r`n"
  $probe = Join-Path $root 'probe.ps1'
  $launch = '$r = Invoke-Native -Exe "MISSING_FAKE_SIGNING_CREDENTIAL_956.exe"; if ($r.ExitCode -ne -1) { exit 2 };'
  $launch += ' if ($r.PSObject.Properties.Name -contains "Output") { exit 3 }; exit 0'
  [IO.File]::WriteAllText($probe, $prefix + $definitions + "`r`n" + $launch)
  $result = Invoke-Child $probe
  $log = Get-Content -LiteralPath (Join-Path $root 'probe.log') -Raw
  Assert-Check (($result.ExitCode -eq 0) -and -not ($result.Output + $log).Contains($marker)) 'launch failure does not retain raw diagnostics'
  $body = $prefix + $definitions + "`r`n" + $traps[0].Extent.Text + "`r`nthrow '" + $marker + "'"
  [IO.File]::WriteAllText($probe, $body)
  $result = Invoke-Child $probe
  $log = Get-Content -LiteralPath (Join-Path $root 'probe.log') -Raw
  Assert-Check (($result.ExitCode -eq 1) -and -not ($result.Output + $log).Contains($marker)) 'trap withholds exception message and source excerpt'
  Assert-Check ($log -match 'at line \d+') 'trap retains structural location'
} finally {
  foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
  # Delete only the test's exact, freshly-created temporary directory.
  $resolved = [IO.Path]::GetFullPath($root)
  $tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
  if (-not $resolved.StartsWith($tempParent, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test cleanup path' }
  Remove-Item -LiteralPath $resolved -Recurse -Force
}
