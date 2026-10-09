/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.plan;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.QueryFilterBuilder;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.services.SearchReasonCode;
import io.justsearch.indexerworker.services.input.SearchInputs;
import io.justsearch.indexerworker.services.input.SpladeEncoding;
import io.justsearch.indexerworker.services.input.VectorEncoding;
import io.justsearch.indexerworker.util.ProtoConverters;
import io.justsearch.ipc.FacetFieldSpec;
import io.justsearch.ipc.FacetSpec;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.logstash.logback.marker.Markers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure decision construction over {@link SearchInputs} (tempdoc 517).
 *
 * <p>No IO. No encoder access. Reads only the captured value and the resolved-config
 * supplier (which is pure-read-once). Produces a complete {@link SearchDecision} that
 * the executor pattern-matches.
 */
public final class SearchPlanner {
  private static final Logger log = LoggerFactory.getLogger(SearchPlanner.class);
  static final int DEFAULT_LIMIT = 10;
  static final int MAX_LIMIT = 100;
  static final int MIN_DF_SKIP_FIELD_DOCS = 100;
  static final int DEFAULT_VECTOR_SKIP_MIN_CHARS = 4;
  static final double DEFAULT_VECTOR_SKIP_MIN_DF_FRACTION = 0.25;

  private final Supplier<ResolvedConfig> resolvedConfigSupplier;

  public SearchPlanner(Supplier<ResolvedConfig> resolvedConfigSupplier) {
    this.resolvedConfigSupplier = resolvedConfigSupplier;
  }

