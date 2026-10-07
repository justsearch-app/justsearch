param([Parameter(Mandatory)][int]$SupervisorPid, [Parameter(Mandatory)][string]$DataDir)
$ErrorActionPreference = 'Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class SessionProbe {
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindowEx(IntPtr parent, IntPtr after, string cls, string title);
  [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
  [DllImport("user32.dll", SetLastError=true)] public static extern IntPtr SendMessageTimeout(IntPtr hwnd, uint msg, UIntPtr w, IntPtr l, uint flags, uint timeout, out UIntPtr result);
}
'@
function Read-EngineRecords {
  $files = Get-ChildItem -LiteralPath (Join-Path $DataDir 'runtime/exit-ledger') -Filter 'engine*.jsonl' -ErrorAction SilentlyContinue
  foreach ($file in $files) {
    $stream = [IO.File]::Open($file.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    $reader = [IO.StreamReader]::new($stream)
    try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    # A writer can still be appending its last row. Only completed JSONL rows count.
    $end = $text.LastIndexOf("`n")
    if ($end -ge 0) {
      foreach ($line in $text.Substring(0, $end).Split("`n")) {
        if ($line.Trim()) { $line | ConvertFrom-Json }
      }
    }
  }
}
$supervisor = Get-Process -Id $SupervisorPid
$window = [IntPtr]::Zero
do {
  $window = [SessionProbe]::FindWindowEx([IntPtr]::Zero, $window, 'JustSearchSessionWindow', 'JustSearchSessionWindow')
  if ($window -eq [IntPtr]::Zero) { throw 'No hidden top-level session window for this supervisor' }
  [uint32]$owner = 0
  [void][SessionProbe]::GetWindowThreadProcessId($window, [ref]$owner)
} until ($owner -eq $SupervisorPid)
[UIntPtr]$result = [UIntPtr]::Zero
$sent = [SessionProbe]::SendMessageTimeout($window, 0x11, [UIntPtr]::Zero, [IntPtr]::Zero, 2, 2000, [ref]$result)
if ($sent -eq [IntPtr]::Zero -or $result.ToUInt64() -ne 1) { throw 'QUERYENDSESSION was not accepted' }
$deadline = [DateTime]::UtcNow.AddSeconds(4)
do {
  $intent = @(Read-EngineRecords | Where-Object { $_.type -eq 'engine-intent' -and $_.reason -eq 'QUIT' -and $_.detail -eq 'session_end' })
  if ($intent.Count) { break }
  Start-Sleep -Milliseconds 20
} while ([DateTime]::UtcNow -lt $deadline)
if (-not $intent.Count) { throw 'QUIT/session_end did not begin at QUERYENDSESSION' }
$sent = [SessionProbe]::SendMessageTimeout($window, 0x16, [UIntPtr]::new(1), [IntPtr]::Zero, 2, 12000, [ref]$result)
if ($sent -eq [IntPtr]::Zero) {
  $errorCode = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
  $exists = [SessionProbe]::IsWindow($window)
  # QUERY starts the quit. This conformance process may complete and destroy its
  # window before the polling sender dispatches END. A live timeout is still a failure.
  $completed = $supervisor.WaitForExit(2000)
  if ($errorCode -ne 1400 -or $exists -or -not $completed) {
    throw "ENDSESSION did not complete: error=$errorCode windowExists=$exists processExited=$completed"
  }
  'Supervisor completed the QUERY-triggered quit before ENDSESSION dispatch'
}
$exits = @(Read-EngineRecords | Where-Object { $_.type -eq 'engine-exit' })
if ($exits.Count -ne 1 -or $exits[0].exitClass -ne 'REQUESTED' -or $exits[0].reason -ne 'QUIT' -or $exits[0].detail -ne 'session_end' -or -not $exits[0].jobEmpty) { throw 'ENDSESSION returned without one requested session-end exit and an empty job' }
'PASS: QUERYENDSESSION begins quit; ENDSESSION returns after the recorded exit and empty job'
