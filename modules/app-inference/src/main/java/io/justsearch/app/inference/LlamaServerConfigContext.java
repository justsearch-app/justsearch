/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.Objects;

/** Immutable configuration captured for one logical llama-server ownership attempt. */
record LlamaServerConfigContext(InferenceConfig inference, ResolvedConfig resolved) {
  LlamaServerConfigContext {
    Objects.requireNonNull(inference, "inference");
    Objects.requireNonNull(resolved, "resolved");
  }
}
