<#
.SYNOPSIS
  JustSearch Headless Runtime launcher (tempdoc 657).

.DESCRIPTION
  Runs the backend as a local, loopback-only (127.0.0.1) service with no desktop
  shell, in the given install/runtime mode:

    headless   full local service (retrieval + LLM, no desktop UI)
    mcp-lite   fast retrieval for agents; excludes the LLM + CUDA runtime from
               the install plan (~2.1 GB vs ~10.7 GB) — does NOT currently gate
               runtime activation of an LLM model already present on disk

  To reuse an existing install's data + models, set $env:JUSTSEARCH_DATA_DIR and
  $env:JUSTSEARCH_HOME before launching.

  The runtime manifest (GET /api/runtime/manifest) reports the active mode under
  mode.intent / mode.realized.

.PARAMETER Mode
  One of 'headless' (default) or 'mcp-lite'.

.EXAMPLE
  .\justsearch-headless.ps1 -Mode mcp-lite
#>
[CmdletBinding()]
param(
  [ValidateSet('headless', 'mcp-lite')]
  [string] $Mode = 'headless'
)

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$java = Join-Path $here 'runtime\bin\java.exe'
if (-not (Test-Path $java)) { $java = 'java' }

$cp = "$here\ui-headless.jar;$here\lib\*"
& $java -cp $cp "-Djustsearch.prod=true" "-Djustsearch.mode=$Mode" io.justsearch.ui.HeadlessApp