  /** Constructs a complete decision from the captured inputs. */
  public SearchDecision plan(SearchInputs inputs) {
    Objects.requireNonNull(inputs, "inputs");
    SearchRequest request = inputs.request();
    String queryString = request.getQuery();

    PipelineConfig pipeline;
    if (request.hasPipeline()) {
      pipeline = request.getPipeline();
    } else {
      log.warn(
          Markers.append("reason_code", "deprecated_mode_fallback")
              .and(Markers.append("search_mode", request.getMode().name())),
          "Search request has no pipeline config — using deprecated mode fallback");
      pipeline = modeToDefaultPipeline(request.getMode());
    }

    int limit = clampLimit(request.getLimit());
    boolean wantSparse = pipeline.getSparseEnabled();
    boolean wantDense = pipeline.getDenseEnabled();
    boolean wantSplade = pipeline.getSpladeEnabled();

    // Hybrid blank-query is invalid (preserved from legacy behaviour at line 399).
    if (wantSparse && wantDense && queryString.isBlank()) {
      throw new IllegalArgumentException("Hybrid mode requires a non-empty query");
    }

    // Early exit for empty query (unless dense-only with explicit vector).
    if (queryString.isBlank() && !wantDense) {
      return new SearchDecision.EmptyQueryDecision(limit, "TEXT");
    }

    // Vector-only blocked: hard fail when no vector available.
    boolean canDense =
        wantDense && inputs.encoding().vector() instanceof VectorEncoding.Success;
    boolean canSplade =
        wantSplade && inputs.encoding().splade() instanceof SpladeEncoding.Success;
    // A failed SPLADE leg already carries the fallback reason and (for a budget miss) cause.
    // If no semantic leg can answer, retain that failure on the multi-leg BM25 path even when
    // the SPLADE preset did not select sparse retrieval. An empty-query decision would discard
    // both the keyword results and the captured skip. Successful SPLADE encodings keep their plan.
    boolean spladeKeywordFallback =
        wantSplade && !canSplade && !canDense
            && inputs.encoding().splade() instanceof SpladeEncoding.Failed;
    boolean canSparse = (wantSparse || spladeKeywordFallback) && !queryString.isBlank();
    boolean sparseOnlyRequest = wantSparse && !wantDense && !wantSplade;

    if (wantDense && !wantSparse && !wantSplade) {
      // Vector-only request.
      if (!inputs.compatBoundary().allowQueryEmbeddings()) {
        SearchReasonCode reason = inputs.compatBoundary().mapped();
        if (reason == SearchReasonCode.EMBEDDING_COMPATIBILITY_UNKNOWN) {
          reason = SearchReasonCode.UNKNOWN;
        }
        return new SearchDecision.BlockedDecision(
            new VectorEncoding.Failed(reason), "VECTOR");
      }
      if (!canDense) {
        SearchReasonCode reason = SearchReasonCode.UNKNOWN;
        if (inputs.encoding().vector() instanceof VectorEncoding.Failed f) {
          reason = f.reason();
        }
        throw new IllegalArgumentException(
            "Vector mode requires a non-empty vector field or an available embedding service"
                + " (reason: "
                + reason.name()
                + ")");
      }
    }

    int retrievalLimit = computeRetrievalLimit(limit, inputs);

    // SparseShortcut path (sparse-only request).
    if (sparseOnlyRequest) {
      LuceneRuntimeTypes.QuerySyntax runtimeSyntax = inputs.runtimeSyntax();
      boolean correctionRetryEnabled =
          runtimeSyntax == LuceneRuntimeTypes.QuerySyntax.SIMPLE
              && resolvedConfigSupplier.get().search().corrections().enabled();
      // Facets are computed by SearchResponseBuilder; the FacetCompute discriminator
      // here only signals which variant + the request's facet fields. The Lucene query
      // for FromRetrievalQuery is built by the executor + held in SearchOutcome.queryForSpans.
      Optional<FacetCompute.FromRetrievalQuery> facets = Optional.empty();
      var facetsMsg = request.hasFacets() ? request.getFacets() : null;
      boolean firstPage = request.getCursor().isBlank();
      if (firstPage
          && facetsMsg != null
          && facetsMsg.getInclude()
          && facetsMsg.getFieldsCount() > 0) {
        Map<String, Integer> fields = buildFacetFields(facetsMsg);
        if (!fields.isEmpty()) {
          // Placeholder Query — the executor sets the actual luceneQuery before facet computation.
          // SearchResponseBuilder reads SearchOutcome.queryForSpans for the actual query.
          // We construct a non-null placeholder here purely to mark "facets requested".
          facets =
              Optional.of(
                  new FacetCompute.FromRetrievalQuery(
                      new org.apache.lucene.search.MatchAllDocsQuery(),
                      fields,
                      facetsMsg.getMaxDocsScanned()));
        }
      }
      ChunkMergeDirective chunkMerge =
          planChunkMerge(inputs, limit, queryString, false);
      return new SearchDecision.SparseShortcut(
          runtimeSyntax, retrievalLimit, correctionRetryEnabled, facets, chunkMerge);
    }

    // MultiLegDecision path.
    Optional<SearchReasonCode> denseSkipReason =
        planDenseSkip(inputs, queryString, canSparse, canDense, canSplade);
    boolean runDense = canDense && denseSkipReason.isEmpty();
    LegSet legs = selectLegSet(inputs, canSparse, runDense, canSplade, retrievalLimit);
    if (legs == null) {
      // No legs runnable → empty result (Bm25Only with empty query).
      return new SearchDecision.EmptyQueryDecision(
          limit, deriveActualMode(canSparse, canDense, canSplade));
    }

    Optional<VectorEncoding.Failed> hybridFallback = Optional.empty();
    if (wantDense && !canDense && (canSparse || canSplade)) {
      if (inputs.encoding().vector() instanceof VectorEncoding.Failed f) {
        hybridFallback = Optional.of(f);
      }
    }
    Optional<SpladeEncoding.Failed> spladeSkip = Optional.empty();
    if (wantSplade && !canSplade && inputs.encoding().splade() instanceof SpladeEncoding.Failed f) {
      spladeSkip = Optional.of(f);
    }

    Optional<FacetCompute.FromFreshBm25> facets = Optional.empty();
    var facetsMsg = request.hasFacets() ? request.getFacets() : null;
    boolean firstPage = request.getCursor().isBlank();
    if (firstPage
        && facetsMsg != null
        && facetsMsg.getInclude()
        && facetsMsg.getFieldsCount() > 0
        && !queryString.isBlank()) {
      Map<String, Integer> fields = buildFacetFields(facetsMsg);
      if (!fields.isEmpty()) {
        facets =
            Optional.of(
                new FacetCompute.FromFreshBm25(
                    queryString,
                    inputs.runtimeFilters(),
                    fields,
                    facetsMsg.getMaxDocsScanned()));
      }
    }

    ChunkMergeDirective chunkMerge =
        planChunkMerge(inputs, limit, queryString, runDense);

    return new SearchDecision.MultiLegDecision(
        inputs.runtimeSyntax(),
        legs,
        hybridFallback,
        denseSkipReason,
        spladeSkip,
        facets,
        chunkMerge);
  }

