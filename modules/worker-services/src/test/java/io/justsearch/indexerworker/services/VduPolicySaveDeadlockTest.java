/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression check adapted from the independent verifier: a VDU publication, a settings save and the indexing loop's
 * writer all finish. The loop's writer holds the Worker's file-mutation fence and, inside it, runs
 * the user-exclusion check (JobBatchWriter.write -> WorkerIngestService.isUserExcluded), which reads
 * the live config. The loop stand-in uses a fair lock as its fence, like the production
 * FileMutationFence and PruneWriterFenceTest.
 */
final class VduPolicySaveDeadlockTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private final ReentrantLock fence = new ReentrantLock(true);

  @ParameterizedTest(name = "vduSaveAndLoopWriterAllFinish(markClaim={0})")
  @ValueSource(booleans = {false, true})
  void vduSaveAndLoopWriterAllFinish(boolean markClaim) throws Exception {
    var previous = ConfigStore.globalOrNull();
    var store = new ConfigStore(config("[\"unrelated.txt\"]"));
    ConfigStore.setGlobal(store);
    var loopMayCheck = new CountDownLatch(1);
    var loopHolds = new CountDownLatch(1);
    try (var runtime = IndexSchema.fromCatalog(VduRevisionTestSupport.catalog(4))
        .atPath(directory.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      String id = Files.writeString(directory.resolve("scan.png"), "source").toString();
      Path other = Files.writeString(directory.resolve("notes.txt"), "text");
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, "uid", SchemaFields.SOURCE_SHA256, "a".repeat(64),
          SchemaFields.CONTENT, "original text", SchemaFields.VDU_STATUS, "PENDING",
          SchemaFields.VDU_RETRY_COUNT, "0")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      IndexingLoop loop = mock(IndexingLoop.class);
      when(loop.getLastCommitTime()).thenAnswer(ignored -> System.currentTimeMillis());
      when(loop.getCurrentState()).thenReturn("IDLE");
      when(loop.withFileMutationFence(any())).thenAnswer(call -> {
        Supplier<?> mutation = call.getArgument(0);
        fence.lock();
        try { return mutation.get(); } finally { fence.unlock(); }
      });
      var service = new WorkerIngestService(null, loop, mock(WorkerSignalBus.class), IndexingPacing.unthrottled(),
          null, null, runtime, runtime, null, 0L);
      Object owner = new Object();
      service.setMutationAdmission(new WorkerMutationAdmission(owner), owner);
      service.useExclusionRoots(() -> List.of(directory));
      var base = CallContext.none();
      var context = new CallContext(null, null, null, base.engineContext().withWorkId(UUID.randomUUID()),
          base.provenance(), base.childLifetime());
      if (!markClaim) {
        assertTrue(service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
            .setDocId(id).setAttemptId("attempt").setMaxRetries(3).build(), context).getSuccess());
      }

      // 1. The loop's writer enters the fence (JobBatchWriter.write: fileMutationFence.lock()).
      Thread[] loopThread = new Thread[1];
      var loopWrite = start("loop-writer", () -> {
        fence.lock();
        try {
          loopHolds.countDown();
          assertTrue(loopMayCheck.await(10, TimeUnit.SECONDS));
          // JobBatchWriter: fileExclusions.test(...) -> isUserExcluded, inside the fence.
          return service.isUserExcluded(other, other);
        } finally { fence.unlock(); }
      }, loopThread);
      assertTrue(loopHolds.await(10, TimeUnit.SECONDS));

      // 2. A VDU RPC arrives and waits for the fence.
      Thread[] vduThread = new Thread[1];
      var vdu = start("vdu-rpc", () -> markClaim
          ? service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
              .setDocId(id).setAttemptId("attempt").setMaxRetries(3).build(), context).getSuccess()
          : service.updateVduResult(UpdateVduResultRequest.newBuilder()
              .setDocId(id).setAttemptId("attempt").setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
              .setExtractedContent("vdu text ".repeat(50)).setVduEnrichment("vdu marker").build(), context)
              .getSuccess(), vduThread);
      assertTrue(waitFor(() -> fence.hasQueuedThread(vduThread[0]) || vdu.isDone()),
          "the VDU call reaches the fence");

      // 3. The user saves a setting.
      Thread[] saveThread = new Thread[1];
      var save = start("settings-save", () -> { store.update(config("[\"unrelated.txt\",\"*.tmp\"]")); return true; },
          saveThread);
      assertTrue(waitFor(() -> save.isDone() || store.publicationLock().hasQueuedThread(saveThread[0])),
          "the save either completes or queues for the publication lock");

      // 4. The loop's writer runs its exclusion check, then leaves the fence.
      loopMayCheck.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      List<String> stuck = new ArrayList<>();
      var all = List.of(Map.entry(loopThread[0], loopWrite), Map.entry(vduThread[0], vdu),
          Map.entry(saveThread[0], save));
      for (var entry : all) {
        try { entry.getValue().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (TimeoutException hung) {
          Thread t = entry.getKey();
          StringBuilder where = new StringBuilder(t.getName() + " " + t.getState());
          for (StackTraceElement frame : t.getStackTrace()) {
            if (frame.getClassName().startsWith("io.justsearch")) { where.append(" at ").append(frame); break; }
          }
          stuck.add(where.toString());
        }
      }
      System.out.println("VERIFIER_DEADLOCK stuck=" + stuck);
      assertEquals(List.of(), stuck, "every thread must finish within 5 s; stuck threads mean a lock cycle");
    } finally {
      loopMayCheck.countDown();
      ConfigStore.restoreGlobal(store, previous);
    }
  }

  @Test void publicationPolicyReadDoesNotQueueBehindSavingWriter() throws Exception {
    var previous = ConfigStore.globalOrNull();
    var store = new ConfigStore(config("[]"));
    ConfigStore.setGlobal(store);
    Thread[] writer = new Thread[1];
    FutureTask<Boolean> save = null;
    store.publicationLock().readLock().lock();
    try {
      save = start("queued-save", () -> { store.update(config("[\"*.tmp\"]")); return true; },writer);
      assertTrue(waitFor(() -> store.publicationLock().hasQueuedThread(writer[0])));
      var policy = start("publication-policy", () ->
          io.justsearch.configuration.excludes.ExclusionPatterns.current().isEmpty(),new Thread[1]);
      assertTrue(assertDoesNotThrow(() -> policy.get(2,TimeUnit.SECONDS)),
          "a publication reads the current immutable snapshot without waiting for the queued save");
    } finally {
      store.publicationLock().readLock().unlock();
      try { if(save != null) save.get(5,TimeUnit.SECONDS); }
      finally { ConfigStore.restoreGlobal(store,previous); }
    }
  }

  private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return true;
      Thread.sleep(5);
    }
    return false;
  }

  private static <T> FutureTask<T> start(String name, Callable<T> work, Thread[] thread) {
    FutureTask<T> task = new FutureTask<>(work);
    thread[0] = new Thread(task, name);
    thread[0].setDaemon(true);
    thread[0].start();
    return task;
  }

  private static ResolvedConfig config(String patterns) {
    return ResolvedConfig.builder().putDefault("justsearch.ui.exclude_patterns", patterns).build();
  }
}
