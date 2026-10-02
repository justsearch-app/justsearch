/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

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
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder;
import io.justsearch.indexerworker.util.ProtoConverters;
import io.justsearch.indexerworker.util.TextAnalysisUtils;
import io.justsearch.indexerworker.util.VectorUtils;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.SearchRequest;
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
    Objects.requireNonNull(request, "request");

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

    BgeM3Output bgeQueryOutput = null;
    BgeM3Encoding bgeEncoding = new BgeM3Encoding.NotRequested();
    if (snap.bgeM3Encoder() != null && (wantDense || wantSplade) && !queryString.isBlank()) {
      try {
        bgeQueryOutput = snap.bgeM3Encoder().encode(queryString);
        bgeEncoding = new BgeM3Encoding.Success(bgeQueryOutput);
      } catch (Exception e) {
        log.warn("BGE-M3 query encoding failed, falling back: {}", e.getMessage());
        bgeEncoding = new BgeM3Encoding.Failed(SearchReasonCode.EMBEDDING_EXCEPTION);
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
                snap.embeddingProvider())
            : new VectorEncoding.NotRequested();

    SpladeEncoding spladeEncoding =
        wantSplade
            ? prepareSpladeWeights(
                queryString,
                bgeQueryOutput,
                snap.spladeEncoder(),
                snap.spladeIdfQueryEncoder())
            : new SpladeEncoding.NotRequested();

    EncodingResults encoding = new EncodingResults(vectorEncoding, spladeEncoding, bgeEncoding);

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
      EmbeddingProvider embeddingProvider) {
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
      List<Float> list = new ArrayList<>(bgeQueryOutput.denseVector().length);
      for (float v : bgeQueryOutput.denseVector()) {
        list.add(v);
      }
      return new VectorEncoding.Success(list, "bgem3");
    }
    if (embeddingProvider != null
        && embeddingProvider.isAvailable()
        && !queryString.isBlank()) {
      try {
        float[] vec = embeddingProvider.embedQuery(queryString);
        if (vec == null || vec.length == 0) {
          return new VectorEncoding.Failed(SearchReasonCode.EMBEDDING_GENERATION_FAILED);
        }
        List<Float> list = new ArrayList<>(vec.length);
        for (float v : vec) {
          list.add(v);
        }
        return new VectorEncoding.Success(list, "embedding-service");
      } catch (RuntimeException e) {
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
      SpladeIdfQueryEncoder idfEncoder) {
    if (bgeQueryOutput != null && bgeQueryOutput.sparseWeights() != null) {
      Map<String, Float> weights =
          SpladeEncoder.pruneByBeta(bgeQueryOutput.sparseWeights(), 0.5f);
      return new SpladeEncoding.Success(weights);
    }
    if (onnxEncoder == null && idfEncoder == null) {
      return new SpladeEncoding.Failed(SearchReasonCode.NO_EMBEDDING_SERVICE);
    }
    try {
      Map<String, Float> weights;
      if (idfEncoder != null) {
        weights = SpladeEncoder.pruneByBeta(idfEncoder.encode(queryString), 0.5f);
      } else {
        weights = SpladeEncoder.pruneByBeta(onnxEncoder.encode(queryString), 0.5f);
      }
      return new SpladeEncoding.Success(weights);
    } catch (Exception e) {
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
