/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import java.util.List;

/**
 * Public wrapper around package-private {@link OnnxModelDiscovery} for Worker-side status reporting.
 *
 * <p>Called at Worker startup to discover ONNX models at standard filesystem locations and
 * explicit-path overrides from resolved config. Results are cached and reported via the
 * {@code HealthCheckResponse.onnx_models} gRPC field so the Head process can display feature
 * status without independently re-walking the filesystem.
 */
public final class WorkerModelDiscovery {

  /** Result of discovering one ONNX model. */
  public record DiscoveredModel(
      String modelName, boolean found, String path, boolean autoDiscovered) {}

  private WorkerModelDiscovery() {}

  /**
   * Discovers all known ONNX models (reranker, citation-scorer) using both explicit paths
   * from resolved config and auto-discovery at standard locations.
   *
   * @return immutable list of discovery results, one per model
   */
  public static List<DiscoveredModel> discoverAll() {
    return discoverAll(ConfigStore.global().get());
  }

  /** Discovers against the same immutable snapshot as the services reported by health. */
  public static List<DiscoveredModel> discoverAll(ResolvedConfig config) {
    ResolvedConfig.Ai ai = config.ai();
    String rerankerPath = pathToString(ai.reranker().modelPath());
    String citationPath = pathToString(ai.citationScorer().modelPath());
    return List.of(
        discover(config, "reranker", rerankerPath),
        discover(config, "citation-scorer", citationPath));
  }

  private static DiscoveredModel discover(ResolvedConfig config, String modelName, String explicitPath) {
    OnnxModelDiscovery.Result result = OnnxModelDiscovery.resolve(config, explicitPath, modelName, null);
    if (result == null) {
      return new DiscoveredModel(modelName, false, null, false);
    }
    return new DiscoveredModel(
        modelName, true, result.modelDir().toString(), result.autoDiscovered());
  }

  private static String pathToString(Path path) {
    return path != null ? path.toString() : null;
  }
}
