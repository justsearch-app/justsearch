# Test/benchmark cleanup is limited to a process this fixture actually started.
$script:fixtureProcesses = @{}
function Register-FixtureProcess($Process) {
  if (-not $Process -or $Process.HasExited) { return }
  $null = $Process.Handle # retain the original Windows handle, pinning its identity
  $birth = $Process.StartTime.ToUniversalTime().ToFileTimeUtc()
  $script:fixtureProcesses[$Process.Id] = @{ Process = $Process; Birth = $birth }
}
function Stop-FixtureProcess($Process) {
  if (-not $Process) { return }
  if ($Process.HasExited) { return }
  $owned = $script:fixtureProcesses[$Process.Id]
  if (-not $owned -or -not [object]::ReferenceEquals($owned.Process, $Process)) {
    throw "No start-time identity recorded for fixture process $($Process.Id)"
  }
  if ($Process.StartTime.ToUniversalTime().ToFileTimeUtc() -ne $owned.Birth) {
    throw "Fixture identity changed for $($Process.Id)"
  }
  $Process.Kill() # held handle, never tree enumeration or a fresh PID lookup
  $null = $Process.WaitForExit(10000)
}
