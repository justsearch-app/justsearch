/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Fresh byte reads that distinguish a held external file lock from malformed durable content. */
public final class ContendedFileReads {
  private static final Duration READ_LOCK_BUDGET = Duration.ofSeconds(2);
  private static final long RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

  private ContendedFileReads() {}

  public static byte[] readAllBytes(Path path) throws IOException {
    return readAllBytes(path, READ_LOCK_BUDGET);
  }

  /** Only lock contention and a replacement gap following it retry. Other I/O failures propagate. */
  static byte[] readAllBytes(Path path, Duration budget) throws IOException {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(budget, "budget");
    long budgetNanos = budget.toNanos();
    if (budgetNanos <= 0) throw new IllegalArgumentException("Read lock budget must be positive");
    long started = System.nanoTime();
    boolean contended = false;
    NoSuchFileException missingAfterContention = null;
    for (;;) {
      requireNotInterrupted(path);
      if (contended && System.nanoTime() - started >= budgetNanos) {
        if (missingAfterContention != null) throw missingAfterContention;
        throw new FileReadContendedException(path);
      }
      // Reopen after contention: an atomic writer may have replaced the named file while waiting.
      try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
        missingAfterContention = null;
        try (FileLock held = trySharedLock(channel)) {
          if (held != null) {
            // The lock protects this exact channel, not a second Files.readAllBytes open.
            // Release it before parsing or calling any other durable owner.
            return Channels.newInputStream(channel).readAllBytes();
          }
        }
      } catch (NoSuchFileException missing) {
        // The generation owner rotates state.json -> state.json.prev before moving the new
        // state.json into place. A reader already waiting on the old file can land in that gap.
        // A path absent before any contention remains an immediate error.
        if (!contended) throw missing;
        missingAfterContention = missing;
      }
      contended = true;
      long remaining = budgetNanos - (System.nanoTime() - started);
      if (remaining <= 0) {
        if (missingAfterContention != null) throw missingAfterContention;
        throw new FileReadContendedException(path);
      }
      try {
        TimeUnit.NANOSECONDS.sleep(Math.min(RETRY_NANOS, remaining));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        var failure = new InterruptedIOException("Interrupted while waiting to read " + path);
        failure.initCause(interrupted);
        throw failure;
      }
    }
  }

  private static FileLock trySharedLock(FileChannel channel) throws IOException {
    try {
      return channel.tryLock(0, Long.MAX_VALUE, true);
    } catch (OverlappingFileLockException heldInThisJvm) {
      return null;
    }
  }

  private static void requireNotInterrupted(Path path) throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedIOException("Interrupted before reading " + path);
    }
  }

  /** Unavailable byte ownership, never evidence of corrupt file contents. */
  public static final class FileReadContendedException extends IOException {
    private FileReadContendedException(Path path) {
      super("Read lock remained unavailable for " + path);
    }
  }
}
