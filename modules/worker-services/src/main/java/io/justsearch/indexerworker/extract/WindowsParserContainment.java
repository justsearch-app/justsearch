/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.configuration.SystemAccess;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/** Windows parser lifetime boundary, installed before a parser can start native children. */
final class WindowsParserContainment {
  private static MemorySegment retainedJob;

  private WindowsParserContainment() {}

  /**
   * The parser owns the only job handle. Never close it in Java: closing a self-assigned job
   * terminates this JVM too. Windows closes it on process death, including forced termination,
   * and kills every descendant inherited into the job. No handle inheritance or breakaway is set.
   */
  static synchronized void install() {
    if (retainedJob != null
        || !SystemAccess.sysProp("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
      return;
    }
    try {
      retainedJob = createAndAssign();
    } catch (Error e) {
      throw e;
    } catch (Throwable failure) {
      throw new IllegalStateException("Cannot establish Windows parser process containment", failure);
    }
  }

  private static MemorySegment createAndAssign() throws Throwable {
    Linker linker = Linker.nativeLinker();
    SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
    MethodHandle create = bind(linker, kernel, "CreateJobObjectW",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    MethodHandle configure = bind(linker, kernel, "SetInformationJobObject",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    MethodHandle current = bind(linker, kernel, "GetCurrentProcess",
        FunctionDescriptor.of(ValueLayout.ADDRESS));
    MethodHandle assign = bind(linker, kernel, "AssignProcessToJobObject",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    MethodHandle close = bind(linker, kernel, "CloseHandle",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    MethodHandle lastError = bind(linker, kernel, "GetLastError",
        FunctionDescriptor.of(ValueLayout.JAVA_INT));

    MemorySegment job = (MemorySegment) create.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
    if (job.address() == 0) throw failed("CreateJobObjectW", lastError);
    try (Arena arena = Arena.ofConfined()) {
      // Win64 JOBOBJECT_EXTENDED_LIMIT_INFORMATION: BASIC_LIMIT_INFORMATION (64),
      // IO_COUNTERS (48), four SIZE_T fields (32). LimitFlags follows two LARGE_INTEGERs.
      if (ValueLayout.ADDRESS.byteSize() != 8) {
        throw new IllegalStateException("Windows parser containment requires a 64-bit JVM");
      }
      MemorySegment limits = arena.allocate(144, 8);
      limits.set(ValueLayout.JAVA_INT, 16, 0x2000); // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
      int configured = (int) configure.invokeExact(job, 9, limits, 144);
      if (configured == 0) throw failed("SetInformationJobObject", lastError);
      MemorySegment process = (MemorySegment) current.invokeExact();
      int result = (int) assign.invokeExact(job, process);
      if (result == 0) throw failed("AssignProcessToJobObject", lastError);
      return job;
    } catch (Throwable failure) {
      try {
        int result = (int) close.invokeExact(job);
        if (result == 0) throw failed("CloseHandle after failed containment setup", lastError);
      } catch (Throwable cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private static IllegalStateException failed(String operation, MethodHandle lastError)
      throws Throwable {
    int code = (int) lastError.invokeExact();
    return new IllegalStateException(operation + " failed with Windows error " + code);
  }

  private static MethodHandle bind(
      Linker linker, SymbolLookup kernel, String name, FunctionDescriptor descriptor) {
    return linker.downcallHandle(kernel.find(name)
        .orElseThrow(() -> new IllegalStateException("Missing kernel32 function " + name)), descriptor);
  }
}
