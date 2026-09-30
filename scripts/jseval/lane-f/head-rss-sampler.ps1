# Samples the Engine, llama-server and extraction children working sets once per interval to a CSV until the stop file
# appears. Lane F stage A merged the Head and Worker processes into one Engine JVM (HeadlessApp
# entry point). Roles: engine, llama-server, extraction-child. privateMB is commit charge.
# Usage: powershell -File tmp/head-rss-sampler.ps1 -Out tmp/head-rss.csv -Stop tmp/head-rss.stop [-IntervalSec 2]
param(
  [string]$Out = "tmp/head-rss.csv",
  [string]$Stop = "tmp/head-rss.stop",
  [int]$IntervalSec = 2
)
"ts,role,pid,workingSetMB,privateMB,cpuSec,threads" | Out-File -Encoding ascii $Out
while (-not (Test-Path $Stop)) {
  $procs = @(Get-CimInstance Win32_Process)
  $engineIds = @($procs | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'HeadlessApp' } | ForEach-Object { $_.ProcessId })
  $ts = Get-Date -Format o
  foreach ($p in $procs) {
    $cl = $p.CommandLine
    $role = $null
    if ($engineIds -contains $p.ProcessId) { $role = 'engine' }
    elseif ($p.Name -eq 'llama-server.exe') { $role = 'llama-server' }
    elseif ($engineIds -contains $p.ParentProcessId -and $cl -match 'ExtractionSandboxChild') { $role = 'extraction-child' }
    if (-not $role) { continue }
    $gp = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue
    if (-not $gp) { continue }
    $ws = [math]::Round($gp.WorkingSet64 / 1MB, 1)
    $pm = [math]::Round($gp.PrivateMemorySize64 / 1MB, 1)
    $cpu = [math]::Round($gp.CPU, 1)
    "{0},{1},{2},{3},{4},{5},{6}" -f $ts, $role, $p.ProcessId, $ws, $pm, $cpu, $gp.Threads.Count | Out-File -Encoding ascii -Append $Out
  }
  Start-Sleep -Seconds $IntervalSec
}
