/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;


/** Test fixtures drive the same bounded operations as BackfillScheduler. No scheduling policy lives here. */
public final class LaneBackfillTestSupport {
  private LaneBackfillTestSupport() {}

  public record BackfillContext(DocumentFieldOps documentFieldOps,
        IndexingCoordinator indexingCoordinator,
        IndexingPacing pacing,
        Supplier<EmbeddingProvider> embeddingProviderSupplier,
        Supplier<SpladeEncoder> spladeEncoderSupplier,
        Supplier<NerService> nerServiceSupplier,
        BooleanSupplier runningSupplier,
        BooleanSupplier allowEmbeddingWritesSupplier,
        int batchSize,
        Logger log,
        boolean chunkVectorsEnabled,
        boolean chunkSpladeEnabled,
        boolean lateChunkingEnabled,
        int chunkSlotsPerBatch,
        BooleanSupplier stopRequestedSupplier,
        BooleanSupplier hardStopRequestedSupplier,
        WindowedEmbedProgress windowedEmbedProgress,
        Set<String> publishedDocIds,
        BooleanSupplier spladeAllowedSupplier,
        java.util.function.Predicate<Boolean> unitAllowed,
        java.util.function.Predicate<EnrichmentAccounting.Stage> stageAllowed) {
    public BackfillContext(DocumentFieldOps documentFieldOps,
        IndexingCoordinator indexingCoordinator,
        IndexingPacing pacing,
        Supplier<EmbeddingProvider> embeddingProviderSupplier,
        Supplier<SpladeEncoder> spladeEncoderSupplier,
        Supplier<NerService> nerServiceSupplier,
        BooleanSupplier runningSupplier,
        BooleanSupplier allowEmbeddingWritesSupplier,
        int batchSize,
        Logger log,
        boolean chunkVectorsEnabled,
        boolean chunkSpladeEnabled,
        boolean lateChunkingEnabled,
        int chunkSlotsPerBatch,
        BooleanSupplier stopRequestedSupplier,
        BooleanSupplier hardStopRequestedSupplier,
        WindowedEmbedProgress windowedEmbedProgress,
        Set<String> publishedDocIds,
        BooleanSupplier spladeAllowedSupplier,
        java.util.function.Predicate<Boolean> unitAllowed) {
      this(documentFieldOps,
        indexingCoordinator,
        pacing,
        embeddingProviderSupplier,
        spladeEncoderSupplier,
        nerServiceSupplier,
        runningSupplier,
        allowEmbeddingWritesSupplier,
        batchSize,
        log,
        chunkVectorsEnabled,
        chunkSpladeEnabled,
        lateChunkingEnabled,
        chunkSlotsPerBatch,
        stopRequestedSupplier,
        hardStopRequestedSupplier,
        windowedEmbedProgress,
        publishedDocIds,
        spladeAllowedSupplier,
        unitAllowed,
        stage -> true);
    }
    public BackfillContext(DocumentFieldOps documentFieldOps,
        IndexingCoordinator indexingCoordinator,
        IndexingPacing pacing,
        Supplier<EmbeddingProvider> embeddingProviderSupplier,
        Supplier<SpladeEncoder> spladeEncoderSupplier,
        Supplier<NerService> nerServiceSupplier,
        BooleanSupplier runningSupplier,
        BooleanSupplier allowEmbeddingWritesSupplier,
        int batchSize,
        Logger log,
        boolean chunkVectorsEnabled,
        boolean chunkSpladeEnabled,
        boolean lateChunkingEnabled,
        int chunkSlotsPerBatch,
        BooleanSupplier stopRequestedSupplier,
        BooleanSupplier hardStopRequestedSupplier,
        WindowedEmbedProgress windowedEmbedProgress,
        Set<String> publishedDocIds,
        BooleanSupplier spladeAllowedSupplier) {
      this(documentFieldOps,
        indexingCoordinator,
        pacing,
        embeddingProviderSupplier,
        spladeEncoderSupplier,
        nerServiceSupplier,
        runningSupplier,
        allowEmbeddingWritesSupplier,
        batchSize,
        log,
        chunkVectorsEnabled,
        chunkSpladeEnabled,
        lateChunkingEnabled,
        chunkSlotsPerBatch,
        stopRequestedSupplier,
        hardStopRequestedSupplier,
        windowedEmbedProgress,
        publishedDocIds,
        spladeAllowedSupplier,
        gpu -> true);
    }
    public BackfillContext(DocumentFieldOps documentFieldOps,
        IndexingCoordinator indexingCoordinator,
        IndexingPacing pacing,
        Supplier<EmbeddingProvider> embeddingProviderSupplier,
        Supplier<SpladeEncoder> spladeEncoderSupplier,
        Supplier<NerService> nerServiceSupplier,
        BooleanSupplier runningSupplier,
        BooleanSupplier allowEmbeddingWritesSupplier,
        int batchSize,
        Logger log,
        boolean chunkVectorsEnabled,
        boolean chunkSpladeEnabled,
        boolean lateChunkingEnabled,
        int chunkSlotsPerBatch,
        BooleanSupplier stopRequestedSupplier,
        BooleanSupplier hardStopRequestedSupplier,
        WindowedEmbedProgress windowedEmbedProgress) {
      this(documentFieldOps,
        indexingCoordinator,
        pacing,
        embeddingProviderSupplier,
        spladeEncoderSupplier,
        nerServiceSupplier,
        runningSupplier,
        allowEmbeddingWritesSupplier,
        batchSize,
        log,
        chunkVectorsEnabled,
        chunkSpladeEnabled,
        lateChunkingEnabled,
        chunkSlotsPerBatch,
        stopRequestedSupplier,
        hardStopRequestedSupplier,
        windowedEmbedProgress,
        new HashSet<>(),
        () -> true);
    }
  }
  public record EmbeddingContext(DocumentFieldOps documentFieldOps,
        IndexingCoordinator indexingCoordinator,
        IndexingPacing pacing,
        Supplier<EmbeddingProvider> embeddingProviderSupplier,
        BooleanSupplier runningSupplier,
        BooleanSupplier allowEmbeddingWritesSupplier,
        int batchSize,
        Logger log,
        BooleanSupplier unitAllowed) {
    public EmbeddingContext(DocumentFieldOps documentFieldOps,
        IndexingCoordinator indexingCoordinator,
        IndexingPacing pacing,
        Supplier<EmbeddingProvider> embeddingProviderSupplier,
        BooleanSupplier runningSupplier,
        BooleanSupplier allowEmbeddingWritesSupplier,
        int batchSize,
        Logger log) {
      this(documentFieldOps,
        indexingCoordinator,
        pacing,
        embeddingProviderSupplier,
        runningSupplier,
        allowEmbeddingWritesSupplier,
        batchSize,
        log,
        () -> true);
    }
  }
  public record Outcome(Map<EnrichmentAccounting.Stage, Integer> processed,
      EnrichmentAccounting.Summary accounting, boolean attempted, boolean aborted, long elapsedMs) {
    public int embedProcessed() { return processed.getOrDefault(EnrichmentAccounting.Stage.EMBED, 0)
        + processed.getOrDefault(EnrichmentAccounting.Stage.CHUNK_EMBED, 0); }
    public int spladeProcessed() { return processed.getOrDefault(EnrichmentAccounting.Stage.SPLADE, 0); }
    public int nerProcessed() { return processed.getOrDefault(EnrichmentAccounting.Stage.NER, 0); }
    public boolean wroteAnything() { return accounting.counters().entrySet().stream()
        .anyMatch(e -> (e.getKey().endsWith(".accepted") || e.getKey().endsWith(".window_advances") || e.getKey().endsWith(".rejected_output")) && e.getValue() > 0); }
  }

