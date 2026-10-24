/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.PathPrefixScope;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.WatchRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Exercises the server's enumeration wiring with real durable receipts, jobs and documents. */
final class LegacyPrefixEnumerationTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"worker", "held", "head"})
  void enumerationFreezesDeclarationsMissingFromItsScanRoots(String declaration) throws Exception {
    Path parent = Files.createDirectories(directory.resolve("parent_%"));
    Path child = parent.resolve("child");
    Path own = Files.writeString(parent.resolve("own.txt"), "parent");
    Path nested = child.resolve("away.txt");
    Path base = directory.resolve("index");
    var manager = new IndexGenerationManager(base);
    manager.initializeOrLoad();
    String generation = manager.startMigration("legacy-enumeration").building_generation();
    Path index = manager.resolveGenerationPathStrict(generation);
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(index).withExecutorRegistrations(testLuceneExecutors()).open();
        var queue = new SqliteJobQueue(directory.resolve("jobs.db"))) {
      queue.open();
      var publisher = service(queue, base, runtime);
      var registry = new RootWatcherRegistry();
      publisher.setRootWatcherRegistry(registry);
      switch (declaration) {
        case "worker" -> {
          Files.createDirectories(child);
          assertTrue(publisher.watchRoot(WatchRootRequest.newBuilder().setRootPath(child.toString())
              .setCollection("child").setRootAdded(true).build(), CallContext.none()).getWatching());
        }
        case "held" -> registry.watchWhenVolumeReturns(child, "child");
        case "head" -> {
          Files.writeString(directory.resolve("watched_roots.json"), new ObjectMapper().writeValueAsString(
              Map.of("schemaVersion", 1, "roots", List.of(Map.of("path", child.toString(), "collection", "child")))));
          publisher.setDeclaredRootsDirectory(directory);
        }
        default -> throw new AssertionError(declaration);
      }
      index(runtime, own, "parent");
      index(runtime, nested, "child");
      runtime.commitOps().commitAndTrack();
      queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(nested, 5)), "child");
      String prefix = PathNormalizer.normalizePathPrefix(parent.toString());
      assertTrue(queue.putSwitchBufferForGeneration(generation, "prefix:" + prefix, "DELETE_PREFIX", prefix));

      // The scan plan has only P. Independently declared C must come from the live authority.
      var server = mock(KnowledgeServer.class, CALLS_REAL_METHODS);
      var services = mock(WorkerAppServices.class);
      when(services.ingestService()).thenReturn(publisher);
      set(server, "appServices", services);
      set(server, "jobQueue", queue);
      set(server, "indexGenerationManager", manager);
      set(server, "running", true);
      for (String field : List.of("migrationEnumeratorFilesSeen", "migrationEnumeratorFilesEnqueued",
          "migrationEnumeratorRootsDone")) set(server, field, new AtomicLong());
      set(server, "migrationEnumeratorLastPath", new AtomicReference<>(""));
      var enumerate = KnowledgeServer.class.getDeclaredMethod("enqueueAllFilesUnderRoots", List.class);
      enumerate.setAccessible(true);
      assertEquals(1, enumerate.invoke(server, List.of(new ResolvedConfig.FileSource(parent, "parent"))));

      var receipt = queue.listSwitchBufferOpsStrictForGeneration(generation).getFirst();
      var frozen = PathPrefixScope.decode(receipt.payload());
      assertEquals(List.of(PathNormalizer.normalizePathPrefix(child.toString())), frozen.excludedSubtrees());
      assertFalse(frozen.contains(PathNormalizer.normalizeKey(nested)));
      assertTrue(queue.matchesExpectedCollection(nested, "child"));
      if (declaration.equals("worker")) assertTrue(registry.watchedRoots().contains(child));
      if (declaration.equals("held")) assertTrue(registry.awaitsVolume(child));

      queue.close();
      try (var reopened = new SqliteJobQueue(directory.resolve("jobs.db"))) {
        reopened.open();
        assertEquals(receipt.payload(), reopened.listSwitchBufferOpsStrictForGeneration(generation).getFirst().payload());
        var replay = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(reopened, runtime, null,
            IndexingPacing.unthrottled(), base, index, new ObjectMapper(), () -> false, () -> false,
            LoggerFactory.getLogger(getClass()), Long.MAX_VALUE, generation)
            .withPublisher(service(reopened, base, runtime)); // no declarations restored
        assertTrue(KnowledgeServerMigrationOps.drainSwitchBufferStrict(replay));
        runtime.commitOps().maybeRefreshBlocking();
        assertNull(runtime.documentFieldOps().getDocumentField(PathNormalizer.normalizeKey(own), SchemaFields.CONTENT));
        assertEquals("child", runtime.documentFieldOps().getDocumentField(PathNormalizer.normalizeKey(nested), SchemaFields.CONTENT));
        assertTrue(reopened.matchesExpectedCollection(nested, "child"));
        assertTrue(reopened.listSwitchBufferOpsStrict().isEmpty());
      }
    }
  }

  @Test
  void enumerationWithoutDeclarationAuthorityLeavesLegacyReceiptUnconverted() throws Exception {
    Path parent = Files.createDirectories(directory.resolve("parent"));
    String prefix = PathNormalizer.normalizePathPrefix(parent.toString());
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db"))) {
      queue.open();
      assertTrue(queue.putSwitchBufferForGeneration("green", "prefix:" + prefix, "DELETE_PREFIX", prefix));
      var context = new KnowledgeServerMigrationOps.EnqueueContext(
          List.of(new ResolvedConfig.FileSource(parent, "parent")), queue, () -> true, () -> null,
          new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicReference<>(""),
          () -> null, () -> null, ignored -> {}, LoggerFactory.getLogger(getClass()));
      assertThrows(IllegalStateException.class, () -> KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(context));
      assertEquals(prefix, queue.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload());
    }
  }

  private static WorkerIngestService service(SqliteJobQueue queue, Path base, RunningRuntime runtime) {
    return new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(), base,
        runtime.openedIndexPath(), runtime, runtime, null, 0L);
  }

  private static void set(KnowledgeServer server, String name, Object value) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(server, value);
  }

  private static void index(RunningRuntime runtime, Path file, String content) {
    String key = PathNormalizer.normalizeKey(file);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID, key,
        SchemaFields.DOC_UID, java.util.UUID.randomUUID().toString(), SchemaFields.PATH, key,
        SchemaFields.CONTENT, content, SchemaFields.COLLECTION, content)));
  }
}
