/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.WatchRootRequest;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Lucene/SQLite swap failures; only model/service construction is substituted. */
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class WatcherReloadRetryTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @Test void failedOpenerThenFailedReconstructionRetainsDeleteUntilRetry() throws Exception {
    var layout = WorkerBootFixture.layout(temp);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "fail_closed");
    Path root = Files.createDirectory(temp.resolve("watched"));
    Path source = Files.writeString(root.resolve("removed.txt"), "remove this content");
    try (var executors = new TestEngineExecutors();
         var queue = new SqliteJobQueue(layout.dataDir().resolve("jobs.db"))) {
      queue.open();
      var current = new AtomicReference<RunningRuntime>();
      current.set(open(layout.activePath()));
      RunningRuntime original = current.get();
      original.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, source.toString(), SchemaFields.DOC_UID, "reload-uid",
          SchemaFields.PATH, source.toString(), SchemaFields.CONTENT, "remove this content")));
      original.commitOps().commitAndTrack(CommitReason.DRAIN);
      original.commitOps().maybeRefreshBlocking();
      assertEquals(1, original.indexCountOps().countByIdAndChunksStrict(source.toString()));
      WorkerIngestService oldIngest = ingest(queue, original, layout);
      assertTrue(oldIngest.watchRoot(WatchRootRequest.newBuilder().setRootPath(root.toString())
          .setRootAdded(true).setCollection("docs").build(), CallContext.none()).getWatching());
      KnowledgeServer server = spy(new KnowledgeServer(executors,
          WorkerBootFixture.workerConfig(layout.dataDir())));
      var incumbent = mock(DefaultWorkerAppServices.class, RETURNS_DEEP_STUBS);
      when(incumbent.ingestService()).thenReturn(oldIngest);
      when(incumbent.resolvedConfig()).thenReturn(io.justsearch.configuration.resolved.ConfigStore.global().get());
      server.appServices = incumbent;
      set(server, "ingestLifecycle", original);
      set(server, "searchLifecycle", original);
      set(server, "activeIndexPath", layout.activePath());
      set(server, "indexGenerationManager", layout.genManager());
      set(server, "jobQueue", queue);
      try {
        assertThrows(IllegalStateException.class, () -> server.swapRuntime(() -> {
          assertFalse(original.isAcceptingWrites());
          try { Files.delete(source); } catch (Exception failure) { throw new AssertionError(failure); }
          oldIngest.acceptWatcherDelete(source.toString(), () -> {
            if (original.isAcceptingWrites()) original.indexingCoordinator().deleteByIdAndChunks(source.toString());
          });
          throw new IllegalStateException("injected opener failure");
        }, Duration.ofSeconds(3), SwapReason.CONFIG_RELOAD));
        assertFalse(queue.listSwitchBufferOpsStrict().isEmpty(),
            "a failed opener cannot discard a settled watcher obligation");

        doThrow(new IllegalStateException("injected service reconstruction failure"))
            .when(server).newAppServices();
        assertThrows(IllegalStateException.class, () -> server.swapRuntime(() -> {
          try { current.set(open(layout.activePath())); return current.get(); }
          catch (Exception failure) { throw new AssertionError(failure); }
        }, Duration.ofSeconds(3), SwapReason.CONFIG_RELOAD));
        assertFalse(queue.listSwitchBufferOpsStrict().isEmpty());

        var replacement = mock(DefaultWorkerAppServices.class, RETURNS_DEEP_STUBS);
        when(replacement.resolvedConfig()).thenReturn(io.justsearch.configuration.resolved.ConfigStore.global().get());
        doAnswer(ignored -> {
          when(replacement.ingestService()).thenReturn(ingest(queue, current.get(), layout));
          return replacement;
        }).when(server).newAppServices();
        server.swapRuntime(() -> {
          try { current.set(open(layout.activePath())); return current.get(); }
          catch (Exception failure) { throw new AssertionError(failure); }
        }, Duration.ofSeconds(3), SwapReason.CONFIG_RELOAD);
        current.get().commitOps().maybeRefreshBlocking();
        assertEquals(0, current.get().indexCountOps().countByIdAndChunksStrict(source.toString()));
        assertTrue(queue.listSwitchBufferOpsStrict().isEmpty(), "only committed effects retire debt");
      } finally {
        server.close();
        current.get().close();
      }
    }
  }

  private RunningRuntime open(Path path) throws Exception {
    return IndexSchema.fromCatalog(WorkerBootFixture.productionCatalog()).atPath(path)
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static WorkerIngestService ingest(SqliteJobQueue queue, RunningRuntime runtime,
      WorkerBootFixture.Layout layout) {
    return new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
        layout.indexBase(), layout.activePath(), runtime, runtime, null, 0L);
  }

  private static void set(KnowledgeServer server, String name, Object value) throws Exception {
    Field field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(server, value);
  }
}