  public static Outcome runLanes(BackfillContext context) { return runLanes(context, new EnrichmentSelection()); }

  public static Outcome runLanes(BackfillContext source, EnrichmentSelection selection) {
    selection.beginCycle();
    long selectedEpoch = source.indexingCoordinator().bulkDeleteEpoch();
    var selectedAuthority = source.indexingCoordinator().enrichmentAuthority();
    var context = new BackfillContext(source.documentFieldOps(),
        source.indexingCoordinator(),
        source.pacing(),
        source.embeddingProviderSupplier(),
        source.spladeEncoderSupplier(),
        source.nerServiceSupplier(),
        () -> source.runningSupplier().getAsBoolean() && source.indexingCoordinator().bulkDeleteEpoch() == selectedEpoch
            && source.indexingCoordinator().enrichmentAuthority() == selectedAuthority,
        source.allowEmbeddingWritesSupplier(),
        source.batchSize(),
        source.log(),
        source.chunkVectorsEnabled(),
        source.chunkSpladeEnabled(),
        source.lateChunkingEnabled(),
        source.chunkSlotsPerBatch(),
        source.stopRequestedSupplier(),
        source.hardStopRequestedSupplier(),
        source.windowedEmbedProgress(),
        source.publishedDocIds(),
        source.spladeAllowedSupplier(),
        source.unitAllowed(),
        source.stageAllowed());
    long start = System.nanoTime();
    long epoch = context.indexingCoordinator().bulkDeleteEpoch();
    var authority = context.indexingCoordinator().enrichmentAuthority();
    var outcomes = new ArrayList<StageOutcome>();
    var processed = new java.util.EnumMap<EnrichmentAccounting.Stage, Integer>(EnrichmentAccounting.Stage.class);
    if (context.embeddingProviderSupplier().get() != null) {
      if (context.chunkVectorsEnabled()) drive(context, selection, EnrichmentAccounting.Stage.CHUNK_EMBED, outcomes, processed);
      drive(context, selection, EnrichmentAccounting.Stage.EMBED, outcomes, processed);
    }
    if (context.nerServiceSupplier().get() != null && context.nerServiceSupplier().get().isAvailable())
      drive(context, selection, EnrichmentAccounting.Stage.NER, outcomes, processed);
    if (context.spladeEncoderSupplier().get() != null && context.spladeAllowedSupplier().getAsBoolean()) {
      drive(context, selection, EnrichmentAccounting.Stage.SPLADE, outcomes, processed);
    }
    var tickets = new LinkedHashMap<EnrichmentAccounting.Ticket, EnrichmentAccounting.Disposition>();
    var counters = new HashMap<String, Long>();
    var failures = java.util.EnumSet.noneOf(EnrichmentAccounting.Stage.class);
    var acquisitions = new ArrayList<EnrichmentAccounting.Acquisition>();
    for (var outcome : outcomes) {
      outcome.accounting().tickets().forEach((ticket, disposition) -> tickets.merge(ticket, disposition,
          (before, after) -> after == EnrichmentAccounting.Disposition.DEFERRED ? before : after));
      outcome.accounting().counters().forEach((key, count) -> counters.merge(key, count, Long::sum));
      failures.addAll(outcome.accounting().storageFailures());
      acquisitions.addAll(outcome.accounting().acquisitions());
    }
    // Repeated selection is observable in a unit, but these assertions describe distinct rows.
    counters.keySet().removeIf(key -> key.endsWith(".selected") || java.util.Arrays.stream(
        EnrichmentAccounting.Disposition.values()).anyMatch(d -> key.endsWith("." + d.name().toLowerCase(java.util.Locale.ROOT))));
    tickets.forEach((ticket, disposition) -> {
      counters.merge(ticket.stage().key + ".selected", 1L, Long::sum);
      counters.merge(ticket.stage().key + "." + disposition.name().toLowerCase(java.util.Locale.ROOT), 1L, Long::sum);
    });
    boolean aborted = context.stopRequestedSupplier().getAsBoolean()
        || context.hardStopRequestedSupplier().getAsBoolean() || !context.runningSupplier().getAsBoolean()
        || Thread.currentThread().isInterrupted() || context.indexingCoordinator().bulkDeleteEpoch() != epoch
        || context.indexingCoordinator().enrichmentAuthority() != authority;
    return new Outcome(processed, new EnrichmentAccounting.Summary(tickets, counters, failures, acquisitions),
        outcomes.stream().anyMatch(StageOutcome::attempted), aborted, (System.nanoTime() - start) / 1_000_000);
  }

