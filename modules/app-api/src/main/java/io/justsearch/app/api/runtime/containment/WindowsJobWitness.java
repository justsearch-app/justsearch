/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import io.justsearch.app.api.runtime.child.LedgerSink;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only job observer. NEW packets are hints to open a handle, never PID identity evidence:
 * the opened process must itself be in this exact job. Handles pin identities until exit.
 * EXIT packets carry no exit code; the retained handle supplies code and FILETIMEs. A timeout
 * also checks signalled handles because Windows does not guarantee job notification delivery.
 * No record ever authorizes a kill. The Engine job and this port live until process death.
 */
final class WindowsJobWitness implements AutoCloseable {
  private static final int NEW_PROCESS = 6;
  private static final int EXIT_PROCESS = 7;
  private static final int ABNORMAL_EXIT_PROCESS = 8;
  private static final int QUERY_AND_SYNCHRONIZE = 0x101000;
  private static final int MAX_PENDING = 4096;
  private final Native calls = new Native();
  private final MemorySegment job;
  private final MemorySegment port;
  private final Map<Long, Held> processes = new HashMap<>(); // observer thread only
  private final Map<Long, String> unproven = new HashMap<>();
  private final ArrayDeque<Event> pending = new ArrayDeque<>();
  private LedgerSink ledger;
  private final java.util.concurrent.locks.ReentrantLock recordingGate =
      new java.util.concurrent.locks.ReentrantLock();
  private EngineIdentity owner;
  private int dropped;
  private boolean writing;
  private volatile boolean recordingFinished;
  private volatile int liveHelpers;
  private volatile long observedThrough;
  private volatile boolean closed;

  private static final class Held {
    private final String id;
    private final MemorySegment handle;
    private final String birth;
    private final String executable;
    private final boolean engineConsole;
    private final Long parentPid;
    private final String parentBirth;
    private long exitSeenAt;

    private Held(String id, MemorySegment handle, String birth, String executable,
        boolean engineConsole, Long parentPid, String parentBirth) {
      this.id = id;
      this.handle = handle;
      this.birth = birth;
      this.executable = executable;
      this.engineConsole = engineConsole;
      this.parentPid = parentPid;
      this.parentBirth = parentBirth;
    }

    String id() { return id; }
    MemorySegment handle() { return handle; }
    String birth() { return birth; }
    boolean engineConsole() { return engineConsole; }
  }

  /** How long an exit waits for its owner's own record before the witness classifies it. */
  static final long OWNER_WAIT_NANOS = 2_000_000_000L;
  /** Every exit waits this long, so an owner registering a just-started identity is not missed. */
  static final long REGISTRATION_WAIT_NANOS = 250_000_000L;
  private record Event(LedgerRecordType type, Map<String, Object> fields) {}

