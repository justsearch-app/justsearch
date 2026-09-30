/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.OperationHistoryMode;
import io.justsearch.app.api.operations.OperationHistoryRow;
import io.justsearch.app.api.operations.OperationState;
import java.time.Instant;
import java.util.Optional;

/** Shared SQL/live projection of committed outcome metadata; no catalog or preparation reads. */
public final class OperationHistoryProjection {
  private OperationHistoryProjection() {}

  public static OperationHistoryEntry entry(OperationHistoryRow row) {
    boolean success = row.state() == OperationState.COMPLETE;
    OperationOutcome outcome = success
        ? row.historyMode() == OperationHistoryMode.UNDO ? OperationOutcome.UNDONE : OperationOutcome.SUCCESS
        : OperationOutcome.FAILURE;
    String reason = success ? null : row.state() == OperationState.CANCELLED ? "cancelled" : row.failureReason();
    Optional<String> executionId = success && row.historyMode() == OperationHistoryMode.UNDOABLE
        ? Optional.ofNullable(row.receipt()).map(io.justsearch.app.api.operations.OperationReceipt::executionId)
        : Optional.empty();
    var provenance = new InvocationProvenance(TransportTag.valueOf(row.context().transport()),
        ExecutorTag.valueOf(row.executor()), Optional.ofNullable(row.initiator()), row.provenanceOccurredAt(),
        Optional.empty(), Optional.ofNullable(row.correlationId()));
    return new OperationHistoryEntry(new OperationRef(row.operationRef()), "head",
        Instant.ofEpochMilli(row.acceptedAt()), Instant.ofEpochMilli(row.completedAt()), outcome,
        Optional.ofNullable(reason), provenance, executionId, Optional.of(row.key()));
  }
}