  private static void drive(BackfillContext c, EnrichmentSelection selection, EnrichmentAccounting.Stage stage,
      List<StageOutcome> outcomes, Map<EnrichmentAccounting.Stage, Integer> processed) {
    if (!c.stageAllowed().test(stage)) return;
    var settled = new HashSet<String>();
    long epoch = c.indexingCoordinator().bulkDeleteEpoch();
    var authority = c.indexingCoordinator().enrichmentAuthority();
    Object provider = switch (stage) {
      case EMBED, CHUNK_EMBED -> c.embeddingProviderSupplier().get();
      case SPLADE -> c.spladeEncoderSupplier().get();
      case NER -> c.nerServiceSupplier().get();
    };
    int cap = stage == EnrichmentAccounting.Stage.CHUNK_EMBED ? c.chunkSlotsPerBatch() : c.batchSize();
    boolean firstTurn = true;
    for (int turn = 0; turn < 128 && settled.size() < cap; turn++) {
      long routesBefore = c.windowedEmbedProgress().routesRetained();
      StageOutcome outcome;
      switch (stage) {
        case EMBED, CHUNK_EMBED -> outcome = CombinedEnrichmentBackfillOps.processDenseUnit(denseContext(c), selection, stage);
        case NER -> outcome = NerBackfillOps.processNerUnit(new NerBackfillOps.BackfillContext(c.documentFieldOps(),
        c.indexingCoordinator(),
        c.pacing(),
        c.nerServiceSupplier(),
        () -> c.runningSupplier().getAsBoolean() && !c.hardStopRequestedSupplier().getAsBoolean(),
        c.batchSize(),
        c.log(),
        () -> !c.stopRequestedSupplier().getAsBoolean() && c.unitAllowed().test(false)), selection);
        case SPLADE -> {
          var sparse = new SpladeBackfillOps.BackfillContext(c.documentFieldOps(),
        c.indexingCoordinator(),
        c.pacing(),
        c.spladeEncoderSupplier(),
        () -> c.runningSupplier().getAsBoolean() && !c.hardStopRequestedSupplier().getAsBoolean(),
        c.batchSize(),
        c.chunkSpladeEnabled(),
        c.log(),
        () -> !c.stopRequestedSupplier().getAsBoolean() && c.unitAllowed().test(false));
          outcome = SpladeBackfillOps.processParentBackfill(sparse, selection);
          if (!outcome.attempted() && c.chunkSpladeEnabled()) outcome = SpladeBackfillOps.processChunkBackfill(sparse, selection);
        }
        default -> throw new AssertionError(stage);
      }
      outcomes.add(outcome);
      processed.merge(stage, (int) (outcome.accounting().count(stage, EnrichmentAccounting.Disposition.PUBLISHED)
          + outcome.accounting().count(stage, EnrichmentAccounting.Disposition.COMPLETED_EMPTY)
          + outcome.accounting().count(stage, EnrichmentAccounting.Disposition.REJECTED_OUTPUT)), Integer::sum);
      selection.served(outcome.accounting());
      int previous = settled.size();
      outcome.accounting().tickets().forEach((ticket, disposition) -> {
        if (disposition != EnrichmentAccounting.Disposition.DEFERRED && disposition != EnrichmentAccounting.Disposition.CANCELLED)
          settled.add(ticket.documentId());
      });
      boolean dense = stage == EnrichmentAccounting.Stage.EMBED || stage == EnrichmentAccounting.Stage.CHUNK_EMBED;
      if (firstTurn && dense) cap = Math.min(cap, outcome.accounting().tickets().size());
      firstTurn = false;
      Object currentProvider = switch (stage) {
        case EMBED, CHUNK_EMBED -> c.embeddingProviderSupplier().get();
        case SPLADE -> c.spladeEncoderSupplier().get();
        case NER -> c.nerServiceSupplier().get();
      };
      if (!outcome.attempted() || c.indexingCoordinator().bulkDeleteEpoch() != epoch
          || c.indexingCoordinator().enrichmentAuthority() != authority || provider != currentProvider
          || (dense && (!c.allowEmbeddingWritesSupplier().getAsBoolean()
              || currentProvider == null || !((EmbeddingProvider) currentProvider).isAvailable()))
          || !outcome.accounting().storageFailures().isEmpty()
          || outcome.accounting().tickets().containsValue(EnrichmentAccounting.Disposition.REJECTED_STALE)
          || outcome.accounting().tickets().containsValue(EnrichmentAccounting.Disposition.CANCELLED)
          || outcome.accounting().acquisitions().stream().anyMatch(a -> !a.available())
          || (!dense && outcome.accounting().tickets().containsValue(EnrichmentAccounting.Disposition.DEFERRED))
          || (stage == EnrichmentAccounting.Stage.SPLADE && !outcome.success())
          || !c.runningSupplier().getAsBoolean() || Thread.currentThread().isInterrupted()
          || c.stopRequestedSupplier().getAsBoolean() || c.hardStopRequestedSupplier().getAsBoolean()
          || (previous == settled.size() && routesBefore == c.windowedEmbedProgress().routesRetained()
              && !outcome.accounting().counters().keySet().stream().anyMatch(key -> key.endsWith(".window_advances")))) break;
    }
  }

