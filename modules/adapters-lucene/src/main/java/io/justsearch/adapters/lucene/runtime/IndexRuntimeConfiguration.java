/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.configuration.ConfigKey;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.SoftDeletesRetentionMergePolicy;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;

/** Immutable projection of the configuration values actually selected by the Lucene runtime. */
public final class IndexRuntimeConfiguration {
  private static final Set<String> DEPENDENCIES = dependenciesInternal();

  private final Map<String, Object> values;

  private IndexRuntimeConfiguration(Map<String, Object> values) {
    this.values = immutableMap(values);
  }

  /** Declared configuration keys consumed by the Lucene runtime projection. */
  public static Set<String> dependencies() {
    return DEPENDENCIES;
  }

  Map<String, Object> values() {
    return values;
  }

  static IndexRuntimeConfiguration fromFactory(
      ResolvedConfig.Index idx,
      Directory directory,
      IndexWriterConfig writerConfig,
      TieredMergePolicy tieredMergePolicy,
      MergePolicy installedMergePolicy,
      FieldMapper fieldMapper,
      KnnVectorsFormat selectedVectorFormat,
      boolean configuredVectorFormatSelected,
      int hnswM,
      int hnswEfConstruction,
      String softDeleteField,
      long nrtTargetMs,
      long nrtHardMs,
      NrtMode nrtMode,
      long nrtBackgroundMs,
      long nrtOnDemandMaxStaleMs) {
    Map<String, Object> result = new TreeMap<>();
    put(result, ConfigKey.INDEX_COMMIT_META_ENABLED, idx.commitMetadataEnabled());
    put(result, ConfigKey.INDEX_DIRECTORY_TYPE, directoryType(directory));
    put(result, ConfigKey.INDEX_WRITER_RAM_BUFFER_MB, writerConfig.getRAMBufferSizeMB());
    put(result, ConfigKey.INDEX_WRITER_MAX_BUFFERED_DOCS, writerConfig.getMaxBufferedDocs());
    put(result, ConfigKey.INDEX_MERGE_SEGS_PER_TIER, tieredMergePolicy.getSegmentsPerTier());
    put(
        result,
        ConfigKey.INDEX_MERGE_MAX_MERGED_SEGMENT_MB,
        tieredMergePolicy.getMaxMergedSegmentMB());
    put(result, ConfigKey.INDEX_SOFT_DELETES_FIELD, softDeleteField);
    boolean retentionInstalled =
        installedMergePolicy instanceof SoftDeletesRetentionMergePolicy
            || installedMergePolicy instanceof TelemetrySoftDeletesMergePolicy;
    put(result, ConfigKey.INDEX_SOFT_DELETES_RETENTION_ENABLED, retentionInstalled);
    put(
        result,
        ConfigKey.INDEX_SOFT_DELETES_RETENTION_DAYS,
        retentionInstalled
            ? LuceneRuntimeUtils.effectiveSoftDeleteRetentionDays(
                idx.softDeletesRetentionDays())
            : null);
    put(
        result,
        ConfigKey.INDEX_SOFT_DELETES_RETENTION_MAX_VERSIONS,
        retentionInstalled
            ? LuceneRuntimeUtils.effectiveSoftDeleteRetentionMaxVersions(
                idx.softDeletesRetentionMaxVersions())
            : null);

    var similarity = writerConfig.getSimilarity();
    put(result, ConfigKey.INDEX_SIMILARITY_TEXT_TYPE, similarity.getClass().getName());
    if (similarity instanceof BM25Similarity bm25) {
      put(result, ConfigKey.INDEX_SIMILARITY_TEXT_K1, bm25.getK1());
      put(result, ConfigKey.INDEX_SIMILARITY_TEXT_B, bm25.getB());
    } else {
      put(result, ConfigKey.INDEX_SIMILARITY_TEXT_K1, null);
      put(result, ConfigKey.INDEX_SIMILARITY_TEXT_B, null);
    }
    put(result, ConfigKey.INDEX_SORT, appliedSort(writerConfig.getIndexSort()));
    put(result, ConfigKey.INDEX_VECTOR_DIMENSION, appliedVectorDimensions(idx, fieldMapper));
    put(result, EnvRegistry.INDEX_VECTOR_HNSW_M,
        configuredVectorFormatSelected ? hnswM : null);
    put(result, EnvRegistry.INDEX_VECTOR_HNSW_EF_CONSTRUCTION,
        configuredVectorFormatSelected ? hnswEfConstruction : null);
    put(
        result,
        EnvRegistry.INDEX_VECTOR_QUANTIZATION_ENABLED,
        selectedVectorFormat
            instanceof org.apache.lucene.codecs.lucene104.Lucene104HnswScalarQuantizedVectorsFormat);

    put(result, ConfigKey.INDEX_NRT_TARGET_MAX_STALE_MS, nrtTargetMs);
    put(result, ConfigKey.INDEX_NRT_MAX_STALE_MS, nrtHardMs);
    put(result, EnvRegistry.INDEX_NRT_MODE, nrtMode.name().toLowerCase(java.util.Locale.ROOT));
    put(result, EnvRegistry.INDEX_NRT_BACKGROUND_REOPEN_MS,
        nrtMode == NrtMode.ON_DEMAND ? nrtBackgroundMs : null);
    put(result, EnvRegistry.INDEX_NRT_ON_DEMAND_MAX_STALE_MS,
        nrtMode == NrtMode.ON_DEMAND ? nrtOnDemandMaxStaleMs : null);
    return new IndexRuntimeConfiguration(result);
  }

