/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.BatchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 811 (C-2a) — the single-file ingest arm's collection tag must survive the whole way to the
 * job queue, which is what carries it into {@code IndexingDocumentOps}'s {@code collection} field
 * write (via {@code IndexJob.collection()} → {@code JobBatchExtractor} → {@code JobBatchWriter}).
 *
 * <p>The directory arm's equivalent is already pinned by {@code WorkerScanOpsTest}
 * ({@code assertEquals("docs", queue.lastCollection)}); before 811 nothing exercised
 * {@code BatchRequest.target_collection} at all, because every ad-hoc caller passed null.
 */
@DisplayName("WorkerIngestService.submitBatch — collection tag")
final class WorkerIngestServiceCollectionTagTest {

  @TempDir Path tempDir;

  private static WorkerIngestService serviceWith(JobQueue queue) {
    return new WorkerIngestService(
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
  }

  private static BatchResponse submit(WorkerIngestService svc, BatchRequest request) {
    return svc.submitBatch(request, CallContext.none());
  }

  @Test
  @DisplayName("target_collection reaches JobQueue.enqueue verbatim")
  void targetCollectionReachesQueue() throws Exception {
    Path file = Files.writeString(tempDir.resolve("note.txt"), "content");
    RecordingQueue queue = new RecordingQueue();

    BatchResponse resp =
        submit(
            serviceWith(queue),
            BatchRequest.newBuilder()
                .addFilePaths(file.toAbsolutePath().toString())
                .setTargetCollection("mcp-ingest")
                .build());

    assertEquals(1, resp.getAcceptedCount(), resp.getErrorMessage());
    assertEquals(1, queue.enqueuedPaths.size());
    assertEquals(
        "mcp-ingest",
        queue.lastCollection,
        "the tag must reach the queue — it is what IndexingDocumentOps writes as SchemaFields.COLLECTION");
  }

  @Test
  @DisplayName("an empty target_collection stays the index default (null), not the empty string")
  void emptyTargetCollectionIsNull() throws Exception {
    Path file = Files.writeString(tempDir.resolve("untagged.txt"), "content");
    RecordingQueue queue = new RecordingQueue();

    submit(
        serviceWith(queue),
        BatchRequest.newBuilder().addFilePaths(file.toAbsolutePath().toString()).build());

    assertTrue(queue.called, "enqueue must have been reached");
    assertNull(queue.lastCollection, "a blank tag must normalize to null, not \"\"");
  }

  private static final class RecordingQueue implements JobQueue {
    final List<Path> enqueuedPaths = new ArrayList<>();
    String lastCollection;
    boolean called;

    @Override
    public void open() {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      enqueuedPaths.addAll(paths);
      lastCollection = collection;
      called = true;
      return paths.size();
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
