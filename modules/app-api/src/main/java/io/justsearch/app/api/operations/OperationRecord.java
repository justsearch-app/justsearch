/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.core.context.EngineContext;

/**
 * Immutable internal projection of the one operations row; HTTP exposes the narrower outcome.
 * updatedAt includes periodic same-position checkpoints. It does not imply another completed unit,
 * less replay work, or a stronger durability guarantee.
 */
public record OperationRecord(
    long id, String key, OperationDescriptor descriptor, EngineContext context,
    String executor, String initiator, String correlationId, OperationState state, String phase,
    String checkpointCursor, long unitsCompleted, long unitsFailed, int attempts,
    long acceptedAt, Long startedAt, long updatedAt, Long completedAt,
    String failureReason, OperationReceipt receipt, OperationHistoryMode historyMode,
    java.time.Instant provenanceOccurredAt, Long expectedSettingsRevision) {}
