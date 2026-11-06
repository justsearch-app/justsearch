/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static io.justsearch.indexerworker.loop.ops.CombinedEnrichmentBackfillOps.*;

import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.inference.LocalSessionAcquisition;
import io.justsearch.indexerworker.loop.pacing.BulkPacePolicy;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared dense kernel: native units produce updates; the caller owns their publication receipt. */
final class DenseEnrichmentUnit {
  private DenseEnrichmentUnit() {}

  /**
   * VECTOR-only mode (tempdoc 691 §Phase M): no char spans are derived or passed to the encoder.
   */
  private static final int[][] NO_SPANS = new int[0][];

  /**
   * Documents per {@code embedDocumentBatch} call in Phase 3a (tempdoc 809 finding 3).
   *
   * <p>The windowed embed used to hand the whole batch — up to {@code embeddingBackfillBatchSize}
   * (100) parents plus {@code chunkSlotsPerBatch} (50) chunk docs — to ONE {@link
   * EmbeddingProvider#embedDocumentBatch} call, measured live at ~43 s. Nothing could be observed,
   * let alone interrupted, inside it, which is what made the scheduler's 5 s cycle budget
   * unenforceable and made removing a watched root cost a full minute of GPU on documents that were
   * already deleted.
   *
   * <p>8 is not a new pacing knob: it is {@code OnnxEmbeddingEncoder.MAX_ORT_BATCH_SIZE}, the size
   * the encoder ALREADY sub-batches every caller batch down to before each ORT run ({@code
   * OnnxEmbeddingEncoder.embedBatch:304-311}, {@code embedPreTokenizedBatch}). Slicing here
   * therefore issues the same sequence of native forward passes it always did — it only puts an
   * observation point between them, so the true atomic unit (one ORT run) becomes the interruption
   * granularity instead of the whole batch.
   */
  private static final int EMBED_ENCODE_SLICE = 8;

  /**
   * Encoder windows per resumable long-document embed unit (round-15 post-round finding).
   *
   * <p>{@link #EMBED_ENCODE_SLICE} above slices the batch by DOCUMENT, which is the right unit only
   * while a document is one forward pass. A 420 KB document is ~250 windows, so an 8-DOCUMENT slice
   * of long documents is ~2,000 forward passes inside one uninterruptible call — the 5 s cycle
   * budget could not touch it, and the long documents (folded in at the TAIL of the embed list by
   * the late-chunking fallback below) were never reached at all. Long documents are therefore
   * driven window-by-window instead, from the front of the batch.
   *
   * <p>32 windows is 4 ORT runs at {@code OnnxEmbeddingEncoder.MAX_ORT_BATCH_SIZE} — ~270 ms at the
   * 68 ms/run measured on the corpus that exposed this. Deliberately not 8: each slice re-tokenizes
   * the document to rebuild its window set, so a smaller slice buys interruption granularity the
   * budget does not need and pays for it in tokenizer CPU on exactly the largest documents.
   */
  private static final int EMBED_WINDOW_SLICE = 32;

  record Result(
      boolean aborted,
      int unitsDone,
      int embedFailed,
      int singlePassProcessed,
      int longDocWindowed,
      int arenaOomWindowed,
      int windowUnitsDone,
      int windowDocsCompleted,
      int windowDocsResumable,
      int singlePassDeferred,
      int embedPopulation) {}

