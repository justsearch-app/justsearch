/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.MigrationTransitionBarrier;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.RootWatcherRegistry;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.WatchRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

@Timeout(240)
final class EngineMigrationWatcherRemovalTest {
  @Test
  void removedFolderCannotIndexNewFilesWhileNestedFolderStillIndexes(@TempDir Path directory)
      throws Exception {
    Path data = Files.createDirectories(directory.resolve("data"));
    Path parent = Files.createDirectories(directory.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("child"));
    Files.writeString(data.resolve("watched_roots.json"), JsonMapper.builder().build()
        .writeValueAsString(Map.of("schemaVersion", 1, "roots", List.of(
            Map.of("path", parent.toString(), "collection", "parent"),
            Map.of("path", child.toString(), "collection", "child")))));
    var barrier = new MigrationTransitionBarrier.Controlled("migration-before-switching");
    var previousConfiguration = ConfigStore.globalOrNull();
    try (var ignored = knownVolume(); var engine = EngineTestHarness.start(data, barrier)) {
      try {
        try (var initial = engine.captureServingView()) {
          for (Path root : List.of(parent, child)) {
            assertTrue(initial.services().ingestService().watchRoot(WatchRootRequest.newBuilder()
                .setRootPath(root.toString()).setCollection(root.equals(parent) ? "parent" : "child")
                .setRootAdded(true).build(), CallContext.none()).getWatching());
          }
        }
        assertTrue(engine.client().startMigration("watcher-removal", TestEngineContexts.FOREGROUND).accepted());
        assertTrue(barrier.awaitReached(120, TimeUnit.SECONDS), "hold the live migration before cutover");
        // The harness's control client has an in-memory authority. Removal uses the durable
        // authority shared with the real Worker, through the same Engine adapter as production.
        try (var live = engine.captureServingView();
            var calls = new DefaultEngineExecutorRegistry();
            var removalClient = new EngineKnowledgeClient(calls, live::services,
                new ForegroundLoadGate(new ForegroundLoad()), 30_000, 100, IpcTelemetry.noop(),
                () -> {}, new EngineAdmissionController(8, 8, 1), WatchedRootsState.load(data))) {
          assertFalse(live.ingestRuntime().openedIndexPath().equals(live.searchRuntime().openedIndexPath()));
          var worker = live.services().ingestService();
          var retired = subscription(worker, parent);
          var retained = subscription(worker, child);
          assertNotNull(retired);
          assertNotNull(retained);
          assertTrue(removalClient.removeWatchedPath(parent, TestEngineContexts.FOREGROUND) >= 0);
          assertNull(subscription(worker, parent), "successful removal retires the real watcher");
          assertNotNull(subscription(worker, child));

          Path removedFile = parent.resolve("new-removed.txt");
          Path keptFile = child.resolve("new-kept.txt");
          var committed = new CountDownLatch(1);
          var callbackFailure = new AtomicReference<Exception>();
          var commits = live.ingestRuntime().commitOps();
          var listenerField = CommitOps.class.getDeclaredField("commitCompletedListener");
          listenerField.setAccessible(true);
          var previous = (CommitOps.CommitCompletedListener) listenerField.get(commits);
          commits.setCommitCompletedListener(reason -> {
            if (previous != null) previous.onCommitCompleted(reason);
            try {
              commits.maybeRefreshBlocking();
              if (live.ingestRuntime().indexCountOps().countByIdAndChunksStrict(
                  PathNormalizer.normalizeKey(keptFile)) > 0) committed.countDown();
            } catch (Exception failure) {
              callbackFailure.set(failure);
              committed.countDown();
            }
          });
          try {
            Files.writeString(removedFile, "removedwatcherregression");
            Files.writeString(keptFile, "retainedwatcherregression");
            // Drive callbacks synchronously at the production routing boundary. The old
            // incarnation must reject even an already-issued event; C uses the real queue/loop.
            worker.acceptWatcherEvent(retired,
                () -> worker.acceptWatcherUpsert(retired, "parent", removedFile));
            worker.acceptWatcherEvent(retained,
                () -> worker.acceptWatcherUpsert(retained, "child", keptFile));
            assertTrue(committed.await(120, TimeUnit.SECONDS), "the nested file must actually index and commit");
            assertNull(callbackFailure.get());
            for (var runtime : List.of(live.searchRuntime(), live.ingestRuntime())) {
              runtime.commitOps().maybeRefreshBlocking();
              assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(
                  PathNormalizer.normalizeKey(removedFile)), "the removed folder cannot index new files");
              assertTrue(runtime.indexCountOps().countByIdAndChunksStrict(
                  PathNormalizer.normalizeKey(keptFile)) > 0, "the nested folder keeps indexing");
            }
          } finally {
            commits.setCommitCompletedListener(previous);
          }
        }
      } finally {
        barrier.release();
      }
    } finally {
      var installed = ConfigStore.globalOrNull();
      if (installed != null) ConfigStore.restoreGlobal(installed, previousConfiguration);
    }
  }

  private static AutoCloseable knownVolume() throws Exception {
    // Reuse the volume-evidence seam used by NoVolumeIdentityExtension. Native directory
    // identity, watcher registration, event routing, queue, indexing and commits stay real.
    var field = Class.forName("io.justsearch.indexerworker.services.RootIdentity")
        .getDeclaredField("VOLUME_IDENTITY_LOOKUP");
    field.setAccessible(true);
    Object previous = field.get(null);
    String volume = System.getProperty("os.name", "").startsWith("Windows")
        ? "windows-volume:739" : "linux-volume-v1:739:ext4:Lw";
    field.set(null, (java.util.function.Function<Path, String>) ignored -> volume);
    return () -> field.set(null, previous);
  }

  private static RootWatcherRegistry.Subscription subscription(WorkerIngestService worker, Path root)
      throws Exception {
    var field = WorkerIngestService.class.getDeclaredField("rootWatcherRegistry");
    field.setAccessible(true);
    var method = RootWatcherRegistry.class.getDeclaredMethod("subscription", Path.class);
    method.setAccessible(true);
    return (RootWatcherRegistry.Subscription) method.invoke(field.get(worker), root);
  }
}
