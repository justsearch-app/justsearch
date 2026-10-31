/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import ai.onnxruntime.OrtException;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.loop.pacing.BulkPacePolicy;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.metrics.EncoderOrtRunSpans;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.ort.NativeSessionHandle;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Combined enrichment backfill: embedding + SPLADE + NER in a single RMW pass per document.
 *
 * <p>Eliminates cross-stage RMW churn where each independent backfill stage drops non-stored fields
 * from other stages (tempdoc 312 BUG-1). By running all enrichments on the same document and
 * writing once, no data is lost between stages. Follows the industry-standard pipeline-in-memory,
 * write-once pattern (ES ingest pipelines, Solr URPs, Vespa document processors).
 *
 * <p>Tempdoc 334 item 3.
 *
 * <p><b>Late-chunking single-pass embed strategy (tempdoc 691 forensics fold-in).</b> Lucene
 * read-modify-write destroys non-stored fields absent from the current write — {@code VECTOR} is a
 * non-stored {@code KnnFloatVectorField} (see the {@code WritePathOps} readModifyWrite re-queue
 * comment). An earlier design ran the late-chunking single-pass embed as a SEPARATE RMW pass
 * ({@code LateChunkingEmbedBackfillOps}) ahead of this one; this pass's later SPLADE/NER-only RMW
 * for the same doc then silently destroyed that just-written vector — chunked parents ended
 * COMPLETED but vectorless (legal-clerc live evidence: vector nDCG 0.016). The fix folds the
 * single-pass strategy IN as another embed sub-phase here: when {@link
 * BackfillContext#lateChunkingEnabled()} is true and a pending-embed parent has {@code
 * PARENT_DOC_ID}-matching chunk docs, its vector comes from one whole-document forward pass
 * ({@link EmbeddingProvider#embedWithSpans}, empty span array) instead of the base-window-mean
 * batch embed — but the result always lands in the SAME per-doc update map this pass already
 * builds for SPLADE/NER, so every doc still gets exactly one bundled write. A {@code null} result
 * (content over the raised single-pass limit) or a GPU BFC-arena OOM on the single-pass call falls
 * back INLINE into the ordinary windowed batch below, alongside every other pending-embed doc in
 * the batch — still one RMW per doc. Flag off: byte-identical to before the fold — the
 * chunk-existence probe and {@code embedWithSpans} are never called.
 */
public final class CombinedEnrichmentBackfillOps {
  private CombinedEnrichmentBackfillOps() {}

  /** VECTOR-only mode (tempdoc 691 §Phase M): no char spans are derived or passed to the encoder. */
  private static final int[][] NO_SPANS = new int[0][];

  /**
   * Documents per {@code embedDocumentBatch} call in Phase 3a (tempdoc 809 finding 3).
   *
   * <p>The windowed embed used to hand the whole batch — up to {@code embeddingBackfillBatchSize}
   * (100) parents plus {@code chunkSlotsPerBatch} (50) chunk docs — to ONE
   * {@link EmbeddingProvider#embedDocumentBatch} call, measured live at ~43 s. Nothing could be
   * observed, let alone interrupted, inside it, which is what made the scheduler's 5 s cycle budget
   * unenforceable and made removing a watched root cost a full minute of GPU on documents that were
   * already deleted.
   *
   * <p>8 is not a new pacing knob: it is {@code OnnxEmbeddingEncoder.MAX_ORT_BATCH_SIZE}, the size
   * the encoder ALREADY sub-batches every caller batch down to before each ORT run
   * ({@code OnnxEmbeddingEncoder.embedBatch:304-311}, {@code embedPreTokenizedBatch}). Slicing here
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

  /**
   * Whether the embed stage must hand the rest of the cycle to SPLADE/NER (round-15 post-round
   * finding; the starvation residual recorded in tempdoc 813 §18).
   *
   * <p>Phases 3b and 3c run AFTER 3a and open with {@link #stopRequested}, so an embed stage that
   * spends the whole cycle budget leaves them structurally unable to run: live evidence showed
   * {@code splade=0ms(ok=0)} and {@code ner=0ms(ok=0)} on EVERY cycle for 12+ minutes while SPLADE
   * sat at 1.05% coverage, and both began advancing the moment embed reached 100%. This is the
   * reservation: past the embed share, embed stops STARTING new units so the remaining budget
   * reaches the other stages.
   *
   * <p>Three deliberate properties. (1) It is NOT the {@code aborted} signal — the batch is not
   * yielding to the caller, it is rebalancing inside itself, so Phases 3b/3c still run, Phase 4
   * still writes, and the tight loop is not stopped (which would hand the reserved slice straight
   * back to the next cycle's embed stage). (2) It only applies when another stage actually has work
   * in this batch; a pure-embed backlog keeps the whole budget. (3) The {@code unitsDone == 0} floor
   * from {@link #stopRequested} applies here too — every batch does at least one embed unit, so the
   * reservation can never starve embed in the other direction.
   */
  private static boolean embedShareSpent(
      BackfillContext context, int unitsDone, boolean otherStagesHaveWork) {
    if (unitsDone == 0 || !otherStagesHaveWork) {
      return false;
    }
    return context.embedShareExhaustedSupplier().getAsBoolean();
  }

  /**
   * Whether this batch should stop starting NEW enrichment work (tempdoc 809 finding 3).
   *
   * <p>Two independent reasons, both meaning "everything after this point is wasted": the
   * scheduler's composite yield signal (shutdown, user activity, GPU claimed, pending ingest, cycle
   * budget spent), and a bulk deletion landing underneath the batch — which is how removing a
   * watched root reaches this pass ({@link IndexingCoordinator#bulkDeleteEpoch()}).
   *
   * <p>{@code unitsDone == 0} is a deliberate floor: a batch has already paid for its content and
   * status fetch by the time the first encoder unit runs, and a batch that yields having done
   * nothing would report no activity, flipping the scheduler out of combined mode and starving the
   * enrichment population instead of pacing it. Every batch completes at least one unit; only the
   * REST of the batch is interruptible.
   */
  private static boolean stopRequested(
      BackfillContext context, int unitsDone, long epochAtSelection) {
    if (unitsDone == 0) {
      return false;
    }
    return context.stopRequestedSupplier().getAsBoolean()
        || context.indexingCoordinator().bulkDeleteEpoch() != epochAtSelection;
  }

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      IndexingPacing pacing,
      Supplier<EmbeddingProvider> embeddingProviderSupplier,
      Supplier<SpladeEncoder> spladeEncoderSupplier,
      Supplier<NerService> nerServiceSupplier,
      BooleanSupplier runningSupplier,
      BooleanSupplier allowEmbeddingWritesSupplier,
      int batchSize,
      Logger log,
      boolean chunkVectorsEnabled,
      // Tempdoc 712: encode chunk docs' text into the splade FeatureField so the chunk-merge
      // sparse sub-leg (searchChunksSplade) has data. Default false. Flag off, a chunk is not a
      // SPLADE candidate at all: it is neither selected on splade_status nor enrolled nor
      // rewritten, and the PENDING its writer stamped is left standing so flipping the flag on
      // picks it up (tempdoc 931 — no status is COMPLETED/FAILED for a stage that never ran).
      boolean chunkSpladeEnabled,
      boolean lateChunkingEnabled,
      // Tempdoc 710 Wave-1.5 Move 4 item 2: was the bare `chunkSlotsPerBatch = 50` local literal
      // below; measured NOT the dense-corpus chunk-only-tail throughput lever (691 §F-1 — that
      // tail is GPU-embedding-compute-bound, not cap-throttled), so this is a config surface for
      // experimentation, not a known-good throughput knob.
      int chunkSlotsPerBatch,
      java.util.ArrayDeque<String> parentIdCache,
      java.util.ArrayDeque<String> chunkIdCache,
      int[] batchesSinceCommit,
      // Tempdoc 809 finding 3: "stop starting new enrichment work" — the caller's composite of
      // shutdown, user activity, GPU yield, pending ingest and the remaining cycle budget. Read at
      // the sub-batch boundaries below, never mid-encode. A context that supplies `() -> false`
      // behaves exactly as this pass did before the checkpoints existed.
      BooleanSupplier stopRequestedSupplier,
      // Tempdoc 897: the composite stop supplier above includes the cycle deadline. A
      // single-pass late-chunking probe may spend that deadline before a null/OOM fallback can
      // record its first resumable window, but shutdown, interruption, GPU yield, pending ingest,
      // and bulk deletion must still pre-empt immediately. This supplier carries those hard stops
      // without the deadline so the fallback can distinguish them.
      BooleanSupplier hardStopRequestedSupplier,
      // Round-15 post-round finding: "the embed stage has used its reserved share of the cycle" —
      // see embedShareSpent. A context supplying `() -> false` gives embed the whole budget, i.e.
      // the pre-fix behaviour.
      BooleanSupplier embedShareExhaustedSupplier,
      // Round-15 post-round finding: cross-cycle per-window accumulator for long documents. Owned
      // by the caller so partial windowing survives the cycle boundary — see WindowedEmbedProgress.
      WindowedEmbedProgress windowedEmbedProgress,
      Set<String> publishedDocIds,
      BooleanSupplier spladeAllowedSupplier,
      Consumer<Boolean> spladeBackfillResult) {
    public BackfillContext(
        DocumentFieldOps documentFieldOps, IndexingCoordinator indexingCoordinator,
        CommitOps commitOps, IndexingPacing pacing,
        Supplier<EmbeddingProvider> embeddingProviderSupplier,
        Supplier<SpladeEncoder> spladeEncoderSupplier, Supplier<NerService> nerServiceSupplier,
        BooleanSupplier runningSupplier, BooleanSupplier allowEmbeddingWritesSupplier,
        int batchSize, Logger log, boolean chunkVectorsEnabled, boolean chunkSpladeEnabled,
        boolean lateChunkingEnabled, int chunkSlotsPerBatch,
        java.util.ArrayDeque<String> parentIdCache, java.util.ArrayDeque<String> chunkIdCache,
        int[] batchesSinceCommit, BooleanSupplier stopRequestedSupplier,
        BooleanSupplier hardStopRequestedSupplier, BooleanSupplier embedShareExhaustedSupplier,
        WindowedEmbedProgress windowedEmbedProgress) {
      this(documentFieldOps, indexingCoordinator, commitOps, pacing, embeddingProviderSupplier,
          spladeEncoderSupplier, nerServiceSupplier, runningSupplier, allowEmbeddingWritesSupplier,
          batchSize, log, chunkVectorsEnabled, chunkSpladeEnabled, lateChunkingEnabled,
          chunkSlotsPerBatch, parentIdCache, chunkIdCache, batchesSinceCommit, stopRequestedSupplier,
          hardStopRequestedSupplier, embedShareExhaustedSupplier, windowedEmbedProgress,
          new HashSet<>(), () -> true, success -> {});
    }
  }

  /**
   * Outcome of one {@link #processCombinedBackfill} call (tempdoc 710 Move 2 item 4).
   *
   * <p>{@code OperationalMetrics.recordStageTiming}/{@code recordEnrichmentCompleted}/{@code
   * recordBatchTiming} were previously called from a {@code finally} block INSIDE this method —
   * the sole caller, which meant individual-backfill-mode counters froze (710 S-B3 finding).
   * Recording moves to {@link BackfillScheduler} (the only component that knows which pass ran);
   * this record carries exactly what that {@code finally} block used to read directly.
   *
   * @param wroteAnything the original return value ({@code written > 0}) — ACTIVITY, not progress:
   *     a document that is rewritten every batch without ever advancing a stage pins this true
   *     forever. Tempdoc 798 renamed it from {@code anyWorkDone} because that name invited exactly
   *     one wrong use: driving a loop's continue-condition (a 20-minute ingest livelock, zero
   *     diagnostics). It selects combined-vs-individual mode and feeds logging/sleep selection —
   *     it must NEVER be a loop's continue-condition. Use {@link #progressed()} or
   *     {@link #excludedDocuments()} for that.
   *     Mode selection also keeps combined mode when {@link #recordTiming()} is true: selected
   *     work may have deferred its write to protect completed SPLADE postings.
   * @param progressed whether the whole submitted RMW batch succeeded and at least one document
   *     actually ADVANCED a stage: a stage completed, an attempted stage failed (the encoder ran
   *     and its retry seam consumed the attempt), or a document with no usable content reached its
   *     terminal {@code FAILED} state. The aggregate writer result does not identify individual
   *     documents in a partial write, so partial success is conservatively reported as no progress.
   *     This is the termination signal for the tight loop — the population it drains is finite, so
   *     a loop conditioned on it cannot livelock.
   *     <p>Deliberately excluded (tempdoc 798 review F2): the INTERMEDIATE retry-count bump of the
   *     Phase-2 blank-content escalation branch. Nothing was attempted there — no encoder ran, no
   *     artifact could exist — and the document has not left the pending population, so the next
   *     tight-loop batch would be an identical no-work batch. Only its terminal step counts.
   *     Counting every bump instead would make this field equal {@link #wroteAnything()}: every
   *     mutation of a document's update map in {@link #processCombinedBackfill} increments some
   *     stage counter, so "the write was non-empty" and "a counter moved" would coincide, and the
   *     distinction this field exists to draw would collapse.
   * @param excludedDocuments whether withholding preservation updates added documents to the
   *     per-cycle exclusion set. The scheduler can select another batch without revisiting these
   *     documents, even when this batch published nothing. This is not stage completion credit.
   * @param aborted whether this batch stopped starting new enrichment work before its document set
   *     was exhausted (tempdoc 809 finding 3) — either because the caller's stop signal came up
   *     (shutdown / user activity / GPU yield / pending ingest / cycle budget) or because a bulk
   *     deletion landed underneath it, which is how removing a watched root reaches this pass.
   *     Whatever the earlier stages already produced is still written, so an aborted batch is not
   *     discarded work; the documents it never reached simply stay PENDING and are re-selected next
   *     cycle. A loop over this method must stop when it sees this: the stop condition that ended
   *     the batch is by construction still true.
   * @param recordTiming the original {@code recordTiming} flag: {@code true} once processing got
   *     past the early-return/interruption checks (mirrors the pre-move gate on whether the
   *     {@code finally} block recorded anything at all). {@code false} means every count/timing
   *     field below is a meaningless zero and must NOT be recorded. Also distinguishes selected
   *     combined work (including deferred writes) from an unavailable combined pass.
   * @param embedProcessed / spladeProcessed / nerProcessed document counts (not batch counts).
   * @param embedMs / spladeMs / nerMs / fetchMs / writeMs / totalMs per-phase wall-clock ms.
   * @param workSetSignature identifies WHICH documents this batch selected and how they were routed
   *     — the exact document-id list plus the per-stage counts the summary line prints. {@code 0}
   *     means no work-set was selected. It exists so the caller can tell a backfill that is DRAINING
   *     from one that is re-selecting the identical head of the queue every cycle: those two emit
   *     the same INFO line today, which is why 54 consecutive stalled cycles produced no diagnostic
   *     at all (round-15 post-round finding). Counts alone would collide; the id list will not.
   */
  public record CombinedOutcome(
      boolean wroteAnything,
      boolean progressed,
      boolean excludedDocuments,
      boolean aborted,
      boolean recordTiming,
      int embedProcessed,
      int spladeProcessed,
      int nerProcessed,
      long embedMs,
      long spladeMs,
      long nerMs,
      long fetchMs,
      long writeMs,
      long totalMs,
      long workSetSignature) {

    /** No pending work / interrupted before any stage ran — nothing to record. */
    public static CombinedOutcome none() {
      return new CombinedOutcome(false, false, false, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
  }

  /**
   * Processes a batch of documents through all available enrichments in a single pass. Each
   * document is read once, enriched with embedding + SPLADE + NER as needed, and written once via a
   * single batch RMW call.
   *
   * @return the batch outcome; {@code outcome.wroteAnything()} replaces the pre-Move-2 boolean
   *     return for backfillDidWork/mode selection, and {@code outcome.progressed()} is what any
   *     loop over this method continues on, along with newly excluded documents (tempdoc 798).
   */
  public static CombinedOutcome processCombinedBackfill(BackfillContext context) {
    return processCombinedBackfill(context, BulkPacePolicy.DEFAULTS);
  }

  /**
   * As {@link #processCombinedBackfill(BackfillContext)}, with the phase slice sizes that bound the
   * GPU work between two {@code pace()} calls given explicitly (Spec D §3.3).
   */
  static CombinedOutcome processCombinedBackfill(BackfillContext context, BulkPacePolicy bulkPace) {
    // Timings survive exceptions; completed counts are assigned only after publication.
    long embedMs = 0;
    long spladeMs = 0;
    long nerMs = 0;
    long fetchMs = 0;
    long writeMs = 0;
    long totalMs = 0;
    boolean recordTiming = false;
    // Tempdoc 809 finding 3 — interruption bookkeeping, hoisted with the accumulators above so the
    // catch block below can report it too.
    boolean aborted = false;
    int docsSkipped = 0;
    int unitsDone = 0;
    long epochAtSelection;
    // Round-15 post-round finding: stamped as soon as this batch's selection + routing is final, so
    // the catch path below reports the same work-set the success path does.
    long workSetSignature = 0;

    // Tempdoc 400 LR2-a: enrichment.batch parent span. Encoder ORT spans
    // emitted inside are parented under this when detailed tracing is on;
    // noop (Span.getInvalid) when off so there is no measurable overhead.
    Span enrichmentSpan = EncoderOrtRunSpans.maybeEnrichmentBatch();
    try (Scope _ = enrichmentSpan.makeCurrent()) {
    try {
      // Tempdoc 809 finding 3: the bulk-deletion epoch as it stood when this batch chose its
      // documents. Captured before the pending-ID queries so a root removal racing the selection is
      // caught too, and re-read at every checkpoint below.
      epochAtSelection = context.indexingCoordinator().bulkDeleteEpoch();

      // Phase 0: Query pending docs. Uses caches from tight loop — first call queries all
      // pending IDs (no limit), subsequent calls pop from cache. Eliminates 4 Lucene queries
      // per iteration after the first (334 Phase 10).
      EmbeddingProvider selectedEmbeddingProvider = context.embeddingProviderSupplier().get();
      boolean embedAvailable = embeddingReady(context, selectedEmbeddingProvider);
      boolean spladeConfigured = context.spladeEncoderSupplier().get() != null;
      boolean spladeAvailable = spladeConfigured
          && context.spladeAllowedSupplier().getAsBoolean();
      boolean nerAvailable =
          context.nerServiceSupplier().get() != null
              && context.nerServiceSupplier().get().isAvailable();

      // Populate parent cache on first call (or when drained)
      if (context.parentIdCache().isEmpty()) {
        List<List<String>> stagePending = new ArrayList<>();
        if (embedAvailable) {
          stagePending.add(
              context
                  .documentFieldOps()
                  .queryDocIdsByField(
                      SchemaFields.EMBEDDING_STATUS,
                      SchemaFields.EMBEDDING_STATUS_PENDING,
                      Integer.MAX_VALUE));
        }
        if (spladeAvailable) {
          // ChunkDocumentWriter stamps splade_status=PENDING on every chunk whatever the
          // chunk-SPLADE flag says, so with the flag off that status is not a work signal: the
          // stage will never run for those documents, and selecting them hands each batch slots it
          // can only rewrite. Excluding them here is what makes the pass converge — a selected
          // chunk with no runnable stage is neither progress nor terminal, so the tight loop's
          // `progressed` continue-condition goes false while the population never shrinks
          // (tempdoc 931: 4,632 rewrite-only escalations across 231 zero-advancement cycles).
          stagePending.add(
              context.chunkSpladeEnabled()
                  ? context
                      .documentFieldOps()
                      .queryDocIdsByField(
                          SchemaFields.SPLADE_STATUS,
                          SchemaFields.SPLADE_STATUS_PENDING,
                          Integer.MAX_VALUE)
                  : context
                      .documentFieldOps()
                      .queryNonChunkDocIdsByField(
                          SchemaFields.SPLADE_STATUS,
                          SchemaFields.SPLADE_STATUS_PENDING,
                          Integer.MAX_VALUE));
        }
        if (nerAvailable) {
          stagePending.add(
              context
                  .documentFieldOps()
                  .queryDocIdsByField(
                      SchemaFields.NER_STATUS,
                      SchemaFields.NER_STATUS_PENDING,
                      Integer.MAX_VALUE));
        }
        Set<String> allPending = new LinkedHashSet<>();
        int longest = stagePending.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < longest; i++) {
          for (List<String> ids : stagePending) {
            if (i < ids.size()) allPending.add(ids.get(i));
          }
        }
        allPending.removeAll(context.publishedDocIds());
        context.parentIdCache().addAll(allPending);
      }

      // Populate chunk cache on first call (or when drained)
      int chunkSlotsPerBatch = context.chunkSlotsPerBatch();
      if (context.chunkIdCache().isEmpty() && embedAvailable && context.chunkVectorsEnabled()) {
        context
            .chunkIdCache()
            .addAll(
                context
                    .documentFieldOps()
                    .queryDocIdsByField(
                        SchemaFields.CHUNK_EMBEDDING_STATUS,
                        SchemaFields.EMBEDDING_STATUS_PENDING,
                        Integer.MAX_VALUE));
      }

      context.parentIdCache().removeAll(context.publishedDocIds());
      context.chunkIdCache().removeAll(context.publishedDocIds());
      if (context.parentIdCache().isEmpty() && context.chunkIdCache().isEmpty()) {
        return CombinedOutcome.none();
      }

      // Pop batchSize parents + chunkSlots chunks from caches
      List<String> pendingIds = new ArrayList<>(context.batchSize() + chunkSlotsPerBatch);
      for (int i = 0; i < context.batchSize() && !context.parentIdCache().isEmpty(); i++) {
        pendingIds.add(context.parentIdCache().poll());
      }
      Set<String> chunkDocIds = new LinkedHashSet<>();
      for (int i = 0; i < chunkSlotsPerBatch && !context.chunkIdCache().isEmpty(); i++) {
        String id = context.chunkIdCache().poll();
        chunkDocIds.add(id);
        if (!pendingIds.contains(id)) pendingIds.add(id);
      }

      pendingIds.removeAll(context.documentFieldOps().excludedProcessingIds(pendingIds));
      chunkDocIds.retainAll(pendingIds);
      if (pendingIds.isEmpty()) {
        return CombinedOutcome.none();
      }

      long t0 = System.nanoTime();

      // Phase 1: Batch content fetch (single searcher, all docs)
      EnrichmentBatch batch = new EnrichmentBatch(context.documentFieldOps(), context.indexingCoordinator(), pendingIds);
      Map<String, String> contentByDocId = batch.contents();

      Map<String, Map<String, String>> batchedFields = batch.fields();

      // Phase 2: Collect docs needing each enrichment
      List<String> embedDocIds = new ArrayList<>();
      List<String> embedContents = new ArrayList<>();
      List<String> lateChunkingDocIds = new ArrayList<>();
      List<String> lateChunkingContents = new ArrayList<>();
      // Parents in this set already belong in resumable windowing: either a prior cycle recorded
      // partial windows, or this cycle's single-pass attempt proved null/arena-OOM. They must not
      // pay documentWindowCount's second tokenization before reaching the window seam.
      Set<String> directWindowedDocIds = new HashSet<>();
      List<String> spladeDocIds = new ArrayList<>();
      // Non-stored postings must accompany any other stage's RMW of a completed document.
      // Enrollment is deferred until those other outcomes exist.
      Set<String> completedSpladeIds = new HashSet<>();

      Map<String, Map<String, Object>> updatesByDocId = new LinkedHashMap<>();
      Set<String> stageAdvancedDocIds = new HashSet<>();
      Set<String> completedWindowDocIds = new HashSet<>();

      // Track which doc IDs are chunk docs (need CHUNK_VECTOR instead of VECTOR)
      Set<String> chunkIdsInBatch = new HashSet<>();

      // Tempdoc 798 review F2: blank-content escalations that reached their terminal FAILED state
      // this batch. Those documents leave the pending population, so they are progress; the
      // intermediate retry bumps below are not (see CombinedOutcome#progressed).

      // Tempdoc 803 blocker: a document that reaches the terminal SPLADE FAILED state removes
      // itself from the pending population forever and fails the readiness gate for the whole
      // index, but nothing named it — 803 could report "1 of 5,408 failed" and not which one.
      // Every terminal SPLADE escalation below records `docId(reason)` here and the batch logs
      // them once at WARN.
      List<String> spladeTerminalFailures = new ArrayList<>();

      // Round-15 post-round finding: how many documents Phase 3c would actually run NER on. Uses
      // Phase 3c's OWN predicate (absent status defaults to PENDING there) rather than the raw read
      // used for embed/SPLADE enrollment, so the budget reservation below fires in exactly the
      // scenario where the reserved stage has work — a wrong-gate here would either starve NER
      // anyway or hand embed a shorter budget for nothing.
      int nerCandidates = 0;

      for (String docId : pendingIds) {
        updatesByDocId.put(docId, new HashMap<>());
        Map<String, String> docFields = batchedFields.getOrDefault(docId, Map.of());
        boolean isChunkDoc =
            chunkDocIds.contains(docId)
                || "true".equalsIgnoreCase(docFields.get(SchemaFields.IS_CHUNK));

        if (isChunkDoc) {
          // Chunk docs can enter through either the dedicated embedding cache or the ordinary
          // SPLADE-status cache. Enrol only the stages whose chunk-specific status is present and
          // pending; never manufacture parent VECTOR/NER state on a chunk.
          String chunkContent = contentByDocId.get(docId);
          String chunkEmbeddingStatus = docFields.get(SchemaFields.CHUNK_EMBEDDING_STATUS);
          String chunkSpladeStatus = docFields.get(SchemaFields.SPLADE_STATUS);
          if (chunkContent == null || chunkContent.isBlank()) {
            // Parent content plus the stored offset law should always produce a non-blank slice, so
            // a blank read here is a fetch/consistency anomaly, not a legitimately empty chunk.
            // Marking CHUNK_EMBEDDING_STATUS=COMPLETED would claim a
            // chunk_vector that will never exist — a silent data-less COMPLETED (the F-032 "status
            // lies" class). Escalate via the retry-count seam instead: retry next cycle, FAIL at
            // max — never COMPLETED-without-data.
            Map<String, Object> updates = updatesByDocId.get(docId);
            if (embedAvailable && context.chunkVectorsEnabled()
                && SchemaFields.EMBEDDING_STATUS_PENDING.equals(chunkEmbeddingStatus)
                && embeddingReady(context, selectedEmbeddingProvider)) {
              Map<String, Object> escalation =
                  EmbeddingBackfillOps.computeChunkEmbeddingFailureUpdate(
                      parseRetryCountOrZero(
                          docFields.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT)));
              if (escalation.containsKey(SchemaFields.CHUNK_EMBEDDING_STATUS)) {
                stageAdvancedDocIds.add(docId);
              }
              updates.putAll(escalation);
            }
            // Same flag gate as the enrollment below: with chunk SPLADE off the stage never ran,
            // so it has no retry seam to escalate and no FAILED to claim.
            if (spladeAvailable
                && context.chunkSpladeEnabled()
                && SchemaFields.SPLADE_STATUS_PENDING.equals(chunkSpladeStatus)) {
              Map<String, Object> escalation =
                  SpladeBackfillOps.computeSpladeFailureUpdate(
                      parseRetryCountOrZero(docFields.get(SchemaFields.SPLADE_RETRY_COUNT)));
              if (escalation.containsKey(SchemaFields.SPLADE_STATUS)) {
                stageAdvancedDocIds.add(docId);
                spladeTerminalFailures.add(docId + "(blank-chunk-content)");
              }
              updates.putAll(escalation);
            }
            continue;
          }
          if (embedAvailable && context.chunkVectorsEnabled()
              && SchemaFields.EMBEDDING_STATUS_PENDING.equals(chunkEmbeddingStatus)) {
            embedDocIds.add(docId);
            embedContents.add(chunkContent);
            chunkIdsInBatch.add(docId);
          }
          if (spladeAvailable && context.chunkSpladeEnabled()
              && SchemaFields.SPLADE_STATUS_PENDING.equals(chunkSpladeStatus)) {
            spladeDocIds.add(docId);
          }
          if (context.chunkSpladeEnabled()
              && SchemaFields.SPLADE_STATUS_COMPLETED.equals(chunkSpladeStatus)) {
            completedSpladeIds.add(docId);
          }
          continue;
        }

        // Parent doc: full enrichment (embed + SPLADE + NER)
        String content = contentByDocId.get(docId);

        // Read the RAW status: an ABSENT status means the stage does not apply to this document,
        // not that it is PENDING. Chunk docs are written with SPLADE_STATUS/CHUNK_EMBEDDING_STATUS
        // but no EMBEDDING_STATUS and no NER_STATUS (ChunkDocumentWriter), and reach this parent
        // branch whenever the splade-status query pulls them in. Defaulting absent to PENDING made
        // the blank-content branch below manufacture an EMBEDDING_STATUS=COMPLETED on a chunk doc
        // that has no vector — a data-less COMPLETED the RMW reset policy (tempdoc 711) then
        // resets straight back to PENDING, forever (the F-032 "status lies" class).
        String embedStatus = docFields.get(SchemaFields.EMBEDDING_STATUS);
        String spladeStatus = docFields.get(SchemaFields.SPLADE_STATUS);
        String nerStatus = docFields.get(SchemaFields.NER_STATUS);

        if (content == null || content.isBlank()) {
          // No content means no artifact can be produced for any stage that applies here. Escalate
          // through each stage's retry-count seam (retry next cycle, FAILED at max) exactly as
          // EmbeddingBackfillOps does for the identical condition — never COMPLETED-without-data.
          Map<String, Object> updates = updatesByDocId.get(docId);
          if (embedAvailable && SchemaFields.EMBEDDING_STATUS_PENDING.equals(embedStatus)) {
            Map<String, Object> escalation =
                EmbeddingBackfillOps.computeEmbeddingFailureUpdate(
                    parseRetryCountOrZero(docFields.get(SchemaFields.EMBEDDING_RETRY_COUNT)));
            if (escalation.containsKey(SchemaFields.EMBEDDING_STATUS)) {
              stageAdvancedDocIds.add(docId);
            }
            updates.putAll(escalation);
          }
          if (nerAvailable && SchemaFields.NER_STATUS_PENDING.equals(nerStatus)) {
            Map<String, Object> escalation =
                NerBackfillOps.computeNerFailureUpdate(
                    parseRetryCountOrZero(docFields.get(SchemaFields.NER_RETRY_COUNT)));
            if (escalation.containsKey(SchemaFields.NER_STATUS)) {
              stageAdvancedDocIds.add(docId);
            }
            updates.putAll(escalation);
          }
          if (spladeAvailable && SchemaFields.SPLADE_STATUS_PENDING.equals(spladeStatus)) {
            // No text means no postings can be produced. A chunk never reaches here — the IS_CHUNK
            // branch above owns every chunk and reconstructs its slice from the parent — so this is
            // a parent whose CONTENT read came back blank, escalated like any other artifact-less
            // outcome rather than claiming COMPLETED with no postings.
            Map<String, Object> escalation =
                SpladeBackfillOps.computeSpladeFailureUpdate(
                    parseRetryCountOrZero(docFields.get(SchemaFields.SPLADE_RETRY_COUNT)));
            if (escalation.containsKey(SchemaFields.SPLADE_STATUS)) {
              stageAdvancedDocIds.add(docId);
              spladeTerminalFailures.add(docId + "(blank-content)");
            }
            updates.putAll(escalation);
          }
          continue;
        }

        // Tempdoc 691 forensics fold-in: a chunked parent (one with PARENT_DOC_ID-matching chunk
        // docs) routes to the late-chunking single-pass embed strategy in Phase 3a-i below instead
        // of the ordinary windowed batch — collected into a separate list here so that sub-phase
        // can try embedWithSpans first and only fold the doc into embedDocIds/embedContents on a
        // null/arena-OOM outcome. This check is embed-stage-only — SPLADE/NER enrollment below is
        // untouched, since those stages are collected independently of embedDocIds/embedContents
        // (no shared enrollment gate to decouple). Whichever strategy serves the vector, the
        // result lands in this same doc's entry in updatesByDocId — one bundled write either way.
        boolean isLateChunkingParent =
            context.lateChunkingEnabled()
                && embedAvailable
                && SchemaFields.EMBEDDING_STATUS_PENDING.equals(embedStatus)
                && hasChunkDocs(context, docId);
        if (isLateChunkingParent) {
          if (context.windowedEmbedProgress().nextWindow(docId, content) > 0) {
            embedDocIds.add(docId);
            embedContents.add(content);
            directWindowedDocIds.add(docId);
          } else {
            lateChunkingDocIds.add(docId);
            lateChunkingContents.add(content);
          }
        } else if (embedAvailable && SchemaFields.EMBEDDING_STATUS_PENDING.equals(embedStatus)) {
          embedDocIds.add(docId);
          embedContents.add(content);
        }
        if (spladeAvailable && SchemaFields.SPLADE_STATUS_PENDING.equals(spladeStatus)) {
          spladeDocIds.add(docId);
        }
        if (SchemaFields.SPLADE_STATUS_COMPLETED.equals(spladeStatus)) {
          completedSpladeIds.add(docId);
        }
        if (nerAvailable
            && SchemaFields.NER_STATUS_PENDING.equals(
                docFields.getOrDefault(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING))) {
          nerCandidates++;
        }
      }

      fetchMs = (System.nanoTime() - t0) / 1_000_000;

      // Check for interruption. Tempdoc 885 item 3: foreground load paces here, it does not
      // cancel the batch.
      context.pacing().pace();
      if (!context.runningSupplier().getAsBoolean()) {
        return CombinedOutcome.none();
      }

      // Past early returns — any work from here should be recorded.
      recordTiming = true;

      // Round-15 post-round finding: does any stage other than embed have work in THIS batch? Only
      // then does embed hand back part of the cycle (see embedShareSpent).
      // Preservation is part of the pending stage's own RMW, not independent work that may
      // reserve its budget and defer the very embedding it exists to preserve.
      boolean otherStagesHaveWork = nerCandidates > 0 || !spladeDocIds.isEmpty();

      workSetSignature =
          computeWorkSetSignature(
              pendingIds,
              embedDocIds.size() + lateChunkingDocIds.size(),
              spladeDocIds.size(),
              chunkIdsInBatch.size(),
              nerCandidates);

      // Spec D §3.3: the pace() above is the only yield point the batch used to have, so one batch
      // ran 2-3 s of GPU work before foreground load could slow it (M5). Each GPU phase below now
      // paces every bulkPhaseDocs documents, and every phase boundary is a pace point, so no stretch
      // between two pace() calls exceeds bulkPaceTargetMs at M5's per-document costs.
      BulkPacePolicy.PhasePacer pacer = new BulkPacePolicy.PhasePacer(context.pacing());
      pacer.startPhase(bulkPace.embeddingPhaseDocs());

      // Phase 3a: Batch embedding
      long tEmbed = System.nanoTime();
      int embedFailed = 0;
      int singlePassProcessed = 0;
      int longDocWindowed = 0;
      int arenaOomWindowed = 0;
      // Round-15 post-round finding, resumable-windowing bookkeeping.
      int windowUnitsDone = 0;
      int windowDocsCompleted = 0;
      int windowDocsResumable = 0;
      int embedDeferredForOtherStages = 0;

      // Phase 3a-i: Late-chunking single-pass embed (tempdoc 691 forensics fold-in). Tries one
      // whole-document forward pass per chunked parent. A null result (content over the raised
      // single-pass limit) or a GPU BFC-arena OOM folds the doc INLINE into the ordinary windowed
      // batch below (embedDocIds/embedContents) rather than deferring to a different RMW pass —
      // every doc still resolves within this pass's single bundled write. A non-arena-OOM failure
      // escalates the parent directly through the same retry-count/FAILED-at-max helper the
      // windowed batch failure path below uses.
      if (!lateChunkingDocIds.isEmpty() && embedAvailable) {
        EmbeddingProvider lateChunkingProvider = context.embeddingProviderSupplier().get();
        for (int i = 0; i < lateChunkingDocIds.size(); i++) {
          // One whole-document forward pass per iteration — already the atomic unit, so the
          // checkpoint costs nothing but a volatile read (tempdoc 809 finding 3).
          if (stopRequested(context, unitsDone, epochAtSelection)) {
            aborted = true;
            docsSkipped += lateChunkingDocIds.size() - i;
            break;
          }
          if (embedShareSpent(context, unitsDone, otherStagesHaveWork)) {
            embedDeferredForOtherStages += lateChunkingDocIds.size() - i;
            break;
          }
          if (!embeddingReady(context, lateChunkingProvider)) break;
          String lcDocId = lateChunkingDocIds.get(i);
          String lcContent = lateChunkingContents.get(i);
          pacer.beforeUnit(1);
          try {
            EmbeddingService.ChunkedEmbedding result =
                lateChunkingProvider.embedWithSpans(lcContent, NO_SPANS);
            if (result != null && result.primaryVector().length > 0) {
              Map<String, Object> updates = updatesByDocId.get(lcDocId);
              updates.put(SchemaFields.VECTOR, result.primaryVector());
              updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
              updates.put(SchemaFields.EMBEDDING_RETRY_COUNT, "0");
              singlePassProcessed++;
              stageAdvancedDocIds.add(lcDocId);
            } else {
              // Content exceeds the raised single-pass limit — fold into the windowed batch.
              embedDocIds.add(lcDocId);
              embedContents.add(lcContent);
              directWindowedDocIds.add(lcDocId);
              longDocWindowed++;
            }
          } catch (Exception e) {
            if (!embeddingReady(context, lateChunkingProvider)
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
              embedDocIds.add(lcDocId);
              embedContents.add(lcContent);
              directWindowedDocIds.add(lcDocId);
              arenaOomWindowed++;
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
              stageAdvancedDocIds.add(lcDocId);
            }
          }
          pacer.afterUnit(1);
          unitsDone++;
        }
      }

      // Phase 3a-ii: resumable per-window embed for documents longer than the encoder's context
      // window (round-15 post-round finding). These are the documents the pre-fix pass could never
      // finish: the late-chunking fallback above appends them at the TAIL of embedDocIds, behind
      // every short parent and every chunk doc, so an 8-DOCUMENT slice loop under a 5 s budget
      // reached them last or not at all — and when it did, all of a document's windows ran inside
      // one uninterruptible call whose partial results were discarded on interruption. Here they go
      // FIRST, one window slice at a time, and every embedded window is folded into the cross-cycle
      // accumulator, so an interrupted document resumes at its next window instead of window 0.
      //
      // Documents whose provider reports a single window (every short document, every chunk doc,
      // and EVERY document when the provider does not expose window granularity — the interface
      // default) stay on the historical whole-document batch path below, unchanged.
      List<String> windowedDocIds = new ArrayList<>();
      List<String> windowedContents = new ArrayList<>();
      if (!embedDocIds.isEmpty() && embedAvailable) {
        EmbeddingProvider windowProbe = context.embeddingProviderSupplier().get();
        List<String> singleWindowDocIds = new ArrayList<>(embedDocIds.size());
        List<String> singleWindowContents = new ArrayList<>(embedContents.size());
        for (int i = 0; i < embedDocIds.size(); i++) {
          if (!embeddingReady(context, windowProbe)) break;
          if (directWindowedDocIds.contains(embedDocIds.get(i))
              || windowProbe.documentWindowCount(embedContents.get(i)) > 1) {
            windowedDocIds.add(embedDocIds.get(i));
            windowedContents.add(embedContents.get(i));
          } else {
            singleWindowDocIds.add(embedDocIds.get(i));
            singleWindowContents.add(embedContents.get(i));
          }
        }
        if (!windowedDocIds.isEmpty()) {
          embedDocIds = singleWindowDocIds;
          embedContents = singleWindowContents;
        }
      }

      if (!windowedDocIds.isEmpty() && embedAvailable) {
        EmbeddingProvider provider = context.embeddingProviderSupplier().get();
        WindowedEmbedProgress progress = context.windowedEmbedProgress();
        for (int i = 0; i < windowedDocIds.size(); i++) {
          String docId = windowedDocIds.get(i);
          boolean directFallback = directWindowedDocIds.contains(docId);
          // A null/OOM classification is not resumable progress. Ignore only the deadline it may
          // have spent until one real window is recorded; hard preemption and deletion still win.
          if (directFallback
              && windowUnitsDone == 0
              && (context.hardStopRequestedSupplier().getAsBoolean()
                  || context.indexingCoordinator().bulkDeleteEpoch() != epochAtSelection)) {
            aborted = true;
            docsSkipped += windowedDocIds.size() - i;
            break;
          }
          int schedulingUnitsDone = directFallback ? windowUnitsDone : unitsDone;
          if (stopRequested(context, schedulingUnitsDone, epochAtSelection)) {
            aborted = true;
            docsSkipped += windowedDocIds.size() - i;
            break;
          }
          if (embedShareSpent(context, schedulingUnitsDone, otherStagesHaveWork)) {
            embedDeferredForOtherStages += windowedDocIds.size() - i;
            break;
          }
          if (!embeddingReady(context, provider)) break;
          String content = windowedContents.get(i);
          boolean isChunk = chunkIdsInBatch.contains(docId);
          // Scoped to THIS document, deliberately: `aborted` is sticky across phases (Phase 3a-i
          // may already have set it), so reading it to decide whether the windowed lane stopped
          // would end the lane on a stale flag and mis-report every remaining document as skipped.
          boolean stoppedOnThisDocument = false;
          boolean abortedOnThisDocument = false;
          try {
            int nextWindow = progress.nextWindow(docId, content);
            while (!progress.isComplete(docId)) {
              if (!embeddingReady(context, provider)) {
                stoppedOnThisDocument = true;
                break;
              }
              // A window is one encoder forward pass of at most a document's length, so the
              // embedding slice counts windows here; counted whether or not the call succeeds.
              pacer.beforeUnit(EMBED_WINDOW_SLICE);
              EmbeddingProvider.WindowSlice slice;
              try {
                slice = provider.embedDocumentWindows(content, nextWindow, EMBED_WINDOW_SLICE);
              } finally {
                pacer.afterUnit(EMBED_WINDOW_SLICE);
              }
              if (slice == null || slice.vectors().isEmpty()) {
                // Either the provider stopped exposing windows mid-batch or it has no window at
                // this index — neither can produce a vector, so escalate through the same retry
                // seam every other embed failure uses rather than looping.
                throw new IllegalStateException(
                    "windowed embed returned no vectors at window " + nextWindow);
              }
              unitsDone++;
              windowUnitsDone++;
              int advancedTo =
                  progress.record(
                      docId, content, slice.totalWindows(), nextWindow, slice.vectors());
              if (advancedTo <= nextWindow) {
                // The accumulator did not advance (every returned window was unusable, or the
                // provider answered a different range than it was asked for). Looping would spin
                // the GPU forever on a document that can never complete — the exact non-termination
                // class this work exists to remove — so escalate through the retry seam instead.
                throw new IllegalStateException(
                    "windowed embed made no progress at window " + nextWindow);
              }
              nextWindow = advancedTo;
              if (nextWindow >= slice.totalWindows()) {
                break;
              }
              if (stopRequested(context, unitsDone, epochAtSelection)) {
                aborted = true;
                abortedOnThisDocument = true;
                stoppedOnThisDocument = true;
                break;
              }
              if (embedShareSpent(context, unitsDone, otherStagesHaveWork)) {
                stoppedOnThisDocument = true;
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
              stageAdvancedDocIds.add(docId);
              completedWindowDocIds.add(docId);
            } else {
              // Interrupted part-way. The document stays PENDING with no update of any kind — a
              // partial mean-pool is NOT this document's embedding and must never be written — but
              // its windows are now held in the accumulator, so the next cycle continues instead of
              // starting over. This is the whole fix.
              windowDocsResumable++;
            }
          } catch (Exception e) {
            context
                .log()
                .warn(
                    "Combined backfill: windowed embed failed for {}: {}", docId, e.getMessage());
            if (!embeddingReady(context, provider) || SpladeBackfillOps.isSystemicFailure(e)) break;
            progress.forget(docId);
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
            stageAdvancedDocIds.add(docId);
          }
          if (stoppedOnThisDocument) {
            int remaining = windowedDocIds.size() - i - 1;
            if (abortedOnThisDocument) {
              docsSkipped += remaining;
            } else {
              embedDeferredForOtherStages += remaining;
            }
            break;
          }
        }
      }

      if (!embedDocIds.isEmpty() && embedAvailable) {
        EmbeddingProvider provider = context.embeddingProviderSupplier().get();
        // Tempdoc 809 finding 3: encode in EMBED_ENCODE_SLICE-document slices instead of one call
        // over the whole batch. Same native forward passes (the encoder sub-batches at exactly this
        // size anyway), but the loop can now be left between them — which is what makes the
        // scheduler's cycle budget, ingest preemption and root-removal cancellation enforceable
        // rather than aspirational.
        for (int sliceStart = 0; sliceStart < embedDocIds.size(); sliceStart += EMBED_ENCODE_SLICE) {
          if (stopRequested(context, unitsDone, epochAtSelection)) {
            aborted = true;
            docsSkipped += embedDocIds.size() - sliceStart;
            break;
          }
          if (embedShareSpent(context, unitsDone, otherStagesHaveWork)) {
            embedDeferredForOtherStages += embedDocIds.size() - sliceStart;
            break;
          }
          int sliceEnd = Math.min(sliceStart + EMBED_ENCODE_SLICE, embedDocIds.size());
          List<String> sliceDocIds = embedDocIds.subList(sliceStart, sliceEnd);
          pacer.beforeUnit(sliceDocIds.size());
          if (!embeddingReady(context, provider)) break;
          List<float[]> vectors;
          try {
            vectors = provider.embedDocumentBatch(embedContents.subList(sliceStart, sliceEnd));
          } catch (Exception failure) {
            if (!embeddingReady(context, provider) || SpladeBackfillOps.isSystemicFailure(failure)) break;
            vectors = null;
          } finally {
            pacer.afterUnit(sliceDocIds.size());
          }
          unitsDone++;
          if (!embeddingReady(context, provider)) break;
          boolean fallback = vectors == null || vectors.size() != sliceDocIds.size();
          if (fallback) vectors = new ArrayList<>(java.util.Collections.nCopies(sliceDocIds.size(), null));
          for (int i = 0; i < sliceDocIds.size(); i++) {
            if (!embeddingReady(context, provider)) break;
            if (fallback) {
              // The failed batch is not a document attempt. Reach at least one individual call,
              // even when it consumed the deadline; hard stops still pre-empt immediately.
              if (context.hardStopRequestedSupplier().getAsBoolean()
                  || context.indexingCoordinator().bulkDeleteEpoch() != epochAtSelection
                  || (i > 0 && stopRequested(context, unitsDone, epochAtSelection))) {
                aborted = true;
                docsSkipped += sliceDocIds.size() - i;
                break;
              }
              pacer.beforeUnit(1);
              try {
                vectors.set(i, provider.embedDocument(embedContents.get(sliceStart + i),
                    InferenceRequest.background()));
              } catch (Exception failure) {
                if (!embeddingReady(context, provider) || SpladeBackfillOps.isSystemicFailure(failure)) break;
              } finally {
                pacer.afterUnit(1);
              }
              if (!embeddingReady(context, provider)) break;
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
              stageAdvancedDocIds.add(eid);
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
              stageAdvancedDocIds.add(eid);
            }
          }
        }
      }
      embedMs = (System.nanoTime() - tEmbed) / 1_000_000;

      // Phase 3b: NER (per-doc GPU inference — batching tested in item 22, regressed due to
      // padding waste exceeding the 467us/call overhead. Per-doc at 2.0ms/call is near-optimal.)
      long tNer = System.nanoTime();
      int nerFailed = 0;
      if (nerAvailable) {
        NerService nerService = context.nerServiceSupplier().get();
        // Tempdoc 809 finding 3: the eligible set is materialised first (same predicate, same
        // order, previously inline `continue`s) so that abandoning the stage part-way can report
        // exactly how many documents it skipped, the way the embed and SPLADE stages can.
        List<String> nerDocIds = new ArrayList<>();
        for (String docId : pendingIds) {
          Map<String, String> docFields = batchedFields.getOrDefault(docId, Map.of());
          if ("true".equalsIgnoreCase(docFields.get(SchemaFields.IS_CHUNK))) {
            continue;
          }
          String nerSt = docFields.getOrDefault(
              SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING);
          if (!SchemaFields.NER_STATUS_PENDING.equals(nerSt)) {
            continue;
          }
          String content = contentByDocId.get(docId);
          if (content == null || content.isBlank()) {
            continue;
          }
          nerDocIds.add(docId);
        }
        if (!nerDocIds.isEmpty()) {
          pacer.startPhase(bulkPace.nerPhaseDocs());
        }
        for (int n = 0; n < nerDocIds.size(); n++) {
          // Per-document GPU inference — already the atomic unit (batching regressed, item 22).
          if (stopRequested(context, unitsDone, epochAtSelection)) {
            aborted = true;
            docsSkipped += nerDocIds.size() - n;
            break;
          }
          String docId = nerDocIds.get(n);
          Map<String, String> docFields = batchedFields.getOrDefault(docId, Map.of());
          String content = contentByDocId.get(docId);
          pacer.beforeUnit(1);
          try {
            List<NerResult> nerBatch = nerService.extractEntitiesBatch(List.of(content));
            NerResult result = nerBatch.isEmpty() ? NerResult.EMPTY : nerBatch.get(0);
            Map<String, Object> updates = updatesByDocId.get(docId);
            updates.put(
                SchemaFields.NER_STATUS,
                result.isEmpty()
                    ? SchemaFields.NER_STATUS_COMPLETED_EMPTY
                    : SchemaFields.NER_STATUS_COMPLETED);
            updates.put(SchemaFields.NER_RETRY_COUNT, "0");
            NerBackfillOps.applyEntityFieldUpdates(updates, result);
            stageAdvancedDocIds.add(docId);
          } catch (Exception e) {
            context
                .log()
                .warn("Combined backfill: NER failed for {}: {}", docId, e.getMessage());
            // Tempdoc 700: escalate via the same pure helper NerBackfillOps.handleNerFailure
            // uses. The try block above never touched updatesByDocId before throwing, so it's
            // safe to write the failure update directly (no partial-success clobber risk, unlike
            // the SPLADE batch-catch above).
            int currentRetryCount =
                parseRetryCountOrZero(docFields.get(SchemaFields.NER_RETRY_COUNT));
            updatesByDocId
                .get(docId)
                .putAll(NerBackfillOps.computeNerFailureUpdate(currentRetryCount));
            nerFailed++;
            stageAdvancedDocIds.add(docId);
          }
          pacer.afterUnit(1);
          unitsDone++;
        }
      }
      nerMs = (System.nanoTime() - tNer) / 1_000_000;

      // Completed postings are non-stored: derive them only for documents with another outcome.
      // The write depends on preservation, so its deadline cannot discard an earned stage outcome.
      List<String> preservationIds = new ArrayList<>();
      for (String docId : pendingIds) {
        if (spladeConfigured && completedSpladeIds.contains(docId)
            && !updatesByDocId.get(docId).isEmpty()) {
          preservationIds.add(docId);
        }
      }
      // Phase 3c: SPLADE preservation, followed by pending SPLADE slices.
      long tSplade = System.nanoTime();
      int spladeFailed = 0;
      boolean systemicSpladeFailure = false;
      boolean attemptedSplade = false;
      pacer.startPhase(bulkPace.spladePhaseDocs());
      for (List<String> candidates : List.of(preservationIds, spladeDocIds)) {
        if (systemicSpladeFailure) break;
        boolean preserving = candidates == preservationIds;
        for (int start = 0; start < candidates.size(); start += bulkPace.spladePhaseDocs()) {
          if (context.hardStopRequestedSupplier().getAsBoolean()
              || context.indexingCoordinator().bulkDeleteEpoch() != epochAtSelection
              || (!preserving && stopRequested(context, unitsDone, epochAtSelection))) {
            aborted = true;
            docsSkipped += candidates.size() - start;
            break;
          }
          if (!context.spladeAllowedSupplier().getAsBoolean()) break;
          SpladeEncoder encoder = context.spladeEncoderSupplier().get();
          if (encoder == null) {
            systemicSpladeFailure = true;
            context.spladeBackfillResult().accept(false);
            break;
          }
          int end = Math.min(start + bulkPace.spladePhaseDocs(), candidates.size());
          List<String> sliceIds = candidates.subList(start, end);
          List<String> texts = sliceIds.stream().map(contentByDocId::get).toList();
          pacer.beforeUnit(sliceIds.size());
          try {
            attemptedSplade = true;
            List<Map<String, Float>> vectors = encoder.encodeBatch(texts);
            if (vectors == null || vectors.size() != sliceIds.size()) {
              throw new IllegalStateException("SPLADE batch result size mismatch");
            }
            for (int i = 0; i < sliceIds.size(); i++) {
              String id = sliceIds.get(i);
              Map<String, Float> vector = vectors.get(i);
              String status = SpladeBackfillOps.spladeStatusFor(vector);
              Map<String, Object> updates = updatesByDocId.get(id);
              updates.put(SchemaFields.SPLADE, vector);
              updates.put(SchemaFields.SPLADE_STATUS, status);
              updates.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
              stageAdvancedDocIds.add(id);
            }
          } catch (Exception failure) {
            context.log().warn("Combined backfill: SPLADE slice failed: {}", failure.getMessage());
            if (context.spladeEncoderSupplier().get() != encoder || SpladeBackfillOps.isSystemicFailure(failure)) {
              systemicSpladeFailure = true;
              context.spladeBackfillResult().accept(false);
            } else if (preserving) {
              for (String id : sliceIds) {
                if (updatesByDocId.get(id).containsKey(SchemaFields.SPLADE)) continue;
                if (context.hardStopRequestedSupplier().getAsBoolean()
                    || context.indexingCoordinator().bulkDeleteEpoch() != epochAtSelection) {
                  aborted = true;
                  break;
                }
                if (!context.spladeAllowedSupplier().getAsBoolean()) break;
                if (context.spladeEncoderSupplier().get() != encoder) {
                  systemicSpladeFailure = true;
                  context.spladeBackfillResult().accept(false);
                  break;
                }
                pacer.beforeUnit(1);
                try {
                  List<Map<String, Float>> vectors =
                      encoder.encodeBatch(List.of(contentByDocId.get(id)));
                  if (vectors == null || vectors.size() != 1) {
                    throw new IllegalStateException("SPLADE batch result size mismatch");
                  }
                  Map<String, Float> vector = vectors.get(0);
                  Map<String, Object> updates = updatesByDocId.get(id);
                  updates.put(SchemaFields.SPLADE, vector);
                  updates.put(SchemaFields.SPLADE_STATUS, SpladeBackfillOps.spladeStatusFor(vector));
                  updates.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
                  stageAdvancedDocIds.add(id);
                } catch (Exception individualFailure) {
                  context.log().warn("Combined backfill: SPLADE preservation failed for {}: {}",
                      id, individualFailure.getMessage());
                  if (context.spladeEncoderSupplier().get() != encoder
                      || SpladeBackfillOps.isSystemicFailure(individualFailure)) {
                    systemicSpladeFailure = true;
                    context.spladeBackfillResult().accept(false);
                    break;
                  }
                } finally {
                  pacer.afterUnit(1);
                  unitsDone++;
                }
              }
            } else {
              for (String id : sliceIds) {
                if (updatesByDocId.get(id).containsKey(SchemaFields.SPLADE)) continue;
                var escalation = SpladeBackfillOps.computeSpladeFailureUpdate(
                    parseRetryCountOrZero(batchedFields.getOrDefault(id, Map.of())
                        .get(SchemaFields.SPLADE_RETRY_COUNT)));
                updatesByDocId.get(id).putAll(escalation);
                stageAdvancedDocIds.add(id);
                if (escalation.containsKey(SchemaFields.SPLADE_STATUS)) {
                  spladeTerminalFailures.add(id + "(encode-failure)");
                }
                spladeFailed++;
              }
            }
            if (systemicSpladeFailure || !preserving) break;
          } finally {
            pacer.afterUnit(sliceIds.size());
            unitsDone++;
          }
        }
      }
      if (attemptedSplade && !systemicSpladeFailure) context.spladeBackfillResult().accept(true);
      spladeMs = (System.nanoTime() - tSplade) / 1_000_000;
      boolean excludedDocuments = false;
      for (String id : preservationIds) {
        Map<String, Object> updates = updatesByDocId.get(id);
        if (!updates.containsKey(SchemaFields.SPLADE)) {
          updates.clear();
          // docs/tempdocs/711-rmw-preservation-and-followup-designs.md: defer this parent
          // until the next idle cycle so it cannot crowd out healthy pending documents.
          excludedDocuments |= context.publishedDocIds().add(id);
        }
      }

      // Phase 4: Single batch write (one RMW per doc with all enrichments)
      long tWrite = System.nanoTime();
      List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();
      for (var entry : updatesByDocId.entrySet()) {
        if (!entry.getValue().isEmpty()) {
          batchUpdates.add(Map.entry(entry.getKey(), entry.getValue()));
        }
      }
      int written = 0;
      if (!batchUpdates.isEmpty()) {
        var result = batch.updateBatch(batchUpdates);
        written = result.updatedCount();
        if (written == batchUpdates.size()) {
          for (var entry : batchUpdates) context.publishedDocIds().add(entry.getKey());
        } else {
          // A partial result has no per-ID receipts. Do not hide a replacement revision; the
          // writer's existing reader can distinguish it without an additional NRT refresh.
          var current = context.documentFieldOps().getEnrichmentInputs(
              batchUpdates.stream().map(Map.Entry::getKey).toList());
          for (var entry : batchUpdates) {
            String id = entry.getKey();
            var input = current.get(id);
            if (input == null || !java.util.Objects.equals(contentByDocId.get(id), input.content())) continue;
            Map<String, String> previousFields = new HashMap<>(batchedFields.getOrDefault(id, Map.of()));
            Map<String, String> currentFields = new HashMap<>(input.fields());
            previousFields.keySet().removeAll(entry.getValue().keySet());
            currentFields.keySet().removeAll(entry.getValue().keySet());
            if (previousFields.equals(currentFields)) context.publishedDocIds().add(id);
          }
        }
      }
      boolean completeBatchWrite = written == batchUpdates.size() && written > 0;
      if (completeBatchWrite) {
        Set<String> writtenDocIds = new HashSet<>();
        for (var entry : batchUpdates) {
          writtenDocIds.add(entry.getKey());
        }
        for (String docId : completedWindowDocIds) {
          if (writtenDocIds.contains(docId)) {
            context.windowedEmbedProgress().forget(docId);
          }
        }
      }
      // The writer exposes aggregate counts only: partial publication cannot identify successes.
      int embedProcessed = completeBatchWrite ? (int) batchUpdates.stream().filter(entry ->
          entry.getValue().containsKey(SchemaFields.VECTOR)
              || entry.getValue().containsKey(SchemaFields.CHUNK_VECTOR)).count() : 0;
      int spladeProcessed = completeBatchWrite ? (int) batchUpdates.stream().filter(entry ->
          entry.getValue().containsKey(SchemaFields.SPLADE)).count() : 0;
      int nerProcessed = completeBatchWrite ? (int) batchUpdates.stream().filter(entry ->
          SchemaFields.NER_STATUS_COMPLETED.equals(entry.getValue().get(SchemaFields.NER_STATUS))
              || SchemaFields.NER_STATUS_COMPLETED_EMPTY.equals(
                  entry.getValue().get(SchemaFields.NER_STATUS))).count() : 0;
      if (completeBatchWrite && !spladeTerminalFailures.isEmpty()) {
        context.log().warn("Combined backfill: {} document(s) reached terminal SPLADE FAILED: {}",
            spladeTerminalFailures.size(), spladeTerminalFailures);
      }
      writeMs = (System.nanoTime() - tWrite) / 1_000_000;

      // Commit every 5 writing batches (334 Phase 10). Lucene 10.4 fixed the MMapDirectory
      // .si arena leak (issue #15068): confined arenas are closed immediately. This
      // should prevent the 24GB native memory growth that killed deferred commits in
      // Phase 8 (tested on Lucene 9.x). If OOM recurs, revert to per-batch commits.
      // NRT refresh is suspended during the tight loop (managed by caller) to prevent
      // mmap accumulation from ControlledRealTimeReopenThread reader snapshots.
      if (written > 0 && ++context.batchesSinceCommit()[0] >= 5) {
        context
            .commitOps()
            .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_COMBINED);
        context.batchesSinceCommit()[0] = 0;
      }

      totalMs = (System.nanoTime() - t0) / 1_000_000;

      context
          .log()
          .info(
              "Combined backfill: docs={} (embed={},splade={},chunks={}),"
                  + " fetch={}ms, embed={}ms(ok={},fail={},singlePass={},longDocWindowed={},"
                  + "arenaOomWindowed={},windowUnits={},windowDocsDone={},windowDocsResuming={},"
                  + "deferredForOtherStages={}),"
                  + " splade={}ms(ok={},fail={}), ner={}ms(ok={},fail={}),"
                  + " write={}ms(written={}), total={}ms",
              pendingIds.size(),
              embedDocIds.size() + windowedDocIds.size(),
              spladeDocIds.size(),
              chunkIdsInBatch.size(),
              fetchMs,
              embedMs,
              embedProcessed,
              embedFailed,
              singlePassProcessed,
              longDocWindowed,
              arenaOomWindowed,
              windowUnitsDone,
              windowDocsCompleted,
              windowDocsResumable,
              embedDeferredForOtherStages,
              spladeMs,
              spladeProcessed,
              spladeFailed,
              nerMs,
              nerProcessed,
              nerFailed,
              writeMs,
              written,
              totalMs);

      if (aborted) {
        boolean populationChanged =
            context.indexingCoordinator().bulkDeleteEpoch() != epochAtSelection;
        context
            .log()
            .info(
                "Combined backfill: batch stopped early after {} encoder units, {} document-stages"
                    + " left unprocessed — {}. Published {} document updates; unfinished work"
                    + " stays PENDING for the next cycle (tempdoc 809).",
                unitsDone,
                docsSkipped,
                populationChanged
                    ? "documents were bulk-deleted underneath this batch (a watched root was"
                        + " removed, or the index was reset)"
                    : "the scheduler asked background enrichment to yield (pending ingest, user"
                        + " active, GPU claimed, or the cycle budget was spent)", written);
      }

      // 710 Move 2 item 4: per-stage enrichment counts/timing are no longer recorded here —
      // recordTiming carries "past the early-return/interruption checks" forward on the returned
      // outcome (mirrors the pre-move finally-block gate) so BackfillScheduler can record from
      // completed-stage data even when a later stage throws (the same "survives exceptions in
      // later stages" property the old finally block had — see the catch block below).
      // Tempdoc 798: `progressed` reports durable stage progress — at least one doc
      // advanced a stage durably (processed, an attempted stage resolved into its retry seam, or a
      // blank-content doc reached terminal FAILED). The aggregate writer result cannot identify
      // individual successes in a partial batch, so only a complete batch confirms that progress.
      // `written > 0` is only ACTIVITY and can stay true forever. See CombinedOutcome#progressed
      // for what is deliberately NOT counted.
      return new CombinedOutcome(
          // Round-15 post-round finding: encoder windows advanced on a long document are ACTIVITY
          // in exactly this field's documented sense — the pass demonstrably touched something and
          // the mode selector must keep choosing the combined pass while a long-document backlog is
          // converging window by window. It stays OUT of `progressed` below: the document has not
          // left the pending population, and `progressed` is a loop's continue-condition.
          written > 0 || windowUnitsDone > 0,
          completeBatchWrite
              && batchUpdates.stream()
                  .map(Map.Entry::getKey)
                  .anyMatch(stageAdvancedDocIds::contains),
          excludedDocuments,
          aborted,
          recordTiming,
          embedProcessed,
          spladeProcessed,
          nerProcessed,
          embedMs,
          spladeMs,
          nerMs,
          fetchMs,
          writeMs,
          totalMs,
          workSetSignature);

    } catch (Exception e) {
      context.log().error("Error during combined enrichment backfill", e);
      return recordTiming
          ? new CombinedOutcome(
              false,
              // The batch aborted before its single write, so nothing landed: no doc advanced a
              // stage durably, whatever the in-flight stage counters say (tempdoc 798).
              false,
              false,
              // Carries whatever an earlier stage already decided; the exception itself does not
              // set it — a crash says nothing about whether the caller wants backfill to yield,
              // and reporting one as an orderly early stop would stop the tight loop for the
              // wrong reason (tempdoc 809).
              aborted,
              true,
              0,
              0,
              0,
              embedMs,
              spladeMs,
              nerMs,
              fetchMs,
              writeMs,
              totalMs,
              workSetSignature)
          : CombinedOutcome.none();
    }
    } finally {
      enrichmentSpan.end();
    }
  }

  private static boolean embeddingReady(BackfillContext context, EmbeddingProvider provider) {
    return provider != null && provider == context.embeddingProviderSupplier().get()
        && provider.isAvailable() && context.allowEmbeddingWritesSupplier().getAsBoolean();
  }

  /**
   * Identifies WHICH documents a batch selected and how it routed them (round-15 post-round
   * finding). See {@link CombinedOutcome#workSetSignature()}.
   *
   * <p>The document-id list is the load-bearing part: per-stage COUNTS repeat routinely on a
   * healthy drain (100 parents + 50 chunks every batch), so a counts-only signature would cry wolf
   * on every large corpus. The id list changes as soon as the head of the queue moves, which is
   * exactly the property a stalled backfill lacks. Never {@code 0} for a non-empty selection, since
   * {@code 0} is the caller's "no work-set" sentinel.
   */
  private static long computeWorkSetSignature(
      List<String> pendingIds, int embedCount, int spladeCount, int chunkCount, int nerCount) {
    if (pendingIds.isEmpty()) {
      return 0L;
    }
    long hash = 1125899906842597L;
    for (String id : pendingIds) {
      hash = hash * 31 + (id == null ? 0 : id.hashCode());
    }
    hash = hash * 31 + embedCount;
    hash = hash * 31 + spladeCount;
    hash = hash * 31 + chunkCount;
    hash = hash * 31 + nerCount;
    return hash == 0L ? 1L : hash;
  }

  /**
   * Existence probe for "does this parent have chunk docs" (tempdoc 691 forensics fold-in) — a
   * {@code queryDocIdsByField(PARENT_DOC_ID, parentId, 1)} limit=1 existence check; only
   * existence matters here since the single-pass embed strategy is VECTOR-only (no chunk
   * span/order metadata is read).
   */
  private static boolean hasChunkDocs(BackfillContext context, String parentId) {
    return !context
        .documentFieldOps()
        .queryDocIdsByField(SchemaFields.PARENT_DOC_ID, parentId, 1)
        .isEmpty();
  }

  /**
   * Walks the exception's cause chain looking for an {@link OrtException} matching {@link
   * NativeSessionHandle#isBfcArenaFailure} — the single choke point for the BFC-arena string
   * match (owned by {@code ort-common}, shared with {@code SpladeEncoder}/{@code
   * CrossEncoderReranker}). {@link EmbeddingService#embedWithSpans} wraps the raw {@code
   * OrtException} in a {@code RuntimeException}, so a direct {@code instanceof} on the caught
   * exception isn't enough — this walks {@link Throwable#getCause()} to find it either way.
   * Ported from the deleted {@code LateChunkingEmbedBackfillOps} (tempdoc 691 forensics fold-in).
   */
  static boolean isArenaOomFailure(Throwable e) {
    for (Throwable cur = e; cur != null; cur = cur.getCause()) {
      if (cur instanceof OrtException ortException) {
        String message = ortException.getMessage();
        if (NativeSessionHandle.isBfcArenaFailure(ortException)
            || (message != null && message.contains("BFCArena")
                && message.contains("AllocateRawInternal")
                && message.contains("Failed to allocate memory"))) return true;
      }
    }
    return false;
  }

  /**
   * Mirrors the retry-count parsing each individual {@code handle*Failure} does before delegating
   * to its pure {@code compute*FailureUpdate} helper (tempdoc 700) — kept local to this class
   * since the combined path parses from a batched pre-fetched field value, not a per-doc read.
   */
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
