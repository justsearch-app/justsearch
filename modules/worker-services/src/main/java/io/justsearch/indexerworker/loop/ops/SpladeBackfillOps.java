/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * SPLADE sparse encoding backfill for documents with {@code splade_status=PENDING}.
 *
 * <p>Runs during idle time and interleaved with primary indexing to encode documents that were
 * indexed before SPLADE was available, or that were deferred to backfill for throughput (tempdoc
 * 278). Follows the same pattern as {@link NerBackfillOps}.
 *
 * <p>GPU/CPU session selection is handled internally by {@link SpladeEncoder}'s session handle
 * (via {@code SessionHandle.acquire()}), which falls back to CPU when the main process is using
 * the GPU.
 */
public final class SpladeBackfillOps {
  private SpladeBackfillOps() {}

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      IndexingPacing pacing,
      Supplier<SpladeEncoder> spladeEncoderSupplier,
      BooleanSupplier runningSupplier,
      int batchSize,
      boolean commitAfterBatch,
      // Tempdoc 712/931: chunk docs carry splade_status=PENDING from creation whatever this flag
      // says, so without it here this lane encodes chunk sparse data the flag says not to produce.
      boolean chunkSpladeEnabled,
      Logger log) {}

  /**
   * Processes a batch of SPLADE backfill documents.
   *
   * @return outcome whose {@code success()} preserves the original "not a systemic failure"
   *     signal ({@code true} on success or partial success, {@code false} when the entire batch
   *     failed systemically e.g. GPU OOM — the caller backs off on consecutive {@code false}); the
   *     record also carries docsProcessed/elapsedMs for {@link BackfillScheduler}'s per-stage
   *     metrics recording (tempdoc 710 Move 2 item 4).
   */
  public static StageOutcome processSpladeBackfill(BackfillContext context) {
    long t0 = System.nanoTime();
    try {
      List<String> pendingIds =
          context.chunkSpladeEnabled()
              ? context
                  .documentFieldOps()
                  .queryDocIdsByField(
                      SchemaFields.SPLADE_STATUS,
                      SchemaFields.SPLADE_STATUS_PENDING,
                      context.batchSize())
              : context
                  .documentFieldOps()
                  .queryNonChunkDocIdsByField(
                      SchemaFields.SPLADE_STATUS,
                      SchemaFields.SPLADE_STATUS_PENDING,
                      context.batchSize());
      long queryMs = (System.nanoTime() - t0) / 1_000_000;

      if (pendingIds.isEmpty()) {
        return StageOutcome.none();
      }

      SpladeEncoder encoder = context.spladeEncoderSupplier().get();
      if (encoder == null) {
        context.log().debug("SPLADE backfill: encoder unavailable, stopping batch");
        return StageOutcome.none();
      }

      context.log().info("Processing SPLADE backfill for {} documents", pendingIds.size());
      int processed = 0;
      int failed = 0;
      int markedFailed = 0;

      // Check for interruption before batch work
      if (paceAndCheckInterrupt(context)) {
        return StageOutcome.elapsedSince(t0);
      }

      // Phase 1: collect parent content or reconstructed chunk slices in one batch.
      long t1 = System.nanoTime();
      Map<String, String> contentByDocId =
          context.documentFieldOps().getDocumentContentBatch(pendingIds);
      List<String> batchDocIds = new ArrayList<>(pendingIds.size());
      List<String> batchContents = new ArrayList<>(pendingIds.size());
      for (String docId : pendingIds) {
        try {
          String content = contentByDocId.get(docId);

          if (content == null || content.isBlank()) {
            // No content means no postings were produced. Marking COMPLETED here claims a splade
            // field that will never exist — a data-less COMPLETED (the F-032 "status lies" class)
            // that the reset-status RMW policy (tempdoc 711/717) sends straight back to PENDING,
            // forever. Escalate through the retry-count seam instead, mirroring
            // EmbeddingBackfillOps' handling of the identical condition.
            context.log().warn("SPLADE backfill: content missing or blank for {}", docId);
            markedFailed +=
                handleSpladeFailure(
                    context.documentFieldOps(),
                    context.indexingCoordinator(),
                    docId,
                    "Content missing or blank",
                    context.log());
            failed++;
            continue;
          }

          batchDocIds.add(docId);
          batchContents.add(content);
        } catch (Exception e) {
          context.log().error("SPLADE content fetch failed for {}: {}", docId, e.getMessage());
          markedFailed +=
              handleSpladeFailure(context.documentFieldOps(), context.indexingCoordinator(), docId, e.getMessage(), context.log());
          failed++;
        }
      }
      long contentFetchMs = (System.nanoTime() - t1) / 1_000_000;

      if (batchContents.isEmpty()) {
        commitIfNeeded(context, processed, failed, markedFailed);
        return new StageOutcome(true, processed, (System.nanoTime() - t0) / 1_000_000);
      }

      // Re-check interruption after content collection
      if (paceAndCheckInterrupt(context)) {
        return new StageOutcome(true, processed, (System.nanoTime() - t0) / 1_000_000);
      }

      // Phase 2: Batch encode with SPLADE
      long t2 = System.nanoTime();
      List<Map<String, Float>> sparseVecs;
      try {
        sparseVecs = encoder.encodeBatch(batchContents);
        // A short/empty result is as unusable as null for the index-aligned loop in Phase 3 —
        // trusting its length to match batchDocIds is what crash-loops the embedding-chunk
        // sibling of this method (EmbeddingBackfillOps#processChunkEmbeddingBackfill). Route
        // it into the same per-doc fallback below instead of indexing out of bounds.
        if (sparseVecs == null || sparseVecs.size() != batchDocIds.size()) {
          throw new IllegalStateException(
              "SPLADE batch result size mismatch (expected "
                  + batchDocIds.size()
                  + ", got "
                  + (sparseVecs == null ? "null" : sparseVecs.size())
                  + ")");
        }
      } catch (Exception e) {
        context.log().error("SPLADE batch encoding failed: {}", e.getMessage());
        // Fallback to per-doc encoding
        for (int i = 0; i < batchDocIds.size(); i++) {
          try {
            Map<String, Float> sparseVec = encoder.encode(
                batchContents.get(i), io.justsearch.core.execution.InferenceRequest.background());
            Map<String, Object> updates = new HashMap<>();
            updates.put(SchemaFields.SPLADE, sparseVec);
            updates.put(SchemaFields.SPLADE_STATUS, spladeStatusFor(sparseVec));
            updates.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
            context.indexingCoordinator().updateDocument(batchDocIds.get(i), updates);
            processed++;
          } catch (Exception e2) {
            markedFailed +=
                handleSpladeFailure(
                    context.documentFieldOps(), context.indexingCoordinator(), batchDocIds.get(i), e2.getMessage(), context.log());
            failed++;
          }
        }

        // Detect batch-wide systemic failure: batch encode failed AND every per-doc fallback
        // also failed. This indicates a persistent encoder problem (e.g., GPU OOM, corrupted
        // session) — not a transient per-doc issue. Signal failure to caller for backoff.
        boolean systemicFailure = processed == 0 && failed == batchDocIds.size();
        commitIfNeeded(context, processed, failed, markedFailed);
        if (systemicFailure) {
          context
              .log()
              .warn(
                  "SPLADE encoding unavailable: entire batch of {} docs failed — {}",
                  failed,
                  e.getMessage());
          return new StageOutcome(false, processed, (System.nanoTime() - t0) / 1_000_000);
        }
        return new StageOutcome(true, processed, (System.nanoTime() - t0) / 1_000_000);
      }

      long encodeMs = (System.nanoTime() - t2) / 1_000_000;

      // Phase 3: Batch-update all docs with SPLADE vectors (single NRT refresh for batch)
      long t3 = System.nanoTime();
      List<Map.Entry<String, Map<String, Object>>> batchUpdates =
          new ArrayList<>(batchDocIds.size());
      for (int i = 0; i < batchDocIds.size(); i++) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(SchemaFields.SPLADE, sparseVecs.get(i));
        updates.put(SchemaFields.SPLADE_STATUS, spladeStatusFor(sparseVecs.get(i)));
        updates.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
        batchUpdates.add(Map.entry(batchDocIds.get(i), updates));
      }
      var batchResult = context.indexingCoordinator().updateDocumentsBatch(batchUpdates);
      processed += batchResult.updatedCount();
      long writeMs = (System.nanoTime() - t3) / 1_000_000;

      long t4 = System.nanoTime();
      commitIfNeeded(context, processed, failed, markedFailed);
      long commitMs = (System.nanoTime() - t4) / 1_000_000;

      long totalMs = (System.nanoTime() - t0) / 1_000_000;
      int docs = batchDocIds.size();
      context
          .log()
          .info(
              "SPLADE backfill profile: docs={}, total={}ms, query={}ms, contentFetch={}ms,"
                  + " encode={}ms, luceneWrite={}ms, commit={}ms, perDoc={}ms",
              docs,
              totalMs,
              queryMs,
              contentFetchMs,
              encodeMs,
              writeMs,
              commitMs,
              docs > 0 ? totalMs / docs : 0);
      return new StageOutcome(true, processed, totalMs);

    } catch (Exception e) {
      context.log().error("Error during SPLADE backfill", e);
      return new StageOutcome(false, 0, (System.nanoTime() - t0) / 1_000_000);
    }
  }

  /**
   * Tempdoc 885 item 3: paces against foreground load, then answers whether to stop. Foreground
   * contention is no longer a stop reason — it slows the stage through {@link
   * IndexingPacing#pace()} instead of abandoning the batch.
   */
  private static boolean paceAndCheckInterrupt(BackfillContext context) {
    context.pacing().pace();
    boolean interrupt = !context.runningSupplier().getAsBoolean();
    if (interrupt) {
      context.log().debug("SPLADE backfill interrupted: loop stopping");
    }
    return interrupt;
  }

  private static void commitIfNeeded(
      BackfillContext context, int processed, int failed, int markedFailed) {
    if (processed > 0 || failed > 0) {
      if (context.commitAfterBatch()) {
        context
            .commitOps()
            .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_SPLADE);
      }
      context
          .log()
          .info(
              "SPLADE backfill cycle: {} processed, {} failed ({} marked FAILED)",
              processed,
              failed,
              markedFailed);
    }
  }

  static int handleSpladeFailure(
      DocumentFieldOps documentFieldOps, IndexingCoordinator indexingCoordinator, String docId, String reason, Logger log) {
    try {
      String retryCountStr =
          documentFieldOps.getDocumentField(docId, SchemaFields.SPLADE_RETRY_COUNT);
      int currentRetryCount = parseRetryCountOrZero(retryCountStr);
      Map<String, Object> updates = computeSpladeFailureUpdate(currentRetryCount);
      int retryCount = currentRetryCount + 1;

      if (updates.containsKey(SchemaFields.SPLADE_STATUS)) {
        log.warn(
            "SPLADE permanently FAILED for {} after {} retries: {}",
            docId,
            retryCount,
            reason);
        indexingCoordinator.updateDocument(docId, updates);
        return 1;
      } else {
        log.debug(
            "SPLADE retry {}/{} for {}: {}",
            retryCount,
            SchemaFields.SPLADE_MAX_RETRIES,
            docId,
            reason);
        indexingCoordinator.updateDocument(docId, updates);
        return 0;
      }

    } catch (Exception e) {
      log.error("Failed to update SPLADE retry count for {}", docId, e);
      return 0;
    }
  }

  /**
   * The terminal SPLADE status a successful encode earns: {@code COMPLETED} when the weights will
   * actually materialise at least one posting, {@code COMPLETED_EMPTY} when they will not (null map,
   * empty map, or no strictly-positive weight — {@code FieldMapper.addFields} emits a
   * {@code FeatureField} only for {@code weight > 0}).
   *
   * <p>Shared by every SPLADE write site (this class, {@link BgeM3BackfillOps}, {@link
   * CombinedEnrichmentBackfillOps}) so the "did the encode produce anything?" question is answered
   * identically at all of them. Stamping COMPLETED here without postings is the data-less COMPLETED
   * the write-time status/artifact contract rejects (tempdoc 798) and the RMW reset lane would
   * otherwise heal back to PENDING forever.
   */
  static String spladeStatusFor(Map<String, Float> sparseVec) {
    if (sparseVec == null) {
      return SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY;
    }
    for (Float weight : sparseVec.values()) {
      if (weight != null && weight > 0.0f) {
        return SchemaFields.SPLADE_STATUS_COMPLETED;
      }
    }
    return SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY;
  }

  /**
   * Pure computation of the retry-count/status update for a SPLADE failure — no I/O. Shared by
   * {@link #handleSpladeFailure} (immediate single-doc write) and the combined enrichment path
   * (merges the result into its own single batched write), so the two stay in escalation-parity
   * by construction (tempdoc 700).
   *
   * @param currentRetryCount the doc's retry count *before* this failure
   * @return field updates: always {@code SPLADE_RETRY_COUNT}; additionally {@code
   *     SPLADE_STATUS=FAILED} once the incremented count reaches {@code SPLADE_MAX_RETRIES}
   */
  static Map<String, Object> computeSpladeFailureUpdate(int currentRetryCount) {
    int retryCount = currentRetryCount + 1;
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.SPLADE_RETRY_COUNT, String.valueOf(retryCount));
    if (retryCount >= SchemaFields.SPLADE_MAX_RETRIES) {
      updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_FAILED);
    }
    return updates;
  }

  private static int parseRetryCountOrZero(String retryCountStr) {
    if (retryCountStr == null || retryCountStr.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(retryCountStr);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }
}
