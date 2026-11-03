/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
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
      Logger log,
      IndexingCoordinator indexingCoordinator) {
    public BackfillContext(DocumentFieldOps documentFieldOps, IndexingPacing pacing,
        Supplier<DisambiguationService> serviceSupplier, BooleanSupplier runningSupplier,
        int batchSize, Logger log) {
      this(documentFieldOps, pacing, serviceSupplier, runningSupplier, batchSize, log, null);
    }
  }

  /**
   * Processes one resumable page of NER-completed documents.
   *
   * <p>Acknowledges progress only after coherent reads, source replacement, and snapshot
   * publication succeed. The final page retires sources absent from the completed scan.
   */
  public static void processDisambiguationBackfill(BackfillContext context) {
    try {
      var coordinator = context.indexingCoordinator();
      var authority = coordinator == null ? null : coordinator.enrichmentAuthority();
      long epoch = coordinator == null ? 0 : coordinator.bulkDeleteEpoch();
      BooleanSupplier stopped = () -> EnrichmentBatch.hardStop(
          () -> !context.runningSupplier().getAsBoolean(), coordinator, epoch, authority);
      DisambiguationService service = context.serviceSupplier().get();
      if (service == null || !service.isAvailable() || stopped.getAsBoolean()) return;
      var state = context.documentFieldOps().entityCorpusState();
      if (state.pendingNer() != 0) return;
      if (!service.prepareReconciliation(state.revision(), state.readerKey())) return;
      var page = context.documentFieldOps().entityPage(
          state.readerKey(), service.reconciliationCursor(), context.batchSize());
      Map<String, Map<String, List<String>>> sources = new LinkedHashMap<>();
      for (var document : page.documents()) {
        context.pacing().pace();
        if (stopped.getAsBoolean() || context.serviceSupplier().get() != service) return;
        sources.put(document.id(), document.mentions());
      }
      if (stopped.getAsBoolean() || context.serviceSupplier().get() != service) return;
      service.replaceSources(sources, () -> {
        context.pacing().pace();
        return !stopped.getAsBoolean() && context.serviceSupplier().get() == service;
      });
      if (stopped.getAsBoolean() || context.serviceSupplier().get() != service) return;
      var current = context.documentFieldOps().entityCorpusState();
      if (!state.equals(current)) return;
      if (coordinator == null) service.acknowledgeReconciliation(page.cursor(), page.exhausted());
      else coordinator.withEnrichmentAuthority(authority, () -> {
        if (!stopped.getAsBoolean() && context.serviceSupplier().get() == service) {
          service.acknowledgeReconciliation(page.cursor(), page.exhausted());
        }
        return null;
      });
    } catch (Exception failure) {
      context.log().warn("Disambiguation batch incomplete; retrying next idle cycle", failure);
    }
  }
}
