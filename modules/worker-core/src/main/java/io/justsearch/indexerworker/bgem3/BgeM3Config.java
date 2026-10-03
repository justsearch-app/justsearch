/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.bgem3;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for BGE-M3 unified dense+sparse encoder.
 *
 * <p>Settings can be overridden via environment variables or system properties:
 *
 * <ul>
 *   <li>{@code JUSTSEARCH_BGE_M3_ENABLED} / {@code justsearch.bgem3.enabled} — enable BGE-M3
 *       (auto if model found)
 *   <li>{@code JUSTSEARCH_BGE_M3_MODEL_PATH} / {@code justsearch.bgem3.model_path} — explicit
 *       model directory
 *   <li>{@code JUSTSEARCH_BGE_M3_GPU_ENABLED} / {@code justsearch.bgem3.gpu_enabled} — enable GPU
 *       inference (default false)
 *   <li>{@code JUSTSEARCH_BGE_M3_GPU_DEVICE_ID} / {@code justsearch.bgem3.gpu_device_id} — CUDA
 *       device (default 0)
 *   <li>{@code JUSTSEARCH_BGE_M3_GPU_MEM_MB} / {@code justsearch.bgem3.gpu_mem_mb} — GPU arena
 *       limit in MB (default 3072; FP16+FlashAttention at 8192 tokens needs ~2.6 GB)
 *   <li>{@code JUSTSEARCH_BGE_M3_MAX_SEQ_LEN} / {@code justsearch.bgem3.max_seq_len} — max tokens
 *       per input (default 8192)
 * </ul>
 */
public record BgeM3Config(
    boolean enabled,
    Path modelPath,
    int maxSequenceLength,
    boolean gpuEnabled,
    int gpuDeviceId,
    long gpuMemLimitBytes) {

  private static final Logger log = LoggerFactory.getLogger(BgeM3Config.class);

  public static final BgeM3Config DISABLED = new BgeM3Config(false, null, 8192, false, 0, 0);

  /** Convenience: reads from {@link ConfigStore#global()}. Prefer {@link #from(ResolvedConfig)}. */
  public static BgeM3Config fromEnv() {
    return from(ConfigStore.global().get());
  }

  /** Creates configuration and discovers models from one resolved snapshot. */
  public static BgeM3Config from(ResolvedConfig config) {
    return from(config.ai().bgeM3(), config);
  }

  /** Legacy sub-record factory; discovery retains its historical global-snapshot fallback. */
  public static BgeM3Config from(ResolvedConfig.Ai.BgeM3 bgeM3) {
    return from(bgeM3, null);
  }

  private static BgeM3Config from(ResolvedConfig.Ai.BgeM3 bgeM3, ResolvedConfig snapshot) {
    String modelPathStr = bgeM3.modelPath() != null ? bgeM3.modelPath().toString() : null;
    BgeM3ModelDiscovery.Result discovery =
        snapshot != null
            ? BgeM3ModelDiscovery.resolve(snapshot, modelPathStr)
            : BgeM3ModelDiscovery.resolve(modelPathStr);
    Path modelPath = discovery != null ? discovery.modelDir() : null;

    Boolean explicitEnabled = bgeM3.enabled();
    boolean enabled =
        explicitEnabled != null
            ? explicitEnabled
            : (discovery != null && discovery.autoDiscovered());

    int maxSeqLen = bgeM3.maxSeqLen();
    boolean gpuEnabled = bgeM3.gpuEnabled();
    int gpuDeviceId = bgeM3.gpuDeviceId();
    int gpuMemMb = bgeM3.gpuMemMb();

    BgeM3Config config =
        new BgeM3Config(
            enabled, modelPath, maxSeqLen, gpuEnabled, gpuDeviceId, gpuMemMb * 1024L * 1024);

    if (config.isReady()) {
      log.info(
          "BGE-M3 enabled: model={}, maxSeqLen={}, gpu={}",
          modelPath,
          maxSeqLen,
          gpuEnabled);
    } else if (enabled) {
      log.warn("BGE-M3 enabled but model not found -- BGE-M3 will be unavailable");
    } else {
      log.debug("BGE-M3 disabled (no model found or explicitly disabled)");
    }

    return config;
  }

  /** Returns true if BGE-M3 is enabled and model path is configured. */
  public boolean isReady() {
    return enabled && modelPath != null;
  }
}
