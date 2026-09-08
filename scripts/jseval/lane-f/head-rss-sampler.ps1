# Samples the Head and Worker JVM working sets once per interval to a CSV until the stop file appears.
# Usage: powershell -File tmp/head-rss-sampler.ps1 -Out tmp/head-rss.csv -Stop tmp/head-rss.stop [-IntervalSec 2]
param(
  [string]$Out = "tmp/head-rss.csv",
  [string]$Stop = "tmp/head-rss.stop",
  [int]$IntervalSec = 2
)
"ts,role,pid,workingSetMB,privateMB,cpuSec,threads" | Out-File -Encoding ascii $Out
while (-not (Test-Path $Stop)) {
  $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'"
  foreach ($p in $procs) {
    $cl = $p.CommandLine
    if (-not $cl) { continue }
    $role = $null
    if ($cl -match 'HeadlessApp') { $role = 'head' }
    elseif ($cl -match 'IndexerWorker') { $role = 'worker' }
    if (-not $role) { continue }
    $gp = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue
    if (-not $gp) { continue }
    $ws = [math]::Round($gp.WorkingSet64 / 1MB, 1)
    $pm = [math]::Round($gp.PrivateMemorySize64 / 1MB, 1)
    $cpu = [math]::Round($gp.CPU, 1)
    "{0},{1},{2},{3},{4},{5},{6}" -f (Get-Date -Format o), $role, $p.ProcessId, $ws, $pm, $cpu, $gp.Threads.Count | Out-File -Encoding ascii -Append $Out
  }
  Start-Sleep -Seconds $IntervalSec
}
