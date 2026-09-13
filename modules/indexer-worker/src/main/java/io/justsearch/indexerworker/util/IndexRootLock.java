/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker-held lock for an index base path.
 *
 * <p>This prevents two different Workers (potentially from different data dirs) from operating on
 * the same {@code indexBasePath} when {@code justsearch.index.base_path} is overridden.
 *
 * <p>The OS lock is authoritative. The sibling lock file remains stable on refusal and release;
 * process exit releases the lock without any metadata-based recovery.
 */
public final class IndexRootLock implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(IndexRootLock.class);

  private static final java.util.Set<Path> HELD_IN_JVM = new java.util.HashSet<>();

  private final Path indexBasePath;
  private Path lockFile;
  private FileChannel channel;
  private FileLock lock;

  public IndexRootLock(Path indexBasePath) {
    if (indexBasePath == null) {
      throw new IllegalArgumentException("indexBasePath is required");
    }
    Path base = indexBasePath.toAbsolutePath().normalize();
    if (base.getParent() == null) {
      throw new IllegalArgumentException("indexBasePath must not be a filesystem root: " + base);
    }
    this.indexBasePath = base;
    // Important for Windows: IndexGenerationManager may rename/move the indexBasePath directory during
    // legacy import. If we lock a file *inside* indexBasePath, the directory move can fail with
    // AccessDeniedException because Windows refuses to move a folder containing an open/locked file.
    //
    // Use a sibling lock file instead (still scoped to this specific indexBasePath path).
    this.lockFile = base.resolveSibling(base.getFileName().toString() + ".index.lock");
  }

  public void acquire() throws IOException {
    synchronized (HELD_IN_JVM) {
      acquireExclusive();
    }
  }

  private void acquireExclusive() throws IOException {
    if (lock != null) {
      return;
    }
    Files.createDirectories(indexBasePath.getParent());
    Path realBase;
    try {
      realBase = indexBasePath.toRealPath();
    } catch (java.nio.file.NoSuchFileException notCreated) {
      if (Files.exists(indexBasePath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw notCreated;
      realBase = indexBasePath.getParent().toRealPath().resolve(indexBasePath.getFileName());
    }
    if (realBase.getParent() == null) {
      throw new IOException("Index base resolves to a filesystem root: " + indexBasePath);
    }
    lockFile = realBase.resolveSibling(realBase.getFileName().toString() + ".index.lock");
    // A second channel close may release this JVM's first native lock on POSIX.
    if (HELD_IN_JVM.contains(lockFile)) {
      throw new IOException("Index base path is already locked in this JVM: " + lockFile);
    }
    this.channel =
        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    try {
      this.lock = channel.tryLock();
    } catch (Exception e) {
      close();
      throw new IOException("Failed to acquire index root lock: " + lockFile, e);
    }
    if (this.lock == null) {
      close();
      throw new IOException("Index base path is already locked by another process: " + lockFile);
    }

    HELD_IN_JVM.add(lockFile);
    writeOwnerMetadataBestEffort();
    log.info("Acquired index root lock: {}", lockFile);
  }

  private void writeOwnerMetadataBestEffort() {
    try {
      long pid = ProcessHandle.current().pid();
      String content = "pid=" + pid + "\n" + ProcessHandle.current().info().startInstant()
          .map(start -> "started_at=" + start + "\n").orElse("");
      channel.truncate(0);
      channel.position(0);
      channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
      channel.force(true);
    } catch (Exception e) {
      log.debug("Failed to write index lock metadata (best-effort): {}", e.getMessage());
    }
  }

  @Override
  public void close() {
    synchronized (HELD_IN_JVM) {
      closeExclusive();
    }
  }

  private void closeExclusive() {
    boolean wasHeld = lock != null;
    try {
      if (lock != null) {
        lock.release();
      }
    } catch (Exception e) {
      log.debug("Failed to release file lock on {}: {}", lockFile, e.getMessage());
    } finally {
      lock = null;
    }
    try {
      if (channel != null) {
        channel.close();
      }
    } catch (Exception e) {
      log.debug("Failed to close file channel for {}: {}", lockFile, e.getMessage());
    } finally {
      channel = null;
    }
    if (wasHeld) HELD_IN_JVM.remove(lockFile);
  }
}
