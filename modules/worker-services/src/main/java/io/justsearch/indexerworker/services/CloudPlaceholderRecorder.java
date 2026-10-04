/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.OutcomeWriteException;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 410 §3 / Phase 2.1 — emits a privacy-safe DEFERRED_POLICY ledger event for an observed
 * cloud-only placeholder so the file surfaces in {@code /api/diagnostics/ingestion/*} instead of
 * being silently invisible. Reading the file would trigger network hydration, so the ledger row is
 * the user-visible signal — the file is intentionally not enqueued.
 *
 * <p>Shared by {@link SyncDirectoryOps} (sync-walk pass) and {@link WorkerScanOps} (Worker-owned
 * traversal); both paths must produce the same ledger semantics.
 */
class CloudPlaceholderRecorder {

  private static final Logger log = LoggerFactory.getLogger(CloudPlaceholderRecorder.class);

  /**
   * Window after which a cloud-placeholder observation is re-recorded for the same path.
   * Repeated walks within the window dedup against the most recent ledger event for the same
   * path-hash + reason code.
   */
  static final long DEDUP_WINDOW_MS = 24L * 60L * 60L * 1000L;

  private final JobQueue jobQueue;

  CloudPlaceholderRecorder(JobQueue jobQueue) {
    this.jobQueue = jobQueue;
  }

  /**
   * Records a cloud-placeholder observation. Dedups against {@link #DEDUP_WINDOW_MS} so repeated
   * scans of the same root over a 24h window record at most one event per path. The dedup probe is
   * best-effort — a probe failure falls through to insert; an insert failure is logged and dropped.
   */
  void record(Path file) {
    record(file, null, CallContext.none().provenance());
  }

  void record(Path file, String collection, JobQueue.EnqueueProvenance provenance) {
    String normalizedPath = PathNormalizer.normalizePath(file.toAbsolutePath().toString());
    String pathHash = sha256Hex(normalizedPath);
    long since = System.currentTimeMillis() - DEDUP_WINDOW_MS;
    if (jobQueue.hasRecentLedgerEvent(pathHash, IngestionReasonCodes.CLOUD_PLACEHOLDER, since)) {
      return;
    }
    IngestionOutcome outcome =
        IngestionOutcome.of(
            IngestionOutcomeClass.DEFERRED_POLICY,
            IngestionReasonCodes.CLOUD_PLACEHOLDER,
            IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT,
            "Cloud-only placeholder; reading would hydrate over network");
    JobQueue.IngestionLedgerEntry entry =
        new JobQueue.IngestionLedgerEntry(
            pathHash, collection, null, null, "CLOUD_PLACEHOLDER", "NOT_CREATED", "n/a", "n/a",
            provenance.originator(), provenance.transport());
    try {
      jobQueue.recordIngestionEvent(file, outcome, entry);
    } catch (OutcomeWriteException e) {
      log.debug(
          "Cloud placeholder ledger write failed (best-effort): {} ({})",
          file.getFileName(),
          e.getMessage());
    }
  }

  static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