  private Optional<SearchReasonCode> planDenseSkip(
      SearchInputs inputs,
      String queryString,
      boolean canSparse,
      boolean canDense,
      boolean canSplade) {
    // Dense-only is recall-first: planner shortcuts are legal only when another leg can answer.
    if (!canDense || (!canSparse && !canSplade)) {
      return Optional.empty();
    }
    ResolvedConfig config = resolvedConfigSupplier.get();
    ResolvedConfig.HybridSearch hybrid = config != null ? config.hybridSearch() : null;
    int minChars =
        hybrid != null ? hybrid.vectorSkipMinChars() : DEFAULT_VECTOR_SKIP_MIN_CHARS;
    if (queryString.length() < minChars) {
      return Optional.of(SearchReasonCode.SKIPPED_SHORT_QUERY);
    }
    long fieldDocCount = inputs.qpp().fieldDocCount();
    if (fieldDocCount < MIN_DF_SKIP_FIELD_DOCS) {
      return Optional.empty();
    }
    double minDfFraction =
        hybrid != null
            ? hybrid.vectorSkipMinDfFraction()
            : DEFAULT_VECTOR_SKIP_MIN_DF_FRACTION;
    if (inputs.qpp().minTermDocFreqFraction() >= minDfFraction) {
      return Optional.of(SearchReasonCode.SKIPPED_NO_DISCRIMINATIVE_TERM);
    }
    return Optional.empty();
  }

  private static int clampLimit(int requested) {
    if (requested <= 0) return DEFAULT_LIMIT;
    return Math.min(requested, MAX_LIMIT);
  }

  private int computeRetrievalLimit(int limit, SearchInputs inputs) {
    boolean chunkMergeLikely =
        inputs.corpus().hasChunkDocs() && inputs.corpus().corpusSupportsChunks();
    return chunkMergeLikely ? Math.max(limit * 2, limit) : limit;
  }

  private LegSet selectLegSet(
      SearchInputs inputs,
      boolean canSparse,
      boolean canDense,
      boolean canSplade,
      int retrievalLimit) {
    var vec =
        inputs.encoding().vector() instanceof VectorEncoding.Success vs ? vs : null;
    var spl =
        inputs.encoding().splade() instanceof SpladeEncoding.Success ss ? ss : null;
    double hybridWeight = 1.0;
    if (canSparse && canDense && canSplade && vec != null && spl != null) {
      return new LegSet.ThreeWay(vec, spl, retrievalLimit, hybridWeight);
    }
    if (canSparse && canDense && vec != null) {
      return new LegSet.Bm25Dense(vec, retrievalLimit, hybridWeight);
    }
    if (canDense && !canSparse && !canSplade && vec != null) {
      return new LegSet.DenseOnly(vec, retrievalLimit);
    }
    if (canSplade && !canSparse && !canDense && spl != null) {
      return new LegSet.SpladeOnly(spl, retrievalLimit);
    }
    if (canSparse && canSplade && spl != null) {
      return new LegSet.Bm25Splade(spl, retrievalLimit);
    }
    if (canDense && canSplade && vec != null && spl != null) {
      return new LegSet.DenseSplade(vec, spl, retrievalLimit);
    }
    if (canSparse) {
      return new LegSet.Bm25Only(retrievalLimit);
    }
    return null;
  }