  public static BackfillContext onlyStage(BackfillContext c, EnrichmentAccounting.Stage stage) {
    return new BackfillContext(c.documentFieldOps(),
        c.indexingCoordinator(),
        c.pacing(),
        c.embeddingProviderSupplier(),
        c.spladeEncoderSupplier(),
        c.nerServiceSupplier(),
        c.runningSupplier(),
        c.allowEmbeddingWritesSupplier(),
        c.batchSize(),
        c.log(),
        c.chunkVectorsEnabled(),
        c.chunkSpladeEnabled(),
        c.lateChunkingEnabled(),
        c.chunkSlotsPerBatch(),
        c.stopRequestedSupplier(),
        c.hardStopRequestedSupplier(),
        c.windowedEmbedProgress(),
        c.publishedDocIds(),
        c.spladeAllowedSupplier(),
        c.unitAllowed(),
        candidate -> candidate == stage);
  }

  public static CombinedEnrichmentBackfillOps.BackfillContext denseContext(BackfillContext c) {
    return new CombinedEnrichmentBackfillOps.BackfillContext(c.documentFieldOps(), c.indexingCoordinator(), c.pacing(),
        c.embeddingProviderSupplier(), c.runningSupplier(), c.allowEmbeddingWritesSupplier(), c.batchSize(), c.log(),
        c.chunkVectorsEnabled(), c.lateChunkingEnabled(), c.chunkSlotsPerBatch(), c.stopRequestedSupplier(),
        c.hardStopRequestedSupplier(), c.windowedEmbedProgress(), c.publishedDocIds(), c.unitAllowed(), c.stageAllowed());
  }

