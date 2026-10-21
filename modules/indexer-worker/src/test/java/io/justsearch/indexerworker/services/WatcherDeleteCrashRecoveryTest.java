/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.WatchRootRequest;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** A separate JVM halts at real durability boundaries; no source checkout is modified. */
final class WatcherDeleteCrashRecoveryTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @ParameterizedTest(name = "recreatedSourceAndNewerQueueRevisionSurviveProcessDeath({0})")
  @ValueSource(strings = {"buffered", "lucene-delete", "commit", "before-cleanup", "after-cleanup"})
  void recreatedSourceAndNewerQueueRevisionSurviveProcessDeath(String cut) throws Exception {
    Path root = Files.createDirectory(temp.resolve("files"));
    Path file = Files.writeString(root.resolve("removed.txt"), "original");
    Path sibling = Files.writeString(root.resolve("sibling.txt"), "sibling");
    Path base = temp.resolve("index");
    Path index = new IndexGenerationManager(base).initializeOrLoad().activeGenerationPath();
    Path database = temp.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(database); var runtime = open(index)) {
      queue.open();
      watch(ingest(queue, runtime, base, index), root);
      seed(runtime, file); seed(runtime, sibling);
      assertTrue(queue.putSwitchBuffer("path:" + file, "DELETE", file.toString()));
      Files.delete(file);
    }
    assertEquals(71, child(cut, base, index, root, database, file), "the selected crash cut must execute");
    Files.writeString(file, "recreated after crash");
    try (var queue = new SqliteJobQueue(database); var runtime = open(index)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "docs");
      var accepted = queue.pollPending(1).getFirst();
      queue.returnUnfinishedClaims(List.of(accepted));
      assertTrue(KnowledgeServerMigrationOps.drainSwitchBufferStrict(context(queue, runtime, base, index)));
      assertEquals(1, queue.queueDepth(), "replaying a stale delete must preserve the newer queued source");
      assertEquals(accepted.unitRevision(), queue.pollPending(1).getFirst().unitRevision());
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(sibling.toString()));
      assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
      // Before a committed removal the old rows survive the crash. After it, the retained newer
      // queue revision owns republishing; neither a partial parent nor a partial chunk may remain.
      long count = runtime.indexCountOps().countByIdAndChunksStrict(file.toString());
      assertTrue(count == 0 || count == 2, "parent/chunk deletion must stay atomic across a crash");
    }
  }

  @ParameterizedTest(name = "reloadDebtSurvivesProcessDeathBeforeReconciliation({0})")
  @ValueSource(strings = {"reload-debt", "reload-during-reconcile"})
  void reloadDebtSurvivesProcessDeathBeforeReconciliation(String cut) throws Exception {
    Path root = Files.createDirectory(temp.resolve("files"));
    Path file = Files.writeString(root.resolve("removed.txt"), "original");
    Path base = temp.resolve("index");
    Path index = new IndexGenerationManager(base).initializeOrLoad().activeGenerationPath();
    Path database = temp.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(database); var runtime = open(index)) {
      queue.open(); watch(ingest(queue, runtime, base, index), root); seed(runtime, file);
    }
    assertEquals(71, child(cut, base, index, root, database, file));
    try (var queue = new SqliteJobQueue(database); var runtime = open(index)) {
      queue.open();
      assertFalse(queue.listSwitchBufferOpsStrict().isEmpty(), "reload debt must be durable before acknowledgement");
      watch(ingest(queue, runtime, base, index), root);
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
      assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
    }
  }

  private RunningRuntime open(Path index) throws Exception {
    return IndexSchema.fromCatalog(FieldCatalogDef.forChunkTestingWithVduRetryCount(0)).atPath(index)
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static WorkerIngestService ingest(SqliteJobQueue queue, RunningRuntime runtime,
      Path base, Path index) {
    return new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
        base, index, runtime, runtime, null, 0L);
  }

  private static void watch(WorkerIngestService service, Path root) {
    assertTrue(service.watchRoot(WatchRootRequest.newBuilder().setRootPath(root.toString())
        .setRootAdded(true).setCollection("docs").build(), CallContext.none()).getWatching());
  }

  private static void seed(RunningRuntime runtime, Path file) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, file.toString(), SchemaFields.DOC_UID, file + "#uid",
        SchemaFields.PATH, file.toString(), SchemaFields.CONTENT, "original text")));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, file + "#chunk0", SchemaFields.DOC_UID, file + "#chunk-uid",
        SchemaFields.PATH, file.toString(), SchemaFields.PARENT_DOC_ID, file.toString(),
        SchemaFields.IS_CHUNK, true, SchemaFields.CHUNK_INDEX, 0,
        SchemaFields.CHUNK_CONTENT, "original text")));
    runtime.commitOps().commitAndTrack(CommitReason.DRAIN);
  }

  private static KnowledgeServerMigrationOps.DrainSwitchBufferContext context(SqliteJobQueue queue,
      RunningRuntime runtime, Path base, Path index) {
    return new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
        IndexingPacing.unthrottled(), base, index, JsonMapper.builder().build(), () -> false,
        () -> false, LoggerFactory.getLogger(WatcherDeleteCrashRecoveryTest.class));
  }

  private int child(String cut, Path base, Path index, Path root, Path database, Path file) throws Exception {
    Path output = temp.resolve("child-" + cut + ".log");
    Path arguments = temp.resolve("child.args");
    Files.writeString(arguments, "-cp\n\"" + System.getProperty("java.class.path")
        .replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
    String java = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    Process child = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", "@" + arguments,
        CrashWorker.class.getName(), cut, base.toString(), index.toString(), root.toString(),
        database.toString(), file.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(child.waitFor(40, TimeUnit.SECONDS), "owned crash worker must exit");
      assertEquals(71, child.exitValue(), () -> "crash point not reached: " + read(output));
      return child.exitValue();
    } finally {
      if (child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(10, TimeUnit.SECONDS)); }
    }
  }

  private static String read(Path path) {
    try { return Files.readString(path); } catch (Exception failure) { return failure.toString(); }
  }

  public static final class CrashWorker {
    public static void main(String[] args) throws Exception {
      String cut = args[0]; Path base = Path.of(args[1]), index = Path.of(args[2]), root = Path.of(args[3]);
      Path database = Path.of(args[4]), file = Path.of(args[5]);
      try (var executors = new TestEngineExecutors();
           var lucene = new LuceneExecutorRegistrations(executors);
           var realQueue = new SqliteJobQueue(database);
           var realRuntime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTestingWithVduRetryCount(0))
               .atPath(index).withExecutorRegistrations(lucene).open()) {
        realQueue.open();
        if ("buffered".equals(cut)) Runtime.getRuntime().halt(71);
        if (cut.startsWith("reload-")) {
          var service = ingest(realQueue, realRuntime, base, index); watch(service, root);
          realRuntime.drainAndClose(Duration.ofSeconds(3), SwapReason.CONFIG_RELOAD);
          Files.delete(file);
          service.acceptWatcherDelete(file.toString(), () -> {});
          if ("reload-debt".equals(cut)) Runtime.getRuntime().halt(71);
          try (var fresh = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTestingWithVduRetryCount(0))
              .atPath(index).withExecutorRegistrations(lucene).open()) {
            var selected = spy(fresh);
            var rootCommits = spy(fresh.commitOps());
            doReturn(rootCommits).when(selected).commitOps();
            doAnswer(call -> { call.callRealMethod(); Runtime.getRuntime().halt(71); return null; })
                .when(rootCommits).commitAndTrack(CommitReason.SYNC_PRUNE);
            var successor = ingest(realQueue, selected, base, index);
            try {
              WorkerIngestService.class.getMethod("reconcileReloadedWatcherRootsFrom", WorkerIngestService.class)
                  .invoke(successor, service);
            } catch (NoSuchMethodException beforeRepair) { /* parent asserts the missing crash cut */ }
          }
          return;
        }
        var queue = spy(realQueue);
        var runtime = spy(realRuntime);
        var coordinator = spy(realRuntime.indexingCoordinator());
        var commits = spy(realRuntime.commitOps());
        doReturn(coordinator).when(runtime).indexingCoordinator();
        doReturn(commits).when(runtime).commitOps();
        doAnswer(call -> { call.callRealMethod(); if ("lucene-delete".equals(cut)) Runtime.getRuntime().halt(71); return null; })
            .when(coordinator).deleteByIdAndChunks(file.toString());
        doAnswer(call -> { call.callRealMethod(); if ("commit".equals(cut)) Runtime.getRuntime().halt(71); return null; })
            .when(commits).commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
        doAnswer(call -> {
          if ("before-cleanup".equals(cut)) Runtime.getRuntime().halt(71);
          Object result = call.callRealMethod();
          if ("after-cleanup".equals(cut)) Runtime.getRuntime().halt(71);
          return result;
        }).when(queue).removeReplayedSwitchBufferOps(anyList());
        KnowledgeServerMigrationOps.drainSwitchBufferStrict(context(queue, runtime, base, index));
      }
    }
  }
}
