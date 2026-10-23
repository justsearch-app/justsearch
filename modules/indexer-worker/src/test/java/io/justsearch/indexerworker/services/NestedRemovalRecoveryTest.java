/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.WatchRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Interrupts real durable effects, then reopens both stores before recovery and retry. */
final class NestedRemovalRecoveryTest extends LuceneExecutorTestBase {
  @TempDir Path temp;
  enum Cut { RECEIPT, SERVING_COMMIT, QUEUE, CANDIDATE_COMMIT, REPLAY_COMMIT, BEFORE_ACK, AFTER_ACK, NONE }
  private static final class Interrupt extends Error {}

  @ParameterizedTest
  @EnumSource(value = Cut.class, names = {"RECEIPT", "SERVING_COMMIT", "QUEUE", "CANDIDATE_COMMIT",
      "REPLAY_COMMIT", "BEFORE_ACK", "AFTER_ACK"})
  void migrationInterruptsKeepFrozenChildScopeThroughRecoveryAndRetry(Cut cut) throws Exception {
    run(cut, true);
  }

  @ParameterizedTest
  @EnumSource(value = Cut.class, names = {"QUEUE", "CANDIDATE_COMMIT"})
  void ordinaryInterruptsKeepChildAndRetryWithoutAddingAJournal(Cut cut) throws Exception {
    run(cut, false);
  }

  private void run(Cut cut, boolean migration) throws Exception {
    Path parent = Files.createDirectories(temp.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("child"));
    Path own = Files.writeString(parent.resolve("own.txt"), "parent");
    Path nested = Files.writeString(child.resolve("nested.txt"), "child");
    Path base = temp.resolve("index");
    var manager = new IndexGenerationManager(base);
    String blue = manager.initializeOrLoad().activeGenerationId();
    String green = migration ? manager.startMigration("nested-interruption").building_generation() : blue;
    Path a = manager.resolveGenerationPathStrict(blue);
    Path b = manager.resolveGenerationPathStrict(green);
    boolean replayCut = cut == Cut.REPLAY_COMMIT || cut == Cut.BEFORE_ACK || cut == Cut.AFTER_ACK;
    String frozen = null;
    try (var first = new Fixture(base, a, b, cut, false, parent, child)) {
      first.declareChild(child);
      first.seed(own, nested);
      first.armed.set(true);
      if (replayCut) assertEquals("", first.remove(parent).getError());
      else assertThrows(Interrupt.class, () -> first.remove(parent));
      first.requireChild(nested);
      var receipts = first.queue.listSwitchBufferOpsStrictForGeneration(green);
      if (migration) {
        assertEquals(1, receipts.size());
        frozen = receipts.getFirst().payload();
      } else assertTrue(first.queue.listSwitchBufferOpsStrict().isEmpty());
    }
    if (replayCut) {
      try (var interruptedReplay = new Fixture(base, a, b, cut, true, parent, child)) {
        interruptedReplay.armed.set(true);
        assertThrows(Interrupt.class, () -> KnowledgeServerMigrationOps.drainSwitchBufferStrict(
            interruptedReplay.context(interruptedReplay.writer, green)));
        interruptedReplay.requireChild(nested);
        var retained = interruptedReplay.queue.listSwitchBufferOpsStrictForGeneration(green);
        if (cut == Cut.AFTER_ACK) assertTrue(retained.isEmpty());
        else assertEquals(frozen, retained.getFirst().payload());
      }
    }
    // No child declaration is restored here: a versioned receipt must retain its original scope.
    try (var recovered = new Fixture(base, a, b, Cut.NONE, false, parent, child)) {
      if (migration) {
        KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(recovered.context(recovered.active, green));
        assertTrue(KnowledgeServerMigrationOps.drainSwitchBufferStrict(
            recovered.context(recovered.candidate, green)));
        recovered.requireAbsent(own);
      }
      recovered.requireChild(nested);
      assertTrue(recovered.queue.listSwitchBufferOpsStrict().isEmpty());
      recovered.declareChild(child);
      assertEquals("", recovered.remove(parent).getError(), "explicit retry finishes the removal");
      recovered.requireAbsent(own);
      recovered.requireChild(nested);
      if (migration) assertTrue(KnowledgeServerMigrationOps.drainSwitchBufferStrict(
          recovered.context(recovered.candidate, green)));
      assertTrue(recovered.queue.listSwitchBufferOpsStrict().isEmpty());
      assertEquals(1, recovered.queue.queueDepth(), "recovery never re-admits P's files");
    }
  }

  private final class Fixture implements AutoCloseable {
    final SqliteJobQueue queue;
    final RunningRuntime active;
    final RunningRuntime candidate;
    final RunningRuntime writer;
    final WorkerIngestService service;
    final AtomicBoolean armed = new AtomicBoolean();
    final AtomicBoolean interrupted = new AtomicBoolean();
    final Path base;