  public static StageOutcome runSparse(SpladeBackfillOps.BackfillContext c) { return runSparse(c, new EnrichmentSelection()); }
  public static StageOutcome runSparse(SpladeBackfillOps.BackfillContext source, EnrichmentSelection selection) {
    var c = new SpladeBackfillOps.BackfillContext(source.documentFieldOps(),
        source.indexingCoordinator(),
        source.pacing(),
        source.spladeEncoderSupplier(),
        source.runningSupplier(),
        source.batchSize(),
        source.chunkSpladeEnabled(),
        source.log(),
        source.unitAllowed());
    if (selection == null) selection = new EnrichmentSelection();
    selection.beginCycle();
    var results = new ArrayList<StageOutcome>();
    int count = 0;
    long epoch = c.indexingCoordinator().bulkDeleteEpoch();
    var authority = c.indexingCoordinator().enrichmentAuthority();
    var provider = c.spladeEncoderSupplier().get();
    while (count < c.batchSize()) {
      var outcome = SpladeBackfillOps.processParentBackfill(c, selection);
      if (!outcome.attempted() && c.chunkSpladeEnabled()) outcome = SpladeBackfillOps.processChunkBackfill(c, selection);
      results.add(outcome);
      selection.served(outcome.accounting());
      count += outcome.accounting().tickets().size();
      if (!outcome.attempted() || !outcome.success() || c.indexingCoordinator().bulkDeleteEpoch() != epoch
          || c.indexingCoordinator().enrichmentAuthority() != authority || c.spladeEncoderSupplier().get() != provider
          || outcome.accounting().tickets().containsValue(EnrichmentAccounting.Disposition.CANCELLED)
          || outcome.accounting().tickets().containsValue(EnrichmentAccounting.Disposition.DEFERRED)
          || outcome.accounting().tickets().containsValue(EnrichmentAccounting.Disposition.REJECTED_STALE)) break;
    }
    return combine(results);
  }

