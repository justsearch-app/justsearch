/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.inference.LocalSessionAcquisition;
import io.justsearch.indexerworker.loop.pacing.BulkPacePolicy;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexerworker.metrics.EncoderOrtRunSpans;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.justsearch.ort.SessionAcquisitionException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
      IndexingPacing pacing,
      Supplier<SpladeEncoder> spladeEncoderSupplier,
      BooleanSupplier runningSupplier,
      int batchSize,
      boolean chunkSpladeEnabled,
      Logger log,
      BooleanSupplier unitAllowed) {

  }

  /** A bounded parent-only sparse unit, with its own keyset cursor. */
  public static StageOutcome processParentBackfill(
      BackfillContext context, EnrichmentSelection selection) {
    return processSpladeBackfill(context, selection,
        DocumentFieldOps.PendingDocumentKind.PARENT);
  }

  /** A bounded chunk-only sparse unit; disabled chunks are never selected. */
  public static StageOutcome processChunkBackfill(
      BackfillContext context, EnrichmentSelection selection) {
    if (!context.chunkSpladeEnabled()) return StageOutcome.none();
    return processSpladeBackfill(context, selection,
        DocumentFieldOps.PendingDocumentKind.CHUNK);
  }

  private static StageOutcome processSpladeBackfill(BackfillContext context,
      EnrichmentSelection selection, DocumentFieldOps.PendingDocumentKind kind) {
    if (selection != null) selection.begin();
    var authority = context.indexingCoordinator().enrichmentAuthority();
    long t0 = System.nanoTime();
    SpladeEncoder encoder = context.spladeEncoderSupplier().get();
    long cancellationEpoch = context.indexingCoordinator().bulkDeleteEpoch();
    EnrichmentAccounting accounting = new EnrichmentAccounting(stage ->
        EnrichmentBatch.hardStop(() -> !context.runningSupplier().getAsBoolean(),
            context.indexingCoordinator(), cancellationEpoch, authority)
            || context.spladeEncoderSupplier().get() != encoder);
    Map<String, String> terminalFailures = new LinkedHashMap<>();
    Span enrichmentSpan = EncoderOrtRunSpans.maybeEnrichmentBatch();
    try (Scope _ = enrichmentSpan.makeCurrent()) {
    try {
      List<String> pendingIds =
          EnrichmentSelection.page(selection, context.documentFieldOps(),
              SchemaFields.SPLADE_STATUS, Math.min(context.batchSize(), BulkPacePolicy.DEFAULTS.spladePhaseDocs()), kind);
      long queryMs = (System.nanoTime() - t0) / 1_000_000;

      var excluded = context.documentFieldOps().excludedProcessingIds(pendingIds);
      pendingIds = new ArrayList<>(pendingIds);
      pendingIds.removeAll(excluded);
      accounting.select(pendingIds, EnrichmentAccounting.Stage.SPLADE);
      if (pendingIds.isEmpty()) {
        if (selection != null) selection.examined(excluded);
        return StageOutcome.none();
      }

      if (encoder == null) {
        context.log().debug("SPLADE backfill: encoder unavailable, stopping batch");
        return StageOutcome.observed(true, 0, t0, accounting);
      }

      context.log().info("Processing SPLADE backfill for {} documents", pendingIds.size());
      int processed = 0;
      int failed = 0;
      int markedFailed = 0;

      // Check for interruption before batch work
      if (paceAndCheckInterrupt(context)) {
        accounting.cancelRemaining();
        return StageOutcome.observed(true, 0, t0, accounting);
      }

      // Phase 1: collect parent content or reconstructed chunk slices in one batch.
      long t1 = System.nanoTime();
      EnrichmentBatch batch = new EnrichmentBatch(context.documentFieldOps(), context.indexingCoordinator(), pendingIds, authority, selection, excluded).accounting(accounting);
      BooleanSupplier unavailable = () -> context.spladeEncoderSupplier().get() != encoder;
      Map<String, String> contentByDocId = batch.contents();
      List<String> batchDocIds = new ArrayList<>(pendingIds.size());
      List<String> batchContents = new ArrayList<>(pendingIds.size());
      for (String docId : batch.ids()) {
        if (selection != null && !"PENDING".equals(batch.fields(docId).get(SchemaFields.SPLADE_STATUS))) continue;
        if (!context.unitAllowed().getAsBoolean()) break;
        if (batch.stopped(() -> !context.runningSupplier().getAsBoolean(), unavailable, EnrichmentAccounting.Stage.SPLADE)) {
          accounting.cancelRemaining();
          break;
        }
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
                publishFailure(batch, docId, "blank-content", terminalFailures);
            failed++;
            continue;
          }

          batchDocIds.add(docId);
          batchContents.add(content);
        } catch (Exception e) {
          if (accounting.storageFailed()) throw e;
          accounting.cancelled(EnrichmentAccounting.Stage.SPLADE, e);
          context.log().error("SPLADE content fetch failed for {}: {}", docId, e.getMessage());
          markedFailed +=
              publishFailure(batch, docId, "content-fetch-failure", terminalFailures);
          failed++;
        }
      }
      long contentFetchMs = (System.nanoTime() - t1) / 1_000_000;

      if (batchContents.isEmpty()) {
        commitIfNeeded(context, processed, failed, markedFailed);
        return StageOutcome.observed(true, processed, t0, accounting);
      }

      // Re-check interruption after content collection
      if (paceAndCheckInterrupt(context)) {
        accounting.cancelRemaining();
        return StageOutcome.observed(true, processed, t0, accounting);
      }

      // Phase 2: Batch encode with SPLADE. Spec D §3.3: the idle-branch batch is 200 documents
      // (justsearch.backfill.splade_batch_size), measured at 2.5 s in one call (M5) — past the
      // ~1 s a bulk producer may hold the GPU between yields (ADR-0050 decision 2). It is encoded
      // in spladePhaseDocs slices with a pace point between them. Any
      // failing slice sends only its unfinished suffix to the per-document fallback. Earlier
      // completed slices retain their results even if a fallback operation is cancelled.
      long t2 = System.nanoTime();
      BulkPacePolicy bulkPace = BulkPacePolicy.DEFAULTS;
      BulkPacePolicy.PhasePacer pacer = new BulkPacePolicy.PhasePacer(context.pacing());
      pacer.startPhase(bulkPace.spladePhaseDocs());
      List<Map<String, Float>> sparseVecs = new ArrayList<>(batchDocIds.size());
      try {
        for (int sliceStart = 0;
            sliceStart < batchContents.size();
            sliceStart += bulkPace.spladePhaseDocs()) {
          int sliceEnd = Math.min(sliceStart + bulkPace.spladePhaseDocs(), batchContents.size());
          pacer.beforeUnit(sliceEnd - sliceStart);
          if (!context.unitAllowed().getAsBoolean()) break;
          if (batch.stopped(() -> !context.runningSupplier().getAsBoolean(), unavailable, EnrichmentAccounting.Stage.SPLADE)) {
            accounting.cancelRemaining();
            break;
          }
          List<Map<String, Float>> sliceVecs;
          try {
            accounting.attempted(EnrichmentAccounting.Stage.SPLADE);
            batch.started(batchDocIds.subList(sliceStart, sliceEnd), EnrichmentAccounting.Stage.SPLADE);
            List<String> sliceTexts = batchContents.subList(sliceStart, sliceEnd);
            sliceVecs = batch.inferenceCancellation(() -> !context.runningSupplier().getAsBoolean(), unavailable).call(() -> encoder.encodeBatch(sliceTexts));
            accounting.acquired(encoder);
          } finally {
            pacer.afterUnit(sliceEnd - sliceStart);
          }
          // A short/empty result is as unusable as null for the index-aligned loop in Phase 3 —
          // trusting its length to match batchDocIds is what crash-loops the embedding-chunk
          // sibling of this method (EmbeddingBackfillOps#processChunkEmbeddingBackfill). Route
          // it into the same per-doc fallback below instead of indexing out of bounds.
          if (sliceVecs == null || sliceVecs.size() != sliceEnd - sliceStart) {
            throw new IllegalStateException(
                "SPLADE batch result size mismatch (expected "
                    + (sliceEnd - sliceStart)
                    + ", got "
                    + (sliceVecs == null ? "null" : sliceVecs.size())
                    + ")");
          }
          for (int i = sliceStart; i < sliceEnd; i++) {
            accounting.computed(batchDocIds.get(i), EnrichmentAccounting.Stage.SPLADE);
          }
          if (batch.retired(unavailable, EnrichmentAccounting.Stage.SPLADE)) break;
          // Materialize one row at a time so fallback retains a successfully read prefix.
          for (int i = 0; i < sliceVecs.size(); i++) sparseVecs.add(sliceVecs.get(i));
        }
      } catch (Exception e) {
        if (batch.retired(unavailable, EnrichmentAccounting.Stage.SPLADE)) {
          return StageOutcome.observed(true, processed, t0, accounting);
        }
        boolean cancelled = accounting.cancelled(EnrichmentAccounting.Stage.SPLADE, e);
        if (!cancelled) {
          if (accounting.acquisitionUnavailable(encoder, e)) {
            commitIfNeeded(context, processed, failed, markedFailed);
            return StageOutcome.observed(false, processed, t0, accounting);
          }
          context.log().error("SPLADE batch encoding failed: {}", e.getMessage());
          if (context.spladeEncoderSupplier().get() != encoder || isSystemicFailure(e)) {
            commitIfNeeded(context, processed, failed, markedFailed);
            return StageOutcome.observed(false, processed, t0, accounting);
          }
          // Publish completed slices before fallback so its cancellation cannot discard them.
          processed += publishSparseVectors(batch, batchDocIds, sparseVecs);
          // Fallback to per-doc encoding for only the unfinished suffix.
          for (int i = sparseVecs.size(); i < batchDocIds.size(); i++) {
            if (context.spladeEncoderSupplier().get() != encoder) {
              commitIfNeeded(context, processed, failed, markedFailed);
              return StageOutcome.observed(false, processed, t0, accounting);
            }
            pacer.beforeUnit(1);
            if (!context.unitAllowed().getAsBoolean()) break;
            if (batch.stopped(() -> !context.runningSupplier().getAsBoolean(), unavailable, EnrichmentAccounting.Stage.SPLADE)) {
              accounting.cancelRemaining();
              break;
            }
            try {
              String fallbackText = batchContents.get(i);
              Map<String, Float> sparseVec = batch.inferenceCancellation(() -> !context.runningSupplier().getAsBoolean(), unavailable).call(() -> encoder.encode(
                  fallbackText, LocalSessionAcquisition.backgroundInference()));
              accounting.acquired(encoder);
              accounting.computed(batchDocIds.get(i), EnrichmentAccounting.Stage.SPLADE);
              if (batch.retired(unavailable, EnrichmentAccounting.Stage.SPLADE)) break;
              Map<String, Object> updates = new HashMap<>();
              updates.put(SchemaFields.SPLADE, sparseVec);
              updates.put(SchemaFields.SPLADE_STATUS, spladeStatusFor(sparseVec));
              updates.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
              if (batch.update(batchDocIds.get(i), updates)) processed++;
            } catch (Exception e2) {
              if (accounting.storageFailed()) throw e2;
              if (batch.retired(unavailable, EnrichmentAccounting.Stage.SPLADE)) break;
              if (accounting.cancelled(EnrichmentAccounting.Stage.SPLADE, e2)) break;
              if (accounting.acquisitionUnavailable(encoder, e2)) break;
              if (context.spladeEncoderSupplier().get() != encoder || isSystemicFailure(e2)) {
                commitIfNeeded(context, processed, failed, markedFailed);
                return StageOutcome.observed(false, processed, t0, accounting);
              }
              markedFailed +=
                  publishFailure(batch, batchDocIds.get(i), "encode-failure", terminalFailures);
              failed++;
            }
            pacer.afterUnit(1);
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
            return StageOutcome.observed(false, processed, t0, accounting);
          }
          return StageOutcome.observed(true, processed, t0, accounting);
        }
        // Never enter Lucene with an interrupted reader or publish its partial native result.
        return StageOutcome.observed(true, processed, t0, accounting);
      }

      if (sparseVecs.isEmpty()) {
        commitIfNeeded(context, processed, failed, markedFailed);
        return StageOutcome.observed(true, processed, t0, accounting);
      }
      long encodeMs = (System.nanoTime() - t2) / 1_000_000;

      // Phase 3: Batch-update all docs with SPLADE vectors (single NRT refresh for batch)
      long t3 = System.nanoTime();
      processed += publishSparseVectors(batch, batchDocIds, sparseVecs);
      batch.rejectRetiredOutcomes();
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
      return StageOutcome.observed(true, processed, t0, accounting);

    } catch (Exception e) {
      if (context.indexingCoordinator().enrichmentAuthority() != authority) {
        accounting.rejectedRetiredOutcomes();
      }
      boolean cancelled = accounting.cancelled(EnrichmentAccounting.Stage.SPLADE, e);
      context.log().error("Error during SPLADE backfill", e);
      return StageOutcome.observed(cancelled, 0, t0, accounting);
    } finally {
      if (!terminalFailures.isEmpty()) {
        context.log().warn("SPLADE backfill: {} document(s) reached terminal SPLADE FAILED: {}",
            terminalFailures.size(), List.copyOf(terminalFailures.values()));
      }
      context.log().info("Enrichment dispositions: {}", accounting.snapshot().counters());
    }
    } finally {
      enrichmentSpan.end();
    }
  }

  private static int publishFailure(EnrichmentBatch batch,
      String id, String reason, Map<String, String> terminalFailures) {
    var updates = computeSpladeFailureUpdate(CombinedEnrichmentBackfillOps.parseRetryCountOrZero(
        batch.fields(id).get(SchemaFields.SPLADE_RETRY_COUNT)));
    boolean terminal = SchemaFields.SPLADE_STATUS_FAILED.equals(updates.get(SchemaFields.SPLADE_STATUS));
    try {
      return batch.update(id, updates) && terminal ? 1 : 0;
    } finally {
      if (terminal && batch.acceptedIds().contains(id)) {
        String detail = reason.equals("blank-content") && "true".equals(batch.fields(id).get(SchemaFields.IS_CHUNK))
            ? "blank-chunk-content" : reason;
        terminalFailures.putIfAbsent(id, id + "(" + detail + ")");
      }
    }
  }

  private static int publishSparseVectors(
      EnrichmentBatch batch, List<String> docIds, List<Map<String, Float>> sparseVecs) {
    if (sparseVecs.isEmpty()) return 0;
    List<Map.Entry<String, Map<String, Object>>> updates = new ArrayList<>(sparseVecs.size());
    for (int i = 0; i < sparseVecs.size(); i++) {
      Map<String, Object> fields = new HashMap<>();
      fields.put(SchemaFields.SPLADE, sparseVecs.get(i));
      fields.put(SchemaFields.SPLADE_STATUS, spladeStatusFor(sparseVecs.get(i)));
      fields.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
      updates.add(Map.entry(docIds.get(i), fields));
    }
    return batch.updateBatch(updates).updatedCount();
  }

  static boolean isSystemicFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SessionAcquisitionException) return true;
      if (CombinedEnrichmentBackfillOps.isArenaOomFailure(cause)) return true;
    }
    return false;
  }

  /**
   * Tempdoc 885 item 3: paces against foreground load, then answers whether to stop. Foreground
   * contention is no longer a stop reason — it slows the stage through {@link
   * IndexingPacing#pace()} instead of abandoning the batch.
   */
  private static boolean paceAndCheckInterrupt(BackfillContext context) {
    context.pacing().pace();
    boolean interrupt = !context.runningSupplier().getAsBoolean()
        || Thread.currentThread().isInterrupted();
    if (interrupt) {
      context.log().debug("SPLADE backfill interrupted: loop stopping");
    }
    return interrupt;
  }

  private static void commitIfNeeded(
      BackfillContext context, int processed, int failed, int markedFailed) {
    if (processed > 0 || failed > 0) {
      context
          .log()
          .info(
              "SPLADE backfill cycle: {} processed, {} failed ({} marked FAILED)",
              processed,
              failed,
              markedFailed);
    }
  }

  /**
   * The terminal SPLADE status a successful encode earns: {@code COMPLETED} when the weights will
   * actually materialise at least one posting, {@code COMPLETED_EMPTY} when they will not (null map,
   * empty map, or no strictly-positive weight — {@code FieldMapper.addFields} emits a
   * {@code FeatureField} only for {@code weight > 0}).
   *
   * <p>Shared by this lane and {@link BgeM3BackfillOps}, so the "did the encode produce anything?" question is answered
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
   * snapshot-bound sparse and BGE-M3 lane publication.
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

}
