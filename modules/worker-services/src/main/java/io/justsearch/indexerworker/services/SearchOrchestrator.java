/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.LuceneRuntime;
import io.justsearch.indexerworker.disambiguation.EntityClusterSnapshot;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.server.EncoderBindings;
import io.justsearch.indexerworker.services.execute.SearchExecutor;
import io.justsearch.indexerworker.services.input.SearchInputCapture;
import io.justsearch.indexerworker.services.input.SearchInputs;
import io.justsearch.indexerworker.services.plan.SearchDecision;
import io.justsearch.indexerworker.services.plan.SearchPlanner;
import io.justsearch.indexerworker.services.respond.SearchResponseBuilder;
import io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.util.function.Supplier;

/**
 * Search orchestration facade (tempdoc 517 + tempdoc 516 P3 cut).
 *
 * <p>The class formerly known as the "1,919-LOC mega-class" — now a thin facade that
 * wires four collaborators (capture → plan → execute → respond) and exposes the
 * remaining deferred-injection setters that {@code WorkerSearchService} continues to call.
 *
 * <p>Tempdoc 516 P3 / Slice 5 (W7.2) cut: the {@code SpladeEncoder} and {@code
 * BgeM3Encoder} volatile slots that 517 itself flagged as "Phase 2 SearchCollaboratorsHolder
 * migration deferred" are now backed by the shared {@link EncoderBindings} registry
 * (also held by {@code IndexingLoop}). DWAS binds once on the registry and both consumers
 * see the update via the same volatile slot. The 4 remaining post-ctor setters
 * (embeddingProvider, clusterSnapshotSupplier, activeGenerationSupplier,
 * spladeIdfQueryEncoder) follow distinct async-load paths that aren't part of the
 * EncoderBindings symmetry.
 *
 * <p>The facade's {@code execute(...)} body is four lines.
 */
public final class SearchOrchestrator {

  // Deferred-injection volatile fields. After tempdoc 516 W7.2 the splade/bgeM3 slots
  // moved to the shared EncoderBindings registry below.
  private volatile EmbeddingProvider embeddingProvider;
  private volatile Supplier<EntityClusterSnapshot> clusterSnapshotSupplier;
  private volatile Supplier<String> activeGenerationSupplier = () -> null;
  private volatile SpladeIdfQueryEncoder spladeIdfQueryEncoder;
  private final EncoderBindings encoderBindings;

  private final SearchInputCapture capture;
  private final SearchPlanner planner;
  private final SearchExecutor executor;
  private final SearchResponseBuilder responseBuilder;
  // Tempdoc 687 R3d: retained only for the boot-time warmUp() empty-index guard below.
  private final io.justsearch.adapters.lucene.runtime.IndexCountOps indexCountOps;

  /** Synthetic, non-empty query text for {@link #warmUp()} — never sent to a real user. */
  private static final String WARMUP_QUERY_TEXT = "warmup";

  /** Back-compat ctor — default-constructs an empty EncoderBindings (tests, etc.). */
  public SearchOrchestrator(LuceneRuntime lifecycle, EmbeddingProvider embeddingProvider) {
    this(lifecycle, embeddingProvider, null);
  }

  /**
   * Canonical ctor — DWAS passes the same {@link EncoderBindings} instance to both
   * {@code SearchOrchestrator} and {@code IndexingLoop} so {@code wireSpladeEncoder} /
   * {@code wireBgeM3Encoder} bind once and both sides observe the update.
   */
  public SearchOrchestrator(
      LuceneRuntime lifecycle,
      EmbeddingProvider embeddingProvider,
      EncoderBindings encoderBindings) {
    this.embeddingProvider = embeddingProvider;
    this.encoderBindings = encoderBindings != null ? encoderBindings : new EncoderBindings();
    this.capture =
        new SearchInputCapture(
            lifecycle.textQueryOps(),
            lifecycle.indexCountOps(),
            lifecycle.commitOps(),
            lifecycle.documentFieldOps(),
            lifecycle::resolvedConfig,
            lifecycle::indexAnalyzerOrNull,
            lifecycle::latestCommitUserDataBestEffort,
            this::encoderSnapshot);
    this.planner = new SearchPlanner(lifecycle::resolvedConfig);
    this.executor =
        new SearchExecutor(
            lifecycle.textQueryOps(),
            lifecycle.readPathOps(),
            lifecycle.hybridSearchOps(),
            lifecycle.chunkSearchOps(),
            lifecycle::resolvedConfig);
    this.responseBuilder =
        new SearchResponseBuilder(
            lifecycle.indexCountOps(),
            lifecycle.documentFieldOps(),
            lifecycle.textQueryOps(),
            lifecycle.facetingEngine(),
            lifecycle::indexAnalyzerOrNull,
            lifecycle::resolvedConfig);
    this.indexCountOps = lifecycle.indexCountOps();
  }

