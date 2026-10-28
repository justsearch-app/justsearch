/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.DeleteByPathResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, round 8 (refined decision 2) — a profiling reset that removes the roots
 * removes each one exactly as the person's removal does: the Worker is told to stop watching it,
 * which is what drops its volume record, its held watch and any deferred state of the root, and to
 * delete under it, so the same folder added again starts from its own add.
 *
 * <p>Real folders; the Worker's removal calls answer as a healthy Worker does and are recorded.
 */
@Timeout(30)
final class ProfilingResetRemovesRootsTest {
  @TempDir Path temp;

  @Test
  void aResetThatRemovesRootsRemovesEachOneAsThePersonsRemovalDoes() throws Exception {
    Path letters = Files.createDirectories(temp.resolve("letters")).toAbsolutePath().normalize();
    Path photos = Files.createDirectories(temp.resolve("photos")).toAbsolutePath().normalize();
    List<Path> unwatched = new CopyOnWriteArrayList<>();
    List<Path> deleted = new CopyOnWriteArrayList<>();
    RootLifecycleOps.WorkerWatchFn worker = new RootLifecycleOps.WorkerWatchFn() {
      @Override
      public void watch(String rootPath, String collection, EngineContext engineContext) {
        // The Worker watches the root.
      }

      @Override
      public void unwatch(String rootPath, EngineContext engineContext) {
        unwatched.add(Path.of(rootPath).toAbsolutePath().normalize());
      }
    };
    ExecutorService walkExecutor = Executors.newSingleThreadExecutor();
    try {
      var roots = new ConcurrentHashMap<Path, Instant>();
      var state = new WatchedRootsState(roots, new WatchedRootsStore(null, null));
      var ops = new RootLifecycleOps(roots, state, ExcludeMatcher::empty,
          (r, collection, mode, excludes, progress, context) -> null,
          worker,
          (r, context) -> {
            worker.unwatch(r.toString(), context); // Worker retirement is inside the deletion call.
            deleted.add(r.toAbsolutePath().normalize());
            return DeleteByPathResponse.newBuilder().setDeletedJobs(0).build();
          },
          (id, context) -> null, mock(SyncOps.class), walkExecutor, (body, context) -> { });
      ops.addWatchedRoot("documents", letters, TestEngineContexts.internal());
      ops.addWatchedRoot("documents", photos, TestEngineContexts.internal());
      walkExecutor.submit(() -> { }).get(10, TimeUnit.SECONDS);
      assertEquals(2, ops.getWatchedRoots(TestEngineContexts.internal()).size(),
          "precondition: both roots are watched");

      ops.clearAllRoots(TestEngineContexts.internal());

      assertEquals(Set.of(letters, photos), Set.copyOf(unwatched),
          "the reset tells the Worker to stop watching each root, which drops its records");
      assertEquals(Set.of(letters, photos), Set.copyOf(deleted),
          "the reset tells the Worker to delete under each root");
      assertTrue(ops.getWatchedRoots(TestEngineContexts.internal()).isEmpty(),
          "no root is kept");
    } finally {
      walkExecutor.shutdownNow();
      assertTrue(walkExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}
