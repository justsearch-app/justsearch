/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The "File Intruder" — simulates external processes (antivirus, indexers, backup agents) that
 * aggressively lock files in the data directory.
 *
 * <p>This harness spawns background threads that:
 *
 * <ol>
 *   <li>Scan the target directory for interesting files (db, log, index)
 *   <li>Randomly open them with shared or exclusive locks
 *   <li>Hold the lock for a short duration
 *   <li>Release and repeat
 * </ol>
 *
 * <p><b>Lane F stage A item A12 — copied verbatim in behaviour from
 * {@code systemtests.torture.FileIntruder}</b>, which is deleted with the rest of the system-tests
 * torture tier. The intruder never had anything to do with the second process: it locks files on
 * disk, and the Engine's index half opens exactly the same files it did. Two things changed and
 * neither is behaviour: the SLF4J logging is gone (this module's test source set does not declare
 * an SLF4J dependency, and the log lines were debug-only), and the class is package-private in
 * {@code io.justsearch.app.engine} rather than public.
 *
 * <p><b>{@code .lock} files are deliberately skipped</b> (the original's "too rude" comment). That
 * exclusion is load-bearing here: {@code IndexRootLock} holds
 * {@code <indexBase>.index.lock} for the Engine's whole lifetime, and an intruder that took it
 * would be testing whether the JDK honours file locks, not whether the index half survives a
 * hostile filesystem.
 */
final class FileIntruder implements AutoCloseable {

  private final Path targetDir;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final List<Thread> intruderThreads = new ArrayList<>();
  private final Random random = new Random();

  // Keep track of open channels/locks to close them properly.
  private final List<FileLock> activeLocks = new CopyOnWriteArrayList<>();
  private final List<FileChannel> activeChannels = new CopyOnWriteArrayList<>();

  FileIntruder(Path targetDir) {
    this.targetDir = targetDir;
  }

  /**
   * Starts the intruder threads.
   *
   * @param threadCount number of concurrent intruder threads
   * @param intensityMs average duration to hold a lock, in milliseconds
   */
  void start(int threadCount, int intensityMs) {
    if (running.getAndSet(true)) {
      return;
    }
    for (int i = 0; i < threadCount; i++) {
      Thread t = new Thread(() -> runIntruderLoop(intensityMs), "intruder-" + i);
      t.setDaemon(true);
      t.start();
      intruderThreads.add(t);
    }
  }

  private void runIntruderLoop(int intensityMs) {
    while (running.get()) {
      try {
        if (!Files.exists(targetDir)) {
          Thread.sleep(100);
          continue;
        }

        // Find a victim file.
        List<Path> victims = new ArrayList<>();
        try (var stream = Files.walk(targetDir)) {
          stream
              .filter(Files::isRegularFile)
              // Do not lock lock files (too rude) — see the class javadoc.
              .filter(p -> !p.getFileName().toString().endsWith(".lock"))
              .forEach(victims::add);
        }

        if (victims.isEmpty()) {
          Thread.sleep(100);
          continue;
        }

        Path victim = victims.get(random.nextInt(victims.size()));
        lockAndHold(victim, intensityMs);

        // Random sleep between attacks.
        Thread.sleep(random.nextInt(intensityMs * 2));

      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        // Ignore errors — we are the chaos.
      }
    }
  }

  private void lockAndHold(Path file, int durationMs) {
    try {
      // Randomly choose a shared (read) or exclusive (write) lock.
      boolean exclusive = random.nextBoolean();

      FileChannel channel =
          FileChannel.open(
              file,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE); // Write access is needed for an exclusive lock.

      activeChannels.add(channel);

      try {
        FileLock lock = channel.tryLock(0, Long.MAX_VALUE, !exclusive);
        if (lock != null) {
          activeLocks.add(lock);
          Thread.sleep(random.nextInt(durationMs) + 1);
          lock.release();
          activeLocks.remove(lock);
        }
      } finally {
        channel.close();
        activeChannels.remove(channel);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      // The lock failed (the file is already locked by the index half?). Expected.
    }
  }

  @Override
  public void close() {
    running.set(false);
    for (Thread t : intruderThreads) {
      t.interrupt();
    }
    // A12 addition to the copied original: a bounded join before the handles are released. The
    // retired version cleared the thread list immediately, which left a window in which a thread
    // sitting between FileChannel.open and activeChannels.add still held a handle nobody tracked
    // — on Windows that is exactly what turns a @TempDir teardown into a spurious test error.
    for (Thread t : intruderThreads) {
      try {
        t.join(2_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    intruderThreads.clear();

    // Clean up any dangling locks.
    for (FileLock lock : activeLocks) {
      try {
        lock.release();
      } catch (Exception e) {
        // Teardown of a harness that deliberately raced the OS on these handles: the lock may
        // already be gone with the channel its holder closed. Failing here would mask the
        // torture result the caller is about to read.
      }
    }
    for (FileChannel ch : activeChannels) {
      try {
        ch.close();
      } catch (Exception e) {
        // Same: best-effort teardown, see above.
      }
    }
    activeLocks.clear();
    activeChannels.clear();
  }
}
