<#
.SYNOPSIS
  Stops one dev-tool run started by job-host.ps1 and verifies that nothing it started survives.

.DESCRIPTION
  The recorded host is ended only when its handle shows the recorded birth; its job handle closes
  and Windows ends every member of the job. Then every identity the run record names (the root and
  each member, PID plus creation time) is waited for on its own handle. An identity whose PID now
  shows another birth is not ours and is never touched. Nothing is found through parent PIDs,
  names or a PID alone. An identity the Engine's exit ledger records as ended (-EndedPath: a JSON
  list of PID plus creation time the caller read from the ledger) is gone and is not probed again:
  its PID may name another process by now whose birth cannot be read, which never makes it ours.

.NOTES
  One JSON line on stdout. Exit codes: 0 every recorded process has ended (outcome 'terminated' when
  this stop ended the host, 'gone' when the run had already ended); 4 incomplete or failure.
  endedByJob lists by identity each recorded process the job's termination ended: the members still
  running when this stop ended the host, or, for a run that had already ended, those the host
  itself ended after its root exited. endedPerLedger lists each recorded identity the ledger
  already showed ended.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$RecordPath,
  [Alias('Pid')][int]$HostPid = 0,
  [string]$CreationFileTimeUtc = '',
  [uint32]$Code = 1,
  [int]$WaitMs = 10000,
  [long]$BirthTick = 10,
  [string]$EndedPath = ''
)
$ErrorActionPreference = 'Stop'

function Write-Outcome([string]$Outcome, [hashtable]$Extra = @{}) {
  $body = @{ outcome = $Outcome; recordPath = $RecordPath }
  foreach ($key in $Extra.Keys) { $body[$key] = $Extra[$key] }
  [Console]::Out.WriteLine(($body | ConvertTo-Json -Compress -Depth 4))
}

# Recorded identities whose end the ledger shows, as PID/birth at BirthTick precision.
$ended = New-Object 'System.Collections.Generic.HashSet[string]'
function Identity-Key($process) {
  [long]$b = 0
  if (-not [long]::TryParse([string]$process.creationFileTimeUtc, [ref]$b)) { return $null }
  return ('{0}/{1}' -f [int]$process.pid, ($b - $b % $BirthTick))
}
function Is-Ended($process) {
  $key = Identity-Key $process
  return ($null -ne $key -and $ended.Contains($key))
}

function Read-Record {
  for ($i = 0; $i -lt 50; $i++) {
    try { return (Get-Content -Raw -LiteralPath $RecordPath -ErrorAction Stop | ConvertFrom-Json) }
    catch { Start-Sleep -Milliseconds 20 }
  }
  return $null
}

try {
  if (-not ('JustSearchJobRun' -as [type])) { Add-Type -Path (Join-Path $PSScriptRoot 'job-run.cs') }
  if ($EndedPath) {
    $endedList = Get-Content -Raw -LiteralPath $EndedPath | ConvertFrom-Json
    foreach ($p in $endedList) {
      $key = Identity-Key $p
      if ($null -ne $key) { [void]$ended.Add($key) }
    }
  }
  $record = Read-Record
  if ($null -eq $record -and $HostPid -le 0) {
    Write-Outcome 'unknown-identity' @{ reason = 'no run record and no recorded host identity' }
    exit 4
  }
  if ($HostPid -le 0) { $HostPid = [int]$record.pid; $CreationFileTimeUtc = [string]$record.creationFileTimeUtc }
  elseif ($null -ne $record -and ([int]$record.pid -ne $HostPid)) {
    Write-Outcome 'unknown-identity' @{ reason = 'the run record names another host' }
    exit 4
  }
  [long]$hostBirth = 0
  if (-not [long]::TryParse($CreationFileTimeUtc, [ref]$hostBirth) -or $hostBirth -le 0) {
    Write-Outcome 'unknown-identity' @{ reason = 'the host birth is unknown' }
    exit 4
  }
  # Which recorded processes are still running just before the job closes: those the stop ends.
  $runningBefore = @()
  if ($null -ne $record) {
    $before = @(@($record.root) + @($record.members) | Where-Object { $_ -and $_.pid } |
      Sort-Object -Property pid, creationFileTimeUtc -Unique | Where-Object { -not (Is-Ended $_) })
    if ($before.Count -gt 0) {
      $runningBefore = @([JustSearchJobRun]::Survivors([int[]]@($before | ForEach-Object { [int]$_.pid }),
        [long[]]@($before | ForEach-Object { [long]$_.creationFileTimeUtc }), $BirthTick, 0) | ForEach-Object { $_ | ConvertFrom-Json })
    }
  }
  $hostOutcome = [JustSearchJobRun]::EndHost($HostPid, $hostBirth, $Code, $BirthTick, $WaitMs)
  if ($hostOutcome -eq 'failed') {
    Write-Outcome 'failed' @{ host = $hostOutcome; reason = 'the recorded host could not be ended' }
    exit 4
  }
  # The host has ended, so the record is final: verify every identity it names.
  $record = Read-Record
  $members = @()
  $endedPerLedger = @()
  if ($null -ne $record) {
    $recorded = @(@($record.root) + @($record.members) | Where-Object { $_ -and $_.pid } |
      Sort-Object -Property pid, creationFileTimeUtc -Unique)
    $endedPerLedger = @($recorded | Where-Object { Is-Ended $_ } |
      ForEach-Object { @{ pid = [int]$_.pid; creationFileTimeUtc = [string]$_.creationFileTimeUtc } })
    $members = @($recorded | Where-Object { -not (Is-Ended $_) })
  }
  $pids = [int[]]@($members | ForEach-Object { [int]$_.pid })
  $births = [long[]]@($members | ForEach-Object { [long]$_.creationFileTimeUtc })
  $survivors = @([JustSearchJobRun]::Survivors($pids, $births, $BirthTick, $WaitMs) | ForEach-Object { $_ | ConvertFrom-Json })
  $outcome = if ($survivors.Count -gt 0) { 'incomplete' } elseif ($hostOutcome -eq 'terminated') { 'terminated' } else { 'gone' }
  $endedByJob = if ($hostOutcome -eq 'terminated') { $runningBefore } elseif ($null -ne $record -and $record.endedByJob) { @($record.endedByJob) } else { @() }
  $endedByJob = @($endedByJob | Where-Object { $_ -and $_.pid } |
    Where-Object { $s = $_; -not ($survivors | Where-Object { $_.pid -eq $s.pid -and $_.creationFileTimeUtc -eq $s.creationFileTimeUtc }) })
  Write-Outcome $outcome @{ host = $hostOutcome; members = $members.Count; survivors = $survivors; endedByJob = $endedByJob;
    endedPerLedger = $endedPerLedger }
  if ($survivors.Count -gt 0) { exit 4 }
  exit 0
} catch {
  Write-Outcome 'failed' @{ reason = $_.Exception.Message }
  exit 4
}
