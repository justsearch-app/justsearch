/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.execute;

import io.justsearch.adapters.lucene.runtime.AdaptiveWeightSelector;
import io.justsearch.adapters.lucene.runtime.ChunkSearchOps;
import io.justsearch.adapters.lucene.runtime.HitProvenanceProjector;
import io.justsearch.adapters.lucene.runtime.HybridFusionUtils;
import io.justsearch.adapters.lucene.runtime.HybridSearchOps;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.QueryFilterBuilder;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.adapters.lucene.runtime.TextQueryOps;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineFutures;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.SearchOutcome;
import io.justsearch.indexerworker.services.WorkerServiceException;
import io.justsearch.indexerworker.services.SearchReasonCode;
import io.justsearch.indexerworker.services.input.SearchInputs;
import io.justsearch.indexerworker.services.plan.ChunkMergeDirective;
import io.justsearch.indexerworker.services.plan.ChunkMergeInputs;
import io.justsearch.indexerworker.services.plan.LegSet;
import io.justsearch.indexerworker.services.plan.SearchDecision;
import io.justsearch.indexing.SchemaFields;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pattern-match dispatcher for {@link SearchDecision} (tempdoc 517).
 *
 * <p>One outer {@code switch} over the four decision variants, with an inner
 * {@code switch} over {@link LegSet} for {@link SearchDecision.MultiLegDecision}.
 * Leg-execution helpers (formerly private methods on the monolithic orchestrator)
 * live here. Each variant handler produces a {@link SearchOutcome} which the
 * response builder reads alongside the decision to project the wire response.
 */
public final class SearchExecutor {
  private static final Logger log = LoggerFactory.getLogger(SearchExecutor.class);
  // Tempdoc 517 — the tracer is looked up on each call rather than cached as a
  // static field. The cost is a single map lookup per request (negligible) and
  // it makes the OTel SDK swap-in for tests (see SearchExecutorOtelTopologyTest)
  // observable. With a static-final capture, any test that runs before SDK
  // installation would bind the tracer to the no-op default for the whole JVM.
  private static Tracer tracer() {
    return GlobalOpenTelemetry.getTracer("io.justsearch.worker.search");
  }

  private static final String BRANCH_FUSION_STRATEGY_RRF = "rrf";
  private static final String BRANCH_FUSION_STRATEGY_CC = "cc";
  private static final int CHUNK_INITIAL_CANDIDATE_MULTIPLIER = 10;
  private static final int CHUNK_RETRY_MULTIPLIER = 2;
  private static final String CHUNK_SOURCE_DOC_ID_FIELD = "_chunk_source_doc_id";
  private static final Set<String> CHUNK_COLLAPSE_MAX_EVIDENCE_SCORE_KEYS =
      Set.of("chunk_sparse", "chunk_vector", "chunk_splade");
  private static final Set<String> CHUNK_COLLAPSE_MIN_POSITIVE_RANK_KEYS =
      Set.of("chunk_sparse_rank", "chunk_vector_rank", "chunk_splade_rank");

  private final TextQueryOps textQueryOps;
  private final ReadPathOps readPathOps;
  private final HybridSearchOps hybridSearchOps;
  private final ChunkSearchOps chunkSearchOps;
  private final Supplier<ResolvedConfig> resolvedConfigSupplier;
  private final LuceneExecutorRegistrations executorRegistrations;

  public SearchExecutor(
      TextQueryOps textQueryOps,
      ReadPathOps readPathOps,
      HybridSearchOps hybridSearchOps,
      ChunkSearchOps chunkSearchOps,
      Supplier<ResolvedConfig> resolvedConfigSupplier,
      LuceneExecutorRegistrations executorRegistrations) {
    this.textQueryOps = textQueryOps;
    this.readPathOps = readPathOps;
    this.hybridSearchOps = hybridSearchOps;
    this.chunkSearchOps = chunkSearchOps;
    this.resolvedConfigSupplier = resolvedConfigSupplier;
    this.executorRegistrations = Objects.requireNonNull(executorRegistrations, "executorRegistrations");
  }

  /**
   * Dispatches on the decision variant. Returns a {@link SearchOutcome} carrying
   * the runtime-derived state (hits, timings, chunk-merge applied, correction
   * applied, etc.). Pre-committed reason codes (e.g. {@code ChunkMergeDirective.Skip})
   * are read off the decision by the response builder — this method only records
   * runtime-derived state.
   */
  /**
   * Runs the decided search. The {@code ctx} overload is the live one; see
   * {@link #execute(SearchDecision, SearchInputs)} for why the other exists.
   */
  public SearchOutcome execute(SearchDecision decision, SearchInputs inputs, CallContext ctx) {
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(inputs, "inputs");
    CallContext call = ctx == null ? CallContext.none() : ctx;
    abortIfCancelled(call, "dispatch");
    return switch (decision) {
      case SearchDecision.EmptyQueryDecision e -> handleEmpty();
      case SearchDecision.BlockedDecision b -> handleBlocked();
      case SearchDecision.SparseShortcut s -> runSparseShortcut(s, inputs, call);
      case SearchDecision.MultiLegDecision m -> runMultiLeg(m, inputs, call);
    };
  }

  /**
   * Uncancellable overload, for the two callers that genuinely have no caller to abandon them: the
   * boot-time warm-up pass and the tests. It is not a convenience — routing a real search through
   * it would silently reinstate the pre-review behaviour, so the live path does not use it.
   */
  public SearchOutcome execute(SearchDecision decision, SearchInputs inputs) {
    return execute(decision, inputs, CallContext.none());
  }

  /**
   * Stops the search if the caller has gone (review B3).
   *
   * <p>On the wire a deadline or a client disconnect ended the server's work: gRPC cancelled the
   * server call and the handler's next write failed. In process nothing has that authority — the
   * client's budget releases the CALLER at the deadline, but the search itself runs on until it
   * finishes, holding a call thread, an index searcher and (on a multi-leg query) a virtual-thread
   * fan-out. Under load that is the difference between a slow search and a search that is still
   * being paid for long after nobody wants it, which is precisely how a bounded thread pool starts
   * rejecting calls that would have succeeded.
   *
   * <p>So the poll goes where the work is divisible: between the pipeline's four phases, and inside
   * this class between retrieval and the fusion/merge phase that follows it. Not finer — a poll
   * inside a Lucene collector would be a different mechanism (an interruptible collector), and not
   * coarser, because the phases either side of retrieval are where the seconds are.
   *
   * @throws WorkerServiceException {@code CANCELLED} if the caller has abandoned the call
   */
  private static void abortIfCancelled(CallContext ctx, String stage) {
    if (ctx.cancelled()) {
      throw WorkerServiceException.cancelled("search cancelled by caller at stage: " + stage);
    }
  }

