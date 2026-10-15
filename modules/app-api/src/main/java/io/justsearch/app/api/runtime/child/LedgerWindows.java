/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.configuration.SystemAccess;
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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The owner records other processes write to the shared children ledger (task b6d536 b101): the
 * extraction child opens its Tesseract window and owns its helpers in its own JVM, so the Engine's
 * witness reads its windows and helper receipts here.
 *
 * <p>Reads only what was appended since the last read, from the ledger's end when the Engine
 * attached. The file read is identified by the handle opened to read it (its volume and file id),
 * not by its size: when the current file is another file than the one last read, the rest of the
 * earlier file is read from the rotated archive, if that is still the same file, and the new file
 * from byte 0. A window or receipt counts only when the process that wrote it is its owner.
 * Not thread-safe: {@link ProcessIntents} calls it under its lock.
 */
final class LedgerWindows {
  private static final int MAX_WINDOWS = 256;
  private static final int MAX_RECEIPTS = 4096;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A helper another process owns, as its owner recorded it. */
  static final class Receipt {
    private final long creatorPid;
    private final String creatorBirth;
    private String intent;
    private ProcessIntents.Resolution outcome;

    private Receipt(long creatorPid, String creatorBirth) {
      this.creatorPid = creatorPid;
      this.creatorBirth = creatorBirth;
    }

    /** Whether this process, read natively as the member's creator, recorded the receipt. */
    boolean createdBy(Long pid, String birth) {
      return pid != null && pid == creatorPid && Objects.equals(birth, creatorBirth);
    }

    /** The stop intent its owner recorded, or null. */
    String intent() {
      return intent;
    }

    /** The exit its owner recorded, or null. */
    ProcessIntents.Resolution outcome() {
      return outcome;
    }
  }

  private final Path dir;
  private long offset;
  /** The identity of the file {@link #offset} is in; null when unknown. */
  private String identity;
  private final Map<String, ProcessIntents.Window> windows = new LinkedHashMap<>() {
    @Override protected boolean removeEldestEntry(Map.Entry<String, ProcessIntents.Window> eldest) {
      return size() > MAX_WINDOWS;
    }
  };
  private final Map<String, Receipt> receipts = new LinkedHashMap<>() {
    @Override protected boolean removeEldestEntry(Map.Entry<String, Receipt> eldest) {
      return size() > MAX_RECEIPTS;
    }
  };

  LedgerWindows(Path dir) {
    this.dir = dir;
    try (Opened current = open(LedgerFamily.CHILDREN.current(dir))) {
      if (current != null) {
        identity = current.identity();
        offset = current.size();
      }
    } catch (IOException unreadable) {
      offset = 0; // read from the start when it can be read
    }
  }

  /** The windows recorded so far, after reading what was appended since the last call. */
  Collection<ProcessIntents.Window> read() {
    try (Opened current = open(LedgerFamily.CHILDREN.current(dir))) {
      if (current == null) return windows.values(); // a rotation is moving it; the next read retries
      String id = current.identity();
      long size = current.size();
      if ((id != null && identity != null) ? !id.equals(identity) : size < offset) {
        // Rotated: the renamed file holds the rest of what was current; the new file starts at zero.
        try (Opened rotated = open(LedgerFamily.CHILDREN.rotated(dir))) {
          if (rotated != null && (identity == null || identity.equals(rotated.identity()))) {
            readFrom(rotated, offset, rotated.size());
          }
        }
        offset = 0;
      } else if (size < offset) {
        offset = 0; // the same file, cut short in place: read it again from its start
      }
      identity = id;
      if (size > offset) offset = readFrom(current, offset, size);
    } catch (IOException unreadable) {
      // unreadable now; the next read retries from the same place
    }
    return windows.values();
  }

  /** The receipt another process recorded for this member, or null. */
  Receipt receipt(long pid, String birth) {
    return birth == null ? null : receipts.get(pid + "/" + birth);
  }

