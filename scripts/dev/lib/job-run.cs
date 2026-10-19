// A dev tool's run in its own Windows job, as WindowsEngineJob does for the Engine's children.
//
// The host creates an unnamed job whose only limit is KILL_ON_JOB_CLOSE (no breakaway), creates
// the run's root suspended, joins it to the job and only then resumes it, so the kernel adds every
// descendant to the job at its creation. Membership is kernel-tracked; it never follows a parent
// PID. The host holds the only job handle: when the host ends, for any reason, Windows ends every
// member. When the root exits, the host ends what the root left in the job and exits with the
// root's code. The run record names the host, the root and every member seen, each by PID and
// creation time read on a handle that IsProcessInJob confirmed, and lists by identity each member
// the job's termination ended after the root had exited (endedByJob). The host ignores Ctrl+C and
// Ctrl+Break: it outlives its root, and only its stopper or its own parent's job ends it early.
// With newProcessGroup the root starts a new process group, so neither it nor anything it starts
// receives the console's Ctrl+C; the starter then asks the root to quit through its own channel.
// With holdAfterRoot the host does not end what the root left: it records the root's exit (state
// root-exited, rootExitCode) and keeps the job, still recording members, until the job empties by
// itself (state ended; the host exits with the root's code) or its stopper ends the host, which
// closes the job. The starter's own ordered steps then run before the job ends anything.
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public static class JustSearchJobRun {
  const uint CREATE_SUSPENDED = 0x4;
  const uint CREATE_NEW_PROCESS_GROUP = 0x200;
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
  delegate bool ConsoleCtrl(uint type);
  [DllImport("kernel32.dll")]
  static extern bool SetConsoleCtrlHandler(ConsoleCtrl handler, bool add);

  /** Ctrl+C (0) and Ctrl+Break (1) are not the host's to act on; close, logoff and shutdown are. */
  static readonly ConsoleCtrl IgnoreInterrupts = type => type <= 1;

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

  /** The PIDs the job lists as its active members now (nested jobs' members included), or null. */
  static int[] ListedPids(IntPtr job) {
    int capacity = 64;
    for (int round = 0; round < 6; round++) {
      int length = 8 + IntPtr.Size * capacity;
      IntPtr buffer = Marshal.AllocHGlobal(length);
      try {
        int returned;
        if (!QueryInformationJobObject(job, JOB_BASIC_PROCESS_ID_LIST, buffer, length, out returned)) {
          if (Marshal.GetLastWin32Error() == ERROR_MORE_DATA) { capacity *= 4; continue; }
          return null;
        }
        int assigned = Marshal.ReadInt32(buffer, 0), listed = Marshal.ReadInt32(buffer, 4);
        if (assigned > listed) { capacity = assigned + 16; continue; }
        int[] pids = new int[listed];
        for (int i = 0; i < listed; i++) pids[i] = (int)Marshal.ReadIntPtr(buffer, 8 + i * IntPtr.Size).ToInt64();
        return pids;
      } finally { Marshal.FreeHGlobal(buffer); }
    }
    return null;
  }

  /** Adds each current member not yet recorded: opened by its listed PID, kept only if IsProcessInJob. */
  static bool RefreshMembers(IntPtr job, Dictionary<int, long> members, List<int> order) {
    int[] listed = ListedPids(job);
    if (listed == null) return false;
    bool changed = false;
    foreach (int pid in listed) {
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
  }

  static string RecordJson(int hostPid, long hostBirth, int rootPid, long rootBirth, Dictionary<int, long> members,
      List<int> order, string state, string rootExit, List<int> endedByJob = null) {
    StringBuilder b = new StringBuilder();
    b.Append("{\"schema\":\"justsearch-job-run.v1\",\"pid\":").Append(hostPid)
     .Append(",\"creationFileTimeUtc\":\"").Append(hostBirth).Append("\",\"root\":").Append(Identity(rootPid, rootBirth))
     .Append(",\"members\":[");
    for (int i = 0; i < order.Count; i++) { if (i > 0) b.Append(','); b.Append(Identity(order[i], members[order[i]])); }
    b.Append("],\"state\":").Append(Q(state)).Append(",\"rootExitCode\":").Append(rootExit ?? "null");
    if (endedByJob != null) {
      b.Append(",\"endedByJob\":[");
      for (int i = 0; i < endedByJob.Count; i++) { if (i > 0) b.Append(','); b.Append(Identity(endedByJob[i], members[endedByJob[i]])); }
      b.Append(']');
    }
    b.Append('}');
    return b.ToString();
  }

  /** Runs one command line in a new job; returns the root's exit code. */
  public static int Host(string application, string commandLine, string directory, string recordPath) {
    return Host(application, commandLine, directory, recordPath, false);
  }

  public static int Host(string application, string commandLine, string directory, string recordPath, bool newProcessGroup) {
    return Host(application, commandLine, directory, recordPath, newProcessGroup, false);
  }

  public static int Host(string application, string commandLine, string directory, string recordPath, bool newProcessGroup,
      bool holdAfterRoot) {
    SetConsoleCtrlHandler(IgnoreInterrupts, true);
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
        IntPtr.Zero, IntPtr.Zero, true, CREATE_SUSPENDED | (newProcessGroup ? CREATE_NEW_PROCESS_GROUP : 0), IntPtr.Zero,
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
      if (holdAfterRoot) {
        // The root has ended; what it left stays until the starter's stop reaches the job.
        string rootExit = code.ToString();
        WriteRecord(recordPath, RecordJson(hostPid, hostBirth, info.dwProcessId, rootBirth, members, order,
            ActiveProcesses(job) == 0 ? "ended" : "root-exited", rootExit));
        while (ActiveProcesses(job) > 0) {
          Thread.Sleep(50);
          if (RefreshMembers(job, members, order))
            WriteRecord(recordPath, RecordJson(hostPid, hostBirth, info.dwProcessId, rootBirth, members, order, "root-exited", rootExit));
        }
        WriteRecord(recordPath, RecordJson(hostPid, hostBirth, info.dwProcessId, rootBirth, members, order, "ended", rootExit));
        return (int)code;
      }
      // The root has ended: what it left in its job is ended with it, and each such member is listed.
      List<int> leftovers = new List<int>();
      int[] listed = ListedPids(job);
      if (listed != null) foreach (int pid in listed) if (pid != info.dwProcessId && members.ContainsKey(pid)) leftovers.Add(pid);
      TerminateJobObject(job, code);
      for (int i = 0; i < 200 && ActiveProcesses(job) > 0; i++) Thread.Sleep(25);
      WriteRecord(recordPath, RecordJson(hostPid, hostBirth, info.dwProcessId, rootBirth, members, order,
          ActiveProcesses(job) == 0 ? "ended" : "incomplete", code.ToString(), leftovers));
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
