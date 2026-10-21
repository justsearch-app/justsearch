/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.bgem3.BgeM3Output;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Unified dense+sparse backfill using BGE-M3. Writes both {@link SchemaFields#SPLADE} (sparse
 * weights) and {@link SchemaFields#VECTOR} (dense embedding) in a single pass, since BGE-M3
 * produces both outputs in one forward pass.
 *
 * <p>Follows the same pattern as {@link SpladeBackfillOps}. Documents with {@code
 * splade_status=PENDING} are processed; both sparse and dense fields are written together.
 *
 * <p>Tempdoc 710 D.3: the {@code splade_status=PENDING} query has no parent/chunk filter, so a
 * batch mixes parent docs and chunk docs (chunk docs get {@code SPLADE_STATUS=PENDING} at
 * creation too — see {@code ChunkDocumentWriter}). The dense-write side must therefore route by
 * doc type — chunk docs use {@link SchemaFields#CHUNK_VECTOR} / {@link
 * SchemaFields#CHUNK_EMBEDDING_STATUS}, parents use {@link SchemaFields#VECTOR} / {@link
 * SchemaFields#EMBEDDING_STATUS} — mirroring the doc-type routing in {@code
 * CombinedEnrichmentBackfillOps}. Doc type is detected from {@link SchemaFields#IS_CHUNK}; content
 * is fetched through the parent-aware batch reader, which reconstructs chunk text from the parent
 * and stored offsets. The sparse ({@code SPLADE}/{@code SPLADE_STATUS}) write pair is unaffected —
 * chunk docs legitimately carry the SPLADE field.
 */
public final class BgeM3BackfillOps {
  private BgeM3BackfillOps() {}

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      WorkerSignalBus signalBus,
      Supplier<BgeM3Encoder> encoderSupplier,
      BooleanSupplier runningSupplier,
      int batchSize,
      boolean commitAfterBatch,
      // Tempdoc 712/931: see SpladeBackfillOps.BackfillContext — same flag, same reason. This lane
      // writes the sparse and dense pair together, so a chunk it selects gets sparse data too.
      boolean chunkSpladeEnabled,
      Logger log) {}

  /**
   * Processes a batch of BGE-M3 backfill documents.
   *
   * @return outcome whose {@code success()} preserves the original "not a systemic failure"
   *     signal ({@code true} on success or partial success, {@code false} when the entire batch
   *     failed systemically); the record also carries docsProcessed/elapsedMs for {@link
   *     BackfillScheduler}'s per-stage metrics recording (tempdoc 710 Move 2 item 4).
   */
  public static StageOutcome processBgeM3Backfill(BackfillContext context) {
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

      BgeM3Encoder encoder = context.encoderSupplier().get();
      if (encoder == null) {
        context.log().debug("BGE-M3 backfill: encoder unavailable, stopping batch");
        return StageOutcome.none();
      }

      context.log().info("Processing BGE-M3 backfill for {} documents", pendingIds.size());
      int processed = 0;
      int failed = 0;

      if (shouldInterrupt(context)) {
        return StageOutcome.elapsedSince(t0);
      }

      // Phase 1: Collect content for all pending docs
      long t1 = System.nanoTime();
      EnrichmentBatch batch = new EnrichmentBatch(context.documentFieldOps(), context.indexingCoordinator(), pendingIds);
      Map<String, String> contentByDocId = batch.contents();
      Map<String, Map<String, String>> typeByDocId =
          batch.fields();
      List<String> batchDocIds = new ArrayList<>(pendingIds.size());
      List<String> batchContents = new ArrayList<>(pendingIds.size());
      // Tempdoc 710 D.3: aligned with batchDocIds/batchContents — tracks whether each pending doc
      // is a chunk doc (CHUNK_VECTOR/CHUNK_EMBEDDING_STATUS) or a parent doc (VECTOR/
      // EMBEDDING_STATUS), since the SPLADE_STATUS=PENDING query above mixes both doc types.
      List<Boolean> batchIsChunk = new ArrayList<>(pendingIds.size());
      for (String docId : pendingIds) {
        try {
          boolean isChunk =
              "true".equals(
                  typeByDocId.getOrDefault(docId, Map.of()).get(SchemaFields.IS_CHUNK));
          String content = contentByDocId.get(docId);

          if (content == null || content.isBlank()) {
            // Neither the sparse postings nor the dense vector can exist without content. Stamping
            // COMPLETED on both stages claims two artifacts that will never be written — a
            // data-less COMPLETED (the F-032 "status lies" class) that the reset-status RMW policy
            // (tempdoc 711/717) bounces straight back to PENDING, forever. Escalate each stage
            // through its own retry-count seam instead: retry next cycle, FAILED at max.
            context.log().warn("BGE-M3 backfill: content missing or blank for {}", docId);
            batch.failure(docId, SchemaFields.SPLADE_RETRY_COUNT, SpladeBackfillOps::computeSpladeFailureUpdate);
            if (isChunk) {
              batch.failure(docId, SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, EmbeddingBackfillOps::computeChunkEmbeddingFailureUpdate);
            } else {
              batch.failure(docId, SchemaFields.EMBEDDING_RETRY_COUNT, EmbeddingBackfillOps::computeEmbeddingFailureUpdate);
            }
            failed++;
            continue;
          }

          batchDocIds.add(docId);
          batchContents.add(content);
          batchIsChunk.add(isChunk);
        } catch (Exception e) {
          context.log().error("BGE-M3 content fetch failed for {}: {}", docId, e.getMessage());
          failed++;
        }
      }
      long contentFetchMs = (System.nanoTime() - t1) / 1_000_000;

      if (batchContents.isEmpty()) {
        commitIfNeeded(context, processed, failed);
        return new StageOutcome(true, processed, (System.nanoTime() - t0) / 1_000_000);
      }

      if (shouldInterrupt(context)) {
        return new StageOutcome(true, processed, (System.nanoTime() - t0) / 1_000_000);
      }

      // Phase 2: Batch encode with BGE-M3 (produces both dense + sparse)
      long t2 = System.nanoTime();
      List<BgeM3Output> outputs;
      try {
        outputs = encoder.encodeBatch(batchContents);
        // A short/empty result is as unusable as null for the index-aligned loop in Phase 3 —
        // trusting its length to match batchDocIds is what crash-loops the embedding-chunk
        // sibling of this method (EmbeddingBackfillOps#processChunkEmbeddingBackfill). Route
        // it into the same per-doc fallback below instead of indexing out of bounds.
        if (outputs == null || outputs.size() != batchDocIds.size()) {
          throw new IllegalStateException(
              "BGE-M3 batch result size mismatch (expected "
                  + batchDocIds.size()
                  + ", got "
                  + (outputs == null ? "null" : outputs.size())
                  + ")");
        }
      } catch (Exception e) {
        context.log().error("BGE-M3 batch encoding failed: {}", e.getMessage());
        // Fallback to per-doc encoding
        int perDocFailed = 0;
        for (int i = 0; i < batchDocIds.size(); i++) {
          String docId = batchDocIds.get(i);
          boolean isChunk = batchIsChunk.get(i);
          try {
            BgeM3Output output = encoder.encode(
                batchContents.get(i), io.justsearch.core.execution.InferenceRequest.background());
            if (writeOutput(batch, docId, isChunk, output)) processed++;
          } catch (Exception e2) {
            context
                .log()
                .warn("BGE-M3 per-doc encoding failed for {}: {}", docId, e2.getMessage());
            // Tempdoc 710 D.3: escalate via the chunk-vs-parent appropriate helper so a
            // deterministically-failing doc reaches EMBEDDING_MAX_RETRIES instead of retrying
            // this pending batch forever with no persisted state.
            if (isChunk) {
              batch.failure(docId, SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, EmbeddingBackfillOps::computeChunkEmbeddingFailureUpdate);
            } else {
              batch.failure(docId, SchemaFields.EMBEDDING_RETRY_COUNT, EmbeddingBackfillOps::computeEmbeddingFailureUpdate);
            }
            perDocFailed++;
            failed++;
          }
        }
        boolean systemicFailure = processed == 0 && perDocFailed == batchDocIds.size();
        commitIfNeeded(context, processed, failed);
        if (systemicFailure) {
          context
              .log()
              .warn(
                  "BGE-M3 encoding unavailable: entire batch of {} docs failed — {}",
                  perDocFailed,
                  e.getMessage());
          return new StageOutcome(false, processed, (System.nanoTime() - t0) / 1_000_000);
        }
        return new StageOutcome(true, processed, (System.nanoTime() - t0) / 1_000_000);
      }
      long encodeMs = (System.nanoTime() - t2) / 1_000_000;

      // Phase 3: Batch-update all docs with both dense + sparse fields
      long t3 = System.nanoTime();
      List<Map.Entry<String, Map<String, Object>>> batchUpdates =
          new ArrayList<>(batchDocIds.size());
      for (int i = 0; i < batchDocIds.size(); i++) {
        BgeM3Output output = outputs.get(i);
        boolean isChunk = batchIsChunk.get(i);
        Map<String, Object> updates = new HashMap<>();
        putSparse(updates, output.sparseWeights());
        if (output.denseVector() != null && output.denseVector().length > 0) {
          // Tempdoc 710 D.3: chunk docs use CHUNK_VECTOR/CHUNK_EMBEDDING_STATUS; parents use
          // VECTOR/EMBEDDING_STATUS.
          updates.put(isChunk ? SchemaFields.CHUNK_VECTOR : SchemaFields.VECTOR, output.denseVector());
          updates.put(
              isChunk ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.EMBEDDING_STATUS,
              SchemaFields.EMBEDDING_STATUS_COMPLETED);
          updates.put(
              isChunk
                  ? SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT
                  : SchemaFields.EMBEDDING_RETRY_COUNT,
              "0");
        }
        batchUpdates.add(Map.entry(batchDocIds.get(i), updates));
      }
      var batchResult = batch.updateBatch(batchUpdates);
      processed += batchResult.updatedCount();
      long writeMs = (System.nanoTime() - t3) / 1_000_000;

      long t4 = System.nanoTime();
      commitIfNeeded(context, processed, failed);
      long commitMs = (System.nanoTime() - t4) / 1_000_000;

      long totalMs = (System.nanoTime() - t0) / 1_000_000;
      int docs = batchDocIds.size();
      context
          .log()
          .info(
              "BGE-M3 backfill profile: docs={}, total={}ms, query={}ms, contentFetch={}ms,"
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
      context.log().error("Error during BGE-M3 backfill", e);
      return new StageOutcome(false, 0, (System.nanoTime() - t0) / 1_000_000);
    }
  }

  private static boolean writeOutput(
      EnrichmentBatch batch, String docId, boolean isChunk, BgeM3Output output) {
    Map<String, Object> updates = new HashMap<>();
    putSparse(updates, output.sparseWeights());
    if (output.denseVector() != null && output.denseVector().length > 0) {
      // Tempdoc 710 D.3: chunk docs use CHUNK_VECTOR/CHUNK_EMBEDDING_STATUS; parents use
      // VECTOR/EMBEDDING_STATUS.
      updates.put(isChunk ? SchemaFields.CHUNK_VECTOR : SchemaFields.VECTOR, output.denseVector());
      updates.put(
          isChunk ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.EMBEDDING_STATUS,
          SchemaFields.EMBEDDING_STATUS_COMPLETED);
      updates.put(
          isChunk ? SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT : SchemaFields.EMBEDDING_RETRY_COUNT,
          "0");
    }
    return batch.update(docId, updates);
  }

  /**
   * Writes the sparse half of a BGE-M3 output into an update map. {@code BgeM3Output} is a bare
   * record with no validation, so {@code sparseWeights()} can be null; the key is then OMITTED
   * rather than set to null (tempdoc 798 review F5). A null VALUE would still reach the RMW merge
   * and, because the preservation engine skips any field the caller supplied ({@code
   * updates.containsKey}), it would be indistinguishable from "this write brings splade data" while
   * indexing none. Omitting the key states the truth. The status is derived by the shared {@link
   * SpladeBackfillOps#spladeStatusFor} predicate either way, which already answers null with
   * {@code COMPLETED_EMPTY} — so the write-time status/artifact contract never sees an
   * unwitnessed COMPLETED from this path.
   */
  private static void putSparse(Map<String, Object> updates, Map<String, Float> sparseWeights) {
    if (sparseWeights != null) {
      updates.put(SchemaFields.SPLADE, sparseWeights);
    }
    updates.put(SchemaFields.SPLADE_STATUS, SpladeBackfillOps.spladeStatusFor(sparseWeights));
    updates.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
  }

  private static void commitIfNeeded(BackfillContext context, int processed, int failed) {
    if (processed > 0 || failed > 0) {
      if (context.commitAfterBatch()) {
        context
            .commitOps()
            .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_BGE_M3);
      }
      context
          .log()
          .info("BGE-M3 backfill batch complete: processed={}, failed={}", processed, failed);
    }
  }

  private static boolean shouldInterrupt(BackfillContext context) {
    return !context.runningSupplier().getAsBoolean()
        || context.signalBus().shouldYieldGpuBackfill(); // tempdoc 630: GPU-claimed OR energy-reduced
  }
}
