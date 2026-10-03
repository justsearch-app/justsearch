/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.configuration.PlatformPaths;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Stable directory identity, independent of mutable timestamps and path spelling. */
record RootIdentity(Path realPath, StableFileIdentity stableFileIdentity) {
  RootIdentity {
    if (realPath == null || stableFileIdentity == null) {
      throw new IllegalArgumentException("Root identity requires a real path and stable file id");
    }
  }

  /** Captures an immutable registration witness without retaining a native resource. */
  static RootIdentity capture(Path root) throws IOException {
    Path normalized = normalize(root);
    validateDirectory(normalized);
    if (PlatformPaths.isWindows()) {
      return WindowsRootIdentityLease.capture(normalized);
    }
    try (RootIdentityLease lease = PortableRootIdentityLease.open(normalized)) {
      lease.requireCurrent();
      return lease.identity();
    }
  }

  /** Opens the identity witness retained for one complete reconciliation. */
  static RootIdentityLease hold(Path root) throws IOException {
    Path normalized = normalize(root);
    validateDirectory(normalized);
    return PlatformPaths.isWindows()
        ? WindowsRootIdentityLease.open(normalized)
        : PortableRootIdentityLease.open(normalized);
  }

  void requireCurrent(Path root) throws IOException {
    requireSame(capture(root), root);
  }

  void requireSame(RootIdentity current, Path root) throws IOException {
    if (!equals(current)) {
      throw new IOException("Reconciliation root identity changed: " + root);
    }
  }

  private static Path normalize(Path root) throws IOException {
    try {
      return root.toAbsolutePath().normalize();
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid reconciliation root", invalid);
    }
  }

