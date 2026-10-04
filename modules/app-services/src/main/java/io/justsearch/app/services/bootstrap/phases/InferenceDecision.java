/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.Objects;

/** The shared inference-existence decision for capability, manager and launch identity. */
public final class InferenceDecision {
  private InferenceDecision() {}

  /**
   * Uses the composition snapshot for accepted settings. Lite mode remains a process launch flag;
   * callers retain this decision through construction rather than sampling configuration again.
   */
  public static boolean decideInferenceConfigured(ResolvedConfig snapshot) {
    return decideInferenceConfigured(snapshot, EnvRegistry.LITE_MODE.getBoolean(false));
  }

  /** The owner may retain the sampled launch gate for its applied composition identity. */
  public static boolean decideInferenceConfigured(ResolvedConfig snapshot, boolean liteMode) {
    Objects.requireNonNull(snapshot, "snapshot");
    return !snapshot.ai().disabled()
        && snapshot.ai().llmEnabled()
        && !liteMode;
  }
}
