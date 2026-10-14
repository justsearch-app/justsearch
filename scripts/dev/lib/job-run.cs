// A dev tool's run in its own Windows job, as WindowsEngineJob does for the Engine's children.
//
// The host creates an unnamed job whose only limit is KILL_ON_JOB_CLOSE (no breakaway), creates
// the run's root suspended, joins it to the job and only then resumes it, so the kernel adds every
// descendant to the job at its creation. Membership is kernel-tracked; it never follows a parent
// PID. The host holds the only job handle: when the host ends, for any reason, Windows ends every
// member. When the root exits, the host ends what the root left in the job and exits with the
// root's code. The run record names the host, the root and every member seen, each by PID and
// creation time read on a handle that IsProcessInJob confirmed.
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public static class JustSearchJobRun {
  const uint CREATE_SUSPENDED = 0x4;
  const int STARTF_USESTDHANDLES = 0x100;
  const uint KILL_ON_JOB_CLOSE = 0x2000;
  const uint PROCESS_TERMINATE = 0x1;
  const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
  const uint SYNCHRONIZE = 0x100000;
  const uint STILL_ACTIVE = 259;
  const int ERROR_INVALID_PARAMETER = 87;
  const int ERROR_MORE_DATA = 234;
  const int JOB_BASIC_ACCOUNTING = 1;
  const int JOB_BASIC_PROCESS_ID_LIST = 3;
  const int JOB_EXTENDED_LIMIT = 9;

  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  struct STARTUPINFO {
    public int cb; public string lpReserved; public string lpDesktop; public string lpTitle;
    public int dwX; public int dwY; public int dwXSize; public int dwYSize;
    public int dwXCountChars; public int dwYCountChars; public int dwFillAttribute; public int dwFlags;
    public short wShowWindow; public short cbReserved2; public IntPtr lpReserved2;
    public IntPtr hStdInput; public IntPtr hStdOutput; public IntPtr hStdError;
  }
  [StructLayout(LayoutKind.Sequential)]
  struct PROCESS_INFORMATION { public IntPtr hProcess; public IntPtr hThread; public int dwProcessId; public int dwThreadId; }
  [StructLayout(LayoutKind.Sequential)]
  struct BASIC_LIMIT {
    public long PerProcessUserTimeLimit; public long PerJobUserTimeLimit; public uint LimitFlags;
    public UIntPtr MinimumWorkingSetSize; public UIntPtr MaximumWorkingSetSize; public uint ActiveProcessLimit;
    public UIntPtr Affinity; public uint PriorityClass; public uint SchedulingClass;
  }
  [StructLayout(LayoutKind.Sequential)]
  struct IO_COUNTERS { public ulong R; public ulong W; public ulong O; public ulong RB; public ulong WB; public ulong OB; }
  [StructLayout(LayoutKind.Sequential)]
  struct EXTENDED_LIMIT {
    public BASIC_LIMIT Basic; public IO_COUNTERS Io; public UIntPtr ProcessMemoryLimit; public UIntPtr JobMemoryLimit;
    public UIntPtr PeakProcessMemoryUsed; public UIntPtr PeakJobMemoryUsed;
  }

  [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
  static extern IntPtr CreateJobObjectW(IntPtr attributes, string name);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool SetInformationJobObject(IntPtr job, int infoClass, ref EXTENDED_LIMIT info, int length);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool QueryInformationJobObject(IntPtr job, int infoClass, IntPtr info, int length, out int returned);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool TerminateJobObject(IntPtr job, uint code);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);
  [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
  static extern bool CreateProcessW(string application, StringBuilder commandLine, IntPtr processAttributes,
      IntPtr threadAttributes, bool inheritHandles, uint flags, IntPtr environment, string directory,
      ref STARTUPINFO startup, out PROCESS_INFORMATION info);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern uint ResumeThread(IntPtr thread);
  [DllImport("kernel32.dll")]
  static extern IntPtr GetStdHandle(int which);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool GetProcessTimes(IntPtr process, out long creation, out long exit, out long kernel, out long user);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool GetExitCodeProcess(IntPtr process, out uint code);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool TerminateProcess(IntPtr process, uint code);
  [DllImport("kernel32.dll")]
  static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
  [DllImport("kernel32.dll")]
  static extern IntPtr GetCurrentProcess();
  [DllImport("kernel32.dll")]
  static extern bool CloseHandle(IntPtr handle);

  static string Q(string value) {
    if (value == null) return "null";
    StringBuilder b = new StringBuilder("\"");
    foreach (char c in value) {
      if (c == '"' || c == '\\') { b.Append('\\'); b.Append(c); }
      else if (c < ' ') b.Append(string.Format("\\u{0:x4}", (int)c));
      else b.Append(c);
    }
    return b.Append('"').ToString();
  }

  static string Identity(int pid, long birth) {
    return "{\"pid\":" + pid + ",\"creationFileTimeUtc\":\"" + birth + "\"}";
  }

  static void WriteRecord(string path, string json) {
    string temp = path + ".tmp-" + System.Diagnostics.Process.GetCurrentProcess().Id;
    for (int attempt = 0; attempt < 20; attempt++) {
      try {
        File.WriteAllText(temp, json, new UTF8Encoding(false));
        if (File.Exists(path)) File.Replace(temp, path, null); else File.Move(temp, path);
        return;
      } catch (IOException) { Thread.Sleep(10); }
      catch (UnauthorizedAccessException) { Thread.Sleep(10); }
    }
    throw new IOException("the run record could not be written: " + path);
  }

  static long BirthOf(IntPtr process) {
    long creation, exit, kernel, user;
    return GetProcessTimes(process, out creation, out exit, out kernel, out user) ? creation : 0;
  }

  static int ActiveProcesses(IntPtr job) {
    IntPtr buffer = Marshal.AllocHGlobal(64);
    try {
      int returned;
      if (!QueryInformationJobObject(job, JOB_BASIC_ACCOUNTING, buffer, 48, out returned)) return -1;
      return Marshal.ReadInt32(buffer, 40);
    } finally { Marshal.FreeHGlobal(buffer); }
  }

  /** Adds each current member not yet recorded: opened by its listed PID, kept only if IsProcessInJob. */
  static bool RefreshMembers(IntPtr job, Dictionary<int, long> members, List<int> order) {
    int capacity = 64;
    for (int round = 0; round < 6; round++) {
      int length = 8 + IntPtr.Size * capacity;
      IntPtr buffer = Marshal.AllocHGlobal(length);
      try {
        int returned;
        if (!QueryInformationJobObject(job, JOB_BASIC_PROCESS_ID_LIST, buffer, length, out returned)) {
          if (Marshal.GetLastWin32Error() == ERROR_MORE_DATA) { capacity *= 4; continue; }
          return false;
        }
        int assigned = Marshal.ReadInt32(buffer, 0), listed = Marshal.ReadInt32(buffer, 4);
        if (assigned > listed) { capacity = assigned + 16; continue; }
        bool changed = false;
        for (int i = 0; i < listed; i++) {
          int pid = (int)Marshal.ReadIntPtr(buffer, 8 + i * IntPtr.Size).ToInt64();
          if (pid <= 0 || members.ContainsKey(pid)) continue;
          IntPtr handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
          if (handle == IntPtr.Zero) continue;
          try {
            bool inJob;
            if (!IsProcessInJob(handle, job, out inJob) || !inJob) continue;
            long birth = BirthOf(handle);
            if (birth == 0) continue;
            members[pid] = birth; order.Add(pid); changed = true;
          } finally { CloseHandle(handle); }
        }
        return changed;
      } finally { Marshal.FreeHGlobal(buffer); }
    }
    return false;
  }

  static string RecordJson(int hostPid, long hostBirth, int rootPid, long rootBirth, Dictionary<int, long> members,
      List<int> order, string state, string rootExit) {
    StringBuilder b = new StringBuilder();
    b.Append("{\"schema\":\"justsearch-job-run.v1\",\"pid\":").Append(hostPid)
     .Append(",\"creationFileTimeUtc\":\"").Append(hostBirth).Append("\",\"root\":").Append(Identity(rootPid, rootBirth))
     .Append(",\"members\":[");
    for (int i = 0; i < order.Count; i++) { if (i > 0) b.Append(','); b.Append(Identity(order[i], members[order[i]])); }
    b.Append("],\"state\":").Append(Q(state)).Append(",\"rootExitCode\":").Append(rootExit ?? "null").Append('}');
    return b.ToString();
  }

  /** Runs one command line in a new job; returns the root's exit code. */
  public static int Host(string application, string commandLine, string directory, string recordPath) {
    IntPtr job = CreateJobObjectW(IntPtr.Zero, null);
    if (job == IntPtr.Zero) throw new InvalidOperationException("CreateJobObjectW failed: " + Marshal.GetLastWin32Error());
    EXTENDED_LIMIT limits = new EXTENDED_LIMIT();
    limits.Basic.LimitFlags = KILL_ON_JOB_CLOSE;
    if (!SetInformationJobObject(job, JOB_EXTENDED_LIMIT, ref limits, Marshal.SizeOf(typeof(EXTENDED_LIMIT))))
      throw new InvalidOperationException("SetInformationJobObject failed: " + Marshal.GetLastWin32Error());
    STARTUPINFO startup = new STARTUPINFO();
    startup.cb = Marshal.SizeOf(typeof(STARTUPINFO));
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdInput = GetStdHandle(-10); startup.hStdOutput = GetStdHandle(-11); startup.hStdError = GetStdHandle(-12);
    PROCESS_INFORMATION info;
    if (!CreateProcessW(string.IsNullOrEmpty(application) ? null : application, new StringBuilder(commandLine),
        IntPtr.Zero, IntPtr.Zero, true, CREATE_SUSPENDED, IntPtr.Zero,
        string.IsNullOrEmpty(directory) ? null : directory, ref startup, out info))
      throw new InvalidOperationException("CreateProcessW failed: " + Marshal.GetLastWin32Error());
    try {
      if (!AssignProcessToJobObject(job, info.hProcess)) {
        int error = Marshal.GetLastWin32Error();
        TerminateProcess(info.hProcess, 1);
        throw new InvalidOperationException("AssignProcessToJobObject failed: " + error);
      }
      int hostPid = System.Diagnostics.Process.GetCurrentProcess().Id;
      long hostBirth = BirthOf(GetCurrentProcess());
      long rootBirth = BirthOf(info.hProcess);
      Dictionary<int, long> members = new Dictionary<int, long>();
      List<int> order = new List<int>();
      members[info.dwProcessId] = rootBirth; order.Add(info.dwProcessId);
      WriteRecord(recordPath, RecordJson(hostPid, hostBirth, info.dwProcessId, rootBirth, members, order, "running", null));
      ResumeThread(info.hThread);
      while (WaitForSingleObject(info.hProcess, 50) != 0) {
        if (RefreshMembers(job, members, order))
          WriteRecord(recordPath, RecordJson(hostPid, hostBirth, info.dwProcessId, rootBirth, members, order, "running", null));
      }
      uint code;
      if (!GetExitCodeProcess(info.hProcess, out code)) code = 1;
      RefreshMembers(job, members, order);
      // The root has ended: what it left in its job is ended with it.
      TerminateJobObject(job, code);
      for (int i = 0; i < 200 && ActiveProcesses(job) > 0; i++) Thread.Sleep(25);
      WriteRecord(recordPath, RecordJson(hostPid, hostBirth, info.dwProcessId, rootBirth, members, order,
          ActiveProcesses(job) == 0 ? "ended" : "incomplete", code.ToString()));
      return (int)code;
    } finally {
      CloseHandle(info.hThread); CloseHandle(info.hProcess); CloseHandle(job);
    }
  }

  /**
   * Ends the recorded host when the handle shows its recorded birth: its job handle closes and the
   * kernel ends every member. Returns terminated, gone (exited or the PID names another birth) or failed.
   */
  public static string EndHost(int pid, long birth, uint code, long birthTick, int waitMs) {
    IntPtr handle = OpenProcess(PROCESS_TERMINATE | PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, false, pid);
    if (handle == IntPtr.Zero) return Marshal.GetLastWin32Error() == ERROR_INVALID_PARAMETER ? "gone" : "failed";
    try {
      long actual = BirthOf(handle);
      if (actual == 0) return "failed";
      if (actual - actual % birthTick != birth - birth % birthTick) return "gone";
      uint exit;
      if (GetExitCodeProcess(handle, out exit) && exit != STILL_ACTIVE) return "gone";
      if (!TerminateProcess(handle, code)) return "failed";
      return WaitForSingleObject(handle, (uint)waitMs) == 0 ? "terminated" : "failed";
    } finally { CloseHandle(handle); }
  }

  /** Waits until every recorded identity has ended; returns the ones still running (or unreadable). */
  public static string[] Survivors(int[] pids, long[] births, long birthTick, int waitMs) {
    List<string> survivors = new List<string>();
    DateTime end = DateTime.UtcNow.AddMilliseconds(waitMs);
    for (int i = 0; i < pids.Length; i++) {
      IntPtr handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, false, pids[i]);
      if (handle == IntPtr.Zero) {
        if (Marshal.GetLastWin32Error() != ERROR_INVALID_PARAMETER) survivors.Add(Identity(pids[i], births[i]));
        continue;
      }
      try {
        long actual = BirthOf(handle);
        if (actual == 0) { survivors.Add(Identity(pids[i], births[i])); continue; }
        if (actual - actual % birthTick != births[i] - births[i] % birthTick) continue;
        int remaining = (int)Math.Max(0, (end - DateTime.UtcNow).TotalMilliseconds);
        if (WaitForSingleObject(handle, (uint)remaining) != 0) survivors.Add(Identity(pids[i], births[i]));
      } finally { CloseHandle(handle); }
    }
    return survivors.ToArray();
  }
}
