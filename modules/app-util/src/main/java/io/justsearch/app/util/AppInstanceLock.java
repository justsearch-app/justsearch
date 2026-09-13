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
   * Tempdoc 501 §3.7: tracks dataDirs for which this JVM already holds an exclusive lock.
   * Required because {@code FileChannel.tryLock()} throws {@link OverlappingFileLockException}
   * on a second acquisition in the same JVM, even from a different {@link FileChannel}.
   *
   * <p>HeadlessApp acquires the lock early in boot; KnowledgeServerBootstrap (which also
   * calls {@code .acquire()} for the standalone-test launch path) consults this set to skip
   * its own acquisition when the Head already holds the lock for the same dataDir.
   */
  private static final java.util.Set<Path> HELD_IN_JVM =
      java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

  /**
   * Returns {@code true} if this JVM already holds an exclusive lock for {@code dataDir}.
   * Callers in the same JVM should use this to skip a redundant acquire that would throw
   * {@link OverlappingFileLockException}.
   */
  public static boolean isHeldByThisJvm(Path dataDir) {
    synchronized (HELD_IN_JVM) {
      try {
        return HELD_IN_JVM.contains(dataDir.toRealPath());
      } catch (IOException unavailable) {
        return false;
      }
    }
  }

  private Path lockPath;
  private Path canonicalDataDir;
  private FileChannel channel;
  private FileLock lock;

  public AppInstanceLock(Path dataDir) {
    if (dataDir == null) {
      throw new IllegalArgumentException("dataDir is required");
    }
    this.canonicalDataDir = dataDir.toAbsolutePath().normalize();
    this.lockPath = canonicalDataDir.resolve("app.lock");
  }

  public boolean isHeld() {
    return lock != null && lock.isValid();
  }

  /** Acquire an exclusive lock; process exit releases it without deleting the lock file. */
  public void acquire() throws IOException {
    synchronized (HELD_IN_JVM) {
      acquireExclusive();
    }
  }

  private void acquireExclusive() throws IOException {
    if (isHeld()) return;
    Files.createDirectories(canonicalDataDir);
    canonicalDataDir = canonicalDataDir.toRealPath();
    lockPath = canonicalDataDir.resolve("app.lock");
    // Never open/close a second channel for a locally held file: on POSIX that can release
    // this JVM's existing native lock, even though its FileLock object remains valid.
    if (HELD_IN_JVM.contains(canonicalDataDir)) {
      throw new AppInstanceLockException("Another JustSearch instance is already running for dataDir="
          + canonicalDataDir);
    }

    // Open/create lock file. Keep channel open for lifetime of the lock.
    channel =
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);

    try {
      lock = channel.tryLock(); // Exclusive lock
      if (lock == null) {
        closeChannelQuietly();
        throw new AppInstanceLockException(
            "Another JustSearch instance is already running for dataDir=" + lockPath.getParent());
      }
    } catch (OverlappingFileLockException e) {
      closeChannelQuietly();
      throw new AppInstanceLockException(
          "Another JustSearch instance is already running for dataDir=" + lockPath.getParent(), e);
    } catch (IOException e) {
      closeChannelQuietly();
      // Normalize into a clearer message for common Windows cases (AccessDenied, etc.).
      throw new AppInstanceLockException(
          "Failed to acquire app lock at " + lockPath + " (dataDir=" + lockPath.getParent() + ")", e);
    }

    // Best-effort: write metadata so humans can see who holds the lock.
    writeOwnerMetadataBestEffort();

    // Tempdoc 501 §3.7: mark in-JVM holders so re-entrant acquires from other components
    // (e.g., KnowledgeServerBootstrap inside the same Head JVM) skip cleanly.
    HELD_IN_JVM.add(canonicalDataDir);

    log.info("Acquired app lock: {}", lockPath);
  }

  private void closeChannelQuietly() {
    try {
      if (channel != null) {
        channel.close();
      }
    } catch (Exception ignored) {
      // best effort
    } finally {
      channel = null;
    }
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
      // Best-effort only; do not fail startup.
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
    boolean wasHeld = (lock != null);
    // Release lock first, then close channel.
    if (lock != null) {
      try {
        lock.release();
      } catch (Exception ignored) {
        // best effort
      } finally {
        lock = null;
      }
    }
    if (channel != null) {
      try {
        channel.close();
      } catch (Exception ignored) {
        // best effort
      } finally {
        channel = null;
      }
    }
    // Tempdoc 501 §3.7: clear in-JVM marker so a clean restart in the same JVM can
    // re-acquire (relevant in tests that start/stop the Head in-process).
    if (wasHeld) {
      HELD_IN_JVM.remove(canonicalDataDir);
    }
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