  static Result encode(
      BackfillContext context,
      long epochAtSelection,
      IndexingCoordinator.EnrichmentAuthority authority,
      EmbeddingProvider selectedEmbeddingProvider,
      boolean embedAvailable,
      boolean retainedParentRoute,
      EnrichmentBatch batch,
      EnrichmentAccounting accounting,
      BulkPacePolicy.PhasePacer pacer,
      List<String> embedDocIds,
      List<String> embedContents,
      List<String> lateChunkingDocIds,
      List<String> lateChunkingContents,
      Set<String> directWindowedDocIds,
      Set<String> chunkIdsInBatch,
      Map<String, Map<String, String>> batchedFields,
      Map<String, Map<String, Object>> updatesByDocId) {
    var budget = new UnitBudget();
    boolean aborted = false;
    int unitsDone = 0;
    int embedFailed = 0;
    int singlePassProcessed = 0;
    int longDocWindowed = 0;
    int arenaOomWindowed = 0;
    // Round-15 post-round finding, resumable-windowing bookkeeping.
    int windowUnitsDone = 0;
    int windowDocsCompleted = 0;
    int windowDocsResumable = 0;
    int singlePassDeferred = 0;
    int embedPopulation = embedDocIds.size() + lateChunkingDocIds.size();

    // docs/tempdocs/809-human-validation-0.2.0-findings.md: partition resumable window work
    // from the ordinary batch path. The pre-fix late-chunking fallback appended long parents
    // behind every short parent and every chunk doc, so an 8-DOCUMENT slice loop under a 5 s budget
    // reached them last or not at all — and when it did, all of a document's windows ran inside
    // one uninterruptible call whose partial results were discarded on interruption. A bounded
    // chunk batch now precedes the windows, then parent windows run before the ordinary remainder.
    // Resuming parents were enrolled before this cycle's fallback. Every embedded window is folded
    // into the cross-cycle accumulator, so an interrupted document resumes at its next window.
    //
    // Documents whose provider reports a single window (every short document, every chunk doc,
    // and EVERY document when the provider does not expose window granularity — the interface
    // default) stay on the historical whole-document batch path below, unchanged.
    List<String> windowedDocIds = new ArrayList<>();
    List<String> windowedContents = new ArrayList<>();
    int priorityChunkEnd = 0;
    if (!embedDocIds.isEmpty() && embedAvailable) {
      EmbeddingProvider windowProbe = selectedEmbeddingProvider;
      List<String> singleWindowDocIds = new ArrayList<>(embedDocIds.size());
      List<String> singleWindowContents = new ArrayList<>(embedContents.size());
      for (int i = 0; i < embedDocIds.size(); i++) {
        if (!ready(context, accounting, windowProbe)) break;
        if (context.windowedEmbedProgress().route(embedDocIds.get(i), embedContents.get(i))
                != WindowedEmbedProgress.Route.FALLBACK
            && (directWindowedDocIds.contains(embedDocIds.get(i))
                || (context.windowedEmbedProgress().route(embedDocIds.get(i), embedContents.get(i))
                        != WindowedEmbedProgress.Route.ORDINARY
                    && windowProbe.documentWindowCount(embedContents.get(i)) > 1))) {
          windowedDocIds.add(embedDocIds.get(i));
          windowedContents.add(embedContents.get(i));
        } else if (chunkIdsInBatch.contains(embedDocIds.get(i))) {
          singleWindowDocIds.add(priorityChunkEnd, embedDocIds.get(i));
          singleWindowContents.add(priorityChunkEnd, embedContents.get(i));
          priorityChunkEnd++;
        } else {
          singleWindowDocIds.add(embedDocIds.get(i));
          singleWindowContents.add(embedContents.get(i));
        }
      }
      if (!windowedDocIds.isEmpty() || !lateChunkingDocIds.isEmpty()) {
        embedDocIds = singleWindowDocIds;
        embedContents = singleWindowContents;
      }
    }

    // docs/tempdocs/809-human-validation-0.2.0-findings.md: keep the encoder unit as the scheduling
    // boundary. One chunk batch gets a turn before single-pass attempts and parent windows;
    // remaining ordinary work follows them. Without either, retain the ordinary batch path.
    priorityChunkEnd =
        windowedDocIds.isEmpty() && lateChunkingDocIds.isEmpty()
            ? 0
            : Math.min(priorityChunkEnd, EMBED_ENCODE_SLICE);
    int sliceStart = 0;
    boolean priorityTurnEndedNormally = true;
    for (int embedTurn = 0; embedTurn < 2; embedTurn++) {
      if (embedTurn == 1 && priorityChunkEnd > 0) {
        // docs/tempdocs/809-human-validation-0.2.0-findings.md: a chunk turn may yield
        // only to a normal parent turn; cancellation must retain the batch's stop accounting.
        boolean stopped =
            context.hardStopRequestedSupplier().getAsBoolean()
                || stopRequested(context, epochAtSelection, authority);
        if (!priorityTurnEndedNormally
            || stopped
            || !ready(context, accounting, selectedEmbeddingProvider)) {

          aborted |= stopped;
          break;
        }
      }
      // Phase 3a-i: Late-chunking single-pass embed (tempdoc 691 forensics fold-in). Tries one
      // whole-document forward pass per chunked parent. A null result (content over the raised
      // single-pass limit) or a GPU BFC-arena OOM folds the doc INLINE into the window list below
      // rather than deferring to a different RMW pass —
      // classification stops at the first fallback so the remaining budget reaches actual
      // window/chunk inference. Unclassified parents remain pending for a later cycle.
      // Every outcome still resolves within this pass's single bundled write. A non-arena-OOM
      // failure
      // escalates the parent directly through the same retry-count/FAILED-at-max helper the
      // windowed batch failure path below uses.
      // A retained route must get useful work next turn: another slow classification must
      // not consume its deadline again. Unclassified parents keep their single-pass route.
      if (embedTurn == 1
          && !retainedParentRoute
          && !lateChunkingDocIds.isEmpty()
          && embedAvailable) {
        EmbeddingProvider lateChunkingProvider = selectedEmbeddingProvider;
        for (int i = 0; i < lateChunkingDocIds.size(); i++) {
          // One whole-document forward pass per iteration — already the atomic unit, so the
          // checkpoint costs nothing but a volatile read (tempdoc 809 finding 3).

          if (!ready(context, accounting, lateChunkingProvider)) break;
          String lcDocId = lateChunkingDocIds.get(i);
          String lcContent = lateChunkingContents.get(i);
          boolean fellBackToWindows = false;
          pacer.beforeUnit(1);
          if (stopRequested(context, epochAtSelection, authority)
              || !ready(context, accounting, lateChunkingProvider)) {
            aborted = true;
            break;
          }
          if (!context.unitAllowed().test(lateChunkingProvider.isUsingGpu())) break;
          if (!budget.admit()) break;
          try {
            batch.started(List.of(lcDocId), EnrichmentAccounting.Stage.EMBED);
            EmbeddingService.ChunkedEmbedding result =
                batch
                    .inferenceCancellation(
                        () ->
                            !context.runningSupplier().getAsBoolean()
                                || context.hardStopRequestedSupplier().getAsBoolean(),
                        () -> !ready(context, accounting, lateChunkingProvider))
                    .call(() -> lateChunkingProvider.embedWithSpans(lcContent, NO_SPANS));
            accounting.acquired(lateChunkingProvider);
            if (result != null && result.primaryVector().length > 0) {
              accounting.computed(lcDocId, EnrichmentAccounting.Stage.EMBED);
            }
            if (batch.retired(
                () -> !ready(context, accounting, lateChunkingProvider),
                EnrichmentAccounting.Stage.EMBED)) break;
            if (result != null && result.primaryVector().length > 0) {
              Map<String, Object> updates = updatesByDocId.get(lcDocId);
              updates.put(SchemaFields.VECTOR, result.primaryVector());
              updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
              updates.put(SchemaFields.EMBEDDING_RETRY_COUNT, "0");
              singlePassProcessed++;

            } else {
              // Retain the route even if classification consumed this turn's deadline.
              boolean windows = EmbeddingProvider.exposesDocumentWindows(lateChunkingProvider);
              context
                  .windowedEmbedProgress()
                  .route(
                      lcDocId,
                      lcContent,
                      windows
                          ? WindowedEmbedProgress.Route.WINDOW
                          : WindowedEmbedProgress.Route.ORDINARY);
              if (windows) {
                windowedDocIds.add(lcDocId);
                windowedContents.add(lcContent);
                directWindowedDocIds.add(lcDocId);
              } else {
                embedDocIds.add(lcDocId);
                embedContents.add(lcContent);
              }
              longDocWindowed++;
              fellBackToWindows = true;
            }
          } catch (Exception e) {
            if (batch.retired(
                () -> !ready(context, accounting, lateChunkingProvider),
                EnrichmentAccounting.Stage.EMBED)) break;
            if (accounting.cancelled(EnrichmentAccounting.Stage.EMBED, e)) {
              if (isArenaOomFailure(e)) {
                // Classification is a completed routing decision even when a hard stop
                // prevents the fallback from starting in this turn.
                boolean windows = EmbeddingProvider.exposesDocumentWindows(lateChunkingProvider);
                context
                    .windowedEmbedProgress()
                    .route(
                        lcDocId,
                        lcContent,
                        windows
                            ? WindowedEmbedProgress.Route.WINDOW
                            : WindowedEmbedProgress.Route.ORDINARY);
              }
              aborted |= hardStopRequested(context, epochAtSelection, authority);
              break;
            }
            if (accounting.acquisitionUnavailable(lateChunkingProvider, e)) break;
            if (!ready(context, accounting, lateChunkingProvider)
                || (SpladeBackfillOps.isSystemicFailure(e) && !isArenaOomFailure(e))) {
              break;
            }
            if (isArenaOomFailure(e)) {
              // A single-pass batch-1 forward pass over the whole document needs more contiguous
              // GPU arena memory than a windowed pass — resource contention, not a bad-input
              // failure. Fold into the windowed batch same as the over-limit null case.
              context
                  .log()
                  .warn(
                      "Combined backfill: late-chunking single-pass falling back to windowed for"
                          + " {} (GPU arena OOM on single-pass batch-1): {}",
                      lcDocId,
                      e.getMessage());
              boolean windows = EmbeddingProvider.exposesDocumentWindows(lateChunkingProvider);
              context
                  .windowedEmbedProgress()
                  .route(
                      lcDocId,
                      lcContent,
                      windows
                          ? WindowedEmbedProgress.Route.WINDOW
                          : WindowedEmbedProgress.Route.ORDINARY);
              if (windows) {
                windowedDocIds.add(lcDocId);
                windowedContents.add(lcContent);
                directWindowedDocIds.add(lcDocId);
              } else {
                embedDocIds.add(lcDocId);
                embedContents.add(lcContent);
              }
              arenaOomWindowed++;
              fellBackToWindows = true;
            } else {
              context
                  .log()
                  .warn(
                      "Combined backfill: late-chunking single-pass embed failed for {}: {}",
                      lcDocId,
                      e.getMessage());
              Map<String, String> lcDocFields = batchedFields.getOrDefault(lcDocId, Map.of());
              int currentRetryCount =
                  parseRetryCountOrZero(lcDocFields.get(SchemaFields.EMBEDDING_RETRY_COUNT));
              updatesByDocId
                  .get(lcDocId)
                  .putAll(EmbeddingBackfillOps.computeEmbeddingFailureUpdate(currentRetryCount));
              embedFailed++;

            }
          }
          pacer.afterUnit(1);
          unitsDone++;
          if (fellBackToWindows) {
            singlePassDeferred += lateChunkingDocIds.size() - i - 1;
            break;
          }
        }
      }

      if (embedTurn == 1 && !windowedDocIds.isEmpty() && embedAvailable) {
        EmbeddingProvider provider = selectedEmbeddingProvider;
        WindowedEmbedProgress progress = context.windowedEmbedProgress();
        for (int i = 0; i < windowedDocIds.size(); i++) {
          String docId = windowedDocIds.get(i);

          if (!ready(context, accounting, provider)) break;
          String content = windowedContents.get(i);
          boolean isChunk = chunkIdsInBatch.contains(docId);
          // Scoped to THIS document, deliberately: `aborted` is sticky across phases (Phase 3a-i
          // may already have set it), so reading it to decide whether the windowed lane stopped
          // would end the lane on a stale flag and mis-report every remaining document as skipped.
          boolean stoppedOnThisDocument = false;

          boolean rejectedSlice = false;
          try {
            int nextWindow = progress.nextWindow(docId, content);
            while (!progress.isComplete(docId)) {
              if (!ready(context, accounting, provider)) {
                stoppedOnThisDocument = true;
                break;
              }
              // A window is one encoder forward pass of at most a document's length, so the
              // embedding slice counts windows here; counted whether or not the call succeeds.
              pacer.beforeUnit(EMBED_WINDOW_SLICE);
              if (stopRequested(context, epochAtSelection, authority)
                  || !ready(context, accounting, provider)) {
                aborted = true;
                stoppedOnThisDocument = true;

                break;
              }
              if (!context.unitAllowed().test(provider.isUsingGpu())) break;
              if (!budget.admit()) {
                stoppedOnThisDocument = true;
                break;
              }
              EmbeddingProvider.WindowSlice slice;
              try {
                batch.started(
                    List.of(docId),
                    isChunk
                        ? EnrichmentAccounting.Stage.CHUNK_EMBED
                        : EnrichmentAccounting.Stage.EMBED);
                int fromWindow = nextWindow;
                slice =
                    batch
                        .inferenceCancellation(
                            () ->
                                !context.runningSupplier().getAsBoolean()
                                    || context.hardStopRequestedSupplier().getAsBoolean(),
                            () -> !ready(context, accounting, provider))
                        .call(
                            () ->
                                provider.embedDocumentWindows(
                                    content, fromWindow, EMBED_WINDOW_SLICE));
                accounting.acquired(provider);
              } finally {
                pacer.afterUnit(EMBED_WINDOW_SLICE);
              }
              if (slice == null) {
                progress.restartWindows(docId, content);
                rejectedSlice = true;
                throw new IllegalArgumentException("Null window slice");
              }
              unitsDone++;
              windowUnitsDone++;
              int advancedTo =
                  progress.record(
                      docId, content, slice.totalWindows(), slice.fromWindow(), slice.vectors());
              if (advancedTo <= nextWindow) {
                rejectedSlice = true;
                throw new IllegalArgumentException("Invalid window slice");
              }
              if (progress.isComplete(docId)) {
                accounting.computed(
                    docId,
                    isChunk
                        ? EnrichmentAccounting.Stage.CHUNK_EMBED
                        : EnrichmentAccounting.Stage.EMBED);
              }
              if (batch.retired(
                  () -> !ready(context, accounting, provider),
                  isChunk
                      ? EnrichmentAccounting.Stage.CHUNK_EMBED
                      : EnrichmentAccounting.Stage.EMBED)) {
                progress.forget(docId);
                stoppedOnThisDocument = true;
                break;
              }
              accounting.advanced(
                  isChunk
                      ? EnrichmentAccounting.Stage.CHUNK_EMBED
                      : EnrichmentAccounting.Stage.EMBED);
              nextWindow = advancedTo;
              if (nextWindow >= slice.totalWindows()) {
                break;
              }

            }
            if (progress.isComplete(docId)) {
              float[] vector = progress.complete(docId);
              Map<String, Object> updates = updatesByDocId.get(docId);
              updates.put(isChunk ? SchemaFields.CHUNK_VECTOR : SchemaFields.VECTOR, vector);
              updates.put(
                  isChunk ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.EMBEDDING_STATUS,
                  SchemaFields.EMBEDDING_STATUS_COMPLETED);
              updates.put(
                  isChunk
                      ? SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT
                      : SchemaFields.EMBEDDING_RETRY_COUNT,
                  "0");
              windowDocsCompleted++;

            } else {
              // Interrupted part-way. The document stays PENDING with no update of any kind — a
              // partial mean-pool is NOT this document's embedding and must never be written — but
              // its windows are now held in the accumulator, so the next cycle continues instead of
              // starting over. This is the whole fix.
              windowDocsResumable++;
            }
          } catch (Exception e) {
            if (batch.retired(
                () -> !ready(context, accounting, provider),
                isChunk
                    ? EnrichmentAccounting.Stage.CHUNK_EMBED
                    : EnrichmentAccounting.Stage.EMBED)) {
              progress.forget(docId);
              break;
            }
            if (accounting.cancelled(
                isChunk ? EnrichmentAccounting.Stage.CHUNK_EMBED : EnrichmentAccounting.Stage.EMBED,
                e)) {
              aborted |= hardStopRequested(context, epochAtSelection, authority);
              break;
            }
            context
                .log()
                .warn("Combined backfill: windowed embed failed for {}: {}", docId, e.getMessage());
            if (accounting.acquisitionUnavailable(provider, e)) break;
            if (!ready(context, accounting, provider) || SpladeBackfillOps.isSystemicFailure(e))
              break;
            // record() preserves a valid partial on a pure position mismatch, and resets
            // every other malformed slice. Rejections still use ordinary retry accounting.
            if (!rejectedSlice) progress.forget(docId);
            Map<String, String> docFields = batchedFields.getOrDefault(docId, Map.of());
            int currentRetryCount =
                parseRetryCountOrZero(
                    docFields.get(
                        isChunk
                            ? SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT
                            : SchemaFields.EMBEDDING_RETRY_COUNT));
            updatesByDocId
                .get(docId)
                .putAll(
                    isChunk
                        ? EmbeddingBackfillOps.computeChunkEmbeddingFailureUpdate(currentRetryCount)
                        : EmbeddingBackfillOps.computeEmbeddingFailureUpdate(currentRetryCount));
            embedFailed++;

          }
          if (stoppedOnThisDocument) {
            break;
          }
        }
      }

      if (!embedDocIds.isEmpty() && embedAvailable) {
        EmbeddingProvider provider = selectedEmbeddingProvider;
        // Tempdoc 809 finding 3: encode in EMBED_ENCODE_SLICE-document slices instead of one call
        // over the whole batch. Same native forward passes (the encoder sub-batches at exactly this
        // size anyway), but the loop can now be left between them — which is what makes the
        // scheduler's cycle budget, ingest preemption and root-removal cancellation enforceable
        // rather than aspirational.
        int sliceLimit = embedTurn == 0 ? priorityChunkEnd : embedDocIds.size();
        for (; sliceStart < sliceLimit; ) {

          boolean retainedFallback =
              context
                      .windowedEmbedProgress()
                      .route(embedDocIds.get(sliceStart), embedContents.get(sliceStart))
                  == WindowedEmbedProgress.Route.FALLBACK;
          int sliceEnd =
              Math.min(sliceStart + (retainedFallback ? 1 : EMBED_ENCODE_SLICE), sliceLimit);
          // A retained fallback never mixes with a new batch: it already paid for its failed batch.
          for (int i = sliceStart + 1; i < sliceEnd; i++) {
            if (context.windowedEmbedProgress().route(embedDocIds.get(i), embedContents.get(i))
                == WindowedEmbedProgress.Route.FALLBACK) {
              sliceEnd = i;
              break;
            }
          }
          List<String> sliceDocIds = embedDocIds.subList(sliceStart, sliceEnd);
          pacer.beforeUnit(sliceDocIds.size());
          if (stopRequested(context, epochAtSelection, authority)
              || !ready(context, accounting, provider)) {
            aborted = true;
            priorityTurnEndedNormally = false;
            break;
          }
          if (!context.unitAllowed().test(provider.isUsingGpu())) break;
          List<float[]> vectors = null;
          if (!retainedFallback) {
            if (!budget.admit()) break;
            try {
              for (String id : sliceDocIds)
                batch.started(
                    List.of(id),
                    chunkIdsInBatch.contains(id)
                        ? EnrichmentAccounting.Stage.CHUNK_EMBED
                        : EnrichmentAccounting.Stage.EMBED);
              List<String> sliceTexts = embedContents.subList(sliceStart, sliceEnd);
              List<float[]> batchVectors =
                  batch
                      .inferenceCancellation(
                          () ->
                              !context.runningSupplier().getAsBoolean()
                                  || context.hardStopRequestedSupplier().getAsBoolean(),
                          () -> !ready(context, accounting, provider))
                      .call(() -> provider.embedDocumentBatch(sliceTexts));
              accounting.acquired(provider);
              if (batchVectors != null && batchVectors.size() == sliceDocIds.size()) {
                vectors = new ArrayList<>(sliceDocIds.size());
                // Retain each read result even if a later row fails to materialize.
                for (int i = 0; i < sliceDocIds.size(); i++) vectors.add(batchVectors.get(i));
              }
            } catch (Exception failure) {
              if (batch.retired(
                  () -> !ready(context, accounting, provider),
                  EnrichmentAccounting.Stage.EMBED,
                  EnrichmentAccounting.Stage.CHUNK_EMBED)) break;
              boolean cancelled = accounting.cancelled(EnrichmentAccounting.Stage.EMBED, failure);
              cancelled |= accounting.cancelled(EnrichmentAccounting.Stage.CHUNK_EMBED, failure);
              if (cancelled) {
                aborted |= hardStopRequested(context, epochAtSelection, authority);
                priorityTurnEndedNormally = false;
                break;
              }
              if (accounting.acquisitionUnavailable(provider, failure)) {
                priorityTurnEndedNormally = false;
                break;
              }
              if (!ready(context, accounting, provider)
                  || SpladeBackfillOps.isSystemicFailure(failure)) {
                priorityTurnEndedNormally = false;
                break;
              }
            } finally {
              pacer.afterUnit(sliceDocIds.size());
              if (embedTurn == 0) unitsDone++;
            }
            if (embedTurn == 1) unitsDone++;
          }
          if (vectors != null) {
            for (int i = 0; i < vectors.size(); i++) {
              float[] vector = vectors.get(i);
              if (vector != null && vector.length > 0) {
                String id = sliceDocIds.get(i);
                accounting.computed(
                    id,
                    chunkIdsInBatch.contains(id)
                        ? EnrichmentAccounting.Stage.CHUNK_EMBED
                        : EnrichmentAccounting.Stage.EMBED);
              }
            }
          }
          if (!ready(context, accounting, provider)) {
            priorityTurnEndedNormally = false;
            break;
          }
          boolean fallback = vectors == null || vectors.size() != sliceDocIds.size();
          int materialized = vectors == null ? 0 : vectors.size();
          if (fallback) {
            if (vectors == null) vectors = new ArrayList<>(sliceDocIds.size());
            for (int i = materialized; i < sliceDocIds.size(); i++) vectors.add(null);
            // One-unit callers resume only the unread suffix next turn instead of repeatedly
            // paying for a bad batch or re-encoding its successful prefix.
            for (int i = sliceStart + materialized; i < sliceEnd; i++)
              context
                  .windowedEmbedProgress()
                  .route(
                      embedDocIds.get(i),
                      embedContents.get(i),
                      WindowedEmbedProgress.Route.FALLBACK);

          }
          for (int i = 0; i < sliceDocIds.size(); i++) {
            if (!ready(context, accounting, provider)) {
              priorityTurnEndedNormally = false;
              break;
            }
            if (fallback && i >= materialized) {
              // A fallback attempt is a new unit and must respect the same stops.
              pacer.beforeUnit(1);
              if (stopRequested(context, epochAtSelection, authority)
                  || !ready(context, accounting, provider)) {
                aborted = true;
                break;
              }
              if (!context.unitAllowed().test(provider.isUsingGpu())) break;
              if (!budget.admit()) break;
              try {
                String fallbackId = sliceDocIds.get(i);
                batch.started(
                    List.of(fallbackId),
                    chunkIdsInBatch.contains(fallbackId)
                        ? EnrichmentAccounting.Stage.CHUNK_EMBED
                        : EnrichmentAccounting.Stage.EMBED);
                String fallbackText = embedContents.get(sliceStart + i);
                vectors.set(
                    i,
                    batch
                        .inferenceCancellation(
                            () ->
                                !context.runningSupplier().getAsBoolean()
                                    || context.hardStopRequestedSupplier().getAsBoolean(),
                            () -> !ready(context, accounting, provider))
                        .call(
                            () ->
                                provider.embedDocument(
                                    fallbackText, LocalSessionAcquisition.backgroundInference())));
                accounting.acquired(provider);
                float[] vector = vectors.get(i);
                if (vector != null && vector.length > 0) {
                  String id = sliceDocIds.get(i);
                  accounting.computed(
                      id,
                      chunkIdsInBatch.contains(id)
                          ? EnrichmentAccounting.Stage.CHUNK_EMBED
                          : EnrichmentAccounting.Stage.EMBED);
                }
              } catch (Exception failure) {
                if (batch.retired(
                    () -> !ready(context, accounting, provider),
                    EnrichmentAccounting.Stage.EMBED,
                    EnrichmentAccounting.Stage.CHUNK_EMBED)) break;
                boolean cancelled = accounting.cancelled(EnrichmentAccounting.Stage.EMBED, failure);
                cancelled |= accounting.cancelled(EnrichmentAccounting.Stage.CHUNK_EMBED, failure);
                if (cancelled) {
                  aborted |= hardStopRequested(context, epochAtSelection, authority);
                  priorityTurnEndedNormally = false;
                  break;
                }
                if (accounting.acquisitionUnavailable(provider, failure)) {
                  priorityTurnEndedNormally = false;
                  break;
                }
                if (!ready(context, accounting, provider)
                    || SpladeBackfillOps.isSystemicFailure(failure)) {
                  priorityTurnEndedNormally = false;
                  break;
                }
              } finally {
                pacer.afterUnit(1);
              }
              if (!ready(context, accounting, provider)) {
                priorityTurnEndedNormally = false;
                break;
              }
            }
            float[] vector = vectors.get(i);
            String eid = sliceDocIds.get(i);
            Map<String, Object> updates = updatesByDocId.get(eid);
            boolean isChunk = chunkIdsInBatch.contains(eid);
            if (vector != null && vector.length > 0) {
              // Chunk docs use CHUNK_VECTOR/CHUNK_EMBEDDING_STATUS; parents use
              // VECTOR/EMBEDDING_STATUS
              updates.put(isChunk ? SchemaFields.CHUNK_VECTOR : SchemaFields.VECTOR, vector);
              updates.put(
                  isChunk ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.EMBEDDING_STATUS,
                  SchemaFields.EMBEDDING_STATUS_COMPLETED);
              updates.put(
                  isChunk
                      ? SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT
                      : SchemaFields.EMBEDDING_RETRY_COUNT,
                  "0");

              if (fallback) {
                try {
                  batch.update(eid, updates);
                } finally {
                  if (batch.acceptedIds().contains(eid))
                    context.windowedEmbedProgress().forget(eid);
                  updates.clear();
                }
              }
            } else {
              // Tempdoc 700: escalate instead of silently resetting to PENDING forever. Look up
              // the retry count already fetched in the batched pre-fetch, delegate the
              // increment/FAILED-at-max decision to the same pure helper the individual
              // EmbeddingBackfillOps siblings use, and merge the result into this doc's entry in
              // updatesByDocId — never a direct updateDocument call (preserves the
              // single-batched-write invariant, tempdoc 312 BUG-1).
              Map<String, String> eidFields = batchedFields.getOrDefault(eid, Map.of());
              int currentRetryCount =
                  parseRetryCountOrZero(
                      eidFields.get(
                          isChunk
                              ? SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT
                              : SchemaFields.EMBEDDING_RETRY_COUNT));
              updates.putAll(
                  isChunk
                      ? EmbeddingBackfillOps.computeChunkEmbeddingFailureUpdate(currentRetryCount)
                      : EmbeddingBackfillOps.computeEmbeddingFailureUpdate(currentRetryCount));
              embedFailed++;

            }
          }
          sliceStart = sliceEnd;
        }
      }
    }
    return new Result(
        aborted,
        unitsDone,
        embedFailed,
        singlePassProcessed,
        longDocWindowed,
        arenaOomWindowed,
        windowUnitsDone,
        windowDocsCompleted,
        windowDocsResumable,
        singlePassDeferred,
        embedPopulation);
  }

  private static final class UnitBudget {
    private int admitted;

    boolean admit() {
      return admitted++ < 1;
    }
  }

  private static boolean ready(
      BackfillContext context, EnrichmentAccounting accounting, EmbeddingProvider provider) {
    return embeddingReady(context, provider) && !accounting.acquisitionHeld(provider);
  }
}
