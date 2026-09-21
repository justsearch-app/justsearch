/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import java.util.List;

/**
 * Module-local delegate for SPLADE ONNX model discovery.
 *
 * <p>Delegates to the shared {@link
 * io.justsearch.configuration.resolved.OnnxModelDiscovery} with SPLADE-specific parameters:
 * model name {@code "splade"}, dev subdirectory {@code "splade/naver-splade-v3"}, and three
 * required files ({@code model.onnx}, {@code tokenizer.json}, {@code vocab.txt}).
 */
final class SpladeModelDiscovery {

  private static final List<String> REQUIRED_FILES =
      List.of("model.onnx", "tokenizer.json", "vocab.txt");

  record Result(Path modelDir, boolean autoDiscovered) {}

  private SpladeModelDiscovery() {}

  static Result resolve(String explicitPath) {
    var shared =
        io.justsearch.configuration.resolved.OnnxModelDiscovery.resolve(
            explicitPath, "splade", "splade/naver-splade-v3", REQUIRED_FILES, true);
    return shared == null ? null : new Result(shared.modelDir(), shared.autoDiscovered());
  }

  static Result resolve(ResolvedConfig config, String explicitPath) {
    var shared =
        io.justsearch.configuration.resolved.OnnxModelDiscovery.resolve(
            config,
            explicitPath,
            "splade",
            "splade/naver-splade-v3",
            REQUIRED_FILES,
            true);
    return shared == null ? null : new Result(shared.modelDir(), shared.autoDiscovered());
  }
}