  private SearchOutcome handleEmpty() {
    return SearchOutcome.empty(new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0));
  }

  private SearchOutcome handleBlocked() {
    return SearchOutcome.empty(new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0));
  }

  /**
   * The parent span every leg of this search hangs off.
   *
   * <p>Lane F stage A item A9: this used to read
   * {@code TracingServerInterceptor.currentOtelContext()}, a gRPC {@code Context} key the server
   * interceptor stashed the extracted W3C context under. The interceptor existed because the
   * transport delivered the trace headers on a thread that had no OTel context of its own. In
   * process the caller IS the caller: {@code WorkerSearchService.search} runs the orchestrator
   * synchronously on the thread that called the port, so the Head's span is already current here
   * and the extraction step has nothing left to do. It is read ONCE per search, at the top, because
   * the legs below run on executor pools and each sets this value as its explicit parent — which is
   * the same reason the interceptor version read it once.
   */
  private SearchOutcome runSparseShortcut(
      SearchDecision.SparseShortcut decision, SearchInputs inputs, CallContext ctx) {
    Context parentCtx = Context.current();
    var request = inputs.request();
    String queryString = request.getQuery();
    var runtimeFilters = inputs.runtimeFilters();
    var boostRuntimeFilters =
        io.justsearch.indexerworker.util.ProtoConverters.toRuntimeFilters(
            request.hasBoostFilters() ? request.getBoostFilters() : null);
    var runtimeSort = io.justsearch.indexerworker.util.ProtoConverters.toRuntimeSort(request.getSort());
    String cursor = request.getCursor();
    Set<String> projection =
        request.getProjectionList().isEmpty()
            ? Set.of()
            : new java.util.HashSet<>(request.getProjectionList());

    Span retrievalSpan =
        tracer()
            .spanBuilder("search/retrieval")
            .setParent(parentCtx)
            .setAttribute("search.mode", "TEXT")
            .startSpan();
    long retrievalStartNs = System.nanoTime();
    LuceneRuntimeTypes.SearchResult result;
    org.apache.lucene.search.Query queryForSpans = null;
    org.apache.lucene.search.Query luceneQuery;
    boolean correctionApplied = false;
    String correctedQuery = null;
    String chunkQueryText = queryString;
    long retrievalMs;
    try {
      try {
        luceneQuery = textQueryOps.buildTextQuery(queryString, runtimeFilters, decision.runtimeSyntax());
        if (luceneQuery != null && boostRuntimeFilters != null) {
          var boostQb = new org.apache.lucene.search.BooleanQuery.Builder();
          boostQb.add(luceneQuery, org.apache.lucene.search.BooleanClause.Occur.MUST);
          QueryFilterBuilder.applyBoostFilters(
              boostQb, boostRuntimeFilters, QueryFilterBuilder.DEFAULT_BOOST_WEIGHT);
          luceneQuery = boostQb.build();
        }
      } catch (org.apache.lucene.queryparser.classic.ParseException e) {
        if (decision.runtimeSyntax() == LuceneRuntimeTypes.QuerySyntax.LUCENE) {
          throw new IllegalArgumentException("Invalid query syntax: " + e.getMessage());
        }
        log.warn("Failed to parse query", e);
        // Tempdoc 517 narrowed SensitiveQuery to the Head HTTP boundary, so the raw queryString
        // is unwrapped inside Worker. Drop to TRACE so the failure-diagnosis affordance survives
        // for ad-hoc debugging while staying out of any reasonable production log level. The
        // diagnostics export DOES bundle this Logback-written log file — DiagnosticsServiceImpl's
        // addDirectoryRedacted(zos, logsDir, "logs") zips engine.log with path-only redaction, no
        // query/content redaction — so staying at TRACE (below the Engine's default INFO level) is
        // what keeps this text out of exported diagnostics, not any exemption of logs from the ZIP.
        // Observations.md item #205 follow-up: typed in-process SafeQueryString wrapper deferred.
        log.trace("Failed query text: {}", queryString);
        luceneQuery = null;
      }

      if (luceneQuery == null) {
        result = new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0);
      } else {
        queryForSpans = luceneQuery;
        result = readPathOps.search(luceneQuery, decision.retrievalLimit(), projection, runtimeSort, cursor);

        if (decision.correctionRetryEnabled()
            && result.totalHits() == 0
            && decision.runtimeSyntax() == LuceneRuntimeTypes.QuerySyntax.SIMPLE) {
          var search = resolvedConfigSupplier.get().search();
          if (search.corrections().enabled() && search.corrections().zeroHitRetryEnabled()) {
            var fuzzyResult =
                textQueryOps.buildFuzzyTextQuery(
                    queryString, runtimeFilters, search.corrections().maxEditDistance());
            if (fuzzyResult != null) {
              var corrResult =
                  readPathOps.search(
                      fuzzyResult.query(), decision.retrievalLimit(), projection, runtimeSort, cursor);
              if (corrResult.totalHits() >= search.corrections().dfThreshold()) {
                queryForSpans = fuzzyResult.query();
                result = corrResult;
                correctionApplied = true;
                correctedQuery = fuzzyResult.correctedText();
                chunkQueryText = correctedQuery;
                log.info("Zero-hit retry: corrections yielded {} hits", result.totalHits());
              }
            }
          }
        }

        if (decision.correctionRetryEnabled()
            && !correctionApplied
            && result.totalHits() > 0
            && decision.runtimeSyntax() == LuceneRuntimeTypes.QuerySyntax.SIMPLE) {
          var ptSearch = resolvedConfigSupplier.get().search();
          if (ptSearch.corrections().enabled()) {
            var perTermResult =
                textQueryOps.buildPerTermFuzzyQuery(
                    queryString, runtimeFilters, ptSearch.corrections().maxEditDistance());
            if (perTermResult != null) {
              var correctedResult =
                  readPathOps.search(
                      perTermResult.query(), decision.retrievalLimit(), projection, runtimeSort, cursor);
              if (correctedResult.totalHits() > result.totalHits()
                  && correctedResult.totalHits() >= ptSearch.corrections().dfThreshold()) {
                queryForSpans = perTermResult.query();
                result = correctedResult;
                correctionApplied = true;
                correctedQuery = perTermResult.correctedText();
                chunkQueryText = correctedQuery;
                log.info("Per-term correction: {} hits", result.totalHits());
              }
            }
          }
        }
      }
      retrievalMs = (System.nanoTime() - retrievalStartNs) / 1_000_000;
      retrievalSpan.setAttribute("search.took_ms", retrievalMs);
      // Tempdoc 553 Phase A: the retrieval phase's documents, projected onto the span as an
      // OpenInference RETRIEVER (single deriver — no hand-authored per-hit attrs at the site).
      retrievalSpan.setAllAttributes(OpenInferenceSpanProjection.retriever(result));
    } finally {
      retrievalSpan.end();
    }

    // Tempdoc 549 Slice 3c (U2): single BM25 text leg (no fusion); survives into/through chunk merge.
    result = HitProvenanceProjector.attachSingleLeg(result, HitProvenanceProjector.LegKind.BM25);

    // Review B3: retrieval is done, and the chunk merge below is a second retrieval round of its
    // own (its own bm25/knn/splade legs, its own fusion). If the caller left during the first, it
    // must not pay for the second.
    abortIfCancelled(ctx, "retrieval");

    var chunkOutcome = maybeApplyChunkMerge(decision.chunkMerge(), result, inputs, chunkQueryText);
    // Facet computation is deferred to SearchResponseBuilder (which owns FacetingEngine).
    // The decision carries the FacetCompute discriminator + arguments; the builder reads
    // the decision and invokes facetingEngine.computeFacets there.
    return new SearchOutcome(
        chunkOutcome.result(),
        null,
        queryForSpans,
        chunkQueryText,
        retrievalMs,
        correctionApplied,
        correctedQuery,
        chunkOutcome.applied(),
        chunkOutcome.reason(),
        chunkOutcome.branchFusionStrategy(),
        chunkOutcome.branchFusionContributed(),
        false,
        chunkOutcome.chunkMergeMs(),
        chunkOutcome.chunkBm25Ns(),
        chunkOutcome.chunkKnnNs(),
        chunkOutcome.chunkSpladeNs(),
        chunkOutcome.chunkRetry(),
        chunkOutcome.branchFusionNs());
  }

  private SearchOutcome runMultiLeg(
      SearchDecision.MultiLegDecision decision, SearchInputs inputs, CallContext ctx) {
    Context parentCtx = Context.current();
    var request = inputs.request();
    String queryString = request.getQuery();
    var runtimeFilters = inputs.runtimeFilters();
    var boostRuntimeFilters =
        io.justsearch.indexerworker.util.ProtoConverters.toRuntimeFilters(
            request.hasBoostFilters() ? request.getBoostFilters() : null);
    // Tempdoc 549 Phase D2: the numeric detail tier is requested via include_detail; `debug` is
    // the transitional alias. Either drives fusion-time detail computation (some keys are only
    // computed when on — Slice-1 interrogation finding), so widen the flag end-to-end here.
    boolean debug = request.getDebug() || request.getIncludeDetail();
    String effectiveMode = decision.legs().effectiveModeLabel();
    var syntax = decision.runtimeSyntax();
    // Tempdoc 821 §P: the lexical leg honours the request's query_syntax, so a malformed LUCENE
    // query must fail the same way the sparse-only shortcut fails it (INVALID_ARGUMENT,
    // SearchExecutor:156) rather than degrading to a silent 0-hit answer. The legs themselves run
    // inside fusion futures and must not throw, so the parse is probed here, once.
    //
    // Probed for EVERY LUCENE multi-leg request, not only the ones with a lexical leg: every
    // multi-leg response re-parses this same query for the headline count
    // (SearchResponseBuilder#computeMatchCount) and for the facet scan, so on a dense-only or
    // splade-only request a malformed query would otherwise surface as "matchCount 0" beside real
    // hits — the exact count-vs-hits contradiction 821 §L.3 exists to prevent. SIMPLE requests
    // take no extra parse.
    if (syntax == LuceneRuntimeTypes.QuerySyntax.LUCENE) {
      try {
        textQueryOps.buildTextQuery(queryString, runtimeFilters, syntax);
      } catch (org.apache.lucene.queryparser.classic.ParseException e) {
        throw new IllegalArgumentException("Invalid query syntax: " + e.getMessage());
      }
    }

    Span retrievalSpan =
        tracer()
            .spanBuilder("search/retrieval")
            .setParent(parentCtx)
            .setAttribute("search.mode", effectiveMode)
            .startSpan();
    long retrievalStartNs = System.nanoTime();
    LuceneRuntimeTypes.SearchResult result;
    boolean spladeExecuted = false;
    long retrievalMs;
    try {
      result =
          switch (decision.legs()) {
            case LegSet.ThreeWay tw -> {
              spladeExecuted = true;
              yield runThreeWay(
                  tw, queryString, runtimeFilters, boostRuntimeFilters, syntax, debug, retrievalSpan,
                  ctx.engineContext().urgency());
            }
            case LegSet.Bm25Dense bd ->
                debug
                    ? hybridSearchOps.searchHybridWithDebug(
                        queryString,
                        toFloatArray(bd.vector().vector()),
                        bd.retrievalLimit(),
                        runtimeFilters,
                        syntax,
                        ctx.engineContext().urgency())
                    : hybridSearchOps.searchHybridFiltered(
                        queryString,
                        toFloatArray(bd.vector().vector()),
                        bd.retrievalLimit(),
                        QueryFilterBuilder.buildFilterQueryOnly(runtimeFilters),
                        syntax,
                        ctx.engineContext().urgency());
            case LegSet.DenseOnly d ->
                // Tempdoc 549 Slice 3c (U2): single dense leg, no fusion.
                HitProvenanceProjector.attachSingleLeg(
                    readPathOps.searchVector(
                        toFloatArray(d.vector().vector()),
                        d.retrievalLimit(),
                        QueryFilterBuilder.buildFilterQueryOnly(runtimeFilters)),
                    HitProvenanceProjector.LegKind.DENSE);
            case LegSet.SpladeOnly p -> {
              spladeExecuted = true;
              yield HitProvenanceProjector.attachSingleLeg(
                  searchSplade(p.splade().weights(), p.retrievalLimit(), runtimeFilters),
                  HitProvenanceProjector.LegKind.SPLADE);
            }
            case LegSet.Bm25Splade bs -> {
              spladeExecuted = true;
              var bm25Result =
                  textQueryOps.searchText(
                      queryString, bs.retrievalLimit(), runtimeFilters, boostRuntimeFilters, syntax);
              var spladeResult =
                  searchSplade(bs.splade().weights(), bs.retrievalLimit(), runtimeFilters);
              var fused = fuseLegs(List.of(bm25Result, spladeResult), bs.retrievalLimit(), debug);
              yield HitProvenanceProjector.attachRetrieval(
                  fused, bm25Result, spladeResult, null, "rrf");
            }
            case LegSet.DenseSplade ds -> {
              spladeExecuted = true;
              var denseResult =
                  readPathOps.searchVector(
                      toFloatArray(ds.vector().vector()),
                      ds.retrievalLimit(),
                      QueryFilterBuilder.buildFilterQueryOnly(runtimeFilters));
              var spladeResult =
                  searchSplade(ds.splade().weights(), ds.retrievalLimit(), runtimeFilters);
              var fused = fuseLegs(List.of(denseResult, spladeResult), ds.retrievalLimit(), debug);
              yield HitProvenanceProjector.attachRetrieval(
                  fused, null, spladeResult, denseResult, "rrf");
            }
            case LegSet.Bm25Only b ->
                HitProvenanceProjector.attachSingleLeg(
                    textQueryOps.searchText(
                        queryString, b.retrievalLimit(), runtimeFilters, boostRuntimeFilters, syntax),
                    HitProvenanceProjector.LegKind.BM25);
          };
      retrievalMs = (System.nanoTime() - retrievalStartNs) / 1_000_000;
      retrievalSpan.setAttribute("search.took_ms", retrievalMs);
      retrievalSpan.setAttribute("search.mode", effectiveMode);
      // Tempdoc 553 Phase A: OpenInference RETRIEVER projection of the retrieval-phase result.
      retrievalSpan.setAllAttributes(OpenInferenceSpanProjection.retriever(result));
    } finally {
      retrievalSpan.end();
    }

    // Review B3: same seam as the sparse shortcut, and the one that matters most — a multi-leg
    // retrieval has just fanned out across virtual threads, and the chunk branch below fans out
    // again. This is the point where an abandoned search stops costing anything.
    abortIfCancelled(ctx, "retrieval");

    // Facet computation deferred to SearchResponseBuilder (which owns FacetingEngine).
    var chunkOutcome =
        maybeApplyChunkMerge(decision.chunkMerge(), result, inputs, queryString);
    return new SearchOutcome(
        chunkOutcome.result(),
        null,
        null,
        queryString,
        retrievalMs,
        false,
        null,
        chunkOutcome.applied(),
        chunkOutcome.reason(),
        chunkOutcome.branchFusionStrategy(),
        chunkOutcome.branchFusionContributed(),
        spladeExecuted,
        chunkOutcome.chunkMergeMs(),
        chunkOutcome.chunkBm25Ns(),
        chunkOutcome.chunkKnnNs(),
        chunkOutcome.chunkSpladeNs(),
        chunkOutcome.chunkRetry(),
        chunkOutcome.branchFusionNs());
  }

  private LuceneRuntimeTypes.SearchResult runThreeWay(
      LegSet.ThreeWay tw,
      String queryString,
      LuceneRuntimeTypes.RuntimeSearchFilters runtimeFilters,
      LuceneRuntimeTypes.RuntimeSearchFilters boostRuntimeFilters,
      LuceneRuntimeTypes.QuerySyntax syntax,
      boolean debug,
      Span retrievalSpan,
      EngineContext.Urgency urgency) {
    ResolvedConfig rc3 = resolvedConfigSupplier.get();
    ResolvedConfig.HybridSearch hs3 = rc3 != null ? rc3.hybridSearch() : null;
    int candidateMax = Math.max(hs3 != null ? hs3.candidateLimitMax() : 100, tw.retrievalLimit());
    int textMult = hs3 != null ? hs3.textCandidateMultiplier() : 10;
    int vectorMult = hs3 != null ? hs3.vectorCandidateMultiplier() : 10;
    int textCandLimit = Math.min(tw.retrievalLimit() * Math.max(1, textMult), candidateMax);
    int vectorCandLimit = Math.min(tw.retrievalLimit() * Math.max(1, vectorMult), candidateMax);

    Context otelCtx = Context.current().with(retrievalSpan);
    LuceneRuntimeTypes.SearchResult result;
    try (var executor = executorRegistrations.openSearchFanout(urgency)) {
      var bm25F =
          EngineFutures.supplyAsync(
              () -> {
                try (Scope ctxScope = otelCtx.makeCurrent()) { // NOPMD - auto-close
                  return branchSpan(
                      "lexical",
                      () ->
                          textQueryOps.searchText(
                              queryString,
                              textCandLimit,
                              runtimeFilters,
                              boostRuntimeFilters,
                              syntax));
                }
              },
              executor);
      var denseF =
          EngineFutures.supplyAsync(
              () -> {
                try (Scope ctxScope = otelCtx.makeCurrent()) { // NOPMD - auto-close
                  return branchSpan(
                      "dense",
                      () ->
                          readPathOps.searchVector(
                              toFloatArray(tw.vector().vector()),
                              vectorCandLimit,
                              QueryFilterBuilder.buildFilterQueryOnly(runtimeFilters)));
                }
              },
              executor);
      var spladeF =
          EngineFutures.supplyAsync(
              () -> {
                try (Scope ctxScope = otelCtx.makeCurrent()) { // NOPMD - auto-close
                  return branchSpan(
                      "splade",
                      () -> searchSplade(tw.splade().weights(), textCandLimit, runtimeFilters));
                }
              },
              executor);
      var bm25Result = bm25F.join();
      var denseResult = denseF.join();
      var spladeResult = spladeF.join();
      double[] weights = {
        hs3 != null ? hs3.ccWeightSparse() : 0.35,
        hs3 != null ? hs3.ccWeightDense() : 0.35,
        hs3 != null ? hs3.ccWeightSplade() : 0.30
      };
      // Tempdoc 580 §13.3 — per-query adaptive CC-weight selection (default off). When enabled, the
      // configured weights become the fallback for queries with no length signal.
      if (hs3 != null && hs3.adaptiveWeightsEnabled()) {
        weights = AdaptiveWeightSelector.selectWeights(bm25Result, denseResult, spladeResult, weights);
      }
      boolean zeroExclude = hs3 != null && hs3.ccZeroExclude();
      Span fuseSpan =
          tracer()
              .spanBuilder("search/fuse")
              .setAttribute("search.fusion.algorithm", "cc")
              .setAttribute("search.fusion.branch_count", 3L)
              .startSpan();
      try {
        result =
            HybridFusionUtils.fuseWithCC3(
                bm25Result,
                denseResult,
                spladeResult,
                tw.retrievalLimit(),
                weights,
                debug,
                zeroExclude,
                "",
                true);
        // Tempdoc 549 Slice 3c (U2): three distinct typed legs → no bm25-vs-splade mislabel
        // (the old debug_scores parser routed the lexical "sparse" key to SPLADE under spladeExecuted).
        result =
            HitProvenanceProjector.attachRetrieval(
                result, bm25Result, spladeResult, denseResult, "cc");
        // Tempdoc 553 Phase A: OpenInference RERANKER projection of the fused output.
        fuseSpan.setAllAttributes(OpenInferenceSpanProjection.reranker("cc", 3, result));
      } finally {
        fuseSpan.end();
      }
    }
    return result;
  }

  // ============================================================
  // Chunk-merge helpers (moved from monolithic SearchOrchestrator)
  // ============================================================

  private record ChunkRunOutcome(
      LuceneRuntimeTypes.SearchResult result,
      boolean applied,
      SearchReasonCode reason,
      String branchFusionStrategy,
      boolean branchFusionContributed,
      // Tempdoc 517 follow-up (pass-F): chunk-merge timing fields threaded
      // through to ComponentTiming. Legacy emit-path set these in
      // SearchOrchestrator.java:886-895.
      long chunkMergeMs,
      long chunkBm25Ns,
      long chunkKnnNs,
      long chunkSpladeNs,
      boolean chunkRetry,
      long branchFusionNs) {}

  /** Tempdoc 774 Stage 1 — base-results gate lever; default true when config is absent. */
  private boolean chunkBranchRequiresBaseResults() {
    ResolvedConfig rc = resolvedConfigSupplier.get();
    ResolvedConfig.HybridSearch hs = rc != null ? rc.hybridSearch() : null;
    return hs == null || hs.chunkBranchRequiresBaseResults();
  }

  private ChunkRunOutcome maybeApplyChunkMerge(
      ChunkMergeDirective directive,
      LuceneRuntimeTypes.SearchResult baseResult,
      SearchInputs inputs,
      String chunkQueryText) {
    if (directive instanceof ChunkMergeDirective.Skip s) {
      var trimmed = trimSearchResult(baseResult, inputs.request().getLimit() > 0 ? inputs.request().getLimit() : 10);
      return new ChunkRunOutcome(trimmed, false, s.reason(), null, false, 0L, 0L, 0L, 0L, false, 0L);
    }
    var apply = (ChunkMergeDirective.EligibleApply) directive;
    boolean hasBaseResults = baseResult.hits() != null && !baseResult.hits().isEmpty();
    // Tempdoc 774 Stage 1 — base-results gate lever. Default true reproduces today's behavior
    // (chunk merge is a recall gate on the doc legs). When false, the chunk branch runs even when
    // the doc legs return empty ("fusion is a ranking step, not a recall gate", at the branch level).
    if (!hasBaseResults && chunkBranchRequiresBaseResults()) {
      return new ChunkRunOutcome(
          baseResult,
          false,
          SearchReasonCode.SKIPPED_EMPTY_BASE_RESULTS,
          null,
          false,
          0L,
          0L,
          0L,
          0L,
          false,
          0L);
    }

    Context parentCtx = Context.current();
    Span chunkSpan = tracer().spanBuilder("search/chunk_merge").setParent(parentCtx).startSpan();
    // Tempdoc 553 Phase A: structural (CHAIN) span — its retriever/reranker children carry documents.
    chunkSpan.setAllAttributes(OpenInferenceSpanProjection.chain());
    long mergeStartNs = System.nanoTime();
    try (Scope chunkScope = chunkSpan.makeCurrent()) { // NOPMD - auto-close
      var ci = apply.inputs();
      float[] chunkQueryVector =
          ci.chunkQueryVector() != null ? toFloatArray(ci.chunkQueryVector()) : null;
      var pipeline = inputs.request().hasPipeline() ? inputs.request().getPipeline() : null;
      if (pipeline == null) {
        return new ChunkRunOutcome(
            baseResult,
            false,
            SearchReasonCode.SKIPPED_UNKNOWN,
            null,
            false,
            0L,
            0L,
            0L,
            0L,
            false,
            0L);
      }
      var merged =
          mergeChunkResults(
              baseResult,
              chunkQueryText,
              chunkQueryVector,
              ci.chunkSpladeWeights(),
              pipeline,
              inputs.request().getDebug() || inputs.request().getIncludeDetail(),
              ci.limit(),
              inputs.runtimeFilters());
      long chunkMergeMs = (System.nanoTime() - mergeStartNs) / 1_000_000;
      return new ChunkRunOutcome(
          merged.result(),
          true,
          SearchReasonCode.APPLIED,
          merged.branchFusionStrategy(),
          merged.branchContributed(),
          chunkMergeMs,
          merged.bm25Ns(),
          merged.knnNs(),
          merged.spladeNs(),
          merged.retryTriggered(),
          merged.branchFusionNs());
    } finally {
      chunkSpan.end();
    }
  }

  private record ChunkMergeResult(
      LuceneRuntimeTypes.SearchResult result,
      long bm25Ns,
      long knnNs,
      long spladeNs,
      long branchFusionNs,
      boolean retryTriggered,
      String branchFusionStrategy,
      boolean branchContributed) {}

  private record ChunkBranchResult(
      LuceneRuntimeTypes.SearchResult parentResult,
      boolean anyLegSaturated,
      long bm25Ns,
      long knnNs,
      long spladeNs,
      // Tempdoc 774 Stage 1 — chunk-side recall-complete protected set, computed from the RAW
      // per-leg chunk results BEFORE the collapse cap (so a leg-top-N parent the collapse would drop
      // is still rescuable). Empty unless chunk_leg_recall_complete is enabled.
      List<LuceneRuntimeTypes.SearchHit> legTopNParents) {}

  /**
   * Whether the chunk-SPLADE retrieval leg may run (tempdoc 931 §E item 8, tempdoc 712).
   *
   * <p>{@code rag.chunk_splade.enabled} is one switch over one stage, not a write-side-only switch:
   * every backfill lane already refuses to encode chunk SPLADE when it is off, so a query-side leg
   * that ignored it scored against whatever partial chunk-{@code splade} population happened to be
   * on disk from an earlier flag-on window. A null {@link ResolvedConfig} (or a null {@code rag()})
   * reads as OFF — the flag's own default — rather than as "unconstrained".
   */
  static boolean chunkSpladeLegEnabled(
      io.justsearch.ipc.PipelineConfig pipeline,
      Map<String, Float> spladeWeights,
      ResolvedConfig resolvedConfig) {
    return pipeline.getSpladeEnabled()
        && spladeWeights != null
        && !spladeWeights.isEmpty()
        && resolvedConfig != null
        && resolvedConfig.rag() != null
        && resolvedConfig.rag().chunkSpladeEnabled();
  }

  private ChunkMergeResult mergeChunkResults(
      LuceneRuntimeTypes.SearchResult wholeDocResult,
      String queryString,
      float[] queryVector,
      Map<String, Float> spladeWeights,
      io.justsearch.ipc.PipelineConfig pipeline,
      boolean debug,
      int limit,
      LuceneRuntimeTypes.RuntimeSearchFilters filters) {

    org.apache.lucene.search.Query chunkFilter = QueryFilterBuilder.buildChunkFilterQuery(filters);

    // Tempdoc 931 §E item 8 — the resolved config is read BEFORE the leg decision, not after the
    // early return: rag.chunk_splade.enabled gates the chunk-SPLADE leg, and with the fetch below
    // the return a splade-only chunk request short-circuited before the flag was ever consulted.
    ResolvedConfig resolvedConfig = resolvedConfigSupplier.get();
    boolean chunkSplade = chunkSpladeLegEnabled(pipeline, spladeWeights, resolvedConfig);
    boolean chunkBm25 = pipeline.getSparseEnabled() && queryString != null && !queryString.isBlank();
    boolean chunkKnn = pipeline.getDenseEnabled() && queryVector != null && queryVector.length > 0;

    if (!chunkSplade && !chunkBm25 && !chunkKnn) {
      return new ChunkMergeResult(
          trimSearchResult(wholeDocResult, limit), 0, 0, 0, 0, false, "", false);
    }

    ResolvedConfig.HybridSearch hybridConfig =
        resolvedConfig != null ? resolvedConfig.hybridSearch() : null;
    // Tempdoc 774 Stage 1 — the chunk branch reads its OWN CC leg weights + zero-exclude, decoupled
    // from the doc-level cc_weight_* keys (§F.1-2 silent coupling). Defaults equal the resolved
    // doc-level values byte-for-byte (ResolvedConfigBuilder fallback), so this is a no-op until set.
    double[] weights = {
      chunkBm25 ? hybridWeight(hybridConfig != null ? hybridConfig.chunkCcWeightSparse() : 0.35) : 0.0,
      chunkKnn ? hybridWeight(hybridConfig != null ? hybridConfig.chunkCcWeightDense() : 0.35) : 0.0,
      chunkSplade ? hybridWeight(hybridConfig != null ? hybridConfig.chunkCcWeightSplade() : 0.30) : 0.0
    };
    boolean zeroExclude = hybridConfig != null && hybridConfig.chunkCcZeroExclude();

    // Tempdoc 774 Stage 1 — collapse-cap lever (default 2 reproduces the old hardcoded 2×limit).
    int collapseLimit =
        Math.max(limit, limit * (hybridConfig != null ? hybridConfig.chunkCollapseLimitMultiplier() : 2));

    // Tempdoc 774 Stage 1 — chunk-side recall-complete per-leg top-N to protect (0 = disabled, so no
    // per-leg protected-set work is done in executeChunkBranchFusion when the lever is off).
    int chunkRecallTopN =
        hybridConfig != null && hybridConfig.chunkLegRecallCompleteEnabled()
            ? hybridConfig.chunkLegRecallCompleteTopN()
            : 0;

    int candidateBudget = Math.max(limit, limit * CHUNK_INITIAL_CANDIDATE_MULTIPLIER);
    // Lane F PR 0b — index.vector.exhaustive_search. Read from the same resolved config the rest of
    // this method reads, so the Head-side switch reaches the Worker through the ordinal-450 config
    // snapshot rather than a raw sysprop read inside this JVM.
    boolean denseExhaustive =
        resolvedConfig != null
            && resolvedConfig.index() != null
            && resolvedConfig.index().vectorExhaustiveSearch();
    boolean retryTriggered = false;
    ChunkBranchResult chunkBranchResult =
        executeChunkBranchFusion(
            queryString,
            queryVector,
            spladeWeights,
            chunkFilter,
            chunkBm25,
            chunkKnn,
            chunkSplade,
            candidateBudget,
            collapseLimit,
            weights,
            debug,
            zeroExclude,
            chunkRecallTopN,
            denseExhaustive);
    if (chunkBranchResult.parentResult().hits() == null
        || chunkBranchResult.parentResult().hits().isEmpty()) {
      return new ChunkMergeResult(
          trimSearchResult(wholeDocResult, limit),
          chunkBranchResult.bm25Ns(),
          chunkBranchResult.knnNs(),
          chunkBranchResult.spladeNs(),
          0,
          false,
          "",
          false);
    }

    if (chunkBranchResult.parentResult().hits().size() < limit
        && chunkBranchResult.anyLegSaturated()) {
      int retryBudget = Math.max(candidateBudget * CHUNK_RETRY_MULTIPLIER, limit);
      retryTriggered = true;
      long initialBm25Ns = chunkBranchResult.bm25Ns();
      long initialKnnNs = chunkBranchResult.knnNs();
      long initialSpladeNs = chunkBranchResult.spladeNs();
      chunkBranchResult =
          executeChunkBranchFusion(
              queryString,
              queryVector,
              spladeWeights,
              chunkFilter,
              chunkBm25,
              chunkKnn,
              chunkSplade,
              retryBudget,
              collapseLimit,
              weights,
              debug,
              zeroExclude,
              chunkRecallTopN,
              denseExhaustive);
      chunkBranchResult =
          new ChunkBranchResult(
              chunkBranchResult.parentResult(),
              chunkBranchResult.anyLegSaturated(),
              initialBm25Ns + chunkBranchResult.bm25Ns(),
              initialKnnNs + chunkBranchResult.knnNs(),
              initialSpladeNs + chunkBranchResult.spladeNs(),
              chunkBranchResult.legTopNParents());
      if (chunkBranchResult.parentResult().hits() == null
          || chunkBranchResult.parentResult().hits().isEmpty()) {
        return new ChunkMergeResult(
            trimSearchResult(wholeDocResult, limit),
            chunkBranchResult.bm25Ns(),
            chunkBranchResult.knnNs(),
            chunkBranchResult.spladeNs(),
            0,
            true,
            "",
            false);
      }
    }

    String branchFusionStrategy =
        hybridConfig != null ? hybridConfig.branchFusionStrategy() : BRANCH_FUSION_STRATEGY_CC;
    long branchFusionStart = System.nanoTime();
    LuceneRuntimeTypes.SearchResult merged;
    Span branchFuseSpan =
        tracer()
            .spanBuilder("search/fuse")
            .setAttribute(
                "search.fusion.algorithm",
                BRANCH_FUSION_STRATEGY_RRF.equals(branchFusionStrategy) ? "rrf" : "cc")
            .setAttribute("search.fusion.branch_count", 2L)
            .startSpan();
    try {
      if (BRANCH_FUSION_STRATEGY_RRF.equals(branchFusionStrategy)) {
        merged =
            HybridFusionUtils.fuseWithRRFNamed(
                wholeDocResult,
                chunkBranchResult.parentResult(),
                limit,
                debug,
                Integer.MAX_VALUE,
                1.0,
                resolvedConfig,
                "whole_branch",
                "chunk_branch",
                "branch_merge_",
                false);
      } else {
        double[] branchWeights = {
          hybridWeight(hybridConfig != null ? hybridConfig.branchCcWeightWhole() : 0.50),
          hybridWeight(hybridConfig != null ? hybridConfig.branchCcWeightChunk() : 0.50)
        };
        boolean branchZeroExclude = hybridConfig == null || hybridConfig.branchCcZeroExclude();
        double chunkMinMultiplier =
            hybridConfig != null ? hybridConfig.branchChunkMinWeightMultiplier() : 0.25;
        // Tempdoc 854 W1 (F-036 §K wrong-gate fix) — the Stage-3B branch ramp's own bounds, no
        // longer shared with the Stage-3A SPLADE parent-length-fade bounds.
        long branchRampFullWeightMaxTokens =
            hybridConfig != null ? hybridConfig.branchRampFullWeightMaxTokens() : 1024L;
        long branchRampZeroWeightMinTokens =
            hybridConfig != null ? hybridConfig.branchRampZeroWeightMinTokens() : 4096L;
        merged =
            HybridFusionUtils.fuseWithCCNamed(
                wholeDocResult,
                chunkBranchResult.parentResult(),
                limit,
                branchWeights,
                debug,
                branchZeroExclude,
                "whole_branch",
                "chunk_branch",
                "branch_merge_",
                "whole",
                "chunk",
                true,
                chunkMinMultiplier,
                branchRampFullWeightMaxTokens,
                branchRampZeroWeightMinTokens);
      }
      // Tempdoc 553 Phase A: OpenInference RERANKER projection of the whole×chunk branch merge.
      branchFuseSpan.setAllAttributes(
          OpenInferenceSpanProjection.reranker(
              BRANCH_FUSION_STRATEGY_RRF.equals(branchFusionStrategy) ? "rrf" : "cc", 2, merged));
    } finally {
      branchFuseSpan.end();
    }
    long branchFusionNs = System.nanoTime() - branchFusionStart;

    // Tempdoc 636 Design v3 — recall-complete rerank pool (default off), branch-fusion stage. A
    // leg-top-N candidate that the whole-doc fusion stage spliced in carries a low synthetic fused
    // score, so branch fusion (whole ⊕ chunk) would re-bury it below the returned window before the
    // Head cross-encoder ever sees it. Re-assert the guarantee on the branch-fused list, identifying
    // the protected docs by the dense/bm25 leg rank carried in their whole-doc provenance (presence,
    // not score — keyword-neutral). Spliced before attachBranchFusion so provenance is re-mapped.
    if (hybridConfig != null && hybridConfig.legRecallCompleteEnabled()) {
      int topN = hybridConfig.legRecallCompleteTopN();
      List<LuceneRuntimeTypes.SearchHit> protectedHits = new ArrayList<>();
      for (LuceneRuntimeTypes.SearchHit h : wholeDocResult.hits()) {
        LuceneRuntimeTypes.HitProvenanceSignals prov = h.provenance();
        if (prov == null) {
          continue;
        }
        boolean denseTopN =
            prov.dense() != null && prov.dense().rank() > 0 && prov.dense().rank() <= topN;
        boolean bm25TopN =
            prov.bm25() != null && prov.bm25().rank() > 0 && prov.bm25().rank() <= topN;
        if (denseTopN || bm25TopN) {
          protectedHits.add(h);
        }
      }
      List<LuceneRuntimeTypes.SearchHit> spliced =
          HybridFusionUtils.spliceRecallComplete(merged.hits(), protectedHits, limit);
      if (spliced != merged.hits()) {
        merged =
            new LuceneRuntimeTypes.SearchResult(spliced, merged.totalHits(), merged.tookMs());
      }
    }

    // Tempdoc 774 Stage 1 — chunk-side recall-complete (default off): the passage-granularity twin
    // of the doc-side guarantee above. The protected set is the RAW per-leg top-N parents captured
    // in executeChunkBranchFusion BEFORE the collapse cap — so a parent a chunk leg ranked top-N but
    // whose fused rank fell outside the collapse cap is still rescued (the collapse is itself a
    // "stage that may not drop a passage-leg top-N candidate", §I.2). Spliced before attachBranchFusion
    // so provenance is re-mapped — same ordering as the doc-side block.
    if (hybridConfig != null && hybridConfig.chunkLegRecallCompleteEnabled()) {
      List<LuceneRuntimeTypes.SearchHit> spliced =
          HybridFusionUtils.spliceRecallComplete(
              merged.hits(), chunkBranchResult.legTopNParents(), limit);
      if (spliced != merged.hits()) {
        merged =
            new LuceneRuntimeTypes.SearchResult(spliced, merged.totalHits(), merged.tookMs());
      }
    }

    // Tempdoc 549 Slice 3c (U2): branch fusion makes fresh hits (fuser drops typed provenance),
    // so re-map by docId — whole-doc branch carries retriever/fusion legs, chunk branch the
    // chunk-merge leg, branch scores + fused score the branch-fusion leg.
    merged =
        HitProvenanceProjector.attachBranchFusion(
            merged, wholeDocResult, chunkBranchResult.parentResult(), branchFusionStrategy);

    return new ChunkMergeResult(
        merged,
        chunkBranchResult.bm25Ns(),
        chunkBranchResult.knnNs(),
        chunkBranchResult.spladeNs(),
        branchFusionNs,
        retryTriggered,
        branchFusionStrategy,
        true);
  }

  private ChunkBranchResult executeChunkBranchFusion(
      String queryString,
      float[] queryVector,
      Map<String, Float> spladeWeights,
      org.apache.lucene.search.Query chunkFilter,
      boolean chunkBm25,
      boolean chunkKnn,
      boolean chunkSplade,
      int candidateBudget,
      int collapseLimit,
      double[] weights,
      boolean debug,
      boolean zeroExclude,
      int recallCompleteTopN,
      boolean denseExhaustive) {
    LuceneRuntimeTypes.SearchResult bm25Result = emptySearchResult();
    LuceneRuntimeTypes.SearchResult denseResult = emptySearchResult();
    LuceneRuntimeTypes.SearchResult spladeResult = emptySearchResult();
    boolean anyLegSaturated = false;
    long bm25Ns = 0, knnNs = 0, spladeNs = 0;

    if (chunkBm25) {
      long t0 = System.nanoTime();
      bm25Result = chunkSearchOps.searchChunksText(queryString, candidateBudget, chunkFilter);
      bm25Ns = System.nanoTime() - t0;
      anyLegSaturated |= isCandidateBudgetSaturated(bm25Result, candidateBudget);
    }
    if (chunkKnn) {
      long t0 = System.nanoTime();
      denseResult = chunkSearchOps.searchChunkVector(queryVector, null, candidateBudget, chunkFilter);
      knnNs = System.nanoTime() - t0;
      // Lane F PR 0b: under index.vector.exhaustive_search the dense leg's totalHits is the whole
      // vector-bearing corpus by construction, so it is excluded from the saturation test and the
      // retry branch fires on exactly the queries it did with the switch off.
      anyLegSaturated |=
          isCandidateBudgetSaturated(denseResult, candidateBudget, denseExhaustive);
    }
    if (chunkSplade) {
      long t0 = System.nanoTime();
      spladeResult = chunkSearchOps.searchChunksSplade(spladeWeights, candidateBudget, chunkFilter);
      spladeNs = System.nanoTime() - t0;
      anyLegSaturated |= isCandidateBudgetSaturated(spladeResult, candidateBudget);
    }

    LuceneRuntimeTypes.SearchResult fusedChunkResult;
    Span chunkFuseSpan =
        tracer()
            .spanBuilder("search/fuse")
            .setAttribute("search.fusion.algorithm", "cc")
            .setAttribute("search.fusion.branch_count", 3L)
            .setAttribute("search.retrieval.branch", "chunk")
            .startSpan();
    try {
      fusedChunkResult =
          HybridFusionUtils.fuseWithCC3(
              bm25Result,
              denseResult,
              spladeResult,
              candidateBudget,
              weights,
              debug,
              zeroExclude,
              "chunk_",
              true);
      // Tempdoc 553 Phase A: OpenInference RERANKER projection of the chunk-side 3-way fusion.
      chunkFuseSpan.setAllAttributes(OpenInferenceSpanProjection.reranker("cc", 3, fusedChunkResult));
    } finally {
      chunkFuseSpan.end();
    }
    // Tempdoc 549 Slice 3c (U2): chunk-merge provenance from the typed chunk legs, before collapse
    // (the collapse helpers preserve hit.provenance() so the winning chunk's signal survives).
    fusedChunkResult =
        HitProvenanceProjector.attachChunkMerge(
            fusedChunkResult, bm25Result, denseResult, spladeResult);
    // Tempdoc 774 Stage 1 — capture the recall-complete protected set from the RAW per-leg results
    // BEFORE collapse: a parent a leg ranked top-N but whose fused rank falls beyond the collapse cap
    // would otherwise be unrescuable (it never reaches parentNormalizedChunkResult).
    List<LuceneRuntimeTypes.SearchHit> legTopNParents =
        collectRawLegTopNParents(bm25Result, denseResult, spladeResult, recallCompleteTopN);
    LuceneRuntimeTypes.SearchResult parentNormalizedChunkResult =
        collapseChunkHitsToParents(fusedChunkResult, collapseLimit);
    return new ChunkBranchResult(
        parentNormalizedChunkResult, anyLegSaturated, bm25Ns, knnNs, spladeNs, legTopNParents);
  }

  /**
   * Tempdoc 774 Stage 1 — the chunk-side recall-complete protected set: each raw chunk leg's top-N
   * chunks (native leg order), mapped to their parent and normalized to parent form, deduped by
   * parent (first-seen across legs in bm25→dense→splade order). Computed on the pre-collapse leg
   * results so a parent a leg ranked top-N is protected even when the collapse cap drops it.
   */
  static List<LuceneRuntimeTypes.SearchHit> collectRawLegTopNParents(
      LuceneRuntimeTypes.SearchResult bm25Result,
      LuceneRuntimeTypes.SearchResult denseResult,
      LuceneRuntimeTypes.SearchResult spladeResult,
      int topN) {
    if (topN <= 0) {
      return List.of();
    }
    Map<String, LuceneRuntimeTypes.SearchHit> byParent = new LinkedHashMap<>();
    addLegTopNParents(byParent, bm25Result, topN);
    addLegTopNParents(byParent, denseResult, topN);
    addLegTopNParents(byParent, spladeResult, topN);
    return new ArrayList<>(byParent.values());
  }

  private static void addLegTopNParents(
      Map<String, LuceneRuntimeTypes.SearchHit> byParent,
      LuceneRuntimeTypes.SearchResult leg,
      int topN) {
    if (leg == null || leg.hits() == null) {
      return;
    }
    List<LuceneRuntimeTypes.SearchHit> hits = leg.hits();
    int n = Math.min(topN, hits.size());
    for (int i = 0; i < n; i++) {
      LuceneRuntimeTypes.SearchHit hit = hits.get(i);
      String parentId = hit.fields().get(SchemaFields.PARENT_DOC_ID);
      if (parentId == null || parentId.isEmpty()) {
        parentId = hit.docId();
      }
      byParent.putIfAbsent(parentId, normalizeChunkHitToParent(hit, parentId));
    }
  }

  private static boolean isCandidateBudgetSaturated(
      LuceneRuntimeTypes.SearchResult result, int candidateBudget) {
    return isCandidateBudgetSaturated(result, candidateBudget, false);
  }

  /**
   * Whether a retrieval leg hit the candidate budget, so the chunk branch should retry wider.
   *
   * <p>{@code ignoreTotalHits} exists for one caller: the dense leg under {@code
   * index.vector.exhaustive_search} (lane F PR 0b). In that mode the kNN query runs with {@code k
   * >= reader.maxDoc()} so it can take Lucene's exact branch, which makes its {@code totalHits} the
   * whole vector-bearing corpus rather than a measure of how hard the leg pushed against the
   * budget. Counting that as saturation would fire the chunk retry on essentially every query and
   * change WHICH BRANCHES the pipeline takes — the switch is only allowed to change the
   * approximation. The returned-hit-count term is untouched: it still measures the same thing.
   */
  static boolean isCandidateBudgetSaturated(
      LuceneRuntimeTypes.SearchResult result, int candidateBudget, boolean ignoreTotalHits) {
    if (result == null || candidateBudget <= 0) {
      return false;
    }
    int hits = result.hits() != null ? result.hits().size() : 0;
    if (hits >= candidateBudget) {
      return true;
    }
    return !ignoreTotalHits && result.totalHits() > candidateBudget;
  }

  private static double hybridWeight(double configuredWeight) {
    return Math.max(0.0, configuredWeight);
  }

  private static LuceneRuntimeTypes.SearchResult emptySearchResult() {
    return new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0);
  }

  static LuceneRuntimeTypes.SearchResult collapseChunkHitsToParents(
      LuceneRuntimeTypes.SearchResult chunkResult, int limit) {
    Map<String, LuceneRuntimeTypes.SearchHit> bestPerParent = new LinkedHashMap<>();
    for (LuceneRuntimeTypes.SearchHit hit : chunkResult.hits()) {
      String parentId = hit.fields().get(SchemaFields.PARENT_DOC_ID);
      if (parentId == null || parentId.isEmpty()) {
        parentId = hit.docId();
      }
      LuceneRuntimeTypes.SearchHit normalized = normalizeChunkHitToParent(hit, parentId);
      LuceneRuntimeTypes.SearchHit existing = bestPerParent.get(parentId);
      if (existing == null) {
        bestPerParent.put(parentId, normalized);
      } else {
        bestPerParent.put(parentId, mergeCollapsedChunkParentHit(existing, normalized));
      }
      if (bestPerParent.size() >= limit) {
        break;
      }
    }
    List<LuceneRuntimeTypes.SearchHit> collapsed = new ArrayList<>(bestPerParent.values());
    return new LuceneRuntimeTypes.SearchResult(
        collapsed, collapsed.size(), chunkResult.tookMs(), null);
  }

  private static LuceneRuntimeTypes.SearchHit normalizeChunkHitToParent(
      LuceneRuntimeTypes.SearchHit hit, String parentId) {
    if (parentId == null || parentId.isEmpty()) {
      return hit;
    }
    Map<String, String> normalizedFields = new HashMap<>(hit.fields());
    normalizedFields.putIfAbsent(SchemaFields.PARENT_DOC_ID, parentId);
    if (hit.docId() != null && !hit.docId().equals(parentId)) {
      normalizedFields.put(CHUNK_SOURCE_DOC_ID_FIELD, hit.docId());
    }
    return new LuceneRuntimeTypes.SearchHit(
        parentId, hit.score(), Map.copyOf(normalizedFields), hit.debugScores(), hit.provenance());
  }

  private static LuceneRuntimeTypes.SearchHit mergeCollapsedChunkParentHit(
      LuceneRuntimeTypes.SearchHit winner, LuceneRuntimeTypes.SearchHit sibling) {
    Map<String, String> mergedFields = new HashMap<>(winner.fields());
    for (var entry : sibling.fields().entrySet()) {
      mergedFields.putIfAbsent(entry.getKey(), entry.getValue());
    }
    Map<String, Float> mergedDebugScores = new HashMap<>(winner.debugScores());
    for (var entry : sibling.debugScores().entrySet()) {
      mergeCollapsedChunkParentDebugScore(mergedDebugScores, entry.getKey(), entry.getValue());
    }
    // Preserve the winning chunk's typed provenance (the chunk-merge leg follows
    // the best chunk per parent); fall back to the sibling's if the winner has none.
    LuceneRuntimeTypes.HitProvenanceSignals mergedProvenance =
        winner.provenance() != null ? winner.provenance() : sibling.provenance();
    return new LuceneRuntimeTypes.SearchHit(
        winner.docId(),
        winner.score(),
        Map.copyOf(mergedFields),
        Map.copyOf(mergedDebugScores),
        mergedProvenance);
  }

  private static void mergeCollapsedChunkParentDebugScore(
      Map<String, Float> mergedDebugScores, String key, Float siblingValue) {
    if (siblingValue == null) {
      return;
    }
    Float winnerValue = mergedDebugScores.get(key);
    if (CHUNK_COLLAPSE_MAX_EVIDENCE_SCORE_KEYS.contains(key)) {
      mergedDebugScores.put(key, chooseMaxEvidenceValue(winnerValue, siblingValue));
      return;
    }
    if (CHUNK_COLLAPSE_MIN_POSITIVE_RANK_KEYS.contains(key)) {
      mergedDebugScores.put(key, chooseBestPositiveRank(winnerValue, siblingValue));
      return;
    }
    mergedDebugScores.putIfAbsent(key, siblingValue);
  }

  private static float chooseMaxEvidenceValue(Float winnerValue, float siblingValue) {
    if (winnerValue == null) {
      return siblingValue;
    }
    return Math.max(winnerValue, siblingValue);
  }

  private static float chooseBestPositiveRank(Float winnerValue, float siblingValue) {
    if (winnerValue == null) {
      return siblingValue;
    }
    boolean winnerPositive = winnerValue > 0f;
    boolean siblingPositive = siblingValue > 0f;
    if (winnerPositive && siblingPositive) {
      return Math.min(winnerValue, siblingValue);
    }
    if (winnerPositive) {
      return winnerValue;
    }
    if (siblingPositive) {
      return siblingValue;
    }
    return winnerValue;
  }

  static LuceneRuntimeTypes.SearchResult trimSearchResult(
      LuceneRuntimeTypes.SearchResult result, int limit) {
    if (result == null || result.hits() == null || result.hits().size() <= limit) {
      return result;
    }
    List<LuceneRuntimeTypes.SearchHit> trimmed =
        new ArrayList<>(result.hits().subList(0, limit));
    return new LuceneRuntimeTypes.SearchResult(
        trimmed, result.totalHits(), result.tookMs(), result.nextCursor());
  }

  // ============================================================
  // Leg helpers
  // ============================================================

  private LuceneRuntimeTypes.SearchResult searchSplade(
      Map<String, Float> queryWeights,
      int limit,
      LuceneRuntimeTypes.RuntimeSearchFilters filters) {
    if (queryWeights == null || queryWeights.isEmpty()) {
      return new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0);
    }
    org.apache.lucene.search.Query query = textQueryOps.buildSpladeQuery(queryWeights, filters);
    if (query == null) {
      return new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0);
    }
    return readPathOps.search(
        query, limit, null, LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, null);
  }

  private LuceneRuntimeTypes.SearchResult fuseLegs(
      List<LuceneRuntimeTypes.SearchResult> legs, int limit, boolean debug) {
    if (legs.isEmpty()) {
      return new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0);
    }
    if (legs.size() == 1) {
      return legs.get(0);
    }
    ResolvedConfig rc = resolvedConfigSupplier.get();
    Span nestedFuseSpan =
        tracer()
            .spanBuilder("search/fuse")
            .setAttribute("search.fusion.algorithm", "rrf")
            .setAttribute("search.fusion.branch_count", (long) legs.size())
            .startSpan();
    try {
      LuceneRuntimeTypes.SearchResult fused = legs.get(0);
      for (int i = 1; i < legs.size(); i++) {
        fused =
            HybridFusionUtils.fuseWithRRF(
                fused, legs.get(i), limit, debug, Integer.MAX_VALUE, 1.0, rc);
      }
      // Tempdoc 553 Phase A: OpenInference RERANKER projection of the nested-leg RRF fusion.
      nestedFuseSpan.setAllAttributes(
          OpenInferenceSpanProjection.reranker("rrf", legs.size(), fused));
      return fused;
    } finally {
      nestedFuseSpan.end();
    }
  }

  private <T> T branchSpan(String branch, Supplier<T> work) {
    Span span =
        tracer()
            .spanBuilder("search/branch")
            .setAttribute("search.retrieval.branch", branch)
            .startSpan();
    try (Scope ignored = span.makeCurrent()) { // NOPMD - auto-close
      T out = work.get();
      // Tempdoc 553 Phase A: a per-leg OpenInference RETRIEVER span carrying the documents this
      // leg produced, projected from its result (the only T this helper is ever called with).
      if (out instanceof LuceneRuntimeTypes.SearchResult legResult) {
        span.setAllAttributes(OpenInferenceSpanProjection.retriever(legResult));
      }
      return out;
    } finally {
      span.end();
    }
  }

  private static float[] toFloatArray(List<Float> list) {
    if (list == null) {
      return new float[0];
    }
    float[] arr = new float[list.size()];
    for (int i = 0; i < arr.length; i++) {
      arr[i] = list.get(i);
    }
    return arr;
  }
}