    Fixture(Path base, Path a, Path b, Cut cut, boolean replay, Path parent, Path child) throws Exception {
      this.base = base;
      var durableQueue = new SqliteJobQueue(temp.resolve("jobs.db"));
      durableQueue.open();
      queue = mock(SqliteJobQueue.class, withSettings().spiedInstance(durableQueue)
          .defaultAnswer(call -> {
            String method = call.getMethod().getName();
            if (armed.get() && replay && cut == Cut.BEFORE_ACK
                && method.equals("removeReplayedSwitchBufferOps") && interrupted.compareAndSet(false, true)) {
              throw new Interrupt();
            }
            Object result = call.callRealMethod();
            boolean point = !replay && ((cut == Cut.RECEIPT && method.equals("putSwitchBufferForGeneration"))
                || (cut == Cut.QUEUE && method.startsWith("deleteByPathPrefix")))
                || (replay && cut == Cut.AFTER_ACK && method.equals("removeReplayedSwitchBufferOps"));
            if (armed.get() && point && interrupted.compareAndSet(false, true)) throw new Interrupt();
            return result;
          }));
      active = open(a);
      candidate = a.equals(b) ? active : open(b);
      writer = commitCut(candidate, cut, replay, false);
      RunningRuntime serving = active == candidate ? writer : commitCut(active, cut, replay, true);
      service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(), base, b,
          writer, serving, null, 0L);
    }

    RunningRuntime commitCut(RunningRuntime real, Cut cut, boolean replay, boolean serving) {
      var commits = mock(CommitOps.class, withSettings().spiedInstance(real.commitOps()).defaultAnswer(call -> {
        Object result = call.callRealMethod();
        boolean point = call.getMethod().getName().equals("commitAndTrack") && call.getArguments().length == 1
            && (replay ? cut == Cut.REPLAY_COMMIT && call.getArgument(0) == CommitReason.SWITCH_BUFFER_REPLAY
                : serving ? cut == Cut.SERVING_COMMIT : cut == Cut.CANDIDATE_COMMIT);
        if (armed.get() && point && interrupted.compareAndSet(false, true)) throw new Interrupt();
        return result;
      }));
      var wrapped = spy(real);
      doReturn(commits).when(wrapped).commitOps();
      return wrapped;
    }

    void declareChild(Path child) {
      assertTrue(service.watchRoot(WatchRootRequest.newBuilder().setRootPath(child.toString())
          .setCollection("child").setRootAdded(true).build(), CallContext.none()).getWatching());
    }

    void seed(Path own, Path child) {
      for (var runtime : active == candidate ? List.of(active) : List.of(active, candidate)) {
        index(runtime, own, "parent");
        index(runtime, child, "child");
        runtime.commitOps().commitAndTrack();
      }
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(own)), "parent");
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(child)), "child");
    }

    io.justsearch.ipc.DeleteByPathResponse remove(Path parent) {
      return service.deleteByPath(DeleteByPathRequest.newBuilder().setPath(parent.toString()).build(), CallContext.none());
    }

    void requireChild(Path path) throws Exception {
      for (var runtime : active == candidate ? List.of(active) : List.of(active, candidate)) {
        runtime.commitOps().maybeRefreshBlocking();
        assertEquals("child", runtime.documentFieldOps().getDocumentField(key(path), SchemaFields.CONTENT));
        assertEquals("child", runtime.documentFieldOps().getDocumentField(key(path), SchemaFields.COLLECTION));
      }
      assertTrue(queue.matchesExpectedCollection(path, "child"));
    }

    void requireAbsent(Path path) throws Exception {
      for (var runtime : active == candidate ? List.of(active) : List.of(active, candidate)) {
        runtime.commitOps().maybeRefreshBlocking();
        assertNull(runtime.documentFieldOps().getDocumentField(key(path), SchemaFields.CONTENT));
      }
    }

    KnowledgeServerMigrationOps.DrainSwitchBufferContext context(RunningRuntime target, String generation) {
      return new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, target, null,
          IndexingPacing.unthrottled(), base, target.openedIndexPath(), new ObjectMapper(),
          () -> false, () -> false, LoggerFactory.getLogger(NestedRemovalRecoveryTest.class),
          Long.MAX_VALUE, generation).withPublisher(service);
    }

    @Override public void close() throws Exception {
      queue.close();
      if (candidate != active) candidate.close();
      active.close();
    }
  }

  private RunningRuntime open(Path path) throws Exception {
    return IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(path)
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static String key(Path file) { return PathNormalizer.normalizeKey(file); }

  private static void index(RunningRuntime runtime, Path file, String value) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, key(file), SchemaFields.DOC_UID, java.util.UUID.randomUUID().toString(),
        SchemaFields.PATH, key(file), SchemaFields.CONTENT, value, SchemaFields.COLLECTION, value)));
  }
}
