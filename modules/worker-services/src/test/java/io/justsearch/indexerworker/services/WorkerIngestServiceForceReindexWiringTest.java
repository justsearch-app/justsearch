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
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Tempdoc 821 §3-C3 — the RPC-entry wiring between a {@code SCAN_MODE_FORCE_REINDEX} request and
 * {@code IndexingLoop.markForced}.
 *
 * <p>{@code WorkerScanOpsTest} pins that a forced scan feeds its {@code ForcedPathSink}, and
 * {@code JobBatchExtractorForcedPathTest} pins that a path in that set bypasses the UNCHANGED
 * branch. Neither sees the one line that connects them ({@code WorkerIngestService#scanRoot}) — a
 * sink wired to a no-op there would leave both green while force-reindex stayed inert.
 *
 * <p>It also pins the reason that line is a lambda rather than {@code indexingLoop::markForced}: a
 * method reference dereferences its receiver when the sink is CREATED, so an ordinary scan would
 * start depending on a field only the forced branch uses.
 */
@DisplayName("WorkerIngestService.scanRoot — force-reindex marking")
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
  @DisplayName("a FORCE_REINDEX scan marks its admitted paths through IndexingLoop.markForced")
  void forceReindexScanReachesMarkForced() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("forced"));
    Path a = Files.writeString(root.resolve("a.txt"), "alpha");
    IndexingLoop loop = mock(IndexingLoop.class);

    scan(serviceWith(new RecordingQueue(), loop), root, ScanMode.SCAN_MODE_FORCE_REINDEX);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(loop).markForced(captor.capture());
    assertEquals(
        Set.of(PathNormalizer.normalizeKey(a)),
        Set.copyOf(captor.getValue()),
        "the key must be the one JobBatchExtractor looks the path up by");
  }

  @Test
  @DisplayName("submitBatch(force_reindex) marks the envelope's key, not a hand-rolled variant")
  void submitBatchForceReindexMarksTheEnvelopeKey() throws Exception {
    Path file = Files.writeString(tempDir.resolve("b.txt"), "beta");
    IndexingLoop loop = mock(IndexingLoop.class);

    submit(serviceWith(new RecordingQueue(), loop), file, true);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(loop).markForced(captor.capture());
    // Tempdoc 821 §P/P3: the batch API's force path used to spell its own derivation here
    // (absolutize, never normalize). Binding the seam to PathNormalizer#normalizeKey is what keeps
    // it agreeing with FileFreshnessSnapshot.capture — the only producer of the key the extractor
    // looks up. JobBatchExtractorForcedPathTest carries the behavioural arms.
    assertEquals(
        Set.of(PathNormalizer.normalizeKey(file)),
        Set.copyOf(captor.getValue()),
        "the key must be the one JobBatchExtractor looks the path up by");
  }

  @Test
  @DisplayName("submitBatch without force_reindex marks nothing")
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
  @DisplayName("an INITIAL scan marks nothing — and does not even touch the indexing loop")
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

    @Override
    public void open() {}

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
