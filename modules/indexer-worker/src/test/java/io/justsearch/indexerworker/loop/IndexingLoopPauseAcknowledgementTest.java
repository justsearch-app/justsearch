/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.scheduling.IndexingHold;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Preservation checks: the real queue/loop already acknowledge pause after publication on main. */
@Timeout(30)
final class IndexingLoopPauseAcknowledgementTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @Test
  void pauseBetweenLeaseAndPollRemainsPausingThroughPublicationAndResumeDrainsRetry()
      throws Exception {
    checkPause(false);
  }

  @Test
  void pauseBeforeLeaseClaimsNothingUntilResume() throws Exception {
    checkPause(true);
  }

  private void checkPause(boolean beforeLease) throws Exception {
    Path root = Files.createDirectory(directory.resolve("root"));
    Path first = Files.writeString(root.resolve("first.txt"), "first letter");
    Path retry = Files.writeString(root.resolve("retry.txt"), "retry letter");
    var hold = new IndexingHold();
    var pollEntered = new CountDownLatch(1);
    var releasePoll = new CountDownLatch(1);
    var extracting = new CountDownLatch(1);
    var releaseExtraction = new CountDownLatch(1);
    var publishing = new CountDownLatch(1);
    var releasePublication = new CountDownLatch(1);
    var published = new CountDownLatch(1);
    var heldCycles = new CountDownLatch(3);
    var pausedCycles = new AtomicReference<>(heldCycles);
    var resumed = new AtomicBoolean();
    var resumeDrained = new CountDownLatch(1);
    var polls = new AtomicInteger();
    var signals = mock(WorkerSignalBus.class, call -> switch (call.getMethod().getName()) {
      case "indexingHold" -> hold;
      case "isIndexingHeld" -> hold.held();
      case "isMainGpuActive" -> {
        if (hold.snapshot().state() == IndexingHold.State.PAUSED) pausedCycles.get().countDown();
        yield false;
      }
      default -> RETURNS_DEFAULTS.answer(call);
    });
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4))
            .atPath(directory.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
        var queue = spy(new SqliteJobQueue(directory.resolve("queue.db"), 1))) {
      runtime.commitOps().stopCommitTimer();
      runtime.commitOps().suspendNrtRefresh();
      queue.open();
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(first).withinRoot(root))));
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(retry).withinRoot(root))));
      queue.markFailed(retry, "fixture: explicit retry required");
      doAnswer(call -> {
        if (polls.incrementAndGet() == 1 && !beforeLease) {
          pollEntered.countDown();
          assertTrue(releasePoll.await(10, TimeUnit.SECONDS), "release the real queue poll");
        }
        return call.callRealMethod();
      }).when(queue).pollPending(anyInt());
      doAnswer(call -> {
        Object result = call.callRealMethod();
        if (resumed.get() && "DONE".equals(state(queue, first))
            && "DONE".equals(state(queue, retry))) resumeDrained.countDown();
        return result;
      }).when(queue).markDoneTransitions(anyCollection(), any());
      var extraction = mock(TimeboxedContentExtractor.class);
      when(extraction.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
      when(extraction.extractArtifact(any())).thenAnswer(call -> {
        extracting.countDown();
        assertTrue(releaseExtraction.await(10, TimeUnit.SECONDS), "release the admitted batch");
        return ExtractionArtifact.full(new ContentExtractor.ExtractionResult(
            Files.readString(call.getArgument(0)), null, "text/plain"), "pause-fixture");
      });
      var identity = mock(DocumentIdentityStore.class);
      when(identity.resolve(anyString(), anyLong())).thenAnswer(call ->
          new DocumentIdentityStore.Identity(call.getArgument(0), call.getArgument(0),
              call.getArgument(1), call.getArgument(1)));
      CommitOps commits = spy(runtime.commitOps());
      doAnswer(call -> {
        publishing.countDown();
        assertTrue(releasePublication.await(10, TimeUnit.SECONDS), "release real reader publication");
        Object result = call.callRealMethod();
        published.countDown();
        return result;
      }).when(commits).maybeRefreshBlocking();
      var loop = new IndexingLoop(mock(EngineExecutorRegistry.Registration.class),
          mock(EngineExecutorRegistry.Registration.class), queue, runtime.indexingCoordinator(),
          commits, runtime.documentFieldOps(), runtime.indexCountOps(), () -> null, signals,
          IndexingPacing.unthrottled(), null, null, null, null, extraction, null,
          new IndexingLoopOptions(false, null, identity, null, null, null));
      var rootCheck = mock(SourcePresence.RootCheck.class);
      when(rootCheck.admits(root)).thenReturn(true);
      when(rootCheck.vouches(root)).thenReturn(true);
      loop.useRootCheck(rootCheck);
      try {
        if (beforeLease) {
          hold.pause(null, "USER_PAUSED");
          loop.start();
          assertTrue(heldCycles.await(10, TimeUnit.SECONDS), "loop cycles while admission is closed");
          assertEquals(IndexingHold.State.PAUSED, hold.snapshot().state());
          assertEquals(0, polls.get(), "pause before acquisition must not poll or claim");
          assertEquals("PENDING", state(queue, first));
          assertEquals(1, queue.reenqueue(JobQueue.EnqueueEntry.stat(retry).withinRoot(root)).accepted());
          assertEquals("PENDING", state(queue, retry));
          var postRequeueCycles = new CountDownLatch(3);
          pausedCycles.set(postRequeueCycles);
          assertTrue(postRequeueCycles.await(10, TimeUnit.SECONDS), "held cycles after retry requeue");
          assertEquals(0, polls.get(), "requeued work stays unclaimed until Resume");
          assertEquals("PENDING", state(queue, retry));
          releaseExtraction.countDown();
          releasePublication.countDown();
          resumed.set(true);
          hold.resume();
        } else {
          loop.start();
          assertTrue(pollEntered.await(10, TimeUnit.SECONDS), "lease acquired before the actual poll");
          hold.pause(null, "USER_PAUSED");
          assertEquals(IndexingHold.State.PAUSING, hold.snapshot().state());
          var requeued = queue.reenqueue(JobQueue.EnqueueEntry.stat(retry).withinRoot(root));
          assertEquals(1, requeued.accepted());
          assertEquals("FAILED", requeued.previousState());
          releasePoll.countDown();
          assertTrue(extracting.await(10, TimeUnit.SECONDS));
          assertEquals(IndexingHold.State.PAUSING, hold.snapshot().state(), "batch still running");
          releaseExtraction.countDown();
          assertTrue(publishing.await(10, TimeUnit.SECONDS));
          assertEquals("DONE", state(queue, first), "outcomes committed before reader publication");
          assertEquals("DONE", state(queue, retry));
          assertEquals(IndexingHold.State.PAUSING, hold.snapshot().state(), "reader publication pending");
          releasePublication.countDown();
          assertTrue(published.await(10, TimeUnit.SECONDS));
          assertTrue(heldCycles.await(10, TimeUnit.SECONDS), "PAUSED reached after publication");
          assertEquals(IndexingHold.State.PAUSED, hold.snapshot().state());
          assertEquals("retry letter", runtime.documentFieldOps().getDocumentField(
              PathNormalizer.normalizeKey(retry), SchemaFields.CONTENT));
          int acknowledgedPolls = polls.get();
          assertEquals(1, queue.reenqueue(JobQueue.EnqueueEntry.stat(retry).withinRoot(root)).accepted());
          assertTrue(loop.pauseForCutover(5_000), "coordination remains live while user-paused");
          loop.resumeAfterCutover();
          var postRequeueCycles = new CountDownLatch(3);
          pausedCycles.set(postRequeueCycles);
          assertTrue(postRequeueCycles.await(10, TimeUnit.SECONDS),
              "fresh held cycles after retry requeue and cutover resume");
          assertEquals(acknowledgedPolls, polls.get(), "no further queue claim until Resume");
          assertEquals("PENDING", state(queue, retry));
          resumed.set(true);
          hold.resume();
        }
        assertTrue(resumeDrained.await(10, TimeUnit.SECONDS), "Resume commits both queue outcomes");
        assertEquals("DONE", state(queue, first));
        assertEquals("DONE", state(queue, retry), "Resume drains deliberately retried work");
        assertTrue(polls.get() > (beforeLease ? 0 : 1));
      } finally {
        releasePoll.countDown();
        releaseExtraction.countDown();
        releasePublication.countDown();
        loop.resumeAfterCutover();
        loop.close();
      }
    }
  }

  private static String state(SqliteJobQueue queue, Path file) {
    return queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file)).get("state");
  }
}
