/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypesRuntimeSearchFiltersBuilder;
import io.justsearch.adapters.lucene.runtime.TextQueryOps;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.bgem3.BgeM3Output;
import io.justsearch.indexerworker.disambiguation.EntityClusterSnapshot;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.services.SearchReasonCode;
import io.justsearch.indexerworker.services.SearchReasonCode.LegCause;
import io.justsearch.indexerworker.services.WorkerServiceException;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder;
import io.justsearch.indexerworker.util.ProtoConverters;
import io.justsearch.indexerworker.util.TextAnalysisUtils;
import io.justsearch.indexerworker.util.VectorUtils;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ort.QueryCpuRecourse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import net.logstash.logback.marker.Markers;
import org.apache.lucene.analysis.Analyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-retrieval IO capture (tempdoc 517).
 *
 * <p>The ONLY class permitted to call {@code CommitOps.maybeRefresh()},
 * {@code IndexCountOps.getOrComputeCorpusProfile()},
 * {@code DocumentFieldOps.queryDocIdsByField(IS_CHUNK, ...)}, or any encoder
 * encode method. Enforced by ArchUnit rules in {@code IndexerWorkerGuardrailsTest}.
 *
 * <p>Captures the 10 IO sites enumerated in §A.1 into an immutable
 * {@link SearchInputs} value. The planner is pure over that value.
 */
public final class SearchInputCapture {
  private static final Logger log = LoggerFactory.getLogger(SearchInputCapture.class);
  private static final int MAX_EXPANDED_FILTER_TERMS = 500;

  private final TextQueryOps textQueryOps;
  private final IndexCountOps indexCountOps;
  private final CommitOps commitOps;
  private final DocumentFieldOps documentFieldOps;
  private final Supplier<ResolvedConfig> resolvedConfigSupplier;
  private final Supplier<Analyzer> indexAnalyzerSupplier;
  private final Supplier<Map<String, String>> commitMetadataSupplier;

  // Deferred-injection sources — Phase 2 holder migration deferred.
  private final EncoderSnapshotProvider encoderSnapshots;

  public SearchInputCapture(
      TextQueryOps textQueryOps,
      IndexCountOps indexCountOps,
      CommitOps commitOps,
      DocumentFieldOps documentFieldOps,
      Supplier<ResolvedConfig> resolvedConfigSupplier,
      Supplier<Analyzer> indexAnalyzerSupplier,
      Supplier<Map<String, String>> commitMetadataSupplier,
      EncoderSnapshotProvider encoderSnapshots) {
    this.textQueryOps = textQueryOps;
    this.indexCountOps = indexCountOps;
    this.commitOps = commitOps;
    this.documentFieldOps = documentFieldOps;
    this.resolvedConfigSupplier = resolvedConfigSupplier;
    this.indexAnalyzerSupplier = indexAnalyzerSupplier;
    this.commitMetadataSupplier = commitMetadataSupplier;
    this.encoderSnapshots = encoderSnapshots;
  }

  /**
   * Snapshot of the deferred-injection encoder/supplier state, read once per
   * request entry. Phase 2 will collapse the 6 volatile fields on
   * {@code SearchOrchestrator} into one volatile holder; until then, the
   * facade provides this snapshot via the {@link EncoderSnapshotProvider}.
   */
  public record EncoderSnapshot(
      EmbeddingProvider embeddingProvider,
      Supplier<EntityClusterSnapshot> clusterSnapshotSupplier,
      Supplier<String> activeGenerationSupplier,
      SpladeEncoder spladeEncoder,
      SpladeIdfQueryEncoder spladeIdfQueryEncoder,
      BgeM3Encoder bgeM3Encoder) {}

  /** Functional source of the per-request encoder snapshot. */
  @FunctionalInterface
  public interface EncoderSnapshotProvider {
    EncoderSnapshot snapshot();
  }

  /** Captures all pre-retrieval IO into an immutable {@link SearchInputs}. */
  public SearchInputs capture(
      SearchRequest request, boolean allowQueryEmbeddings, String compatReasonCode) {
    return capture(request, allowQueryEmbeddings, compatReasonCode,
        InferenceRequest.foreground(), SemanticDeadline.unbounded());
  }

