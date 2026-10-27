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

  @Test
  void finalSnapshotReobservesH1AndChangedH2BeforeDeleting() throws Exception {
    assertAll(java.util.stream.Stream.of(H1, "replacement H2 with a different size").map(content -> () -> {
      try (var fixture = new FenceFixture(true, true)) {
        fixture.behindFence(() -> {
          var jobs = fixture.loop.getExtractor().extractAll(List.of(fixture.claim));
          if (!content.equals(H1)) assertTrue(jobs.isEmpty(), "H2 defers the obsolete extracted artifact");
          return jobs;
        }, () -> Files.writeString(fixture.file, content), true, true);
        fixture.assertRetained();
        fixture.assertNoGrace();
        assertEquals(content, Files.readString(fixture.file));
      }
    }));
  }

  @Test
  void postExtractionHashFailureReobservesChangedH2BeforeDeleting() throws Exception {
    try (var fixture = new FenceFixture()) {
      fixture.behindFence(() -> {
        var jobs = fixture.loop.getExtractor().extractAll(List.of(fixture.claim));
        assertTrue(jobs.isEmpty(), "H2 defers the obsolete extracted artifact");
        return jobs;
      }, () -> Files.writeString(fixture.file, "replacement H2 with different metadata"), true);
      fixture.assertRetained();
      fixture.assertNoGrace();
    }
  }

  @Test
  void initialNonregularObservationCannotDeleteAReturnedRegularSource() throws Exception {
    assertAll(java.util.stream.Stream.of("directory", "live-link", "dangling-link").map(kind -> () -> {
      try (var fixture = new FenceFixture()) {
        fixture.replaceBy(kind);
        String content = kind.equals("directory") ? "returning regular H2 bytes" : H1;
        fixture.behindFence(() -> fixture.loop.getExtractor().extractAll(List.of(fixture.claim)), () -> {
          Files.delete(fixture.file);
          Files.writeString(fixture.file, content);
        });
        fixture.assertRetained();
        fixture.assertNoGrace();
        assertEquals(H1, Files.readString(fixture.target));
      }
    }));
  }

  @Test
  void initialAndFinalSnapshotRejectSupersededReturnedAndReconstructedClaims() throws Exception {
    assertAll(java.util.stream.Stream.of(false, true).flatMap(post ->
        java.util.stream.Stream.of("superseded", "returned", "reconstructed").map(kind -> () -> {
          try (var fixture = new FenceFixture()) {
            JobQueue.IndexJob stale = fixture.claim;
            if (kind.equals("superseded")) {
              assertEquals(1, fixture.queue.enqueueEntries(List.of(
                  JobQueue.EnqueueEntry.stat(fixture.file).withinRoot(fixture.root)), "replacement", "replacement-scan"));
            } else if (kind.equals("returned")) {
              fixture.queue.returnUnfinishedClaims(List.of(stale));
              var reissued = fixture.queue.pollPending(1).getFirst();
              assertEquals(stale, reissued);
              assertNotSame(stale, reissued);
            } else {
              stale = new JobQueue.IndexJob(stale.path(), stale.collection(), stale.provenance(),
                  stale.scanId(), stale.unitRevision(), stale.walkEpoch(), stale.recordedForce(),
                  stale.plannedSourceSha256(), stale.ingestionRoot());
              assertEquals(fixture.claim, stale);
              assertNotSame(fixture.claim, stale);
            }
            assertFalse(fixture.queue.ownsClaimForPublication(stale));
            var rowBefore = fixture.queue.watcherReconciliationRow(fixture.key);
            assertTrue(fixture.queue.putSwitchBufferForGeneration("green", "path:" + fixture.key,
                "CREATE", "accepted-replacement-fixture"));
            Files.delete(fixture.file);
            if (post) fixture.resolve(stale);
            else fixture.loop.getExtractor().extractAll(List.of(stale));
            assertAll(fixture::assertRetained, fixture::assertNoGrace,
                () -> assertEquals(rowBefore, fixture.queue.watcherReconciliationRow(fixture.key),
                    "old work cannot finish or alter the replacement row"),
                () -> assertEquals(1, fixture.queue.listSwitchBufferOpsStrict().size()));
          }
        })));
  }

  @Test
  void currentExclusionWinsAfterWaitingAtInitialAndFinalSnapshotGates() throws Exception {
    assertAll(java.util.stream.Stream.of(false, true).map(post -> () -> {
      try (var fixture = new FenceFixture()) {
        Files.delete(fixture.file);
        fixture.behindFence(() -> post ? fixture.resolve(fixture.claim)
            : fixture.loop.getExtractor().extractAll(List.of(fixture.claim)),
            () -> fixture.loop.useFileExclusions((file, root) -> true));
        fixture.assertRetained();
        fixture.assertNoGrace();
        assertEquals("SKIPPED_TEMP_OR_SYSTEM", fixture.queue.watcherReconciliationRow(fixture.key).get("last_reason_code"));
      }
    }));
  }

  @Test
  void rootDisappearanceWhileWaitingKeepsRowsAndRetriesWithoutExpiry() throws Exception {
    assertAll(java.util.stream.Stream.of(false, true).map(post -> () -> {
      try (var fixture = new FenceFixture()) {
        Files.delete(fixture.file);
        fixture.behindFence(() -> post ? fixture.resolve(fixture.claim)
            : fixture.loop.getExtractor().extractAll(List.of(fixture.claim)),
            () -> {
              Files.move(fixture.root, fixture.base.resolve("unavailable-root"));
              // Preserve RootWitness's documented one-second cache; this is not an instantaneous-volume claim.
              Thread.sleep(1_100);
            });
        fixture.assertRetained();
        fixture.assertNoGrace();
        assertEquals("ROOT_UNAVAILABLE", fixture.queue.watcherReconciliationRow(fixture.key).get("last_reason_code"));
        assertEquals("RETRY_UNTIL_AVAILABLE", fixture.queue.watcherReconciliationRow(fixture.key).get("last_retry_policy"));
      }
    }));
  }

  @Test
  void genuineAbsenceDeletesExactParentAndChunksAtInitialFinalAndReentrantWriterGates() throws Exception {
    assertAll(java.util.stream.Stream.of("initial", "post-hash", "final", "writer").map(route -> () -> {
      Path base;
      String key;
      try (var fixture = new FenceFixture()) {
        base = fixture.base;
        key = fixture.key;
        var extracted = route.equals("writer")
            ? fixture.loop.getExtractor().extractAll(List.of(fixture.claim)) : List.<ExtractedJob>of();
        if (route.equals("writer")) assertEquals(1, extracted.size());
        if (route.equals("post-hash")) {
          when(fixture.extraction.extractArtifact(fixture.file)).thenAnswer(call -> {
            Files.delete(fixture.file);
            return fixture.artifact();
          });
        } else Files.delete(fixture.file);
        switch (route) {
          case "initial", "post-hash" -> fixture.loop.getExtractor().extractAll(List.of(fixture.claim));
          case "final" -> fixture.resolve(fixture.claim);
          case "writer" -> fixture.loop.getWriter().write(extracted.getFirst(), null);
          default -> throw new AssertionError(route);
        }
        fixture.a.commitOps().commitAndTrack(); fixture.b.commitOps().commitAndTrack();
        fixture.assertCounts(0);
        assertNotNull(fixture.identities.lookup(DocumentIdentityStore.pathHash(key)).orElseThrow().deletedAtMs());
      }
      try (var a = open(base.resolve("A")); var b = open(base.resolve("B"))) {
        assertEquals(0, a.indexCountOps().countByIdAndChunksStrict(key));
        assertEquals(0, b.indexCountOps().countByIdAndChunksStrict(key));
        assertEquals(1, a.indexCountOps().countByIdAndChunksStrict("unrelated"));
        assertEquals(1, b.indexCountOps().countByIdAndChunksStrict("unrelated"));
      }
    }));
  }

  @Test
  void singleGenerationDeletionDoesNotMutateAnUnwiredServingGeneration() throws Exception {
    try (var fixture = new FenceFixture(false)) {
      Files.delete(fixture.file);
      fixture.loop.getExtractor().extractAll(List.of(fixture.claim));
      fixture.a.commitOps().maybeRefreshBlocking(); fixture.b.commitOps().maybeRefreshBlocking();
      assertEquals(2, fixture.a.indexCountOps().countByIdAndChunksStrict(fixture.key));
      assertEquals(0, fixture.b.indexCountOps().countByIdAndChunksStrict(fixture.key));
    }
  }

  @Test
  void deletionKeepsAThenBOrderingAndExistingPartialFailureSemantics() throws Exception {
    assertAll(java.util.stream.Stream.of("A", "B").map(failing -> () -> {
      try (var fixture = new FenceFixture()) {
        Files.delete(fixture.file);
        var a = spy(fixture.a.indexingCoordinator());
        var b = spy(fixture.b.indexingCoordinator());
        doThrow(new IllegalStateException("owned " + failing + " delete failure"))
            .when(failing.equals("A") ? a : b).deleteByIdAndChunks(fixture.key);
        var handler = new StaleSourceHandler(b, fixture.identities, () -> a);
        assertEquals(0, handler.deleteMissingSource(fixture.file));
        var order = inOrder(a, b);
        order.verify(a).deleteByIdAndChunks(fixture.key);
        if (failing.equals("A")) verify(b, never()).deleteByIdAndChunks(anyString());
        else order.verify(b).deleteByIdAndChunks(fixture.key);
        fixture.a.commitOps().maybeRefreshBlocking(); fixture.b.commitOps().maybeRefreshBlocking();
        assertEquals(failing.equals("A") ? 2 : 0,
            fixture.a.indexCountOps().countByIdAndChunksStrict(fixture.key));
        assertEquals(2, fixture.b.indexCountOps().countByIdAndChunksStrict(fixture.key));
        fixture.assertNoGrace();
      }
    }));
  }

  @Test
  void persistentDirectoryAndLinksRetireOldTextWithoutFollowingTarget() throws Exception {
    assertAll(java.util.stream.Stream.of("directory", "live-link", "dangling-link").map(kind -> () -> {
      try (var fixture = new FenceFixture()) {
        fixture.replaceBy(kind);
        fixture.loop.getExtractor().extractAll(List.of(fixture.claim));
        fixture.assertCounts(0);
        verify(fixture.extraction, never()).extractArtifact(any());
        assertEquals(H1, Files.readString(fixture.target));
        if (!kind.equals("dangling-link")) fixture.assertNoGrace();
      }
    }));
  }

  /** Composed loop with owned SQLite queue/identity state and distinct writable A/B generations. */
  private final class FenceFixture implements AutoCloseable {
    private final Path base = Files.createTempDirectory(directory, "neighbor-");
    private final Path root = Files.createDirectory(base.resolve("root"));
    private final Path file = Files.writeString(root.resolve("source.txt"), H1);
    private final Path target = Files.writeString(base.resolve("link-target.txt"), H1);
    private final String key = PathNormalizer.normalizeKey(file);
    private final RunningRuntime a = open(base.resolve("A"));
    private final RunningRuntime b = open(base.resolve("B"));
    private final SqliteJobQueue queue = new SqliteJobQueue(base.resolve("queue.db"),
        ignored -> JobQueue.RecordedClaimDecision.ALLOW);
    private final JobQueue loopQueue;
    private final io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore identities;
    private final IndexingLoop loop;
    private final TimeboxedContentExtractor extraction = mock(TimeboxedContentExtractor.class);
    private final JobQueue.IndexJob claim;
    private final FileEnvelope envelope = FileEnvelope.fromSnapshot(FileFreshnessSnapshot.capture(file));

    private FenceFixture() throws Exception { this(true, false); }

    private FenceFixture(boolean projectToA) throws Exception { this(projectToA, false); }

    private FenceFixture(boolean projectToA, boolean captured) throws Exception {
      queue.open();
      identities = new io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore(base.resolve("queue.db"));
      identities.resolve(DocumentIdentityStore.pathHash(key), System.currentTimeMillis());
      seed(a, key); seed(b, key);
      for (var runtime : List.of(a, b)) {
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID,
            "unrelated", SchemaFields.DOC_UID, "unrelated-uid", SchemaFields.PATH, "unrelated",
            SchemaFields.CONTENT, "keep unrelated text")));
      }
      if (captured) {
        String operation = "01994180-0000-7000-8000-000000000981";
        var walk = queue.beginCapturedWalk(operation, "a".repeat(64), true);
        assertEquals(1, queue.enqueueRecordedEntries(operation, walk.enumerationEpoch(), List.of(
            new JobQueue.EnqueueEntry(file, Files.size(file), null, "0".repeat(64), root)), null));
        queue.closeRecordedWalkEnumeration(operation, walk.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      } else {
        assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root))));
      }
      claim = queue.pollPending(1).getFirst();
      assertTrue(queue.ownsClaimForPublication(claim));
      when(extraction.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
      when(extraction.extractArtifact(file)).thenReturn(artifact());
      loopQueue = captured ? mock(SqliteJobQueue.class, org.mockito.AdditionalAnswers.delegatesTo(queue)) : queue;
      loop = new IndexingLoop(mock(EngineExecutorRegistry.Registration.class),
          mock(EngineExecutorRegistry.Registration.class), loopQueue,
          b.indexingCoordinator(), b.commitOps(), b.documentFieldOps(), b.indexCountOps(), () -> null,
          mock(WorkerSignalBus.class), IndexingPacing.unthrottled(), null, null, null, null, extraction, null,
          new IndexingLoopOptions(false, null, identities, null, null, null));
      if (projectToA) loop.wireActiveLexicalSource(a);
      var witness = RootWitness.inMemory();
      assertTrue(witness.admits(root)); assertTrue(witness.vouches(root));
      loop.useRootCheck(witness);
      var runningField = IndexingLoop.class.getDeclaredField("running"); runningField.setAccessible(true);
      ((AtomicBoolean) runningField.get(loop)).set(true);
      assertCounts(2);
    }

    private ExtractionArtifact artifact() {
      return ExtractionArtifact.full(new ContentExtractor.ExtractionResult(H1, null, "text/plain"), "owned-parser");
    }

    private boolean resolve(JobQueue.IndexJob issued) throws Exception {
      var resolverField = IndexingLoop.class.getDeclaredField("staleResolver");
      resolverField.setAccessible(true);
      return ((StaleSnapshotResolver) resolverField.get(loop)).tryHandleStale(
          file, envelope, "default", null, "after extraction", issued.provenance(), issued);
    }

    private void replaceBy(String kind) throws Exception {
      Files.delete(file);
      switch (kind) {
        case "directory" -> Files.createDirectory(file);
        case "live-link" -> Files.createSymbolicLink(file, target);
        case "dangling-link" -> Files.createSymbolicLink(file, base.resolve("absent-target"));
        default -> throw new AssertionError(kind);
      }
    }

    private void behindFence(java.util.concurrent.Callable<?> action, CheckedMutation mutation) throws Exception {
      behindFence(action, mutation, false, false);
    }

    private void behindFence(java.util.concurrent.Callable<?> action, CheckedMutation mutation,
        boolean afterExtraction) throws Exception {
      behindFence(action, mutation, afterExtraction, false);
    }

    private void behindFence(java.util.concurrent.Callable<?> action, CheckedMutation mutation,
        boolean afterExtraction, boolean finalSnapshot) throws Exception {
      if (finalSnapshot) {
        // Captured membership permits the already stable H1 hash to reach final validation.
        // This existing queue read occurs after both SHA passes, before the final source probe.
        doAnswer(call -> {
          Files.delete(file);
          return queue.recordedWalk(claim.scanId());
        }).when(loopQueue).recordedWalk(claim.scanId());
      }
      var parserEntered = new CountDownLatch(1);
      var releaseParser = new CountDownLatch(1);
      if (afterExtraction) {
        when(extraction.extractArtifact(file)).thenAnswer(call -> {
          parserEntered.countDown();
          assertTrue(releaseParser.await(5, TimeUnit.SECONDS), "SETUP: owned parser released");
          return artifact();
        });
      }
      var fence = loop.fileMutationFence();
      var lockField = FileMutationFence.class.getDeclaredField("lock"); lockField.setAccessible(true);
      var lock = (ReentrantLock) lockField.get(fence);
      var task = new FutureTask<>(action);
      var worker = new Thread(task, "owned-neighbor-extraction");
      boolean retained;
      try {
        if (afterExtraction) {
          worker.start();
          assertTrue(parserEntered.await(5, TimeUnit.SECONDS), "SETUP: original source admitted and hashed");
        }
        fence.lock();
        try {
          if (afterExtraction) {
            if (!finalSnapshot) Files.delete(file);
            releaseParser.countDown();
          } else worker.start();
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (!task.isDone() && !lock.hasQueuedThread(worker) && System.nanoTime() < deadline) Thread.sleep(5);
          assertTrue(task.isDone() || lock.hasQueuedThread(worker), "SETUP: real fence entry or baseline completion");
          a.commitOps().maybeRefreshBlocking(); b.commitOps().maybeRefreshBlocking();
          retained = a.indexCountOps().countByIdAndChunksStrict(key) == 2
              && b.indexCountOps().countByIdAndChunksStrict(key) == 2;
          mutation.run();
        } finally { fence.unlock(); }
        task.get(5, TimeUnit.SECONDS);
        assertAll(
            () -> assertTrue(retained, "both generations must retain parent/chunks while the actual fence is held"),
            this::assertNoGrace);
      } finally {
        releaseParser.countDown();
        worker.join(5_000);
        assertFalse(worker.isAlive(), "owned neighbor thread must finish");
      }
    }

    private void assertRetained() throws Exception { assertCounts(2); }

    private void assertCounts(long expected) throws Exception {
      a.commitOps().maybeRefreshBlocking(); b.commitOps().maybeRefreshBlocking();
      assertEquals(expected, a.indexCountOps().countByIdAndChunksStrict(key));
      assertEquals(expected, b.indexCountOps().countByIdAndChunksStrict(key));
      assertEquals(1, a.indexCountOps().countByIdAndChunksStrict("unrelated"));
      assertEquals(1, b.indexCountOps().countByIdAndChunksStrict("unrelated"));
    }

    private void assertNoGrace() {
      assertNull(identities.lookup(DocumentIdentityStore.pathHash(key)).orElseThrow().deletedAtMs());
    }

    @Override
    public void close() throws Exception {
      loop.close(); identities.close(); queue.close(); a.close(); b.close();
    }
  }

  @FunctionalInterface
  private interface CheckedMutation { void run() throws Exception; }

}