  IndexRuntimeConfiguration withSession(
      ResolvedConfig resolvedConfig,
      Integer vectorEfSearchOverride,
      boolean vectorExhaustiveSearch,
      long maxQueueDepth,
      ValidationMode validationMode) {
    Map<String, Object> result = new TreeMap<>(values);
    ResolvedConfig.Index idx = resolvedConfig.index();
    put(result, EnvRegistry.INDEX_VECTOR_EF_SEARCH, vectorEfSearchOverride);
    put(result, EnvRegistry.INDEX_VECTOR_EXHAUSTIVE_SEARCH, vectorExhaustiveSearch);
    put(result, ConfigKey.INDEX_QUEUE_MAX_DEPTH, maxQueueDepth);
    put(result, ConfigKey.INDEX_VALIDATION_MODE,
        validationMode.name().toLowerCase(java.util.Locale.ROOT));
    put(
        result,
        EnvRegistry.INDEX_COMMIT_TIMER_INTERVAL_MS,
        CommitOps.effectiveCommitTimerIntervalMs(idx.commitTimerIntervalMs()));
    addHybrid(result, resolvedConfig.hybridSearch());
    return new IndexRuntimeConfiguration(result);
  }

  private static void addHybrid(Map<String, Object> result, ResolvedConfig.HybridSearch hs) {
    put(result, EnvRegistry.HYBRID_CANDIDATE_LIMIT_MAX, hs.candidateLimitMax());
    put(result, EnvRegistry.HYBRID_TEXT_CANDIDATE_MULTIPLIER, hs.textCandidateMultiplier());
    put(result, EnvRegistry.HYBRID_VECTOR_CANDIDATE_MULTIPLIER, hs.vectorCandidateMultiplier());
    String strategy = "cc".equals(hs.fusionStrategy()) ? "cc" : "rrf";
    put(result, EnvRegistry.HYBRID_FUSION_STRATEGY, strategy);
    put(result, EnvRegistry.HYBRID_RERANK_POOL_RECALL_COMPLETE, hs.legRecallCompleteEnabled());
    put(result, EnvRegistry.HYBRID_RERANK_POOL_TOP_N,
        hs.legRecallCompleteEnabled() ? hs.legRecallCompleteTopN() : null);

    boolean rrf = "rrf".equals(strategy);
    put(result, EnvRegistry.HYBRID_RRF_K, rrf ? hs.rrfK() : null);
    put(result, EnvRegistry.HYBRID_VECTOR_RRF_WEIGHT, rrf ? hs.vectorRrfWeight() : null);
    put(result, EnvRegistry.HYBRID_BM25_SCORE_BOOST_WEIGHT,
        rrf ? hs.bm25ScoreBoostWeight() : null);
    put(result, EnvRegistry.HYBRID_VECTOR_LOW_SIGNAL_TOP_SCORE_THRESHOLD,
        rrf ? hs.vectorLowSignalTopScoreThreshold() : null);
    put(result, EnvRegistry.HYBRID_BM25_LOW_SIGNAL_TOP_SCORE_THRESHOLD,
        rrf ? hs.bm25LowSignalTopScoreThreshold() : null);
    put(result, EnvRegistry.HYBRID_BM25_LOW_SIGNAL_TOTAL_HITS_THRESHOLD,
        rrf ? hs.bm25LowSignalTotalHitsThreshold() : null);
    put(result, EnvRegistry.HYBRID_VECTOR_ONLY_CAP_LOW_SIGNAL,
        rrf ? hs.vectorOnlyCapLowSignal() : null);
    put(result, EnvRegistry.HYBRID_VECTOR_RRF_WEIGHT_LOW_SIGNAL,
        rrf ? hs.vectorRrfWeightLowSignal() : null);

    boolean cc = !rrf;
    put(result, EnvRegistry.HYBRID_CC_ALPHA, cc ? hs.ccAlpha() : null);
    put(result, EnvRegistry.HYBRID_CC_ZERO_EXCLUDE, cc ? hs.ccZeroExclude() : null);
    put(result, EnvRegistry.HYBRID_LEG_ARBITRATION_ENABLED,
        cc ? hs.legArbitrationEnabled() : null);
    boolean arbitration = cc && hs.legArbitrationEnabled();
    put(result, EnvRegistry.HYBRID_LEG_ARBITRATION_ALPHA_DIVERGE,
        arbitration ? hs.legArbitrationAlphaDiverge() : null);
    put(result, EnvRegistry.HYBRID_LEG_ARBITRATION_BM25_INCOHERENCE_MIN,
        arbitration ? hs.legArbitrationBm25IncoherenceMin() : null);
  }

