/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import java.util.List;

/**
 * Extension of {@link JobQueue} that supports a durable write-behind buffer for migration
 * SWITCHING state.
 *
 * <p>During index migration cutover, mutation operations (submit, delete, sync) are durably
 * buffered in the switch buffer instead of being applied to the active queue. After cutover
 * completes, the buffered operations are replayed against the new index generation.
 *
 * <p>Implementations: {@link SqliteJobQueue}.
 */
public interface SwitchBufferCapableQueue extends JobQueue {

  /**
   * A buffered operation stored during migration or cutover.
   *
   * <p>{@code generation} is empty for the historical, unscoped switch buffer. New migration
   * journal admissions must carry the building generation so that two candidates can retain the
   * same logical key without replacing one another.
   */
  record SwitchBufferOp(
      String generation,
      String key,
      String op,
      String payload,
      long lastUpdatedMs,
      String revision) {

    /** Compatibility constructor for the pre-generation switch buffer API. */
    public SwitchBufferOp(String key, String op, String payload, long lastUpdatedMs, String revision) {
      this("", key, op, payload, lastUpdatedMs, revision);
    }
  }

  /**
   * Inserts or replaces an operation in the durable switch buffer.
   *
   * <p><b>IMPORTANT:</b> This method is fail-closed. If the write fails, it returns {@code false}
   * and the caller MUST NOT acknowledge the operation as successful.
   *
   * @param key unique key for the operation (used for dedup/replace)
   * @param op operation type identifier
   * @param payload serialized operation payload
   * @return true if the write succeeded
   */
  boolean putSwitchBuffer(String key, String op, String payload);

  /**
   * Inserts or replaces an operation scoped to one candidate generation.
   *
   * <p>Implementations that do not support generation-scoped journalling fail closed rather than
   * silently falling back to the legacy unscoped key space.
   */
  default boolean putSwitchBufferForGeneration(
      String generation, String key, String op, String payload) {
    throw new UnsupportedOperationException("Generation-scoped switch-buffer writes are unavailable");
  }

  /** Source revision is distinct from the random replacement token used for conditional cleanup. */
  enum ProjectionAdmission { ACCEPTED, DUPLICATE, STALE, CONFLICT }

  /** Atomically retains the latest accepted no-file projection or delete for one candidate. */
  default ProjectionAdmission admitProjectionForGeneration(String generation, String payload) {
    throw new UnsupportedOperationException("Generation-scoped projection admission is unavailable");
  }

  /**
   * Atomically accepts one file job and its generation-scoped UPSERT journal row.
   *
   * <p>The queue owns both writes so a caller never acknowledges a file whose replay obligation
   * was not committed with the job admission. Implementations without this transaction refuse
   * rather than falling back to two independent writes.
   */
  default boolean enqueueAndBufferFileForGeneration(
      String generation, EnqueueEntry entry, String collection, String scanId) {
    throw new UnsupportedOperationException(
        "Atomic generation-scoped file admission is unavailable");
  }

  /** All-or-nothing ordinary batch admission and candidate replay obligation. */
  default int enqueueAndBufferFilesForGeneration(
      String generation, List<EnqueueEntry> entries, String collection, String scanId) {
    throw new UnsupportedOperationException(
        "Atomic generation-scoped batch admission is unavailable");
  }

  /**
   * Covers a native migration enumeration batch atomically. An earlier accepted foreground
   * mutation for the same candidate path wins over this baseline scan; the covered count includes
   * those superseded scan entries so enumeration completeness is still accountable.
   */
  default int enqueueEnumeratedFilesForGeneration(String generation, List<EnqueueEntry> entries) {
    throw new UnsupportedOperationException(
        "Atomic migration enumeration admission is unavailable");
  }

  /** Atomic generation-scoped file admission without recorded scan membership. */
  default boolean enqueueAndBufferFileForGeneration(
      String generation, EnqueueEntry entry, String collection) {
    return enqueueAndBufferFileForGeneration(generation, entry, collection, null);
  }

  /** Atomically admits a finite recorded walk and its candidate-scoped file UPSERTs. */
  default int enqueueRecordedEntriesAndBufferForGeneration(
      String generation, String operationKey, long epoch,
      List<EnqueueEntry> entries, String collection) {
    throw new UnsupportedOperationException(
        "Atomic generation-scoped recorded admission is unavailable");
  }

  /**
   * Replaces an issued streaming recorded claim's stale source witness and candidate UPSERT in
   * one transaction. Returns false when the claim is not an eligible candidate member. Storage
   * failure throws so the caller cannot acknowledge a partial replacement.
   */
  default boolean supersedeStreamingRecordedSource(
      IndexJob claim, String observedSha256, IngestionOutcome staleOutcome,
      IngestionLedgerEntry entry) {
    return false;
  }

  /** Exact settled queue admission behind one versioned file projection obligation. */
  default boolean matchesAcceptedFileProjection(
      String path, String unitRevision, String sourceSha256) {
    throw new UnsupportedOperationException(
        "Exact accepted file projection evidence is unavailable");
  }

  /** Atomically buffers sync; maintenance preserves an earlier admission's attribution. */
  boolean putSyncRoot(String key, SwitchBufferSyncRoot payload);

  /** Generation-scoped form of {@link #putSyncRoot(String, SwitchBufferSyncRoot)}. */
  default boolean putSyncRootForGeneration(
      String generation, String key, SwitchBufferSyncRoot payload) {
    throw new UnsupportedOperationException(
        "Generation-scoped sync-root buffering is unavailable");
  }

  /** Returns the number of buffered ops currently in the durable switch buffer. */
  long switchBufferDepth();

  /** Returns all buffered ops, sorted by last_updated ascending (best-effort). */
  List<SwitchBufferOp> listSwitchBufferOps();

  /** Final cutover requires an exact read; implementations without one refuse promotion. */
  default List<SwitchBufferOp> listSwitchBufferOpsStrict() {
    throw new UnsupportedOperationException("Strict switch-buffer listing is unavailable");
  }

  /** Exact listing for one candidate generation; unreadable storage must fail closed. */
  default List<SwitchBufferOp> listSwitchBufferOpsStrictForGeneration(String generation) {
    throw new UnsupportedOperationException(
        "Generation-scoped strict switch-buffer listing is unavailable");
  }

  /**
   * Removes only unchanged versions from a successfully committed replay snapshot.
   * Concurrent insertions/replacements survive, including identical payloads/timestamps.
   * Storage failure throws; no caller may substitute an unconditional table clear.
   */
  int removeReplayedSwitchBufferOps(List<SwitchBufferOp> replayed);

  /**
   * Removes only unchanged versions from one generation's replay snapshot.
   *
   * <p>The implementation must include generation, key and revision in each conditional delete.
   */
  default int removeReplayedSwitchBufferOpsForGeneration(
      String generation, List<SwitchBufferOp> replayed) {
    if (generation == null || generation.isBlank()) {
      throw new IllegalArgumentException("Generation must be non-blank");
    }
    for (SwitchBufferOp entry : replayed) {
      if (!generation.equals(entry.generation())) {
        throw new IllegalArgumentException("Replay entry belongs to a different generation");
      }
    }
    return removeReplayedSwitchBufferOps(replayed);
  }
}
