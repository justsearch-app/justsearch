/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import io.justsearch.app.api.runtime.ledger.LedgerTimestamps;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

/**
 * Windows terminator (Spec B §2.3), through FFM on kernel32:
 *
 * <ul>
 *   <li>{@code OpenProcess(PROCESS_TERMINATE | PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE)}
 *       right after spawn, kept until the exit record. The JDK {@link Process} stays reachable until
 *       then, so its own handle pins the PID as well. No reflection into the JDK's handle.
 *   <li>One {@code TerminateProcess(h, JS_CHILD_STOP)}. A failure on a signalled handle is
 *       ALREADY_EXITED (the child ended first, and its own code stands); any other failure is
 *       FAILED with the Windows error, captured by the downcall.
 *   <li>The exit code through {@code Process.exitValue()} ({@code GetExitCodeProcess}); creation
 *       and exit FILETIMEs through {@code GetProcessTimes}.
 * </ul>
 */
final class WindowsProcessTerminator implements ProcessTerminator {
  private static final int PROCESS_TERMINATE = 0x0001;
  private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
  private static final int SYNCHRONIZE = 0x0010_0000;
  private static final int WAIT_OBJECT_0 = 0;
  /** FILETIME ticks between 1601-01-01 and 1970-01-01. */
  private static final long FILETIME_UNIX_EPOCH = 116_444_736_000_000_000L;

  private final MethodHandle openProcess;
  private final MethodHandle terminateProcess;
  private final MethodHandle processTimes;
  private final MethodHandle waitForSingleObject;
  private final MethodHandle closeHandle;
  private final MethodHandle getExitCodeProcess;
  private final MethodHandle queryImageName;
  private final StructLayout stateLayout;
  private final VarHandle lastError;

  private WindowsProcessTerminator(MethodHandle openProcess, MethodHandle terminateProcess,
      MethodHandle processTimes, MethodHandle waitForSingleObject, MethodHandle closeHandle,
      MethodHandle getExitCodeProcess, MethodHandle queryImageName,
      StructLayout stateLayout, VarHandle lastError) {
    this.openProcess = openProcess;
    this.terminateProcess = terminateProcess;
    this.processTimes = processTimes;
    this.waitForSingleObject = waitForSingleObject;
    this.closeHandle = closeHandle;
    this.getExitCodeProcess = getExitCodeProcess;
    this.queryImageName = queryImageName;
    this.stateLayout = stateLayout;
    this.lastError = lastError;
  }

