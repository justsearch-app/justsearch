/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-lifetime exclusion for a JustSearch data directory.
 *
 * <p>The Engine acquires this before opening mutable stores. OS locks are authoritative and the
 * lock file is never deleted during refusal or release; PID metadata is diagnostic only.
 */
public final class AppInstanceLock implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(AppInstanceLock.class);

  /**
   * Tempdoc 501 §3.7: tracks owners and failed-close reservations for dataDirs in this JVM.
   * Required because {@code FileChannel.tryLock()} throws {@link OverlappingFileLockException}
   * on a second acquisition in the same JVM, even from a different {@link FileChannel}.
   *
   * <p>HeadlessApp acquires the lock early in boot; KnowledgeServerBootstrap (which also
   * calls {@code .acquire()} for the standalone-test launch path) consults these owners to skip
   * its own acquisition when the Head already holds the lock for the same dataDir.
   */
  private static final java.util.Map<Path, AppInstanceLock> HELD_IN_JVM = new java.util.HashMap<>();

  /**
   * Returns {@code true} if this JVM already holds an exclusive lock for {@code dataDir}.
   * Callers in the same JVM should use this to skip a redundant acquire that would throw
   * {@link OverlappingFileLockException}.
   */
  public static boolean isHeldByThisJvm(Path dataDir) {
    synchronized (HELD_IN_JVM) {
      try {
        var owner = HELD_IN_JVM.get(dataDir.toRealPath());
        return owner != null && owner.isHeld();
      } catch (IOException unavailable) {
        return false;
      }
    }
  }

  private Path lockPath;
  private Path canonicalDataDir;
  private FileChannel channel;
  private FileLock lock;
  private IOException closeFailure;

  public AppInstanceLock(Path dataDir) {
    if (dataDir == null) {
      throw new IllegalArgumentException("dataDir is required");
    }
    this.canonicalDataDir = dataDir.toAbsolutePath().normalize();
    this.lockPath = canonicalDataDir.resolve("app.lock");
  }

  public boolean isHeld() {
    return closeFailure == null && channel != null && channel.isOpen() && lock != null && lock.isValid();
  }

  /** Acquire an exclusive lock; process exit releases it without deleting the lock file. */
  public void acquire() throws IOException {
    synchronized (HELD_IN_JVM) {
      acquireExclusive();
    }
  }

  private void acquireExclusive() throws IOException {
    if (closeFailure != null) throw new AppInstanceLockException("Prior app lock close failed", closeFailure);
    if (isHeld()) return;
    Files.createDirectories(canonicalDataDir);
    canonicalDataDir = canonicalDataDir.toRealPath();
    lockPath = canonicalDataDir.resolve("app.lock");
    // Never open/close a second channel for a locally held file: on POSIX that can release
    // this JVM's existing native lock, even though its FileLock object remains valid.
    if (HELD_IN_JVM.containsKey(canonicalDataDir)) {
      throw new AppInstanceLockException("Another JustSearch instance is already running for dataDir="
          + canonicalDataDir);
    }

    // Reserve before opening: even a failed acquisition must confirm channel cleanup.
    HELD_IN_JVM.put(canonicalDataDir, this);
    try {
      channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      lock = channel.tryLock();
      if (lock == null) throw new AppInstanceLockException(
          "Another JustSearch instance is already running for dataDir=" + canonicalDataDir);
      writeOwnerMetadataBestEffort();
    } catch (IOException | RuntimeException | Error failure) {
      try { close(); } catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      if (failure instanceof Error error) throw error;
      throw new AppInstanceLockException("Failed to acquire app lock at " + lockPath, failure);
    }

    log.info("Acquired app lock: {}", lockPath);
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
      log.debug("Failed to write app.lock metadata (best-effort): {}", e.getMessage());
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
    HELD_IN_JVM.remove(canonicalDataDir, this);
  }

  /** Thrown when the dataDir is already in use by another JustSearch instance. */
  public static final class AppInstanceLockException extends IOException {
    public AppInstanceLockException(String message) {
      super(message);
    }

    public AppInstanceLockException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