  /** Reads complete lines from {@code from} up to {@code size}; returns the position after the last one read. */
  private long readFrom(Opened file, long from, long size) throws IOException {
    if (size <= from) return from;
    byte[] bytes = file.read(from, (int) Math.min(Integer.MAX_VALUE - 16, size - from));
    int start = 0;
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] != '\n') continue;
      line(new String(bytes, start, i - start, StandardCharsets.UTF_8));
      start = i + 1;
    }
    return from + start;
  }

  private void line(String text) {
    if (!text.contains("\"helper-")) return;
    JsonNode node;
    try {
      node = JSON.readTree(text);
    } catch (RuntimeException corrupt) {
      return;
    }
    String type = text(node, "type");
    if (type == null) return;
    JsonNode writerPid = node.path("writer").path("pid");
    if (!writerPid.isIntegralNumber()) return;
    if (type.startsWith("helper-window-")) {
      window(node, type, writerPid.longValue());
    } else if (type.equals("helper-start") || type.equals("helper-intent") || type.equals("helper-exit")) {
      receipt(node, type, writerPid.longValue());
    }
  }

  private void window(JsonNode node, String type, long writer) {
    JsonNode owner = node.path("owner");
    String id = text(node, "intentId");
    if (id == null || !owner.path("pid").isIntegralNumber() || writer != owner.path("pid").longValue()) {
      return; // only the owner itself can declare its window
    }
    try {
      if (type.equals("helper-window-start")) {
        String image = text(node, "image");
        String reason = text(node, "reason");
        String start = text(node, "requestedAt");
        if (image == null || reason == null || reason.isEmpty() || start == null) return;
        windows.put(id, new ProcessIntents.Window(image, reason, Instant.parse(start),
            owner.path("pid").longValue(), text(owner, "creationFileTime")));
      } else if (type.equals("helper-window-end") && windows.containsKey(id) && text(node, "closedAt") != null) {
        windows.get(id).close(Instant.parse(text(node, "closedAt")));
      }
    } catch (java.time.format.DateTimeParseException malformed) {
      // a window without valid bounds admits nothing
    }
  }

  /**
   * A helper receipt counts only when its writer is the helper's creator: a process records the
   * helpers it launched, never another process's.
   */
  private void receipt(JsonNode node, String type, long writer) {
    JsonNode pid = node.path("pid");
    JsonNode creatorPid = node.path("creatorPid");
    String birth = text(node, "creationFileTime");
    String creatorBirth = text(node, "creatorCreationFileTime");
    if (!pid.isIntegralNumber() || !creatorPid.isIntegralNumber() || birth == null || creatorBirth == null
        || creatorPid.longValue() != writer) {
      return;
    }
    String key = pid.longValue() + "/" + birth;
    Receipt receipt = receipts.get(key);
    if (receipt == null || !receipt.createdBy(creatorPid.longValue(), creatorBirth)) {
      // A start write can fail; a self-contained intent or exit receipt still names its creator.
      receipt = new Receipt(creatorPid.longValue(), creatorBirth);
      receipts.put(key, receipt);
    }
    String reason = text(node, "reason");
    if (type.equals("helper-intent") && reason != null) {
      receipt.intent = reason;
    } else if (type.equals("helper-exit")) {
      String exitClass = text(node, "exitClass");
      if (reason != null && ("REQUESTED".equals(exitClass) || "UNEXPECTED".equals(exitClass))) {
        receipt.outcome = new ProcessIntents.Resolution(exitClass, reason, "owner exit record");
      }
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isString() ? value.asString() : null;
  }

  /** One opened file: its identity, read from the opened handle where the platform allows. */
  private interface Opened extends AutoCloseable {
    /** The file's identity, or null when the platform supplies none. */
    String identity() throws IOException;

    long size() throws IOException;

    byte[] read(long from, int length) throws IOException;

    @Override void close() throws IOException;
  }

  /** Opens {@code file} for reading, sharing every access with writers and rotation; null when absent. */
  private static Opened open(Path file) throws IOException {
    WindowsFiles windows = WindowsFiles.INSTANCE;
    return windows != null ? windows.open(file) : PortableFile.open(file);
  }

  /** Elsewhere: a channel, and the file key read by path when it is opened. */
  private static final class PortableFile implements Opened {
    private final FileChannel channel;
    private final String identity;

    private PortableFile(FileChannel channel, String identity) {
      this.channel = channel;
      this.identity = identity;
    }

    static Opened open(Path file) throws IOException {
      FileChannel channel;
      try {
        channel = FileChannel.open(file, StandardOpenOption.READ);
      } catch (NoSuchFileException absent) {
        return null;
      }
      try {
        Object key = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        return new PortableFile(channel, key == null ? null : key.toString());
      } catch (IOException | RuntimeException failure) {
        channel.close();
        throw failure;
      }
    }

    @Override public String identity() {
      return identity;
    }

    @Override public long size() throws IOException {
      return channel.size();
    }

    @Override public byte[] read(long from, int length) throws IOException {
      ByteBuffer buffer = ByteBuffer.allocate(length);
      int read = 0;
      while (read >= 0 && buffer.hasRemaining()) read = channel.read(buffer, from + buffer.position());
      return java.util.Arrays.copyOf(buffer.array(), buffer.position());
    }

    @Override public void close() throws IOException {
      channel.close();
    }
  }

  /**
   * Windows, through FFM on kernel32: {@code CreateFileW} sharing read, write and delete (a
   * rotation may rename the file while it is open), the volume and 128-bit file id of that handle
   * ({@code GetFileInformationByHandleEx(FileIdInfo)}), {@code GetFileSizeEx} and positioned
   * {@code ReadFile}. Null where FFM cannot bind kernel32.
   */
  private static final class WindowsFiles {
    private static final int GENERIC_READ = 0x8000_0000;
    private static final int FILE_SHARE_ALL = 0x0000_0007;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_ATTRIBUTE_NORMAL = 0x80;
    private static final int FILE_ID_INFO = 18;
    private static final int ERROR_FILE_NOT_FOUND = 2;
    private static final int ERROR_PATH_NOT_FOUND = 3;
    private static final long INVALID_HANDLE_VALUE = -1L;
    private static final WindowsFiles INSTANCE = load();

    private final MethodHandle createFile;
    private final MethodHandle fileInformation;
    private final MethodHandle fileSize;
    private final MethodHandle readFile;
    private final MethodHandle closeHandle;
    private final StructLayout state;
    private final VarHandle lastError;

    private WindowsFiles(MethodHandle createFile, MethodHandle fileInformation, MethodHandle fileSize,
        MethodHandle readFile, MethodHandle closeHandle, StructLayout state, VarHandle lastError) {
      this.createFile = createFile;
      this.fileInformation = fileInformation;
      this.fileSize = fileSize;
      this.readFile = readFile;
      this.closeHandle = closeHandle;
      this.state = state;
      this.lastError = lastError;
    }

    private static WindowsFiles load() {
      if (!SystemAccess.sysProp("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) return null;
      try {
        Linker linker = Linker.nativeLinker();
        SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
        Linker.Option capture = Linker.Option.captureCallState("GetLastError");
        StructLayout state = Linker.Option.captureStateLayout();
        return new WindowsFiles(
            bind(linker, kernel, "CreateFileW", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS), capture),
            bind(linker, kernel, "GetFileInformationByHandleEx", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
            bind(linker, kernel, "GetFileSizeEx", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            bind(linker, kernel, "ReadFile", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            bind(linker, kernel, "CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
            state, state.varHandle(MemoryLayout.PathElement.groupElement("GetLastError")));
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

    Opened open(Path file) throws IOException {
      MemorySegment handle;
      try (Arena arena = Arena.ofConfined()) {
        String path = file.toAbsolutePath().toString();
        MemorySegment wide = arena.allocate((long) (path.length() + 1) * Character.BYTES, 2);
        for (int i = 0; i < path.length(); i++) wide.setAtIndex(ValueLayout.JAVA_CHAR, i, path.charAt(i));
        wide.setAtIndex(ValueLayout.JAVA_CHAR, path.length(), '\0');
        MemorySegment captured = arena.allocate(state);
        handle = (MemorySegment) createFile.invokeExact(captured, wide, GENERIC_READ, FILE_SHARE_ALL,
            MemorySegment.NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, MemorySegment.NULL);
        if (handle.address() == 0 || handle.address() == INVALID_HANDLE_VALUE) {
          int error = (int) lastError.get(captured, 0L);
          if (error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND) return null;
          throw new IOException("CreateFileW failed for " + file + " with Windows error " + error);
        }
      } catch (IOException | RuntimeException | Error failure) {
        throw failure;
      } catch (Throwable failure) {
        throw new IOException("Cannot open " + file, failure);
      }
      return new Handle(file, handle);
    }

    private final class Handle implements Opened {
      private final Path file;
      private final MemorySegment handle;

      Handle(Path file, MemorySegment handle) {
        this.file = file;
        this.handle = handle;
      }

      @Override public String identity() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
          MemorySegment info = arena.allocate(24, 8);
          if ((int) fileInformation.invokeExact(handle, FILE_ID_INFO, info, 24) == 0) {
            throw new IOException("GetFileInformationByHandleEx(FileIdInfo) failed for " + file);
          }
          return Long.toUnsignedString(info.get(ValueLayout.JAVA_LONG_UNALIGNED, 0)) + ":"
              + Long.toHexString(info.get(ValueLayout.JAVA_LONG_UNALIGNED, 16)) + ":"
              + Long.toHexString(info.get(ValueLayout.JAVA_LONG_UNALIGNED, 8));
        } catch (IOException | RuntimeException | Error failure) {
          throw failure;
        } catch (Throwable failure) {
          throw new IOException("Cannot identify " + file, failure);
        }
      }

      @Override public long size() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
          MemorySegment size = arena.allocate(ValueLayout.JAVA_LONG);
          if ((int) fileSize.invokeExact(handle, size) == 0) throw new IOException("GetFileSizeEx failed for " + file);
          return size.get(ValueLayout.JAVA_LONG, 0);
        } catch (IOException | RuntimeException | Error failure) {
          throw failure;
        } catch (Throwable failure) {
          throw new IOException("Cannot size " + file, failure);
        }
      }

      @Override public byte[] read(long from, int length) throws IOException {
        byte[] out = new byte[length];
        int done = 0;
        try (Arena arena = Arena.ofConfined()) {
          int chunk = Math.min(length, 1 << 20);
          MemorySegment buffer = arena.allocate(Math.max(1, chunk));
          MemorySegment read = arena.allocate(ValueLayout.JAVA_INT);
          MemorySegment overlapped = arena.allocate(32, 8);
          while (done < length) {
            long position = from + done;
            overlapped.fill((byte) 0);
            overlapped.set(ValueLayout.JAVA_INT, 16, (int) position);
            overlapped.set(ValueLayout.JAVA_INT, 20, (int) (position >>> 32));
            int want = Math.min(chunk, length - done);
            if ((int) readFile.invokeExact(handle, buffer, want, read, overlapped) == 0) break; // at end of file
            int got = read.get(ValueLayout.JAVA_INT, 0);
            if (got <= 0) break;
            MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, 0, out, done, got);
            done += got;
          }
        } catch (RuntimeException | Error failure) {
          throw failure;
        } catch (Throwable failure) {
          throw new IOException("Cannot read " + file, failure);
        }
        return done == length ? out : java.util.Arrays.copyOf(out, done);
      }

      @Override public void close() throws IOException {
        try {
          if ((int) closeHandle.invokeExact(handle) == 0) throw new IOException("CloseHandle failed for " + file);
        } catch (IOException | RuntimeException | Error failure) {
          throw failure;
        } catch (Throwable failure) {
          throw new IOException("Cannot close " + file, failure);
        }
      }
    }
  }
}
