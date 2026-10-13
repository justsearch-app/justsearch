<#
.SYNOPSIS
  Token accounting probe for llama-server.

.DESCRIPTION
  Measures the difference between:
  - tokenizing "raw" user content (what JustSearch currently approximates), and
  - tokenizing the fully templated prompt produced by llama-server's POST /apply-template.

  This helps quantify chat-template overhead (special tokens, wrappers, role headers) so
  Q&A context budgeting can be made template-accurate.

  Output is written as JSON (schema: token-probe.v1) under tmp/agent-evidence/_summaries by default.

.EXAMPLE
  # Start a local llama-server on a small model, run probe, stop server.
  powershell -ExecutionPolicy Bypass -File scripts/ai/token-probe.ps1 `
    -StartLocalServer `
    -Port 8086 `
    -ModelPath .\\models\\tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf

.EXAMPLE
  # Probe an already-running llama-server instance.
  powershell -ExecutionPolicy Bypass -File scripts/ai/token-probe.ps1 `
    -ServerUrl http://localhost:8080 `
    -UserPrompt "Summarize the following text: ..."
#>

[CmdletBinding()]
param(
  [Parameter(Mandatory = $false)]
  [string] $ServerUrl = "http://localhost:8080",

  [Parameter(Mandatory = $false)]
  [int] $Port = 8080,

  [Parameter(Mandatory = $false)]
  [string] $SystemPrompt = "You are a helpful assistant.",

  [Parameter(Mandatory = $false)]
  [string] $UserPrompt = "What is the main idea of the provided context?",

  [Parameter(Mandatory = $false)]
  [string] $ContextPath,

  [Parameter(Mandatory = $false)]
  [switch] $StartLocalServer,

  [Parameter(Mandatory = $false)]
  [string] $LlamaServerExe = "native-bin/llama-server/llama-server.exe",

  [Parameter(Mandatory = $false)]
  [string] $ModelPath = "models/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",

  [Parameter(Mandatory = $false)]
  [bool] $AddSpecial = $false,

  [Parameter(Mandatory = $false)]
  [bool] $ParseSpecial = $true,

  [Parameter(Mandatory = $false)]
  [int] $CtxSize = 2048,

  [Parameter(Mandatory = $false)]
  [int] $GpuLayers = 0,

  [Parameter(Mandatory = $false)]
  [string] $OutJson
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) # scripts/ai -> scripts -> repo root
$summaryDir = Join-Path -Path $repoRoot -ChildPath "tmp\\agent-evidence\\_summaries"
$null = New-Item -ItemType Directory -Force -Path $summaryDir
$timestamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
if ([string]::IsNullOrWhiteSpace($OutJson)) {
  $OutJson = Join-Path -Path $summaryDir -ChildPath ("token-probe-" + $timestamp + ".json")
}

function Normalize-BaseUrl([string] $Url) {
  if ([string]::IsNullOrWhiteSpace($Url)) { return $Url }
  return $Url.TrimEnd("/")
}

function Wait-HttpOk([string] $Url, [int] $TimeoutMs = 180000) {
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  while ($sw.ElapsedMilliseconds -lt $TimeoutMs) {
    try {
      $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -Method GET -TimeoutSec 2
      if ($resp.StatusCode -eq 200) { return $true }
    } catch {
      Start-Sleep -Milliseconds 250
    }
  }
  return $false
}

function Invoke-JsonPost([string] $Url, [object] $BodyObj, [int] $TimeoutSec = 10) {
  $json = $BodyObj | ConvertTo-Json -Depth 20 -Compress
  return Invoke-RestMethod -Uri $Url -Method POST -ContentType "application/json" -Body $json -TimeoutSec $TimeoutSec
}

function Try-Tokenize([string] $BaseUrl, [string] $Content, [bool] $AddSpecialBool, [bool] $ParseSpecialBool) {
  if ($null -eq $Content) { $Content = "" }
  $body = @{
    content = $Content
    add_special = $AddSpecialBool
    parse_special = $ParseSpecialBool
  }
  try {
    $resp = Invoke-JsonPost -Url ($BaseUrl + "/tokenize") -BodyObj $body -TimeoutSec 10
    if ($null -eq $resp -or $null -eq $resp.tokens) { return $null }
    return @($resp.tokens).Count
  } catch {
    return $null
  }
}

function Try-ApplyTemplate([string] $BaseUrl, [object[]] $Messages) {
  $body = @{ messages = $Messages }
  try {
    $resp = Invoke-JsonPost -Url ($BaseUrl + "/apply-template") -BodyObj $body -TimeoutSec 10
    if ($null -eq $resp -or [string]::IsNullOrWhiteSpace($resp.prompt)) { return $null }
    return [string] $resp.prompt
  } catch {
    return $null
  }
}

function Read-Context([string] $Path) {
  if ([string]::IsNullOrWhiteSpace($Path)) { return "" }
  $p = $Path
  if (-not [System.IO.Path]::IsPathRooted($p)) {
    $p = Join-Path -Path $repoRoot -ChildPath $p
  }
  if (-not (Test-Path -LiteralPath $p)) {
    throw "ContextPath not found: $p"
  }
  return [string] (Get-Content -LiteralPath $p -Raw)
}

$baseUrl = Normalize-BaseUrl $ServerUrl
$startedPid = $null
$startedBirth = $null
$startError = $null
$applyTemplatePrompt = $null