  /**
   * As {@link #capture(SearchRequest, boolean, String)}, under the caller's request and the
   * search's semantic sub-budget (Spec D §1.3). The BGE-M3, dense and SPLADE ONNX legs run under
   * {@code semantic}; a leg that misses it becomes {@code SEMANTIC_BUDGET_EXCEEDED} with a cause,
   * while a cancelled request or a passed request deadline still propagates.
   */
  public SearchInputs capture(
      SearchRequest request, boolean allowQueryEmbeddings, String compatReasonCode,
      InferenceRequest acquisition, SemanticDeadline semantic) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(semantic, "semantic");

    EncoderSnapshot snap = encoderSnapshots.snapshot();
    String queryString = request.getQuery();

    // 1. Refresh index for near-real-time results.
    commitOps.maybeRefresh();

    // 2. Cluster snapshot for entity-filter expansion + facet merging.
    EntityClusterSnapshot clusterSnapshot = currentSnapshot(snap.clusterSnapshotSupplier());

    // 3. Corpus capabilities probe.
    // Mirror the planner's pipeline fallback so QPP eligibility matches the legacy behaviour:
    // when the request omits pipeline, fall back to the mode-derived default.
    var pipeline =
        request.hasPipeline()
            ? request.getPipeline()
            : io.justsearch.indexerworker.services.plan.SearchPlanner.modeToDefaultPipeline(
                request.getMode());
    boolean chunkAwarePotential =
        resolvedConfigSupplier.get().search().chunkAwareEnabled()
            && !queryString.isBlank()
            && request.getCursor().isBlank()
            && request.getQuerySyntax()
                != io.justsearch.ipc.SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE
            && ProtoConverters.toRuntimeSort(request.getSort())
                == LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE;
    boolean hasChunkDocs = false;
    if (chunkAwarePotential) {
      hasChunkDocs =
          !documentFieldOps.queryDocIdsByField(SchemaFields.IS_CHUNK, "true", 1).isEmpty();
    }
    var corpusProfile =
        hasChunkDocs
            ? indexCountOps.getOrComputeCorpusProfile()
            : io.justsearch.adapters.lucene.runtime.CorpusProfile.EMPTY;
    CorpusCapabilities corpus =
        new CorpusCapabilities(
            hasChunkDocs,
            corpusProfile.isShortCorpus(),
            corpusProfile.medianTokenCount(),
            corpusProfile.chunkRate());

    // 4. Filter expansion (entity normalization).
    var filtersMsg = request.hasFilters() ? request.getFilters() : null;
    LuceneRuntimeTypes.RuntimeSearchFilters runtimeFilters =
        expandEntityFilters(ProtoConverters.toRuntimeFilters(filtersMsg), clusterSnapshot);
    if (runtimeFilters == null) {
      // Build empty filters when none supplied so downstream code sees a non-null value.
      runtimeFilters = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().build();
    }

    // 5. Encoder capture — BGE-M3 then individual encoders.
    boolean wantDense = pipeline.getDenseEnabled();
    boolean wantSplade = pipeline.getSpladeEnabled();

    LegBudget legs = new LegBudget(acquisition, semantic);
    BgeM3Output bgeQueryOutput = null;
    BgeM3Encoding bgeEncoding = new BgeM3Encoding.NotRequested();
    if (snap.bgeM3Encoder() != null && (wantDense || wantSplade) && !queryString.isBlank()) {
      LegCause unstarted = legs.unstartedCause(true);
      if (unstarted != null) {
        bgeEncoding = new BgeM3Encoding.Failed(SearchReasonCode.SEMANTIC_BUDGET_EXCEEDED, unstarted);
      } else {
        try (QueryCpuRecourse.Observation observed = QueryCpuRecourse.observe()) {
          try {
            bgeQueryOutput = snap.bgeM3Encoder().encode(queryString, legs.legRequest());
            bgeEncoding = new BgeM3Encoding.Success(bgeQueryOutput);
            observed.succeeded();
          } finally {
            legs.bgeServed = observed.served();
          }
        } catch (Exception e) {
          LegCause cause = legs.missCause(e, "bgem3");
          if (cause != null) {
            bgeEncoding = new BgeM3Encoding.Failed(SearchReasonCode.SEMANTIC_BUDGET_EXCEEDED, cause);
          } else {
            log.warn("BGE-M3 query encoding failed, falling back: {}", e.getMessage());
            bgeEncoding = new BgeM3Encoding.Failed(SearchReasonCode.EMBEDDING_EXCEPTION);
          }
        }
      }
    }

