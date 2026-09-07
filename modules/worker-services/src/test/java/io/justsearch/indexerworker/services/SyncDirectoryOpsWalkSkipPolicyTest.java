package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ipc.SyncDirectoryResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mirrors {@link WorkerScanOpsTest#appliesIngestionSkipPolicyAtWalkTime} for the sync-directory
 * walk path — {@code SyncDirectoryOps.visitFile} did not apply {@code IngestionSkipPolicy} at
 * walk time, so junk files (lock files, temp files, thumbnail caches, compiled bytecode) reached
 * the enqueue path instead of being skipped like the initial-scan walk in {@link WorkerScanOps}.
 */
final class SyncDirectoryOpsWalkSkipPolicyTest {

  @TempDir Path tempDir;

  @Test
  void appliesIngestionSkipPolicyAtWalkTime() throws Exception {
    Path root = tempDir.resolve("policy-skip");
    Files.createDirectories(root);
    Files.writeString(root.resolve("module.pyc"), "bytecode");
    Files.writeString(root.resolve("~$Office.docx"), "lock-file");
    Files.writeString(root.resolve("draft.tmp"), "temp");
    Files.writeString(root.resolve("Thumbs.db"), "system");
    Path keep = Files.writeString(root.resolve("notes.md"), "kept");
    RecordingQueue queue = new RecordingQueue();
    SyncDirectoryOps ops = new SyncDirectoryOps(null, null, null, queue, null);

    // force=true so the walk enqueues every non-skipped file unconditionally (no indexed-path
    // lookup, which would require a real readPathOps).
    SyncDirectoryResponse resp = ops.execute(root.toString(), true);

    assertEquals("", resp.getError(), "the walk must not have terminated with an error");
    assertEquals(List.of(keep), queue.enqueuedPaths, "Only the non-policy-skipped file is enqueued");
  }

  /**
   * Pins that the sync-directory walk passes each file's real byte size to the queue (tempdoc 813
   * Slice B) — the visitor already holds {@code BasicFileAttributes}, so a regression to the
   * size-dropping path-only default overload would silently null every size on re-sync.
   */
  @Test
  void syncWalkCarriesEachFilesRealSizeToTheQueue() throws Exception {
    Path root = tempDir.resolve("size-pin");
    Files.createDirectories(root);
    Path small = Files.writeString(root.resolve("small.md"), "abc");
    Path large = Files.writeString(root.resolve("large.md"), "0123456789");
    RecordingQueue queue = new RecordingQueue();
    SyncDirectoryOps ops = new SyncDirectoryOps(null, null, null, queue, null);

    SyncDirectoryResponse resp = ops.execute(root.toString(), true);

    assertEquals("", resp.getError(), "the walk must not have terminated with an error");
    assertEquals(2, queue.enqueuedEntries.size(), "Both files enqueued as sized entries");
    for (JobQueue.EnqueueEntry entry : queue.enqueuedEntries) {
      assertEquals(
          Files.size(entry.path()),
          entry.sizeBytes(),
          "Entry for " + entry.path() + " must carry the file's real size");
    }
    long smallSize = Files.size(small);
    long largeSize = Files.size(large);
    assertEquals(
        2,
        queue.enqueuedEntries.stream()
            .filter(e -> e.sizeBytes() == smallSize || e.sizeBytes() == largeSize)
            .count(),
        "The two distinct real sizes must both be recorded");
  }

  private static final class RecordingQueue implements JobQueue {
    final List<Path> enqueuedPaths = new ArrayList<>();
    final List<EnqueueEntry> enqueuedEntries = new ArrayList<>();

    @Override
    public void open() {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      enqueuedPaths.addAll(paths);
      return paths.size();
    }

    @Override
    public int enqueueEntries(List<EnqueueEntry> entries, String collection) {
      enqueuedEntries.addAll(entries);
      return enqueue(EnqueueEntry.paths(entries), collection);
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
