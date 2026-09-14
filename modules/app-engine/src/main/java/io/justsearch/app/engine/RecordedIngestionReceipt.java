/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.indexerworker.queue.JobQueue;
import java.util.Objects;

/**
 * Pure projection of a sealed recorded walk into the operation checkpoint and terminal receipt.
 *
 * <p>This is deliberately package-private and contains no queue, store, authority or effect
 * access. The queue projection remains the source of the compact identity and counts.
 */
final class RecordedIngestionReceipt {
  private static final String CURSOR_PREFIX = "ingest-receipt:";

  private RecordedIngestionReceipt() {}

  /** Returns the compact identity of the exact sealed receipt projection. */
  static String cursor(JobQueue.SealedWalkReceipt receipt) {
    Objects.requireNonNull(receipt, "receipt");
    return CURSOR_PREFIX + receipt.version() + ":" + receipt.revision() + ":" + receipt.sha256();
  }

  /** Returns whether an operation row has the exact receipt identity and historical counts. */
  static boolean checkpointMatches(OperationRecord row, JobQueue.SealedWalkReceipt receipt) {
    Objects.requireNonNull(row, "row");
    Objects.requireNonNull(receipt, "receipt");
    return cursor(receipt).equals(row.checkpointCursor())
        && row.unitsCompleted() == receipt.completedUnits()
        && row.unitsFailed() == receipt.failedUnits();
  }

  /** Derives the durable operation state from current enumeration evidence. */
  static OperationState terminalState(JobQueue.SealedWalkReceipt receipt) {
    Objects.requireNonNull(receipt, "receipt");
    if (receipt.enumerationOutcome() == JobQueue.WalkEnumerationOutcome.CANCELLED) {
      return OperationState.CANCELLED;
    }
    if (receipt.enumerationOutcome() == JobQueue.WalkEnumerationOutcome.FAILED
        || receipt.currentFailedUnits() > 0) {
      return OperationState.FAILED;
    }
    return OperationState.COMPLETE;
  }

  /** Derives the bounded durable outcome code; execution IDs are never synthesized here. */
  static OperationReceipt terminalReceipt(JobQueue.SealedWalkReceipt receipt) {
    Objects.requireNonNull(receipt, "receipt");
    String code;
    if (receipt.enumerationOutcome() == JobQueue.WalkEnumerationOutcome.CANCELLED) {
      code = "cancelled";
    } else if (receipt.enumerationOutcome() == JobQueue.WalkEnumerationOutcome.FAILED) {
      code = "INGEST_ENUMERATION_FAILED";
    } else if (receipt.currentFailedUnits() > 0) {
      code = "INGEST_UNITS_FAILED";
    } else {
      code = "SUCCESS";
    }
    return new OperationReceipt(code, null);
  }

  /** Returns whether both checkpoint identity/counts and terminal state/receipt match exactly. */
  static boolean terminalMatches(OperationRecord row, JobQueue.SealedWalkReceipt receipt) {
    Objects.requireNonNull(row, "row");
    Objects.requireNonNull(receipt, "receipt");
    return row.state() == terminalState(receipt)
        && terminalReceipt(receipt).equals(row.receipt())
        && checkpointMatches(row, receipt);
  }
}