  /** Reads the 6 volatile slots once per request into a SearchInputCapture snapshot. */
  private SearchInputCapture.EncoderSnapshot encoderSnapshot() {
    return new SearchInputCapture.EncoderSnapshot(
        embeddingProvider,
        clusterSnapshotSupplier != null ? clusterSnapshotSupplier : () -> null,
        activeGenerationSupplier,
        encoderBindings.spladeEncoder(),
        spladeIdfQueryEncoder,
        encoderBindings.bgeM3Encoder());
  }

  // === Deferred-injection setters (preserved for WorkerSearchService wiring) ===
  // 516 P3 / W7.2: setSpladeEncoder + setBgeM3Encoder removed — encoderBindings.bindX() now.

  public void setActiveGenerationSupplier(Supplier<String> supplier) {
    this.activeGenerationSupplier = supplier != null ? supplier : () -> null;
  }

  public void setEmbeddingProvider(EmbeddingProvider embeddingProvider) {
    this.embeddingProvider = embeddingProvider;
  }

  public void setClusterSnapshotSupplier(Supplier<EntityClusterSnapshot> supplier) {
    this.clusterSnapshotSupplier = supplier;
  }

  public void setSpladeIdfQueryEncoder(SpladeIdfQueryEncoder encoder) {
    this.spladeIdfQueryEncoder = encoder;
  }

  /** The 4-line facade body. */
  public SearchResponse execute(
      SearchRequest request, boolean allowQueryEmbeddings, String compatReasonCode) {
    SearchInputs inputs = capture.capture(request, allowQueryEmbeddings, compatReasonCode);
    SearchDecision decision = planner.plan(inputs);
    SearchOutcome outcome = executor.execute(decision, inputs);
    return responseBuilder.build(outcome, decision, inputs);
  }

  /**
   * Tempdoc 687 R3d: boot-time warm-up. Runs one synthetic, non-blank BM25 query through
   * capture → plan → execute — the same cold components a real user's first query pays
   * JIT/class-load cost for (the ICU analyzer via {@code SearchInputCapture.computeQpp},
   * the Lucene query builder + {@code IndexSearcher} via {@code SearchExecutor}'s sparse-
   * shortcut path, and the QPP term-stats accessors).
   *
   * <p>Deliberately stops before {@link SearchResponseBuilder#build}: that step is where
   * {@code OperationalMetrics.recordSearch(...)} lives (worker-internal search-count/latency
   * telemetry surfaced on {@code /api/status}), so calling only capture/plan/execute keeps
   * this pass invisible to it — a synthetic boot-time call must not appear as the user's
   * first "real" search. It is also below the gRPC boundary entirely: this method is called
   * directly by {@code KnowledgeServer} in-process, so it never reaches the Head's
   * app-services feedback layer (feature snapshots, dispositions, GPL triples), which only
   * runs against gRPC search responses that actually cross the wire.
   *
   * @return {@code true} if the warm-up pass ran, {@code false} if it was skipped because the
   *     index has zero documents (nothing to search yet)
   */
  public boolean warmUp() {
    if (indexCountOps.docCount() <= 0) {
      return false;
    }
    SearchRequest request =
        SearchRequest.newBuilder()
            .setQuery(WARMUP_QUERY_TEXT)
            .setLimit(1)
            .setPipeline(io.justsearch.ipc.PipelineConfig.newBuilder().setSparseEnabled(true).build())
            .build();
    SearchInputs inputs = capture.capture(request, false, null);
    SearchDecision decision = planner.plan(inputs);
    executor.execute(decision, inputs);
    return true;
  }

  // ============================================================
  // Static delegates preserved for backwards-compatibility with existing tests.
  // Both helpers' substantive logic lives on SearchPlanner; these delegates exist
  // so SearchOrchestratorPipelineDispatchTest does not need to be updated in this
  // slice. ArchUnit dependency-direction is not affected — these are static
  // method delegates without runtime imports.
  // ============================================================

  static io.justsearch.ipc.PipelineConfig modeToDefaultPipeline(
      io.justsearch.ipc.SearchMode mode) {
    return SearchPlanner.modeToDefaultPipeline(mode);
  }

  static String deriveActualMode(boolean sparseRan, boolean denseRan, boolean spladeRan) {
    return SearchPlanner.deriveActualMode(sparseRan, denseRan, spladeRan);
  }

  static String deriveEffectiveMode(io.justsearch.ipc.PipelineConfig pipeline) {
    return SearchPlanner.deriveActualMode(
        pipeline.getSparseEnabled(), pipeline.getDenseEnabled(), pipeline.getSpladeEnabled());
  }
}
