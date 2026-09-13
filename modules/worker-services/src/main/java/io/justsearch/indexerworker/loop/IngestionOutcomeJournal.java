/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.OutcomeWriteException;
import io.justsearch.telemetry.catalog.CounterMetric;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outcome and ledger bookkeeping for the indexing loop's primary path.
 *
 * <p>Tempdoc 516 Slice 4a.1 — extracted from {@link IndexingLoop}. Owns the pending
 * mark-done queue, the partition + drain logic, the canonical outcome factories
 * ({@link #fullSuccess()}, {@link #partialSuccess()}, {@link #skipped(String)},
 * {@link #outcome}), the safe outcome-write wrapper, and the
 * {@code metrics.recordDocumentFailed} fan-out.
 *
 * <p>Slice 4a's Extractor + Writer extractions (4a.2 / 4a.3) will call this directly so
 * they don't straddle the loop's residue. Today (after 4a.1 lands) {@code IndexingLoop}
 * delegates its previous private methods to this collaborator.
 *
 * <p>Not thread-safe — the indexing loop is single-threaded and so is the journal.
 *
 * <p>P5 boundary: a concrete class with named methods. No strategy interface, no plug-in
 * outcome registry. The canonical outcome factories
 * ({@link #fullSuccess()}, {@link #partialSuccess()}, {@link #emptySuccess()},
 * {@link #skipped(String)}, {@link #outcome}) are the closed set; new outcome kinds extend the
 * set explicitly — {@link #emptySuccess()} is one such extension (tempdoc 671, Long-term design
 * part 2).
 */
public final class IngestionOutcomeJournal {

  private static final Logger log = LoggerFactory.getLogger(IngestionOutcomeJournal.class);
  private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("indexing");

  private final JobQueue jobQueue;
  private final OperationalMetrics metrics;
  private final CounterMetric<IngestionOutcomeTags> outcomeWriteFailureCounter;
  private final BooleanSupplier detailedTracingSupplier;
  private final List<JobQueue.IngestionLedgerTransition> pendingMarkDone = new ArrayList<>();

  public IngestionOutcomeJournal(
      JobQueue jobQueue,
      OperationalMetrics metrics,
      CounterMetric<IngestionOutcomeTags> outcomeWriteFailureCounter,
      BooleanSupplier detailedTracingSupplier) {
    this.jobQueue = jobQueue;
    this.metrics = metrics;
    this.outcomeWriteFailureCounter = outcomeWriteFailureCounter;
    this.detailedTracingSupplier = detailedTracingSupplier;
  }

  // ---- transition queue ----

  /** Enqueues a successful write's ledger transition for drain after the next commit. */
  public void enqueueTransition(JobQueue.IngestionLedgerTransition transition) {
    java.util.Objects.requireNonNull(transition.claim(), "Committed transitions require their processing claim");
    pendingMarkDone.add(transition);
  }

  /** Test-only accessor for the pending-transition queue (read-only snapshot). */
  List<JobQueue.IngestionLedgerTransition> pendingTransitionsForTest() {
    return List.copyOf(pendingMarkDone);
  }

  /** Clears all pending transitions without draining. Used by {@code resetForProfiling}. */
  public void clearPending() {
    pendingMarkDone.clear();
  }

  // ---- drain ----

  /**
   * Drains the pending mark-done list after a successful commit.
   *
   * <p>Jobs that were written to Lucene but not yet committed stay in PROCESSING state until
   * this runs, so crash recovery ({@code recoverStuckJobs}) will re-index them — safe because
   * Lucene writes are idempotent.
   *
   * <p>Tempdoc 410 Slice G.1 partitioning preserved: transitions are split by artifact status
   * so the LEDGER outcome class matches the DOCUMENT's EXTRACTION_REASON_CODE field. Pre-G.1
   * the entire batch was hardcoded to SUCCESS_FULL regardless of artifact status, causing the
   * diagnostics endpoint and Search to contradict each other.
   */
  public void drainPending() {
    if (pendingMarkDone.isEmpty()) return;
    Span markDoneSpan = maybeSpan("indexing.markDone");
    markDoneSpan.setAttribute("paths.count", (long) pendingMarkDone.size());
    try {
      List<JobQueue.IngestionLedgerTransition> fullSuccess = new ArrayList<>();
      List<JobQueue.IngestionLedgerTransition> partialSuccess = new ArrayList<>();
      List<JobQueue.IngestionLedgerTransition> emptySuccess = new ArrayList<>();
      for (JobQueue.IngestionLedgerTransition transition : pendingMarkDone) {
        switch (classifyTransition(transition)) {
          case PARTIAL -> partialSuccess.add(transition);
          case EMPTY -> emptySuccess.add(transition);
          case FULL -> fullSuccess.add(transition);
        }
      }
      drainGroup(fullSuccess, fullSuccess());
      drainGroup(partialSuccess, partialSuccess());
      drainGroup(emptySuccess, emptySuccess());
      // Value-equal records can carry different live claims. A stale transition discarded by
      // fallback must not remove the current claim whose outcome write rolled back.
      java.util.Set<JobQueue.IngestionLedgerTransition> drained =
          java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      drained.addAll(fullSuccess);
      drained.addAll(partialSuccess);
      drained.addAll(emptySuccess);
      pendingMarkDone.removeIf(drained::contains);
    } finally {
      markDoneSpan.end();
    }
  }

  /**
   * Drains one outcome-grouped batch. Try a batched {@code markDoneTransitions}; on
   * {@link OutcomeWriteException} fall back to per-path so a single bad row doesn't block the
   * rest. Paths that fail per-path stay in {@code pendingMarkDone} (via the caller's
   * identity removal of the surviving in-list) so the next drain or
   * {@code recoverStuckJobs} on Worker restart can retry.
   */
  private void drainGroup(
      List<JobQueue.IngestionLedgerTransition> group, IngestionOutcome outcome) {
    if (group.isEmpty()) return;
    try {
      jobQueue.markDoneTransitions(group, outcome);
      return;
    } catch (OutcomeWriteException e) {
      log.warn(
          "Batch markDone for outcome {} rolled back, falling back to per-path: {}",
          outcome.outcomeClass(),
          e.getMessage());
    }
    Iterator<JobQueue.IngestionLedgerTransition> it = group.iterator();
    while (it.hasNext()) {
      JobQueue.IngestionLedgerTransition transition = it.next();
      try {
        jobQueue.markClaimDone(transition.claim(), outcome, transition.entry());
      } catch (OutcomeWriteException ex) {
        log.warn(
            "Per-path markDone after commit rolled back; will retry on next drain: {}",
            transition.path(),
            ex);
        it.remove(); // keep this transition in the caller's pendingMarkDone for retry
      }
    }
  }

  private static IngestionSuccessClassifier.Bucket classifyTransition(
      JobQueue.IngestionLedgerTransition transition) {
    String artifactStatus = transition == null || transition.entry() == null
        ? null
        : transition.entry().artifactStatus();
    return IngestionSuccessClassifier.classify(artifactStatus);
  }

  // ---- outcome construction ----

  public IngestionOutcome skipped(String reasonCode) {
    return outcome(
        IngestionOutcomeClass.SKIPPED_POLICY,
        reasonCode,
        IngestionRetryPolicy.NONE,
        reasonCode);
  }

  /**
   * Canonical outcome factory — used by the Extractor + Writer for all
   * non-{@link #fullSuccess()}/{@link #partialSuccess()} outcomes (skipped, failed,
   * deferred). Pure delegate to {@link IngestionOutcome#of}.
   */
  public IngestionOutcome outcome(
      IngestionOutcomeClass outcomeClass,
      String reasonCode,
      IngestionRetryPolicy retryPolicy,
      String diagnosticSummary) {
    return IngestionOutcome.of(outcomeClass, reasonCode, retryPolicy, diagnosticSummary);
  }

  public IngestionOutcome fullSuccess() {
    return outcome(
        IngestionOutcomeClass.SUCCESS_FULL,
        IngestionReasonCodes.SUCCESS,
        IngestionRetryPolicy.NONE,
        "Indexed successfully");
  }

  public IngestionOutcome partialSuccess() {
    return outcome(
        IngestionOutcomeClass.SUCCESS_PARTIAL,
        IngestionReasonCodes.SUCCESS_PARTIAL,
        IngestionRetryPolicy.NONE,
        "Indexed successfully (extracted text was truncated to fit the policy cap)");
  }

  /** Tempdoc 671, Long-term design part 2 — extends the closed factory set (line 39-42 above). */
  public IngestionOutcome emptySuccess() {
    return outcome(
        IngestionOutcomeClass.SUCCESS_EMPTY,
        IngestionReasonCodes.SUCCESS_EMPTY,
        IngestionRetryPolicy.NONE,
        "Indexed successfully (the parser produced no usable content)");
  }

  // ---- record outcomes safely ----

  /**
   * Runs an outcome-aware queue write, swallowing any RuntimeException so a transient SQL
   * failure or a misroute (e.g., the Phase 0.2 defer-policy guard surfacing as
   * {@link IllegalArgumentException}) doesn't crash the batch loop. The path stays in
   * {@code PROCESSING} when the underlying transaction rolls back; {@code recoverStuckJobs}
   * on next Worker startup will requeue it.
   *
   * <p>B-H.4 broadened the catch from {@code OutcomeWriteException} to {@link RuntimeException}
   * because the defer-policy guard in {@code SqliteJobQueue.markFailed} throws
   * {@code IllegalArgumentException} on a misrouted call.
   */
  public void recordOutcomeSafely(Path filePath, String op, Runnable write) {
    try {
      write.run();
    } catch (RuntimeException e) {
      outcomeWriteFailureCounter.increment(IngestionOutcomeTags.ofIndexingLoop());
      log.warn(
          "Failed to record outcome '{}' for {} (queue stays PROCESSING; recoverStuckJobs will requeue): {}",
          op,
          filePath,
          e.getMessage());
    }
  }

  // ---- failed metric ----

  /** Records a failed-document operational metric, classified by file kind. */
  public void recordFailedMetric(Path filePath, String mimeType) {
    String mimeBase = IndexingDocumentOps.normalizeMimeBase(mimeType);
    metrics.recordDocumentFailed(IndexingDocumentOps.classifyFileKind(filePath, mimeBase));
  }

  // ---- helpers ----

  private Span maybeSpan(String name) {
    if (!detailedTracingSupplier.getAsBoolean()) return Span.getInvalid();
    return TRACER.spanBuilder(name).startSpan();
  }
}