  private static void validateDirectory(Path root) throws IOException {
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException unreadable) {
      throw new IOException("Reconciliation root is unreadable: " + root, unreadable);
    }
    if (!attributes.isDirectory() || !Files.isReadable(root)) {
      throw new IOException("Reconciliation root is not a readable directory: " + root);
    }
  }

  private static Path realPath(Path root) throws IOException {
    try {
      return root.toRealPath(LinkOption.NOFOLLOW_LINKS);
    } catch (IOException unreadable) {
      throw new IOException("Reconciliation root identity is unreadable: " + root, unreadable);
    }
  }

  private static RootIdentity portableIdentity(Path root) throws IOException {
    validateDirectory(root);
    BasicFileAttributes attributes =
        Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    Object fileKey = attributes.fileKey();
    if (fileKey == null) {
      throw new IOException("Reconciliation root has no stable file identity: " + root);
    }
    return new RootIdentity(realPath(root), new ProviderFileIdentity(fileKey));
  }

  sealed interface StableFileIdentity permits ProviderFileIdentity, WindowsFileIdentity {}

  record ProviderFileIdentity(Object fileKey) implements StableFileIdentity {
    ProviderFileIdentity {
      if (fileKey == null) throw new IllegalArgumentException("fileKey must be stable and non-null");
    }
  }

  record WindowsFileIdentity(long volumeSerialNumber, String fileId128)
      implements StableFileIdentity {
    WindowsFileIdentity {
      if (fileId128 == null || fileId128.length() != 32) {
        throw new IllegalArgumentException("Windows FILE_ID_128 must contain 16 bytes");
      }
    }
  }

  private static final class PortableRootIdentityLease implements RootIdentityLease {
    private final Path root;
    private final RootIdentity identity;
    private final DirectoryStream<Path> heldDirectory;

    private PortableRootIdentityLease(
        Path root, RootIdentity identity, DirectoryStream<Path> heldDirectory) {
      this.root = root;
      this.identity = identity;
      this.heldDirectory = heldDirectory;
    }

    static RootIdentityLease open(Path root) throws IOException {
      RootIdentity beforeOpen = portableIdentity(root);
      DirectoryStream<Path> heldDirectory = Files.newDirectoryStream(root);
      boolean retained = false;
      try {
        beforeOpen.requireSame(portableIdentity(root), root);
        PortableRootIdentityLease lease =
            new PortableRootIdentityLease(root, beforeOpen, heldDirectory);
        retained = true;
        return lease;
      } finally {
        if (!retained) heldDirectory.close();
      }
    }

    @Override
    public RootIdentity identity() {
      return identity;
    }

    @Override
    public void requireCurrent() throws IOException {
      identity.requireSame(portableIdentity(root), root);
    }

    @Override
    public void close() throws IOException {
      heldDirectory.close();
    }
  }

  private static final class WindowsRootIdentityLease implements RootIdentityLease {
    private final Path root;
    private final RootIdentity identity;
    private final WindowsNative.DirectoryHandle heldHandle;

    private WindowsRootIdentityLease(
        Path root, RootIdentity identity, WindowsNative.DirectoryHandle heldHandle) {
      this.root = root;
      this.identity = identity;
      this.heldHandle = heldHandle;
    }

    static RootIdentityLease open(Path root) throws IOException {
      // Retaining the original handle prevents its native ID from being recycled while the caller
      // performs admissions. Omitting FILE_SHARE_DELETE is additional best-effort protection on
      // providers that honor it for directory renames; ReFS does not make that an atomic lock.
      WindowsNative.DirectoryHandle heldHandle = WindowsNative.open(root, false);
      boolean retained = false;
      try {
        RootIdentity identity = new RootIdentity(realPath(root), heldHandle.fileIdentity());
        identity.requireSame(capture(root), root);
        WindowsRootIdentityLease lease = new WindowsRootIdentityLease(root, identity, heldHandle);
        retained = true;
        return lease;
      } finally {
        if (!retained) heldHandle.close();
      }
    }

    /** Registration capture permits ordinary rename/delete immediately after this call returns. */
    static RootIdentity capture(Path root) throws IOException {
      try (WindowsNative.DirectoryHandle observed = WindowsNative.open(root, true)) {
        RootIdentity identity = new RootIdentity(realPath(root), observed.fileIdentity());
        try (WindowsNative.DirectoryHandle current = WindowsNative.open(root, true)) {
          identity.requireSame(
              new RootIdentity(realPath(root), current.fileIdentity()), root);
        }
        return identity;
      }
    }

    @Override
    public RootIdentity identity() {
      return identity;
    }

    @Override
    public void requireCurrent() throws IOException {
      validateDirectory(root);
      identity.requireSame(capture(root), root);
    }

    @Override
    public void close() throws IOException {
      heldHandle.close();
    }
  }

  /** Minimal kernel32 boundary for a held directory HANDLE and FILE_ID_INFO. */
  private static final class WindowsNative {
    private static final int FILE_SHARE_READ_WRITE = 0x00000003;
    private static final int FILE_SHARE_DELETE = 0x00000004;
    private static final int FILE_READ_ATTRIBUTES = 0x00000080;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    private static final int FILE_ID_INFO_CLASS = 18;
    private static final long INVALID_HANDLE_VALUE = -1L;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32", Arena.global());
    private static final MethodHandle CREATE_FILE = bind(
        "CreateFileW",
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS));
    private static final MethodHandle FILE_INFORMATION = bind(
        "GetFileInformationByHandleEx",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT));
    private static final MethodHandle CLOSE_HANDLE = bind(
        "CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle LAST_ERROR =
        bind("GetLastError", FunctionDescriptor.of(ValueLayout.JAVA_INT));

    private WindowsNative() {}

    static DirectoryHandle open(Path root, boolean shareDelete) throws IOException {
      try (Arena arena = Arena.ofConfined()) {
        String path = extendedPath(root);
        MemorySegment widePath = arena.allocate((long) (path.length() + 1) * Character.BYTES, 2);
        for (int i = 0; i < path.length(); i++) {
          widePath.setAtIndex(ValueLayout.JAVA_CHAR, i, path.charAt(i));
        }
        widePath.setAtIndex(ValueLayout.JAVA_CHAR, path.length(), '\0');
        MemorySegment handle = (MemorySegment) CREATE_FILE.invokeExact(
            widePath,
            FILE_READ_ATTRIBUTES,
            FILE_SHARE_READ_WRITE | (shareDelete ? FILE_SHARE_DELETE : 0),
            MemorySegment.NULL,
            OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS,
            MemorySegment.NULL);
        if (handle.address() == 0 || handle.address() == INVALID_HANDLE_VALUE) {
          throw failed("CreateFileW", root);
        }
        try {
          MemorySegment info = arena.allocate(24, 8);
          int result = (int) FILE_INFORMATION.invokeExact(handle, FILE_ID_INFO_CLASS, info, 24);
          if (result == 0) throw failed("GetFileInformationByHandleEx(FileIdInfo)", root);
          long volumeSerialNumber = info.get(ValueLayout.JAVA_LONG_UNALIGNED, 0);
          char[] fileId = new char[32];
          for (int i = 0; i < 16; i++) {
            int value = Byte.toUnsignedInt(info.get(ValueLayout.JAVA_BYTE, 8L + i));
            fileId[i * 2] = HEX[value >>> 4];
            fileId[i * 2 + 1] = HEX[value & 0x0f];
          }
          return new DirectoryHandle(
              root, handle, new WindowsFileIdentity(volumeSerialNumber, new String(fileId)));
        } catch (Throwable failure) {
          try {
            close(handle, root);
          } catch (Throwable cleanup) {
            failure.addSuppressed(cleanup);
          }
          throw failure;
        }
      } catch (IOException failure) {
        throw failure;
      } catch (Throwable failure) {
        throw new IOException("Cannot capture native identity for root: " + root, failure);
      }
    }

    private static String extendedPath(Path root) {
      String path = root.toString();
      if (path.startsWith("\\\\?\\")) return path;
      if (path.startsWith("\\\\")) return "\\\\?\\UNC\\" + path.substring(2);
      return "\\\\?\\" + path;
    }

    private static IOException failed(String operation, Path root) throws Throwable {
      int code = (int) LAST_ERROR.invokeExact();
      return new IOException(operation + " failed for " + root + " with Windows error " + code);
    }

    private static void close(MemorySegment handle, Path root) throws Throwable {
      int result = (int) CLOSE_HANDLE.invokeExact(handle);
      if (result == 0) throw failed("CloseHandle", root);
    }

    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
      return LINKER.downcallHandle(
          KERNEL.find(name)
              .orElseThrow(() -> new IllegalStateException("Missing kernel32 function " + name)),
          descriptor);
    }

    static final class DirectoryHandle implements AutoCloseable {
      private final Path root;
      private final MemorySegment handle;
      private final WindowsFileIdentity fileIdentity;
      private boolean closed;

      private DirectoryHandle(
          Path root, MemorySegment handle, WindowsFileIdentity fileIdentity) {
        this.root = root;
        this.handle = handle;
        this.fileIdentity = fileIdentity;
      }

      WindowsFileIdentity fileIdentity() {
        return fileIdentity;
      }

      @Override
      public void close() throws IOException {
        if (closed) return;
        try {
          WindowsNative.close(handle, root);
          closed = true;
        } catch (IOException failure) {
          throw failure;
        } catch (Throwable failure) {
          throw new IOException("Cannot close native reconciliation root handle", failure);
        }
      }
    }
  }
}

/** Held witness for the original directory throughout one reconciliation. */
interface RootIdentityLease extends AutoCloseable {
  RootIdentity identity();

  void requireCurrent() throws IOException;

  @Override
  void close() throws IOException;
}