try {
  if ($StartLocalServer.IsPresent) {
    $exe = $LlamaServerExe
    if (-not [System.IO.Path]::IsPathRooted($exe)) {
      $exe = Join-Path -Path $repoRoot -ChildPath $exe
    }
    $model = $ModelPath
    if (-not [System.IO.Path]::IsPathRooted($model)) {
      $model = Join-Path -Path $repoRoot -ChildPath $model
    }
    if (-not (Test-Path -LiteralPath $exe)) { throw "llama-server.exe not found: $exe" }
    if (-not (Test-Path -LiteralPath $model)) { throw "ModelPath not found: $model" }

    # Prefer IPv4 loopback to avoid localhost -> ::1 resolution issues on Windows.
    $baseUrl = "http://127.0.0.1:$Port"
    $healthUrl = $baseUrl + "/health"

    $proc = Start-Process -FilePath $exe -ArgumentList @(
      "-m", $model,
      "--port", $Port,
      "-c", $CtxSize,
      "-ngl", $GpuLayers,
      "--no-warmup"
    ) -PassThru -WindowStyle Hidden
    $startedPid = $proc.Id
    $startedBirth = $proc.StartTime.ToUniversalTime().ToFileTimeUtc().ToString()
    $ok = Wait-HttpOk -Url $healthUrl
    if (-not $ok) {
      throw "Timed out waiting for llama-server readiness on $healthUrl (pid=$startedPid)"
    }
  }

  $ctx = Read-Context $ContextPath
  $userContent = $UserPrompt
  if (-not [string]::IsNullOrWhiteSpace($ctx)) {
    $userContent = $userContent + "`n`n---`n`n" + $ctx
  }

  $messages = @(
    @{ role = "system"; content = $SystemPrompt },
    @{ role = "user"; content = $userContent }
  )

  $addSpecialBool = [bool] $AddSpecial
  $parseSpecialBool = [bool] $ParseSpecial

  $rawUserTokens = Try-Tokenize -BaseUrl $baseUrl -Content $userContent -AddSpecialBool $addSpecialBool -ParseSpecialBool $parseSpecialBool
  $applyTemplatePrompt = Try-ApplyTemplate -BaseUrl $baseUrl -Messages $messages
  $promptTokens = $null
  if (-not [string]::IsNullOrWhiteSpace($applyTemplatePrompt)) {
    $promptTokens = Try-Tokenize -BaseUrl $baseUrl -Content $applyTemplatePrompt -AddSpecialBool $addSpecialBool -ParseSpecialBool $parseSpecialBool
  }

  $overhead = $null
  if ($null -ne $rawUserTokens -and $null -ne $promptTokens) {
    $overhead = [int] ($promptTokens - $rawUserTokens)
  }

  $outObj = [ordered]@{
    kind = "token-probe.v1"
    captured_at = (Get-Date).ToString("o")
    server_url = $baseUrl
    start_local_server = $StartLocalServer.IsPresent
    llama_server_exe = if ($StartLocalServer.IsPresent) { $LlamaServerExe } else { "" }
    model_path = if ($StartLocalServer.IsPresent) { $ModelPath } else { "" }
    tokenize = @{
      add_special = $addSpecialBool
      parse_special = $parseSpecialBool
    }
    inputs = @{
      system_chars = $SystemPrompt.Length
      user_chars = $userContent.Length
      context_path = if ([string]::IsNullOrWhiteSpace($ContextPath)) { "" } else { $ContextPath }
    }
    results = @{
      raw_user_tokens = $rawUserTokens
      templated_prompt_tokens = $promptTokens
      template_overhead_tokens = $overhead
      templated_prompt_chars = if ($null -eq $applyTemplatePrompt) { $null } else { $applyTemplatePrompt.Length }
    }
    warnings = @()
  }

  if ($null -eq $rawUserTokens) {
    $outObj.warnings += "tokenize_failed"
  }
  if ($null -eq $applyTemplatePrompt) {
    $outObj.warnings += "apply_template_failed"
  }
  if ($null -eq $promptTokens -and $null -ne $applyTemplatePrompt) {
    $outObj.warnings += "tokenize_templated_prompt_failed"
  }

  $outJsonText = $outObj | ConvertTo-Json -Depth 20
  $outJsonText | Set-Content -LiteralPath $OutJson -Encoding UTF8

  Write-Host ("Wrote token probe: " + $OutJson)
  Write-Host ("- server_url: " + $baseUrl)
  $rawUserTokensOut = if ($null -ne $rawUserTokens) { $rawUserTokens } else { "null" }
  $promptTokensOut = if ($null -ne $promptTokens) { $promptTokens } else { "null" }
  $overheadOut = if ($null -ne $overhead) { $overhead } else { "null" }
  Write-Host ("- raw_user_tokens: " + $rawUserTokensOut)
  Write-Host ("- templated_prompt_tokens: " + $promptTokensOut)
  Write-Host ("- template_overhead_tokens: " + $overheadOut)

} catch {
  $startError = $_.Exception.Message
  throw
} finally {
  if ($null -ne $startedPid -and $null -ne $startedBirth) {
    try {
      & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PSScriptRoot/../dev/lib/terminate-process.ps1" -Pid $startedPid -CreationFileTimeUtc $startedBirth -Code 1 -BirthTick 1
    } catch {
      # best-effort
    }
  }
}
