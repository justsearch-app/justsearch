/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import io.justsearch.app.services.worker.CancelToken;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.services.RootWatcherRegistry;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Exact-declaration removal through the Engine, real recorded admissions and watcher registry. */
@Timeout(30)
final class EngineUndeclaredParentRemovalTest {
  @Test
  void undeclaredParentPreservesDeclaredChildAndRecordedJobs(@TempDir Path directory) throws Exception {
    try (var fixture = EngineRootRetirementTest.fixture(directory, 3, IndexingPacing.unthrottled(), true)) {
      var watchers = new RootWatcherRegistry();
      fixture.service().setRootWatcherRegistry(watchers);
      assertTrue(fixture.client().watchRoot(fixture.root().toString(), null,
          TestEngineContexts.FOREGROUND).getWatching());
      var watchedRoots = RootWatcherRegistry.class.getDeclaredMethod("watchedRoots");
      watchedRoots.setAccessible(true);
      var expectedRoots = Set.of(fixture.root());
      assertEquals(expectedRoots, watchedRoots.invoke(watchers));
      assertEquals(expectedRoots, Set.copyOf(fixture.roots().watchedPaths()));
      assertEquals(expectedRoots, Set.copyOf(WatchedRootsState.load(fixture.data()).watchedPaths()));
      String key = "undeclared-parent-recorded-walk";
      long epoch = fixture.queue().beginRecordedWalk(key, "b".repeat(64), true).enumerationEpoch();
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE,
          fixture.client().enumerateWatchedRecordedRoot(fixture.plan(), key, epoch,
              TestEngineContexts.FOREGROUND, new CancelToken()).toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertEquals(3, fixture.queue().queueDepth(), "the declared child's recorded jobs really are queued");
      var queuedState = fixture.queue().jobStateCounts();
      var index = fixture.runtime().indexingCoordinator();
      clearInvocations(fixture.runtime(), index, fixture.service());

      assertEquals(0, fixture.client().removeWatchedPath(fixture.root().getParent(),
          TestEngineContexts.FOREGROUND), "an undeclared parent must not retire its declared child");

      assertEquals(expectedRoots, Set.copyOf(fixture.roots().watchedPaths()));
      assertEquals(expectedRoots, Set.copyOf(WatchedRootsState.load(fixture.data()).watchedPaths()));
      assertEquals(expectedRoots, watchedRoots.invoke(watchers));
      assertEquals(3, fixture.queue().queueDepth());
      assertEquals(queuedState, fixture.queue().jobStateCounts(), "queued recorded work is unchanged");
      // No worker mutation or index write may be issued, including prefix deletions of documents/chunks.
      verifyNoInteractions(fixture.service(), fixture.runtime(), index);
    }
  }
}
