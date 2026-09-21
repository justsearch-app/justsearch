/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.configuration.AppliedConfigurationVersion;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.bgem3.BgeM3Config;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import io.justsearch.indexerworker.ner.NerConfig;
import io.justsearch.indexerworker.splade.SpladeConfig;
import io.justsearch.reranker.CitationScorerConfig;
import io.justsearch.reranker.RerankerConfig;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The encoder owner's typed applied-configuration projection.
 *
 * <p>The role configs are created once from the supplied {@link ResolvedConfig} and are then used
 * both to compose the surface and to compute its applied version. The raw projection is confined to
 * the digest call; callers can observe only the dependency names and resulting digest.
 */
final class EncoderConfigurationProjection {

  private static final Set<String> DEPENDENCIES =
      Set.of(
          key(EnvRegistry.AI_EMBED_ENABLED),
          key(EnvRegistry.EMBED_ONNX_MODEL_PATH),
          key(EnvRegistry.EMBED_BACKEND),
          key(EnvRegistry.EMBED_GPU_ENABLED),
          key(EnvRegistry.EMBED_GPU_DEVICE_ID),
          key(EnvRegistry.EMBED_GPU_MEM_MB),
          key(EnvRegistry.EMBED_CONTEXT_LENGTH),
          key(EnvRegistry.EMBED_LATE_CHUNKING_ENABLED),
          key(EnvRegistry.EMBED_LATE_CHUNKING_CONTEXT_LENGTH),
          key(EnvRegistry.GPU_ENABLED),
          key(EnvRegistry.POLICY_GPU_ACCELERATION_ENABLED),
          key(EnvRegistry.SPARSE_MODEL),
          key(EnvRegistry.SPLADE_ENABLED),
          key(EnvRegistry.SPLADE_MODEL_PATH),
          key(EnvRegistry.SPLADE_MAX_SEQ_LEN),
          key(EnvRegistry.SPLADE_GPU_ENABLED),
          key(EnvRegistry.SPLADE_GPU_DEVICE_ID),
          key(EnvRegistry.SPLADE_GPU_MEM_MB),
          key(EnvRegistry.SPLADE_QUERY_MODE),
          key(EnvRegistry.SPLADE_ACTIVATION),
          key(EnvRegistry.NER_ENABLED),
          key(EnvRegistry.NER_MODEL_PATH),
          key(EnvRegistry.NER_MAX_SEQ_LEN),
          key(EnvRegistry.NER_CONFIDENCE_THRESHOLD),
          key(EnvRegistry.NER_GPU_ENABLED),
          key(EnvRegistry.NER_GPU_DEVICE_ID),
          key(EnvRegistry.NER_GPU_MEM_MB),
          key(EnvRegistry.BGE_M3_ENABLED),
          key(EnvRegistry.BGE_M3_MODEL_PATH),
          key(EnvRegistry.BGE_M3_MAX_SEQ_LEN),
          key(EnvRegistry.BGE_M3_GPU_ENABLED),
          key(EnvRegistry.BGE_M3_GPU_DEVICE_ID),
          key(EnvRegistry.BGE_M3_GPU_MEM_MB),
          key(EnvRegistry.RERANK_ENABLED),
          key(EnvRegistry.RERANK_MODEL_PATH),
          key(EnvRegistry.RERANK_GPU_ENABLED),
          key(EnvRegistry.RERANK_GPU_DEVICE_ID),
          key(EnvRegistry.RERANK_GPU_MEM_MB),
          key(EnvRegistry.RERANK_TOP_K),
          key(EnvRegistry.RERANK_DEADLINE_MS),
          key(EnvRegistry.RERANK_MIN_HITS),
          key(EnvRegistry.RERANK_MAX_SEQ_LEN),
          key(EnvRegistry.RERANK_MAX_AVG_DOC_LENGTH_CHARS),
          key(EnvRegistry.RERANK_JUDGE_BLEND_ENABLED),
          key(EnvRegistry.RERANK_JUDGE_BLEND_ALPHA),
          key(EnvRegistry.RERANK_JUDGE_ARBITRATION_ENABLED),
          key(EnvRegistry.RERANK_JUDGE_ARBITRATION_ALPHA_DIVERGE),
          key(EnvRegistry.RERANK_JUDGE_ARBITRATION_SKIP_ENABLED),
          key(EnvRegistry.CITATION_SCORER_ENABLED),
          key(EnvRegistry.CITATION_SCORER_MODEL_PATH),
          key(EnvRegistry.CITATION_SCORER_THRESHOLD),
          key(EnvRegistry.CITATION_SCORER_MAX_SEQ_LEN),
          key(EnvRegistry.CITATION_SCORER_DEADLINE_MS),
          key(EnvRegistry.CAPABILITY_CONTRACT_STRICT),
          key(EnvRegistry.ORT_PROFILING_DIR),
          key(EnvRegistry.ORT_VERBOSE_LOGGING),
          key(EnvRegistry.ORT_INTRA_OP_THREADS));

