/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;

/** Windows entry security, including protection against later parent ACL changes. */
final class WindowsOwnerOnlyDirectory {
  // Protected DACL; owner and SYSTEM have full control, inherited by files and subdirectories.
  static final String SDDL = "D:P(A;OICI;FA;;;OW)(A;OICI;FA;;;SY)";
  // DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION.
  static final int REPAIR_SECURITY_INFORMATION = 0x00000004 | 0x80000000;

  private WindowsOwnerOnlyDirectory() {}

  static void apply(Path path, boolean create) throws IOException {
    if (ValueLayout.ADDRESS.byteSize() != 8) {
      throw new IOException("Windows directory security requires a 64-bit JVM");
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment captured = arena.allocate(Win32.STATE);
      MemorySegment output = arena.allocate(ValueLayout.ADDRESS);
      int converted = (int) Win32.CONVERT.invokeExact(captured, wide(arena, SDDL), 1, output, MemorySegment.NULL);
      if (converted == 0) throw failure(path, captured);
      MemorySegment descriptor = output.get(ValueLayout.ADDRESS, 0);
      try {
        MemorySegment name = wide(arena, path.toString());
        int result;
        if (create) {
          // Win64 SECURITY_ATTRIBUTES: DWORD, padding, pointer, BOOL, padding.
          // Arena allocations are zeroed, so bInheritHandle stays false.
          MemorySegment attributes = arena.allocate(24, 8);
          attributes.set(ValueLayout.JAVA_INT, 0, 24);
          attributes.set(ValueLayout.ADDRESS, 8, descriptor);
          result = (int) Win32.CREATE.invokeExact(captured, name, attributes);
        } else {
          repair(arena, path, captured, name, descriptor);
          result = 1;
        }
        if (result == 0) {
          int error = (int) Win32.ERROR.get(captured, 0L);
          if (create && error == 183) throw new FileAlreadyExistsException(path.toString());
          throw failure(path, captured);
        }
      } finally {
        Win32.FREE.invoke(descriptor);
      }
    } catch (IOException failure) {
      throw failure;
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable unavailable) {
      throw new IOException("Windows directory security unavailable for " + path, unavailable);
    }
  }

  private static void repair(Arena arena, Path path, MemorySegment captured,
      MemorySegment name, MemorySegment descriptor) throws Throwable {
    // MAXIMUM_ALLOWED suppresses SetSecurityInfo's automatic propagation to children.
    // Only our no-follow walker may select descendants for repair.
    // https://learn.microsoft.com/en-us/windows/win32/api/aclapi/nf-aclapi-setsecurityinfo
    MemorySegment handle = (MemorySegment) Win32.OPEN.invokeExact(captured, name,
        0x02000000, 3, MemorySegment.NULL, 3, 0x02200000, MemorySegment.NULL);
    if (handle.address() == -1L || handle.address() == 0L) throw failure(path, captured);
    try {
      // FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS opens the entry itself.
      MemorySegment information = arena.allocate(52, 4); // BY_HANDLE_FILE_INFORMATION
      if ((int) Win32.INFORMATION.invokeExact(captured, handle, information) == 0) throw failure(path, captured);
      checkRepairEntry(path, information);
      MemorySegment present = arena.allocate(ValueLayout.JAVA_INT);
      MemorySegment dacl = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment defaulted = arena.allocate(ValueLayout.JAVA_INT);
      if ((int) Win32.DACL.invokeExact(captured, descriptor, present, dacl, defaulted) == 0) throw failure(path, captured);
      MemorySegment acl = dacl.get(ValueLayout.ADDRESS, 0);
      if (present.get(ValueLayout.JAVA_INT, 0) == 0 || acl.address() == 0L) {
        throw new IOException("Owner-only security descriptor has no DACL");
      }
      int error = (int) Win32.REPAIR.invokeExact(handle, 1, REPAIR_SECURITY_INFORMATION,
          MemorySegment.NULL, MemorySegment.NULL, acl, MemorySegment.NULL);
      if (error != 0) throw new IOException("Cannot set protected entry DACL for " + path + " (Windows error " + error + ")");
    } finally {
      int closed = (int) Win32.CLOSE.invokeExact(handle);
    }
  }

  static void checkRepairEntry(Path path, MemorySegment information) throws IOException {
    int attributes = information.get(ValueLayout.JAVA_INT, 0);
    if ((attributes & 0x400) != 0) {
      throw new IOException("Skipping security repair on a reparse point: " + path);
    }
    // BY_HANDLE_FILE_INFORMATION.nNumberOfLinks is an unsigned DWORD at offset 40.
    if ((attributes & 0x10) == 0 && Integer.toUnsignedLong(information.get(ValueLayout.JAVA_INT, 40)) > 1) {
      throw new IOException("Skipping security repair on a hard-linked file: " + path);
    }
  }

  private static MemorySegment wide(Arena arena, String value) {
    return arena.allocateFrom(ValueLayout.JAVA_CHAR, (value + '\0').toCharArray());
  }

  private static IOException failure(Path path, MemorySegment captured) {
    int error = (int) Win32.ERROR.get(captured, 0L);
    return new IOException("Cannot set protected entry DACL for " + path + " (Windows error " + error + ")");
  }

  private static final class Win32 {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32", Arena.global());
    private static final SymbolLookup ADVAPI = SymbolLookup.libraryLookup("advapi32", Arena.global());
    private static final StructLayout STATE = Linker.Option.captureStateLayout();
    private static final VarHandle ERROR = STATE.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
    private static final Linker.Option CAPTURE = Linker.Option.captureCallState("GetLastError");
    private static final MethodHandle CONVERT = bind(ADVAPI, "ConvertStringSecurityDescriptorToSecurityDescriptorW",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS), CAPTURE);
    private static final MethodHandle CREATE = bind(KERNEL, "CreateDirectoryW",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), CAPTURE);
    private static final MethodHandle OPEN = bind(KERNEL, "CreateFileW",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS), CAPTURE);
    private static final MethodHandle INFORMATION = bind(KERNEL, "GetFileInformationByHandle",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), CAPTURE);
    private static final MethodHandle DACL = bind(ADVAPI, "GetSecurityDescriptorDacl",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS), CAPTURE);
    private static final MethodHandle REPAIR = bind(ADVAPI, "SetSecurityInfo",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS));
    private static final MethodHandle CLOSE = bind(KERNEL, "CloseHandle",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle FREE = bind(KERNEL, "LocalFree",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static MethodHandle bind(SymbolLookup library, String name,
        FunctionDescriptor descriptor, Linker.Option... options) {
      return LINKER.downcallHandle(library.find(name)
          .orElseThrow(() -> new IllegalStateException("Missing Windows function " + name)), descriptor, options);
    }
  }
}
