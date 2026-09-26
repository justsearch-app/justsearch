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

  private static final java.util.Map<Path, IndexRootLock> HELD_IN_JVM = new java.util.HashMap<>();

  private final Path indexBasePath;
  private Path lockFile;
  private FileChannel channel;
  private FileLock lock;
  private IOException closeFailure;

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
    if (closeFailure != null) throw new IOException("Prior index lock close failed", closeFailure);
    if (lock != null && lock.isValid() && channel != null && channel.isOpen()) return;
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
    if (HELD_IN_JVM.containsKey(lockFile)) {
      throw new IOException("Index base path is already locked in this JVM: " + lockFile);
    }
    HELD_IN_JVM.put(lockFile, this);
    try {
      channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      lock = channel.tryLock();
      if (lock == null) throw new IOException("Index base path is already locked by another process: " + lockFile);
      writeOwnerMetadataBestEffort();
    } catch (IOException | RuntimeException | Error failure) {
      try { close(); } catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      if (failure instanceof Error error) throw error;
      throw new IOException("Failed to acquire index root lock: " + lockFile, failure);
    }
    log.info("Acquired index root lock: {}", lockFile);
  }

  private void writeOwnerMetadataBestEffort() throws IOException {
    try {
      long pid = ProcessHandle.current().pid();
      String content = "pid=" + pid + "\n" + ProcessHandle.current().info().startInstant()
          .map(start -> "started_at=" + start + "\n").orElse("");
      channel.truncate(0);
      channel.position(0);
      channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
      channel.force(true);
    } catch (Exception e) {
      if (lock == null || !lock.isValid() || channel == null || !channel.isOpen()) {
        throw new IOException("Native exclusion lost while writing lock metadata", e);
      }
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
    if (closeFailure != null && channel != null && !channel.isOpen()) {
      throw new java.io.UncheckedIOException("Native lock close remains unconfirmed; process restart required", closeFailure);
    }
    try {
      // Closing the owner channel releases its locks; do not release separately while leaving
      // an old channel whose later close could release a new owner's POSIX lock.
      if (channel != null) channel.close();
    } catch (IOException failure) {
      closeFailure = failure;
      throw new java.io.UncheckedIOException("Native lock channel did not close", failure);
    } catch (RuntimeException | Error failure) {
      closeFailure = new IOException("Native channel cleanup failed", failure);
      throw failure;
    }
    channel = null;
    lock = null;
    closeFailure = null;
    HELD_IN_JVM.remove(lockFile, this);
  }

}