  private final ResolvedConfig cfg;
  private final EmbeddingConfig embedding;
  private final SpladeConfig splade;
  private final NerConfig ner;
  private final BgeM3Config bgeM3;
  private final RerankerConfig reranker;
  private final CitationScorerConfig citation;

  private EncoderConfigurationProjection(
      ResolvedConfig cfg,
      EmbeddingConfig embedding,
      SpladeConfig splade,
      NerConfig ner,
      BgeM3Config bgeM3,
      RerankerConfig reranker,
      CitationScorerConfig citation) {
    this.cfg = cfg;
    this.embedding = embedding;
    this.splade = splade;
    this.ner = ner;
    this.bgeM3 = bgeM3;
    this.reranker = reranker;
    this.citation = citation;
  }

  static EncoderConfigurationProjection from(ResolvedConfig cfg) {
    return new EncoderConfigurationProjection(
        cfg,
        EmbeddingConfig.from(cfg),
        SpladeConfig.from(cfg),
        NerConfig.from(cfg),
        BgeM3Config.from(cfg),
        RerankerConfig.from(cfg),
        CitationScorerConfig.from(cfg));
  }

  static Set<String> dependencies() {
    return DEPENDENCIES;
  }

  ResolvedConfig config() {
    return cfg;
  }

  EmbeddingConfig embedding() {
    return embedding;
  }

  SpladeConfig splade() {
    return splade;
  }

  NerConfig ner() {
    return ner;
  }

  BgeM3Config bgeM3() {
    return bgeM3;
  }

  RerankerConfig reranker() {
    return reranker;
  }

  CitationScorerConfig citation() {
    return citation;
  }

