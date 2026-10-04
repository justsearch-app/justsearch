package io.justsearch.app.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Windows-specific: exercises the single-instance lock's process-liveness semantics
// (and spawns cmd.exe). Runs only on the dedicated Windows lane (tempdoc 668 option B).
@Tag("windows")
class AppInstanceLockTest {

  @TempDir Path tempDir;

  @Test
  void acquire_failsWhenAlreadyHeld() throws Exception {
    Path dataDir = tempDir.resolve("data");
    Files.createDirectories(dataDir);

    AppInstanceLock lock1 = new AppInstanceLock(dataDir);
    lock1.acquire();
    try {
      AppInstanceLock lock2 = new AppInstanceLock(dataDir);
      var ex =
          assertThrows(AppInstanceLock.AppInstanceLockException.class, lock2::acquire);
      assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("another justsearch instance"));
    } finally {
      lock1.close();
    }
  }

  @Test
  void close_allowsReacquire() throws Exception {
    Path dataDir = tempDir.resolve("data2");
    Files.createDirectories(dataDir);

    AppInstanceLock lock1 = new AppInstanceLock(dataDir);
    lock1.acquire();
    lock1.close();

    AppInstanceLock lock2 = new AppInstanceLock(dataDir);
    lock2.acquire();
    lock2.close();
  }

  @Test
  void acquire_recoversFromDeadProcess() throws Exception {
    Path dataDir = tempDir.resolve("data-stale");
    Files.createDirectories(dataDir);

    // Write a fake lock file with a non-existent PID (99999999).
    Path lockFile = dataDir.resolve("app.lock");
    Files.writeString(lockFile, "pid=99999999\nstarted_at=2025-01-01T00:00:00Z\n");

    // Acquire should succeed — the stale lock file should be recovered.
    AppInstanceLock lock = new AppInstanceLock(dataDir);
    assertDoesNotThrow(lock::acquire);
    assertTrue(lock.isHeld());
    lock.close();
  }

  @Test
  void acquire_recoversFromRecentlyDeadProcess() throws Exception {
    Path dataDir = tempDir.resolve("data-recent-dead");
    Files.createDirectories(dataDir);

    // Spawn a real process and wait for it to exit
    Process p = new ProcessBuilder("cmd.exe", "/c", "exit", "0").start();
    p.waitFor();
    long deadPid = p.pid();

    // Write lock file with the real (now-dead) PID and a plausible start time
    Path lockFile = dataDir.resolve("app.lock");
    Files.writeString(lockFile, "pid=" + deadPid + "\nstarted_at=2025-01-01T00:00:00Z\n");

    // Acquire should recover — ProcessHandle.of(deadPid) should return empty
    AppInstanceLock lock = new AppInstanceLock(dataDir);
    assertDoesNotThrow(lock::acquire);
    assertTrue(lock.isHeld());
    lock.close();
  }

}
