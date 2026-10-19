// Test-only native helper, compiled into each check's private temporary directory.
using System;
using System.Runtime.InteropServices;
public static class FixtureIdentity {
  [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
  [DllImport("kernel32.dll")] static extern bool GetProcessTimes(IntPtr h, out long birth, out long end, out long kernel, out long user);
  [DllImport("kernel32.dll")] static extern bool TerminateProcess(IntPtr h, uint code);
  [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr h, uint milliseconds);
  [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
  public static int Main(string[] args) {
    int pid; if(args.Length < 1 || !int.TryParse(args[0], out pid) || pid <= 0) return 4;
    IntPtr h=OpenProcess(args.Length == 1 ? 0x1000u : 0x101001u, false, pid);
    if(h == IntPtr.Zero) {
      if(Marshal.GetLastWin32Error() == 87) return 3;
      // An exited process can remain pinned by an observer but reject termination access.
      // Read its identity and exit time through a query handle; never treat denial as gone.
      IntPtr query=OpenProcess(0x1000u,false,pid);
      if(query == IntPtr.Zero) return 4;
      try {
        long birth,end,kernel,user,recorded;
        if(!GetProcessTimes(query,out birth,out end,out kernel,out user) || args.Length < 2
            || !long.TryParse(args[1],out recorded)) return 4;
        return birth != recorded || end != 0 ? 3 : 4;
      } finally { CloseHandle(query); }
    }
    try {
      long birth,end,kernel,user; if(!GetProcessTimes(h,out birth,out end,out kernel,out user)) return 4;
      if(args.Length == 1) { Console.WriteLine(birth); return 0; }
      long recorded; if(!long.TryParse(args[1],out recorded) || recorded != birth) return 3;
      if(end != 0) return 3;
      if(!TerminateProcess(h,1)) return 4;
      return WaitForSingleObject(h,5000) == 0 ? 0 : 4;
    } finally { CloseHandle(h); }
  }
}
