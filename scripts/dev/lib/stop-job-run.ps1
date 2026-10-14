<#
.SYNOPSIS
  Stops one dev-tool run started by job-host.ps1 and verifies that nothing it started survives.

.DESCRIPTION
  The recorded host is ended only when its handle shows the recorded birth; its job handle closes
  and Windows ends every member of the job. Then every identity the run record names (the root and
  each member, PID plus creation time) is waited for on its own handle. An identity whose PID now
  shows another birth is not ours and is never touched. Nothing is found through parent PIDs,
  names or a PID alone.

.NOTES
  One JSON line on stdout. Exit codes: 0 every recorded process has ended (outcome 'terminated' when
  this stop ended the host, 'gone' when the run had already ended); 4 incomplete or failure.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$RecordPath,
  [Alias('Pid')][int]$HostPid = 0,
  [string]$CreationFileTimeUtc = '',
  [uint32]$Code = 1,
  [int]$WaitMs = 10000,
  [long]$BirthTick = 10
)
$ErrorActionPreference = 'Stop'

function Write-Outcome([string]$Outcome, [hashtable]$Extra = @{}) {
  $body = @{ outcome = $Outcome; recordPath = $RecordPath }
  foreach ($key in $Extra.Keys) { $body[$key] = $Extra[$key] }
  [Console]::Out.WriteLine(($body | ConvertTo-Json -Compress -Depth 4))
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
  $hostOutcome = [JustSearchJobRun]::EndHost($HostPid, $hostBirth, $Code, $BirthTick, $WaitMs)
  if ($hostOutcome -eq 'failed') {
    Write-Outcome 'failed' @{ host = $hostOutcome; reason = 'the recorded host could not be ended' }
    exit 4
  }
  # The host has ended, so the record is final: verify every identity it names.
  $record = Read-Record
  $members = @()
  if ($null -ne $record) {
    $members = @(@($record.root) + @($record.members) | Where-Object { $_ -and $_.pid } |
      Sort-Object -Property pid, creationFileTimeUtc -Unique)
  }
  $pids = [int[]]@($members | ForEach-Object { [int]$_.pid })
  $births = [long[]]@($members | ForEach-Object { [long]$_.creationFileTimeUtc })
  $survivors = @([JustSearchJobRun]::Survivors($pids, $births, $BirthTick, $WaitMs) | ForEach-Object { $_ | ConvertFrom-Json })
  $outcome = if ($survivors.Count -gt 0) { 'incomplete' } elseif ($hostOutcome -eq 'terminated') { 'terminated' } else { 'gone' }
  Write-Outcome $outcome @{ host = $hostOutcome; members = $members.Count; survivors = $survivors }
  if ($survivors.Count -gt 0) { exit 4 }
  exit 0
} catch {
  Write-Outcome 'failed' @{ reason = $_.Exception.Message }
  exit 4
}
