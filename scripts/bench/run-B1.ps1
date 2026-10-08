# run-B1.ps1 — zero-click orchestrator for the E-S-B1 system-sanity benchmark.
# Launches telemetry (LHM DLL, nvidia-smi dmon, smartctl NVMe poll) and benchmarks
# (PassMark via ptscript, llama-bench) in the right order, waits, stops telemetry,
# summarises outputs.
#
# Invoke from F:\JustSearch (repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\bench\run-B1.ps1

. (Join-Path $PSScriptRoot "../testing/owned-process.ps1")
$ErrorActionPreference = "Stop"
# Script lives at <root>\scripts\bench\run-B1.ps1 → go up 3 levels to reach <root>.
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$outDir     = Join-Path $root "tmp\B1"
$pt         = "C:\Program Files\PerformanceTest\PerformanceTest64.exe"
$ptscript   = Join-Path $root "scripts\bench\passmark-b1.ptscript"
$llamaBench = "C:\Program Files\llama.cpp\llama-bench.exe"
$llamaModel = Join-Path $root "models\Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"
$smartctl   = "C:\Program Files\smartmontools\bin\smartctl.exe"
$lhmLog     = Join-Path $root "scripts\bench\lhm-log.ps1"

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$orchestratorLog = Join-Path $outDir "orchestrator.log"
function Say($m) {
  $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m
  Write-Host $line
  $line | Out-File -FilePath $orchestratorLog -Append -Encoding ASCII
}

Say "B1 orchestrator start"
Say "Output dir: $outDir"

# ---- 1. Pre-flight state ----
Say ""
Say "=== Pre-flight ==="
$gpu = (nvidia-smi --query-gpu=temperature.gpu --format=csv,noheader) -replace '\s',''
Say "GPU temp: $gpu C"
$nvme = & $smartctl -A -d nvme pd1 | Select-String "^Temperature:" | ForEach-Object { $_ -replace '\s+',' ' }
Say "NVMe temp raw: $nvme"
$mem = (Get-CimInstance Win32_OperatingSystem | ForEach-Object { [math]::Round($_.FreePhysicalMemory/1MB,1) })
Say "Free RAM: $mem GB"
$wu = (Get-Service wuauserv).Status
Say "Windows Update service: $wu"

# ---- 2. Start telemetry in background jobs ----
Say ""
Say "=== Starting telemetry (6000 s = 100 min each) ==="

$dmonCsv = Join-Path $outDir "gpu.csv"
$dmon = Start-Process -FilePath "nvidia-smi" -ArgumentList "dmon","-s","pucvmet","-d","1","-c","6000","-f","`"$dmonCsv`"" -NoNewWindow -PassThru
Register-FixtureProcess $dmon
Say "nvidia-smi dmon PID=$($dmon.Id) -> $dmonCsv"

$lhmCsv = Join-Path $outDir "lhm.csv"
$lhmProc = Start-Process -FilePath "powershell.exe" `
  -ArgumentList "-NoProfile","-ExecutionPolicy","Bypass","-File","`"$lhmLog`"","-Output","`"$lhmCsv`"","-IntervalSec","1","-DurationSec","6000" `
  -NoNewWindow -PassThru
Register-FixtureProcess $lhmProc
Say "lhm-log.ps1 PID=$($lhmProc.Id) -> $lhmCsv"

# smartctl 1 Hz poll loop in a background PowerShell job
$smartJsonl = Join-Path $outDir "nvme.jsonl"
$smartJob = Start-Job -ScriptBlock {
  param($smartctl, $out)
  $end = (Get-Date).AddSeconds(6000)
  while ((Get-Date) -lt $end) {
    $t = Get-Date -Format o
    try {
      $j = & $smartctl -A -j -d nvme pd1 2>$null
      "$t`t$j" -join "" | Out-File -Append -Encoding ASCII $out
    } catch {}
    Start-Sleep -Seconds 1
  }
} -ArgumentList $smartctl, $smartJsonl
Say "smartctl poll JobId=$($smartJob.Id) -> $smartJsonl"

Start-Sleep -Seconds 2
Say "Telemetry warm-up: 2 s"

try {
  # ---- 3. Launch PassMark (blocking) ----
  Say ""
  Say "=== PassMark B1 start (expected 30-35 min at SETDURATION 30, N=1; 2026-04-19 measured 31 min) ==="
  $ptStart = Get-Date
  $ptProc = Start-Process -FilePath $pt -ArgumentList "/s","`"$ptscript`"" -WorkingDirectory $root -Wait -PassThru
  Register-FixtureProcess $ptProc
  $ptEnd = Get-Date
  $ptWall = ($ptEnd - $ptStart).TotalSeconds
  Say "PassMark exit: $($ptProc.ExitCode); wall: $([math]::Round($ptWall,1)) s"

  # ---- 4. llama-bench ----
  Say ""
  Say "=== llama-bench on 8B Q4_K_M ==="
  $lbCsv = Join-Path $outDir "llama-bench.csv"
  $lbStart = Get-Date
  if (Test-Path $llamaModel) {
    $lbErr = Join-Path $outDir "llama-bench.stderr"
    # Use Start-Process for clean stdout/stderr split - the `& cmd 2> ... > ...` form trips
    # PS 5.1's NativeCommandError when $ErrorActionPreference is Stop.
    $lbProc = Start-Process -FilePath $llamaBench `
      -ArgumentList "-m","`"$llamaModel`"","-ngl","99","-p","512","-n","128","-r","5","-o","csv" `
      -NoNewWindow -Wait -PassThru `
      -RedirectStandardOutput $lbCsv `
      -RedirectStandardError $lbErr
    Register-FixtureProcess $lbProc
    $lbWall = ((Get-Date) - $lbStart).TotalSeconds
    Say "llama-bench exit: $($lbProc.ExitCode); wall: $([math]::Round($lbWall,1)) s -> $lbCsv"
  } else {
    Say "SKIPPED llama-bench: model not found at $llamaModel"
  }
} finally {
  # ---- 5. Stop telemetry (ALWAYS — even if PassMark/llama-bench errored) ----
  Say ""
  Say "=== Stopping telemetry (finally block) ==="
  Stop-FixtureProcess $dmon
  Stop-FixtureProcess $lhmProc
  Stop-Job -Job $smartJob -ErrorAction SilentlyContinue
  Remove-Job -Job $smartJob -Force -ErrorAction SilentlyContinue
  Say "All telemetry stopped."

  # ---- 6. Summary ----
  Say ""
  Say "=== Outputs ==="
  Get-ChildItem $outDir | Select-Object Name, Length, LastWriteTime | ForEach-Object {
    Say ("  {0}  {1,12} bytes  {2}" -f $_.Name, $_.Length, $_.LastWriteTime)
  }
  Say ""
  Say "B1 run done (check exit codes above for success/failure). Review results in $outDir"
}
