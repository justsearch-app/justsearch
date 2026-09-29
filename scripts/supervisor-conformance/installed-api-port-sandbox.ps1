#Requires -Version 5.1
<#
.SYNOPSIS
  Stages and launches the installed API-port proof in a disposable Windows Sandbox.

.DESCRIPTION
  The production installer, the resolved Node executable, and the minimal proof sources are copied
  into a private read-only Sandbox mapping. A separate mapping carries only guest results. The
  Sandbox launch is non-blocking; inspect the returned output directory for the terminal receipt.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$InstallerPath,
  [string]$NodePath,
  [string]$WorkRoot,
  [switch]$GenerateOnly,
  [int]$MemoryMB = 8192
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$installer = (Resolve-Path -LiteralPath $InstallerPath).Path
if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) {
  throw "InstallerPath is not a file: $installer"
}
if ([string]::IsNullOrWhiteSpace($NodePath)) {
  $nodeCommand = Get-Command node.exe -ErrorAction Stop
  $NodePath = $nodeCommand.Source
}
$nodeCandidate = (Resolve-Path -LiteralPath $NodePath).Path
if (-not (Test-Path -LiteralPath $nodeCandidate -PathType Leaf)) {
  throw "NodePath is not a file: $nodeCandidate"
}
$nodeProbeInfo = New-Object System.Diagnostics.ProcessStartInfo
$nodeProbeInfo.FileName = $nodeCandidate
$nodeProbeInfo.Arguments = '-p "process.execPath"'
$nodeProbeInfo.UseShellExecute = $false
$nodeProbeInfo.CreateNoWindow = $true
$nodeProbeInfo.RedirectStandardOutput = $true
$nodeProbeInfo.RedirectStandardError = $true
$nodeProbe = New-Object System.Diagnostics.Process
$nodeProbe.StartInfo = $nodeProbeInfo
if (-not $nodeProbe.Start()) {
  throw "NodePath probe did not start: $nodeCandidate"
}
if (-not $nodeProbe.WaitForExit(10000)) {
  $nodeProbeCleanupError = $null
  try { $nodeProbe.Kill() } catch { $nodeProbeCleanupError = $_.Exception.Message }
  $cleanupDetail = if ($nodeProbeCleanupError) {
    "; exact-owned probe cleanup failed: $nodeProbeCleanupError"
  } else { "" }
  throw "NodePath probe timed out after 10 seconds: $nodeCandidate$cleanupDetail"
}
$nodeExecOutput = $nodeProbe.StandardOutput.ReadToEnd()
$nodeProbeError = $nodeProbe.StandardError.ReadToEnd()
$nodeExecPaths = @($nodeExecOutput -split "`r?`n" |
  Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($nodeProbe.ExitCode -ne 0 -or $nodeExecPaths.Count -ne 1) {
  throw "NodePath could not report one process.execPath: $nodeCandidate; exit=$($nodeProbe.ExitCode); stderr=$($nodeProbeError.Trim())"
}
$node = (Resolve-Path -LiteralPath $nodeExecPaths[0].Trim()).Path
if (-not (Test-Path -LiteralPath $node -PathType Leaf)) {
  throw "Resolved Node runtime is not a file: $node"
}
if ($MemoryMB -lt 4096) {
  throw "MemoryMB must be at least 4096"
}

if ([string]::IsNullOrWhiteSpace($WorkRoot)) {
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $suffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)
  $WorkRoot = Join-Path $repoRoot "tmp\installed-api-port-sandbox\$stamp-$suffix"
} elseif (-not [System.IO.Path]::IsPathRooted($WorkRoot)) {
  $WorkRoot = Join-Path $repoRoot $WorkRoot
}
$work = [System.IO.Path]::GetFullPath($WorkRoot)
if (Test-Path -LiteralPath $work) {
  throw "Refusing to reuse a non-private Sandbox work root: $work"
}

$inputDir = Join-Path $work "input"
$outputDir = Join-Path $work "output"
$fixtureDir = Join-Path $inputDir "scripts\supervisor-conformance"
$operationKeyDir = Join-Path $inputDir "modules\ui-web\src\api"
$nodeDir = Join-Path $inputDir "node"
$installerDir = Join-Path $inputDir "installer"
foreach ($directory in @($fixtureDir, $operationKeyDir, $nodeDir, $installerDir, $outputDir)) {
  New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

$stagedInstaller = Join-Path $installerDir "JustSearch-setup.exe"
$stagedNode = Join-Path $nodeDir "node.exe"
$stagedFixture = Join-Path $fixtureDir "installed-api-port-restart.mjs"
$stagedGuest = Join-Path $fixtureDir "installed-api-port-sandbox-guest.ps1"
$stagedOperationKey = Join-Path $operationKeyDir "operationKey.ts"
Copy-Item -LiteralPath $installer -Destination $stagedInstaller
Copy-Item -LiteralPath $node -Destination $stagedNode
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "installed-api-port-restart.mjs") `
  -Destination $stagedFixture
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "installed-api-port-sandbox-guest.ps1") `
  -Destination $stagedGuest
