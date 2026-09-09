/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Path;
import java.util.function.LongConsumer;

/**
 * Resolves a file whose snapshot has become stale between extract and write.
 *
 * <p>Tempdoc 516 Slice 4a.2 — shared helper for the two stale re-check sites
 * that previously lived as {@code IndexingLoop.handleStaleAfterSnapshot}:
 * the extract path post-validation (Extractor) and the write path pre-write
 * (Writer). Both call {@link #tryHandleStale} with the same shape; this class
 * unifies the logic so neither collaborator straddles the seam.
 *
 * <p>Side effect: when the validation reports DELETED, the resolver triggers
 * the index-side deletion via {@link StaleSourceHandler#deleteMissingSource}
 * and reports the resulting delete count via the {@link LongConsumer}
 * {@code indexedDelta} callback so the caller's commit-driver counter can
 * advance. The counter itself is NOT mutated here — that's a residue
 * concern.
 *
 * <p>P5 boundary: a concrete class with one method and a closed set of
 * delegates, not a strategy interface.
 */
public final class StaleSnapshotResolver {

  private final WorkerIngestionAuthority ingestionAuthority;
  private final StaleSourceHandler staleSourceHandler;
  private final IngestionOutcomeJournal journal;
  private final JobQueue jobQueue;
  private final TimeboxedContentExtractor contentExtractor;
  private final LongConsumer indexedDelta;

  public StaleSnapshotResolver(
      WorkerIngestionAuthority ingestionAuthority,
      StaleSourceHandler staleSourceHandler,
      IngestionOutcomeJournal journal,
      JobQueue jobQueue,
      TimeboxedContentExtractor contentExtractor,
      LongConsumer indexedDelta) {
    this.ingestionAuthority = ingestionAuthority;
    this.staleSourceHandler = staleSourceHandler;
    this.journal = journal;
    this.jobQueue = jobQueue;
    this.contentExtractor = contentExtractor;
    this.indexedDelta = indexedDelta;
  }

  /**
   * @return {@code true} when the file is stale and the appropriate action
   *     (mark-done for DELETED, defer for other change shapes) has been
   *     recorded; {@code false} when the file is fresh and the caller may
   *     proceed.
   */
  public boolean tryHandleStale(
      Path filePath,
      FileEnvelope envelope,
      String collection,
      ValidatedExtractionArtifact artifact,
      String timing, JobQueue.EnqueueProvenance provenance) {
    FileFreshnessSnapshot.SourceValidationResult validation =
        FileFreshnessSnapshot.fromEnvelope(envelope).validateNow();
    if (validation == FileFreshnessSnapshot.SourceValidationResult.FRESH) {
      return false;
    }
    return handleStale(filePath, envelope, collection, artifact, timing, validation, provenance);
  }

  /** Records a caller-proven stale condition through the same fail-closed outcome path. */
  boolean handleKnownStale(
      Path filePath,
      FileEnvelope envelope,
      String collection,
      ValidatedExtractionArtifact artifact,
      String timing,
      FileFreshnessSnapshot.SourceValidationResult validation, JobQueue.EnqueueProvenance provenance) {
    if (validation == FileFreshnessSnapshot.SourceValidationResult.FRESH) {
      throw new IllegalArgumentException("Known-stale validation must not be FRESH");
    }
    return handleStale(filePath, envelope, collection, artifact, timing, validation, provenance);
  }

  private boolean handleStale(
      Path filePath,
      FileEnvelope envelope,
      String collection,
      ValidatedExtractionArtifact artifact,
      String timing,
      FileFreshnessSnapshot.SourceValidationResult validation, JobQueue.EnqueueProvenance provenance) {
    IngestionOutcome staleOutcome = ingestionAuthority.staleOutcome(validation, timing);
    if (validation == FileFreshnessSnapshot.SourceValidationResult.DELETED) {
      indexedDelta.accept(staleSourceHandler.deleteMissingSource(filePath));
      journal.recordOutcomeSafely(
          filePath,
          "STALE_DELETED",
          () ->
              jobQueue.markDone(
                  filePath,
                  staleOutcome,
                  LedgerEntryFactory.forEnvelope(
                      envelope, collection, artifact, contentExtractor.extractionPolicy(), provenance)));
      return true;
    }
    journal.recordOutcomeSafely(
        filePath,
        "STALE_DEFER",
        () ->
            jobQueue.defer(
                filePath,
                staleOutcome,
                LedgerEntryFactory.forEnvelope(
                    envelope, collection, artifact, contentExtractor.extractionPolicy(), provenance)));
    return true;
  }
}
