/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ner;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for BERT NER entity extraction.
 *
 * <p>Settings can be overridden via environment variables or system properties:
 *
 * <ul>
 *   <li>{@code JUSTSEARCH_NER_ENABLED} / {@code justsearch.ner.enabled} — enable NER (auto if model
 *       found)
 *   <li>{@code JUSTSEARCH_NER_MODEL_PATH} / {@code justsearch.ner.model_path} — explicit model
 *       directory
 *   <li>{@code JUSTSEARCH_NER_MAX_SEQ_LEN} / {@code justsearch.ner.max_seq_len} — max tokens per
 *       chunk (default 512)
 *   <li>{@code JUSTSEARCH_NER_CONFIDENCE_THRESHOLD} / {@code justsearch.ner.confidence_threshold} —
 *       minimum entity confidence (default 0.5)
 * </ul>
 */
public record NerConfig(
    boolean enabled,
    Path modelPath,
    int maxSequenceLength,
    float confidenceThreshold,
    boolean gpuEnabled,
    int gpuDeviceId,
    long gpuMemLimitBytes) {

  private static final Logger log = LoggerFactory.getLogger(NerConfig.class);

  public static final NerConfig DISABLED = new NerConfig(false, null, 512, 0.5f, false, 0, 0);

  /** Convenience: reads from {@link ConfigStore#global()}. Prefer {@link #from(ResolvedConfig)}. */
  public static NerConfig fromEnv() {
    return from(ConfigStore.global().get());
  }

  /** Creates configuration and discovers models from one resolved snapshot. */
  public static NerConfig from(ResolvedConfig config) {
    return from(config.ai().ner(), config);
  }

  /** Legacy sub-record factory; discovery retains its historical global-snapshot fallback. */
  public static NerConfig from(ResolvedConfig.Ai.Ner ner) {
    return from(ner, null);
  }

  private static NerConfig from(ResolvedConfig.Ai.Ner ner, ResolvedConfig snapshot) {
    String modelPathStr = ner.modelPath() != null ? ner.modelPath().toString() : null;
    NerModelDiscovery.Result discovery =
        snapshot != null
            ? NerModelDiscovery.resolve(snapshot, modelPathStr)
            : NerModelDiscovery.resolve(modelPathStr);
    Path modelPath = discovery != null ? discovery.modelDir() : null;

    Boolean explicitEnabled = ner.enabled();
    // Tempdoc 374 alpha.17 R4: align with EmbeddingConfig — explicit path also
    // enables. See {@code SpladeConfig.from} for the full root-cause notes;
    // NER had the identical asymmetry. Round-7 sandbox: NER backfill never
    // ran on any of 5184 SciFact docs post-Install-AI even though the model
    // was present and {@code justsearch.ner.gpu_enabled=true} resolved at the
    // worker.
    boolean enabled =
        explicitEnabled != null
            ? explicitEnabled
            : (discovery != null
                && (discovery.autoDiscovered() || modelPathStr != null));

    int maxSeqLen = ner.maxSeqLen();
    float confidenceThreshold = (float) ner.confidenceThreshold();
    boolean gpuEnabled = ner.gpuEnabled();
    int gpuDeviceId = ner.gpuDeviceId();
    int gpuMemMb = ner.gpuMemMb();

    NerConfig config =
        new NerConfig(
            enabled, modelPath, maxSeqLen, confidenceThreshold,
            gpuEnabled, gpuDeviceId, gpuMemMb * 1024L * 1024);

    if (config.isReady()) {
      log.info(
          "NER enabled: model={}, maxSeqLen={}, confidenceThreshold={}, gpu={}",
          modelPath, maxSeqLen, confidenceThreshold, gpuEnabled);
    } else if (enabled) {
      log.warn("NER enabled but model not found — NER will be unavailable");
    } else {
      log.debug("NER disabled (no model found or explicitly disabled)");
    }

    return config;
  }

  /** Returns true if NER is enabled and model path is configured. */
  public boolean isReady() {
    return enabled && modelPath != null;
  }
}
