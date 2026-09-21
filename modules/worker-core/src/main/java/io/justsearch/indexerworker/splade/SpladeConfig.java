/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for SPLADE-v3 sparse retrieval encoder.
 *
 * <p>Settings can be overridden via environment variables or system properties:
 *
 * <ul>
 *   <li>{@code JUSTSEARCH_SPLADE_ENABLED} / {@code justsearch.splade.enabled} — enable SPLADE
 *       (auto if model found)
 *   <li>{@code JUSTSEARCH_SPLADE_MODEL_PATH} / {@code justsearch.splade.model_path} — explicit
 *       model directory
 *   <li>{@code JUSTSEARCH_SPLADE_GPU_ENABLED} / {@code justsearch.splade.gpu_enabled} — enable GPU
 *       inference (default false)
 *   <li>{@code JUSTSEARCH_SPLADE_GPU_DEVICE_ID} / {@code justsearch.splade.gpu_device_id} — CUDA
 *       device (default 0)
 *   <li>{@code JUSTSEARCH_SPLADE_GPU_MEM_MB} / {@code justsearch.splade.gpu_mem_mb} — GPU arena
 *       limit in MB (default 2048; activations at batch=8 seqLen=512 need ~1 GB+)
 *   <li>{@code JUSTSEARCH_SPLADE_MAX_SEQ_LEN} / {@code justsearch.splade.max_seq_len} — max tokens
 *       per input (default 512)
 *   <li>{@code JUSTSEARCH_SPLADE_QUERY_MODE} / {@code justsearch.splade.query_mode} — query encoding
 *       mode: "onnx" (default, neural inference) or "idf" (IDF-weighted token lookup)
 *   <li>{@code JUSTSEARCH_SPLADE_ACTIVATION} / {@code justsearch.splade.activation} — post-processing
 *       activation: "log1p" (default, single-log for SPLADE-v3) or "double_log1p" (double-log for
 *       OpenSearch doc-v3 models)
 * </ul>
 */
public record SpladeConfig(
    boolean enabled,
    Path modelPath,
    int maxSequenceLength,
    boolean gpuEnabled,
    int gpuDeviceId,
    long gpuMemLimitBytes,
    String queryMode,
    String activation) {

  private static final Logger log = LoggerFactory.getLogger(SpladeConfig.class);

  public static final SpladeConfig DISABLED =
      new SpladeConfig(false, null, 512, false, 0, 0, "onnx", "log1p");

  /** Convenience: reads from {@link ConfigStore#global()}. Prefer {@link #from(ResolvedConfig)}. */
  public static SpladeConfig fromEnv() {
    return from(ConfigStore.global().get());
  }

  /** Creates configuration and discovers models from one resolved snapshot. */
  public static SpladeConfig from(ResolvedConfig config) {
    return from(config.ai().splade(), config);
  }

  /** Legacy sub-record factory; discovery retains its historical global-snapshot fallback. */
  public static SpladeConfig from(ResolvedConfig.Ai.Splade splade) {
    return from(splade, null);
  }

  private static SpladeConfig from(ResolvedConfig.Ai.Splade splade, ResolvedConfig snapshot) {
    String modelPathStr = splade.modelPath() != null ? splade.modelPath().toString() : null;
    SpladeModelDiscovery.Result discovery =
        snapshot != null
            ? SpladeModelDiscovery.resolve(snapshot, modelPathStr)
            : SpladeModelDiscovery.resolve(modelPathStr);
    Path modelPath = discovery != null ? discovery.modelDir() : null;

    Boolean explicitEnabled = splade.enabled();
    // Tempdoc 374 alpha.17 R4: align with EmbeddingConfig — explicit path also
    // enables. Pre-alpha.17 the fallback was `discovery.autoDiscovered()` only,
    // so {@code AiInstallService.applyOnnxSettings} writing an explicit
    // {@code justsearch.splade.model_path} sysprop at end of Install AI flipped
    // SPLADE from auto-on (autoDiscovered=true) to silently disabled
    // (autoDiscovered=false, explicitEnabled=null → enabled=false). Round-7
    // sandbox: every install post-Install-AI had {@code spladeEnabled: false}
    // and {@code splade=...} was missing from {@code Combined backfill} log
    // lines even though the model was on disk and the runtime worked. Embedding
    // already had this branch; SPLADE was the asymmetry.
    boolean enabled =
        explicitEnabled != null
            ? explicitEnabled
            : (discovery != null
                && (discovery.autoDiscovered() || modelPathStr != null));

    int maxSeqLen = splade.maxSeqLen();
    // Policy gate is already applied in ResolvedConfigBuilder.resolveModelGpuEnabled() (D4).
    boolean gpuEnabled = splade.gpuEnabled();
    int gpuDeviceId = splade.gpuDeviceId();
    int gpuMemMb = splade.gpuMemMb();
    String queryMode = splade.queryMode();
    String activation = splade.activation();

    SpladeConfig config =
        new SpladeConfig(
            enabled, modelPath, maxSeqLen, gpuEnabled, gpuDeviceId, gpuMemMb * 1024L * 1024,
            queryMode, activation);

    if (config.isReady()) {
      log.info(
          "SPLADE enabled: model={}, maxSeqLen={}, gpu={}, queryMode={}, activation={}",
          modelPath,
          maxSeqLen,
          gpuEnabled,
          queryMode,
          activation);
    } else if (enabled) {
      log.warn("SPLADE enabled but model not found -- SPLADE will be unavailable");
    } else {
      log.debug("SPLADE disabled (no model found or explicitly disabled)");
    }

    return config;
  }

  /** Returns true if SPLADE is enabled and model path is configured. */
  public boolean isReady() {
    return enabled && modelPath != null;
  }

  /** Returns true if query encoding should use IDF-weighted lookup instead of ONNX inference. */
  public boolean isIdfQueryMode() {
    return "idf".equalsIgnoreCase(queryMode);
  }

  /** Returns true if double-log activation should be used (for OpenSearch doc-v3 models). */
  public boolean isDoubleLogActivation() {
    return "double_log1p".equalsIgnoreCase(activation);
  }

  /** Returns true if relu-only activation should be used (no log, for multilingual-v1). */
  public boolean isReluOnlyActivation() {
    return "relu".equalsIgnoreCase(activation);
  }
}
