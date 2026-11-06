/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexerworker.metrics.EncoderOrtRunSpans;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * NER entity extraction backfill for documents with {@code ner_status=PENDING}.
 *
 * <p>Resource admission
 * is checked per document independently of hard cancellation inside inference.
 */
public final class NerBackfillOps {
  private NerBackfillOps() {}

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      IndexingPacing pacing,
      Supplier<NerService> nerServiceSupplier,
      BooleanSupplier runningSupplier,
      int batchSize,
      Logger log,
      BooleanSupplier unitAllowed) {

  }

  /** One document is the NER native unit; the selection cursor is shared with the batch caller. */
  public static StageOutcome processNerUnit(BackfillContext source, EnrichmentSelection selection) {
    return processNerBackfill(new BackfillContext(source.documentFieldOps(),
        source.indexingCoordinator(),
        source.pacing(),
        source.nerServiceSupplier(),
        source.runningSupplier(),
        1,
        source.log(),
        source.unitAllowed()), selection);
  }

  private static StageOutcome processNerBackfill(BackfillContext context,
      EnrichmentSelection selection) {
    if (selection != null) selection.begin();
    var authority = context.indexingCoordinator().enrichmentAuthority();
    NerService selectedService = context.nerServiceSupplier().get();
    long methodStart = System.nanoTime();
    long cancellationEpoch = context.indexingCoordinator().bulkDeleteEpoch();
    EnrichmentAccounting accounting = new EnrichmentAccounting(stage ->
        EnrichmentBatch.hardStop(() -> !context.runningSupplier().getAsBoolean(),
            context.indexingCoordinator(), cancellationEpoch, authority)
            || selectedService == null || !selectedService.isAvailable()
            || context.nerServiceSupplier().get() != selectedService);
    Span enrichmentSpan = EncoderOrtRunSpans.maybeEnrichmentBatch();
    try (Scope _ = enrichmentSpan.makeCurrent()) {
    try {
      List<String> pendingIds =
          EnrichmentSelection.page(selection, context.documentFieldOps(),
              SchemaFields.NER_STATUS, context.batchSize(), false);

      var excluded = context.documentFieldOps().excludedProcessingIds(pendingIds);
      pendingIds = new ArrayList<>(pendingIds);
      pendingIds.removeAll(excluded);
      accounting.select(pendingIds, EnrichmentAccounting.Stage.NER);
      if (pendingIds.isEmpty()) {
        if (selection != null) selection.examined(excluded);
        return StageOutcome.none();
      }

      context.log().info("Processing NER backfill for {} documents", pendingIds.size());
      int processed = 0;
      int failed = 0;
      int markedFailed = 0;

      // Collect updates during per-doc NER inference, then batch-write at the end
      // (single NRT refresh instead of 100 × 85ms per-doc refreshes)
      List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();

      if (!context.runningSupplier().getAsBoolean()) {
        accounting.cancelRemaining();
        return StageOutcome.observed(true, 0, methodStart, accounting);
      }
      EnrichmentBatch batch = new EnrichmentBatch(context.documentFieldOps(), context.indexingCoordinator(), pendingIds, authority, selection, excluded).accounting(accounting);
      BooleanSupplier unavailable = () -> selectedService == null || !selectedService.isAvailable()
          || context.nerServiceSupplier().get() != selectedService;
      for (String docId : batch.ids()) {
        if (selection != null && !"PENDING".equals(batch.fields(docId).get(SchemaFields.NER_STATUS))) continue;
        // Tempdoc 885 item 3: foreground load paces the per-document loop. Resource holds
        // defer this unit; hard stops also reach its internal native-call boundaries.
        context.pacing().pace();
        if (!context.unitAllowed().getAsBoolean()) break;
        if (batch.stopped(() -> !context.runningSupplier().getAsBoolean(), unavailable, EnrichmentAccounting.Stage.NER)) {
          accounting.cancelRemaining();
          context.log().debug("NER backfill interrupted: loop stopping");
          break;
        }

        try {
          String content = batch.content(docId);
          if (content == null || content.isBlank()) {
            // NER never ran, so this is not a legitimately-empty extraction — marking COMPLETED
            // would claim an enrichment that does not exist (the F-032 "status lies" class,
            // tempdoc 717). Escalate through the retry-count seam the same way
            // EmbeddingBackfillOps handles missing content: retry next cycle, FAILED at max.
            context.log().warn("NER backfill: content missing or blank for {}", docId);
            markedFailed +=
                batch.failure(docId, SchemaFields.NER_RETRY_COUNT, NerBackfillOps::computeNerFailureUpdate);
            failed++;
            continue;
          }

          NerService nerService = selectedService;
          if (nerService == null) {
            context.log().debug("NER backfill: service unavailable, stopping batch");
            break;
          }
          batch.started(List.of(docId), EnrichmentAccounting.Stage.NER);
          List<NerResult> nerBatch = batch.inferenceCancellation(() -> !context.runningSupplier().getAsBoolean(), unavailable).call(() -> nerService.extractEntitiesBatch(List.of(content)));
          accounting.acquired(nerService);
          NerResult result = nerBatch.isEmpty() ? NerResult.EMPTY : nerBatch.get(0);

          Map<String, Object> updates = new HashMap<>();
          // NER ran; zero entities is a valid terminal outcome, but it must stay distinguishable
          // from an actually-enriched document (SchemaFields.VDU_STATUS_COMPLETED_EMPTY precedent).
          updates.put(
              SchemaFields.NER_STATUS,
              result.isEmpty()
                  ? SchemaFields.NER_STATUS_COMPLETED_EMPTY
                  : SchemaFields.NER_STATUS_COMPLETED);
          updates.put(SchemaFields.NER_RETRY_COUNT, "0");

          applyEntityFieldUpdates(updates, result);

          accounting.computed(docId, updates);
          if (batch.retired(unavailable, EnrichmentAccounting.Stage.NER)) break;
          batchUpdates.add(Map.entry(docId, updates));
          processed++;

        } catch (Exception e) {
          if (accounting.storageFailed()) throw e;
          if (batch.retired(unavailable, EnrichmentAccounting.Stage.NER)) break;
          boolean cancelled = accounting.cancelled(EnrichmentAccounting.Stage.NER, e);
          context.log().error("NER backfill failed for doc {}: {}", docId, e.getMessage());
          if (cancelled || batch.stopped(() -> !context.runningSupplier().getAsBoolean())
              || context.nerServiceSupplier().get() != selectedService) {
            accounting.cancelRemaining();
            break;
          }
          if (accounting.acquisitionUnavailable(selectedService, e)) break;
          markedFailed +=
              batch.failure(docId, SchemaFields.NER_RETRY_COUNT, NerBackfillOps::computeNerFailureUpdate);
          failed++;
        }
      }

      // Batch-write all collected updates (single NRT refresh for the entire batch)
      if (!batchUpdates.isEmpty()) {
        if (batchUpdates.stream().anyMatch(entry ->
            SchemaFields.NER_STATUS_COMPLETED.equals(entry.getValue().get(SchemaFields.NER_STATUS)))) {
          context.documentFieldOps().markNerPublished();
        }
        processed = batch.updateBatch(batchUpdates).updatedCount();
      }

      batch.rejectRetiredOutcomes();
      if (processed > 0 || failed > 0) {
        context
            .log()
            .info(
                "NER backfill complete: {} processed, {} failed ({} permanently marked FAILED)",
                processed,
                failed,
                markedFailed);
      }
      return StageOutcome.observed(true, processed, methodStart, accounting);

    } catch (Exception e) {
      if (context.indexingCoordinator().enrichmentAuthority() != authority) {
        accounting.rejectedRetiredOutcomes();
      }
      accounting.cancelled(EnrichmentAccounting.Stage.NER, e);
      context.log().error("Error during NER backfill", e);
      return StageOutcome.observed(false, 0, methodStart, accounting);
    } finally {
      context.log().info("Enrichment dispositions: {}", accounting.snapshot().counters());
    }
    } finally {
      enrichmentSpan.end();
    }
  }

  /**
   * Pure computation of the retry-count/status update for a NER lane failure — no I/O.
   *
   * @param currentRetryCount the doc's retry count *before* this failure
   * @return field updates: always {@code NER_RETRY_COUNT}; additionally {@code
   *     NER_STATUS=FAILED} once the incremented count reaches {@code NER_MAX_RETRIES}
   */
  static Map<String, Object> computeNerFailureUpdate(int currentRetryCount) {
    int retryCount = currentRetryCount + 1;
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.NER_RETRY_COUNT, String.valueOf(retryCount));
    if (retryCount >= SchemaFields.NER_MAX_RETRIES) {
      updates.put(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_FAILED);
    }
    return updates;
  }

  /**
   * Pure derivation of the multi-valued {@code ENTITY_*_RAW} facet/filter updates from a {@link
   * NerResult}. Mutates the lane's update map in place and preserves the exact entity lists.
   *
   * @param updates the batch-update map to populate in place; a no-op if {@code result} is empty
   * @param result the NER extraction result
   */
  static void applyEntityFieldUpdates(Map<String, Object> updates, NerResult result) {
    if (result.isEmpty()) {
      return;
    }
    if (!result.persons().isEmpty()) {
      updates.put(SchemaFields.ENTITY_PERSONS_RAW, new ArrayList<>(result.persons()));
    }
    if (!result.organizations().isEmpty()) {
      updates.put(SchemaFields.ENTITY_ORGANIZATIONS_RAW, new ArrayList<>(result.organizations()));
    }
    if (!result.locations().isEmpty()) {
      updates.put(SchemaFields.ENTITY_LOCATIONS_RAW, new ArrayList<>(result.locations()));
    }
  }

}
