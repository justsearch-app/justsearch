/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.adapters.lucene.runtime.PathPrefixScope;

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
      String revision,
      long acceptedOrder) {

    public SwitchBufferOp(String generation, String key, String op, String payload,
        long lastUpdatedMs, String revision) {
      this(generation, key, op, payload, lastUpdatedMs, revision, 0);
    }

    /** Release retirement requires the actual SQL order and exact replacement UUID. */
    public String retirementUuid() {
      if (acceptedOrder <= 0 || revision == null
          || !revision.replace("-", "").matches("[0-9a-f]{32}")) {
        throw new IllegalArgumentException("Incomplete ordered retirement receipt");
      }
      return revision.replace("-", "");
    }

    public String retirementRootUnit() {
      if (!"SYNC_ROOT".equals(op)) throw new IllegalStateException("Not a ROOT receipt");
      return "root1:" + retirementUuid() + ":" + acceptedOrder + ":"
          + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /** Compatibility constructor for the pre-generation switch buffer API. */
    public SwitchBufferOp(String key, String op, String payload, long lastUpdatedMs, String revision) {
      this("", key, op, payload, lastUpdatedMs, revision);
    }
  }

  /** The opaque root1 unit keeps its original receipt position across later walks and restarts. */
  record RootOrigin(String uuid, long acceptedOrder) {
    public static RootOrigin fromUnit(String unit) {
      if (unit == null || !unit.startsWith("root1:")) return null;
      String[] parts = unit.split(":", -1);
      if (parts.length != 4 || !parts[1].matches("[0-9a-f]{32}")
          || !parts[3].matches("[0-9a-f]{32}") || !parts[2].matches("[1-9][0-9]{0,18}")) {
        throw new IllegalArgumentException("Invalid ROOT unit identity");
      }
      long order = Long.parseLong(parts[2]);
      return new RootOrigin(parts[1], order);
    }
  }

  default SwitchBufferOp retirementRootReceipt(String generation, String key) {
    throw new UnsupportedOperationException("Ordered ROOT receipt unavailable");
  }

  default void retainRetirementRootCollection(SwitchBufferOp root,String collection) {
    throw new UnsupportedOperationException("ROOT collection evidence unavailable");
  }
  default String retirementRootCollection(SwitchBufferOp root) { return null; }

  default int enqueueRetirementRootEntries(SwitchBufferOp root, List<EnqueueEntry> entries,
      String collection, boolean maintenance, boolean replay,
      java.util.function.BooleanSupplier cancelled) {
    throw new UnsupportedOperationException("Receipt-owned ROOT admissions unavailable");
  }

  /** Exact candidate whose retained release-recovery journal still orders new admissions. */
  default String release030RetirementGeneration() { return null; }

  /** Live retirement sync refreshes only retained exact receipts in the job admission transaction. */
  default int enqueueRelease030LiveSyncEntries(List<EnqueueEntry> entries, String collection,
      java.util.function.BooleanSupplier cancelled) {
    return enqueueRelease030LiveSyncEntries(entries, collection, false, cancelled);
  }

  /** Policy-preserving form: maintenance may cover an existing waiting row without replacing it. */
  default int enqueueRelease030LiveSyncEntries(List<EnqueueEntry> entries, String collection,
      boolean maintenance, java.util.function.BooleanSupplier cancelled) {
    throw new UnsupportedOperationException("Atomic retirement sync admissions are unavailable");
  }

  /** Full expected row, including absence, used only by fenced watcher reconciliation. */
  default java.util.Map<String, String> watcherReconciliationRow(String path) {
    throw new UnsupportedOperationException("Watcher reconciliation row evidence unavailable");
  }

  /**
   * One conditional file transition. Caller holds the actual writer file fence. The queue
   * reserves the write transaction, rechecks journal/row/ownership, then calls fresh proof.
   */
  default boolean reconcileWatcherFile(WatcherReconciliationFile file, Runnable freshProof,
      Runnable removeIndex) {
    throw new UnsupportedOperationException("Conditional watcher reconciliation unavailable");
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
  default int enqueueEnumeratedFilesForGeneration(
      String generation, List<EnqueueEntry> entries, String collection) {
    throw new UnsupportedOperationException("Atomic migration enumeration admission is unavailable");
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

  /** A captured finite plan may retain H1 while its exact terminal ledger proves indexed H2. */
  default boolean matchesAcceptedFileProjection(
      String path, String unitRevision, String sourceSha256, String indexedSourceSha256) {
    return sourceSha256 != null && sourceSha256.equals(indexedSourceSha256)
        && matchesAcceptedFileProjection(path, unitRevision, sourceSha256);
  }

  /** What the path's own queue row says about an accepted file admission on a generation. */
  enum SourceFileWork {
    /**
     * The writer of this admission, or of a later one that replaced it, committed exactly the
     * bytes the generation holds ({@code indexedSourceSha256}).
     */
    COMMITTED,
    /**
     * The row is pending (a fresh admission, or a retry that is due) or claimed: its writer will
     * judge the file as it is when it runs. It is no effect until that writer commits.
     */
    OUTSTANDING,
    /**
     * The row's writer failed, terminally or with a retry waiting out a back-off. That outcome is
     * for the file state the writer ran on and is no effect; nothing is in flight.
     */
    FAILED,
    /**
     * The row's last writer ended it by the generation's normal policy for a source that is not
     * indexable as it was then: not a regular file, excluded, or too large. That handling is the
     * generation's committed effect for the file in that state.
     */
    POLICY,
    /** No row, or a finished row that wrote nothing the generation holds. */
    NONE
  }

  /**
   * The path's row judged against the generation whose committed source witness is {@code
   * indexedSourceSha256}. A finished row alone never proves an effect: a retired, refused or
   * skipped row wrote nothing.
   */
  default SourceFileWork sourceFileWork(String path, String indexedSourceSha256) {
    throw new UnsupportedOperationException("Queue admission ownership evidence is unavailable");
  }

  /**
   * Refusal cleanup only: admits {@code entry} to the source as the job whose unit revision is
   * {@code admission}, the name refusal cleanup gives one file state of the path. Returns 1 when
   * accepted. The queue row is the durable record of that admission.
   */
  default int enqueueRefusalCleanupAdmission(
      JobQueue.EnqueueEntry entry, String collection, String admission) {
    return enqueueEntries(List.of(entry), collection);
  }

  /**
   * Refusal cleanup only: whether {@code path}'s queue row is still the admission named {@code
   * admission}. A restart keeps the answer, so it keeps the same job and its retry deadline.
   */
  default boolean holdsRefusalCleanupAdmission(String path, String admission) {
    return false;
  }

  /**
   * Exact captured-plan evidence for a file already removed by a later broad replay receipt.
   * This is read-only: the retained sealed walk row must remain available for its owner to
   * acknowledge after the final pointer certificate.
   */
  default boolean matchesAcceptedCapturedFileDeletion(
      String path, String unitRevision, String plannedSourceSha256) {
    throw new UnsupportedOperationException(
        "Exact captured file deletion evidence is unavailable");
  }

  /** Atomically buffers sync; maintenance preserves an earlier admission's attribution. */
  boolean putSyncRoot(String key, SwitchBufferSyncRoot payload);

  /** Generation-scoped form of {@link #putSyncRoot(String, SwitchBufferSyncRoot)}. */
  default boolean putSyncRootForGeneration(
      String generation, String key, SwitchBufferSyncRoot payload) {
    throw new UnsupportedOperationException(
        "Generation-scoped sync-root buffering is unavailable");
  }

  /** Durably freezes legacy prefix scopes before any reader may apply them. */
  default void scopeLegacyPrefixReceipts(
      java.util.function.Function<String, PathPrefixScope> scope) {
    if (listSwitchBufferOpsStrict().stream().anyMatch(op -> "DELETE_PREFIX".equals(op.op())
        && PathPrefixScope.isLegacy(op.payload()))) {
      throw new UnsupportedOperationException("Legacy prefix receipt conversion is unavailable");
    }
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
