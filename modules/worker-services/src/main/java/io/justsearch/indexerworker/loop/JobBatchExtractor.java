/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.extract.EngineOwnedPaths;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ContentExtractor.BudgetExceededException;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.SandboxExtractionException;
import io.justsearch.indexerworker.extract.SandboxServiceUnavailableException;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor.ExtractionTimeoutException;
import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.ingest.IngestionServiceUnavailableException;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
  private final BatchServiceFailures identityFailures = new BatchServiceFailures();
  private final BatchServiceFailures extractionFailures = new BatchServiceFailures();

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
    try {
      return extractForBatch(jobs);
    } finally {
      long delay = prepareRetry();
      finishBatch(System.currentTimeMillis() + delay);
    }
  }

  /** The running loop completes both services together after the write phase. */
  List<ExtractedJob> extractForBatch(List<JobQueue.IndexJob> jobs) {
    identityFailures.begin();
    extractionFailures.begin();
    indexEmptyForBatch = indexKnownEmpty();
    List<ExtractedJob> extracted = new ArrayList<>(jobs.size());
    for (JobQueue.IndexJob job : jobs) {
      if (!running.get()) break;
      ExtractedJob ex = extractJob(job);
      if (ex != null) extracted.add(ex);
      // Pace extraction per file while keeping the remaining claims owned by this batch.
      indexingPacing.pace();
    }
    return extracted;
  }

  void useOwnedPaths(EngineOwnedPaths paths) {
    identityFailures.useOwnedPaths(paths);
    extractionFailures.useOwnedPaths(paths);
  }

  long prepareRetry(BooleanSupplier canary) {
    return Math.max(identityFailures.prepareRetry(canary), extractionFailures.prepareRetry(canary));
  }

  long prepareRetry() {
    return Math.max(identityFailures.prepareRetry(), extractionFailures.prepareRetry());
  }

  void finishBatch(long retryAtMs) {
    identityFailures.finish(retryAtMs);
    extractionFailures.finish(retryAtMs);
  }

  long retryDelayMs() {
    return Math.max(identityFailures.retryDelayMs(), extractionFailures.retryDelayMs());
  }

  boolean deferredBatch() {
    return identityFailures.deferredBatch() || extractionFailures.deferredBatch();
  }

  /**
   * Whether the index is known to hold no documents. Task t-20261013-ef3c41: {@code docCount()}
   * answers 0 when the reader cannot be read, which made an unreadable index "empty" and skipped
   * the unchanged-file check for the whole batch — every file re-extracted and rewritten. Only a
   * count that was actually read as 0 makes the index empty; an unreadable one keeps the check.
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // a closed reader throws unchecked
  private boolean indexKnownEmpty() {
    try {
      return indexCountOps.docCountOrThrow() == 0;
    } catch (IOException | RuntimeException unreadable) {
      log.debug("Index count unreadable; keeping the unchanged-file check: {}", unreadable.getMessage());
      return false;
    }
  }

  /** Resets the per-batch index-empty cache. Called from {@code resetForProfiling}. */
  public void resetPerBatchCache() {
    indexEmptyForBatch = false;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private ExtractedJob extractJob(JobQueue.IndexJob claim) {
    Path filePath = claim.path();
    String collection = claim.collection();
    JobQueue.EnqueueProvenance provenance = claim.provenance();
    log.debug("Processing: {}", filePath);

    long startTime = System.currentTimeMillis();
    FileEnvelope envelope = null;
    boolean extracting = false;

    try {
      SourceAdmission admission = ingestionAuthority.admit(claim);
      if (admission.action() == SourceAdmissionAction.STALE_DONE) {
        log.debug("File not found, treating as delete: {}", filePath);
        bestEffortDeleteMissingSource(filePath);
        journal.recordOutcomeSafely(
            filePath,
            "STALE_DONE",
            () -> jobQueue.markClaimDone(claim, admission.outcome(), ledgerEntry(filePath, collection, provenance)));
        batchStats.recordSkipped();
        return null;
      }

      if (admission.action() == SourceAdmissionAction.RETRYABLE_FAILURE) {
        log.warn("File not readable, marking as failed: {}", filePath);
        journal.recordOutcomeSafely(
            filePath,
            admission.outcome().outcomeClass().name(),
            () -> jobQueue.markClaimFailed(claim, admission.outcome(), ledgerEntry(filePath, collection, provenance)));
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
            () -> jobQueue.markClaimDone(claim, admission.outcome(), entry));
        batchStats.recordSkipped();
        return null;
      }
      envelope = admission.envelope();
      String docUid;
      DocumentIdentityStore identityStore = null;
      try {
        // Tempdoc 419 / T5.2 (ADR-0028): record (pathHash, normalizedPath) into the scoped
        // reverse-lookup store so the activity panel can later answer "which file is this hash?".
        pathResolutionStoreSupplier.get().record(
            envelope.pathHash(), envelope.normalizedPath(), envelope.observedAtMs());
        identityStore = documentIdentityStoreSupplier.get();
        docUid = identityStore.resolve(envelope.pathHash(), envelope.observedAtMs()).docUid();
        identityFailures.success();
      } catch (RuntimeException identityError) {
        FileEnvelope admittedEnvelope = envelope;
        Consumer<Boolean> recordFailure = systemic -> {
          IngestionOutcome outcome = journal.outcome(
              IngestionOutcomeClass.WRITE_FAILED,
              IngestionReasonCodes.WRITE_FAILED,
              systemic ? IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT
                  : IngestionRetryPolicy.RETRY_WITH_BACKOFF,
              failureDetail(identityError));
          journal.recordOutcomeSafely(filePath, "WRITE_FAILED(document_identity)", () -> {
            var entry = ledgerEntry(filePath, admittedEnvelope, collection, null, provenance);
            if (systemic) jobQueue.deferClaim(claim, outcome, entry, identityFailures.retryAtMs());
            else jobQueue.markClaimFailed(claim, outcome, entry);
          });
        };
        if (identityStore == DocumentIdentityStore.UNAVAILABLE) {
          // This receiver rejects every document independently of its content.
          identityFailures.unavailable(identityError, recordFailure);
        } else {
          identityFailures.failure(identityError, filePath, recordFailure);
        }
        journal.recordFailedMetric(filePath, null);
        batchStats.recordFailed();
        return null;
      }
      try {
        String normalizedPath = envelope.normalizedPath();
        boolean forceReindex = claim.plannedSourceSha256() != null
            || (claim.walkEpoch() != null
                ? claim.recordedForce() : forcedPaths.remove(normalizedPath));
        // Skip isUnmodified() on empty index — every doc is new (312 item 10).
        if (!forceReindex && !indexEmptyForBatch) {
          if (documentFieldOps.isUnmodified(normalizedPath, envelope.modifiedAtMs())
              && Objects.equals(collection == null || collection.isBlank() ? null : collection,
                  documentFieldOps.getDocumentFieldOrThrow(normalizedPath, SchemaFields.COLLECTION))) {
            log.debug("File unchanged, skipping: {}", filePath);
            FileEnvelope envelopeForLedger = envelope;
            journal.recordOutcomeSafely(
                filePath,
                "UNCHANGED",
                () ->
                    jobQueue.markClaimDone(claim,
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
        extracting = true;
        ExtractionArtifact rawArtifact = contentExtractor.extractArtifact(filePath);
        extracting = false;
        extractionFailures.success();
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
            filePath, envelope, collection, artifact, "after extraction", provenance, claim)) {
          batchStats.recordSkipped();
          return null;
        }
        throw changedDuringExtraction;
      }
      if (!sourceSha256.equals(sourceSha256AfterExtraction)) {
        if (staleResolver.tryHandleStale(
            filePath, envelope, collection, artifact, "after extraction", provenance, claim)) {
          batchStats.recordSkipped();
          return null;
        }
        staleResolver.handleKnownStale(
            filePath,
            envelope,
            collection,
            artifact,
            "during extraction",
            FileFreshnessSnapshot.SourceValidationResult.CONTENT_CHANGED, provenance, claim);
        batchStats.recordSkipped();
        return null;
      }

      if (claim.plannedSourceSha256() != null
          && !claim.plannedSourceSha256().equals(sourceSha256)) {
        if (staleResolver.handleChangedAcceptedSource(
            filePath, envelope, collection, artifact, sourceSha256, provenance, claim)) {
          batchStats.recordSkipped();
          return null;
        }
      }

      if (staleResolver.tryHandleStale(filePath, envelope, collection, artifact, "after extraction", provenance, claim)) {
        batchStats.recordSkipped();
        return null;
      }

      return new ExtractedJob(
          claim, artifact, startTime, envelope, sourceSha256, docUid);

    } catch (IngestionServiceUnavailableException
        | EngineExecutorRejectedException
        | SandboxServiceUnavailableException unavailable) {
      FileEnvelope admittedEnvelope = envelope;
      extractionFailures.unavailable(unavailable, systemic -> journal.recordOutcomeSafely(
          filePath, "WRITE_FAILED(shared_service)", () -> jobQueue.deferClaim(claim,
              journal.outcome(IngestionOutcomeClass.WRITE_FAILED, IngestionReasonCodes.WRITE_FAILED,
                  IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT, failureDetail(unavailable)),
              ledgerEntry(filePath, admittedEnvelope, collection, null, provenance),
              extractionFailures.retryAtMs())));
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    } catch (BudgetExceededException e) {
      log.warn("Extraction budget exceeded for: {} - {}", filePath, e.getMessage());
      FileEnvelope envelopeForLedger = envelope;
      journal.recordOutcomeSafely(
          filePath,
          "BUDGET_EXCEEDED",
          () ->
              jobQueue.markClaimFailed(claim,
                  journal.outcome(
                      IngestionOutcomeClass.BUDGET_EXCEEDED,
                      e.reasonCode(),
                      IngestionRetryPolicy.NONE,
                      failureDetail(e)),
                  ledgerEntry(filePath, envelopeForLedger, collection, null, provenance)));
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    } catch (IOException | ContentExtractor.ExtractionException | RuntimeException | Error e) {
      if (e instanceof VirtualMachineError fatal) throw fatal;
      FileEnvelope admittedEnvelope = envelope;
      IngestionOutcomeClass outcomeClass = extractionFailureClass(e);
      IngestionOutcome original = extractionOutcome(claim, e, outcomeClass);
      Consumer<Boolean> record = systemic -> journal.recordOutcomeSafely(filePath,
          systemic ? "WRITE_FAILED(shared_service)" : outcomeClass.name(), () -> {
            var entry = ledgerEntry(filePath, admittedEnvelope, collection, null, provenance);
            if (systemic) jobQueue.deferClaim(claim,
                journal.outcome(IngestionOutcomeClass.WRITE_FAILED, IngestionReasonCodes.WRITE_FAILED,
                    IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT, failureDetail(e)),
                entry, extractionFailures.retryAtMs());
            else jobQueue.markClaimFailed(claim, original, entry);
          });
      if (extractionFailures.ownsFailure(e, filePath)) extractionFailures.unavailable(e, record);
      else if (extracting && !EngineOwnedPaths.sourceFailure(e, filePath)
          && !(e instanceof IOException io && sourceAccessOutcome(claim, io) != null)) {
        extractionFailures.failure(e, record);
      } else record.accept(false);
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    }
  }

  private static IngestionOutcomeClass extractionFailureClass(Throwable failure) {
    if (failure instanceof IOException) return IngestionOutcomeClass.IO_FAILED;
    if (failure instanceof ExtractionTimeoutException) return IngestionOutcomeClass.PARSER_TIMEOUT;
    if (failure instanceof SandboxExtractionException) return IngestionOutcomeClass.SANDBOX_FAILED;
    return IngestionOutcomeClass.PARSER_FAILED;
  }

  private IngestionOutcome extractionOutcome(JobQueue.IndexJob claim, Throwable failure,
      IngestionOutcomeClass outcomeClass) {
    if (failure instanceof IOException io) {
      IngestionOutcome source = sourceAccessOutcome(claim, io);
      if (source != null) return source;
    }
    return journal.outcome(outcomeClass,
        outcomeClass == IngestionOutcomeClass.IO_FAILED ? IngestionReasonCodes.IO_ERROR : outcomeClass.name(),
        outcomeClass == IngestionOutcomeClass.PARSER_FAILED && failure instanceof ContentExtractor.ExtractionException
            ? IngestionRetryPolicy.NONE : IngestionRetryPolicy.RETRY_WITH_BACKOFF,
        failureDetail(failure));
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

  /**
   * The outcome for an I/O failure caused by access to the source itself, or {@code null} when the
   * source is present and readable (the failure is then the content's, e.g. a damaged archive) or
   * reported not found (the next admission decides with its own confirmation).
   */
  private IngestionOutcome sourceAccessOutcome(JobQueue.IndexJob claim, IOException failure) {
    SourcePresence.Verdict verdict =
        ingestionAuthority.presence(claim.path(), claim.ingestionRoot());
    if (verdict == SourcePresence.Verdict.ROOT_UNAVAILABLE
        || verdict == SourcePresence.Verdict.SOURCE_UNREADABLE) {
      return ingestionAuthority.notConfirmedAbsent(verdict, claim.path(), failure);
    }
    if (verdict == SourcePresence.Verdict.PRESENT && !openableForRead(claim.path())) {
      return ingestionAuthority.sourceUnreadable(claim.path(), failure);
    }
    return null;
  }

  private static boolean openableForRead(Path source) {
    try (var channel = java.nio.file.Files.newByteChannel(source)) {
      return channel.isOpen();
    } catch (IOException | SecurityException | UnsupportedOperationException unreadable) {
      return false;
    }
  }

  private void bestEffortDeleteMissingSource(Path filePath) {
    indexedDelta.accept(staleSourceHandler.deleteMissingSource(filePath));
  }

  private Span maybeSpan(String name) {
    if (!detailedTracingSupplier.getAsBoolean()) return Span.getInvalid();
    return TRACER.spanBuilder(name).startSpan();
  }
}
