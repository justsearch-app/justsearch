<#
.SYNOPSIS
  Ends one process with a chosen exit code, only if it is still the process that was recorded.

.DESCRIPTION
  Spec F section 5.2 (Q6): the dev supervisor's forced kill must leave an exit code that tells a
  supervisor kill apart from a crash. `taskkill /F` and libuv's kill both end the process with code 1,
  the same code as an uncaught exception in the Engine, so this helper calls TerminateProcess with
  the code the caller names (JS_ENGINE_STOP, 0x4A530002, for the Engine).

  The identity check and the kill use ONE process handle: OpenProcess, then GetProcessTimes on that
  handle, then TerminateProcess on the same handle. A pid recycled between the check and the kill
  would need the recorded process to exit and a new one to take its pid while this handle is open,
  which Windows does not allow (an open handle keeps the pid reserved).

  The creation time is compared under the shared birth rule (exit-ledger.cjs sameBirth): both values
  truncated to BirthTick, the coarser clock's tick, are equal. The recorded value comes from
  Win32_Process.CreationDate (microsecond) or the ledger (GetProcessTimes, 100 ns) and the live one
  from GetProcessTimes, so the caller passes the microsecond (10 ticks). A different process cannot
  take a pid inside the same tick as the recorded one was born, because the recorded process must
  have exited (and its last handle closed) first.

.PARAMETER Pid
  The process id (bound to $TargetPid: $PID is a read-only automatic variable).

.PARAMETER CreationFileTimeUtc
  The recorded creation time, Windows FILETIME ticks as a decimal string.

.PARAMETER Code
  The exit code TerminateProcess gives the process.

.PARAMETER BirthTick
  The tick (in 100 ns FILETIME units) the births are compared at; the rule's default is the
  microsecond (10). A birth recorded in milliseconds (a registered child's startedAt) is compared at
  the millisecond (10000).

.PARAMETER Tree
  Also end the recorded process's descendants, as `taskkill /T` would, but each one only through
  its own handle: while the recorded process's handle is held (its pid cannot be reused), its
  descendants are read in at most three process-table snapshots. Verified parent handles stay
  open throughout, pinning their PIDs. After each scan the proven processes are ended and waited
  for; a final scan with no new descendants proves closure. An unknown link or an exhausted
  scan budget is reported as partial, never as a completed tree.

.NOTES
  Exit codes: 0 terminated; 3 identity mismatch or the process is gone (nothing was killed);
  4 failure (bad arguments, access denied, TerminateProcess failed, or with -Tree a descendant
  that could not be ended: outcome 'partial').
  One JSON line on stdout describes the outcome.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][Alias('Pid')][int]$TargetPid,
  [Parameter(Mandatory = $true)][string]$CreationFileTimeUtc,
  [Parameter(Mandatory = $true)][uint32]$Code,
  [long]$BirthTick = 10,
  [switch]$Tree
)

$ErrorActionPreference = 'Stop'

function Write-Outcome([string]$Outcome, [hashtable]$Extra = @{}) {
  $body = @{ outcome = $Outcome; pid = $TargetPid; code = $Code }
  foreach ($key in $Extra.Keys) { $body[$key] = $Extra[$key] }
  [Console]::Out.WriteLine(($body | ConvertTo-Json -Compress))
}

[long]$recorded = 0
if (-not [long]::TryParse($CreationFileTimeUtc.Trim(), [ref]$recorded) -or $recorded -le 0) {
  Write-Outcome 'bad-arguments' @{ reason = 'CreationFileTimeUtc is not a positive decimal FILETIME' }
  exit 4
}
if ($TargetPid -le 0) {
  Write-Outcome 'bad-arguments' @{ reason = 'Pid must be positive' }
  exit 4
}
if ($BirthTick -lt 1) {
  Write-Outcome 'bad-arguments' @{ reason = 'BirthTick must be positive' }
  exit 4
}

try {
  Add-Type -Namespace JustSearchDev -Name TerminateNative -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
public static extern System.IntPtr OpenProcess(uint access, bool inherit, int pid);
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
public static extern bool GetProcessTimes(System.IntPtr process, out long creation, out long exit, out long kernel, out long user);
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
public static extern bool GetExitCodeProcess(System.IntPtr process, out uint exitCode);
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
public static extern bool TerminateProcess(System.IntPtr process, uint exitCode);
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
public static extern uint WaitForSingleObject(System.IntPtr handle, uint milliseconds);
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
public static extern bool CloseHandle(System.IntPtr handle);
'@
} catch {
  Write-Outcome 'failed' @{ reason = "Add-Type failed: $($_.Exception.Message)" }
  exit 4
}

