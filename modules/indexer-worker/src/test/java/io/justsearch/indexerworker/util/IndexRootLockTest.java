package io.justsearch.indexerworker.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexRootLockTest {

  @TempDir Path tempDir;

  @Test
  void acquire_succeeds() throws Exception {
    Path indexBase = tempDir.resolve("index").resolve("default");
    Files.createDirectories(indexBase);

    IndexRootLock lock = new IndexRootLock(indexBase);
    lock.acquire();
    lock.close();
  }

  @Test
  void acquire_failsWhenAlreadyHeld() throws Exception {
    Path indexBase = tempDir.resolve("index2").resolve("default");
    Files.createDirectories(indexBase);

    IndexRootLock lock1 = new IndexRootLock(indexBase);
    lock1.acquire();
    try {
      IndexRootLock lock2 = new IndexRootLock(indexBase);
      assertThrows(IOException.class, lock2::acquire);
    } finally {
      lock1.close();
    }
  }

  @Test
  void close_allowsReacquire() throws Exception {
    Path indexBase = tempDir.resolve("index3").resolve("default");
    Files.createDirectories(indexBase);

    IndexRootLock lock1 = new IndexRootLock(indexBase);
    lock1.acquire();
    lock1.close();

    IndexRootLock lock2 = new IndexRootLock(indexBase);
    lock2.acquire();
    lock2.close();
  }

  @Test
  void acquire_recoversFromDeadProcess() throws Exception {
    Path indexDir = tempDir.resolve("index4");
    Files.createDirectories(indexDir);
    Path indexBase = indexDir.resolve("default");
    Files.createDirectories(indexBase);

    // Write a fake lock file with a non-existent PID.
    Path lockFile = indexDir.resolve("default.index.lock");
    Files.writeString(lockFile, "pid=99999999\nstarted_at=2025-01-01T00:00:00Z\n");

    IndexRootLock lock = new IndexRootLock(indexBase);
    assertDoesNotThrow(lock::acquire);
    lock.close();
  }

  @Test
  void acquire_recoversFromRecentlyDeadProcess() throws Exception {
    Path indexDir = tempDir.resolve("index5");
    Files.createDirectories(indexDir);
    Path indexBase = indexDir.resolve("default");
    Files.createDirectories(indexBase);

    // Spawn a real process and wait for it to exit (OS-appropriate throwaway so this runs on
    // Linux CI too — IndexRootLock itself is ProcessHandle-based/cross-platform; tempdoc 668).
    boolean isWindows =
        System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    Process p =
        (isWindows
                ? new ProcessBuilder("cmd.exe", "/c", "exit", "0")
                : new ProcessBuilder("sh", "-c", "exit 0"))
            .start();
    p.waitFor();
    long deadPid = p.pid();

    // Write lock file with the real (now-dead) PID
    Path lockFile = indexDir.resolve("default.index.lock");
    Files.writeString(lockFile, "pid=" + deadPid + "\nstarted_at=2025-01-01T00:00:00Z\n");

    IndexRootLock lock = new IndexRootLock(indexBase);
    assertDoesNotThrow(lock::acquire);
    lock.close();
  }

}
