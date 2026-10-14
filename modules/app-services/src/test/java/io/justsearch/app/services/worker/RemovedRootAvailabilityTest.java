/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.ipc.DeleteByPathResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, round 8, scenario S8 — removing a root keeps nothing of it. The Head
 * keeps the last answer of each root's background availability check; a root removed while it read
 * unavailable and added again once its folder is back must not show the removed root's
 * "unavailable" answer.
 *
 * <p>Real conditions for the folder (it is deleted, then made again); the Worker's removal and add
 * calls answer as a healthy Worker does.
 */
@Timeout(30)
final class RemovedRootAvailabilityTest {
  private static final String ROOT_UNAVAILABLE_REASON = "ROOT_NOT_DIRECTORY";

  @TempDir Path temp;

  @Test
  void aRootAddedAgainDoesNotShowTheRemovedRootsUnavailableAnswer() throws Exception {
    Path root = Files.createDirectories(temp.resolve("share")).toAbsolutePath().normalize();
    ExecutorService walkExecutor = Executors.newSingleThreadExecutor();
    try {
      var roots = new ConcurrentHashMap<Path, Instant>();
      var state = new WatchedRootsState(roots, new WatchedRootsStore(null, null));
      var ops = new RootLifecycleOps(roots, state, ExcludeMatcher::empty,
          (r, collection, mode, excludes, progress, context) -> null,
          mock(RootLifecycleOps.WorkerWatchFn.class),
          (r, context) -> DeleteByPathResponse.newBuilder().setDeletedJobs(0).build(),
          (id, context) -> null, mock(SyncOps.class), walkExecutor, (body, context) -> { });
      ops.addWatchedRoot("documents", root, TestEngineContexts.internal());
      Files.delete(root);
      assertEquals(ROOT_UNAVAILABLE_REASON, settledWalkError(ops, walkExecutor),
          "precondition: the root reads unavailable while its folder is gone");

      Files.createDirectories(root);
      assertTrue(ops.removeWatchedPath(root, TestEngineContexts.internal()) >= 0,
          "precondition: the removal completes");
      ops.addWatchedRoot("documents", root, TestEngineContexts.internal());

      List<IndexingService.WatchedRoot> added = ops.getWatchedRoots(TestEngineContexts.internal());
      assertEquals(1, added.size());
      assertNull(added.get(0).walkError(),
          "the root added again does not show the removed root's unavailable answer");
    } finally {
      walkExecutor.shutdownNow();
      assertTrue(walkExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * The root's walk error once the background availability check has landed: the first read
   * schedules the check on {@code walkExecutor}; a no-op task queued behind it marks its end.
   */
  private static String settledWalkError(RootLifecycleOps ops, ExecutorService walkExecutor)
      throws Exception {
    ops.getWatchedRoots(TestEngineContexts.internal());
    walkExecutor.submit(() -> { }).get(10, TimeUnit.SECONDS);
    List<IndexingService.WatchedRoot> roots = ops.getWatchedRoots(TestEngineContexts.internal());
    assertEquals(1, roots.size());
    return roots.get(0).walkError();
  }
}
