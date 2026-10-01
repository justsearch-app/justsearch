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
      $birth = $observed.StartTime.ToUniversalTime().ToFileTimeUtc().ToString()
      $exe = $observed.MainModule.FileName
      $identityBirth = $birth
      if ($target.creationTimeSource -eq 'CIM') {
        # A held handle alive across this fresh CIM read prevents PID reuse in the read.
        if ($observed.HasExited) { throw 'held process exited before identity read' }
        $cim = Get-CimInstance Win32_Process -Filter "ProcessId = $($target.pid)"
        if ($null -eq $cim -or $observed.HasExited) { throw 'held process exited during identity read' }
        $identityBirth = $cim.CreationDate.ToFileTimeUtc().ToString()
      }
      if ($identityBirth -ne $target.creationFileTimeUtc -or $exe.ToLowerInvariant() -ne $target.executable.ToLowerInvariant()) {
        $observed.Dispose()
        $gaps += @{ pid = $target.pid; reason = 'PID/start/executable identity mismatch; no target observation';
          recordedStart = $target.creationFileTimeUtc; observedStart = $identityBirth; startSource = $target.creationTimeSource }
        continue
      }
      $held += @{ process = $observed; target = $target; osBirth = $birth; exited = $false }
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
          $exits += @{ pid = $item.target.pid; creationFileTimeUtc = $item.target.creationFileTimeUtc;
            osCreationFileTimeUtc = $item.osBirth;
            executable = $item.target.executable; exitCode = $item.process.ExitCode;
            atMs = ([DateTimeOffset]$item.process.ExitTime.ToUniversalTime()).ToUnixTimeMilliseconds() }
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
  $forced = @()
  if (Test-Path -LiteralPath $FinishFile) { $forced = (Get-Content -LiteralPath $FinishFile -Raw | ConvertFrom-Json).forcedPids }
  $temporary = "$OutputFile.pending"
  @{ processExits = @($exits); gaps = @($gaps); forcedPids = @($forced) } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $temporary -Encoding UTF8
  Move-Item -LiteralPath $temporary -Destination $OutputFile -Force
} finally { foreach ($item in $held) { $item.process.Dispose() } }
