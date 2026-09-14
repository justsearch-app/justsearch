/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.ScanRootProgress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
final class RootCompletionMembershipTest {
  @TempDir Path temp;

  @Test
  void removalCannotLandBetweenCompletionMembershipCheckAndTimestampWrite() throws Exception {
    Path path = Files.createDirectories(temp.resolve("root")).toAbsolutePath().normalize();
    var roots = new PausedMembership();
    var state = new WatchedRootsState(roots, new WatchedRootsStore(null, null));
    var queued = new AtomicReference<Consumer<EngineContext>>();
    var ops = new RootLifecycleOps(roots, state, ExcludeMatcher::empty,
        (root, collection, mode, excludes, progress, context) -> {
          var terminal = ScanRootProgress.newBuilder().setFilesAdmitted(1).setComplete(true).build();
          progress.accept(terminal);
          return terminal;
        }, mock(RootLifecycleOps.WorkerWatchFn.class), (root, context) -> null,
        (id, context) -> null, mock(SyncOps.class), mock(ExecutorService.class),
        (body, context) -> queued.set(body));
    ops.addWatchedRoot("notes", path, TestEngineContexts.internal());
    var walking = new FutureTask<Void>(() -> {
      queued.get().accept(TestEngineContexts.internal());
      return null;
    });
    Thread walker = Thread.ofPlatform().unstarted(walking);
    roots.walker = walker;
    var removing = new FutureTask<Void>(() -> {
      state.removeRootAndNested(path);
      return null;
    });
    Thread remover = Thread.ofPlatform().unstarted(removing);
    try {
      walker.start();
      assertTrue(roots.checked.await(3, TimeUnit.SECONDS));
      remover.start();
      long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
      while (!removing.isDone() && remover.getState() != Thread.State.BLOCKED
          && System.nanoTime() < deadline) Thread.sleep(5);
      assertTrue(removing.isDone() || remover.getState() == Thread.State.BLOCKED,
          "removal must reach the state owner before the completion check is released");
      roots.release.countDown();
      walking.get(3, TimeUnit.SECONDS);
      removing.get(3, TimeUnit.SECONDS);
      assertFalse(roots.containsKey(path), "completed walk must not resurrect a removed root");
      assertTrue(roots.isEmpty());
      assertNull(state.getCollection(path));
    } finally {
      roots.release.countDown();
      walker.join(Duration.ofSeconds(3));
      if (remover.getState() != Thread.State.NEW) remover.join(Duration.ofSeconds(3));
    }
  }

  /** Pause after the walk has observed membership, before that true result reaches its caller. */
  private static final class PausedMembership extends ConcurrentHashMap<Path, Instant> {
    private static final long serialVersionUID = 1L;
    private final CountDownLatch checked = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private Thread walker;
    private int walkerChecks;

    @Override public boolean containsKey(Object key) {
      boolean present = super.containsKey(key);
      if (Thread.currentThread() == walker && ++walkerChecks == 2) {
        checked.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("completion was not released");
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new AssertionError(failure);
        }
      }
      return present;
    }
  }
}
