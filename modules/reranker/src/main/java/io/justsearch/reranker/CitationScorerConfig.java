/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;

/**
 * Configuration for CPU-based citation scorer (ONNX cross-encoder).
 *
 * <p>All settings can be overridden via environment variables or system properties.
 */
public record CitationScorerConfig(
    boolean enabled,
    Path modelPath,
    double threshold,
    int maxSequenceLength,
    long deadlineBudgetMs) {

  /** Default configuration with citation scoring disabled. */
  public static final CitationScorerConfig DISABLED =
      new CitationScorerConfig(false, null, 0.5, 512, 2000);

  /**
   * Creates configuration from environment variables, system properties, and auto-discovery.
   *
   * <p>Model path resolution (first match wins):
   *
   * <ol>
   *   <li>{@code JUSTSEARCH_CITATION_SCORER_MODEL_PATH} / {@code
   *       justsearch.citation.scorer.model_path} - explicit override
   *   <li>{@code <dataDir>/models/onnx/citation-scorer/} - AI Home location
   *   <li>{@code <cwd>/models/onnx/citation-scorer/} - install directory
   * </ol>
   *
   * <p>If no explicit {@code JUSTSEARCH_CITATION_SCORER_ENABLED} is set, citation scoring is
   * auto-enabled when a model is auto-discovered at a standard location (AI Home or install dir).
   * Models found via explicit env var or at dev fallback paths require explicit {@code
   * ENABLED=true}.
   *
   * <p>Other settings:
   *
   * <ul>
   *   <li>{@code JUSTSEARCH_CITATION_SCORER_THRESHOLD} / {@code
   *       justsearch.citation.scorer.threshold} - Minimum score for a match (default: 0.5)
   *   <li>{@code JUSTSEARCH_CITATION_SCORER_MAX_SEQ_LEN} / {@code
   *       justsearch.citation.scorer.max_seq_len} - Max sequence length (default: 512)
   *   <li>{@code JUSTSEARCH_CITATION_SCORER_DEADLINE_MS} / {@code
   *       justsearch.citation.scorer.deadline_ms} - Max time budget (default: 2000ms)
   * </ul>
   */
  /** Convenience: reads from {@link ConfigStore#global()}. Prefer {@link #from(ResolvedConfig)}. */
  public static CitationScorerConfig fromEnv() {
    return from(ConfigStore.global().get());
  }

  /** Creates configuration and discovers models from one resolved snapshot. */
  public static CitationScorerConfig from(ResolvedConfig config) {
    return from(config.ai().citationScorer(), config);
  }

  /** Legacy sub-record factory; discovery retains its historical global-snapshot fallback. */
  public static CitationScorerConfig from(ResolvedConfig.Ai.CitationScorer scorer) {
    return from(scorer, null);
  }

  private static CitationScorerConfig from(
      ResolvedConfig.Ai.CitationScorer scorer, ResolvedConfig config) {
    String modelPathStr = scorer.modelPath() != null ? scorer.modelPath().toString() : null;
    OnnxModelDiscovery.Result discovery =
        config != null
            ? OnnxModelDiscovery.resolve(config, modelPathStr, "citation-scorer", null)
            : OnnxModelDiscovery.resolve(modelPathStr, "citation-scorer", null);
    Path modelPath = discovery != null ? discovery.modelDir() : null;

    Boolean explicitEnabled = scorer.enabled();
    // Tempdoc 374 alpha.18 R4-sweep: align with EmbeddingConfig — explicit path
    // also enables. See {@code RerankerConfig.from} for the full root-cause
    // notes; CitationScorer had the identical asymmetry. Round-8 didn't surface
    // this because citation hit Bug I (CUDA-policy mismatch) before the enable
    // check could fire.
    boolean enabled =
        explicitEnabled != null
            ? explicitEnabled
            : (discovery != null
                && (discovery.autoDiscovered() || modelPathStr != null));

    return new CitationScorerConfig(
        enabled, modelPath, scorer.threshold(), scorer.maxSeqLen(), scorer.deadlineMs());
  }

  /** Returns true if citation scoring is enabled and model path is configured. */
  public boolean isReady() {
    return enabled && modelPath != null;
  }
}
