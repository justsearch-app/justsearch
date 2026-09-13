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

  /** Opaque frozen value; its nonce binds approval to this exact preparation, not to a later replacement. */
  record Preparation(java.util.UUID nonce, OperationPreparedPayload payload) {
    public Preparation {
      java.util.Objects.requireNonNull(nonce, "nonce");
      java.util.Objects.requireNonNull(payload, "payload");
    }
  }

  /** Pending preparation is not an accepted operation or a history outcome. */
  java.util.Optional<Preparation> pendingPreparation(String key, OperationDescriptor descriptor);

  /**
   * Preserve the first unexpired preparation. Empty means a matching operation is already accepted;
   * changed public identity always conflicts. The store never invokes a handler inside its lock.
   */
  java.util.Optional<Preparation> savePreparation(String key, OperationDescriptor descriptor, Preparation preparation);

  /** Atomically copy the exact unexpired pending payload into acceptance and delete its pending row. */
  Acceptance acceptPrepared(String key, OperationDescriptor descriptor,
      io.justsearch.core.context.EngineContext context,
      io.justsearch.agent.api.registry.InvocationProvenance provenance, java.util.UUID nonce);

  /** Private owner/recovery read; receipt and history projections never include this value. */
  java.util.Optional<Preparation> acceptedPreparation(long id);

  record Acceptance(OperationRecord record, boolean created) {}

  /** Row-first lookup: callers compare a missing key's timestamp with historySinceMillis(). */
  java.util.Optional<OperationRecord> find(String key);

  /**
   * Read before preparation: validates the key and compares public identity before returning a row.
   * A missing retained-window key returns empty; expired/future/invalid keys refuse without writing.
   * This is not authority to execute: acceptance repeats the comparison under its transaction.
   */
  java.util.Optional<OperationRecord> lookup(String key, OperationDescriptor descriptor);

  /** Only ACCEPTED can start; repeated starts cannot execute another body. */
  boolean start(long id);

  /** A reconciler has revalidated a recoverable open attempt before scheduling its next body. */
  boolean resume(long id);

  /** Refusal before admission cannot terminalize a running effect; returns its row under the write lock. */
  OperationRecord rejectBeforeStart(long id, OperationReceipt receipt);

  /** A checkpoint describes already committed effects; counts cannot go backwards. */
  boolean checkpoint(long id, String cursor, long unitsCompleted, long unitsFailed);

  /** Terminal rows are immutable. Returns the committed row snapshot, or empty when the update was refused. */
  java.util.Optional<OperationRecord> finish(long id, OperationState terminalState, OperationReceipt receipt);

  /** Open rows for owner-scoped reconciliation, in acceptance order. */
  java.util.List<OperationRecord> openRecords();

  long historySinceMillis();

  /** Atomically evict aged/over-cap terminal rows and advance the missing-key history fence. */
  void pruneHistory();

  /** Recovery performed by this open, including completion of an interrupted quarantine. */
  java.util.Optional<Recovery> recovery();

  record Recovery(java.nio.file.Path preservedDirectory, long historySinceMillis) {}

  @Override
  void close() throws IOException;
}