  private ChunkMergeDirective planChunkMerge(
      SearchInputs inputs,
      int limit,
      String queryString,
      boolean allowDenseVector) {
    SearchRequest request = inputs.request();
    boolean chunkAwareEnabled = resolvedConfigSupplier.get().search().chunkAwareEnabled();
    if (!chunkAwareEnabled) {
      return new ChunkMergeDirective.Skip(SearchReasonCode.SKIPPED_DISABLED);
    }
    boolean firstPage = request.getCursor().isBlank();
    if (!firstPage) {
      return new ChunkMergeDirective.Skip(SearchReasonCode.SKIPPED_PAGINATED);
    }
    boolean querySyntaxEligible =
        request.getQuerySyntax() != SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE;
    if (!querySyntaxEligible) {
      return new ChunkMergeDirective.Skip(SearchReasonCode.SKIPPED_QUERY_SYNTAX);
    }
    boolean relevanceSort =
        ProtoConverters.toRuntimeSort(request.getSort())
            == LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE;
    if (!relevanceSort) {
      return new ChunkMergeDirective.Skip(SearchReasonCode.SKIPPED_SORT_NOT_RELEVANCE);
    }
    if (!inputs.corpus().hasChunkDocs()) {
      return new ChunkMergeDirective.Skip(SearchReasonCode.SKIPPED_NO_CHUNK_DOCS);
    }
    if (!inputs.corpus().corpusSupportsChunks()) {
      return new ChunkMergeDirective.Skip(SearchReasonCode.SKIPPED_SHORT_CORPUS);
    }
    if (queryString.isBlank()) {
      return new ChunkMergeDirective.Skip(SearchReasonCode.SKIPPED_EMPTY_QUERY);
    }
    // EligibleApply — the executor checks hasBaseResults at runtime.
    java.util.List<Float> vecList = null;
    if (allowDenseVector && inputs.encoding().vector() instanceof VectorEncoding.Success vs) {
      vecList = vs.vector();
    }
    Map<String, Float> spladeWeights = null;
    if (inputs.encoding().splade() instanceof SpladeEncoding.Success ss) {
      spladeWeights = ss.weights();
    }
    return new ChunkMergeDirective.EligibleApply(
        new ChunkMergeInputs(limit, vecList, spladeWeights));
  }

  private static Map<String, Integer> buildFacetFields(FacetSpec facetsMsg) {
    Map<String, Integer> fields = new HashMap<>();
    for (FacetFieldSpec spec : facetsMsg.getFieldsList()) {
      if (spec == null) continue;
      String f = spec.getField();
      if (f.isBlank()) continue;
      int size = spec.getSize() <= 0 ? 10 : Math.min(spec.getSize(), 100);
      fields.put(f, size);
    }
    return fields;
  }

  public static PipelineConfig modeToDefaultPipeline(SearchMode mode) {
    return switch (mode) {
      case SEARCH_MODE_VECTOR -> PipelineConfig.newBuilder().setDenseEnabled(true).build();
      case SEARCH_MODE_HYBRID ->
          PipelineConfig.newBuilder()
              .setSparseEnabled(true)
              .setDenseEnabled(true)
              .setFusionAlgorithm("rrf")
              .setLambdamartEnabled(true)
              .build();
      case SEARCH_MODE_SPLADE ->
          PipelineConfig.newBuilder().setSpladeEnabled(true).setExpansionEnabled(true).build();
      default ->
          PipelineConfig.newBuilder()
              .setSparseEnabled(true)
              .setLambdamartEnabled(true)
              .setExpansionEnabled(true)
              .build();
    };
  }

  public static String deriveActualMode(boolean sparseRan, boolean denseRan, boolean spladeRan) {
    int count = (sparseRan ? 1 : 0) + (denseRan ? 1 : 0) + (spladeRan ? 1 : 0);
    if (count >= 2) return "HYBRID";
    if (denseRan) return "VECTOR";
    if (spladeRan) return "SPLADE";
    return "TEXT";
  }
}
