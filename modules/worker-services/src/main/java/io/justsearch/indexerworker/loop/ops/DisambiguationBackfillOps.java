/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Entity disambiguation backfill for documents with completed NER extraction.
 *
 * <p>Harvests raw entity mentions from NER-completed documents, feeds them to the
 * {@link DisambiguationService} for clustering. Runs after NER backfill is complete
 * (disambiguation is supplementary to raw entity facets).
 */
public final class DisambiguationBackfillOps {
  private DisambiguationBackfillOps() {}

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingPacing pacing,
      Supplier<DisambiguationService> serviceSupplier,
      BooleanSupplier runningSupplier,
      int batchSize,
      Logger log) {}

  /**
   * Processes one resumable page of NER-completed documents.
   *
   * <p>Acknowledges progress only after coherent reads, source replacement, and snapshot
   * publication succeed. The final page retires sources absent from the completed scan.
   */
  public static void processDisambiguationBackfill(BackfillContext context) {
    try {
      DisambiguationService service = context.serviceSupplier().get();
      if (service == null || !service.isAvailable() || !context.runningSupplier().getAsBoolean()) return;
      var state = context.documentFieldOps().entityCorpusState();
      if (state.pendingNer() != 0) return;
      if (!service.prepareReconciliation(state.revision(), state.readerKey())) return;
      var page = context.documentFieldOps().entityPage(
          state.readerKey(), service.reconciliationCursor(), context.batchSize());
      Map<String, Map<String, List<String>>> sources = new LinkedHashMap<>();
      for (var document : page.documents()) {
        context.pacing().pace();
        if (!context.runningSupplier().getAsBoolean() || Thread.currentThread().isInterrupted()) return;
        sources.put(document.id(), document.mentions());
      }
      if (!context.runningSupplier().getAsBoolean() || Thread.currentThread().isInterrupted()) return;
      service.replaceSources(sources);
      if (!context.runningSupplier().getAsBoolean() || Thread.currentThread().isInterrupted()) return;
      var current = context.documentFieldOps().entityCorpusState();
      if (!state.equals(current)) return;
      service.acknowledgeReconciliation(page.cursor(), page.exhausted());
    } catch (Exception failure) {
      context.log().warn("Disambiguation batch incomplete; retrying next idle cycle", failure);
    }
  }
}
