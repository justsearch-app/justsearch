/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.WatchedRootsFormat;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.SyncDirectoryRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

final class DeclaredCollectionRegistryCacheTest {
  @TempDir Path data;

  @Test
  void candidateReconciliationParsesRegistryOnceWithRealQueue() throws Exception {
    Path root = Files.createDirectory(data.resolve("source"));
    for (int i = 0; i < 64; i++) Files.writeString(root.resolve("file" + i + ".txt"), "content");
    declarations(root, null);
    Path base = data.resolve("index");
    var manager = new IndexGenerationManager(base);
    var layout = manager.initializeOrLoad();
    manager.startMigration("cache-check");
    var reads = mock(ReadPathOps.class);
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenReturn(new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0));
    var serving = mock(RunningRuntime.class);
    var candidate = mock(RunningRuntime.class);
    when(serving.isAcceptingWrites()).thenReturn(true);
    when(candidate.isAcceptingWrites()).thenReturn(true);
    when(serving.readPathOps()).thenReturn(reads);
    try (var queue = new SqliteJobQueue(data.resolve("jobs.db"))) {
      queue.open();
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          base, layout.activeGenerationPath(), candidate, serving, null, 0L);
      service.setDeclaredRootsDirectory(data);
      var registry = new RootWatcherRegistry();
      assertTrue(registry.watch(root.toString(), null).watching());
      service.setRootWatcherRegistry(registry);
      var parses = new AtomicInteger();
      try (var format = mockStatic(WatchedRootsFormat.class, CALLS_REAL_METHODS)) {
        format.when(() -> WatchedRootsFormat.declaredCollections(data)).thenAnswer(call -> {
          parses.incrementAndGet();
          return call.callRealMethod();
        });
        var response = service.syncDirectory(SyncDirectoryRequest.newBuilder()
            .setRootPath(root.toString()).build(), CallContext.none());
        assertEquals("", response.getError());
        assertEquals(64, response.getFilesAdded());
        assertEquals(64, queue.listSwitchBufferOpsStrict().size());
        assertEquals(1, parses.get(), "candidate discovery and all 64 real queue admissions share the parsed registry");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void watcherAndSingleFileAdmissionsReuseParseAndSeeRegistryChanges(boolean watcher) throws Exception {
    Path root = Files.createDirectory(data.resolve("source"));
    Path file = Files.writeString(root.resolve("file.txt"), "content");
    declarations(root, "A");
    try (var queue = new SqliteJobQueue(data.resolve("jobs.db"))) {
      queue.open();
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          null, null, null, null, null, 0L);
      service.setDeclaredRootsDirectory(data);
      var registry = new RootWatcherRegistry();
      assertTrue(registry.watch(root.toString(), null).watching());
      service.setRootWatcherRegistry(registry);
      var parses = new AtomicInteger();
      try (var format = mockStatic(WatchedRootsFormat.class, CALLS_REAL_METHODS)) {
        format.when(() -> WatchedRootsFormat.declaredCollections(data)).thenAnswer(call -> {
          parses.incrementAndGet();
          return call.callRealMethod();
        });
        for (int i = 0; i < 64; i++) admit(service, registry, root, file, watcher);
        assertEquals(1, parses.get(), "64 independent admissions parse one unchanged registry once");
        var claims = queue.pollPending(1);
        assertEquals("A", claims.getFirst().collection());
        queue.returnUnfinishedClaims(claims);
        // Same byte length and timestamp: atomic replacement must still invalidate by file key.
        var stamp = Files.getLastModifiedTime(data.resolve("watched_roots.json"));
        declarations(root, "B");
        Files.setLastModifiedTime(data.resolve("watched_roots.json"), stamp);
        admit(service, registry, root, file, watcher);
        claims = queue.pollPending(1);
        assertEquals("B", claims.getFirst().collection());
        queue.returnUnfinishedClaims(claims);
        declarations(null, null);
        admit(service, registry, root, file, watcher);
        claims = queue.pollPending(1);
        assertNull(claims.getFirst().collection(), "removal exposes the default subscription");
        queue.returnUnfinishedClaims(claims);
        declarations(root, "C");
        admit(service, registry, root, file, watcher);
        assertEquals("C", queue.pollPending(1).getFirst().collection());
        assertEquals(4, parses.get(), "change, removal and addition each invalidate the parsed snapshot");
      }
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void reappearedFileFetchesDeclarationSnapshotOutsideQueueLock() throws Exception {
    Path root = Files.createDirectory(data.resolve("source"));
    Path file = Files.writeString(root.resolve("file.txt"), "content");
    declarations(root, "C");
    try (var queue = new SqliteJobQueue(data.resolve("jobs.db"))) {
      queue.open();
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          null, null, null, null, null, 0L);
      service.setDeclaredRootsDirectory(data);
      var lockField = SqliteJobQueue.class.getDeclaredField("lock");
      lockField.setAccessible(true);
      var lock = (ReentrantLock) lockField.get(queue);
      var supplierField = SqliteJobQueue.class.getDeclaredField("declaredCollectionRoots");
      supplierField.setAccessible(true);
      var supplier = (Supplier<List<io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding>>) supplierField.get(queue);
      var probes = new AtomicInteger();
      queue.useDeclaredCollectionRoots(() -> {
        probes.incrementAndGet();
        assertFalse(lock.isHeldByCurrentThread(), "even the cache identity probe must run outside the queue lock");
        return supplier.get();
      });
      assertTrue(queue.enqueueReappearedFile(JobQueue.EnqueueEntry.stat(file), null));
      assertEquals(1, probes.get());
      assertEquals("C", queue.pollPending(1).getFirst().collection());
    }
  }

  private static void admit(WorkerIngestService service, RootWatcherRegistry registry,
      Path root, Path file, boolean watcher) {
    if (watcher) service.acceptWatcherUpsert(registry.subscription(root), null, file);
    else assertEquals(1, service.submitBatch(BatchRequest.newBuilder()
        .addFilePaths(file.toString()).build(), CallContext.none()).getAcceptedCount());
  }

  private void declarations(Path root, String collection) throws Exception {
    var binding = new java.util.LinkedHashMap<String, String>();
    if (root != null) binding.put("path", root.toString());
    if (collection != null) binding.put("collection", collection);
    AtomicFileWrites.replace(data.resolve("watched_roots.json"), new ObjectMapper().writeValueAsBytes(
        Map.of("schemaVersion", 1, "roots", root == null ? List.of() : List.of(binding))));
  }
}
