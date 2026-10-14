# Launch a private command in its own job and return once its run record names the job's host.
# The command (a .bat/.cmd, through cmd.exe) runs under lib/job-host.ps1: everything it starts,
# such as the java behind a start script, is in that job by kernel membership, and
# lib/stop-job-run.ps1 ends the run and verifies each recorded process. Never parent links.
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$CommandFile,
      [Parameter(Mandatory=$true)][string]$RecordPath)
$ErrorActionPreference = 'Stop'
$comspec = if ($env:ComSpec) { $env:ComSpec } else { Join-Path $env:SystemRoot 'System32\cmd.exe' }
$spec = @{ application = $comspec; commandLine = ('"' + $comspec + '" /d /s /c ""' + $CommandFile + '""'); cwd = (Get-Location).Path }
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($spec | ConvertTo-Json -Compress)))
$jobHost = Join-Path $PSScriptRoot 'lib\job-host.ps1'
Remove-Item -LiteralPath $RecordPath -ErrorAction SilentlyContinue
$hostProcess = Start-Process -FilePath 'powershell.exe' -PassThru -WindowStyle Hidden -ArgumentList @(
  '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $jobHost + '"'),
  '-Spec', $encoded, '-RecordPath', ('"' + $RecordPath + '"'))
try {
  $end = [DateTime]::UtcNow.AddSeconds(60)
  while ([DateTime]::UtcNow -lt $end) {
    try {
      $record = Get-Content -Raw -LiteralPath $RecordPath -ErrorAction Stop | ConvertFrom-Json
      if ([int]$record.pid -ne $hostProcess.Id) { throw "the run record names another host than PID $($hostProcess.Id)" }
      exit 0
    } catch [System.Management.Automation.ItemNotFoundException] {
    } catch [System.ArgumentException] {
    } catch [System.IO.IOException] {
    }
    if ($hostProcess.HasExited) { throw "the job host exited with code $($hostProcess.ExitCode) before recording its run" }
    Start-Sleep -Milliseconds 25
  }
  throw 'the job host did not record its run within 60 s'
} finally { $hostProcess.Dispose() }
