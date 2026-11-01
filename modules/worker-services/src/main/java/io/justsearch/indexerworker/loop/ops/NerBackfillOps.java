/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexing.SchemaFields;
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
 * <p>Runs after embedding backfill is complete (NER facets are supplementary). CPU-only — no GPU
 * conflict management needed.
 */
public final class NerBackfillOps {
  private NerBackfillOps() {}

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      IndexingPacing pacing,
      Supplier<NerService> nerServiceSupplier,
      BooleanSupplier runningSupplier,
      int batchSize,
      Logger log) {}

  /**
   * Processes one batch of NER backfill.
   *
   * @return the batch outcome (tempdoc 710 Move 2 item 4) — {@link BackfillScheduler} records
   *     per-stage timing/counts from this instead of relying on a metrics call inside the op.
   */
  public static StageOutcome processNerBackfill(BackfillContext context) {
    long methodStart = System.nanoTime();
    try {
      List<String> pendingIds =
          context
              .documentFieldOps()
              .queryDocIdsByField(
                  SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING, context.batchSize());

      if (pendingIds.isEmpty()) {
        return StageOutcome.none();
      }

      context.log().info("Processing NER backfill for {} documents", pendingIds.size());
      int processed = 0;
      int failed = 0;
      int markedFailed = 0;

      // Collect updates during per-doc NER inference, then batch-write at the end
      // (single NRT refresh instead of 100 × 85ms per-doc refreshes)
      List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();

      if (!context.runningSupplier().getAsBoolean()) return StageOutcome.elapsedSince(methodStart);
      EnrichmentBatch batch = new EnrichmentBatch(context.documentFieldOps(), context.indexingCoordinator(), pendingIds);
      for (String docId : pendingIds) {
        // NER is CPU-only: interrupt on shutdown, no GPU check. Tempdoc 885 item 3: foreground
        // load paces the per-document loop instead of breaking out of it.
        context.pacing().pace();
        if (!context.runningSupplier().getAsBoolean()) {
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

          NerService nerService = context.nerServiceSupplier().get();
          if (nerService == null) {
            context.log().debug("NER backfill: service unavailable, stopping batch");
            break;
          }
          List<NerResult> nerBatch = nerService.extractEntitiesBatch(List.of(content));
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

          batchUpdates.add(Map.entry(docId, updates));
          processed++;

        } catch (Exception e) {
          context.log().error("NER backfill failed for doc {}: {}", docId, e.getMessage());
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

      if (processed > 0 || failed > 0) {
        context
            .commitOps()
            .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_NER);
        context
            .log()
            .info(
                "NER backfill complete: {} processed, {} failed ({} permanently marked FAILED)",
                processed,
                failed,
                markedFailed);
      }
      return new StageOutcome(true, processed, (System.nanoTime() - methodStart) / 1_000_000);

    } catch (Exception e) {
      context.log().error("Error during NER backfill", e);
      return new StageOutcome(false, 0, (System.nanoTime() - methodStart) / 1_000_000);
    }
  }



  /**
   * Pure computation of the retry-count/status update for a NER failure — no I/O. Shared by the snapshot-bound individual and combined enrichment paths (merges the
   * result into its own single batched write), so the two stay in escalation-parity by
   * construction (tempdoc 700).
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
   * NerResult} — no I/O. Shared by {@link #processNerBackfill} and the combined enrichment path so
   * both preserve the exact entity lists. Mutates {@code updates} in place to match both callers.
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
