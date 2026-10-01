# Samples the Engine, llama-server and extraction children working sets once per interval to a CSV until the stop file
# appears. Lane F stage A merged the Head and Worker processes into one Engine JVM (HeadlessApp
# entry point). Roles: engine, llama-server, extraction-child. privateMB is commit charge.
# Usage: powershell -File tmp/head-rss-sampler.ps1 -Out tmp/head-rss.csv -Stop tmp/head-rss.stop [-IntervalSec 2]
param(
  [string]$Out = "tmp/head-rss.csv",
  [string]$Stop = "tmp/head-rss.stop",
  [int]$IntervalSec = 2,
  [switch]$IncludeSplitWorker,
  [string]$Scope = "",
  [ValidateSet('branch', 'main')][string]$Arm = 'branch'
)
if ($Scope) { $ErrorActionPreference = 'Stop' }
"ts,role,pid,workingSetMB,privateMB,cpuSec,threads,workingSetBytes,privateBytes,creationFileTimeUtc,expectedProcessCount" | Out-File -Encoding ascii $Out
while (-not (Test-Path $Stop)) {
  $procs = @(Get-CimInstance Win32_Process)
  $ownedScope = $null
  if ($Scope) {
    $ownedScope = Get-Content -LiteralPath $Scope -Raw | ConvertFrom-Json
    $nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    if ($nowMs - $ownedScope.atMs -gt 15000) { throw "Owned process scope is stale" }
  }
  $engineIds = @($procs | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'HeadlessApp' } | ForEach-Object { $_.ProcessId })
  $workerIds = @($procs | Where-Object { $IncludeSplitWorker -and $_.Name -eq 'java.exe' -and $_.CommandLine -match 'io.justsearch.indexerworker.IndexerWorker' } | ForEach-Object { $_.ProcessId })
  $ts = Get-Date -Format o
  $sampledIds = @()
  foreach ($p in $procs) {
    $cl = $p.CommandLine
    $role = $null
    if ($Scope) {
      $match = @($ownedScope.processes | Where-Object { $_.pid -eq $p.ProcessId })
      if ($match.Count -eq 0) { continue }
      if ($match.Count -ne 1 -or $match[0].creationFileTimeUtc -ne $p.CreationDate.ToFileTimeUtc().ToString() -or $match[0].cmdlineFingerprint -ne $p.CommandLine) {
        throw "Owned process identity changed for PID $($p.ProcessId)"
      }
      $role = $match[0].role
    }
    elseif ($engineIds -contains $p.ProcessId) { $role = 'engine' }
    elseif ($workerIds -contains $p.ProcessId) { $role = 'worker' }
    elseif ($p.Name -eq 'llama-server.exe') { $role = 'llama-server' }
    elseif (($engineIds -contains $p.ParentProcessId -or $workerIds -contains $p.ParentProcessId) -and $cl -match 'ExtractionSandboxChild') { $role = 'extraction-child' }
    if (-not $role) { continue }
    $gp = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue
    if (-not $gp) { continue }
    $ws = [math]::Round($gp.WorkingSet64 / 1MB, 1)
    $pm = [math]::Round($gp.PrivateMemorySize64 / 1MB, 1)
    $cpu = [math]::Round($gp.CPU, 1)
    $sampledIds += $p.ProcessId
    $expected = if ($Scope) { @($ownedScope.processes).Count } else { "" }
    $creation = $p.CreationDate.ToFileTimeUtc().ToString()
    "{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10}" -f $ts, $role, $p.ProcessId, $ws, $pm, $cpu, $gp.Threads.Count, $gp.WorkingSet64, $gp.PrivateMemorySize64, $creation, $expected | Out-File -Encoding ascii -Append $Out
  }
  if ($Scope -and $sampledIds.Count -ne @($ownedScope.processes).Count) { throw "Incomplete owned process memory sample" }
  Start-Sleep -Seconds $IntervalSec
}
