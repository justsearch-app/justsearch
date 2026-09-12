/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.io.IOException;

/**
 * Lifetime of the Engine's durable acceptance store. The outer composition owns this instance;
 * neither the application nor index half may close it before the index drain has finished.
 */
public interface OperationStore extends AutoCloseable {
  /** Acceptance commits before this returns. Existing keys compare the entire canonical identity. */
  Acceptance accept(String key, OperationDescriptor descriptor,
      io.justsearch.core.context.EngineContext context,
      io.justsearch.agent.api.registry.InvocationProvenance provenance);

  record Acceptance(OperationRecord record, boolean created) {}

  /** Row-first lookup: callers compare a missing key's timestamp with historySinceMillis(). */
  java.util.Optional<OperationRecord> find(String key);

  /** Only ACCEPTED can start; repeated starts cannot execute another body. */
  boolean start(long id);

  /** A reconciler has revalidated a recoverable open attempt before scheduling its next body. */
  boolean resume(long id);

  /** Refusal before admission cannot terminalize an already-running effect. */
  boolean rejectBeforeStart(long id, OperationReceipt receipt);

  /** A checkpoint describes already committed effects; counts cannot go backwards. */
  boolean checkpoint(long id, String cursor, long unitsCompleted, long unitsFailed);

  /** Terminal rows are immutable. The runner is the sole producer of this transition. */
  boolean finish(long id, OperationState terminalState, OperationReceipt receipt);

  /** Open rows for owner-scoped reconciliation, in acceptance order. */
  java.util.List<OperationRecord> openRecords();

  long historySinceMillis();

  /** Recovery performed by this open, including completion of an interrupted quarantine. */
  java.util.Optional<Recovery> recovery();

  record Recovery(java.nio.file.Path preservedDirectory, long historySinceMillis) {}

  @Override
  void close() throws IOException;
}