  String digest() {
    Map<String, Object> values = new LinkedHashMap<>();

    put(values, EnvRegistry.AI_EMBED_ENABLED, embedding.enabled());
    put(values, EnvRegistry.EMBED_ONNX_MODEL_PATH, path(embedding.modelPath()));
    put(values, EnvRegistry.EMBED_BACKEND, embedding.backend());
    put(values, EnvRegistry.EMBED_GPU_ENABLED, embedding.gpuEnabled());
    put(values, EnvRegistry.EMBED_GPU_DEVICE_ID, embedding.gpuDeviceId());
    put(values, EnvRegistry.EMBED_GPU_MEM_MB, embedding.gpuMemLimitBytes());
    put(values, EnvRegistry.EMBED_CONTEXT_LENGTH, embedding.contextLength());
    put(values, EnvRegistry.EMBED_LATE_CHUNKING_ENABLED, embedding.lateChunkingEnabled());
    put(
        values,
        EnvRegistry.EMBED_LATE_CHUNKING_CONTEXT_LENGTH,
        embedding.lateChunkingContextLength());

    put(values, EnvRegistry.GPU_ENABLED, cfg.ai().masterGpuEnabled());
    put(
        values,
        EnvRegistry.POLICY_GPU_ACCELERATION_ENABLED,
        cfg.ai().gpuAccelerationAllowed());
    put(values, EnvRegistry.SPARSE_MODEL, cfg.ai().sparseModel());
    put(values, EnvRegistry.SPLADE_ENABLED, splade.enabled());
    put(values, EnvRegistry.SPLADE_MODEL_PATH, path(splade.modelPath()));
    put(values, EnvRegistry.SPLADE_MAX_SEQ_LEN, splade.maxSequenceLength());
    put(values, EnvRegistry.SPLADE_GPU_ENABLED, splade.gpuEnabled());
    put(values, EnvRegistry.SPLADE_GPU_DEVICE_ID, splade.gpuDeviceId());
    put(values, EnvRegistry.SPLADE_GPU_MEM_MB, splade.gpuMemLimitBytes());
    put(values, EnvRegistry.SPLADE_QUERY_MODE, splade.queryMode());
    put(values, EnvRegistry.SPLADE_ACTIVATION, splade.activation());

    put(values, EnvRegistry.NER_ENABLED, ner.enabled());
    put(values, EnvRegistry.NER_MODEL_PATH, path(ner.modelPath()));
    put(values, EnvRegistry.NER_MAX_SEQ_LEN, ner.maxSequenceLength());
    put(values, EnvRegistry.NER_CONFIDENCE_THRESHOLD, ner.confidenceThreshold());
    put(values, EnvRegistry.NER_GPU_ENABLED, ner.gpuEnabled());
    put(values, EnvRegistry.NER_GPU_DEVICE_ID, ner.gpuDeviceId());
    put(values, EnvRegistry.NER_GPU_MEM_MB, ner.gpuMemLimitBytes());

    put(values, EnvRegistry.BGE_M3_ENABLED, bgeM3.enabled());
    put(values, EnvRegistry.BGE_M3_MODEL_PATH, path(bgeM3.modelPath()));
    put(values, EnvRegistry.BGE_M3_MAX_SEQ_LEN, bgeM3.maxSequenceLength());
    put(values, EnvRegistry.BGE_M3_GPU_ENABLED, bgeM3.gpuEnabled());
    put(values, EnvRegistry.BGE_M3_GPU_DEVICE_ID, bgeM3.gpuDeviceId());
    put(values, EnvRegistry.BGE_M3_GPU_MEM_MB, bgeM3.gpuMemLimitBytes());

    put(values, EnvRegistry.RERANK_ENABLED, reranker.enabled());
    put(values, EnvRegistry.RERANK_MODEL_PATH, path(reranker.modelPath()));
    put(values, EnvRegistry.RERANK_GPU_ENABLED, reranker.gpuEnabled());
    put(values, EnvRegistry.RERANK_GPU_DEVICE_ID, reranker.gpuDeviceId());
    put(values, EnvRegistry.RERANK_GPU_MEM_MB, cfg.ai().reranker().gpuMemMb());
    put(values, EnvRegistry.RERANK_TOP_K, reranker.topK());
    put(values, EnvRegistry.RERANK_DEADLINE_MS, reranker.deadlineBudgetMs());
    put(values, EnvRegistry.RERANK_MIN_HITS, reranker.minHitsThreshold());
    put(values, EnvRegistry.RERANK_MAX_SEQ_LEN, reranker.maxSequenceLength());
    put(values, EnvRegistry.RERANK_MAX_AVG_DOC_LENGTH_CHARS, reranker.maxAvgDocLengthChars());
    put(values, EnvRegistry.RERANK_JUDGE_BLEND_ENABLED, reranker.judgeBlendEnabled());
    put(values, EnvRegistry.RERANK_JUDGE_BLEND_ALPHA, reranker.judgeBlendAlpha());
    put(
        values,
        EnvRegistry.RERANK_JUDGE_ARBITRATION_ENABLED,
        reranker.judgeArbitrationEnabled());
    put(
        values,
        EnvRegistry.RERANK_JUDGE_ARBITRATION_ALPHA_DIVERGE,
        reranker.judgeArbitrationAlphaDiverge());
    put(
        values,
        EnvRegistry.RERANK_JUDGE_ARBITRATION_SKIP_ENABLED,
        reranker.judgeArbitrationSkipEnabled());

    put(values, EnvRegistry.CITATION_SCORER_ENABLED, citation.enabled());
    put(values, EnvRegistry.CITATION_SCORER_MODEL_PATH, path(citation.modelPath()));
    put(values, EnvRegistry.CITATION_SCORER_THRESHOLD, citation.threshold());
    put(values, EnvRegistry.CITATION_SCORER_MAX_SEQ_LEN, citation.maxSequenceLength());
    put(values, EnvRegistry.CITATION_SCORER_DEADLINE_MS, citation.deadlineBudgetMs());

    put(values, EnvRegistry.CAPABILITY_CONTRACT_STRICT, cfg.ai().capabilityContractStrict());
    ResolvedConfig.Ai.Profiling profiling = cfg.ai().profiling();
    put(
        values,
        EnvRegistry.ORT_PROFILING_DIR,
        profiling != null ? path(profiling.ortProfilingDir()) : null);
    put(
        values,
        EnvRegistry.ORT_VERBOSE_LOGGING,
        profiling != null && profiling.verboseLogging());
    put(
        values,
        EnvRegistry.ORT_INTRA_OP_THREADS,
        profiling != null ? profiling.intraOpThreads() : null);

    return AppliedConfigurationVersion.digest(DEPENDENCIES, values);
  }

  private static String key(EnvRegistry key) {
    return key.configKey();
  }

  private static void put(Map<String, Object> values, EnvRegistry key, Object value) {
    values.put(key(key), value);
  }

  private static String path(Path path) {
    return path != null ? path.normalize().toString() : null;
  }
}