  public static StageOutcome runNer(NerBackfillOps.BackfillContext c) { return runNer(c, new EnrichmentSelection()); }
  public static StageOutcome runNer(NerBackfillOps.BackfillContext c, EnrichmentSelection selection) {
    if (selection == null) selection = new EnrichmentSelection();
    selection.beginCycle();
    var results = new ArrayList<StageOutcome>();
    long epoch = c.indexingCoordinator().bulkDeleteEpoch();
    var authority = c.indexingCoordinator().enrichmentAuthority();
    for (int turn = 0; turn < c.batchSize(); turn++) {
      var outcome = NerBackfillOps.processNerUnit(c, selection);
      results.add(outcome);
      selection.served(outcome.accounting());
      if (!outcome.attempted() || c.indexingCoordinator().bulkDeleteEpoch() != epoch
          || c.indexingCoordinator().enrichmentAuthority() != authority
          || outcome.accounting().tickets().containsValue(EnrichmentAccounting.Disposition.CANCELLED)
          || outcome.accounting().tickets().containsValue(EnrichmentAccounting.Disposition.DEFERRED)
          || outcome.accounting().tickets().containsValue(EnrichmentAccounting.Disposition.REJECTED_STALE)) break;
    }
    return combine(results);
  }

  private static StageOutcome combine(List<StageOutcome> outcomes) {
    var tickets = new LinkedHashMap<EnrichmentAccounting.Ticket, EnrichmentAccounting.Disposition>();
    var counters = new HashMap<String, Long>();
    var failures = java.util.EnumSet.noneOf(EnrichmentAccounting.Stage.class);
    var acquisitions = new ArrayList<EnrichmentAccounting.Acquisition>();
    for (var outcome : outcomes) {
      tickets.putAll(outcome.accounting().tickets());
      outcome.accounting().counters().forEach((key, count) -> counters.merge(key, count, Long::sum));
      failures.addAll(outcome.accounting().storageFailures());
      acquisitions.addAll(outcome.accounting().acquisitions());
    }
    return new StageOutcome(outcomes.stream().allMatch(StageOutcome::success), outcomes.stream().mapToInt(StageOutcome::docsProcessed).sum(),
        outcomes.stream().mapToLong(StageOutcome::elapsedMs).sum(), new EnrichmentAccounting.Summary(tickets, counters, failures, acquisitions),
        outcomes.stream().anyMatch(StageOutcome::attempted));
  }

  public static CombinedEnrichmentBackfillOps.BackfillContext denseContext(EmbeddingContext c) {
    return new CombinedEnrichmentBackfillOps.BackfillContext(c.documentFieldOps(), c.indexingCoordinator(), c.pacing(),
        c.embeddingProviderSupplier(), c.runningSupplier(), c.allowEmbeddingWritesSupplier(), c.batchSize(), c.log(),
        false, false, 0, () -> false, () -> !c.runningSupplier().getAsBoolean(), new WindowedEmbedProgress(), new HashSet<>(),
        gpu -> c.unitAllowed().getAsBoolean(), stage -> true);
  }

  public static StageOutcome runDenseParent(EmbeddingContext c) { return runDenseParent(c, new EnrichmentSelection()); }
  public static StageOutcome runDenseChunk(EmbeddingContext c) { return runDenseChunk(c, new EnrichmentSelection()); }
  public static StageOutcome runDenseParent(EmbeddingContext c, EnrichmentSelection selection) { return embedding(c, selection, false); }
  public static StageOutcome runDenseChunk(EmbeddingContext c, EnrichmentSelection selection) { return embedding(c, selection, true); }
  private static StageOutcome embedding(EmbeddingContext c, EnrichmentSelection selection, boolean chunk) {
    var stage = chunk ? EnrichmentAccounting.Stage.CHUNK_EMBED : EnrichmentAccounting.Stage.EMBED;
    var source = new BackfillContext(c.documentFieldOps(),
        c.indexingCoordinator(),
        c.pacing(),
        c.embeddingProviderSupplier(),
        () -> null,
        () -> null,
        c.runningSupplier(),
        c.allowEmbeddingWritesSupplier(),
        c.batchSize(),
        c.log(),
        chunk,
        false,
        false,
        c.batchSize(),
        () -> false,
        () -> !c.runningSupplier().getAsBoolean(),
        new WindowedEmbedProgress(),
        new HashSet<>(),
        () -> false,
        gpu -> c.unitAllowed().getAsBoolean(),
        candidate -> candidate == stage);
    var result = runLanes(source, selection);
    return new StageOutcome(result.wroteAnything(), result.embedProcessed(), result.elapsedMs(), result.accounting(), result.attempted());
  }
}
