/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;

/**
 * Module-local delegate for ONNX model discovery.
 *
 * <p>Delegates to the shared {@link
 * io.justsearch.configuration.resolved.OnnxModelDiscovery} in {@code modules/configuration} and
 * wraps the result in a module-local {@link Result} type to preserve existing call-site
 * compatibility.
 */
final class OnnxModelDiscovery {

  /** Result of model discovery, tracking where (and whether) a model was found. */
  record Result(Path modelDir, boolean autoDiscovered) {}

  private OnnxModelDiscovery() {}

  /**
   * Resolves an ONNX model directory via the shared discovery implementation.
   *
   * @param explicitPath explicit path from env var (may be null or blank)
   * @param modelName canonical model name (e.g. "reranker", "citation-scorer")
   * @param devSubdir dev-mode fallback subdirectory (e.g. "reranker/ms-marco-MiniLM-L6-v2")
   * @return discovery result, or null if not found
   */
  static Result resolve(String explicitPath, String modelName, String devSubdir) {
    var shared =
        io.justsearch.configuration.resolved.OnnxModelDiscovery.resolve(
            explicitPath, modelName, devSubdir);
    return shared == null ? null : new Result(shared.modelDir(), shared.autoDiscovered());
  }

  /** Snapshot-bound variant that does not consult ConfigStore.global(). */
  static Result resolve(
      ResolvedConfig config, String explicitPath, String modelName, String devSubdir) {
    var shared =
        io.justsearch.configuration.resolved.OnnxModelDiscovery.resolve(
            config, explicitPath, modelName, devSubdir);
    return shared == null ? null : new Result(shared.modelDir(), shared.autoDiscovered());
  }
}
