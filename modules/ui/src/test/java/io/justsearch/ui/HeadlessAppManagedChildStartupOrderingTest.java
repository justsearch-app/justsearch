/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HeadlessAppManagedChildStartupOrderingTest {
  @Test
  void heldOwnershipSeedBlocksReconciliationAndChildCapableAsyncStart(@TempDir Path tmp)
      throws Exception {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    CountDownLatch seedCommitEntered = new CountDownLatch(1);
    CountDownLatch releaseSeedCommit = new CountDownLatch(1);
    AtomicBoolean reconciled = new AtomicBoolean();
    AtomicBoolean childStarted = new AtomicBoolean();
    publisher.addListener(
        ignored -> {
          seedCommitEntered.countDown();
          try {
            releaseSeedCommit.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    try (var caller = Executors.newSingleThreadExecutor()) {
      var started =
          caller.submit(
              () ->
                  HeadlessApp.startChildCapableAsyncAfterOwnershipReconciliation(
                      publisher,
                      () -> reconciled.set(true),
                      () -> {
                        childStarted.set(true);
                        return true;
                      }));

      assertTrue(seedCommitEntered.await(5, TimeUnit.SECONDS));
      assertFalse(reconciled.get());
      assertFalse(childStarted.get());
      releaseSeedCommit.countDown();

      assertTrue(started.get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS));
      assertTrue(reconciled.get());
      assertTrue(childStarted.get());
      assertTrue(java.nio.file.Files.isRegularFile(publisher.manifestPath()));
    }
  }
}
