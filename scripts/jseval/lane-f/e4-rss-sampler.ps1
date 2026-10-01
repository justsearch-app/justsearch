# Stage E4 scoped memory sampler. Samples every process in the collector's owned-process scope
# (process-scope.json, written by e456-live.mjs) once per interval to a CSV until the stop file
# appears. privateMB is commit charge.
#
# Why a separate script (2026-10-01): head-rss-sampler.ps1 threw "Incomplete owned process memory
# sample" whenever a short-lived owned child (role other-child or extraction-child) exited between the
# collector writing the scope and the next sampler tick, which is normal. Its bytes enter E2's pair
# identity, so it is left untouched; E4 uses this script. A scoped JVM (head, worker, engine) that
# vanishes is still a failure, and a reused PID still fails identity. An exited non-JVM child is
# written as an "exited" row with zero bytes (it holds no memory) so the sample stays complete for the
# existing exact-byte parser and the exit stays visible.
# Usage: powershell -File e4-rss-sampler.ps1 -Out head-rss.csv -Stop rss.stop -Scope process-scope.json [-IntervalSec 2]
param(
  [Parameter(Mandatory = $true)][string]$Out,
  [Parameter(Mandatory = $true)][string]$Stop,
  [Parameter(Mandatory = $true)][string]$Scope,
  [int]$IntervalSec = 2,
  [ValidateSet('branch', 'main')][string]$Arm = 'branch'
)
$ErrorActionPreference = 'Stop'
$jvmRoles = @('head', 'worker', 'engine')
"ts,role,pid,workingSetMB,privateMB,cpuSec,threads,workingSetBytes,privateBytes,creationFileTimeUtc,expectedProcessCount,state" | Out-File -Encoding ascii $Out
while (-not (Test-Path $Stop)) {
  $ownedScope = Get-Content -LiteralPath $Scope -Raw | ConvertFrom-Json
  $nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  if ($nowMs - $ownedScope.atMs -gt 15000) { throw "Owned process scope is stale" }
  $scoped = @($ownedScope.processes)
  $expected = $scoped.Count
  $ts = Get-Date -Format o
  $accounted = 0
  foreach ($s in $scoped) {
    $p = Get-CimInstance Win32_Process -Filter "ProcessId=$($s.pid)" -ErrorAction SilentlyContinue
    $gp = if ($p) { Get-Process -Id $s.pid -ErrorAction SilentlyContinue } else { $null }
    if (-not $p -or -not $gp) {
      if ($jvmRoles -contains $s.role) { throw "Owned JVM vanished: PID $($s.pid) role $($s.role)" }
      "{0},{1},{2},0,0,0,0,0,0,{3},{4},exited" -f $ts, $s.role, $s.pid, $s.creationFileTimeUtc, $expected | Out-File -Encoding ascii -Append $Out
      $accounted++
      continue
    }
    if ($s.creationFileTimeUtc -ne $p.CreationDate.ToFileTimeUtc().ToString() -or $s.cmdlineFingerprint -ne $p.CommandLine) {
      throw "Owned process identity changed for PID $($s.pid)"
    }
    $ws = [math]::Round($gp.WorkingSet64 / 1MB, 1)
    $pm = [math]::Round($gp.PrivateMemorySize64 / 1MB, 1)
    $cpu = [math]::Round($gp.CPU, 1)
    "{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10},running" -f $ts, $s.role, $s.pid, $ws, $pm, $cpu, $gp.Threads.Count, $gp.WorkingSet64, $gp.PrivateMemorySize64, $s.creationFileTimeUtc, $expected | Out-File -Encoding ascii -Append $Out
    $accounted++
  }
  if ($accounted -ne $expected) { throw "Incomplete owned process memory sample" }
  Start-Sleep -Seconds $IntervalSec
}
