/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

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
import java.util.Optional;

/**
 * The Engine's own Windows job: created with {@code CreateJobObjectW(NULL, NULL)}, limited to
 * exactly {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}, and joined by the Engine process itself.
 *
 * <ul>
 *   <li><b>Exactly 0x2000.</b> No breakaway ({@code 0x800}, {@code 0x1000}), so no child can leave
 *       the job, including under Node's job, which allows silent breakaway. No {@code
 *       DIE_ON_UNHANDLED_EXCEPTION} ({@code 0x400}), so Windows Error Reporting still dumps a
 *       crashing Engine.
 *   <li><b>The handle is never closed.</b> Closing it would end the Engine with its children. It
 *       lives in a global-arena cell for the life of the process; process death closes it, and that
 *       is what ends the children. The handle is not inheritable (no security attributes), because
 *       an inherited copy in a child would keep the job open after the Engine dies. No API exposes
 *       it, and Engine code never calls {@code TerminateJobObject} on it.
 *   <li><b>{@code GetLastError} is captured by the downcall</b> ({@code captureCallState}), so the
 *       JVM cannot overwrite it between the failing call and the read.
 * </ul>
 */
public final class WindowsEngineJob implements EngineContainment {
  /** {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}, and the only limit this job sets. */
  static final int KILL_ON_JOB_CLOSE = 0x2000;
  /** {@code JobObjectExtendedLimitInformation}. */
  private static final int EXTENDED_LIMIT_INFORMATION_CLASS = 9;
  /** Win64 {@code JOBOBJECT_EXTENDED_LIMIT_INFORMATION}: basic limits 64, I/O counters 48, 4 SIZE_T. */
  private static final int EXTENDED_LIMIT_INFORMATION_SIZE = 144;
  /** {@code BasicLimitInformation.LimitFlags} follows two {@code LARGE_INTEGER}s. */
  private static final int LIMIT_FLAGS_OFFSET = 16;

  /** The job handle, held for the life of the process and never closed. */
  private static final MemorySegment JOB_HANDLE = Arena.global().allocate(ValueLayout.ADDRESS);

  private final Status status;
  private final Optional<String> reason;
  private WindowsJobWitness witness;
  private String witnessFailure;

  private WindowsEngineJob(Status status, Optional<String> reason) {
    this.status = status;
    this.reason = reason;
  }

  /** Creates the job and joins it. Returns UNAVAILABLE with {@code <call>:<error>} on failure. */
  static WindowsEngineJob install() {
    try {
      return createAndJoin();
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable failure) {
      return unavailable(failure.getClass().getSimpleName());
    }
  }

  private static WindowsEngineJob createAndJoin() throws Throwable {
    Linker linker = Linker.nativeLinker();
    SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
    Linker.Option captureLastError = Linker.Option.captureCallState("GetLastError");
    StructLayout stateLayout = Linker.Option.captureStateLayout();
    VarHandle lastError =
        stateLayout.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
    MethodHandle create = bind(linker, kernel, "CreateJobObjectW",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        captureLastError);
    MethodHandle configure = bind(linker, kernel, "SetInformationJobObject",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
        captureLastError);
    MethodHandle assign = bind(linker, kernel, "AssignProcessToJobObject",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        captureLastError);
    MethodHandle currentProcess = linker.downcallHandle(
        kernel.find("GetCurrentProcess").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS));
    MethodHandle close = linker.downcallHandle(kernel.find("CloseHandle").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    try (Arena call = Arena.ofConfined()) {
      MemorySegment state = call.allocate(stateLayout);
      MemorySegment job =
          (MemorySegment) create.invokeExact(state, MemorySegment.NULL, MemorySegment.NULL);
      if (job.address() == 0) return failed("CreateJobObjectW", lastError, state);

      MemorySegment limits = call.allocate(EXTENDED_LIMIT_INFORMATION_SIZE, 8);
      limits.set(ValueLayout.JAVA_INT, LIMIT_FLAGS_OFFSET, KILL_ON_JOB_CLOSE);
      int configured = (int) configure.invokeExact(state, job, EXTENDED_LIMIT_INFORMATION_CLASS,
          limits, EXTENDED_LIMIT_INFORMATION_SIZE);
      if (configured == 0) {
        WindowsEngineJob result = failed("SetInformationJobObject", lastError, state);
        int unused = (int) close.invokeExact(job); // not joined yet: closing ends nothing
        return result;
      }

      // Associate while inactive, before the Engine or any of its helpers can enter the job.
      WindowsJobWitness witness = null;
      String witnessFailure = null;
      try {
        witness = WindowsJobWitness.associate(job);
      } catch (VirtualMachineError fatal) {
        int _ = (int) close.invokeExact(job);
        throw fatal;
      } catch (Throwable failure) {
        // Accounting may degrade, but the kill-on-close backstop must still be installed.
        witnessFailure = failure.getClass().getSimpleName();
      }
      MemorySegment self = (MemorySegment) currentProcess.invokeExact();
      int joined = (int) assign.invokeExact(state, job, self);
      if (joined == 0) {
        WindowsEngineJob result = failed("AssignProcessToJobObject", lastError, state);
        int unused = (int) close.invokeExact(job); // assignment failed: we are not in it
        if (witness != null) witness.close();
        return result;
      }
      JOB_HANDLE.set(ValueLayout.ADDRESS, 0, job);
      WindowsEngineJob result = new WindowsEngineJob(Status.CONTAINED, Optional.empty());
      result.witness = witness;
      result.witnessFailure = witnessFailure;
      if (witness != null) witness.start();
      return result;
    }
  }

