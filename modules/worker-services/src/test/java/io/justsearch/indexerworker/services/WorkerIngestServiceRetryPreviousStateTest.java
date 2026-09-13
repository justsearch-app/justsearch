/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ipc.RetryIndexingJobRequest;
import io.justsearch.ipc.RetryIndexingJobResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 885 §UD open item 1 — {@code retryIndexingJob} reports the state it replaced.
 *
 * <p>The RPC used to state {@code setPreviousState("FAILED")} unconditionally, without ever reading
 * the row: already wrong for a {@code PENDING}-in-backoff job, and wrong again once item 21b added
 * {@code RETRY_EXHAUSTED}. Every case below returns a state that is NOT {@code "FAILED"}, so each
 * one fails against the pre-fix handler.
 *
 * <p>The queue's own read-in-the-enqueue-transaction is pinned separately by
 * {@code JobQueueRetryLadderTest.reenqueueReportsTheStateItReplaced}; this file pins the wire.
 */
@DisplayName("WorkerIngestService.retryIndexingJob — previous state")
final class WorkerIngestServiceRetryPreviousStateTest {

  private static final String PATH_HASH = "abc123";
  private static final String NORMALIZED_PATH = "C:/corpus/unreachable.txt";

  @Test
  @DisplayName("an exhausted row's retry reports RETRY_EXHAUSTED, not FAILED")
  void exhaustedRowReportsItsRealState() {
    RetryIndexingJobResponse resp = retryWith(new StateQueue(1, "RETRY_EXHAUSTED"));

    assertTrue(resp.getRetried());
    assertEquals(
        "RETRY_EXHAUSTED",
        resp.getPreviousState(),
        "a week of retries must not be relabelled as a parse failure on the retry response");
  }

  @Test
  @DisplayName("a job waiting on a backoff reports PENDING")
  void backoffRowReportsPending() {
    assertEquals("PENDING", retryWith(new StateQueue(1, "PENDING")).getPreviousState());
  }

  @Test
  @DisplayName("a queue with no row-state authority reports UNKNOWN rather than guessing FAILED")
  void unknownPreviousStateIsNotGuessed() {
    assertEquals("UNKNOWN", retryWith(new StateQueue(1, null)).getPreviousState());
  }

  @Test
  @DisplayName("a refused re-enqueue is still NOT_RETRYABLE (unchanged contract)")
  void refusedReenqueueUnchanged() {
    RetryIndexingJobResponse resp = retryWith(new StateQueue(0, "FAILED"));
    assertEquals(false, resp.getRetried());
    assertEquals("NOT_RETRYABLE", resp.getPreviousState());
  }

  private static RetryIndexingJobResponse retryWith(JobQueue queue) {
    WorkerIngestService svc =
        new WorkerIngestService(
            queue,
            null,
            null,
            io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(),
            null,
            null,
            null,
            null,
            null,
            0L);
    svc.setPathResolutionStore(
        new PathResolutionStore() {
          @Override
          public void record(String pathHash, String normalizedPath, long nowMs) {}

          @Override
          public void markRemoved(String pathHash, long nowMs) {}

          @Override
          public Optional<Resolution> lookup(String pathHash) {
            return PATH_HASH.equals(pathHash)
                ? Optional.of(new Resolution(pathHash, NORMALIZED_PATH, 0L, null))
                : Optional.empty();
          }

          @Override
          public int pruneByRootPrefix(String rootPrefix) {
            return 0;
          }

          @Override
          public int pruneOldRemoved(long cutoffMs) {
            return 0;
          }
        });

    return svc.retryIndexingJob(
        RetryIndexingJobRequest.newBuilder().setPathHash(PATH_HASH).build(), CallContext.none());
  }

  /** A queue that reports a fixed re-enqueue outcome, the way SqliteJobQueue reads its own row. */
  private static final class StateQueue implements JobQueue {
    private final int accepted;
    private final String previousState;

    StateQueue(int accepted, String previousState) {
      this.accepted = accepted;
      this.previousState = previousState;
    }

    @Override
    public ReenqueueResult reenqueue(EnqueueEntry entry) {
      return new ReenqueueResult(accepted, previousState);
    }

    @Override
    public void open() {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      throw new AssertionError(
          "the retry path must go through reenqueue — a plain enqueue cannot report the state it"
              + " overwrote");
    }

    @Override
    public List<IndexJob> pollPending(int limit) {
      return List.of();
    }

    @Override
    public void markDone(Path path) {}

    @Override
    public void markFailed(Path path, String errorMessage) {}

    @Override
    public void recordIngestionEvent(
        Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {}

    @Override
    public int recoverStuckJobs() {
      return 0;
    }

    @Override
    public long queueDepth() {
      return 0;
    }

    @Override
    public long completedCount() {
      return 0;
    }

    @Override
    public int cleanupOldJobs(int retentionDays) {
      return 0;
    }

    @Override
    public void close() {}
  }
}
