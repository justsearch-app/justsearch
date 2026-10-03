/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntime;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Recovery for a legacy index whose embedding fingerprint was never stamped.
 *
 * <p>When an index is {@code BLOCKED_LEGACY} (documents present, no stored
 * {@code embedding_model_sha256}) the vectors on documents already marked
 * {@code EMBEDDING_STATUS=COMPLETED} — or {@code FAILED} — have unknowable provenance: they may have
 * been written by a different embedding model, and there is no fingerprint to prove otherwise. The
 * only sound recovery is to re-embed them under the current model. This op re-marks those parent
 * documents back to {@code PENDING} so the existing embedding backfill picks them up; the backfill
 * overwrites each stale vector and, once {@code pending==0}, the rebuild certifies and stamps the
 * current fingerprint.
 *
 * <p>Only parent documents carry {@code EMBEDDING_STATUS} (chunks use
 * {@code CHUNK_EMBEDDING_STATUS}), so the term query naturally scopes to parents.
 */
public final class EmbeddingRecoveryOps {
  private EmbeddingRecoveryOps() {}

  private static final String LEGACY_REASON_CODE = "LEGACY_INDEX_NO_FINGERPRINT";

  /**
   * Outcome of a {@link #rescueBlockedLegacyIndex} attempt.
   *
   * @param reMarkedPending parent documents re-marked PENDING for re-embed
   * @param rebuildStarted whether the controller transitioned to REBUILDING
   */
  public record LegacyRescueOutcome(int reMarkedPending, boolean rebuildStarted) {
    static final LegacyRescueOutcome SKIPPED = new LegacyRescueOutcome(0, false);
  }

