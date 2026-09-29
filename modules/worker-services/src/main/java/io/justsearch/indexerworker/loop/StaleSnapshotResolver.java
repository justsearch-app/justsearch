/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
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
   * Resolves a fresh or stale file snapshot after extraction.
   *
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
      String timing, JobQueue.EnqueueProvenance provenance, JobQueue.IndexJob claim) {
    FileFreshnessSnapshot.SourceValidationResult validation =
        FileFreshnessSnapshot.fromEnvelope(envelope).validateNow();
    if (validation == FileFreshnessSnapshot.SourceValidationResult.FRESH) {
      return false;
    }
    return handleStale(filePath, envelope, collection, artifact, timing, validation, provenance, claim);
  }

  /** Records a caller-proven stale condition through the same fail-closed outcome path. */
  boolean handleKnownStale(
      Path filePath,
      FileEnvelope envelope,
      String collection,
      ValidatedExtractionArtifact artifact,
      String timing,
      FileFreshnessSnapshot.SourceValidationResult validation, JobQueue.EnqueueProvenance provenance, JobQueue.IndexJob claim) {
    if (validation == FileFreshnessSnapshot.SourceValidationResult.FRESH) {
      throw new IllegalArgumentException("Known-stale validation must not be FRESH");
    }
    return handleStale(filePath, envelope, collection, artifact, timing, validation, provenance, claim);
  }

  /**
   * A stable new hash may replace an obsolete streaming candidate witness atomically.
   *
   * @return {@code true} when replacement or defer handled the mismatch; {@code false} only when
   *     an exact complete captured walk may carry the stable source to the writer
   */
  boolean handleChangedAcceptedSource(
      Path filePath, FileEnvelope envelope, String collection,
      ValidatedExtractionArtifact artifact, String observedSha256,
      JobQueue.EnqueueProvenance provenance, JobQueue.IndexJob claim) {
    if (mayReplayCapturedSourceChange(claim)) {
      return false;
    }
    IngestionOutcome staleOutcome = ingestionAuthority.staleOutcome(
        FileFreshnessSnapshot.SourceValidationResult.CONTENT_CHANGED, "since admission");
    var entry = LedgerEntryFactory.forEnvelope(
        envelope, collection, artifact, contentExtractor.extractionPolicy(), provenance);
    journal.recordOutcomeSafely(filePath, "STALE_SOURCE_SUPERSEDE", () -> {
      if (!(jobQueue instanceof SwitchBufferCapableQueue candidateQueue)
          || !candidateQueue.supersedeStreamingRecordedSource(
              claim, observedSha256, staleOutcome, entry)) {
        jobQueue.deferClaim(claim, staleOutcome, entry);
      }
    });
    return true;
  }

  /**
   * A completed captured enumeration freezes membership, so replacing its H1 source witness would
   * incorrectly turn it into a streaming walk. The exact unsealed walk instead keeps the issued
   * claim and lets the writer's ownership and fresh-source checks decide whether stable H2 may
   * publish. Missing or unreadable progress is never replay permission.
   */
  private boolean mayReplayCapturedSourceChange(JobQueue.IndexJob claim) {
    if (claim == null || claim.scanId() == null || claim.walkEpoch() == null) {
      return false;
    }
    return jobQueue.recordedWalk(claim.scanId())
        .filter(progress -> claim.scanId().equals(progress.operationKey()))
        .filter(JobQueue.WalkProgress::capturedPlan)
        .filter(progress -> progress.enumerationEpoch() == claim.walkEpoch())
        .filter(
            progress ->
                progress.enumerationOutcome() == JobQueue.WalkEnumerationOutcome.COMPLETE)
        .filter(progress -> progress.sealedAt() == null)
        .isPresent();
  }

  private boolean handleStale(
      Path filePath,
      FileEnvelope envelope,
      String collection,
      ValidatedExtractionArtifact artifact,
      String timing,
      FileFreshnessSnapshot.SourceValidationResult validation, JobQueue.EnqueueProvenance provenance, JobQueue.IndexJob claim) {
    IngestionOutcome staleOutcome = ingestionAuthority.staleOutcome(validation, timing);
    if (validation == FileFreshnessSnapshot.SourceValidationResult.DELETED) {
      indexedDelta.accept(staleSourceHandler.deleteMissingSource(filePath));
      journal.recordOutcomeSafely(
          filePath,
          "STALE_DELETED",
          () ->
              jobQueue.markClaimDone(
                  claim,
                  staleOutcome,
                  LedgerEntryFactory.forEnvelope(
                      envelope, collection, artifact, contentExtractor.extractionPolicy(), provenance)));
      return true;
    }
    journal.recordOutcomeSafely(
        filePath,
        "STALE_DEFER",
        () ->
            jobQueue.deferClaim(
                claim,
                staleOutcome,
                LedgerEntryFactory.forEnvelope(
                    envelope, collection, artifact, contentExtractor.extractionPolicy(), provenance)));
    return true;
  }
}
