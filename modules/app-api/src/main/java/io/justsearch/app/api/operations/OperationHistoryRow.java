/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.core.context.EngineContext;
import java.time.Instant;
import java.util.Objects;

/** Narrow committed-history projection. Contains no public identity JSON or private preparation. */
public record OperationHistoryRow(long id, String key, OperationKind kind, String operationRef,
    EngineContext context, String executor, String initiator, String correlationId,
    OperationState state, OperationHistoryMode historyMode, long acceptedAt, long completedAt,
    Instant provenanceOccurredAt, String failureReason, OperationReceipt receipt) {
  public OperationHistoryRow {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(operationRef, "operationRef");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(executor, "executor");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(historyMode, "historyMode");
    Objects.requireNonNull(provenanceOccurredAt, "provenanceOccurredAt");
    if (!state.terminal() || historyMode == OperationHistoryMode.NONE) {
      throw new IllegalArgumentException("History requires a visible committed terminal row");
    }
  }

  /** Live completion uses the identical narrow shape as the durable SELECT. */
  public static OperationHistoryRow from(OperationRecord row) {
    return new OperationHistoryRow(row.id(), row.key(), row.descriptor().kind(), row.descriptor().operationRef(),
        row.context(), row.executor(), row.initiator(), row.correlationId(), row.state(), row.historyMode(),
        row.acceptedAt(), Objects.requireNonNull(row.completedAt(), "completedAt"), row.provenanceOccurredAt(),
        row.failureReason(), row.receipt());
  }
}
