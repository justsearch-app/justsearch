/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Path;

/**
 * Pure factory for {@link JobQueue.IngestionLedgerEntry} construction.
 *
 * <p>Tempdoc 516 Slice 3 substrate (Appendix A.1). Today the two {@code ledgerEntry} overloads
 * live on {@code IndexingLoop} but are called from both the extract path and the write path —
 * a cross-seam read that prevents the upcoming Slice 4a Extractor + Writer extractions from
 * being clean. Lifted here as static factory methods so both collaborators can call them
 * without pulling the ledger-entry constructor across their own seams.
 *
 * <p>P5 note: a class with two static factory methods, not a strategy interface.
 */
public final class LedgerEntryFactory {

  private LedgerEntryFactory() {}

  /** Path-only entry (envelope unavailable — used for skips/failures pre-validation). */
  public static JobQueue.IngestionLedgerEntry forPathOnly(Path filePath, String collection) {
    return forPathOnly(filePath, collection, null);
  }

  public static JobQueue.IngestionLedgerEntry forPathOnly(Path filePath, String collection,
      JobQueue.EnqueueProvenance provenance) {
    String normalizedPath = PathNormalizer.normalizePath(filePath.toAbsolutePath().toString());
    return new JobQueue.IngestionLedgerEntry(
        FileFreshnessSnapshot.pathHash(normalizedPath),
        blankToNull(collection),
        null,
        null,
        null,
        null,
        null,
        null,
        provenance == null ? null : provenance.originator(),
        provenance == null ? null : provenance.transport());
  }

  /**
   * Envelope-bearing entry. {@code defaultPolicy} is consulted only when {@code artifact} is
   * null (then its {@code policyId()} is used as the fallback).
   */
  public static JobQueue.IngestionLedgerEntry forEnvelope(
      FileEnvelope envelope,
      String collection,
      ValidatedExtractionArtifact artifact,
      TikaExtractionPolicy defaultPolicy) {
    return forEnvelope(envelope, collection, artifact, defaultPolicy, null);
  }

  public static JobQueue.IngestionLedgerEntry forEnvelope(
      FileEnvelope envelope, String collection, ValidatedExtractionArtifact artifact,
      TikaExtractionPolicy defaultPolicy, JobQueue.EnqueueProvenance provenance) {
    if (envelope == null) {
      return null;
    }
    return new JobQueue.IngestionLedgerEntry(
        envelope.pathHash(),
        blankToNull(collection),
        envelope.sizeBytes(),
        envelope.modifiedAtMs(),
        envelope.regularFile() ? "REGULAR_FILE" : "NON_REGULAR_SOURCE",
        artifact != null ? artifact.status().name() : "NOT_CREATED",
        artifact != null ? artifact.policyId() : defaultPolicy.policyId(),
        artifact != null ? artifact.parserId() : "UNKNOWN",
        provenance == null ? null : provenance.originator(),
        provenance == null ? null : provenance.transport());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
