/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.io.IOException;

/**
 * Lifetime of the Engine's durable acceptance store. The outer composition owns this instance;
 * neither the application nor index half may close it before the index drain has finished.
 */
public interface OperationStore extends AutoCloseable {
  int RECENT_HISTORY_LIMIT = 200;
  java.time.Duration HISTORY_RETENTION = java.time.Duration.ofDays(30);

  /** Latest visible terminal rows, oldest first, bounded to RECENT_HISTORY_LIMIT; no private payload SELECT. */
  java.util.List<OperationHistoryRow> recentHistory(int limit);

  int HISTORY_PROJECTION_BATCH_LIMIT = 256;

  /** Unacknowledged visible completions, oldest completion first; no private payload SELECT. */
  java.util.List<OperationHistoryRow> pendingHistoryProjection(int limit);

  /** Highest accepted row id at capture; bounds startup enumeration, never durable progress. */
  long historyProjectionUpperId();

  /**
   * Pending startup rows with id <= maximumId, after (completedAt,id); id=0 starts the first page.
   * The finite accepted-id cohort remains bounded even when completion clocks move backwards.
   */
  java.util.List<OperationHistoryRow> pendingHistoryProjectionAfter(int limit, long completedAt, long id, long maximumId);

  /**
   * The single history projector calls this only after durable sink acceptance or an explicit
   * ownership exclusion. Key-scoped and idempotent; never changes outcome, identity or timestamps.
   */
  boolean acknowledgeHistoryProjection(String key);

  /** Acceptance commits before this returns. Existing keys compare the entire canonical identity. */
  Acceptance accept(String key, OperationDescriptor descriptor,
      io.justsearch.core.context.EngineContext context,
      io.justsearch.agent.api.registry.InvocationProvenance provenance);

  /** First acceptance freezes history policy; retries neither change it nor compare it as identity. */
  Acceptance accept(String key, OperationDescriptor descriptor,
      io.justsearch.core.context.EngineContext context,
      io.justsearch.agent.api.registry.InvocationProvenance provenance, OperationHistoryMode historyMode);

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

  /** Prepared acceptance freezes the same policy and provenance as ordinary acceptance. */
  Acceptance acceptPrepared(String key, OperationDescriptor descriptor,
      io.justsearch.core.context.EngineContext context,
      io.justsearch.agent.api.registry.InvocationProvenance provenance, java.util.UUID nonce,
      OperationHistoryMode historyMode);

  /** Private owner/recovery read; receipt and history projections never include this value. */
  java.util.Optional<Preparation> acceptedPreparation(long id);

  /**
   * Runner-only derived acceptance. The trusted resolver selected this root from the inspected
   * parent preparation. Compare that exact witness and copy parent attribution atomically;
   * never call the resolver or an operation handler under the store lock.
   */
  default Acceptance acceptIngestChild(String parentKey, String childKey,
      Preparation expectedParentPreparation, RecordedRootPlan oneRootPlan) {
    throw new OperationStoreException(OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED, null);
  }

  /**
   * Exact derived-child observation for receipt repair, including terminal children after restart.
   * Never creates acceptance or authorizes replay. Missing parent or inconsistent stored binding
   * refuses; an existing parent with no matching child returns empty.
   */
  default java.util.Optional<OperationRecord> findIngestChild(String parentKey, RecordedRootPlan oneRootPlan) {
    throw new UnsupportedOperationException("Recorded ingestion child lookup is unavailable");
  }

  record Acceptance(OperationRecord record, boolean created) {}

  /**
   * One consistent observation of a row and the missing-key retention fence. A present row wins.
   * Unknown means no acceptance record and therefore no effect only because every producer must
   * commit acceptance before its first effect. This read never grants permission to execute.
   */
  OperationOutcomeView outcome(String key);

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

  /**
   * Runner-only, one-shot marker before settings preparation. Only a RUNNING settings-apply or
   * reconfigure row with no prior marker may be armed. The column stores the expected revision;
   * committed revision is expected+1. This nonterminal transition emits no completion event.
   */
  boolean armSettingsRevision(long id, long expectedRevision);

  /**
   * Runner-only marker for an accepted installer-generation REINDEX. The runner must first resolve
   * the exact v2 preparation and full settings witness. This separate method cannot widen ordinary
   * settings commitment to arbitrary reindex operations.
   */
  default boolean armInstallerGenerationSettingsRevision(long id, long expectedRevision) {
    return false;
  }

  /** A reconciler has revalidated a recoverable open attempt before scheduling its next body. */
  boolean resume(long id);

  /** Refusal before admission cannot terminalize a running effect; returns its row under the write lock. */
  OperationRecord rejectBeforeStart(long id, OperationReceipt receipt);

  /** A checkpoint describes already committed effects; counts cannot go backwards. */
  boolean checkpoint(long id, String cursor, long unitsCompleted, long unitsFailed);

  /** Runner-only atomic projection of committed bulk progress; bound evidence cannot be replaced. */
  default boolean checkpointBulkReindex(long id, BulkReindexProgress progress) {
    throw new UnsupportedOperationException("Bulk reindex progress is unavailable");
  }

  /** Private recovery observation; full gaps/history are not loaded by ordinary row lookups. */
  default java.util.Optional<BulkReindexProgress> bulkReindexProgress(long id) {
    throw new UnsupportedOperationException("Bulk reindex progress is unavailable");
  }

  /**
   * Re-checkpoint each running durable row's current committed cursor and counts atomically.
   * The store clock stamps the checkpoint; no snapshot can overwrite newer unit progress.
   * An unfinished unit cannot advance this position. Terminal and interactive rows are untouched.
   */
  void checkpointDurableOperations();

  /** Terminal rows are immutable. Returns the committed row snapshot, or empty when the update was refused. */
  java.util.Optional<OperationRecord> finish(long id, OperationState terminalState, OperationReceipt receipt);

  /**
   * Observe newly committed terminal transitions after the store lock is released, including
   * non-dispatched producers and rejection before start. Refused/repeated transitions emit nothing.
   * Listener failure cannot undo completion or prevent other observers. This is a live notification,
   * not durable delivery: a projection must catch up retained rows after subscribing and deduplicate
   * by row identity. Closing removes the subscription; an in-flight notification may still deliver it.
   */
  AutoCloseable subscribeCompletions(java.util.function.Consumer<OperationRecord> listener);

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
