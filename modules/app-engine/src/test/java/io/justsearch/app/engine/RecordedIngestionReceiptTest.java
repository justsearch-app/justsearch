/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.indexerworker.queue.JobQueue;
import org.junit.jupiter.api.Test;

final class RecordedIngestionReceiptTest {
  private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Test
  void cursorUsesTheExactCompactIdentityAndStaysBounded() {
    JobQueue.SealedWalkReceipt receipt = receipt(7, 3, 0, 0, JobQueue.WalkEnumerationOutcome.COMPLETE);

    assertEquals("ingest-receipt:1:7:" + HASH, RecordedIngestionReceipt.cursor(receipt));
    assertTrue(RecordedIngestionReceipt.cursor(receipt(Long.MAX_VALUE, 0, 0, 0,
        JobQueue.WalkEnumerationOutcome.COMPLETE)).length() <= 128);
  }

  @Test
  void terminalProjectionDistinguishesCurrentFailuresFromHistoricalFailures() {
    JobQueue.SealedWalkReceipt completeWithHistory =
        receipt(4, 2, 5, 0, JobQueue.WalkEnumerationOutcome.COMPLETE);
    JobQueue.SealedWalkReceipt unitsFailed =
        receipt(4, 2, 5, 2, JobQueue.WalkEnumerationOutcome.COMPLETE);
    JobQueue.SealedWalkReceipt enumerationFailed =
        receipt(4, 2, 5, 0, JobQueue.WalkEnumerationOutcome.FAILED);
    JobQueue.SealedWalkReceipt cancelled =
        receipt(4, 2, 5, 5, JobQueue.WalkEnumerationOutcome.CANCELLED);

    assertEquals(OperationState.COMPLETE, RecordedIngestionReceipt.terminalState(completeWithHistory));
    assertEquals(new OperationReceipt("SUCCESS", null),
        RecordedIngestionReceipt.terminalReceipt(completeWithHistory));
    assertEquals(OperationState.FAILED, RecordedIngestionReceipt.terminalState(unitsFailed));
    assertEquals(new OperationReceipt("INGEST_UNITS_FAILED", null),
        RecordedIngestionReceipt.terminalReceipt(unitsFailed));
    assertEquals(OperationState.FAILED, RecordedIngestionReceipt.terminalState(enumerationFailed));
    assertEquals(new OperationReceipt("INGEST_ENUMERATION_FAILED", null),
        RecordedIngestionReceipt.terminalReceipt(enumerationFailed));
    assertEquals(OperationState.CANCELLED, RecordedIngestionReceipt.terminalState(cancelled));
    assertEquals(new OperationReceipt("cancelled", null),
        RecordedIngestionReceipt.terminalReceipt(cancelled));
  }

  @Test
  void checkpointRequiresExactCursorAndHistoricalCounts() {
    JobQueue.SealedWalkReceipt receipt =
        receipt(11, 6, 3, 0, JobQueue.WalkEnumerationOutcome.COMPLETE);
    OperationRecord matching = row(OperationState.COMPLETE, RecordedIngestionReceipt.cursor(receipt),
        receipt.completedUnits(), receipt.failedUnits(), new OperationReceipt("SUCCESS", null));

    assertTrue(RecordedIngestionReceipt.checkpointMatches(matching, receipt));
    assertFalse(RecordedIngestionReceipt.checkpointMatches(
        row(matching.state(), "ingest-receipt:1:5:" + HASH, matching.unitsCompleted(), matching.unitsFailed(), matching.receipt()),
        receipt));
    assertFalse(RecordedIngestionReceipt.checkpointMatches(
        row(matching.state(), matching.checkpointCursor(), matching.unitsCompleted() + 1, matching.unitsFailed(), matching.receipt()),
        receipt));
    assertFalse(RecordedIngestionReceipt.checkpointMatches(
        row(matching.state(), matching.checkpointCursor(), matching.unitsCompleted(), matching.unitsFailed() + 1, matching.receipt()),
        receipt));
  }

  @Test
  void terminalMatchRejectsIdentityStateReceiptAndExecutionMismatches() {
    JobQueue.SealedWalkReceipt receipt =
        receipt(8, 2, 0, 0, JobQueue.WalkEnumerationOutcome.COMPLETE);
    String cursor = RecordedIngestionReceipt.cursor(receipt);
    OperationRecord matching = row(OperationState.COMPLETE, cursor, 2, 0,
        new OperationReceipt("SUCCESS", null));

    assertTrue(RecordedIngestionReceipt.terminalMatches(matching, receipt));
    assertFalse(RecordedIngestionReceipt.terminalMatches(
        row(OperationState.FAILED, cursor, 2, 0, matching.receipt()), receipt));
    assertFalse(RecordedIngestionReceipt.terminalMatches(
        row(OperationState.COMPLETE, cursor, 2, 0, new OperationReceipt("OTHER", null)), receipt));
    assertFalse(RecordedIngestionReceipt.terminalMatches(
        row(OperationState.COMPLETE, cursor, 2, 0, new OperationReceipt("SUCCESS", "late-execution")), receipt));
    assertFalse(RecordedIngestionReceipt.terminalMatches(
        row(OperationState.COMPLETE, "ingest-receipt:1:9:" + HASH, 2, 0, matching.receipt()), receipt));
  }

  private static JobQueue.SealedWalkReceipt receipt(long revision, long completed, long failed,
      long currentFailed, JobQueue.WalkEnumerationOutcome outcome) {
    return new JobQueue.SealedWalkReceipt(1, revision, HASH, completed, failed, currentFailed, outcome);
  }

  private static OperationRecord row(OperationState state, String cursor, long completed, long failed,
      OperationReceipt receipt) {
    return new OperationRecord(7L, "operation", null, null, "INGEST", "initiator", "correlation",
        state, "INDEXING", cursor, completed, failed, 1, 0L, null, 0L, null, null, receipt, null, null, null);
  }
}
