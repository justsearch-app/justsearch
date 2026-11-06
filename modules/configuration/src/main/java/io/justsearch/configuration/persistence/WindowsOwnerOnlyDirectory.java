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
import java.security.MessageDigest;
import java.util.HexFormat;

/** Windows entry security, including protection against later parent ACL changes. */
public final class WindowsOwnerOnlyDirectory {
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

  /** Opens the entry itself and rejects reparse points before an index operation. */
  public static void requireNonReparsePoint(Path path) throws IOException {
    inspectEntry(path, false);
  }

  /** Opens the entry itself and requires one link for historical index recovery admission. */
  public static void requireSingleLink(Path path) throws IOException {
    inspectEntry(path, true);
  }

  /**
   * The entry's own NTFS identity (volume serial number and file index), opened without following a
   * link or reparse point: it stays the same across renames on one volume and differs for any other
   * file, which Java's file key does not offer on Windows.
   */
  public static String fileIdentity(Path path) throws IOException {
    String[] identity = new String[1];
    inspectEntry(path, false, information -> identity[0] = identityOf(information));
    return identity[0];
  }

  /** BY_HANDLE_FILE_INFORMATION: dwVolumeSerialNumber at 28, nFileIndexHigh/Low at 44/48. */
  private static String identityOf(MemorySegment information) {
    return Integer.toUnsignedString(information.get(ValueLayout.JAVA_INT, 28), 16) + ":"
        + Integer.toUnsignedString(information.get(ValueLayout.JAVA_INT, 44), 16) + ":"
        + Integer.toUnsignedString(information.get(ValueLayout.JAVA_INT, 48), 16);
  }

  /** What {@link #deleteVerified} found in the file it opened. */
  public enum HandleCheck {
    /** It was the expected file, it passed, and its handle was marked for deletion and closed. */
    DELETED,
    /** It is not the expected file (another identity, a link or reparse point, a directory). */
    CHANGED,
    /** It is the expected file but has another link. */
    LINKED,
    /** It is the expected file but its size or content hash differs. */
    NOT_VERIFIED
  }