  private static Object appliedVectorDimensions(ResolvedConfig.Index idx, FieldMapper mapper) {
    Map<String, Object> dimension = new LinkedHashMap<>();
    dimension.put("configuredGuard", idx.vectorDimension());
    Map<String, Integer> mapperDimensions = new TreeMap<>();
    for (FieldMapper.FieldDef def : mapper.fieldDefs().values()) {
      if ("vector".equals(def.type) && def.vectorDim != null) {
        mapperDimensions.put(def.id, def.vectorDim);
      }
    }
    dimension.put("mapperDimensions", Collections.unmodifiableMap(mapperDimensions));
    return Collections.unmodifiableMap(dimension);
  }

  private static List<Map<String, Object>> appliedSort(Sort sort) {
    if (sort == null) return List.of();
    List<Map<String, Object>> result = new ArrayList<>();
    for (SortField field : sort.getSort()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("field", field.getField());
      item.put("type", field.getType().name());
      item.put("reverse", field.getReverse());
      result.add(Collections.unmodifiableMap(item));
    }
    return List.copyOf(result);
  }

  private static String directoryType(Directory directory) {
    if (directory instanceof org.apache.lucene.store.NIOFSDirectory) return "NIOFS";
    if (directory instanceof org.apache.lucene.store.MMapDirectory) return "MMAP";
    return directory.getClass().getName();
  }