  /**
   * The whole BLOCKED_LEGACY rescue decision, as one ordered entry point: count the parent docs → re-mark
   * the unknown-provenance COMPLETED/FAILED parents PENDING → commit, refresh and verify coverage → transition to
   * REBUILDING.
   *
   * <p>The ordering is the invariant, and it is why this lives here rather than inline at the call
   * site. The embedding backfill only ever picks up {@code PENDING} documents, and {@code
   * EmbeddingCompatibilityController.checkRebuildCompletion} certifies on {@code
   * pendingEmbeddingCount == 0}. So a rescue that enters REBUILDING <b>without</b> re-marking first
   * finds {@code pending == 0} already true, certifies instantly, re-embeds nothing, and stamps the
   * current fingerprint onto vectors of unknowable provenance — fabricating the very attestation the
   * fingerprint exists to provide. Exposing the sequence as a single entry point means a caller
   * cannot reach the transition without the re-mark having happened.
   *
   * <p>Best-effort by contract: never throws, so worker startup cannot be blocked by recovery.
   *
   * @param ecc the compatibility controller to transition (no-op if not BLOCKED_LEGACY)
   * @param ingestLifecycle the write-side runtime; re-marking needs a {@link RunningRuntime}
   * @param batchSize re-mark batch size
   * @param log the caller's logger, so recovery lines stay attributed to the calling class
   */
  public static LegacyRescueOutcome rescueBlockedLegacyIndex(
      EmbeddingCompatibilityController ecc, LuceneRuntime ingestLifecycle, int batchSize,
      Logger log) {
    try {
      if (ecc == null || ingestLifecycle == null) return LegacyRescueOutcome.SKIPPED;
      if (ecc.state() != EmbeddingCompatibilityController.State.BLOCKED_LEGACY) {
        return LegacyRescueOutcome.SKIPPED;
      }
      if (!LEGACY_REASON_CODE.equals(ecc.reasonCode())) return LegacyRescueOutcome.SKIPPED;

      // docCount() includes chunks, but embedding_status is only on parent docs. Exclude chunks to
      // prevent the heuristic from always failing when chunks exist.
      var countOps = ingestLifecycle.indexCountOps();
      long totalDocs = countOps.docCountOrThrow();
      int chunkDocs = countOps.countByFieldOrThrow(SchemaFields.IS_CHUNK, "true");
      long docs = totalDocs - chunkDocs;
      if (docs <= 0) return LegacyRescueOutcome.SKIPPED;

      // The re-mark needs the write-side coordinator, which only a RunningRuntime exposes — without
      // it, do NOT transition: entering REBUILDING with completed>0 and pending==0 un-re-marked
      // would let the loop certify and stamp provenance-unknown vectors. BLOCKED_LEGACY is the safe
      // state; the next boot with a RunningRuntime recovers.
      if (!(ingestLifecycle instanceof RunningRuntime running)) {
        log.warn(
            "Embedding recovery: BLOCKED_LEGACY index detected but the write-side coordinator is"
                + " unavailable (runtime phase {}); recovery deferred to a boot with a running"
                + " writer",
            ingestLifecycle.getClass().getSimpleName());
        return LegacyRescueOutcome.SKIPPED;
      }

      int remarked =
          remarkEmbeddedParentDocsPending(
              running.documentFieldOps(), running.indexingCoordinator(), batchSize, log);

      // RMW refreshes before writes; only this post-write barrier makes the re-mark visible to
      // normal and shutdown certification. Stay BLOCKED_LEGACY until every old status is gone.
      running.commitOps().commitAndTrack(CommitReason.VDU_RECOVERY);
      running.commitOps().maybeRefreshBlocking();
      int completed = countOps.countByFieldOrThrow(
          SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
      int failed = countOps.countByFieldOrThrow(
          SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_FAILED);
      if (completed != 0 || failed != 0) {
        throw new IllegalStateException("Legacy recovery did not cover every unknown-provenance parent");
      }
      boolean started = ecc.maybeAutoStartRebuildForBlockedLegacy(docs);
      log.warn(
          "Embedding recovery: BLOCKED_LEGACY index with no fingerprint (parentDocs={},"
              + " reMarkedPending={}) -> auto-started rebuild={}. Dense/hybrid retrieval will be"
              + " restored once the backfill re-embeds and the fingerprint is stamped.",
          docs,
          remarked,
          started);
      return new LegacyRescueOutcome(remarked, started);
    } catch (Exception e) {
      // Best-effort: never block worker startup on auto-rebuild recovery, but make the failure
      // visible instead of silently swallowing it (tempdoc 726 F3).
      log.warn("Embedding recovery: BLOCKED_LEGACY auto-rebuild attempt failed (best-effort)", e);
      return LegacyRescueOutcome.SKIPPED;
    }
  }

  /**
   * Re-marks every parent document currently {@code COMPLETED} or {@code FAILED} back to
   * {@code PENDING} — and resets {@code embedding_retry_count} to 0 — so the embedding backfill
   * re-embeds it under the current model with a full retry budget (tempdoc 819 C).
   *
   * <p>The full COMPLETED/FAILED id set is collected FIRST (the set is stable while nothing has been
   * mutated), then re-marked with read-modify-write batch updates (other fields, e.g. content, are
   * preserved). Collect-then-update avoids the NRT-visibility lag that a query-mutate-requery drain
   * would hit — a just-updated document can still appear in the next status query for an iteration,
   * which would otherwise double-count or spin.
   *
   * @return the number of distinct documents re-marked PENDING
   */
  public static int remarkEmbeddedParentDocsPending(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator coordinator,
      int batchSize,
      Logger log) {
    if (documentFieldOps == null || coordinator == null || batchSize <= 0) {
      throw new IllegalArgumentException("Legacy recovery requires readable fields, a writer and positive batch size");
    }
    Set<String> ids = new LinkedHashSet<>();
    ids.addAll(
        collectAllIds(documentFieldOps, SchemaFields.EMBEDDING_STATUS_COMPLETED, batchSize));
    ids.addAll(collectAllIds(documentFieldOps, SchemaFields.EMBEDDING_STATUS_FAILED, batchSize));
    if (ids.isEmpty()) {
      return 0;
    }

    int total = 0;
    List<Map.Entry<String, Map<String, Object>>> batch = new ArrayList<>(batchSize);
    for (String id : ids) {
      // Tempdoc 819 C: reset embedding_retry_count alongside the status. Re-marking status alone
      // left a document rescued from FAILED carrying an exhausted counter, so the backfill's very
      // first re-attempt escalated it straight back to FAILED — one attempt per boot, no matter how
      // many boots. A rescue is a deliberate re-embed request; it earns a full retry budget.
      batch.add(
          Map.entry(
              id,
              Map.of(
                  SchemaFields.EMBEDDING_STATUS,
                  SchemaFields.EMBEDDING_STATUS_PENDING,
                  SchemaFields.EMBEDDING_RETRY_COUNT,
                  "0")));
      if (batch.size() >= batchSize) {
        total += updateExactBatch(coordinator, batch);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      total += updateExactBatch(coordinator, batch);
    }

    if (total > 0 && log != null) {
      log.warn(
          "Embedding recovery: re-marked {} parent document(s) (COMPLETED/FAILED) back to PENDING"
              + " for re-embed under the current model (legacy index had no stored fingerprint)",
          total);
    }
    return total;
  }

  private static int updateExactBatch(
      IndexingCoordinator coordinator, List<Map.Entry<String, Map<String, Object>>> batch) {
    var result = coordinator.updateDocumentsBatch(batch);
    if (result == null || result.notFoundCount() != 0 || result.updatedCount() != batch.size()) {
      throw new IllegalStateException("Legacy recovery re-mark did not update the complete batch");
    }
    return result.updatedCount();
  }

  /**
   * Collects every parent-doc id whose {@code EMBEDDING_STATUS} equals {@code value}. Widens the
   * query limit until the result is smaller than the limit (meaning all matches were returned);
   * safe because no mutation happens during collection, so the match set is stable.
   */
  private static List<String> collectAllIds(
      DocumentFieldOps documentFieldOps, String value, int batchSize) {
    int limit = Math.min(Math.max(batchSize, 1), 1 << 26);
    while (true) {
      List<String> ids =
          documentFieldOps.queryDocIdsByField(SchemaFields.EMBEDDING_STATUS, value, limit);
      if (ids.size() < limit) return ids;
      if (limit >= 1 << 26) {
        throw new IllegalStateException("Legacy recovery enumeration exceeded its coverage limit");
      }
      limit = Math.min(limit << 1, 1 << 26);
    }
  }
}
