/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ipc.UpgradeQuiescenceResponse;
import java.util.List;

/**
 * Worker-owned upgrade barrier. It stops the indexing loop at a batch boundary, relies on the
 * loop's existing final-commit path, and checkpoints the SQLite WAL before reporting ready.
 */
final class WorkerUpgradeQuiescence {
  private static final long LOOP_DRAIN_TIMEOUT_MS = 10_000;

  private final JobQueue jobQueue;
  private final IndexingLoop indexingLoop;
  private final IndexGenerationManager generations;

  private String preparationId;
  private boolean loopQuiesced;
  private boolean loopWasRunning;
  private boolean queueCheckpointed;

  WorkerUpgradeQuiescence(
      JobQueue jobQueue, IndexingLoop indexingLoop, IndexGenerationManager generations) {
    this.jobQueue = jobQueue;
    this.indexingLoop = indexingLoop;
    this.generations = generations;
  }

  synchronized UpgradeQuiescenceResponse prepare(String requestedId) {
    requireId(requestedId);
    if (preparationId != null && !preparationId.equals(requestedId)) {
      return response(requestedId, List.of("another upgrade preparation owns the Worker barrier"));
    }
    preparationId = requestedId;
    String migration = migrationState();
    if (blocksUpgrade(migration)) {
      return response(requestedId, List.of("index migration is " + migration));
    }
    if (!loopQuiesced) {
      loopWasRunning = loopWasRunning || (indexingLoop != null && indexingLoop.isRunning());
      loopQuiesced = indexingLoop == null || indexingLoop.quiesceForUpgrade(LOOP_DRAIN_TIMEOUT_MS);
    }
    if (!loopQuiesced) {
      return response(requestedId, List.of("indexing loop did not drain at a batch boundary"));
    }
    if (!queueCheckpointed) {
      queueCheckpointed = jobQueue == null || jobQueue.checkpointWal();
    }
    return response(
        requestedId,
        queueCheckpointed ? List.of() : List.of("jobs.db WAL checkpoint is still busy"));
  }

  /**
   * True while a preparation owns the Worker barrier. Tempdoc 931 §E item 10: writer-level
   * maintenance ({@link IndexSettleOps}) must refuse rather than run a force-merge underneath an
   * upgrade that has already been told the Worker is quiesced.
   */
  synchronized boolean hasActivePreparation() {
    return preparationId != null;
  }

  synchronized UpgradeQuiescenceResponse status(String requestedId) {
    requireOwner(requestedId);
    // Status also advances an already-owned preparation. This lets a migration finish or a busy
    // WAL checkpoint clear without requiring the Head to mint or resubmit another preparation.
    return prepare(requestedId);
  }

  synchronized UpgradeQuiescenceResponse cancel(String requestedId) {
    requireOwner(requestedId);
    if (loopQuiesced && loopWasRunning && indexingLoop != null) {
      indexingLoop.resumeAfterUpgradePreparation();
    }
    preparationId = null;
    loopQuiesced = false;
    loopWasRunning = false;
    queueCheckpointed = false;
    return response(requestedId, List.of());
  }

  private UpgradeQuiescenceResponse response(String requestedId, List<String> blockers) {
    return UpgradeQuiescenceResponse.newBuilder()
        .setPreparationId(requestedId)
        .setReady(blockers.isEmpty() && loopQuiesced && queueCheckpointed)
        .setLoopQuiesced(loopQuiesced)
        .setQueueCheckpointed(queueCheckpointed)
        .setMigrationState(migrationState())
        .addAllBlockers(blockers)
        .build();
  }

  private String migrationState() {
    if (generations == null) return "IDLE";
    try {
      IndexGenerationManager.State state = generations.readStateBestEffort();
      return state == null || state.migration_state() == null
          ? "IDLE"
          : state.migration_state();
    } catch (RuntimeException e) {
      return "UNKNOWN";
    }
  }

  private static boolean blocksUpgrade(String migration) {
    return "MIGRATING".equals(migration)
        || "SWITCHING".equals(migration)
        || "UNKNOWN".equals(migration);
  }

  private void requireOwner(String requestedId) {
    requireId(requestedId);
    if (preparationId == null || !preparationId.equals(requestedId)) {
      throw new IllegalArgumentException("preparationId does not own the Worker barrier");
    }
  }

  private static void requireId(String requestedId) {
    if (requestedId == null || requestedId.isBlank()) {
      throw new IllegalArgumentException("preparationId is required");
    }
  }
}
