/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Sibling-temp replacement that preserves the prior target until the replacement is complete. */
public final class AtomicFileWrites {
  private static final FileAccess NIO = new NioFileAccess();

  private AtomicFileWrites() {}

  public static void replace(Path target, byte[] content) throws IOException {
    replace(target, content, NIO);
  }

  /** Forces the temporary bytes and refuses replacement when atomic move is unavailable.
   * This does not promise parent-directory durability across physical power loss.
   */
  public static void replaceStrict(Path target, byte[] content) throws IOException {
    replaceStrict(target, content, NIO);
  }

  static void replaceStrict(Path target, byte[] content, FileAccess files) throws IOException {
    replace(target, content, files, true);
  }

  public static void replaceUtf8(Path target, String content) throws IOException {
    replace(target, content.getBytes(StandardCharsets.UTF_8), NIO);
  }

  static void replace(Path target, byte[] content, FileAccess files) throws IOException {
    replace(target, content, files, false);
  }

  private static void replace(Path target, byte[] content, FileAccess files, boolean strict)
      throws IOException {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(files, "files");

    Path absoluteTarget = target.toAbsolutePath().normalize();
    Path parent = absoluteTarget.getParent();
    if (parent == null) throw new IOException("Target has no parent: " + target);
    files.createDirectories(parent);

    Path temp = files.createTempFile(parent, absoluteTarget.getFileName().toString() + ".", ".tmp");
    try {
      if (strict) files.writeForced(temp, content);
      else files.write(temp, content);
      try {
        moveAtomicReplace(temp, absoluteTarget, files);
      } catch (AtomicMoveNotSupportedException unsupported) {
        if (strict) throw unsupported;
        files.moveReplace(temp, absoluteTarget);
      }
    } catch (IOException | RuntimeException | Error failure) {
      try {
        files.deleteIfExists(temp);
      } catch (IOException | RuntimeException | Error cleanup) {
        if (cleanup != failure) failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private static void moveAtomicReplace(Path temp, Path target, FileAccess files) throws IOException {
    long started = System.nanoTime();
    long budget = TimeUnit.SECONDS.toNanos(2);
    AccessDeniedException firstDenial = null;
    for (;;) {
      if (Thread.currentThread().isInterrupted()) {
        var interrupted = new InterruptedIOException("Interrupted before replacing " + target);
        if (firstDenial != null) interrupted.addSuppressed(firstDenial);
        throw interrupted;
      }
      if (firstDenial != null && System.nanoTime() - started >= budget) throw firstDenial;
      try {
        files.moveAtomicReplace(temp, target);
        return;
      } catch (AccessDeniedException denied) {
        // Windows refuses atomic replacement while an ordinary reader has the old file open.
        // Keep the completed temp and prior authority; never turn this into a non-atomic move.
        // A permanent permission denial remains the original failure after the bounded wait.
        if (firstDenial == null) firstDenial = denied;
        long remaining = budget - (System.nanoTime() - started);
        if (remaining <= 0) throw firstDenial;
        try {
          TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(10), remaining));
        } catch (InterruptedException stopped) {
          Thread.currentThread().interrupt();
          var interrupted = new InterruptedIOException("Interrupted while replacing " + target);
          interrupted.initCause(stopped);
          interrupted.addSuppressed(firstDenial);
          throw interrupted;
        }
      }
    }
  }

  interface FileAccess {
    void createDirectories(Path directory) throws IOException;

    Path createTempFile(Path directory, String prefix, String suffix) throws IOException;

    void write(Path path, byte[] content) throws IOException;

    void writeForced(Path path, byte[] content) throws IOException;

    void moveAtomicReplace(Path source, Path target) throws IOException;

    void moveReplace(Path source, Path target) throws IOException;

    void deleteIfExists(Path path) throws IOException;
  }

  private static final class NioFileAccess implements FileAccess {
    @Override
    public void createDirectories(Path directory) throws IOException {
      Files.createDirectories(directory);
    }

    @Override
    public Path createTempFile(Path directory, String prefix, String suffix) throws IOException {
      return Files.createTempFile(directory, prefix, suffix);
    }

    @Override
    public void write(Path path, byte[] content) throws IOException {
      Files.write(
          path,
          content,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void writeForced(Path path, byte[] content) throws IOException {
      try (FileChannel channel = FileChannel.open(
          path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
    }

    @Override
    public void moveAtomicReplace(Path source, Path target) throws IOException {
      Files.move(
          source,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void moveReplace(Path source, Path target) throws IOException {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void deleteIfExists(Path path) throws IOException {
      Files.deleteIfExists(path);
    }
  }
}
