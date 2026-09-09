/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ContentExtractor.BudgetExceededException;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.SandboxExtractionException;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor.ExtractionTimeoutException;
import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts content from a batch of {@link JobQueue.IndexJob}s.
 *
 * <p>Tempdoc 516 Slice 4a.3 — extracted from {@link IndexingLoop}. Owns the
 * per-job admission / unmodified-check / Tika extraction / validation path
 * and the per-batch {@code indexEmptyForBatch} cache. Returns the surviving
 * {@link ExtractedJob} list; failed / skipped / deleted / stale jobs are
 * recorded into the ledger + {@link BatchStats} internally and don't appear
 * in the return value.
 *
 * <p>Cross-seam state — {@code indexedSinceCommit} — is reported back via
 * the {@link LongConsumer} {@code indexedDelta} callback when the
 * STALE_DONE path triggers a missing-source delete. The residue owns the
 * counter.
 *
 * <p>P5 boundary: a concrete class with two entry points (extractAll +
 * resetPerBatchCache). No strategy interface.
 */
public final class JobBatchExtractor {

  private static final Logger log = LoggerFactory.getLogger(JobBatchExtractor.class);
  private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("indexing");

  private final WorkerIngestionAuthority ingestionAuthority;
  private final IngestionOutcomeJournal journal;
  private final JobQueue jobQueue;
  private final TimeboxedContentExtractor contentExtractor;
  private final DocumentFieldOps documentFieldOps;
  private final IndexCountOps indexCountOps;
  private final BatchStats batchStats;
  private final StaleSnapshotResolver staleResolver;
  private final StaleSourceHandler staleSourceHandler;
  private final IndexingPacing indexingPacing;
  private final AtomicBoolean running;
  private final Set<String> forcedPaths;
  private final Supplier<PathResolutionStore> pathResolutionStoreSupplier;
  private final Supplier<DocumentIdentityStore> documentIdentityStoreSupplier;
  private final IndexingDocumentOps.StageRecorder stageRecorder;
  private final BooleanSupplier detailedTracingSupplier;
  private final LongConsumer indexedDelta;

  private boolean indexEmptyForBatch;

  public JobBatchExtractor(
      WorkerIngestionAuthority ingestionAuthority,
      IngestionOutcomeJournal journal,
      JobQueue jobQueue,
      TimeboxedContentExtractor contentExtractor,
      DocumentFieldOps documentFieldOps,
      IndexCountOps indexCountOps,
      BatchStats batchStats,
      StaleSnapshotResolver staleResolver,
      StaleSourceHandler staleSourceHandler,
      IndexingPacing indexingPacing,
      AtomicBoolean running,
      Set<String> forcedPaths,
      Supplier<PathResolutionStore> pathResolutionStoreSupplier,
      Supplier<DocumentIdentityStore> documentIdentityStoreSupplier,
      IndexingDocumentOps.StageRecorder stageRecorder,
      BooleanSupplier detailedTracingSupplier,
      LongConsumer indexedDelta) {
    this.ingestionAuthority = ingestionAuthority;
    this.journal = journal;
    this.jobQueue = jobQueue;
    this.contentExtractor = contentExtractor;
    this.documentFieldOps = documentFieldOps;
    this.indexCountOps = indexCountOps;
    this.batchStats = batchStats;
    this.staleResolver = staleResolver;
    this.staleSourceHandler = staleSourceHandler;
    this.indexingPacing = indexingPacing;
    this.running = running;
    this.forcedPaths = forcedPaths;
    this.pathResolutionStoreSupplier = pathResolutionStoreSupplier;
    this.documentIdentityStoreSupplier = documentIdentityStoreSupplier;
    this.stageRecorder = stageRecorder;
    this.detailedTracingSupplier = detailedTracingSupplier;
    this.indexedDelta = indexedDelta;
  }