    VectorEncoding vectorEncoding =
        wantDense
            ? prepareQueryVector(
                request.getVectorList(),
                queryString,
                allowQueryEmbeddings,
                compatReasonCode,
                bgeQueryOutput,
                snap.embeddingProvider(), legs)
            : new VectorEncoding.NotRequested();

    SpladeEncoding spladeEncoding =
        wantSplade
            ? prepareSpladeWeights(
                queryString,
                bgeQueryOutput,
                snap.spladeEncoder(),
                snap.spladeIdfQueryEncoder(), allowQueryEmbeddings && switch (snap.embeddingProvider() == null
                    ? EmbeddingProvider.Availability.State.ABSENT
                    : snap.embeddingProvider().availability().state()) {
                  case STARTING -> true;
                  case READY, ABSENT, FAILED -> false;
                }, legs)
            : new SpladeEncoding.NotRequested();

    // A unified encoder supplies both legs. Keep its failure when there is no successful
    // replacement (or explicit dense vector), instead of relabelling it as a missing service.
    if (bgeEncoding instanceof BgeM3Encoding.Failed failed) {
      if (allowQueryEmbeddings && vectorEncoding instanceof VectorEncoding.Failed) {
        vectorEncoding = new VectorEncoding.Failed(failed.reason(), failed.cause());
      }
      if (spladeEncoding instanceof SpladeEncoding.Failed) {
        spladeEncoding = new SpladeEncoding.Failed(failed.reason(), failed.cause());
      }
    }

    EncodingResults encoding =
        new EncodingResults(
            vectorEncoding,
            spladeEncoding,
            bgeEncoding,
            wantDense ? legs.served(legs.denseServed) : null,
            wantSplade ? legs.served(legs.spladeServed) : null);

    // 6. QPP signals (lexical only).
    QppMetrics qpp = QppMetrics.ZERO;
    if (pipeline.getSparseEnabled() || pipeline.getSpladeEnabled()) {
      qpp = computeQpp(queryString);
    }

    // 7. Boundary mapping for compat string.
    EmbeddingCompatBoundary compatBoundary =
        EmbeddingCompatBoundary.of(compatReasonCode, allowQueryEmbeddings);

    // 8. Commit metadata + active generation (best-effort).
    Map<String, String> commitMetadata = Map.of();
    try {
      var md = commitMetadataSupplier.get();
      if (md != null) {
        commitMetadata = Map.copyOf(md);
      }
    } catch (RuntimeException e) {
      log.debug("commit metadata supplier failed (best-effort)", e);
    }
    String activeGeneration = null;
    try {
      activeGeneration = snap.activeGenerationSupplier().get();
    } catch (RuntimeException e) {
      log.debug("active generation supplier failed (best-effort)", e);
    }