  private static Set<String> dependenciesInternal() {
    Set<String> result = new TreeSet<>();
    for (ConfigKey key : new ConfigKey[] {
        ConfigKey.INDEX_COMMIT_META_ENABLED,
        ConfigKey.INDEX_DIRECTORY_TYPE,
        ConfigKey.INDEX_WRITER_RAM_BUFFER_MB,
        ConfigKey.INDEX_WRITER_MAX_BUFFERED_DOCS,
        ConfigKey.INDEX_MERGE_SEGS_PER_TIER,
        ConfigKey.INDEX_MERGE_MAX_MERGED_SEGMENT_MB,
        ConfigKey.INDEX_SOFT_DELETES_FIELD,
        ConfigKey.INDEX_SOFT_DELETES_RETENTION_ENABLED,
        ConfigKey.INDEX_SOFT_DELETES_RETENTION_DAYS,
        ConfigKey.INDEX_SOFT_DELETES_RETENTION_MAX_VERSIONS,
        ConfigKey.INDEX_SIMILARITY_TEXT_TYPE,
        ConfigKey.INDEX_SIMILARITY_TEXT_K1,
        ConfigKey.INDEX_SIMILARITY_TEXT_B,
        ConfigKey.INDEX_SORT,
        ConfigKey.INDEX_VECTOR_DIMENSION,
        ConfigKey.INDEX_NRT_TARGET_MAX_STALE_MS,
        ConfigKey.INDEX_NRT_MAX_STALE_MS,
        ConfigKey.INDEX_QUEUE_MAX_DEPTH,
        ConfigKey.INDEX_VALIDATION_MODE}) {
      result.add(key.configKey());
    }
    for (EnvRegistry key : new EnvRegistry[] {
        EnvRegistry.INDEX_VECTOR_HNSW_M,
        EnvRegistry.INDEX_VECTOR_HNSW_EF_CONSTRUCTION,
        EnvRegistry.INDEX_VECTOR_EF_SEARCH,
        EnvRegistry.INDEX_VECTOR_EXHAUSTIVE_SEARCH,
        EnvRegistry.INDEX_VECTOR_QUANTIZATION_ENABLED,
        EnvRegistry.INDEX_NRT_MODE,
        EnvRegistry.INDEX_NRT_BACKGROUND_REOPEN_MS,
        EnvRegistry.INDEX_NRT_ON_DEMAND_MAX_STALE_MS,
        EnvRegistry.INDEX_COMMIT_TIMER_INTERVAL_MS,
        EnvRegistry.HYBRID_CANDIDATE_LIMIT_MAX,
        EnvRegistry.HYBRID_TEXT_CANDIDATE_MULTIPLIER,
        EnvRegistry.HYBRID_VECTOR_CANDIDATE_MULTIPLIER,
        EnvRegistry.HYBRID_FUSION_STRATEGY,
        EnvRegistry.HYBRID_RERANK_POOL_RECALL_COMPLETE,
        EnvRegistry.HYBRID_RERANK_POOL_TOP_N,
        EnvRegistry.HYBRID_RRF_K,
        EnvRegistry.HYBRID_VECTOR_RRF_WEIGHT,
        EnvRegistry.HYBRID_BM25_SCORE_BOOST_WEIGHT,
        EnvRegistry.HYBRID_VECTOR_LOW_SIGNAL_TOP_SCORE_THRESHOLD,
        EnvRegistry.HYBRID_BM25_LOW_SIGNAL_TOP_SCORE_THRESHOLD,
        EnvRegistry.HYBRID_BM25_LOW_SIGNAL_TOTAL_HITS_THRESHOLD,
        EnvRegistry.HYBRID_VECTOR_ONLY_CAP_LOW_SIGNAL,
        EnvRegistry.HYBRID_VECTOR_RRF_WEIGHT_LOW_SIGNAL,
        EnvRegistry.HYBRID_CC_ALPHA,
        EnvRegistry.HYBRID_CC_ZERO_EXCLUDE,
        EnvRegistry.HYBRID_LEG_ARBITRATION_ENABLED,
        EnvRegistry.HYBRID_LEG_ARBITRATION_ALPHA_DIVERGE,
        EnvRegistry.HYBRID_LEG_ARBITRATION_BM25_INCOHERENCE_MIN}) {
      result.add(key.configKey());
    }
    return Collections.unmodifiableSet(result);
  }

  private static void put(Map<String, Object> values, ConfigKey key, Object value) {
    values.put(key.configKey(), value);
  }

  private static void put(Map<String, Object> values, EnvRegistry key, Object value) {
    values.put(key.configKey(), value);
  }

  private static Map<String, Object> immutableMap(Map<String, Object> values) {
    return Collections.unmodifiableMap(new TreeMap<>(values));
  }
}
