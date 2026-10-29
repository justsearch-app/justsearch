/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * #758 — a reconcile the Worker's watcher asked for (an
 * event overflow) and had not yet run when the person removed the root is dropped. Run, it would
 * sync the removed root: index whatever folder is at its path and record its volume.
 *
 * <p>The watcher's own reconcile executor (one thread) is held by another root's reconcile while the
 * overflow of the root to be removed waits behind it; the root is removed through the watcher
 * registry's unwatch, the Worker side of the Head's removal.
 */
final class RemovedRootWatcherReconcileTest {
  @TempDir Path tempDir;

  @Test
  @Timeout(30)
  void aReconcileAskedForBeforeTheRootWasRemovedNeverRuns() throws Exception {
    Path busy = Files.createDirectories(tempDir.resolve("busy"));
    Path removed = Files.createDirectories(tempDir.resolve("removed"));
    Path after = Files.createDirectories(tempDir.resolve("after"));
    CountDownLatch busyEntered = new CountDownLatch(1);
    CountDownLatch releaseBusy = new CountDownLatch(1);
    CountDownLatch afterRan = new CountDownLatch(1);
    List<Path> reconciled = new CopyOnWriteArrayList<>();

    try (WorkerMethvinWatcher watcher =
        new WorkerMethvinWatcher(
            TestWorkerExecutorRegistrations.watcher(),
            mock(JobQueue.class),
            null,
            path -> { },
            (root, force) -> {
              reconciled.add(root);
              if (root.equals(busy)) {
                busyEntered.countDown();
                try {
                  assertTrue(releaseBusy.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(e);
                }
              }
              if (root.equals(after)) {
                afterRan.countDown();
              }
            })) {
      RootWatcherRegistry registry = new RootWatcherRegistry(watcher);
      watcher.handleOverflow(busy, busy);
      assertTrue(busyEntered.await(10, TimeUnit.SECONDS), "precondition: the executor is held");
      watcher.handleOverflow(removed, removed);

      registry.unwatch(removed.toString());
      watcher.handleOverflow(after, after);
      releaseBusy.countDown();

      assertTrue(afterRan.await(10, TimeUnit.SECONDS),
          "precondition: everything queued behind the held reconcile has had its turn");
      assertEquals(List.of(busy, after), List.copyOf(reconciled),
          "the reconcile asked for before the root was removed is dropped, never run");
    }
  }
}