    return new SearchInputs(
        request,
        runtimeFilters,
        clusterSnapshot,
        corpus,
        encoding,
        qpp,
        compatBoundary,
        commitMetadata,
        activeGeneration);
  }

  // ============================================================
  // Helpers (moved verbatim from legacy SearchOrchestrator)
  // ============================================================

  /**
   * The request and the semantic sub-budget that one capture's encoder legs share, with the
   * catch-site rule of Spec D §1.3: a cancelled request or a passed request deadline propagates; a
   * passed semantic deadline is a {@code SEMANTIC_BUDGET_EXCEEDED} leg with a cause, or, for a
   * VECTOR-only request that has no keyword fallback, a typed deadline failure (§5.3); anything
   * else is the leg's ordinary failure.
   */
  private static final class LegBudget {
    private final InferenceRequest request;
    private final SemanticDeadline semantic;
    private final InferenceRequest legRequest;
    private LegCause lastMiss;
    // The device that served each leg's encode (the search trace's per-leg note); BGE-M3 serves
    // both legs from one encode.
    private QueryCpuRecourse.Served bgeServed;
    private QueryCpuRecourse.Served denseServed;
    private QueryCpuRecourse.Served spladeServed;

    LegBudget(InferenceRequest request, SemanticDeadline semantic) {
      this.request = request;
      this.semantic = semantic;
      this.legRequest = semantic.narrow(request);
    }

    /**
     * The device that served a leg: its own encode's, or else the BGE-M3 encode's that the leg was
     * to come from (which may have missed).
     */
    QueryCpuRecourse.Served served(QueryCpuRecourse.Served leg) {
      return leg != null ? leg : bgeServed;
    }

    /** The caller's request: cancellation and the request deadline. */
    InferenceRequest request() {
      return request;
    }

    /** The request a semantic leg runs under. */
    InferenceRequest legRequest() {
      return legRequest;
    }

    /**
     * The cause for a leg that will not be started, or null when it may run (or, with no encoder,
     * report its absence): the models-ready wait used the whole sub-budget ({@code loading}),
     * whether or not the encoder was wired yet, or an earlier leg used it (that leg's cause).
     */
    LegCause unstartedCause(boolean encoderPresent) {
      request.remainingNanos();
      if (!semantic.bounded()) return null;
      if (semantic.modelsTimedOut()) return missed(LegCause.LOADING, "all");
      if (encoderPresent && semantic.passed()) {
        return missed(lastMiss != null ? lastMiss : LegCause.CONTENDED, "unstarted");
      }
      return null;
    }

    /** The cause when {@code failure} is a semantic budget miss, or null for an ordinary failure. */
    LegCause missCause(Exception failure, String leg) {
      request.remainingNanos();
      if (!semantic.bounded()) return null;
      // A deadline miss before the semantic deadline passed is the device's breaker turning the
      // leg away on arrival (Spec D §2.2); the encoder may have wrapped it.
      if (!semantic.passed() && !LegCauses.isDeadlineMiss(failure)) {
        return null;
      }
      if (LegCauses.breakerOpen(failure)) {
        log.debug(
            Markers.append("leg", leg).and(Markers.append("breaker_open", true)),
            "Semantic leg {} turned away: the device's breaker is open",
            leg);
      }
      return missed(LegCauses.of(failure), leg);
    }

    /** A failure of a leg that runs on the request itself (the CPU IDF SPLADE path). */
    LegCause requestFailure(Exception failure) {
      request.remainingNanos();
      return null;
    }

    private LegCause missed(LegCause cause, String leg) {
      lastMiss = cause;
      if (semantic.kind() == InferenceRequest.SemanticBudget.REQUIRED) {
        throw WorkerServiceException.deadlineExceeded(
            SearchReasonCode.SEMANTIC_BUDGET_EXCEEDED.wire() + " (cause: " + cause.wire() + ")");
      }
      io.opentelemetry.api.trace.Span.current()
          .setAttribute("search.semantic_budget." + leg + ".cause", cause.wire());
      log.info(
          Markers.append("reason_code", SearchReasonCode.SEMANTIC_BUDGET_EXCEEDED.wire())
              .and(Markers.append("leg", leg))
              .and(Markers.append("leg_cause", cause.wire())),
          "Semantic leg {} missed the search's semantic sub-budget (cause: {})",
          leg,
          cause.wire());
      return cause;
    }
  }

  private QppMetrics computeQpp(String queryString) {
    if (queryString == null || queryString.isBlank()) return QppMetrics.ZERO;
    Analyzer analyzer = indexAnalyzerSupplier.get();
    if (analyzer == null) return QppMetrics.ZERO;
    Set<String> terms =
        TextAnalysisUtils.analyzeTerms(analyzer, SchemaFields.CONTENT, queryString);
    if (terms.isEmpty()) return QppMetrics.ZERO;

    LuceneRuntimeTypes.QppSignals signals =
        textQueryOps.getQppSignals(SchemaFields.CONTENT, terms);
    long fieldDocCount = signals.fieldDocCount();
    if (fieldDocCount <= 0) return QppMetrics.ZERO;

    float maxIdf = 0f;
    double sumIctf = 0.0;
    double queryScope = 1.0;
    double minTermDocFreqFraction = 1.0;

    for (String term : terms) {
      int df = signals.docFreqs().getOrDefault(term, 0);
      long tf = signals.termCollFreqs().getOrDefault(term, 0L);

      float idf = (float) Math.log((fieldDocCount + 1.0) / (df + 1.0));
      if (idf > maxIdf) maxIdf = idf;

      long S = signals.sumTotalTermFreq();
      double ictf = Math.log((S + 1.0) / (tf + 1.0));
      sumIctf += ictf;

      double docFreqFraction = (double) df / fieldDocCount;
      queryScope *= (1.0 - docFreqFraction);
      minTermDocFreqFraction = Math.min(minTermDocFreqFraction, docFreqFraction);
    }

    float avgIctf = (float) (sumIctf / terms.size());
    float qs = (float) (1.0 - queryScope);
    return new QppMetrics(
        maxIdf,
        avgIctf,
        Math.max(0f, Math.min(1f, qs)),
        fieldDocCount,
        (float) Math.max(0.0, Math.min(1.0, minTermDocFreqFraction)));
  }

  private VectorEncoding prepareQueryVector(
      List<Float> vectorList,
      String queryString,
      boolean allowQueryEmbeddings,
      String compatReasonCode,
      BgeM3Output bgeQueryOutput,
      EmbeddingProvider embeddingProvider,
      LegBudget legs) {
    legs.request().remainingNanos();
    if (!vectorList.isEmpty()) {
      return new VectorEncoding.Success(new ArrayList<>(vectorList), "explicit");
    }
    if (!allowQueryEmbeddings) {
      SearchReasonCode reason =
          compatReasonCode != null
              ? SearchReasonCode.fromCompatString(compatReasonCode)
              : SearchReasonCode.EMBEDDING_COMPATIBILITY_BLOCKED;
      return new VectorEncoding.Failed(reason);
    }
    if (bgeQueryOutput != null && bgeQueryOutput.denseVector() != null) {
      legs.denseServed = legs.bgeServed;
      List<Float> list = new ArrayList<>(bgeQueryOutput.denseVector().length);
      for (float v : bgeQueryOutput.denseVector()) {
        list.add(v);
      }
      return new VectorEncoding.Success(list, "bgem3");
    }
    var availability = embeddingProvider == null
        ? EmbeddingProvider.Availability.ABSENT : embeddingProvider.availability();
    boolean canEmbed = switch (availability.state()) {
      case READY -> true;
      case STARTING, ABSENT, FAILED -> false;
    };
    boolean encoderPresent = switch (availability.state()) {
      case READY, STARTING -> true;
      case ABSENT -> false;
      case FAILED -> {
        log.warn("Embedding model load failed", availability.failure());
        yield false;
      }
    };
    LegCause unstarted = queryString.isBlank() || !encoderPresent
        ? null : legs.unstartedCause(true);
    if (unstarted != null) {
      return new VectorEncoding.Failed(SearchReasonCode.SEMANTIC_BUDGET_EXCEEDED, unstarted);
    }
    if (canEmbed && !queryString.isBlank()) {
      try (QueryCpuRecourse.Observation observed = QueryCpuRecourse.observe()) {
        float[] vec;
        try {
          vec = embeddingProvider.embedQuery(queryString, legs.legRequest());
        } finally {
          legs.denseServed = observed.served();
        }
        if (vec == null || vec.length == 0) {
          return new VectorEncoding.Failed(SearchReasonCode.EMBEDDING_GENERATION_FAILED);
        }
        List<Float> list = new ArrayList<>(vec.length);
        for (float v : vec) {
          list.add(v);
        }
        observed.succeeded();
        return new VectorEncoding.Success(list, "embedding-service");
      } catch (RuntimeException e) {
        LegCause cause = legs.missCause(e, "dense");
        if (cause != null) {
          return new VectorEncoding.Failed(SearchReasonCode.SEMANTIC_BUDGET_EXCEEDED, cause);
        }
        log.warn("Embedding generation failed: {}", e.getMessage());
        return new VectorEncoding.Failed(SearchReasonCode.EMBEDDING_EXCEPTION);
      }
    }
    return new VectorEncoding.Failed(SearchReasonCode.NO_EMBEDDING_SERVICE);
  }

  private SpladeEncoding prepareSpladeWeights(
      String queryString,
      BgeM3Output bgeQueryOutput,
      SpladeEncoder onnxEncoder,
      SpladeIdfQueryEncoder idfEncoder,
      boolean compatibleModelPending,
      LegBudget legs) {
    legs.request().remainingNanos();
    if (bgeQueryOutput != null && bgeQueryOutput.sparseWeights() != null) {
      legs.spladeServed = legs.bgeServed;
      Map<String, Float> weights =
          SpladeEncoder.pruneByBeta(bgeQueryOutput.sparseWeights(), 0.5f);
      return new SpladeEncoding.Success(weights);
    }
    // The CPU IDF path is not a semantic leg: it stays on the request (Spec D §1.3).
    if (idfEncoder == null && (onnxEncoder != null || compatibleModelPending)) {
      LegCause unstarted = legs.unstartedCause(true);
      if (unstarted != null) {
        return new SpladeEncoding.Failed(SearchReasonCode.SEMANTIC_BUDGET_EXCEEDED, unstarted);
      }
    }
    if (onnxEncoder == null && idfEncoder == null) {
      return new SpladeEncoding.Failed(SearchReasonCode.NO_EMBEDDING_SERVICE);
    }
    try {
      Map<String, Float> weights;
      if (idfEncoder != null) {
        weights = SpladeEncoder.pruneByBeta(idfEncoder.encode(queryString), 0.5f);
      } else {
        try (QueryCpuRecourse.Observation observed = QueryCpuRecourse.observe()) {
          try {
            weights =
                SpladeEncoder.pruneByBeta(onnxEncoder.encode(queryString, legs.legRequest()), 0.5f);
            observed.succeeded();
          } finally {
            legs.spladeServed = observed.served();
          }
        }
      }
      return new SpladeEncoding.Success(weights);
    } catch (Exception e) {
      LegCause cause = idfEncoder != null ? legs.requestFailure(e) : legs.missCause(e, "splade");
      if (cause != null) {
        return new SpladeEncoding.Failed(SearchReasonCode.SEMANTIC_BUDGET_EXCEEDED, cause);
      }
      log.warn(
          Markers.append("reason_code", "splade_encoding_failed")
              .and(Markers.append("error_type", e.getClass().getSimpleName())),
          "SPLADE query encoding failed: {}",
          e.getMessage());
      return new SpladeEncoding.Failed(SearchReasonCode.EMBEDDING_GENERATION_FAILED);
    }
  }

  private LuceneRuntimeTypes.RuntimeSearchFilters expandEntityFilters(
      LuceneRuntimeTypes.RuntimeSearchFilters filters, EntityClusterSnapshot snapshot) {
    if (filters == null) {
      return null;
    }
    if (snapshot == null || snapshot.isEmpty()) {
      return filters;
    }

    List<String> persons = expandFilterList(filters.entityPersons(), "PERSON", snapshot);
    List<String> orgs = expandFilterList(filters.entityOrganizations(), "ORGANIZATION", snapshot);
    List<String> locs = expandFilterList(filters.entityLocations(), "LOCATION", snapshot);

    if (persons == filters.entityPersons()
        && orgs == filters.entityOrganizations()
        && locs == filters.entityLocations()) {
      return filters;
    }

    return LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
        .mime(filters.mime())
        .language(filters.language())
        .fileKind(filters.fileKind())
        .mimeBase(filters.mimeBase())
        .pathPrefix(filters.pathPrefix())
        .modifiedFromMs(filters.modifiedFromMs())
        .modifiedToMs(filters.modifiedToMs())
        .includeChunks(filters.includeChunks())
        .entityPersons(persons)
        .entityOrganizations(orgs)
        .entityLocations(locs)
        .metaSource(filters.metaSource())
        .metaAuthor(filters.metaAuthor())
        .metaCategory(filters.metaCategory())
        .metaPublishedFromMs(filters.metaPublishedFromMs())
        .metaPublishedToMs(filters.metaPublishedToMs())
        .build();
  }

  // visible for testing
  static List<String> expandFilterList(
      List<String> values, String entityType, EntityClusterSnapshot snapshot) {
    if (values == null || values.isEmpty()) {
      return values;
    }
    Set<String> expanded = new HashSet<>(values);
    for (String value : values) {
      expanded.addAll(
          snapshot.expandCanonical(entityType, snapshot.getCanonical(entityType, value)));
    }
    if (expanded.size() > MAX_EXPANDED_FILTER_TERMS) {
      log.warn(
          "Entity filter expansion for {} exceeded cap ({} > {}), using unexpanded values",
          entityType,
          expanded.size(),
          MAX_EXPANDED_FILTER_TERMS);
      return values;
    }
    return expanded.size() == values.size() ? values : new ArrayList<>(expanded);
  }

  private EntityClusterSnapshot currentSnapshot(
      Supplier<EntityClusterSnapshot> supplier) {
    if (supplier == null) return null;
    try {
      return supplier.get();
    } catch (Exception e) {
      log.warn("Failed to get cluster snapshot", e);
      return null;
    }
  }
}
