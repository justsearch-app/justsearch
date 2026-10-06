/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.time.Duration;
import java.util.OptionalInt;

/**
 * Reads the OS exit code of a process this test did not start (Windows only): a handle opened
 * while the process is alive keeps its exit code readable after it ends. The JDK has no such view
 * for a process that is not its own child, and the launched llama-server's code is what tells a
 * recorded stop ({@code JS_CHILD_STOP}) from a plain destroy (code 1).
 */
final class ExitCodeWatch implements AutoCloseable {
  private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
  private static final int SYNCHRONIZE = 0x0010_0000;
  private static final int WAIT_OBJECT_0 = 0;

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32", Arena.global());
  private static final MethodHandle OPEN = bind("OpenProcess",
      FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.JAVA_INT));
  private static final MethodHandle WAIT = bind("WaitForSingleObject",
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  private static final MethodHandle EXIT_CODE = bind("GetExitCodeProcess",
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  private static final MethodHandle CLOSE = bind("CloseHandle",
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

  private final long pid;
  private final MemorySegment handle;
  private boolean closed;

  private ExitCodeWatch(long pid, MemorySegment handle) {
    this.pid = pid;
    this.handle = handle;
  }

  static boolean supported() {
    return System.getProperty("os.name", "").startsWith("Windows");
  }

  /** Opens a watch on the live process {@code pid}. */
  static ExitCodeWatch open(long pid) throws Throwable {
    MemorySegment handle = (MemorySegment) OPEN.invokeExact(
        PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, 0, (int) pid);
    if (handle.address() == 0) throw new IllegalStateException("cannot open process " + pid);
    return new ExitCodeWatch(pid, handle);
  }

  long pid() {
    return pid;
  }

  /** The exit code once the process ended within {@code max}; empty while it still runs. */
  OptionalInt awaitExitCode(Duration max) throws Throwable {
    int waited = (int) WAIT.invokeExact(handle, (int) Math.min(Integer.MAX_VALUE, max.toMillis()));
    if (waited != WAIT_OBJECT_0) return OptionalInt.empty();
    try (Arena call = Arena.ofConfined()) {
      MemorySegment code = call.allocate(ValueLayout.JAVA_INT);
      int ok = (int) EXIT_CODE.invokeExact(handle, code);
      if (ok == 0) throw new IllegalStateException("GetExitCodeProcess failed for " + pid);
      return OptionalInt.of(code.get(ValueLayout.JAVA_INT, 0));
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    try {
      int _ = (int) CLOSE.invokeExact(handle);
    } catch (Throwable ignored) {
      // released with this process at worst
    }
  }

  private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(KERNEL.find(name).orElseThrow(), descriptor);
  }
}