$PROCESS_TERMINATE = 0x0001
$PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
$SYNCHRONIZE = 0x00100000
$STILL_ACTIVE = 259
$ERROR_INVALID_PARAMETER = 87

$handle = [JustSearchDev.TerminateNative]::OpenProcess(
  ($PROCESS_TERMINATE -bor $PROCESS_QUERY_LIMITED_INFORMATION -bor $SYNCHRONIZE), $false, $TargetPid)
if ($handle -eq [System.IntPtr]::Zero) {
  $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
  if ($err -eq $ERROR_INVALID_PARAMETER) {
    Write-Outcome 'gone' @{ reason = 'no process has this pid' }
    exit 3
  }
  Write-Outcome 'failed' @{ reason = "OpenProcess failed with Win32 error $err" }
  exit 4
}

try {
  [long]$creation = 0; [long]$exitTime = 0; [long]$kernel = 0; [long]$user = 0
  if (-not [JustSearchDev.TerminateNative]::GetProcessTimes($handle, [ref]$creation, [ref]$exitTime, [ref]$kernel, [ref]$user)) {
    $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
    Write-Outcome 'failed' @{ reason = "GetProcessTimes failed with Win32 error $err" }
    exit 4
  }
  $liveTick = $creation - ($creation % $BirthTick)
  $recordedTick = $recorded - ($recorded % $BirthTick)
  if ($liveTick -ne $recordedTick) {
    Write-Outcome 'mismatch' @{ liveCreationFileTimeUtc = "$creation"; recordedCreationFileTimeUtc = "$recorded" }
    exit 3
  }
  [uint32]$current = 0
  if ([JustSearchDev.TerminateNative]::GetExitCodeProcess($handle, [ref]$current) -and $current -ne $STILL_ACTIVE) {
    Write-Outcome 'gone' @{ reason = 'the recorded process has already exited'; exitCode = $current }
    exit 3
  }
  if (-not $Tree) {
    if (-not [JustSearchDev.TerminateNative]::TerminateProcess($handle, $Code)) {
      Write-Outcome 'failed' @{ rootOutcome = 'failed'; reason = "TerminateProcess failed with Win32 error $([System.Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
      exit 4
    }
    Write-Outcome 'terminated' @{ rootOutcome = 'terminated' }
    exit 0
  }

  # Linear collections: never repeatedly copy a growing PowerShell array.
  $ended = New-Object System.Collections.Generic.List[int]
  $notEnded = New-Object 'System.Collections.Generic.HashSet[int]'
  $nodes = @{ $TargetPid = @{ handle = $handle; birth = $creation; outcome = 'pending' } }
  $limit = $false; $closed = $false; $scanCount = 0
  try {
    for ($scanCount = 1; $scanCount -le 3; $scanCount++) {
      $rows = @(Get-CimInstance Win32_Process -Property ProcessId, ParentProcessId, CreationDate -ErrorAction Stop)
      $births = @{}; $children = @{}
      foreach ($row in $rows) {
        $id = [int]$row.ProcessId
        # Keep parent links even when CIM could not read the birth.
        if ($row.CreationDate) { $births[$id] = [long]$row.CreationDate.ToFileTimeUtc() }
        $parent = [int]$row.ParentProcessId
        if (-not $children.ContainsKey($parent)) { $children[$parent] = New-Object System.Collections.Generic.List[int] }
        $children[$parent].Add($id)
      }
      $pending = New-Object System.Collections.Generic.List[int]
      if ($scanCount -eq 1) { $pending.Add($TargetPid) }
      $queue = New-Object System.Collections.Generic.Queue[int]
      $seen = New-Object 'System.Collections.Generic.HashSet[int]'
      # Include every pinned parent: exited parents may be absent from the next table, while a
      # child they spawned during the previous enumeration still names their reserved PID.
      foreach ($id in $nodes.Keys) { $queue.Enqueue($id); [void]$seen.Add($id) }
      foreach ($id in $notEnded) { if ($seen.Add($id)) { $queue.Enqueue($id) } }
      while ($queue.Count -gt 0) {
        $parent = $queue.Dequeue()
        if (-not $children.ContainsKey($parent)) { continue }
        foreach ($id in $children[$parent]) {
          if (-not $seen.Add($id)) { continue }
          if (-not $nodes.ContainsKey($parent) -or -not $births.ContainsKey($id)) {
            [void]$notEnded.Add($id)
            $queue.Enqueue($id) # retain the entire unresolved subtree, without killing it
            continue
          }
          [long]$parentBirth = $nodes[$parent].birth
          [long]$childBirth = $births[$id]
          if (($childBirth - ($childBirth % 10)) -lt ($parentBirth - ($parentBirth % 10))) { continue }
          $childHandle = [JustSearchDev.TerminateNative]::OpenProcess(
            ($PROCESS_TERMINATE -bor $PROCESS_QUERY_LIMITED_INFORMATION -bor $SYNCHRONIZE), $false, $id)
          # Even a disappeared intermediate process leaves ownership below it unresolved: no
          # parent handle can pin its PID for the next enumeration.
          if ($childHandle -eq [System.IntPtr]::Zero) {
            [void]$notEnded.Add($id); $queue.Enqueue($id); continue
          }
          [long]$childCreation = 0
          $proven = [JustSearchDev.TerminateNative]::GetProcessTimes($childHandle, [ref]$childCreation, [ref]$exitTime, [ref]$kernel, [ref]$user)
          if (-not $proven -or ($childCreation - ($childCreation % 10)) -ne ($childBirth - ($childBirth % 10))) {
            [void][JustSearchDev.TerminateNative]::CloseHandle($childHandle)
            [void]$notEnded.Add($id); $queue.Enqueue($id); continue
          }
          $nodes[$id] = @{ handle = $childHandle; birth = $childCreation; outcome = 'pending' }
          $pending.Add($id)
          $queue.Enqueue($id)
        }
      }
      if ($pending.Count -eq 0) { $closed = $true; break }
      # Parent first prevents further spawning. Every proven handle stays pinned until the final
      # scan, and termination completion is observed on that same handle before scanning again.
      foreach ($id in $pending) {
        $node = $nodes[$id]
        if ([JustSearchDev.TerminateNative]::WaitForSingleObject($node.handle, 0) -eq 0) {
          $node.outcome = 'gone'; continue
        }
        if ([JustSearchDev.TerminateNative]::TerminateProcess($node.handle, $Code)) {
          $node.outcome = 'terminating'
        } else { $node.outcome = 'failed'; [void]$notEnded.Add($id) }
      }
      $waitClock = [Diagnostics.Stopwatch]::StartNew()
      foreach ($id in $pending) {
        $node = $nodes[$id]
        if ($node.outcome -ne 'terminating') { continue }
        [uint32]$remainingMs = [Math]::Max(0, 1500 - $waitClock.ElapsedMilliseconds)
        if ([JustSearchDev.TerminateNative]::WaitForSingleObject($node.handle, $remainingMs) -eq 0) {
          $node.outcome = 'terminated'
          if ($id -ne $TargetPid) { $ended.Add($id) }
        } else { $node.outcome = 'unconfirmed'; [void]$notEnded.Add($id) }
      }
    }
    if (-not $closed) { $limit = $true }
    $extra = @{ rootOutcome = $nodes[$TargetPid].outcome; descendantsEnded = @($ended.ToArray());
      descendantsNotEnded = @($notEnded | Where-Object { $_ -ne $TargetPid });
      enumerationLimitReached = $limit; scans = [Math]::Min($scanCount, 3) }
    if ($notEnded.Count -gt 0 -or $limit) { Write-Outcome 'partial' $extra; exit 4 }
    Write-Outcome 'terminated' $extra
    exit 0
  } catch {
    Write-Outcome 'failed' @{ rootOutcome = $nodes[$TargetPid].outcome; descendantsEnded = @($ended.ToArray());
      descendantsNotEnded = @($notEnded | Where-Object { $_ -ne $TargetPid });
      reason = "tree enumeration failed: $($_.Exception.Message)" }
    exit 4
  } finally {
    foreach ($id in $nodes.Keys) {
      if ($id -ne $TargetPid) { [void][JustSearchDev.TerminateNative]::CloseHandle($nodes[$id].handle) }
    }
  }
} finally {
  [void][JustSearchDev.TerminateNative]::CloseHandle($handle)
}