  /**
   * Deletes a file only through one handle whose checks it passed (design D4, repair round 3): the
   * file is opened once, without following a link or reparse point, for reading and deletion while
   * no one else may write, rename or delete it; its NTFS identity, link count and size are read from
   * that handle, its SHA-256 is computed by reading the same handle, and only then is that handle's
   * delete disposition set and the handle closed, so exactly that file is deleted, whatever its name
   * holds meanwhile. A file that fails a check is left as it was (its handle closes without the
   * disposition). The handle is never opened delete-on-close: that could not be withdrawn after a
   * failed check.
   *
   * @param identity the identity {@link #fileIdentity} read for the expected file
   * @param size the expected size in bytes, or a negative value when unknown
   * @param sha256 the expected SHA-256 (hex), or {@code null} for a file without a recorded hash
   * @param beforeDelete runs after every check passed, immediately before the disposition is set
   * @throws IOException when the file cannot be opened that way (in use, a sharing violation, no
   *     such file), read, or marked for deletion, or when the platform calls are unavailable;
   *     nothing was deleted then
   */
  public static HandleCheck deleteVerified(Path path, String identity, long size, String sha256,
      Runnable beforeDelete) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment captured = arena.allocate(Win32.STATE);
      // GENERIC_READ | DELETE, FILE_SHARE_READ only, OPEN_EXISTING,
      // FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_SEQUENTIAL_SCAN (no backup semantics: a folder
      // cannot be opened).
      MemorySegment handle = (MemorySegment) Win32.OPEN.invokeExact(captured,
          wide(arena, path.toString()), 0x80010000, 1, MemorySegment.NULL, 3, 0x08200000,
          MemorySegment.NULL);
      if (handle.address() == -1L || handle.address() == 0L) {
        throw new IOException("Cannot open " + path + " for deletion (Windows error "
            + (int) Win32.ERROR.get(captured, 0L) + ")");
      }
      try {
        MemorySegment information = arena.allocate(52, 4);
        if ((int) Win32.INFORMATION.invokeExact(captured, handle, information) == 0) {
          throw new IOException("Cannot read " + path + " (Windows error "
              + (int) Win32.ERROR.get(captured, 0L) + ")");
        }
        int attributes = information.get(ValueLayout.JAVA_INT, 0);
        if ((attributes & 0x400) != 0 || (attributes & 0x10) != 0) return HandleCheck.CHANGED;
        if (!identityOf(information).equals(identity)) return HandleCheck.CHANGED;
        if (Integer.toUnsignedLong(information.get(ValueLayout.JAVA_INT, 40)) != 1) {
          return HandleCheck.LINKED;
        }
        long length = (Integer.toUnsignedLong(information.get(ValueLayout.JAVA_INT, 32)) << 32)
            | Integer.toUnsignedLong(information.get(ValueLayout.JAVA_INT, 36));
        if (size >= 0 && length != size) return HandleCheck.NOT_VERIFIED;
        if (sha256 != null && !sha256.equalsIgnoreCase(sha256Of(arena, captured, handle, path))) {
          return HandleCheck.NOT_VERIFIED;
        }
        beforeDelete.run();
        // FILE_DISPOSITION_INFO { BOOLEAN DeleteFile = TRUE } through FileDispositionInfo (4).
        MemorySegment disposition = arena.allocate(1);
        disposition.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
        if ((int) Win32.DISPOSE.invokeExact(captured, handle, 4, disposition, 1) == 0) {
          throw new IOException("Cannot delete " + path + " (Windows error "
              + (int) Win32.ERROR.get(captured, 0L) + ")");
        }
        return HandleCheck.DELETED;
      } finally {
        int _ = (int) Win32.CLOSE.invokeExact(handle);
      }
    } catch (IOException failure) {
      throw failure;
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable unavailable) {
      // Also on a platform without these calls: nothing was deleted.
      throw new IOException("Cannot delete " + path + " through its handle", unavailable);
    }
  }

  private static String sha256Of(Arena arena, MemorySegment captured, MemorySegment handle,
      Path path) throws Throwable {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    int chunk = 1 << 20;
    MemorySegment buffer = arena.allocate(chunk);
    MemorySegment read = arena.allocate(ValueLayout.JAVA_INT);
    while (true) {
      if ((int) Win32.READ.invokeExact(captured, handle, buffer, chunk, read, MemorySegment.NULL) == 0) {
        throw new IOException("Cannot read " + path + " (Windows error "
            + (int) Win32.ERROR.get(captured, 0L) + ")");
      }
      int n = read.get(ValueLayout.JAVA_INT, 0);
      if (n == 0) break;
      digest.update(buffer.asSlice(0, n).asByteBuffer());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void inspectEntry(Path path, boolean singleLink) throws IOException {
    inspectEntry(path, singleLink, information -> {});
  }

  private static void inspectEntry(Path path, boolean singleLink,
      java.util.function.Consumer<MemorySegment> read) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment captured = arena.allocate(Win32.STATE);
      MemorySegment handle = (MemorySegment) Win32.OPEN.invokeExact(captured,
          wide(arena, path.toString()), 0, 7, MemorySegment.NULL, 3, 0x02200000,
          MemorySegment.NULL);
      if (handle.address() == -1L || handle.address() == 0L) throw failure(path, captured);
      try {
        MemorySegment information = arena.allocate(52, 4);
        if ((int) Win32.INFORMATION.invokeExact(captured, handle, information) == 0) {
          throw failure(path, captured);
        }
        if ((information.get(ValueLayout.JAVA_INT, 0) & 0x400) != 0) {
          throw new IOException("Reparse point is not an owned entry: " + path);
        }
        if (singleLink && Integer.toUnsignedLong(information.get(ValueLayout.JAVA_INT, 40)) != 1) {
          throw new IOException("Entry must have exactly one link: " + path);
        }
        read.accept(information);
      } finally {
        int _ = (int) Win32.CLOSE.invokeExact(handle);
      }
    } catch (IOException failure) {
      throw failure;
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable unavailable) {
      throw new IOException("Cannot inspect Windows entry: " + path, unavailable);
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
      int _ = (int) Win32.CLOSE.invokeExact(handle);
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
    private static final MethodHandle READ = bind(KERNEL, "ReadFile",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), CAPTURE);
    private static final MethodHandle DISPOSE = bind(KERNEL, "SetFileInformationByHandle",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT), CAPTURE);
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
