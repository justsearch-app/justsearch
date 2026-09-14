/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationAttemptRunner.Reconciliation;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.indexerworker.queue.JobQueue;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Receipt-only recovery part of the Engine's recorded-ingestion coordinator. No admission, producer,
 * permission, timer or terminal writer lives here. The runner applies each returned decision.
 */
final class RecordedIngestionSettlement {
  static final String UNAVAILABLE = "INGEST_UNIT_STATE_UNAVAILABLE";
  static final String EXHAUSTED = "INGEST_RECOVERY_ATTEMPTS_EXHAUSTED";

  private final OperationStore operations;
  private final JobQueue queue;

  RecordedIngestionSettlement(OperationStore operations, JobQueue queue) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.queue = Objects.requireNonNull(queue, "queue");
  }

  /**
   * The outer owner has revoked this child's permission before calling. Enumeration exit is
   * actual producer completion, not cancellation request or a caller's deadline response.
   * Missing evidence may fail only after both producer and process-local queue owners exited.
   */
  Reconciliation reconcile(OperationRecord row, String expectedPlanHash, boolean enumerationExited) {
    Objects.requireNonNull(row, "row");
    Objects.requireNonNull(expectedPlanHash, "expectedPlanHash");
    if (row.state().terminal()) throw new IllegalArgumentException("Cannot reconcile a terminal child");
    if (!enumerationExited || queue.hasIssuedRecordedClaims(row.key())) return new Reconciliation.Wait();
    final JobQueue.SealedWalkReceipt receipt;
    try {
      requirePlan(row.key(), expectedPlanHash);
      queue.trySealRecordedWalk(row.key());
      var sealed = queue.sealedRecordedWalkReceipt(row.key());
      if (sealed.isEmpty()) return new Reconciliation.Wait();
      receipt = sealed.orElseThrow();
      // A derived projection cannot justify decreasing already-confirmed operation progress.
      if (row.unitsCompleted() > receipt.completedUnits() || row.unitsFailed() > receipt.failedUnits()) {
        return failed(UNAVAILABLE);
      }
    } catch (JobQueue.RecordedWalkGapException unavailable) {
      return failed(UNAVAILABLE);
    }
    if (RecordedIngestionReceipt.checkpointMatches(row, receipt)) return terminalDecision(receipt);
    if (row.attempts() >= OperationAttemptRunner.MAX_DURABLE_ATTEMPTS) return failed(EXHAUSTED);
    return new Reconciliation.Resume(handle -> {
      // A decision is a snapshot, never permission to checkpoint a replaced/corrupt projection.
      try {
        requirePlan(row.key(), expectedPlanHash);
        var current = queue.sealedRecordedWalkReceipt(row.key()).orElseThrow(
            () -> new JobQueue.RecordedWalkGapException("Sealed ingestion receipt disappeared"));
        if (!receipt.equals(current)) {
          return OperationExecution.finished(failure(UNAVAILABLE));
        }
      } catch (JobQueue.RecordedWalkGapException unavailable) {
        return OperationExecution.finished(failure(UNAVAILABLE));
      }
      handle.checkpoint(RecordedIngestionReceipt.cursor(receipt), receipt.completedUnits(), receipt.failedUnits());
      return terminalExecution(receipt);
    });
  }

  /** Re-read the durable terminal row before acknowledging the exact immutable queue revision. */
  boolean acknowledge(String key, String expectedPlanHash) {
    var row = operations.find(key).orElseThrow(
        () -> new JobQueue.RecordedWalkGapException("Recorded ingestion operation disappeared"));
    if (!row.state().terminal()) return false;
    requirePlan(key, expectedPlanHash);
    var receipt = queue.sealedRecordedWalkReceipt(key).orElseThrow(
        () -> new JobQueue.RecordedWalkGapException("Terminal ingestion has no sealed receipt"));
    if (!RecordedIngestionReceipt.terminalMatches(row, receipt)) {
      throw new JobQueue.RecordedWalkGapException("Terminal ingestion receipt disagrees with the operation");
    }
    return queue.acknowledgeRecordedWalk(key, receipt.revision());
  }

  private void requirePlan(String key, String expectedPlanHash) {
    var progress = queue.recordedWalk(key).orElseThrow(
        () -> new JobQueue.RecordedWalkGapException("Recorded ingestion progress disappeared"));
    if (!expectedPlanHash.equals(progress.planHash())) {
      throw new JobQueue.RecordedWalkGapException("Recorded ingestion plan disagrees with its child");
    }
  }

  private static Reconciliation terminalDecision(JobQueue.SealedWalkReceipt receipt) {
    var outcome = RecordedIngestionReceipt.terminalReceipt(receipt);
    return switch (RecordedIngestionReceipt.terminalState(receipt)) {
      case COMPLETE -> new Reconciliation.Complete(outcome);
      case FAILED -> new Reconciliation.Failed(outcome);
      case CANCELLED -> new Reconciliation.Cancelled(outcome);
      default -> throw new IllegalStateException("Sealed ingestion has a nonterminal outcome");
    };
  }

  private static Reconciliation failed(String code) {
    return new Reconciliation.Failed(new OperationReceipt(code, null));
  }

  private static OperationExecution terminalExecution(JobQueue.SealedWalkReceipt receipt) {
    var state = RecordedIngestionReceipt.terminalState(receipt);
    if (state == OperationState.CANCELLED) {
      return new OperationExecution(OperationResult.success("Finalizing cancelled ingestion"),
          CompletableFuture.failedFuture(new CancellationException("Recorded enumeration cancelled")));
    }
    return OperationExecution.finished(state == OperationState.COMPLETE
        ? OperationResult.success("Recorded ingestion completed")
        : failure(RecordedIngestionReceipt.terminalReceipt(receipt).code()));
  }

  private static OperationResult failure(String code) {
    return OperationResult.failure("Recorded ingestion could not complete", code, Map.of(), false);
  }
}