Copy-Item -LiteralPath (Join-Path $repoRoot "modules\ui-web\src\api\operationKey.ts") `
  -Destination $stagedOperationKey

$installerHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $stagedInstaller).Hash.ToLowerInvariant()
$nodeHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $stagedNode).Hash.ToLowerInvariant()
$stageReceipt = [ordered]@{
  schema = "justsearch.installed-api-port-sandbox-stage.v1"
  generatedAt = (Get-Date).ToUniversalTime().ToString("o")
  installer = [ordered]@{ source = $installer; staged = $stagedInstaller; sha256 = $installerHash }
  node = [ordered]@{ source = $node; staged = $stagedNode; sha256 = $nodeHash }
  inputDir = $inputDir
  outputDir = $outputDir
}
$stageReceiptPath = Join-Path $outputDir "host-stage.json"
($stageReceipt | ConvertTo-Json -Depth 6) | Set-Content -LiteralPath $stageReceiptPath -Encoding UTF8

$sandboxInput = "C:\Users\WDAGUtilityAccount\Desktop\JustSearchApiPortInput"
$sandboxOutput = "C:\Users\WDAGUtilityAccount\Desktop\JustSearchApiPortOutput"
$guestScript = "$sandboxInput\scripts\supervisor-conformance\installed-api-port-sandbox-guest.ps1"
$guestWork = "C:\Users\WDAGUtilityAccount\AppData\Local\Temp\JustSearchApiPortProof"
$guestInstall = "C:\Users\WDAGUtilityAccount\AppData\Local\Programs\JustSearchApiPortProof"
$guestCommand = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$guestScript`"" +
  " -InstallerPath `"$sandboxInput\installer\JustSearch-setup.exe`"" +
  " -ExpectedInstallerSha256 `"$installerHash`"" +
  " -NodePath `"$sandboxInput\node\node.exe`"" +
  " -ExpectedNodeSha256 `"$nodeHash`"" +
  " -FixturePath `"$sandboxInput\scripts\supervisor-conformance\installed-api-port-restart.mjs`"" +
  " -WorkRoot `"$guestWork`" -InstallDir `"$guestInstall`" -OutputDir `"$sandboxOutput`""

$xml = New-Object System.Xml.XmlDocument
$configuration = $xml.CreateElement("Configuration")
$xml.AppendChild($configuration) | Out-Null
$vgpu = $xml.CreateElement("VGpu"); $vgpu.InnerText = "Disable"; $configuration.AppendChild($vgpu) | Out-Null
$network = $xml.CreateElement("Networking"); $network.InnerText = "Enable"; $configuration.AppendChild($network) | Out-Null
$memory = $xml.CreateElement("MemoryInMB"); $memory.InnerText = "$MemoryMB"; $configuration.AppendChild($memory) | Out-Null
$mappedFolders = $xml.CreateElement("MappedFolders"); $configuration.AppendChild($mappedFolders) | Out-Null
foreach ($mapping in @(
    [ordered]@{ host = $inputDir; sandbox = $sandboxInput; readOnly = "true" },
    [ordered]@{ host = $outputDir; sandbox = $sandboxOutput; readOnly = "false" })) {
  $mapped = $xml.CreateElement("MappedFolder"); $mappedFolders.AppendChild($mapped) | Out-Null
  $hostFolderElement = $xml.CreateElement("HostFolder")
  $hostFolderElement.InnerText = $mapping.host
  $mapped.AppendChild($hostFolderElement) | Out-Null
  $sandbox = $xml.CreateElement("SandboxFolder"); $sandbox.InnerText = $mapping.sandbox; $mapped.AppendChild($sandbox) | Out-Null
  $readOnly = $xml.CreateElement("ReadOnly"); $readOnly.InnerText = $mapping.readOnly; $mapped.AppendChild($readOnly) | Out-Null
}
$logon = $xml.CreateElement("LogonCommand"); $configuration.AppendChild($logon) | Out-Null
$command = $xml.CreateElement("Command"); $command.InnerText = $guestCommand; $logon.AppendChild($command) | Out-Null

$wsbPath = Join-Path $work "installed-api-port-sandbox.wsb"
$settings = New-Object System.Xml.XmlWriterSettings
$settings.Indent = $true
$settings.OmitXmlDeclaration = $true
$settings.Encoding = New-Object System.Text.UTF8Encoding($false)
$writer = [System.Xml.XmlWriter]::Create($wsbPath, $settings)
try { $xml.Save($writer) } finally { $writer.Close() }

if (-not $GenerateOnly.IsPresent) {
  $existing = @(Get-Process -ErrorAction SilentlyContinue |
    Where-Object { $_.ProcessName -like "WindowsSandbox*" })
  if ($existing.Count -gt 0) {
    throw "Refusing to take over an existing Windows Sandbox process"
  }
  Start-Process -FilePath $wsbPath -WindowStyle Hidden | Out-Null
}

[pscustomobject]@{
  launched = -not $GenerateOnly.IsPresent
  wsbPath = $wsbPath
  inputDir = $inputDir
  outputDir = $outputDir
  terminalReceipt = (Join-Path $outputDir "installed-api-port-sandbox-result.json")
  fixtureStdout = (Join-Path $outputDir "fixture-stdout.log")
  fixtureStderr = (Join-Path $outputDir "fixture-stderr.log")
  stageReceipt = $stageReceiptPath
}
