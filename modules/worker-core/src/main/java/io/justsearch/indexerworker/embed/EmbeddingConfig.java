/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import io.justsearch.configuration.resolved.ConfigResolution;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.embed.onnx.EmbeddingOnnxModelDiscovery;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for ONNX embedding encoder.
 *
 * <p>Follows the same pattern as {@link io.justsearch.indexerworker.splade.SpladeConfig} and {@link
 * io.justsearch.reranker.RerankerConfig}: a single {@link #fromEnv()} factory consolidates all
 * config reads from {@link ConfigStore} (first) and {@link EnvRegistry} (fallback).
 *
 * <p>Settings can be overridden via environment variables or system properties:
 *
 * <ul>
 *   <li>{@code JUSTSEARCH_AI_EMBED_ENABLED} / {@code justsearch.ai.embed.enabled} — enable
 *       embedding (auto if model found)
 *   <li>{@code JUSTSEARCH_EMBED_BACKEND} / {@code justsearch.embed.backend} — backend: "auto"
 *       (default) or "onnx"
 *   <li>{@code JUSTSEARCH_EMBED_GPU_ENABLED} / {@code justsearch.embed.gpu.enabled} — enable GPU
 *       inference (default false)
 *   <li>{@code JUSTSEARCH_EMBED_GPU_DEVICE_ID} / {@code justsearch.embed.gpu.device_id} — CUDA
 *       device (default 0)
 *   <li>{@code JUSTSEARCH_EMBED_GPU_MEM_MB} / {@code justsearch.embed.gpu_mem_mb} — GPU arena
 *       limit in MB (default 2048)
 *   <li>{@code JUSTSEARCH_EMBED_CONTEXT_LENGTH} / {@code justsearch.embed.context_length} — max
 *       context length in tokens (default 2048)
 *   <li>{@code JUSTSEARCH_EMBED_LATE_CHUNKING_CONTEXT_LENGTH} / {@code
 *       justsearch.embed.late_chunking_context_length} — single-pass whole-doc VECTOR limit for
 *       the late-chunking path (tempdoc 691 Phase 2; default 8192, clamped to [contextLength,
 *       8192])
 * </ul>
 */
public record EmbeddingConfig(
    boolean enabled,
    Path modelPath,
    String backend,
    boolean gpuEnabled,
    int gpuDeviceId,
    long gpuMemLimitBytes,
    int contextLength,
    // Tempdoc 691 Phase 1: late-chunking embed pass (single forward pass for a chunked parent +
    // its chunk docs) — default off.
    boolean lateChunkingEnabled,
    // Tempdoc 691 Phase 2: single-pass whole-doc VECTOR limit for the late-chunking path. 0 (as
    // in DISABLED) is a defensive sentinel — callers must fall back to contextLength/maxSeqLen
    // when this is <= 0.
    int lateChunkingContextLength) {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

  public static final EmbeddingConfig DISABLED =
      new EmbeddingConfig(false, null, "auto", false, 0, 0, 2048, false, 0);

  /** Convenience: reads from {@link ConfigStore#global()}. Prefer {@link #from(ResolvedConfig)}. */
  public static EmbeddingConfig fromEnv() {
    return from(ConfigStore.global().get(), true);
  }

  /** Creates configuration from a resolved config snapshot and auto-discovery. */
  public static EmbeddingConfig from(ResolvedConfig config) {
    return from(config, false);
  }

  private static EmbeddingConfig from(ResolvedConfig config, boolean legacyEnvFallback) {
    ResolvedConfig.Ai.Embedding embed = config.ai().embedding();

    // Model discovery: prefer the snapshot's resolved model path over re-walking the filesystem.
    String snapshotModelPath = null;
    ConfigResolution resolution = config.resolution("justsearch.embed.onnx.model_path");
    if (resolution != null
        && resolution.value() != null
        && !resolution.value().isBlank()) {
      snapshotModelPath = resolution.value();
    }
    EmbeddingOnnxModelDiscovery.Result discovery =
        legacyEnvFallback
            ? EmbeddingOnnxModelDiscovery.resolve(snapshotModelPath)
            : EmbeddingOnnxModelDiscovery.resolve(config, snapshotModelPath);
    Path modelPath = discovery != null ? discovery.modelDir() : null;

    // Enabled (nullable three-state: true/false/null=auto)
    Boolean explicitEnabled = embed.enabled();
    // Enable embedding if: explicitly enabled, OR auto-discovered at a standard location,
    // OR an explicit model path was set (user intent is clear — env var or snapshot).
    boolean enabled =
        explicitEnabled != null
            ? explicitEnabled
            : (discovery != null
                && (discovery.autoDiscovered() || snapshotModelPath != null));

    // Backend
    String backend = embed.backend();
    if (backend == null || backend.isBlank()) {
      backend = "auto";
    }

    // GPU: ResolvedConfigBuilder.resolveEmbedGpuEnabled already handles the 4-priority
    // resolution (per-model → legacy layers → master switch → false) + policy gate.
    boolean gpuEnabled = embed.gpuEnabled();
    int gpuDeviceId = embed.gpuDeviceId();
    int gpuMemMb = embed.gpuMemMb();
    int contextLength = embed.contextLength();
    boolean lateChunkingEnabled = embed.lateChunkingEnabled();
    int lateChunkingContextLength = embed.lateChunkingContextLength();

    EmbeddingConfig embeddingConfig =
        new EmbeddingConfig(
            enabled, modelPath, backend, gpuEnabled, gpuDeviceId, gpuMemMb * 1024L * 1024,
            contextLength, lateChunkingEnabled, lateChunkingContextLength);

    if (embeddingConfig.isReady()) {
      log.info(
          "Embedding enabled: model={}, backend={}, gpu={}, contextLength={}",
          modelPath,
          backend,
          gpuEnabled,
          contextLength);
    } else if (enabled) {
      log.warn("Embedding enabled but model not found -- embedding will be unavailable");
    } else {
      log.debug("Embedding disabled (no model found or explicitly disabled)");
    }

    return embeddingConfig;
  }

  /** Returns true if embedding is enabled and model path is configured. */
  public boolean isReady() {
    return enabled && modelPath != null;
  }

}
