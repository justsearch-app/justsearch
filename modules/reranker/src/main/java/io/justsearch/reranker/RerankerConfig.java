/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Configuration for cross-encoder reranker.
 *
 * <p>All settings can be overridden via environment variables or system properties.
 */
public record RerankerConfig(
    boolean enabled,
    Path modelPath,
    int topK,
    long deadlineBudgetMs,
    int minHitsThreshold,
    int maxSequenceLength,
    boolean gpuEnabled,
    int gpuDeviceId,
    long maxAvgDocLengthChars,
    // Tempdoc 643: judge-stage refinement floor — blend the CE reorder with the pre-rerank
    // (fusion/LambdaMART) order so the CE cannot regress a hit past what the blend weight
    // allows. Default off; judgeBlendAlpha is only consulted when judgeBlendEnabled is true.
    boolean judgeBlendEnabled,
    double judgeBlendAlpha,
    // Tempdoc 643 (E1/E2): compute judgeBlendAlpha per query from a runtime confidence signal
    // instead of reading the static value above. Requires judgeBlendEnabled. Default off.
    boolean judgeArbitrationEnabled,
    double judgeArbitrationAlphaDiverge,
    // Tempdoc 643 (perf-skip): skip the CE RPC entirely (not just re-weight it) when the
    // arbitration gate says fusion is decisive. Requires judgeArbitrationEnabled. Default off.
    boolean judgeArbitrationSkipEnabled) {

  /** Default configuration with reranking disabled. */
  public static final RerankerConfig DISABLED =
      new RerankerConfig(
          false, null, 20, 200, 5, 512, true, 0, 16_000, false, 0.5, false, 0.85, false);

  /**
   * Creates configuration from environment variables, system properties, and auto-discovery.
   *
   * <p>Model path resolution (first match wins):
   *
   * <ol>
   *   <li>{@code JUSTSEARCH_RERANK_MODEL_PATH} / {@code justsearch.rerank.model_path} - explicit
   *       override
   *   <li>{@code <dataDir>/models/onnx/reranker/} - AI Home location
   *   <li>{@code <cwd>/models/onnx/reranker/} - install directory
   * </ol>
   *
   * <p>If no explicit {@code JUSTSEARCH_RERANK_ENABLED} is set, reranking is auto-enabled when a
   * model is auto-discovered at a standard location (AI Home or install dir). Models found via
   * explicit env var or at dev fallback paths require explicit {@code ENABLED=true}.
   *
   * <p>Other settings:
   *
   * <ul>
   *   <li>{@code JUSTSEARCH_RERANK_TOP_K} / {@code justsearch.rerank.top_k} - Number of documents
   *       to rerank (default: 20)
   *   <li>{@code JUSTSEARCH_RERANK_DEADLINE_MS} / {@code justsearch.rerank.deadline_ms} - Max time
   *       budget (default: 200ms)
   *   <li>{@code JUSTSEARCH_RERANK_MIN_HITS} / {@code justsearch.rerank.min_hits} - Minimum hits to
   *       trigger reranking (default: 5)
   *   <li>{@code JUSTSEARCH_RERANK_MAX_SEQ_LEN} / {@code justsearch.rerank.max_seq_len} - Max
   *       sequence length (default: 512; model supports 8192 but O(n²) attention cost and GPU VRAM make that impractical)
   *   <li>{@code JUSTSEARCH_RERANK_MAX_AVG_DOC_LENGTH_CHARS} / {@code
   *       justsearch.rerank.max_avg_doc_length_chars} - Auto-disable cross-encoder when index
   *       average document length exceeds this threshold (default: 0 = gate disabled). Tempdoc 774
   *       §J.2/§K flipped the default 16000 → 0: the gate's input is a Head-side session-average
   *       cache populated only by {@code GET /api/knowledge/status} (which evals never poll), so
   *       every register baseline measured the CE-on pipeline while production sessions polling that
   *       endpoint silently lost the CE on long-doc corpora. Set to &gt;0 to restore the gate — its
   *       original rationale: MiniLM-L6-v2 truncates at 512 tokens; documents longer than ~4K tokens
   *       lose most content, causing catastrophic ranking degradation (measured: -0.606 nDCG on
   *       6K-token docs).
   *   <li>{@code JUSTSEARCH_RERANK_JUDGE_BLEND_ENABLED} / {@code
   *       justsearch.rerank.judge_blend_enabled} - Tempdoc 643: blend the cross-encoder's reorder
   *       with the pre-rerank order instead of replacing it outright (default: false)
   *   <li>{@code JUSTSEARCH_RERANK_JUDGE_BLEND_ALPHA} / {@code
   *       justsearch.rerank.judge_blend_alpha} - Weight on the fusion score in the blend, in
   *       [0,1]; 1.0 = ignore the CE, 0.0 = today's CE-only order (default: 0.5)
   *   <li>{@code JUSTSEARCH_RERANK_JUDGE_ARBITRATION_ENABLED} / {@code
   *       justsearch.rerank.judge_arbitration_enabled} - Tempdoc 643 (E1/E2): controls two
   *       independent effects. (1) Compute the judge blend alpha per query from a runtime
   *       confidence signal (CE margin + leg agreement) instead of the static {@code
   *       judge_blend_alpha} — this effect requires {@code judge_blend_enabled}. (2) Gate {@code
   *       judge_arbitration_skip_enabled} (perf-skip) — this effect does NOT require {@code
   *       judge_blend_enabled}; it decides whether the cross-encoder is called at all, independent
   *       of the blend (default: false)
   *   <li>{@code JUSTSEARCH_RERANK_JUDGE_ARBITRATION_ALPHA_DIVERGE} / {@code
   *       justsearch.rerank.judge_arbitration_alpha_diverge} - Alpha to use when the arbitration
   *       gate decides fusion is decisive and the CE is not confident (default: 0.85)
   *   <li>{@code JUSTSEARCH_RERANK_JUDGE_ARBITRATION_SKIP_ENABLED} / {@code
   *       justsearch.rerank.judge_arbitration_skip_enabled} - Tempdoc 643 (perf-skip): skip the
   *       CE RPC entirely (not just re-weight it) when the arbitration gate says fusion is
   *       decisive. Requires {@code judge_arbitration_enabled} (default: false)
   * </ul>
   */
  /** Convenience: reads from {@link ConfigStore#global()}. Prefer {@link #from(ResolvedConfig)}. */
  public static RerankerConfig fromEnv() {
    return from(ConfigStore.global().get());
  }

  /** Creates configuration and discovers models from one resolved snapshot. */
  public static RerankerConfig from(ResolvedConfig config) {
    return from(config.ai(), config);
  }

  /** Legacy sub-record factory; discovery retains its historical global-snapshot fallback. */
  public static RerankerConfig from(ResolvedConfig.Ai ai) {
    return from(ai, null);
  }

  private static RerankerConfig from(ResolvedConfig.Ai ai, ResolvedConfig config) {
    ResolvedConfig.Ai.Reranker reranker = ai.reranker();

    String modelPathStr = reranker.modelPath() != null ? reranker.modelPath().toString() : null;
    OnnxModelDiscovery.Result discovery =
        config != null
            ? OnnxModelDiscovery.resolve(config, modelPathStr, "reranker", null)
            : OnnxModelDiscovery.resolve(modelPathStr, "reranker", null);
    Path modelPath = discovery != null ? discovery.modelDir() : null;

    Boolean explicitEnabled = reranker.enabled();
    // Tempdoc 374 alpha.18 R4-sweep: align with EmbeddingConfig — explicit path
    // also enables. Pre-alpha.18 the fallback was {@code discovery.autoDiscovered()}
    // only, so {@code AiInstallService.applyOnnxSettings} writing an explicit
    // {@code justsearch.rerank.model_path} sysprop at end of Install AI silently
    // disabled reranker. Round-8 didn't surface this because reranker is lazy-init
    // (a HYBRID query is needed to trigger it). Same fix template as the alpha.17
    // R4 fix on SpladeConfig and NerConfig.
    boolean enabled =
        explicitEnabled != null
            ? explicitEnabled
            : (discovery != null
                && (discovery.autoDiscovered() || modelPathStr != null));

    return new RerankerConfig(
        enabled, modelPath, reranker.topK(), reranker.deadlineMs(),
        reranker.minHits(), reranker.maxSeqLen(),
        reranker.gpuEnabled(), reranker.gpuDeviceId(),
        reranker.maxAvgDocLengthChars(),
        reranker.judgeBlendEnabled(), reranker.judgeBlendAlpha(),
        reranker.judgeArbitrationEnabled(), reranker.judgeArbitrationAlphaDiverge(),
        reranker.judgeArbitrationSkipEnabled());
  }

  /** Returns true if reranking is enabled and model path is configured. */
  public boolean isReady() {
    return enabled && modelPath != null;
  }

  /** Resolves the reranker model path from the resolved config snapshot. */
  private static String resolveRerankerModelPath(ResolvedConfig.Ai ai) {
    return ai.reranker().modelPath() != null ? ai.reranker().modelPath().toString() : null;
  }

  // ==================== Chunk Reranking (Phase 5) ====================

  /**
   * Order of reranking relative to diversification in the RAG retrieval pipeline.
   *
   * <p>Phase 5 Gap 5: The optimal order depends on GPU availability:
   * <ul>
   *   <li>GPU available: Rerank first (full candidate set) -> Diversify (semantic scores)
   *   <li>CPU only: Diversify first (bounds work) -> Rerank (smaller set fits deadline)
   * </ul>
   */
  public enum RerankOrder {
    /** Automatically choose based on GPU availability (recommended). */
    AUTO,
    /** Always rerank before diversification (GPU-style, higher quality). */
    BEFORE_DIVERSIFY,
    /** Always rerank after diversification (CPU-style, bounded latency). */
    AFTER_DIVERSIFY
  }

  /**
   * Configuration for RAG chunk reranking (Phase 5).
   *
   * <p>Separate from search reranking to allow different settings:
   * - Smaller topK (chunks are smaller than docs)
   * - Tighter deadline (RAG is latency-sensitive)
   * - Independent enable flag
   * - Adaptive rerank order based on GPU availability
   */
  public record ChunkRerankerConfig(
      boolean enabled,
      Path modelPath,
      int topK,
      int maxGpuCandidates,
      long deadlineBudgetMs,
      int minHitsThreshold,
      int maxSequenceLength,
      boolean gpuEnabled,
      int gpuDeviceId,
      RerankOrder order) {

    /** Default configuration with chunk reranking disabled due to CPU latency. */
    public static final ChunkRerankerConfig DISABLED =
        new ChunkRerankerConfig(false, null, 10, 50, 150, 3, 512, false, 0, RerankOrder.AUTO);

    /**
     * Creates chunk reranker configuration from environment variables.
     *
     * <p>Supported settings:
     * <ul>
     *   <li>{@code JUSTSEARCH_RERANK_CHUNKS_ENABLED} - Enable chunk reranking (default: false)
     *   <li>{@code JUSTSEARCH_RERANK_CHUNKS_MODEL_PATH} - Path to model (falls back to search reranker model)
     *   <li>{@code JUSTSEARCH_RERANK_CHUNKS_TOP_K} - Chunks to rerank on CPU (default: 10)
     *   <li>{@code JUSTSEARCH_RERANK_CHUNKS_MAX_GPU_CANDIDATES} - Max candidates when GPU available (default: 50)
     *   <li>{@code JUSTSEARCH_RERANK_CHUNKS_DEADLINE_MS} - Time budget (default: 150ms)
     *   <li>{@code JUSTSEARCH_RERANK_CHUNKS_MIN_HITS} - Min chunks to trigger (default: 3)
     *   <li>{@code JUSTSEARCH_RERANK_CHUNKS_ORDER} - Order relative to diversification: auto, before_diversify, after_diversify (default: auto)
     * </ul>
     */
    /**
     * Creates chunk reranker configuration from the resolved config snapshot.
     *
     * <p>Requires {@link ConfigStore} to be initialized. See tempdoc 347.
     */
    /** Convenience: reads from {@link ConfigStore#global()}. Prefer {@link #from} in new code. */
    public static ChunkRerankerConfig fromEnv() {
      return from(ConfigStore.global().get().ai());
    }

    /** Creates chunk reranker configuration and discovers models from one resolved snapshot. */
    public static ChunkRerankerConfig from(ResolvedConfig config) {
      return from(config.ai(), config);
    }

    /** Creates chunk reranker configuration from a resolved AI sub-record. */
    public static ChunkRerankerConfig from(ResolvedConfig.Ai ai) {
      return from(ai, null);
    }

    private static ChunkRerankerConfig from(ResolvedConfig.Ai ai, ResolvedConfig config) {
      ResolvedConfig.Ai.Reranker.ChunkReranker chunks = ai.reranker().chunks();

      // Fall back to search reranker model path if not specified
      String modelPathStr =
          chunks.modelPath() != null ? chunks.modelPath().toString() : null;
      if (modelPathStr == null || modelPathStr.isBlank()) {
        modelPathStr = resolveRerankerModelPath(ai);
      }
      OnnxModelDiscovery.Result discovery =
          config != null
              ? OnnxModelDiscovery.resolve(
                  config, modelPathStr, "reranker", "reranker/ms-marco-MiniLM-L6-v2")
              : OnnxModelDiscovery.resolve(
                  modelPathStr, "reranker", "reranker/ms-marco-MiniLM-L6-v2");
      Path modelPath = discovery != null ? discovery.modelDir() : null;

      Boolean explicitEnabled = chunks.enabled();
      boolean enabled =
          explicitEnabled != null
              ? explicitEnabled
              : (discovery != null && discovery.autoDiscovered());

      String orderStr = chunks.order();
      RerankOrder order = switch (orderStr.toLowerCase(Locale.ROOT)) {
        case "before_diversify" -> RerankOrder.BEFORE_DIVERSIFY;
        case "after_diversify" -> RerankOrder.AFTER_DIVERSIFY;
        default -> RerankOrder.AUTO;
      };

      return new ChunkRerankerConfig(
          enabled, modelPath, chunks.topK(), chunks.maxGpuCandidates(),
          chunks.deadlineMs(), chunks.minHits(), chunks.maxSeqLen(),
          chunks.gpuEnabled(), chunks.gpuDeviceId(), order);
    }

    /** Returns true if chunk reranking is enabled and model path is configured. */
    public boolean isReady() {
      return enabled && modelPath != null;
    }
  }
}
