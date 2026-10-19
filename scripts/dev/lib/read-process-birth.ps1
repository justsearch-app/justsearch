# Read only. The spawn caller blocks the JS event loop and retains its original child handle,
# so Windows cannot recycle that PID while this helper opens and queries it, even after exit.
[CmdletBinding()]
param([Parameter(Mandatory=$true)][Alias('Pid')][int]$TargetPid)
$ErrorActionPreference = 'Stop'
Add-Type -Namespace JustSearchDev -Name BirthNative -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError=true)]
public static extern System.IntPtr OpenProcess(uint access, bool inherit, int pid);
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError=true)]
public static extern bool GetProcessTimes(System.IntPtr process, out long creation, out long exit, out long kernel, out long user);
[System.Runtime.InteropServices.DllImport("kernel32.dll")]
public static extern bool CloseHandle(System.IntPtr handle);
'@
$handle = [JustSearchDev.BirthNative]::OpenProcess(0x1000, $false, $TargetPid)
if ($handle -eq [IntPtr]::Zero) { exit 3 }
try {
  [long]$birth=0; [long]$ended=0; [long]$kernel=0; [long]$user=0
  if (-not [JustSearchDev.BirthNative]::GetProcessTimes($handle,[ref]$birth,[ref]$ended,[ref]$kernel,[ref]$user)) { exit 4 }
  [Console]::Out.WriteLine($birth.ToString())
} finally { [void][JustSearchDev.BirthNative]::CloseHandle($handle) }
