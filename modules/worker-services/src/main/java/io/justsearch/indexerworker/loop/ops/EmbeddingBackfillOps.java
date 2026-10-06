/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

public final class EmbeddingBackfillOps {
  private EmbeddingBackfillOps() {}

  // About 1 MiB of UTF-16 text per parent batch. An oversized individual document is
  // admitted whole, then the batch stops: extraction policy remains the per-file limit.
  static final long MAX_PARENT_BATCH_CHARS = 512_000;

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      WorkerSignalBus signalBus,
      IndexingPacing pacing,
      Supplier<EmbeddingProvider> embeddingProviderSupplier,
      BooleanSupplier runningSupplier,
      BooleanSupplier allowEmbeddingWritesSupplier,
      int batchSize,
      Logger log) {}

  /**
   * Processes one batch of parent-doc embedding backfill.
   *
   * @return the batch outcome (tempdoc 710 Move 2 item 4) — {@link BackfillScheduler} records
   *     per-stage timing/counts from this instead of relying on a metrics call inside the op.
   */
  public static StageOutcome processEmbeddingBackfill(BackfillContext context) {
    long methodStart = System.nanoTime();
    if (!context.allowEmbeddingWritesSupplier().getAsBoolean()) {
      context.log().trace("Embedding backfill skipped: writes blocked by compatibility controller");
      return StageOutcome.none();
    }

    try {
      List<String> pendingIds =
          context.documentFieldOps().queryDocIdsByField(
              SchemaFields.EMBEDDING_STATUS,
              SchemaFields.EMBEDDING_STATUS_PENDING,
              context.batchSize());

      if (pendingIds.isEmpty()) {
        return StageOutcome.none();
      }

      context.log().info("Processing embedding backfill for {} documents", pendingIds.size());
      int processed = 0;
      int failed = 0;
      int markedFailed = 0;

      // Check for interruption before batch work
      EmbeddingProvider embeddingProvider = context.embeddingProviderSupplier().get();
      if (checkInterrupt(context, embeddingProvider, "Backfill")) {
        return StageOutcome.elapsedSince(methodStart);
      }

      // Phase 1: Bound retained parent content as well as document count. Fetch incrementally
      // so a batch of large files cannot exhaust heap before the encoder sees its first window.
      long t0 = System.nanoTime();
      List<String> batchDocIds = new ArrayList<>(pendingIds.size());
      List<String> batchContents = new ArrayList<>(pendingIds.size());
      long retainedChars = 0;
      for (String docId : pendingIds) {
        String content = context.documentFieldOps().getDocumentContent(docId);
        if (content == null || content.isBlank()) {
          context.log().warn("Backfill: Document content missing for {}", docId);
          markedFailed +=
              handleEmbeddingFailure(
                  context.documentFieldOps(), context.indexingCoordinator(), docId, "Content missing or blank", context.log());
          failed++;
          continue;
        }
        if (!batchContents.isEmpty()
            && retainedChars + content.length() > MAX_PARENT_BATCH_CHARS) break;
        batchDocIds.add(docId);
        batchContents.add(content);
        retainedChars += content.length();
        if (retainedChars >= MAX_PARENT_BATCH_CHARS) break;
      }

      if (batchContents.isEmpty()) {
        commitIfNeeded(context, processed, failed, markedFailed);
        return new StageOutcome(true, processed, (System.nanoTime() - methodStart) / 1_000_000);
      }

      long t1 = System.nanoTime();

      // Re-check interruption after content collection
      if (checkInterrupt(context, context.embeddingProviderSupplier().get(), "Backfill")) {
        return new StageOutcome(true, processed, (System.nanoTime() - methodStart) / 1_000_000);
      }

      // Phase 2: Batch embed all collected content
      List<float[]> vectors = embeddingProvider.embedDocumentBatch(batchContents);
      // 312 E3: Force full result materialization + brief yield to let GPU resources release.
      // Testing whether GPU memory holding interferes with Lucene mmap writes.
      if (vectors != null) {
        @SuppressWarnings("unused")
        float sink = 0;
        for (float[] v : vectors) {
          if (v != null && v.length > 0) sink += v[0];
        }
      }
      long t2 = System.nanoTime();

      // Phase 3: Update docs with results (fallback to sequential if batch failed or
      // misaligned — trusting a batch result's length to match the request is what caused
      // the chunk-backfill AIOOBE crash-loop below; guard both null and size-mismatch here too).
      if (vectors == null || vectors.size() != batchDocIds.size()) {
        if (vectors == null) {
          context.log().warn("Backfill: Batch embedding returned null, falling back to per-doc");
        } else {
          context.log().warn(
              "Backfill: Batch embedding size mismatch (expected {}, got {}), falling back to per-doc",
              batchDocIds.size(),
              vectors.size());
        }
        for (int i = 0; i < batchDocIds.size(); i++) {
          int[] counts =
              embedAndUpdateSingle(
                  context, embeddingProvider, batchDocIds.get(i), batchContents.get(i));
          processed += counts[0];
          failed += counts[1];
          markedFailed += counts[2];
        }
      } else {
        // Collect successful updates for batch write (single NRT refresh)
        long tListStart = System.nanoTime();
        List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();
        for (int i = 0; i < batchDocIds.size(); i++) {
          String docId = batchDocIds.get(i);
          float[] vector = vectors.get(i);
          if (vector != null && vector.length > 0) {
            Map<String, Object> updates = new HashMap<>();
            updates.put(SchemaFields.VECTOR, vector);
            updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
            updates.put(SchemaFields.EMBEDDING_RETRY_COUNT, "0");
            batchUpdates.add(Map.entry(docId, updates));
          } else {
            context.log().warn("Backfill: Batch returned null vector for {}", docId);
            markedFailed +=
                handleEmbeddingFailure(
                    context.documentFieldOps(), context.indexingCoordinator(), docId, "Empty vector in batch", context.log());
            failed++;
          }
        }
        long tListEnd = System.nanoTime();
        if (!batchUpdates.isEmpty()) {
          var result = context.indexingCoordinator().updateDocumentsBatch(batchUpdates);
          processed += result.updatedCount();
        }
        long tWriteEnd = System.nanoTime();
        context.log().info(
            "Backfill write phases: listBuild={}ms, updateBatch={}ms, total={}ms",
            (tListEnd - tListStart) / 1_000_000,
            (tWriteEnd - tListEnd) / 1_000_000,
            (tWriteEnd - tListStart) / 1_000_000);
      }

      long t3 = System.nanoTime();
      // 312 item 28: Log per-phase timing for backfill profiling
      context.log().info(
          "Backfill batch timing: docs={}, fetch={}ms, embed={}ms, write={}ms, total={}ms",
          batchDocIds.size(),
          (t1 - t0) / 1_000_000,
          (t2 - t1) / 1_000_000,
          (t3 - t2) / 1_000_000,
          (t3 - t0) / 1_000_000);

      commitIfNeeded(context, processed, failed, markedFailed);
      return new StageOutcome(true, processed, (System.nanoTime() - methodStart) / 1_000_000);

    } catch (Exception e) {
      context.log().error("Error during embedding backfill", e);
      return new StageOutcome(false, 0, (System.nanoTime() - methodStart) / 1_000_000);
    }
  }

  private static boolean checkInterrupt(
      BackfillContext context, EmbeddingProvider embeddingProvider, String label) {
    // Tempdoc 885 item 3: foreground load paces this stage boundary instead of interrupting it.
    context.pacing().pace();
    // Tempdoc 630: defer backfill when Main claims the GPU (VRAM conflict, GPU only) OR the OS wants
    // reduced background work (energy saver, GPU+CPU). The two reasons are kept distinct so energy
    // throttles CPU backfill too.
    boolean mainGpuActive = context.signalBus().isMainGpuActive();
    boolean energyReduced = context.signalBus().isEnergyReduced();
    // Spec E step E3: the bulk hold stops this pass too, when its encoder (embedding, the only one
    // this pass runs) is on the GPU. CPU embedding is not GPU work and is not held (P2).
    boolean bulkHeld = context.signalBus().isBulkHeld();
    boolean shouldInterrupt =
        LoopPacingPolicy.shouldInterruptBackfill(
            context.runningSupplier().getAsBoolean(),
            mainGpuActive,
            energyReduced,
            bulkHeld,
            false,
            embeddingProvider);
    if (shouldInterrupt) {
      boolean backfillBlocked =
          !LoopPacingPolicy.shouldRunBackfill(
              mainGpuActive, energyReduced, bulkHeld, false, embeddingProvider);
      context
          .log()
          .debug(
              "{} interrupted: backfill blocked (GPU/energy/hold)={}, stopping={}",
              label,
              backfillBlocked,
              !context.runningSupplier().getAsBoolean());
    }
    return shouldInterrupt;
  }

  private static void commitIfNeeded(
      BackfillContext context, int processed, int failed, int markedFailed) {
    if (processed > 0 || failed > 0) {
      context
          .commitOps()
          .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_EMBEDDING);
      context
          .log()
          .info(
              "Backfill complete: {} processed, {} failed ({} permanently marked FAILED)",
              processed,
              failed,
              markedFailed);
    }
  }

  private static int[] embedAndUpdateSingle(
      BackfillContext context, EmbeddingProvider embeddingProvider, String docId, String content) {
    int processed = 0;
    int failed = 0;
    int markedFailed = 0;
    try {
      float[] vector = embeddingProvider.embedDocument(content, InferenceRequest.background());
      if (vector != null && vector.length > 0) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(SchemaFields.VECTOR, vector);
        updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
        updates.put(SchemaFields.EMBEDDING_RETRY_COUNT, "0");
        context.indexingCoordinator().updateDocument(docId, updates);
        processed = 1;
      } else {
        markedFailed =
            handleEmbeddingFailure(
                context.documentFieldOps(), context.indexingCoordinator(), docId, "Empty vector returned", context.log());
        failed = 1;
      }
    } catch (Exception e) {
      context.log().error("Backfill failed for doc {}: {}", docId, e.getMessage());
      markedFailed =
          handleEmbeddingFailure(context.documentFieldOps(), context.indexingCoordinator(), docId, e.getMessage(), context.log());
      failed = 1;
    }
    return new int[] {processed, failed, markedFailed};
  }

  public static int handleEmbeddingFailure(
      DocumentFieldOps documentFieldOps, IndexingCoordinator indexingCoordinator, String docId, String reason, Logger log) {
    try {
      String retryCountStr = documentFieldOps.getDocumentField(docId, SchemaFields.EMBEDDING_RETRY_COUNT);
      int currentRetryCount = parseRetryCountOrZero(retryCountStr);
      Map<String, Object> updates = computeEmbeddingFailureUpdate(currentRetryCount);
      int retryCount = currentRetryCount + 1;

      if (updates.containsKey(SchemaFields.EMBEDDING_STATUS)) {
        log.warn("Embedding permanently FAILED for {} after {} retries: {}", docId, retryCount, reason);
        indexingCoordinator.updateDocument(docId, updates);
        return 1;
      } else {
        log.debug(
            "Embedding retry {}/{} for {}: {}",
            retryCount,
            SchemaFields.EMBEDDING_MAX_RETRIES,
            docId,
            reason);
        indexingCoordinator.updateDocument(docId, updates);
        return 0;
      }

    } catch (Exception e) {
      log.error("Failed to update retry count for {}", docId, e);
      return 0;
    }
  }

  /**
   * Pure computation of the retry-count/status update for a parent-doc embedding failure — no I/O.
   * Used by both {@link #handleEmbeddingFailure} (immediate single-doc write) and the combined
   * enrichment path ({@code CombinedEnrichmentBackfillOps}, which merges the result into its own
   * single batched write), so the two paths stay in escalation-parity by construction rather than
   * via a hand-ported copy that can drift (tempdoc 700).
   *
   * @param currentRetryCount the doc's retry count *before* this failure
   * @return field updates: always {@code EMBEDDING_RETRY_COUNT}; additionally {@code
   *     EMBEDDING_STATUS=FAILED} once the incremented count reaches {@code EMBEDDING_MAX_RETRIES}
   */
  public static Map<String, Object> computeEmbeddingFailureUpdate(int currentRetryCount) {
    int retryCount = currentRetryCount + 1;
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.EMBEDDING_RETRY_COUNT, String.valueOf(retryCount));
    if (retryCount >= SchemaFields.EMBEDDING_MAX_RETRIES) {
      updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_FAILED);
    }
    return updates;
  }

  /**
   * Processes one batch of chunk-doc embedding backfill.
   *
   * @return outcome whose {@code success()} preserves the original "any chunks processed"
   *     tight-loop-control signal (tempdoc 710 Move 2 item 4 — the record also carries
   *     docsProcessed/elapsedMs for {@link BackfillScheduler}'s per-stage metrics recording)
   */
  public static StageOutcome processChunkEmbeddingBackfill(BackfillContext context) {
    long methodStart = System.nanoTime();
    if (!context.allowEmbeddingWritesSupplier().getAsBoolean()) {
      context.log().trace("Chunk embedding backfill skipped: writes blocked by compatibility controller");
      return new StageOutcome(false, 0, (System.nanoTime() - methodStart) / 1_000_000);
    }

    try {
      List<String> pendingChunkIds =
          context.documentFieldOps().queryDocIdsByField(
              SchemaFields.CHUNK_EMBEDDING_STATUS,
              SchemaFields.EMBEDDING_STATUS_PENDING,
              context.batchSize());

      if (pendingChunkIds.isEmpty()) {
        return new StageOutcome(false, 0, (System.nanoTime() - methodStart) / 1_000_000);
      }

      context
          .log()
          .info("Processing chunk embedding backfill for {} chunks", pendingChunkIds.size());
      int processed = 0;
      int failed = 0;
      int markedFailed = 0;

      EmbeddingProvider embeddingProvider = context.embeddingProviderSupplier().get();
      if (checkInterrupt(context, embeddingProvider, "Chunk backfill")) {
        return new StageOutcome(false, 0, (System.nanoTime() - methodStart) / 1_000_000);
      }

      // Phase 1: reconstruct chunk text in one batch (distinct parents are read once).
      Map<String, String> contentByChunkId =
          context.documentFieldOps().getDocumentContentBatch(pendingChunkIds);
      List<String> batchChunkIds = new ArrayList<>(pendingChunkIds.size());
      List<String> batchContents = new ArrayList<>(pendingChunkIds.size());
      for (String chunkId : pendingChunkIds) {
        try {
          String chunkContent = contentByChunkId.get(chunkId);
          if (chunkContent == null || chunkContent.isBlank()) {
            context.log().warn("Chunk backfill: Content missing for {}", chunkId);
            markedFailed +=
                handleChunkEmbeddingFailure(
                    context.documentFieldOps(),
                    context.indexingCoordinator(),
                    chunkId,
                    "Chunk content missing or blank",
                    context.log());
            failed++;
            continue;
          }
          batchChunkIds.add(chunkId);
          batchContents.add(chunkContent);
        } catch (Exception e) {
          context.log().error("Chunk backfill content fetch failed for {}: {}", chunkId, e.getMessage());
          markedFailed +=
              handleChunkEmbeddingFailure(
                  context.documentFieldOps(), context.indexingCoordinator(), chunkId, e.getMessage(), context.log());
          failed++;
        }
      }

      if (batchContents.isEmpty()) {
        commitChunkIfNeeded(context, processed, failed, markedFailed);
        return new StageOutcome(
            failed > 0, processed, (System.nanoTime() - methodStart) / 1_000_000);
      }

      if (checkInterrupt(context, context.embeddingProviderSupplier().get(), "Chunk backfill")) {
        return new StageOutcome(false, 0, (System.nanoTime() - methodStart) / 1_000_000);
      }

      // Phase 2: Batch embed
      List<float[]> vectors = embeddingProvider.embedDocumentBatch(batchContents);

      // Phase 3: Update chunks with results (fallback to sequential if batch failed or
      // misaligned — a null-only guard here let a short/empty `vectors` result reach the
      // index-aligned loop below and throw AIOOBE before any chunk was marked failed, which
      // crash-looped the whole enrichment cycle forever since the scheduler just refetched
      // the same still-PENDING batch).
      if (vectors == null || vectors.size() != batchChunkIds.size()) {
        if (vectors == null) {
          context.log().warn("Chunk backfill: Batch embedding returned null, falling back to per-chunk");
        } else {
          context.log().warn(
              "Chunk backfill: Batch embedding size mismatch (expected {}, got {}), falling back to per-chunk",
              batchChunkIds.size(),
              vectors.size());
        }
        for (int i = 0; i < batchChunkIds.size(); i++) {
          String chunkId = batchChunkIds.get(i);
          try {
            float[] vector =
                embeddingProvider.embedDocument(batchContents.get(i), InferenceRequest.background());
            if (vector != null && vector.length > 0) {
              Map<String, Object> updates = new HashMap<>();
              updates.put(SchemaFields.CHUNK_VECTOR, vector);
              updates.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
              updates.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");
              context.indexingCoordinator().updateDocument(chunkId, updates);
              processed++;
            } else {
              markedFailed +=
                  handleChunkEmbeddingFailure(
                      context.documentFieldOps(), context.indexingCoordinator(), chunkId, "Empty vector returned", context.log());
              failed++;
            }
          } catch (Exception e) {
            context.log().error("Chunk backfill failed for {}: {}", chunkId, e.getMessage());
            markedFailed +=
                handleChunkEmbeddingFailure(
                    context.documentFieldOps(), context.indexingCoordinator(), chunkId, e.getMessage(), context.log());
            failed++;
          }
        }
      } else {
        // Collect successful updates for batch write (single NRT refresh)
        List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();
        for (int i = 0; i < batchChunkIds.size(); i++) {
          String chunkId = batchChunkIds.get(i);
          float[] vector = vectors.get(i);
          if (vector != null && vector.length > 0) {
            Map<String, Object> updates = new HashMap<>();
            updates.put(SchemaFields.CHUNK_VECTOR, vector);
            updates.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
            updates.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");
            batchUpdates.add(Map.entry(chunkId, updates));
          } else {
            context.log().warn("Chunk backfill: Batch returned null vector for {}", chunkId);
            markedFailed +=
                handleChunkEmbeddingFailure(
                    context.documentFieldOps(), context.indexingCoordinator(), chunkId, "Empty vector in batch", context.log());
            failed++;
          }
        }
        if (!batchUpdates.isEmpty()) {
          var result = context.indexingCoordinator().updateDocumentsBatch(batchUpdates);
          processed += result.updatedCount();
        }
      }

      commitChunkIfNeeded(context, processed, failed, markedFailed);
      return new StageOutcome(
          processed > 0 || failed > 0, processed, (System.nanoTime() - methodStart) / 1_000_000);

    } catch (Exception e) {
      context.log().error("Error during chunk embedding backfill", e);
      return new StageOutcome(false, 0, (System.nanoTime() - methodStart) / 1_000_000);
    }
  }

  private static void commitChunkIfNeeded(
      BackfillContext context, int processed, int failed, int markedFailed) {
    if (processed > 0 || failed > 0) {
      context
          .commitOps()
          .commitAndTrack(
              io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_EMBEDDING_CHUNK);
      context
          .log()
          .info(
              "Chunk backfill complete: {} processed, {} failed ({} permanently marked FAILED)",
              processed,
              failed,
              markedFailed);
    }
  }

  public static int handleChunkEmbeddingFailure(
      DocumentFieldOps documentFieldOps, IndexingCoordinator indexingCoordinator, String chunkId, String reason, Logger log) {
    try {
      String retryCountStr =
          documentFieldOps.getDocumentField(chunkId, SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT);
      int currentRetryCount = parseRetryCountOrZero(retryCountStr);
      Map<String, Object> updates = computeChunkEmbeddingFailureUpdate(currentRetryCount);
      int retryCount = currentRetryCount + 1;

      if (updates.containsKey(SchemaFields.CHUNK_EMBEDDING_STATUS)) {
        log.warn(
            "Chunk embedding permanently FAILED for {} after {} retries: {}",
            chunkId,
            retryCount,
            reason);
        indexingCoordinator.updateDocument(chunkId, updates);
        return 1;
      } else {
        log.debug(
            "Chunk embedding retry {}/{} for {}: {}",
            retryCount,
            SchemaFields.EMBEDDING_MAX_RETRIES,
            chunkId,
            reason);
        indexingCoordinator.updateDocument(chunkId, updates);
        return 0;
      }

    } catch (Exception e) {
      log.error("Failed to update chunk retry count for {}", chunkId, e);
      return 0;
    }
  }

  /**
   * Pure computation of the retry-count/status update for a chunk-doc embedding failure — no I/O.
   * Mirrors {@link #computeEmbeddingFailureUpdate} for the {@code CHUNK_*} field pair; shared by
   * {@link #handleChunkEmbeddingFailure} and the combined enrichment path (tempdoc 700).
   *
   * @param currentRetryCount the chunk's retry count *before* this failure
   * @return field updates: always {@code CHUNK_EMBEDDING_RETRY_COUNT}; additionally {@code
   *     CHUNK_EMBEDDING_STATUS=FAILED} once the incremented count reaches {@code
   *     EMBEDDING_MAX_RETRIES} (chunk embedding shares the parent-doc threshold — there is no
   *     separate {@code CHUNK_EMBEDDING_MAX_RETRIES} constant)
   */
  public static Map<String, Object> computeChunkEmbeddingFailureUpdate(int currentRetryCount) {
    int retryCount = currentRetryCount + 1;
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, String.valueOf(retryCount));
    if (retryCount >= SchemaFields.EMBEDDING_MAX_RETRIES) {
      updates.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_FAILED);
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
