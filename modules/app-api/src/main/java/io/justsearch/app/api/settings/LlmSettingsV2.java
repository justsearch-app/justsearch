/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

/**
 * LLM/inference settings in the v2 canonical contract.
 *
 * <p>GPU layers is null for automatic selection on reads, zero for explicit CPU, and positive
 * for offload. Like the other optional fields, null or omission in a partial update preserves
 * the existing value; the settings reset operation restores automatic selection.
 */
public record LlmSettingsV2(
    String serverExecutable,
    Integer contextWindow,
    Integer maxTokens,
    Integer gpuLayers,
    String modelPath,
    String llamaLibPath,
    String gameDetection
) {
  public LlmSettingsV2 {
    if (gameDetection != null && !gameDetection.equals("auto") && !gameDetection.equals("off")) {
      throw new IllegalArgumentException("gameDetection must be auto or off");
    }
  }

  /** Existing callers omit the GPU game-detection override. */
  public LlmSettingsV2(String serverExecutable, Integer contextWindow, Integer maxTokens,
      Integer gpuLayers, String modelPath, String llamaLibPath) {
    this(serverExecutable, contextWindow, maxTokens, gpuLayers, modelPath, llamaLibPath, null);
  }

  public static LlmSettingsV2 defaults() {
    return new LlmSettingsV2(null, 4096, 1024, null, null, null);
  }
}
