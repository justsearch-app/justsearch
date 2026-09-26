/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.bgem3;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import java.util.List;

/**
 * Module-local delegate for BGE-M3 ONNX model discovery.
 *
 * <p>Delegates to the shared {@link
 * io.justsearch.configuration.resolved.OnnxModelDiscovery} with BGE-M3-specific parameters:
 * model name {@code "bge-m3"}, dev subdirectory {@code "bge-m3/BAAI-bge-m3"}, and two
 * required files ({@code model_fp16_with_sparse.onnx}, {@code tokenizer.json}).
 */
final class BgeM3ModelDiscovery {

  /** FP16 fused model with Flash Attention support and re-attached sparse head. */
  static final String FP16_MODEL_FILE = "model_fp16_with_sparse.onnx";

  /** FP32 fallback model (CPU-only, no Flash Attention). */
  static final String FP32_MODEL_FILE = "model_quantized.onnx";

  private static final List<String> REQUIRED_FILES = List.of(FP16_MODEL_FILE, "tokenizer.json");

  record Result(Path modelDir, boolean autoDiscovered) {}

  private BgeM3ModelDiscovery() {}

  static Result resolve(String explicitPath) {
    var shared =
        io.justsearch.configuration.resolved.OnnxModelDiscovery.resolve(
            explicitPath, "bge-m3", "bge-m3/BAAI-bge-m3", REQUIRED_FILES, true);
    return shared == null ? null : new Result(shared.modelDir(), shared.autoDiscovered());
  }

  static Result resolve(ResolvedConfig config, String explicitPath) {
    var shared =
        io.justsearch.configuration.resolved.OnnxModelDiscovery.resolve(
            config,
            explicitPath,
            "bge-m3",
            "bge-m3/BAAI-bge-m3",
            REQUIRED_FILES,
            true);
    return shared == null ? null : new Result(shared.modelDir(), shared.autoDiscovered());
  }
}