  /**
   * Extracts content for each job in the batch. Records skipped/failed/stale
   * outcomes internally; returns only the jobs that survived to a valid
   * {@link ExtractedJob}.
   *
   * <p>Refreshes the per-batch {@code indexEmptyForBatch} cache as the first
   * step so the per-job loop can short-circuit {@code isUnmodified} on an
   * empty index (312 item 10).
   */
  public List<ExtractedJob> extractAll(List<JobQueue.IndexJob> jobs) {
    indexEmptyForBatch = indexCountOps.docCount() == 0;

    List<ExtractedJob> extracted = new ArrayList<>(jobs.size());
    for (JobQueue.IndexJob job : jobs) {
      if (!running.get()) break;

      ExtractedJob ex = extractJob(job.path(), job.collection(), job.provenance());
      if (ex != null) {
        extracted.add(ex);
      }
      // Tempdoc 885 item 3: extraction is the CPU-heaviest step in the batch, so it paces per
      // file. The predecessor abandoned the rest of the batch on user activity; the duty cycle
      // slows it instead, which is why arm (c) can still make progress under continuous search.
      indexingPacing.pace();
    }
    return extracted;
  }

  /** Resets the per-batch index-empty cache. Called from {@code resetForProfiling}. */
  public void resetPerBatchCache() {
    indexEmptyForBatch = false;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private ExtractedJob extractJob(Path filePath, String collection, JobQueue.EnqueueProvenance provenance) {
    log.debug("Processing: {}", filePath);

    long startTime = System.currentTimeMillis();
    FileEnvelope envelope = null;

    try {
      SourceAdmission admission = ingestionAuthority.admit(filePath);
      if (admission.action() == SourceAdmissionAction.STALE_DONE) {
        log.debug("File not found, treating as delete: {}", filePath);
        bestEffortDeleteMissingSource(filePath);
        journal.recordOutcomeSafely(
            filePath,
            "STALE_DONE",
            () -> jobQueue.markDone(filePath, admission.outcome(), ledgerEntry(filePath, collection, provenance)));
        batchStats.recordSkipped();
        return null;
      }

      if (admission.action() == SourceAdmissionAction.RETRYABLE_FAILURE) {
        log.warn("File not readable, marking as failed: {}", filePath);
        journal.recordOutcomeSafely(
            filePath,
            admission.outcome().outcomeClass().name(),
            () -> jobQueue.markFailed(filePath, admission.outcome(), ledgerEntry(filePath, collection, provenance)));
        journal.recordFailedMetric(filePath, null);
        batchStats.recordFailed();
        return null;
      }

      if (admission.action() == SourceAdmissionAction.SKIP_DONE) {
        JobQueue.IngestionLedgerEntry entry =
            admission.envelope() != null
                ? ledgerEntry(filePath, admission.envelope(), collection, null, provenance)
                : ledgerEntry(filePath, collection, provenance);
        journal.recordOutcomeSafely(
            filePath,
            admission.outcome().outcomeClass().name(),
            () -> jobQueue.markDone(filePath, admission.outcome(), entry));
        batchStats.recordSkipped();
        return null;
      }
      envelope = admission.envelope();
      // Tempdoc 419 / T5.2 (ADR-0028): record (pathHash, normalizedPath) into the scoped
      // reverse-lookup store so the activity panel can later answer "which file is this hash?".
      pathResolutionStoreSupplier.get().record(
          envelope.pathHash(), envelope.normalizedPath(), envelope.observedAtMs());
      String docUid;
      try {
        docUid =
            documentIdentityStoreSupplier
                .get()
                .resolve(envelope.pathHash(), envelope.observedAtMs())
                .docUid();
      } catch (RuntimeException identityError) {
        log.error("Document identity persistence failed for: {}", filePath, identityError);
        FileEnvelope admittedEnvelope = envelope;
        journal.recordOutcomeSafely(
            filePath,
            "WRITE_FAILED(document_identity)",
            () ->
                jobQueue.markFailed(
                    filePath,
                    journal.outcome(
                        IngestionOutcomeClass.WRITE_FAILED,
                        IngestionReasonCodes.WRITE_FAILED,
                        IngestionRetryPolicy.RETRY_WITH_BACKOFF,
                        failureDetail(identityError)),
                    ledgerEntry(filePath, admittedEnvelope, collection, null, provenance)));
        journal.recordFailedMetric(filePath, null);
        batchStats.recordFailed();
        return null;
      }
      try {
        String normalizedPath = envelope.normalizedPath();
        boolean forceReindex = forcedPaths.remove(normalizedPath);
        // Skip isUnmodified() on empty index — every doc is new (312 item 10).
        if (!forceReindex && !indexEmptyForBatch) {
          if (documentFieldOps.isUnmodified(normalizedPath, envelope.modifiedAtMs())) {
            log.debug("File unchanged, skipping: {}", filePath);
            FileEnvelope envelopeForLedger = envelope;
            journal.recordOutcomeSafely(
                filePath,
                "UNCHANGED",
                () ->
                    jobQueue.markDone(
                        filePath,
                        journal.skipped(IngestionReasonCodes.UNCHANGED),
                        ledgerEntry(filePath, envelopeForLedger, collection, null, provenance)));
            batchStats.recordSkipped();
            return null;
          }
        } else if (forceReindex) {
          log.debug("Force reindex requested, bypassing unchanged check: {}", filePath);
        }
      } catch (RuntimeException e) {
        log.debug("Could not check modification state, proceeding with extraction: {}", filePath);
      }

      Span extractSpan = maybeSpan("indexing.extract");
      extractSpan.setAttribute("doc.path", filePath.toString());
      long extractStart = System.currentTimeMillis();
      ValidatedExtractionArtifact artifact;
      String sourceSha256 = SourceContentHash.sha256(filePath);
      try {
        // W1.5 — inlined what used to be extractContent + validateArtifact wrappers.
        ExtractionArtifact rawArtifact = contentExtractor.extractArtifact(filePath);
        String sourcePathHash = envelope != null ? envelope.pathHash() : null;
        artifact = rawArtifact.validate(contentExtractor.extractionPolicy(), sourcePathHash);
      } finally {
        extractSpan.end();
      }
      stageRecorder.record("extract", System.currentTimeMillis() - extractStart, null);

      String sourceSha256AfterExtraction;
      try {
        sourceSha256AfterExtraction = SourceContentHash.sha256(filePath);
      } catch (IOException changedDuringExtraction) {
        if (staleResolver.tryHandleStale(
            filePath, envelope, collection, artifact, "after extraction", provenance)) {
          batchStats.recordSkipped();
          return null;
        }
        throw changedDuringExtraction;
      }
      if (!sourceSha256.equals(sourceSha256AfterExtraction)) {
        if (staleResolver.tryHandleStale(
            filePath, envelope, collection, artifact, "after extraction", provenance)) {
          batchStats.recordSkipped();
          return null;
        }
        staleResolver.handleKnownStale(
            filePath,
            envelope,
            collection,
            artifact,
            "during extraction",
            FileFreshnessSnapshot.SourceValidationResult.CONTENT_CHANGED, provenance);
        batchStats.recordSkipped();
        return null;
      }

      if (staleResolver.tryHandleStale(filePath, envelope, collection, artifact, "after extraction", provenance)) {
        batchStats.recordSkipped();
        return null;
      }

      return new ExtractedJob(
          filePath, collection, artifact, startTime, envelope, sourceSha256, docUid, provenance);

    } catch (BudgetExceededException e) {
      log.warn("Extraction budget exceeded for: {} - {}", filePath, e.getMessage());
      FileEnvelope envelopeForLedger = envelope;
      journal.recordOutcomeSafely(
          filePath,
          "BUDGET_EXCEEDED",
          () ->
              jobQueue.markFailed(
                  filePath,
                  journal.outcome(
                      IngestionOutcomeClass.BUDGET_EXCEEDED,
                      e.reasonCode(),
                      IngestionRetryPolicy.NONE,
                      failureDetail(e)),
                  ledgerEntry(filePath, envelopeForLedger, collection, null, provenance)));
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    } catch (ExtractionTimeoutException e) {
      log.warn("Extraction timeout for: {} - {}", filePath, e.getMessage());
      FileEnvelope envelopeForLedger = envelope;
      journal.recordOutcomeSafely(
          filePath,
          "PARSER_TIMEOUT",
          () ->
              jobQueue.markFailed(
                  filePath,
                  journal.outcome(
                      IngestionOutcomeClass.PARSER_TIMEOUT,
                      IngestionReasonCodes.PARSER_TIMEOUT,
                      IngestionRetryPolicy.RETRY_WITH_BACKOFF,
                      failureDetail(e)),
                  ledgerEntry(filePath, envelopeForLedger, collection, null, provenance)));
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    } catch (SandboxExtractionException e) {
      log.warn("Extraction sandbox failed for: {} - {}", filePath, e.getMessage());
      FileEnvelope envelopeForLedger = envelope;
      journal.recordOutcomeSafely(
          filePath,
          "SANDBOX_FAILED",
          () ->
              jobQueue.markFailed(
                  filePath,
                  journal.outcome(
                      IngestionOutcomeClass.SANDBOX_FAILED,
                      IngestionReasonCodes.SANDBOX_FAILED,
                      IngestionRetryPolicy.RETRY_WITH_BACKOFF,
                      failureDetail(e)),
                  ledgerEntry(filePath, envelopeForLedger, collection, null, provenance)));
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    } catch (ContentExtractor.ExtractionException e) {
      log.warn("Content extraction failed for: {} - {}", filePath, e.getMessage());
      FileEnvelope envelopeForLedger = envelope;
      journal.recordOutcomeSafely(
          filePath,
          "PARSER_FAILED(terminal)",
          () ->
              jobQueue.markFailed(
                  filePath,
                  journal.outcome(
                      IngestionOutcomeClass.PARSER_FAILED,
                      IngestionReasonCodes.PARSER_FAILED,
                      IngestionRetryPolicy.NONE,
                      failureDetail(e)),
                  ledgerEntry(filePath, envelopeForLedger, collection, null, provenance)));
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    } catch (IOException e) {
      log.error("IO error processing: {}", filePath, e);
      FileEnvelope envelopeForLedger = envelope;
      journal.recordOutcomeSafely(
          filePath,
          "IO_FAILED",
          () ->
              jobQueue.markFailed(
                  filePath,
                  journal.outcome(
                      IngestionOutcomeClass.IO_FAILED,
                      IngestionReasonCodes.IO_ERROR,
                      IngestionRetryPolicy.RETRY_WITH_BACKOFF,
                      failureDetail(e)),
                  ledgerEntry(filePath, envelopeForLedger, collection, null, provenance)));
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    } catch (RuntimeException e) {
      log.error("Failed to process: {}", filePath, e);
      FileEnvelope envelopeForLedger = envelope;
      journal.recordOutcomeSafely(
          filePath,
          "PARSER_FAILED(retryable)",
          () ->
              jobQueue.markFailed(
                  filePath,
                  journal.outcome(
                      IngestionOutcomeClass.PARSER_FAILED,
                      IngestionReasonCodes.PARSER_FAILED,
                      IngestionRetryPolicy.RETRY_WITH_BACKOFF,
                      failureDetail(e)),
                  ledgerEntry(filePath, envelopeForLedger, collection, null, provenance)));
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    }
  }

  /**
   * Tempdoc 885 item 21c — the durable failure reason is the exception's own text.
   *
   * <p>Every catch site above used to store a fixed literal ("I/O failure", "Parser failed") in
   * {@code error_message}, so the only place the actual cause existed was a log line, which is
   * rotated and which no UI or support flow reads. The literal was a restatement of the outcome
   * class the same row already carries; the message is the part that says WHICH file access failed
   * or WHAT the parser choked on. For a sandbox failure the message is also where the child's exit
   * code lives ({@code PersistentExtractionSandbox} formats it into the exception), so 21c's "and
   * the child exit code for SANDBOX_FAILED" needs no separate field.
   *
   * <p>Bounding is {@link io.justsearch.indexerworker.ingest.IngestionOutcome}'s job — its canonical
   * constructor collapses newlines and truncates at 512 chars, so a stack-trace-sized message
   * cannot reach the database from here.
   */
  private static String failureDetail(Throwable e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return e.getClass().getSimpleName() + ": " + message;
  }

  private JobQueue.IngestionLedgerEntry ledgerEntry(Path filePath, String collection, JobQueue.EnqueueProvenance provenance) {
    return LedgerEntryFactory.forPathOnly(filePath, collection, provenance);
  }

  private JobQueue.IngestionLedgerEntry ledgerEntry(
      Path filePath, FileEnvelope envelope, String collection, ValidatedExtractionArtifact artifact,
      JobQueue.EnqueueProvenance provenance) {
    if (envelope == null) return LedgerEntryFactory.forPathOnly(filePath, collection, provenance);
    return LedgerEntryFactory.forEnvelope(
        envelope, collection, artifact, contentExtractor.extractionPolicy(), provenance);
  }

  private void bestEffortDeleteMissingSource(Path filePath) {
    indexedDelta.accept(staleSourceHandler.deleteMissingSource(filePath));
  }

  private Span maybeSpan(String name) {
    if (!detailedTracingSupplier.getAsBoolean()) return Span.getInvalid();
    return TRACER.spanBuilder(name).startSpan();
  }
}
