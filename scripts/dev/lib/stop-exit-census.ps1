# Read-only OS exit observer: handles are opened before stop and held after native/JVM death.
param([string]$InputFile, [string]$OutputFile, [string]$ReadyFile, [string]$FinishFile)
$ErrorActionPreference = 'Stop'
$targets = (Get-Content -LiteralPath $InputFile -Raw | ConvertFrom-Json).targets
$held = @()
$gaps = @()
$exits = @()
try {
  foreach ($target in $targets) {
    $observed = $null
    try {
      $observed = [System.Diagnostics.Process]::GetProcessById([int]$target.pid)
      $null = $observed.Handle # Force an OS handle before an exit makes PID lookup impossible.
      # The held handle's own birth (GetProcessTimes, exact to 100 ns): the process this handle names.
      [long]$birth = $observed.StartTime.ToUniversalTime().ToFileTimeUtc()
      $exe = $observed.MainModule.FileName
      # Spec F §6.1, the shared birth rule (exit-ledger.cjs sameBirth): the recorded birth and the
      # handle's agree when both, truncated to the target's tick (the coarser clock: the microsecond
      # for a CIM or ledger birth, the millisecond for a B11 startInstant), are equal. A pid reused by
      # another process names a birth a whole process lifetime after the recorded one, never inside
      # one tick; and the handle held from here keeps the pid from being reused again.
      # A target without a tick takes the rule's default, the microsecond (BIRTH_TICKS.MICROSECOND).
      [long]$recorded = 0; [long]$tick = 10
      if ($null -ne $target.birthTick -and (-not [long]::TryParse("$($target.birthTick)", [ref]$tick) -or $tick -lt 1)) {
        throw "target birth tick is not a positive integer: $($target.birthTick)"
      }
      if (-not [long]::TryParse("$($target.creationFileTimeUtc)", [ref]$recorded) -or $recorded -le 0) {
        throw 'target lacks a recorded birth'
      }
      $sameBirth = ($birth - ($birth % $tick)) -eq ($recorded - ($recorded % $tick))
      if (-not $sameBirth -or $exe.ToLowerInvariant() -ne $target.executable.ToLowerInvariant()) {
        $observed.Dispose()
        $gaps += @{ pid = $target.pid; reason = 'PID/start/executable identity mismatch; no target observation';
          recordedStart = $target.creationFileTimeUtc; observedStart = "$birth"; birthTick = "$tick"; startSource = $target.creationTimeSource }
        continue
      }
      $held += @{ process = $observed; target = $target; osBirth = "$birth"; exited = $false }
    } catch {
      if ($null -ne $observed) { $observed.Dispose() }
      $gaps += @{ pid = $target.pid; reason = "process handle unavailable before stop: $($_.Exception.Message)" }
    }
  }
  @{ ready = $true } | ConvertTo-Json | Set-Content -LiteralPath $ReadyFile -Encoding UTF8
  $deadline = [DateTime]::UtcNow.AddSeconds(90)
  while ($true) {
    foreach ($item in $held) {
      try {
        if (-not $item.exited -and $item.process.HasExited) {
          $item.exited = $true
          # Spec F §6.1: an exit time at or before the FILETIME epoch, or before 2001, is unknown
          # (the ledger's 2001 rule); the code is still the held handle's.
          $atMs = $null; $timeSource = 'unknown'
          try {
            $exitTime = $item.process.ExitTime.ToUniversalTime()
            if ($exitTime.ToFileTimeUtc() -gt 0 -and $exitTime.Year -ge 2001) {
              $atMs = ([DateTimeOffset]$exitTime).ToUnixTimeMilliseconds(); $timeSource = 'os'
            }
          } catch { $atMs = $null; $timeSource = 'unknown' }
          $exits += @{ pid = $item.target.pid; creationFileTimeUtc = $item.target.creationFileTimeUtc;
            osCreationFileTimeUtc = $item.osBirth;
            executable = $item.target.executable; exitCode = $item.process.ExitCode;
            atMs = $atMs; exitTimeSource = $timeSource }
        }
      } catch {
        $item.exited = $true
        $gaps += @{ pid = $item.target.pid; reason = "exit observation failed: $($_.Exception.Message)" }
      }
    }
    if ((Test-Path -LiteralPath $FinishFile) -or [DateTime]::UtcNow -ge $deadline) { break }
    Start-Sleep -Milliseconds 50
  }
  foreach ($item in $held) {
    if (-not $item.exited) { $gaps += @{ pid = $item.target.pid; reason = 'still alive or exit unobserved at terminal stop' } }
  }
  # The stop's own kills, informational only: a verdict comes from the exit ledger (Spec B §5.1).
  $killed = @()
  if (Test-Path -LiteralPath $FinishFile) { $killed = (Get-Content -LiteralPath $FinishFile -Raw | ConvertFrom-Json).killedPids }
  $temporary = "$OutputFile.pending"
  @{ processExits = @($exits); gaps = @($gaps); killedPids = @($killed) } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $temporary -Encoding UTF8
  Move-Item -LiteralPath $temporary -Destination $OutputFile -Force
} finally { foreach ($item in $held) { $item.process.Dispose() } }
