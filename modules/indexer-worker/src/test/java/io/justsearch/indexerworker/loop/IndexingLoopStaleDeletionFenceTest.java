/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.RootWitness;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real composed extractor, queue and A/B indices; supporting component checks, not Head boot. */
@Timeout(20)
final class IndexingLoopStaleDeletionFenceTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private static final String H1 = "original source bytes";

  @Test
  void initialMissingSourceCannotDeleteAcrossTheActualFenceAndMustReobserveRecreatedH1() throws Exception {
    assertStaleDeletionReobserved(false);
  }

  @Test
  void postExtractionMissingSourceCannotDeleteAcrossTheActualFenceAndMustReobserveRecreatedH1() throws Exception {
    assertStaleDeletionReobserved(true);
  }

  private void assertStaleDeletionReobserved(boolean postExtraction) throws Exception {
    Path root = Files.createDirectory(directory.resolve("root"));
    Path file = Files.writeString(root.resolve("source.txt"), H1);
    String key = PathNormalizer.normalizeKey(file);
    try (var a = open(directory.resolve("A")); var b = open(directory.resolve("B"));
        var queue = new SqliteJobQueue(directory.resolve("queue.db"))) {
      queue.open();
      seed(a, key); seed(b, key);
      assertEquals(2, a.indexCountOps().countByIdAndChunksStrict(key));
      assertEquals(2, b.indexCountOps().countByIdAndChunksStrict(key));
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file,
          new JobQueue.EnqueueProvenance("system", "MIGRATION_ENUMERATOR")).withinRoot(root))));
      var claim = queue.pollPending(1).getFirst();
      assertEquals(file, claim.path());
      assertEquals("PROCESSING", queue.watcherReconciliationRow(key).get("state"));
      var identity = mock(DocumentIdentityStore.class);
      when(identity.resolve(anyString(), anyLong())).thenAnswer(call -> {
        String pathHash = call.getArgument(0);
        long now = call.getArgument(1);
        return new DocumentIdentityStore.Identity(pathHash, "fixture-source-uid", now, now);
      });
      var extraction = mock(TimeboxedContentExtractor.class);
      when(extraction.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
      var parserEntered = new CountDownLatch(1);
      var releaseParser = new CountDownLatch(1);
      when(extraction.extractArtifact(file)).thenAnswer(call -> {
        if (postExtraction) {
          parserEntered.countDown();
          assertTrue(releaseParser.await(5, TimeUnit.SECONDS), "SETUP: owned parser gate released");
        }
        return ExtractionArtifact.full(new ContentExtractor.ExtractionResult(H1, null, "text/plain"), "owned-parser");
      });
      var loop = new IndexingLoop(mock(EngineExecutorRegistry.Registration.class),
          mock(EngineExecutorRegistry.Registration.class), queue,
          b.indexingCoordinator(), b.commitOps(), b.documentFieldOps(), b.indexCountOps(), () -> null,
          mock(WorkerSignalBus.class), IndexingPacing.unthrottled(), null, null, null, null, extraction, null,
          new IndexingLoopOptions(false, null, identity, null, null, null));
      try {
        loop.wireActiveLexicalSource(a);
        var rootWitness = RootWitness.inMemory();
        assertTrue(rootWitness.admits(root)); assertTrue(rootWitness.vouches(root));
        loop.useRootCheck(rootWitness);
        var runningField = IndexingLoop.class.getDeclaredField("running"); runningField.setAccessible(true);
        ((AtomicBoolean) runningField.get(loop)).set(true);
        var fence = loop.fileMutationFence();
        var lockField = FileMutationFence.class.getDeclaredField("lock"); lockField.setAccessible(true);
        var lock = (ReentrantLock) lockField.get(fence);
        var task = new FutureTask<>(() -> loop.getExtractor().extractAll(List.of(claim)));
        var worker = new Thread(task, "owned-stale-source-extraction");
        boolean retainedA;
        boolean retainedB;
        try {
          if (postExtraction) {
            worker.start();
            assertTrue(parserEntered.await(5, TimeUnit.SECONDS), "SETUP: real admission/hash precede controlled extraction completion");
          }
          fence.lock();
          try {
            Files.delete(file);
            assertEquals(SourcePresence.Verdict.ABSENT, SourcePresence.classify(file, root, rootWitness));
            if (postExtraction) releaseParser.countDown();
            else worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            // No artificial gate after the final stale observation. Observe the real fence wait,
            // or completion on bad-current which performs its unfenced deletion immediately.
            while (!task.isDone() && !lock.hasQueuedThread(worker) && System.nanoTime() < deadline) {
              Thread.sleep(5);
            }
            assertTrue(task.isDone() || lock.hasQueuedThread(worker), "SETUP: extraction reached completion or the actual shared fence");
            a.commitOps().maybeRefreshBlocking(); b.commitOps().maybeRefreshBlocking();
            retainedA = a.indexCountOps().countByIdAndChunksStrict(key) == 2;
            retainedB = b.indexCountOps().countByIdAndChunksStrict(key) == 2;
            Files.writeString(file, H1); // Same issued claim, same root, recreated before fence entry.
            if (!task.isDone()) assertTrue(queue.ownsClaimForPublication(claim), "SETUP: unchanged issued claim while waiting");
          } finally { fence.unlock(); }
          task.get(5, TimeUnit.SECONDS);
          a.commitOps().maybeRefreshBlocking(); b.commitOps().maybeRefreshBlocking();
          assertAll(
              () -> assertTrue(retainedA, "A cannot be deleted while the actual ownership fence is held"),
              () -> assertTrue(retainedB, "B cannot be deleted while the actual ownership fence is held"),
              () -> assertEquals(2, a.indexCountOps().countByIdAndChunksStrict(key), "freshly recreated H1 retains A parent/chunk"),
              () -> assertEquals(2, b.indexCountOps().countByIdAndChunksStrict(key), "freshly recreated H1 retains B parent/chunk"),
              () -> assertEquals(H1, Files.readString(file)));
        } finally {
          releaseParser.countDown();
          worker.join(5_000);
          assertFalse(worker.isAlive(), "owned extraction thread must finish");
        }
      } finally { loop.close(); }
    }
  }

  private RunningRuntime open(Path index) throws Exception {
    var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).atPath(index)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private static void seed(RunningRuntime runtime, String key) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID, key,
        SchemaFields.DOC_UID, "source-uid", SchemaFields.PATH, key, SchemaFields.CONTENT, H1)));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID, "child",
        SchemaFields.DOC_UID, "child-uid", SchemaFields.PATH, key, SchemaFields.PARENT_DOC_ID, key,
        SchemaFields.IS_CHUNK, "true", SchemaFields.CHUNK_START_CHAR, 0, SchemaFields.CHUNK_END_CHAR, H1.length(),
        SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(H1))));
    runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
  }
}
