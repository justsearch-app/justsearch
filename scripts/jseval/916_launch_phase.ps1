# Tempdoc 916 Part 1 — historical detached launcher for one campaign phase.
# The shipping tree has no temporary chunk-sweep binding; the driver preflight refuses
# execution until that binding is deliberately restored in a throwaway experiment branch.
#
# WHY DETACHED: a tracked background task is killed at ~60 minutes
# (a harness limit), and this campaign's phases run for hours. The
# driver therefore runs as a `Start-Process` child of nothing, supervised through
# `run-watcher.mjs`'s heartbeat + verdict rather than by a live tool call.
#
# Usage:
#   powershell -NoProfile -File 916_launch_phase.ps1 -Phase sigma `
#     -Corpora "mixed/legal-clerc-200" -Arms "500/50" -Reps 3 [-DeadlineMs 2000]
param(
  [Parameter(Mandatory = $true)][string]$Phase,
  [Parameter(Mandatory = $true)][string]$Corpora,
  [string]$Arms = "",
  [int]$Reps = 1,
  [int]$DeadlineMs = 2000,
  [string]$Out = "F:\justsearch-public\.claude\worktrees\lane-E\scripts\jseval\tmp\916-part1\sweep-20260903"
)

$ErrorActionPreference = "Stop"
$jseval = "F:\justsearch-public\.claude\worktrees\lane-E\scripts\jseval"
$watcher = "F:\justsearch-public\.claude\worktrees\lane-E\scripts\dev\run-watcher.mjs"
$state = Join-Path $Out ("watch-" + $Phase)
New-Item -ItemType Directory -Force -Path $state | Out-Null

$driverArgs = @("916_chunk_sweep.py", "run", "--out", $Out, "--corpora", $Corpora,
                "--reps", "$Reps", "--deadline-ms", "$DeadlineMs", "--phase", $Phase)
if ($Arms -ne "") { $driverArgs += @("--arms", $Arms) }

$argList = @("$watcher", "run", "--dir", "$state", "--marker-on-exit", "PHASE.done", "--",
             "python") + $driverArgs

$p = Start-Process -FilePath "node" -ArgumentList $argList -WorkingDirectory $jseval `
  -RedirectStandardOutput (Join-Path $state "watcher.out") `
  -RedirectStandardError (Join-Path $state "watcher.err") `
  -WindowStyle Hidden -PassThru
"phase=$Phase pid=$($p.Id) state=$state"