  private WindowsJobWitness(MemorySegment job) throws Throwable {
    this.job = job;
    port = (MemorySegment) calls.createPort.invokeExact(MemorySegment.ofAddress(-1L),
        MemorySegment.NULL, 0L, 1);
    if (port.address() == 0) throw new IllegalStateException("CreateIoCompletionPort failed");
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment association = arena.allocate(16, 8);
      association.set(ValueLayout.ADDRESS, 0, job);
      association.set(ValueLayout.ADDRESS, 8, port);
      if ((int) calls.configure.invokeExact(job, 7, association, 16) == 0) {
        closeHandle(port);
        throw new IllegalStateException("JobObjectAssociateCompletionPortInformation failed");
      }
    }
  }

  static WindowsJobWitness associate(MemorySegment job) throws Throwable {
    return new WindowsJobWitness(job);
  }

  void start() {
    Thread.ofPlatform().daemon().name("engine-job-witness").start(this::observe);
  }

  synchronized void attach(LedgerSink sink, EngineIdentity identity) {
    if (ledger != null) return;
    ledger = sink;
    owner = identity;
    Thread.ofPlatform().daemon().name("engine-job-ledger").start(this::write);
    notifyAll();
  }

  private void observe() {
    try (Arena arena = Arena.ofConfined()) {
      witnessEngineConsole();
      MemorySegment message = arena.allocate(ValueLayout.JAVA_INT);
      MemorySegment key = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment value = arena.allocate(ValueLayout.ADDRESS);
      while (!closed) {
        int ok = (int) calls.dequeue.invokeExact(port, message, key, value, 25);
        if (ok != 0 && key.get(ValueLayout.ADDRESS, 0).address() == job.address()) {
          long pid = value.get(ValueLayout.ADDRESS, 0).address();
          int kind = message.get(ValueLayout.JAVA_INT, 0);
          if (kind == NEW_PROCESS) entered(pid);
          else if (kind == EXIT_PROCESS || kind == ABNORMAL_EXIT_PROCESS) {
            if (processes.containsKey(pid)) exited(pid);
            else unprovenExit(pid);
          }
        }
        for (long pid : processes.keySet().toArray(Long[]::new)) exited(pid);
        observedThrough = System.nanoTime();
      }
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable failure) {
      enqueue(LedgerRecordType.LEDGER_GAP, Map.of("reason", "WRITE_FAILED", "count", 1,
          "since", Instant.now(), "source", "job_witness_failed"));
    } finally {
      processes.values().forEach(h -> closeHandle(h.handle()));
      processes.clear();
    }
  }

  private void entered(long pid) throws Throwable {
    entered(pid, false);
  }

  /** Console hosts can predate AssignProcessToJobObject. A snapshot is only a discovery hint. */
  private void witnessEngineConsole() throws Throwable {
    MemorySegment snapshot = (MemorySegment) calls.snapshot.invokeExact(2, 0);
    if (snapshot.address() == -1L) return;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment entry = arena.allocate(568, 8); // PROCESSENTRY32W, Windows x64
      entry.set(ValueLayout.JAVA_INT, 0, 568);
      int found = (int) calls.first.invokeExact(snapshot, entry);
      while (found != 0) {
        if (Integer.toUnsignedLong(entry.get(ValueLayout.JAVA_INT, 32)) == ProcessHandle.current().pid())
          entered(Integer.toUnsignedLong(entry.get(ValueLayout.JAVA_INT, 8)), true);
        found = (int) calls.next.invokeExact(snapshot, entry);
      }
    } finally { closeHandle(snapshot); }
  }

  private void entered(long pid, boolean consoleCandidate) throws Throwable {
    if (pid <= 0 || pid > 0xFFFF_FFFFL || processes.containsKey(pid)) return;
    MemorySegment handle = (MemorySegment) calls.open.invokeExact(QUERY_AND_SYNCHRONIZE, 0, (int) pid);
    String id = "j-" + UUID.randomUUID();
    String birth = null;
    String executable = null;
    boolean member = false;
    if (handle.address() != 0) {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment inside = arena.allocate(ValueLayout.JAVA_INT);
        member = (int) calls.inJob.invokeExact(handle, job, inside) != 0
            && inside.get(ValueLayout.JAVA_INT, 0) != 0;
        // A recycled outside PID cannot be attached to this NEW packet.
        if (member || consoleCandidate) {
          long created = times(handle)[0];
          birth = created > 0 ? Long.toString(created) : null;
          MemorySegment size = arena.allocate(ValueLayout.JAVA_INT);
          size.set(ValueLayout.JAVA_INT, 0, 1024);
          MemorySegment name = arena.allocate(2048, 2);
          if ((int) calls.image.invokeExact(handle, 0, name, size) != 0) {
            char[] chars = new char[size.get(ValueLayout.JAVA_INT, 0)];
            for (int i = 0; i < chars.length; i++) chars[i] = name.get(ValueLayout.JAVA_CHAR, i * 2L);
            executable = new String(chars);
          }
        }
      }
    }
    boolean awaited = false;
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("witnessId", id);
    fields.put("pid", pid);
    fields.put("creationFileTime", birth);
    fields.put("executable", executable);
    if ((member || consoleCandidate) && handle.address() != 0 && (int) calls.wait.invokeExact(handle, 0) == 258) {
      // Read the parent from this held process, then pin/query that parent's own identity.
      // This is a read-only console binding, never authority to terminate an ancestor.
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment basic = arena.allocate(48, 8);
        if ((int) calls.basic.invokeExact(handle, 0, basic, 48, MemorySegment.NULL) == 0) {
          long parentPid = basic.get(ValueLayout.JAVA_LONG, 40);
          MemorySegment parent = (MemorySegment) calls.open.invokeExact(QUERY_AND_SYNCHRONIZE, 0, (int) parentPid);
          if (parent.address() != 0) {
            try {
              long parentBirth = times(parent)[0];
              long parentEnd = times(parent)[1];
              if ((int) calls.wait.invokeExact(handle, 0) == 258
                  && parentBirth > 0 && birth != null && parentBirth <= Long.parseLong(birth)
                  && (parentEnd == 0 || Long.parseLong(birth) <= parentEnd)) {
                fields.put("parentPid", parentPid);
                fields.put("parentCreationFileTime", Long.toString(parentBirth));
              }
            } finally { closeHandle(parent); }
          }
        }
      }
    }
    boolean engineConsole = consoleCandidate && executable != null
        && executable.toLowerCase(java.util.Locale.ROOT).endsWith("\\conhost.exe")
        && Long.valueOf(ProcessHandle.current().pid()).equals(fields.get("parentPid"));
    if (consoleCandidate && !engineConsole) {
      if (handle.address() != 0) closeHandle(handle);
      return; // An unrelated or reused snapshot PID is never an owned process.
    }
    if (engineConsole) fields.put("consoleClient", true);
    fields.put("contained", member ? true : null);
    fields.put("observedAt", Instant.now());
    // Owner receipts, read alongside these facts, supply classification.
    fields.put("awaited", awaited);
    fields.put("expectedCode", awaited ? 0 : null);
    fields.put("reason", awaited ? "HELPER_COMPLETION" : null);
    enqueue(LedgerRecordType.JOB_PROCESS_START, fields);
    if (member || engineConsole) {
      processes.put(pid, new Held(id, handle, birth, executable, engineConsole,
          (Long) fields.get("parentPid"), (String) fields.get("parentCreationFileTime")));
      // The Engine's own console cannot end before the Engine. Witness it without making
      // a clean shutdown consume the helper drain deadline waiting for its own console.
      if (pid != ProcessHandle.current().pid() && !engineConsole) liveHelpers++;
    }
    else {
      unproven.put(pid, id);
      if (handle.address() != 0) closeHandle(handle);
    }
  }

  private void unprovenExit(long pid) {
    String id = unproven.remove(pid);
    if (id == null) return;
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("witnessId", id);
    fields.put("pid", pid);
    fields.put("creationFileTime", null);
    fields.put("code", null);
    fields.put("exitClass", "UNKNOWN");
    fields.put("reason", null);
    fields.put("exitedAt", null);
    fields.put("observedAt", Instant.now());
    fields.put("forced", false);
    enqueue(LedgerRecordType.JOB_PROCESS_EXIT, fields);
  }

  private void exited(long pid) throws Throwable {
    Held held = processes.get(pid);
    if (held == null || (int) calls.wait.invokeExact(held.handle(), 0) != 0) return;
    boolean waiting = false;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment result = arena.allocate(ValueLayout.JAVA_INT);
      Integer code = (int) calls.code.invokeExact(held.handle(), result) == 0
          ? null : result.get(ValueLayout.JAVA_INT, 0);
      long exit = times(held.handle())[1];
      Instant exitedAt = instantOf(exit);
      long now = System.nanoTime();
      if (held.exitSeenAt == 0) held.exitSeenAt = now;
      // Membership is evidence, never owner intent: the class and reason come from the intent an
      // owner recorded before this exit, and only an exit with no recorded intent is UNEXPECTED.
      var resolution = resolve(pid, held, code, exitedAt, now - held.exitSeenAt);
      if (resolution == null) { waiting = true; return; } // the owner may still record it; keep the handle
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("witnessId", held.id());
      fields.put("pid", pid);
      fields.put("creationFileTime", held.birth());
      fields.put("code", code);
      fields.put("exitClass", code == null ? "UNKNOWN" : resolution.exitClass());
      fields.put("reason", code == null ? "native exit code unavailable" : resolution.reason());
      fields.put("intentBasis", resolution.basis());
      fields.put("exitedAt", exitedAt == null ? null : exitedAt.toString());
      fields.put("observedAt", Instant.now());
      fields.put("forced", false);
      enqueue(LedgerRecordType.JOB_PROCESS_EXIT, fields);
    } finally {
      if (!waiting) {
        processes.remove(pid);
        if (pid != ProcessHandle.current().pid() && !held.engineConsole()) liveHelpers--;
        closeHandle(held.handle());
      }
    }
  }

  /** Null while the owner may still record this exit. */
  private static io.justsearch.app.api.runtime.child.ProcessIntents.Resolution resolve(
      long pid, Held held, Integer code, Instant exitedAt, long waitedNanos) {
    boolean ownerWaitOver = waitedNanos >= OWNER_WAIT_NANOS;
    java.util.Optional<io.justsearch.app.api.runtime.child.ProcessIntents.Resolution> found;
    boolean console = held.executable != null
        && held.executable.toLowerCase(java.util.Locale.ROOT).endsWith("\\conhost.exe") && held.parentPid != null;
    if (console && !held.engineConsole) {
      found = io.justsearch.app.api.runtime.child.ProcessIntents.resolveConsole(
          held.parentPid, held.parentBirth, ownerWaitOver);
    } else {
      found = io.justsearch.app.api.runtime.child.ProcessIntents.resolve(pid, held.birth,
          held.executable, instantOf(held.birth == null ? 0 : Long.parseLong(held.birth)), exitedAt, ownerWaitOver);
    }
    if (found.isEmpty()) return null;
    var resolution = found.get();
    if ("none".equals(resolution.basis()) && waitedNanos < REGISTRATION_WAIT_NANOS) return null;
    if (code != null && io.justsearch.app.api.runtime.child.OwnedHelper.crashCode(code)
        && "REQUESTED".equals(resolution.exitClass())) {
      return new io.justsearch.app.api.runtime.child.ProcessIntents.Resolution("UNEXPECTED",
          "crash exit code after: " + resolution.reason(), resolution.basis());
    }
    return resolution;
  }

  private long[] times(MemorySegment process) throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment values = arena.allocate(32, 8);
      int ok = (int) calls.times.invokeExact(process, values, values.asSlice(8),
          values.asSlice(16), values.asSlice(24));
      return ok == 0 ? new long[] {0, 0}
          : new long[] {values.get(ValueLayout.JAVA_LONG, 0), values.get(ValueLayout.JAVA_LONG, 8)};
    }
  }

  private static Instant instantOf(long ticks) {
    if (ticks < 126_227_808_000_000_000L) return null; // 2001 rule, not a manufactured exit
    long unix = ticks - 116_444_736_000_000_000L;
    return Instant.ofEpochSecond(unix / 10_000_000L, (unix % 10_000_000L) * 100L);
  }

  private synchronized void enqueue(LedgerRecordType type, Map<String, Object> fields) {
    if (recordingFinished) return;
    if (pending.size() >= MAX_PENDING) dropped++;
    else pending.add(new Event(type, fields));
    notifyAll();
  }

  private void write() {
    while (!closed) {
      Event event;
      synchronized (this) {
        while (pending.isEmpty() && !closed) {
          try { wait(); } catch (InterruptedException interrupted) { return; }
        }
        if (closed) return;
        if (dropped > 0) {
          event = new Event(LedgerRecordType.LEDGER_GAP, new LinkedHashMap<>(Map.of(
              "reason", "WRITE_FAILED", "count", dropped, "since", Instant.now(), "source", "job_witness_overflow")));
          dropped = 0;
        } else event = pending.remove();
        writing = true;
      }
      Map<String, Object> fields = new LinkedHashMap<>(event.fields());
      fields.put("owner", Map.of("pid", owner.pid(), "creationFileTime", owner.creationFileTime(),
          "instanceId", owner.instanceId()));
      fields.put("incarnation", io.justsearch.app.api.runtime.RuntimeManifest.IncarnationInfo.parse(
          io.justsearch.configuration.SystemAccess.rawEnvVar(
              io.justsearch.app.api.runtime.RuntimeManifest.IncarnationInfo.ENVIRONMENT)));
      try {
        recordingGate.lock();
        try {
          if (!recordingFinished) ledger.append(event.type(), fields);
        } finally { recordingGate.unlock(); }
      } finally {
        synchronized (this) { writing = false; notifyAll(); }
      }
    }
  }

  void finish(java.time.Duration max) {
    long started = System.nanoTime();
    long allowance = Math.max(0, max.toNanos());
    long deadline = started + allowance;
    // 500 forced appends on Windows: measured p99 3.4-9.8 ms. Keep 150 ms for an in-flight
    // append, including a 100 ms storage stall, without extending the total allowance.
    long drainDeadline = deadline - Math.min(allowance, 150_000_000L);
    synchronized (this) {
      if (ledger == null) return;
      while (!recordingFinished && (observedThrough < started + 50_000_000L
          || liveHelpers > 0 || writing || !pending.isEmpty())) {
        long left = drainDeadline - System.nanoTime();
        if (left <= 0) break;
        try { wait(Math.max(1, Math.min(25, left / 1_000_000L))); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
      }
      // Refuse future writes first. An append already on disk must finish before ownership
      // can be released, even when another caller retries this fence after a timeout.
      recordingFinished = true;
      pending.clear();
    }
    try {
      if (!recordingGate.tryLock(Math.max(0, deadline - System.nanoTime()),
          java.util.concurrent.TimeUnit.NANOSECONDS)) {
        throw new IllegalStateException("Exit witness storage is still active; retain the instance lock");
      }
      recordingGate.unlock();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Exit witness fence interrupted; retain the instance lock", interrupted);
    }
  }

  private void closeHandle(MemorySegment handle) {
    try { int ignored = (int) calls.close.invokeExact(handle); }
    catch (Throwable ignored) { /* Process death releases the handle at worst. */ }
  }

  @Override public synchronized void close() {
    closed = true;
    notifyAll();
    closeHandle(port);
  }

  private static final class Native {
    private final Linker linker = Linker.nativeLinker();
    private final SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
    private final SymbolLookup ntdll = SymbolLookup.libraryLookup("ntdll", Arena.global());
    private final MethodHandle basic = linker.downcallHandle(ntdll.find("NtQueryInformationProcess").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private final MethodHandle createPort = bind("CreateIoCompletionPort", ValueLayout.ADDRESS,
        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);
    private final MethodHandle configure = bind("SetInformationJobObject", ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
    private final MethodHandle dequeue = bind("GetQueuedCompletionStatus", ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
    private final MethodHandle open = bind("OpenProcess", ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    private final MethodHandle inJob = bind("IsProcessInJob", ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private final MethodHandle times = bind("GetProcessTimes", ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private final MethodHandle image = bind("QueryFullProcessImageNameW", ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private final MethodHandle wait = bind("WaitForSingleObject", ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
    private final MethodHandle code = bind("GetExitCodeProcess", ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private final MethodHandle close = bind("CloseHandle", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
    private final MethodHandle snapshot = bind("CreateToolhelp32Snapshot", ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    private final MethodHandle first = bind("Process32FirstW", ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private final MethodHandle next = bind("Process32NextW", ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    private MethodHandle bind(String name, java.lang.foreign.MemoryLayout result,
        java.lang.foreign.MemoryLayout... args) {
      return linker.downcallHandle(kernel.find(name).orElseThrow(), FunctionDescriptor.of(result, args));
    }
  }
}
