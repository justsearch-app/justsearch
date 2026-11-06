/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import ai.onnxruntime.OrtException;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.loop.pacing.BulkPacePolicy;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.metrics.EncoderOrtRunSpans;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
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
import java.util.function.Supplier;
import org.slf4j.Logger;

/** Dense parent/chunk backfill units used by the fair scheduler. */
public final class CombinedEnrichmentBackfillOps {
  private CombinedEnrichmentBackfillOps() {}

  static boolean hardStopRequested(BackfillContext context, long epochAtSelection,
      IndexingCoordinator.EnrichmentAuthority authority) {
    return EnrichmentBatch.hardStop(() -> !context.runningSupplier().getAsBoolean()
        || context.hardStopRequestedSupplier().getAsBoolean(), context.indexingCoordinator(),
        epochAtSelection, authority);
  }

  /** Deadlines and hard stops both bound the start of every unit, including the first. */
  static boolean stopRequested(BackfillContext context, long epochAtSelection,
      IndexingCoordinator.EnrichmentAuthority authority) {
    // The scheduler supplier may pace. Recheck source and authority after that wait.
    return context.stopRequestedSupplier().getAsBoolean()
        || hardStopRequested(context, epochAtSelection, authority);
  }

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      IndexingPacing pacing,
      Supplier<EmbeddingProvider> embeddingProviderSupplier,
      BooleanSupplier runningSupplier,
      BooleanSupplier allowEmbeddingWritesSupplier,
      int batchSize,
      Logger log,
      boolean chunkVectorsEnabled,
      boolean lateChunkingEnabled,
      int chunkSlotsPerBatch,
      BooleanSupplier stopRequestedSupplier,
      BooleanSupplier hardStopRequestedSupplier,
      WindowedEmbedProgress windowedEmbedProgress,
      Set<String> publishedDocIds,
      java.util.function.Predicate<Boolean> unitAllowed,
      java.util.function.Predicate<EnrichmentAccounting.Stage> stageAllowed) {}

  /** One admitted dense unit; classification and partial windows remain owned for resumption. */
  public static StageOutcome processDenseUnit(BackfillContext source,
      EnrichmentSelection selection, EnrichmentAccounting.Stage stage) {
    long methodStart = System.nanoTime();
    if (stage != EnrichmentAccounting.Stage.EMBED && stage != EnrichmentAccounting.Stage.CHUNK_EMBED) {
      throw new IllegalArgumentException("Not a dense stage: " + stage);
    }
    var context = new BackfillContext(source.documentFieldOps(), source.indexingCoordinator(),
        source.pacing(), source.embeddingProviderSupplier(), source.runningSupplier(),
        source.allowEmbeddingWritesSupplier(), source.batchSize(), source.log(), source.chunkVectorsEnabled(),
        source.lateChunkingEnabled(), source.chunkSlotsPerBatch(), source.stopRequestedSupplier(),
        source.hardStopRequestedSupplier(), source.windowedEmbedProgress(), source.publishedDocIds(),
        source.unitAllowed(), candidate -> candidate == stage && source.stageAllowed().test(candidate));
    if (selection != null) selection.begin();
    var outcome = processDenseBackfill(context, selection);
    int processed = (int) (outcome.accounting().count(stage, EnrichmentAccounting.Disposition.PUBLISHED)
        + outcome.accounting().count(stage, EnrichmentAccounting.Disposition.REJECTED_OUTPUT));
    boolean progressed = outcome.wroteAnything() && (processed > 0
        || outcome.accounting().count(stage, EnrichmentAccounting.Disposition.FAILED_CHARGED) > 0);
    if (!outcome.accounting().storageFailures().isEmpty()
        || outcome.accounting().count(stage, EnrichmentAccounting.Disposition.CANCELLED) > 0) processed = 0;
    return new StageOutcome(progressed, processed, (System.nanoTime() - methodStart) / 1_000_000,
        outcome.accounting(), outcome.attempted());
  }

  private record DenseOutcome(boolean wroteAnything, EnrichmentAccounting.Summary accounting,
      boolean attempted) {
    static DenseOutcome none() { return new DenseOutcome(false, EnrichmentAccounting.Summary.empty(), false); }
  }

  private static DenseOutcome processDenseBackfill(BackfillContext context,
      EnrichmentSelection selection) {
    // Timings survive exceptions; completed counts are assigned only after publication.
    long embedMs;
    long fetchMs;
    long writeMs;
    long totalMs;
    boolean attemptStarted = false;
    // Tempdoc 809 finding 3 — interruption bookkeeping, hoisted with the accumulators above so the
    // catch block below can report it too.
    boolean aborted = false;
    var authority = context.indexingCoordinator().enrichmentAuthority();
    // Capture source identity before selection for both checkpoints and failure classification.
    long epochAtSelection = context.indexingCoordinator().bulkDeleteEpoch();
    EmbeddingProvider selectedEmbeddingProvider = context.embeddingProviderSupplier().get();
    EnrichmentAccounting accounting = new EnrichmentAccounting(stage ->
        hardStopRequested(context, epochAtSelection, authority) || !embeddingReady(context, selectedEmbeddingProvider));



    Map<String, Map<String, Object>> updatesByDocId = new LinkedHashMap<>();
    int unitsDone = 0;

    // Tempdoc 400 LR2-a: enrichment.batch parent span. Encoder ORT spans
    // emitted inside are parented under this when detailed tracing is on;
    // noop (Span.getInvalid) when off so there is no measurable overhead.
    Span enrichmentSpan = EncoderOrtRunSpans.maybeEnrichmentBatch();
    try (Scope _ = enrichmentSpan.makeCurrent()) {
    try {
      // Phase 0: bounded document-ID pages, independently resumable for each stage.
      boolean embedAvailable = (context.stageAllowed().test(EnrichmentAccounting.Stage.EMBED)
          || context.stageAllowed().test(EnrichmentAccounting.Stage.CHUNK_EMBED))
          && embeddingReady(context, selectedEmbeddingProvider)
          && context.unitAllowed().test(selectedEmbeddingProvider.isUsingGpu());
      // The selected dense lane contributes a bounded page. Its unexamined suffix stays
      // behind the stage cursor rather than in an all-ID cache.
      List<List<String>> stagePending = new ArrayList<>();
      if (embedAvailable && context.stageAllowed().test(EnrichmentAccounting.Stage.EMBED)) {
        stagePending.add(EnrichmentSelection.page(selection, context.documentFieldOps(),
            SchemaFields.EMBEDDING_STATUS, context.batchSize(), false));
      }
      int chunkSlotsPerBatch = context.chunkSlotsPerBatch();
      List<String> chunkPage = embedAvailable
          && context.stageAllowed().test(EnrichmentAccounting.Stage.CHUNK_EMBED)
          && context.chunkVectorsEnabled() && chunkSlotsPerBatch > 0
          ? EnrichmentSelection.page(selection, context.documentFieldOps(),
              SchemaFields.CHUNK_EMBEDDING_STATUS, chunkSlotsPerBatch, false) : List.of();
      Set<String> alreadyPublished = new HashSet<>();
      for (List<String> ids : stagePending) for (String id : ids)
        if (context.publishedDocIds().contains(id)) alreadyPublished.add(id);
      for (String id : chunkPage) if (context.publishedDocIds().contains(id)) alreadyPublished.add(id);
      Set<String> parents = new LinkedHashSet<>();
      // Retained owners get served before admitting more work. Reap obsolete identities first.
      context.windowedEmbedProgress().reconcile(context.documentFieldOps(), authority,
          epochAtSelection, selectedEmbeddingProvider);
      if (embedAvailable) {
        var owners = context.windowedEmbedProgress().owners();
        boolean parentsAllowed = context.stageAllowed().test(EnrichmentAccounting.Stage.EMBED);
        boolean chunksAllowed = context.stageAllowed().test(EnrichmentAccounting.Stage.CHUNK_EMBED);
        // A held retained owner must not occupy the other dense stage's selection slots.
        var ownerFields = parentsAllowed == chunksAllowed ? Map.<String, Map<String, String>>of()
            : context.documentFieldOps().getDocumentFieldsBatchOrThrow(owners, Set.of(SchemaFields.IS_CHUNK));
        for (String id : owners) {
          if (parents.size() >= context.batchSize()) break;
          boolean chunk = "true".equals(ownerFields.getOrDefault(id, Map.of()).get(SchemaFields.IS_CHUNK));
          if (!(chunk ? chunksAllowed : parentsAllowed)) continue;
          if (!context.publishedDocIds().contains(id)) parents.add(id);
        }
      }
      int longest = stagePending.stream().mapToInt(List::size).max().orElse(0);
      for (int i = 0; i < longest && parents.size() < context.batchSize(); i++) {
        for (List<String> ids : stagePending) {
          if (i < ids.size() && parents.size() < context.batchSize()
              && !alreadyPublished.contains(ids.get(i))) parents.add(ids.get(i));
        }
      }
      Set<String> chunkDocIds = new LinkedHashSet<>(chunkPage);
      chunkDocIds.removeAll(alreadyPublished);
      // A full progress store defers new dense owners before retaining their proof.
      // Otherwise a refused oversized chunk could consume the allowance needed by
      // the admitted owners that must finish to release a slot. Other stages can
      // still select the same chunk independently through their own page.
      chunkDocIds.removeIf(id -> !context.windowedEmbedProgress().canAdmit(id));
      // Preserve the chunk-first lane when allocating retained text, too. A retained
      // oversized parent must not consume the allowance before chunks get their turn.
      List<String> pendingIds = new ArrayList<>(chunkDocIds);
      for (String id : parents) if (!pendingIds.contains(id)) pendingIds.add(id);
      var excluded = new HashSet<>(context.documentFieldOps().excludedProcessingIds(pendingIds));
      excluded.addAll(alreadyPublished);
      pendingIds.removeAll(excluded);
      chunkDocIds.retainAll(pendingIds);
      if (pendingIds.isEmpty()) {
        if (selection != null) selection.examined(excluded);
        return DenseOutcome.none();
      }

      long t0 = System.nanoTime();
      attemptStarted = true;

      // Phase 1: Batch content fetch (single searcher, all docs)
      EnrichmentBatch batch = new EnrichmentBatch(context.documentFieldOps(), context.indexingCoordinator(), pendingIds, authority, selection, excluded).accounting(accounting);
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
      boolean retainedParentRoute = false;

      // Track which doc IDs are chunk docs (need CHUNK_VECTOR instead of VECTOR)
      Set<String> chunkIdsInBatch = new HashSet<>();

      for (String docId : batch.ids()) {
        updatesByDocId.put(docId, new HashMap<>());
        Map<String, String> docFields = batchedFields.getOrDefault(docId, Map.of());
        boolean isChunkDoc =
            chunkDocIds.contains(docId)
                || "true".equalsIgnoreCase(docFields.get(SchemaFields.IS_CHUNK));

        boolean docEmbedAvailable = embedAvailable
            && context.stageAllowed().test(isChunkDoc ? EnrichmentAccounting.Stage.CHUNK_EMBED
                : EnrichmentAccounting.Stage.EMBED)
            && context.windowedEmbedProgress().canAdmit(docId);

        if (isChunkDoc) {
          if (embedAvailable && context.stageAllowed().test(EnrichmentAccounting.Stage.CHUNK_EMBED)
              && context.chunkVectorsEnabled()
              && SchemaFields.EMBEDDING_STATUS_PENDING.equals(docFields.get(SchemaFields.CHUNK_EMBEDDING_STATUS))) {
            accounting.select(docId, EnrichmentAccounting.Stage.CHUNK_EMBED);
          }

          // Enrol only pending chunk embedding; never manufacture parent VECTOR/NER state.
          String chunkContent = contentByDocId.get(docId);
          String chunkEmbeddingStatus = docFields.get(SchemaFields.CHUNK_EMBEDDING_STATUS);

          if ((chunkContent == null || chunkContent.isBlank())
              && stopRequested(context, epochAtSelection, authority)) continue;
          if (chunkContent == null || chunkContent.isBlank()) {
            // Parent content plus the stored offset law should always produce a non-blank slice, so
            // a blank read here is a fetch/consistency anomaly, not a legitimately empty chunk.
            // Marking CHUNK_EMBEDDING_STATUS=COMPLETED would claim a
            // chunk_vector that will never exist — a silent data-less COMPLETED (the F-032 "status
            // lies" class). Escalate via the retry-count seam instead: retry next cycle, FAIL at
            // max — never COMPLETED-without-data.
            Map<String, Object> updates = updatesByDocId.get(docId);
            if (docEmbedAvailable && context.chunkVectorsEnabled()
                && SchemaFields.EMBEDDING_STATUS_PENDING.equals(chunkEmbeddingStatus)
                && embeddingReady(context, selectedEmbeddingProvider)) {
              Map<String, Object> escalation =
                  EmbeddingBackfillOps.computeChunkEmbeddingFailureUpdate(
                      parseRetryCountOrZero(
                          docFields.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT)));
              updates.putAll(escalation);
            }

            continue;
          }
          if (docEmbedAvailable && context.chunkVectorsEnabled()
              && SchemaFields.EMBEDDING_STATUS_PENDING.equals(chunkEmbeddingStatus)) {
            context.windowedEmbedProgress().identify(docId, chunkContent,
                new WindowedEmbedProgress.Identity(authority, epochAtSelection, selectedEmbeddingProvider,
                    selectedEmbeddingProvider.dimension(), docFields.get(SchemaFields.DOC_UID),
                    docFields.get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256),
                    docFields.get(SchemaFields.SOURCE_SHA256)));
            embedDocIds.add(docId);
            embedContents.add(chunkContent);
            chunkIdsInBatch.add(docId);
          }

          continue;
        }

        // Parent document: dense embedding.
        String content = contentByDocId.get(docId);

        // Read the RAW status: an ABSENT status means the stage does not apply to this document,
        // not that it is PENDING. Chunk documents have their own status and vector fields.
        String embedStatus = docFields.get(SchemaFields.EMBEDDING_STATUS);

        if (embedAvailable && context.stageAllowed().test(EnrichmentAccounting.Stage.EMBED)
            && SchemaFields.EMBEDDING_STATUS_PENDING.equals(embedStatus)) {
          accounting.select(docId, EnrichmentAccounting.Stage.EMBED);
        }

        if ((content == null || content.isBlank())
            && stopRequested(context, epochAtSelection, authority)) continue;
        if (content == null || content.isBlank()) {
          // No content means no artifact can be produced for any stage that applies here. Escalate
          // through each stage's retry-count seam (retry next cycle, FAILED at max) exactly as
          // EmbeddingBackfillOps does for the identical condition — never COMPLETED-without-data.
          Map<String, Object> updates = updatesByDocId.get(docId);
          if (docEmbedAvailable && SchemaFields.EMBEDDING_STATUS_PENDING.equals(embedStatus)) {
            Map<String, Object> escalation =
                EmbeddingBackfillOps.computeEmbeddingFailureUpdate(
                    parseRetryCountOrZero(docFields.get(SchemaFields.EMBEDDING_RETRY_COUNT)));
            updates.putAll(escalation);
          }

          continue;
        }

        // Tempdoc 691 forensics fold-in: a chunked parent (one with PARENT_DOC_ID-matching chunk
        // docs) routes to the late-chunking single-pass embed strategy in Phase 3a-i below instead
        // of the ordinary windowed batch — collected into a separate list here so that sub-phase
        // can try embedWithSpans first and only fold the doc into embedDocIds/embedContents on a
        // null/arena-OOM outcome. Whichever strategy serves the vector, its result lands in
        // this document's dense update.
        boolean isLateChunkingParent =
            context.lateChunkingEnabled()
                && docEmbedAvailable
                && SchemaFields.EMBEDDING_STATUS_PENDING.equals(embedStatus)
                && hasChunkDocs(context, docId);
        if (docEmbedAvailable && SchemaFields.EMBEDDING_STATUS_PENDING.equals(embedStatus)) {
          context.windowedEmbedProgress().identify(docId, content,
              new WindowedEmbedProgress.Identity(authority, epochAtSelection, selectedEmbeddingProvider,
                  selectedEmbeddingProvider.dimension(), docFields.get(SchemaFields.DOC_UID),
                  docFields.get(SchemaFields.CONTENT_SHA256), docFields.get(SchemaFields.SOURCE_SHA256)));
        }
        if (isLateChunkingParent) {
          var route = context.windowedEmbedProgress().route(docId, content);
          if (route != null || context.windowedEmbedProgress().nextWindow(docId, content) > 0) {
            retainedParentRoute = true;
            embedDocIds.add(docId);
            embedContents.add(content);
            if (route != WindowedEmbedProgress.Route.ORDINARY
                && route != WindowedEmbedProgress.Route.FALLBACK) directWindowedDocIds.add(docId);
          } else {
            lateChunkingDocIds.add(docId);
            lateChunkingContents.add(content);
          }
        } else if (docEmbedAvailable && SchemaFields.EMBEDDING_STATUS_PENDING.equals(embedStatus)) {
          embedDocIds.add(docId);
          embedContents.add(content);
        }

      }

      fetchMs = (System.nanoTime() - t0) / 1_000_000;

      // Check for interruption. Tempdoc 885 item 3: foreground load paces here, it does not
      // cancel the batch.
      context.pacing().pace();
      if (!context.runningSupplier().getAsBoolean()) {
        accounting.cancelRemaining();
        return new DenseOutcome(false, accounting.snapshot(), true);
      }

      // Spec D §3.3: the pace() above is the only yield point the batch used to have, so one batch
      // ran 2-3 s of GPU work before foreground load could slow it (M5). Each GPU phase below now
      // paces every bulkPhaseDocs documents, and every phase boundary is a pace point, so no stretch
      // between two pace() calls exceeds bulkPaceTargetMs at M5's per-document costs.
      BulkPacePolicy.PhasePacer pacer = new BulkPacePolicy.PhasePacer(context.pacing());
      pacer.startPhase(BulkPacePolicy.DEFAULTS.embeddingPhaseDocs());

      // Phase 3a: Batch embedding
      long tEmbed = System.nanoTime();


      var dense = DenseEnrichmentUnit.encode(context, epochAtSelection, authority,
          selectedEmbeddingProvider, embedAvailable, retainedParentRoute,
          batch, accounting, pacer, embedDocIds, embedContents, lateChunkingDocIds,
          lateChunkingContents, directWindowedDocIds, chunkIdsInBatch, batchedFields,
          updatesByDocId);
      aborted |= dense.aborted();
      unitsDone += dense.unitsDone();
      int embedFailed = dense.embedFailed();
      int singlePassProcessed = dense.singlePassProcessed();
      int longDocWindowed = dense.longDocWindowed();
      int arenaOomWindowed = dense.arenaOomWindowed();
      int windowUnitsDone = dense.windowUnitsDone();
      int windowDocsCompleted = dense.windowDocsCompleted();
      int windowDocsResumable = dense.windowDocsResumable();
      int singlePassDeferred = dense.singlePassDeferred();
      int embedPopulation = dense.embedPopulation();
      embedMs = (System.nanoTime() - tEmbed) / 1_000_000;

      // Publish the completed dense unit with its captured comparison proof.
      long tWrite = System.nanoTime();


      List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();
      for (var entry : updatesByDocId.entrySet()) {
        if (!entry.getValue().isEmpty()) {
          batchUpdates.add(Map.entry(entry.getKey(), entry.getValue()));
        }
      }
      int written = 0;
      if (!batchUpdates.isEmpty()) {
        io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BatchUpdateResult result;
        try {
          result = batch.updateBatch(batchUpdates);
        } finally {
          // A receipt records the accepted prefix before result() can throw.
          for (String docId : batch.acceptedIds()) {
            var updates = updatesByDocId.get(docId);
            if (updates != null && (updates.get(SchemaFields.VECTOR) instanceof float[]
                || updates.get(SchemaFields.CHUNK_VECTOR) instanceof float[])) {
              context.windowedEmbedProgress().forget(docId);
            }
          }
        }
        written = result.updatedCount();
        // The exclusion set keeps its pre-receipt members: later batches in this cycle exclude
        // exactly the documents they excluded before publication receipts existed. Receipts feed
        // only the outcome accounting (docs/tempdocs/711-rmw-preservation-and-followup-designs.md).
        if (written == batchUpdates.size()) {
          for (var entry : batchUpdates) context.publishedDocIds().add(entry.getKey());
        } else {
          // Do not hide a replacement revision; the writer's existing reader can distinguish it
          // without an additional NRT refresh.
          var current = context.documentFieldOps().getEnrichmentInputs(
              batchUpdates.stream().map(Map.Entry::getKey).toList(), EmbeddingBackfillOps.MAX_PARENT_BATCH_CHARS);
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
      boolean individualFallbackProgress = batchUpdates.isEmpty()
          && !batch.acceptedIds().isEmpty();
      // These counts feed the metrics that existed before publication receipts, so they keep
      // their complete-batch inputs; per-document receipts and writer corrections feed only the
      // outcome accounting.
      int embedProcessed = completeBatchWrite ? (int) batchUpdates.stream().filter(entry ->
          entry.getValue().containsKey(SchemaFields.VECTOR)
              || entry.getValue().containsKey(SchemaFields.CHUNK_VECTOR)).count() : 0;
      writeMs = (System.nanoTime() - tWrite) / 1_000_000;

      // E4b: the scheduler owns enrichment commits (document/time thresholds, idle drain); the
      // probe-era five-writing-batch commit is retired.

      if (context.indexingCoordinator().enrichmentAuthority() != authority) {
        accounting.rejectedRetiredOutcomes();
      }
      if (embedAvailable && !embeddingReady(context, selectedEmbeddingProvider)) {
        accounting.retired(EnrichmentAccounting.Stage.EMBED);
        accounting.retired(EnrichmentAccounting.Stage.CHUNK_EMBED);
        accounting.cancelRemaining();
      }
      if (hardStopRequested(context, epochAtSelection, authority)) {
        accounting.cancelRemaining();
        aborted = true;
      }
      totalMs = (System.nanoTime() - t0) / 1_000_000;
      context.log().info("Enrichment dispositions: {}", accounting.snapshot().counters());

      context
          .log()
          .info(
              "Combined backfill: docs={} (embed={},chunks={}),"
                  + " fetch={}ms, embed={}ms(ok={},fail={},singlePass={},longDocWindowed={},"
                  + "arenaOomWindowed={},windowUnits={},windowDocsDone={},windowDocsResuming={},"
                  + "singlePassDeferred={}),"
                  + " write={}ms(written={}), total={}ms",
              pendingIds.size(),
              embedPopulation,
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
              singlePassDeferred,
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
                accounting.snapshot().tickets().values().stream().filter(disposition ->
                    disposition == EnrichmentAccounting.Disposition.DEFERRED
                        || disposition == EnrichmentAccounting.Disposition.CANCELLED).count(),
                populationChanged
                    ? "documents were bulk-deleted underneath this batch (a watched root was"
                        + " removed, or the index was reset)"
                    : "the scheduler asked background enrichment to yield (pending ingest, user"
                        + " active, GPU claimed, or the cycle budget was spent)", written);
      }

      return new DenseOutcome(written > 0 || windowUnitsDone > 0 || individualFallbackProgress,
          accounting.snapshot(), true);

    } catch (Exception e) {
      for (var stage : EnrichmentAccounting.Stage.values()) accounting.cancelled(stage, e);
      updatesByDocId.forEach(accounting::computed);
      context.log().info("Enrichment dispositions: {}", accounting.snapshot().counters());
      context.log().error("Error during combined enrichment backfill", e);
      return attemptStarted ? new DenseOutcome(false, accounting.snapshot(), true) : DenseOutcome.none();
    }
    } finally {
      context.windowedEmbedProgress().releaseUnused();
      enrichmentSpan.end();
    }
  }
  static boolean embeddingReady(BackfillContext context, EmbeddingProvider provider) {
    return provider != null && provider == context.embeddingProviderSupplier().get()
        && provider.isAvailable() && context.allowEmbeddingWritesSupplier().getAsBoolean();
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
  static int parseRetryCountOrZero(String retryCountStr) {
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
