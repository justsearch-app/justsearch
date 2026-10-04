/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ner;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;

/**
 * Module-local delegate for NER ONNX model discovery.
 *
 * <p>Delegates to the shared {@link
 * io.justsearch.configuration.resolved.OnnxModelDiscovery} with NER-specific parameters:
 * model name {@code "ner"}, dev subdirectory {@code "ner/distilbert-multilingual-ner-hrl"}.
 */
final class NerModelDiscovery {

  record Result(Path modelDir, boolean autoDiscovered) {}

  private NerModelDiscovery() {}

  static Result resolve(String explicitPath) {
    var shared =
        io.justsearch.configuration.resolved.OnnxModelDiscovery.resolve(
            explicitPath, "ner", "ner/distilbert-multilingual-ner-hrl");
    return shared == null ? null : new Result(shared.modelDir(), shared.autoDiscovered());
  }

  static Result resolve(ResolvedConfig config, String explicitPath) {
    var shared =
        io.justsearch.configuration.resolved.OnnxModelDiscovery.resolve(
            config, explicitPath, "ner", "ner/distilbert-multilingual-ner-hrl");
    return shared == null ? null : new Result(shared.modelDir(), shared.autoDiscovered());
  }
}
