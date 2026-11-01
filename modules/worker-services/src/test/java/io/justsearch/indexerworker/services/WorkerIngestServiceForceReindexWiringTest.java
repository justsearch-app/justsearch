/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Both public entry points put force in the queue admission before it can be consumed. */
@DisplayName("WorkerIngestService.scanRoot — durable force admission")
final class WorkerIngestServiceForceReindexWiringTest {

  @TempDir Path tempDir;

  private static WorkerIngestService serviceWith(JobQueue queue, IndexingLoop loop) {
    return new WorkerIngestService(
        queue,
        loop,
        null,
        io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(),
        null,
        null,
        null,
        null,
        null,
        0L);
  }

  private void scan(WorkerIngestService svc, Path root, ScanMode mode) {
    svc.scanRoot(
        ScanRootRequest.newBuilder().setRootPath(root.toString()).setMode(mode).build(),
        (ScanRootProgress progress) -> {
          // progress is not under test here
        },
        CallContext.none());
  }

  private void submit(WorkerIngestService svc, Path file, boolean force) {
    // The response shape is not under test here; returning at all IS the completion signal.
    svc.submitBatch(
        BatchRequest.newBuilder().addFilePaths(file.toString()).setForceReindex(force).build(),
        CallContext.none());
  }

  @Test
  // "a FORCE_REINDEX scan admits force atomically"
  void forceReindexScanAdmitsForceAtomically() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("forced"));
    Path a = Files.writeString(root.resolve("a.txt"), "alpha");
    IndexingLoop loop = mock(IndexingLoop.class);

    RecordingQueue queue = new RecordingQueue();
    scan(serviceWith(queue, loop), root, ScanMode.SCAN_MODE_FORCE_REINDEX);

    assertEquals(List.of(a), queue.enqueuedPaths);
    org.junit.jupiter.api.Assertions.assertTrue(queue.entries.getFirst().forceReindex());
    verify(loop, never()).markForced(any());
  }

  @Test
  // "submitBatch(force_reindex) admits force atomically"
  void submitBatchAdmitsForceAtomically() throws Exception {
    Path file = Files.writeString(tempDir.resolve("b.txt"), "beta");
    IndexingLoop loop = mock(IndexingLoop.class);

    RecordingQueue queue = new RecordingQueue();
    submit(serviceWith(queue, loop), file, true);

    assertEquals(List.of(file), queue.enqueuedPaths);
    org.junit.jupiter.api.Assertions.assertTrue(queue.entries.getFirst().forceReindex());
    verify(loop, never()).markForced(any());
  }

  @Test
  // "submitBatch without force_reindex marks nothing"
  void submitBatchWithoutForceReindexMarksNothing() throws Exception {
    Path file = Files.writeString(tempDir.resolve("b.txt"), "beta");
    IndexingLoop loop = mock(IndexingLoop.class);
    RecordingQueue queue = new RecordingQueue();

    submit(serviceWith(queue, loop), file, false);

    // Precision: the batch WAS accepted, so "nothing marked" is the flag gate firing rather than a
    // rejected path.
    assertEquals(1, queue.enqueuedPaths.size(), "the ordinary batch still admits the file");
    verify(loop, never()).markForced(any());
  }

  @Test
  // "an INITIAL scan marks nothing — and does not even touch the indexing loop"
  void initialScanDoesNotMarkAnything() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("plain"));
    Files.writeString(root.resolve("a.txt"), "alpha");
    IndexingLoop loop = mock(IndexingLoop.class);
    RecordingQueue queue = new RecordingQueue();

    scan(serviceWith(queue, loop), root, ScanMode.SCAN_MODE_INITIAL);

    // Precision: the walk DID admit the file, so "nothing marked" is the mode gate firing rather
    // than a scan that admitted nothing.
    assertEquals(1, queue.enqueuedPaths.size(), "the ordinary scan still admits the file");
    verify(loop, never()).markForced(any());
  }

  /** Minimal queue that records what the walk enqueued. */
  private static final class RecordingQueue implements JobQueue {
    final List<Path> enqueuedPaths = new ArrayList<>();
    final List<EnqueueEntry> entries = new ArrayList<>();

    @Override
    public int enqueueEntries(List<EnqueueEntry> admitted, String collection) {
      entries.addAll(admitted);
      return enqueue(EnqueueEntry.paths(admitted), collection);
    }


    @Override
    public void open() {}

    @Override
    public void returnUnfinishedClaims(Collection<IndexJob> claims) {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      enqueuedPaths.addAll(paths);
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
