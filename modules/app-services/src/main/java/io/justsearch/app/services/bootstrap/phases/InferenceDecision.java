/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.telemetry.Telemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §10 final-push: extracted from {@code HeadAssembly}.
 *
 * <p>Two pure-ish functions for inference lifecycle decisions:
 * <ul>
 *   <li>{@link #decideInferenceConfigured} — env / sysprop predicate, no construction.
 *       Returns true iff inference WILL be configured by {@link #createInferenceManager}.
 *       F3 reorder: this runs at Phase 2 (capability resolution) before service construction.
 *   <li>{@link #createInferenceManager} — actually constructs the {@link
 *       InferenceLifecycleManager} when AI is enabled. Returns null in lite-mode / ai-disabled
 *       configurations.
 * </ul>
 */
public final class InferenceDecision {

  private static final Logger log = LoggerFactory.getLogger(InferenceDecision.class);

  private InferenceDecision() {}

  /** Inspects env / sysprops only; no service construction. */
  public static boolean decideInferenceConfigured() {
    boolean aiDisabled =
        Boolean.parseBoolean(
            System.getProperty(
                "justsearch.ai.disabled",
                System.getenv().getOrDefault("JUSTSEARCH_AI_DISABLED", "false")));
    boolean liteMode =
        Boolean.parseBoolean(
            System.getProperty(
                "justsearch.lite.mode",
                System.getenv().getOrDefault("JUSTSEARCH_LITE_MODE", "false")));
    return !(aiDisabled || liteMode);
  }

  /** Constructs the live inference manager, or returns null when AI is disabled / lite mode. */
  public static InferenceLifecycleManager createInferenceManager(Telemetry telemetry) {
    return createInferenceManager(telemetry,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop());
  }

  public static InferenceLifecycleManager createInferenceManager(
      Telemetry telemetry, io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry) {
    boolean aiEnabled = decideInferenceConfigured();
    boolean liteMode =
        Boolean.parseBoolean(
            System.getProperty(
                "justsearch.lite.mode",
                System.getenv().getOrDefault("JUSTSEARCH_LITE_MODE", "false")));
    boolean aiDisabled =
        Boolean.parseBoolean(
            System.getProperty(
                "justsearch.ai.disabled",
                System.getenv().getOrDefault("JUSTSEARCH_AI_DISABLED", "false")));
    if (liteMode && !aiDisabled) {
      log.info("Lite mode enabled — InferenceLifecycleManager init will be skipped.");
    }
    return BootstrapInferenceFactory.createInferenceManager(
        aiEnabled,
        BootstrapHelpers.currentResolvedConfig(),
        System.getProperty("user.dir"),
        telemetry,
        log,
        childRegistry);
  }
}
