<#
.SYNOPSIS
  Runs one command in a new Windows job and holds the job's only handle until the command ends.

.DESCRIPTION
  A dev tool's run, contained as the Engine contains its children (WindowsEngineJob): the job's only
  limit is KILL_ON_JOB_CLOSE, with no breakaway. The root is created suspended, joined to the job,
  then resumed, so every descendant joins the job at creation; membership is kernel-tracked and is
  never derived from parent PIDs. Ending this host, for any reason, ends every member. When the root
  exits, this host ends what the root left in its job and exits with the root's code.

  The run record (RecordPath, JSON) names this host (pid, creationFileTimeUtc), the root and every
  member seen, each by PID and the creation time read on a handle IsProcessInJob confirmed. The
  stop is stop-job-run.ps1.

.PARAMETER Spec
  Base64 of UTF-8 JSON: { application?: string, commandLine: string, cwd?: string, newProcessGroup?: boolean }.
  With newProcessGroup the root and its descendants do not receive the console's Ctrl+C; this host
  never acts on Ctrl+C or Ctrl+Break in any case.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Spec,
  [Parameter(Mandatory = $true)][string]$RecordPath
)
$ErrorActionPreference = 'Stop'
try {
  $run = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Spec)) | ConvertFrom-Json
  if (-not ('JustSearchJobRun' -as [type])) { Add-Type -Path (Join-Path $PSScriptRoot 'job-run.cs') }
  $code = [JustSearchJobRun]::Host([string]$run.application, [string]$run.commandLine, [string]$run.cwd, $RecordPath,
    [bool]$run.newProcessGroup)
} catch {
  [Console]::Error.WriteLine("job-host: $($_.Exception.Message)")
  exit 127
}
exit $code
