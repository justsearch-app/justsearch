/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 834 Â§5.2 â€” the unlock seam's two load-bearing properties, tested rather than asserted in a
 * comment: the scan must not run under the key monitor, and it must not be able to break unlock.
 */
final class UnlockDeferredScanTest {

  @TempDir Path dataDir;

  private DataKeyManager configured() {
    var m = new DataKeyManager(new EncryptionKeystore(dataDir));
    m.setup("passphrase".toCharArray());
    m.lock();
    return m;
  }

  @Test
  @DisplayName("unlock() returns while the scan is still running â€” the key monitor is not held")
  void scanDoesNotBlockTheKeyMonitor() throws Exception {
    DataKeyManager keys = configured();
    var scanStarted = new CountDownLatch(1);
    var releaseScan = new CountDownLatch(1);
    try (var seam =
        new UnlockDeferredScan(new io.justsearch.core.execution.TestEngineExecutors(),
                "test-scan",
                () -> {
                  scanStarted.countDown();
                  try {
                    releaseScan.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                })
            .attachTo(keys)) {

      keys.unlock("passphrase".toCharArray());

      // If the listener ran the scan inline, `fire` â€” and therefore the synchronized unlock() â€” would
      // still be blocked on releaseScan, and this call could not have returned at all.
      assertTrue(scanStarted.await(5, TimeUnit.SECONDS), "the scan must actually have been scheduled");
      assertEquals(
          DataKeyManager.State.UNLOCKED,
          keys.state(),
          "state() is synchronized too: reaching it proves the monitor was released");

      releaseScan.countDown();
      assertTrue(seam.awaitQuiescence(Duration.ofSeconds(5)));
    }
  }

  @Test
  @DisplayName("a throwing scan breaks neither unlock nor the next scan")
  void aThrowingScanIsContained() {
    DataKeyManager keys = configured();
    var runs = new AtomicInteger();
    try (var seam =
        new UnlockDeferredScan(new io.justsearch.core.execution.TestEngineExecutors(),
                "test-scan",
                () -> {
                  runs.incrementAndGet();
                  throw new IllegalStateException("scan blew up");
                })
            .attachTo(keys)) {

      keys.unlock("passphrase".toCharArray());
      assertTrue(seam.awaitQuiescence(Duration.ofSeconds(5)));
      assertEquals(DataKeyManager.State.UNLOCKED, keys.state(), "unlock must still have succeeded");
      assertEquals(1, runs.get());

      // DataKeyManager.fire swallows listener throws, so a fault here would be INVISIBLE rather than
      // loud â€” and a dead executor would silently stop reconciling every later unlock.
      keys.lock();
      keys.unlock("passphrase".toCharArray());
      assertTrue(seam.awaitQuiescence(Duration.ofSeconds(5)));
      assertEquals(2, runs.get(), "the worker must survive a failed scan");
    }
  }

  @Test
  @DisplayName("only transitions INTO unlocked schedule a scan")
  void lockDoesNotSchedule() {
    DataKeyManager keys = configured();
    var runs = new AtomicInteger();
    try (var seam = new UnlockDeferredScan(new io.justsearch.core.execution.TestEngineExecutors(), "test-scan", runs::incrementAndGet).attachTo(keys)) {
      keys.unlock("passphrase".toCharArray());
      keys.lock();
      assertTrue(seam.awaitQuiescence(Duration.ofSeconds(5)));
      assertEquals(1, runs.get());
    }
  }
}