  /** Binds kernel32, or returns null when FFM cannot (the caller falls back to the JDK). */
  static WindowsProcessTerminator load() {
    try {
      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
      Linker.Option capture = Linker.Option.captureCallState("GetLastError");
      StructLayout state = Linker.Option.captureStateLayout();
      VarHandle error = state.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
      return new WindowsProcessTerminator(
          bind(linker, kernel, "OpenProcess", FunctionDescriptor.of(ValueLayout.ADDRESS,
              ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), capture),
          bind(linker, kernel, "TerminateProcess", FunctionDescriptor.of(ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.JAVA_INT), capture),
          bind(linker, kernel, "GetProcessTimes", FunctionDescriptor.of(ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
              ValueLayout.ADDRESS)),
          bind(linker, kernel, "WaitForSingleObject", FunctionDescriptor.of(ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
          bind(linker, kernel, "CloseHandle",
              FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
          bind(linker, kernel, "GetExitCodeProcess", FunctionDescriptor.of(ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
          bind(linker, kernel, "QueryFullProcessImageNameW", FunctionDescriptor.of(ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
          state, error);
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable unavailable) {
      return null;
    }
  }

  private static MethodHandle bind(Linker linker, SymbolLookup kernel, String name,
      FunctionDescriptor descriptor, Linker.Option... options) {
    return linker.downcallHandle(kernel.find(name)
        .orElseThrow(() -> new IllegalStateException("missing kernel32!" + name)), descriptor, options);
  }

  @Override
  public OsProcess open(Process process) throws IOException {
    MemorySegment handle = open(process.pid(),
        PROCESS_TERMINATE | PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE);
    if (handle == null) {
      throw new IOException("OpenProcess failed for child pid " + process.pid());
    }
    return new WindowsProcess(process, handle, times(handle)[0]);
  }

  @Override
  public OsProcess open(ProcessHandle process) throws IOException {
    MemorySegment handle = open(process.pid(),
        PROCESS_TERMINATE | PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE);
    if (handle == null) throw new IOException("OpenProcess failed for predecessor pid " + process.pid());
    return new WindowsProcess(null, handle, times(handle)[0]);
  }

  @Override
  public int ourCode() {
    return TerminateCodes.JS_CHILD_STOP;
  }

  @Override
  public boolean codeDistinctive() {
    return true;
  }

  @Override
  public Optional<String> creationFileTime(long pid) {
    MemorySegment handle = open(pid, PROCESS_QUERY_LIMITED_INFORMATION);
    if (handle == null) return Optional.empty();
    try {
      long creation = times(handle)[0];
      return creation > 0 ? Optional.of(Long.toString(creation)) : Optional.empty();
    } finally {
      close(handle);
    }
  }

  @Override
  public IdentityPresence identityPresence(long pid) {
    if (pid <= 0 || pid > 0xFFFF_FFFFL) return IdentityPresence.UNKNOWN;
    try (Arena call = Arena.ofConfined()) {
      MemorySegment state = call.allocate(stateLayout);
      MemorySegment handle = (MemorySegment) openProcess.invokeExact(state,
          PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, 0, (int) pid);
      if (handle.address() == 0) {
        return (int) lastError.get(state, 0L) == 87 ? IdentityPresence.GONE : IdentityPresence.UNKNOWN;
      }
      try { return signalled(handle) ? IdentityPresence.GONE : IdentityPresence.PRESENT; }
      finally { close(handle); }
    } catch (VirtualMachineError fatal) { throw fatal; }
    catch (Throwable unreadable) { return IdentityPresence.UNKNOWN; }
  }

  /** The handle, or null when the process is gone or cannot be opened. */
  private MemorySegment open(long pid, int access) {
    if (pid <= 0 || pid > 0xFFFF_FFFFL) return null;
    try (Arena call = Arena.ofConfined()) {
      MemorySegment state = call.allocate(stateLayout);
      MemorySegment handle =
          (MemorySegment) openProcess.invokeExact(state, access, 0, (int) pid);
      return handle.address() == 0 ? null : handle;
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable failure) {
      return null;
    }
  }

  /** {creation, exit} FILETIMEs; 0 for a time the call could not read. */
  private long[] times(MemorySegment handle) {
    try (Arena call = Arena.ofConfined()) {
      MemorySegment creation = call.allocate(ValueLayout.JAVA_LONG);
      MemorySegment exit = call.allocate(ValueLayout.JAVA_LONG);
      MemorySegment kernel = call.allocate(ValueLayout.JAVA_LONG);
      MemorySegment user = call.allocate(ValueLayout.JAVA_LONG);
      int ok = (int) processTimes.invokeExact(handle, creation, exit, kernel, user);
      if (ok == 0) return new long[] {0, 0};
      return new long[] {creation.get(ValueLayout.JAVA_LONG, 0), exit.get(ValueLayout.JAVA_LONG, 0)};
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable failure) {
      return new long[] {0, 0};
    }
  }

  private boolean signalled(MemorySegment handle) {
    try {
      return (int) waitForSingleObject.invokeExact(handle, 0) == WAIT_OBJECT_0;
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable failure) {
      return false;
    }
  }

  private void close(MemorySegment handle) {
    try {
      int _ = (int) closeHandle.invokeExact(handle);
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable ignored) {
      // The handle is released with the process at worst.
    }
  }

  /** A FILETIME as an instant; empty for 0 and for anything the ledger reads as unknown. */
  static Optional<Instant> instantOf(long fileTime) {
    if (fileTime <= 0) return Optional.empty();
    long sinceEpoch = fileTime - FILETIME_UNIX_EPOCH;
    Instant instant = Instant.ofEpochSecond(
        Math.floorDiv(sinceEpoch, 10_000_000L), Math.floorMod(sinceEpoch, 10_000_000L) * 100L);
    return instant.isBefore(LedgerTimestamps.EARLIEST_KNOWN) ? Optional.empty() : Optional.of(instant);
  }

  private final class WindowsProcess implements OsProcess {
    private final Process process;
    private final MemorySegment handle;
    private final Optional<String> creationFileTime;
    private boolean closed;

    WindowsProcess(Process process, MemorySegment handle, long creationFileTime) {
      this.process = process;
      this.handle = handle;
      this.creationFileTime =
          creationFileTime > 0 ? Optional.of(Long.toString(creationFileTime)) : Optional.empty();
    }

    @Override
    public Optional<String> creationFileTime() {
      return creationFileTime;
    }

    @Override
    public synchronized Optional<String> executable() {
      if (closed) return Optional.empty();
      try (Arena call = Arena.ofConfined()) {
        MemorySegment buffer = call.allocate(65_536, 2);
        MemorySegment length = call.allocate(ValueLayout.JAVA_INT);
        length.set(ValueLayout.JAVA_INT, 0, 32_768);
        int ok = (int) queryImageName.invokeExact(handle, 0, buffer, length);
        if (ok == 0) return Optional.empty();
        char[] chars = new char[length.get(ValueLayout.JAVA_INT, 0)];
        for (int i = 0; i < chars.length; i++) chars[i] = buffer.getAtIndex(ValueLayout.JAVA_CHAR, i);
        return Optional.of(new String(chars));
      } catch (VirtualMachineError fatal) {
        throw fatal;
      } catch (Throwable unreadable) {
        return Optional.empty();
      }
    }

    @Override
    public synchronized TerminateResult terminate(int code) {
      if (closed) return TerminateResult.ALREADY_EXITED;
      try (Arena call = Arena.ofConfined()) {
        MemorySegment state = call.allocate(stateLayout);
        int ok = (int) terminateProcess.invokeExact(state, handle, code);
        if (ok != 0) return TerminateResult.OK;
        int error = (int) lastError.get(state, 0L);
        return signalled(handle) ? TerminateResult.ALREADY_EXITED : TerminateResult.failed(error);
      } catch (VirtualMachineError fatal) {
        throw fatal;
      } catch (Throwable failure) {
        return TerminateResult.failed(-1);
      }
    }

    @Override
    public boolean awaitExit(Duration max) throws InterruptedException {
      if (process != null) return process.waitFor(Math.max(0, max.toMillis()), TimeUnit.MILLISECONDS);
      long deadline = System.nanoTime() + max.toNanos();
      while (!signalled(handle)) {
        if (System.nanoTime() >= deadline) return false;
        TimeUnit.MILLISECONDS.sleep(Math.min(10, Math.max(1, max.toMillis())));
      }
      return true;
    }

    @Override
    public OptionalInt exitCode() {
      if (process == null) {
        if (!signalled(handle)) return OptionalInt.empty();
        try (Arena call = Arena.ofConfined()) {
          MemorySegment code = call.allocate(ValueLayout.JAVA_INT);
          int ok = (int) getExitCodeProcess.invokeExact(handle, code);
          return ok == 0 ? OptionalInt.empty() : OptionalInt.of(code.get(ValueLayout.JAVA_INT, 0));
        } catch (VirtualMachineError fatal) {
          throw fatal;
        } catch (Throwable failure) {
          return OptionalInt.empty();
        }
      }
      try {
        return OptionalInt.of(process.exitValue());
      } catch (IllegalThreadStateException alive) {
        return OptionalInt.empty();
      }
    }

    @Override
    public synchronized Optional<Instant> exitedAt() {
      if (closed) return Optional.empty();
      return instantOf(times(handle)[1]);
    }

    @Override
    public synchronized void close() {
      if (closed) return;
      closed = true;
      try {
        WindowsProcessTerminator.this.close(handle);
      } finally {
        Reference.reachabilityFence(process);
      }
    }
  }
}
