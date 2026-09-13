/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.ipc.SettleIndexRequest;
import io.justsearch.ipc.SettleIndexResponse;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 931 §E item 10 — the writer-level "settle" maintenance RPC for {@link
 * WorkerIngestService}.
 *
 * <p>Purges deleted-but-unmerged documents from the ACTIVE index so two arms of a paired
 * evaluation compare with equal merge state. Two fresh indexes of the same corpus were observed
 * carrying 2,629 vs 222 tombstones; tombstones stay in the collection statistics BM25 scores
 * against, which moved hit counts 3-4% with no code cause.
 *
 * <p>Sibling of {@link MigrationControlOps}: that one owns generation-level maintenance (GC,
 * cutover, rollback), this one owns writer-level maintenance inside one generation.
 */
final class IndexSettleOps {

  private static final Logger log = LoggerFactory.getLogger(IndexSettleOps.class);

  /**
   * Migration states during which the writer must be left alone. Same set {@code
   * WorkerUpgradeQuiescence.blocksUpgrade} refuses on: MIGRATING and SWITCHING move documents
   * between generations, and UNKNOWN means the state file could not be read — fail closed.
   */
  private static final Set<String> BLOCKING_MIGRATION_STATES =
      Set.of("MIGRATING", "SWITCHING", "UNKNOWN");

  private final RunningRuntime ingestLifecycle;
  private final IndexGenerationManager generations;
  private final WorkerUpgradeQuiescence upgradeQuiescence;

  IndexSettleOps(
      RunningRuntime ingestLifecycle,
      IndexGenerationManager generations,
      WorkerUpgradeQuiescence upgradeQuiescence) {
    this.ingestLifecycle = ingestLifecycle;
    this.generations = generations;
    this.upgradeQuiescence = upgradeQuiescence;
  }

  SettleIndexResponse settleIndex(SettleIndexRequest request) {
    String refusal = refusalReason();
    if (refusal != null) {
      log.warn("settleIndex refused: {}", refusal);
      return SettleIndexResponse.newBuilder().setAccepted(false).setError(refusal).build();
    }

    boolean expungeDeletesOnly = request == null || request.getExpungeDeletesOnly();
    int maxSegments = request == null ? 0 : request.getMaxSegments();

    IndexCountOps counts = ingestLifecycle.indexCountOps();
    long maxDocBefore = counts.maxDoc();
    long numDocsBefore = counts.docCount();
    long startNanos = System.nanoTime();
    try {
      ingestLifecycle.indexingCoordinator().settle(expungeDeletesOnly, maxSegments);
      ingestLifecycle.commitOps().commitAndTrack(CommitReason.SETTLE);
      // Without the blocking refresh the searcher still reads the pre-merge segments, so the
      // "after" counts would report the settle as a no-op.
      ingestLifecycle.commitOps().maybeRefreshBlocking();
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? "Failed to settle index" : e.getMessage();
      log.error("settleIndex failed", e);
      return SettleIndexResponse.newBuilder().setAccepted(false).setError(message).build();
    }
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    long maxDocAfter = counts.maxDoc();
    long numDocsAfter = counts.docCount();
    int segmentsAfter = counts.segmentCount();

    log.info(
        "settleIndex accepted: expungeDeletesOnly={} maxDoc {}->{} numDocs {}->{} segments={}"
            + " elapsedMs={}",
        expungeDeletesOnly,
        maxDocBefore,
        maxDocAfter,
        numDocsBefore,
        numDocsAfter,
        segmentsAfter,
        elapsedMs);

    return SettleIndexResponse.newBuilder()
        .setAccepted(true)
        .setError("")
        .setMaxDocBefore(maxDocBefore)
        .setNumDocsBefore(numDocsBefore)
        .setMaxDocAfter(maxDocAfter)
        .setNumDocsAfter(numDocsAfter)
        .setSegmentsAfter(segmentsAfter)
        .setElapsedMs(elapsedMs)
        .build();
  }

  /** Returns the refusal message, or null when the settle may proceed. */
  private String refusalReason() {
    if (ingestLifecycle == null) {
      return "Index runtime not available";
    }
    if (upgradeQuiescence != null && upgradeQuiescence.hasActivePreparation()) {
      return "Upgrade quiescence preparation owns the Worker barrier";
    }
    String migration = migrationState();
    if (BLOCKING_MIGRATION_STATES.contains(migration)) {
      return "Index migration is " + migration;
    }
    return null;
  }

  private String migrationState() {
    if (generations == null) {
      return "IDLE";
    }
    try {
      IndexGenerationManager.State state = generations.readStateBestEffort();
      return state == null || state.migration_state() == null ? "IDLE" : state.migration_state();
    } catch (RuntimeException e) {
      return "UNKNOWN";
    }
  }
}
