/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.memory;

import io.justsearch.configuration.SystemAccess;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/** Shared FFM plumbing of the native sources. */
final class NativeCalls {
  static final Linker LINKER = Linker.nativeLinker();

  private NativeCalls() {}

  /** A system DLL by absolute path, so the DLL search order cannot substitute another. */
  static SymbolLookup systemLibrary(String fileName, Arena arena) {
    String root = SystemAccess.envVar("SystemRoot");
    Path system32 = Path.of(root == null ? "C:\\Windows" : root, "System32");
    return SymbolLookup.libraryLookup(system32.resolve(fileName), arena);
  }

  static MethodHandle downcall(SymbolLookup library, String symbol, FunctionDescriptor descriptor) {
    MemorySegment function =
        library
            .find(symbol)
            .orElseThrow(() -> new IllegalStateException("symbol missing: " + symbol));
    return LINKER.downcallHandle(function, descriptor);
  }

  /** Rethrows a JVM failure, wraps anything else so the reader can report UNKNOWN. */
  static IllegalStateException failure(String what, Throwable cause) {
    if (cause instanceof VirtualMachineError error) throw error;
    if (cause instanceof IllegalStateException state) return state;
    return new IllegalStateException(what + ": " + cause, cause);
  }

  static IllegalStateException status(String what, int status) {
    return new IllegalStateException(what + " status=0x" + Integer.toHexString(status));
  }
}