  private static WindowsEngineJob failed(String call, VarHandle lastError, MemorySegment state) {
    return unavailable(call + ":" + (int) lastError.get(state, 0L));
  }

  private static WindowsEngineJob unavailable(String reason) {
    return new WindowsEngineJob(Status.UNAVAILABLE, Optional.of(reason));
  }

  private static MethodHandle bind(Linker linker, SymbolLookup kernel, String name,
      FunctionDescriptor descriptor, Linker.Option option) {
    return linker.downcallHandle(kernel.find(name)
        .orElseThrow(() -> new IllegalStateException("missing kernel32!" + name)), descriptor, option);
  }

  @Override
  public Status status() {
    return status;
  }

  @Override
  public Optional<String> unavailableReason() {
    return reason;
  }

  @Override
  public void recordProcesses(io.justsearch.app.api.runtime.child.LedgerSink ledger,
      io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity owner) {
    io.justsearch.app.api.runtime.child.OwnedHelper.recordTo(ledger, owner);
    if (witness != null) witness.attach(ledger, owner);
    else if (witnessFailure != null) ledger.append(
        io.justsearch.app.api.runtime.ledger.LedgerRecordType.LEDGER_GAP,
        java.util.Map.of("reason", "WRITE_FAILED", "count", 1, "since", java.time.Instant.now(),
            "source", "job_witness_unavailable", "failure", witnessFailure));
  }

  @Override
  public void finishProcessRecording(java.time.Duration max) {
    if (witness != null) witness.finish(max);
  }

  /**
   * {@code IsProcessInJob(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, pid), engineJob)}. The
   * caller holds the child (a live {@link Process}), so its PID cannot be reused under the query.
   * The process handle is closed; the job handle is only read.
   */
  @Override
  public Optional<Boolean> contains(ProcessHandle child) {
    if (status != Status.CONTAINED || child == null) return Optional.empty();
    MemorySegment job = JOB_HANDLE.get(ValueLayout.ADDRESS, 0);
    if (job.address() == 0) return Optional.empty();
    try {
      return Membership.query(job, child.pid());
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable failure) {
      return Optional.empty();
    }
  }

  /** The downcalls {@link #contains} needs, bound on first use. */
  private static final class Membership {
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final MethodHandle OPEN;
    private static final MethodHandle IN_JOB;
    private static final MethodHandle CLOSE;

    static {
      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
      OPEN = linker.downcallHandle(kernel.find("OpenProcess").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
              ValueLayout.JAVA_INT));
      IN_JOB = linker.downcallHandle(kernel.find("IsProcessInJob").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
              ValueLayout.ADDRESS));
      CLOSE = linker.downcallHandle(kernel.find("CloseHandle").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }

    static Optional<Boolean> query(MemorySegment job, long pid) throws Throwable {
      if (pid <= 0 || pid > 0xFFFF_FFFFL) return Optional.empty();
      MemorySegment process =
          (MemorySegment) OPEN.invokeExact(PROCESS_QUERY_LIMITED_INFORMATION, 0, (int) pid);
      if (process.address() == 0) return Optional.empty();
      try (Arena call = Arena.ofConfined()) {
        MemorySegment result = call.allocate(ValueLayout.JAVA_INT);
        int ok = (int) IN_JOB.invokeExact(process, job, result);
        if (ok == 0) return Optional.empty();
        return Optional.of(result.get(ValueLayout.JAVA_INT, 0) != 0);
      } finally {
        int unused = (int) CLOSE.invokeExact(process);
      }
    }
  }

  @Override
  public String toString() {
    return "WindowsEngineJob[" + status + reason.map(r -> ", " + r).orElse("") + "]";
  }
}
