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
  descendants are read from one process-table snapshot, every link a parent born no later than its
  child, and each is ended only if its handle shows the birth the snapshot named.

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
public static extern bool CloseHandle(System.IntPtr handle);
'@
} catch {
  Write-Outcome 'failed' @{ reason = "Add-Type failed: $($_.Exception.Message)" }
  exit 4
}

$PROCESS_TERMINATE = 0x0001
$PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
$STILL_ACTIVE = 259
$ERROR_INVALID_PARAMETER = 87

$handle = [JustSearchDev.TerminateNative]::OpenProcess(
  ($PROCESS_TERMINATE -bor $PROCESS_QUERY_LIMITED_INFORMATION), $false, $TargetPid)
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
  $ended = @(); $notEnded = @()
  if ($Tree) {
    try {
      $rows = @(Get-CimInstance Win32_Process -Property ProcessId, ParentProcessId, CreationDate -ErrorAction Stop)
    } catch {
      Write-Outcome 'failed' @{ reason = "the process table could not be read: $($_.Exception.Message)" }
      exit 4
    }
    $births = @{}; $children = @{}
    foreach ($row in $rows) {
      if ($row.CreationDate) { $births[[int]$row.ProcessId] = [long]$row.CreationDate.ToFileTimeUtc() }
      $parent = [int]$row.ParentProcessId
      if (-not $children.ContainsKey($parent)) { $children[$parent] = New-Object System.Collections.Generic.List[int] }
      $children[$parent].Add([int]$row.ProcessId)
    }
    # Microsecond: the snapshot's clock. A child is never born before its parent.
    $micro = { param([long]$t) $t - ($t % 10) }
    $descendants = New-Object System.Collections.Generic.List[int]
    $seen = @{ $TargetPid = $true }
    $queue = New-Object System.Collections.Generic.Queue[object]
    $queue.Enqueue(@($TargetPid, $creation))
    while ($queue.Count -gt 0) {
      $item = $queue.Dequeue()
      if (-not $children.ContainsKey([int]$item[0])) { continue }
      foreach ($child in $children[[int]$item[0]]) {
        if ($seen.ContainsKey($child)) { continue }
        if (-not $births.ContainsKey($child)) { $notEnded += $child; continue }
        if ((& $micro $births[$child]) -lt (& $micro ([long]$item[1]))) { continue }
        $seen[$child] = $true
        $descendants.Add($child)
        $queue.Enqueue(@($child, $births[$child]))
      }
    }
    # Deepest first; each through its own handle, re-checked against the snapshot's birth.
    for ($i = $descendants.Count - 1; $i -ge 0; $i--) {
      $child = $descendants[$i]
      $childHandle = [JustSearchDev.TerminateNative]::OpenProcess(
        ($PROCESS_TERMINATE -bor $PROCESS_QUERY_LIMITED_INFORMATION), $false, $child)
      if ($childHandle -eq [System.IntPtr]::Zero) {
        if ([System.Runtime.InteropServices.Marshal]::GetLastWin32Error() -ne $ERROR_INVALID_PARAMETER) { $notEnded += $child }
        continue
      }
      try {
        [long]$childCreation = 0
        if (-not [JustSearchDev.TerminateNative]::GetProcessTimes($childHandle, [ref]$childCreation, [ref]$exitTime, [ref]$kernel, [ref]$user)) {
          $notEnded += $child
          continue
        }
        if ((& $micro $childCreation) -ne (& $micro $births[$child])) { $notEnded += $child; continue }
        [uint32]$childCode = 0
        if ([JustSearchDev.TerminateNative]::GetExitCodeProcess($childHandle, [ref]$childCode) -and $childCode -ne $STILL_ACTIVE) { continue }
        if ([JustSearchDev.TerminateNative]::TerminateProcess($childHandle, $Code)) { $ended += $child } else { $notEnded += $child }
      } finally {
        [void][JustSearchDev.TerminateNative]::CloseHandle($childHandle)
      }
    }
  }
  if (-not [JustSearchDev.TerminateNative]::TerminateProcess($handle, $Code)) {
    $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
    Write-Outcome 'failed' @{ reason = "TerminateProcess failed with Win32 error $err"; descendantsEnded = $ended; descendantsNotEnded = $notEnded }
    exit 4
  }
  if ($notEnded.Count -gt 0) {
    Write-Outcome 'partial' @{ descendantsEnded = $ended; descendantsNotEnded = $notEnded }
    exit 4
  }
  if ($Tree) {
    Write-Outcome 'terminated' @{ descendantsEnded = $ended }
  } else {
    Write-Outcome 'terminated'
  }
  exit 0
} finally {
  [void][JustSearchDev.TerminateNative]::CloseHandle($handle)
}
