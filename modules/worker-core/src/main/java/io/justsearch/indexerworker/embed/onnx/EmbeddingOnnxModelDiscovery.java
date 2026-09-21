/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;

/**
 * Module-local delegate for ONNX embedding model discovery.
 *
 * <p>Delegates to the shared {@link
 * io.justsearch.configuration.resolved.OnnxModelDiscovery} with embedding-specific parameters.
 * Checks {@link EnvRegistry#EMBED_ONNX_MODEL_PATH} as an explicit override before delegating.
 */
public final class EmbeddingOnnxModelDiscovery {

  public record Result(Path modelDir, boolean autoDiscovered) {}

  private EmbeddingOnnxModelDiscovery() {}

  /** Model directory name — must match the on-disk directory under onnx/. */
  private static final String MODEL_NAME = "gte-multilingual-base";

  public static Result resolve(String explicitPath) {
    if (explicitPath == null || explicitPath.isBlank()) {
      explicitPath = EnvRegistry.EMBED_ONNX_MODEL_PATH.get().orElse(null);
    }
    var shared =
        io.justsearch.configuration.resolved.OnnxModelDiscovery.resolve(
            explicitPath, MODEL_NAME, null);
    return shared == null ? null : new Result(shared.modelDir(), shared.autoDiscovered());
  }

  /** Snapshot-bound discovery; unlike the legacy overload this never consults EnvRegistry. */
  public static Result resolve(ResolvedConfig config, String explicitPath) {
    var shared =
        io.justsearch.configuration.resolved.OnnxModelDiscovery.resolve(
            config, explicitPath, MODEL_NAME, null);
    return shared == null ? null : new Result(shared.modelDir(), shared.autoDiscovered());
  }
}
