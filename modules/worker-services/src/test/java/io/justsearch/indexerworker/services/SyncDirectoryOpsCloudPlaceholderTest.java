package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 410 Phase 2.1 — verifies cloud-placeholder observations land in the ledger as
 * DEFERRED_POLICY with the CLOUD_PLACEHOLDER reason code, instead of being silently invisible.
 *
 * <p>The Windows {@code FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS} bit cannot be portably synthesized,
 * so we test the {@code recordCloudPlaceholderObservation} helper directly. The platform-bit
 * detection itself is exercised by the existing Windows-only system tests.
 */
final class SyncDirectoryOpsCloudPlaceholderTest {

  @TempDir Path tempDir;

  @Test
  void recordsCloudPlaceholderAsDeferredPolicyOutcome() throws Exception {
    Path file = tempDir.resolve("cloud-only.txt");
    Files.createFile(file);
    RecordingQueue queue = new RecordingQueue();
    SyncDirectoryOps ops = new SyncDirectoryOps(null, null, null, queue, null);

    ops.recordCloudPlaceholderObservation(file, null);

    assertNotNull(queue.lastOutcome, "Cloud placeholder must produce a typed outcome");
    assertEquals(IngestionOutcomeClass.DEFERRED_POLICY, queue.lastOutcome.outcomeClass());
    assertEquals(IngestionReasonCodes.CLOUD_PLACEHOLDER, queue.lastOutcome.reasonCode());
    assertEquals(IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT, queue.lastOutcome.retryPolicy());
    assertNotNull(queue.lastEntry, "Cloud placeholder must record privacy-safe ledger entry");
    assertEquals("CLOUD_PLACEHOLDER", queue.lastEntry.sourceKind());
    assertEquals("NOT_CREATED", queue.lastEntry.artifactStatus());
    assertNull(queue.lastEntry.collection(), "Sync-walk placeholder has no collection tag");
    assertNotNull(queue.lastEntry.pathHash(), "Ledger entry carries privacy-safe path hash");
  }

  @Test
  void dedupsRepeatedCloudPlaceholderObservationsForSamePath() throws Exception {
    Path file = tempDir.resolve("cloud-dedup.txt");
    Files.createFile(file);
    RecordingQueue queue = new RecordingQueue();
    queue.dedup = true;
    SyncDirectoryOps ops = new SyncDirectoryOps(null, null, null, queue, null);

    ops.recordCloudPlaceholderObservation(file, null);
    int firstWriteCount = queue.recordCount;
    ops.recordCloudPlaceholderObservation(file, null);

    assertEquals(
        firstWriteCount,
        queue.recordCount,
        "Repeated observation within the dedup window must not re-record");
  }

  private static final class RecordingQueue implements JobQueue {
    IngestionOutcome lastOutcome;
    IngestionLedgerEntry lastEntry;
    int recordCount;
    boolean dedup;
    private final java.util.Set<String> recorded = new java.util.HashSet<>();

    @Override
    public void open() {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      return paths == null ? 0 : paths.size();
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
    public boolean hasRecentLedgerEvent(String pathHash, String reasonCode, long sinceMs) {
      return dedup && recorded.contains(pathHash + "|" + reasonCode);
    }

    @Override
    public void recordIngestionEvent(
        Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
      lastOutcome = outcome;
      lastEntry = entry;
      recordCount++;
      if (entry != null && entry.pathHash() != null && outcome != null) {
        recorded.add(entry.pathHash() + "|" + outcome.reasonCode());
      }
    }

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
