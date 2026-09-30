/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Helper service that orchestrates runtime variant activation (GPU Booster Pack on/off).
 * Composed by {@link RuntimeVariantService} implementations.
 *
 * <p>Interface added as part of tempdoc 519 §9 Block B2. The concrete implementation lives in
 * {@code modules/app-services/.../ai/runtime/} with the same simple name; consumers in {@code app-services}
 * import this interface from {@code app-api}.
 *
 * <p>Stability: stable (API contract).
 */
public interface RuntimeActivationService {

  /** Owner-frozen started status and terminal completion belong to this exact attempt. */
  record Attempt(AiRuntimeActivationStatus started,
      java.util.concurrent.CompletionStage<AiRuntimeActivationStatus> completion) {}

  /** Return the current activation-flow status (idle / running / completed / failed). */
  AiRuntimeActivationStatus getActivationStatus();

  /** Return the broader runtime status including installed variants and ONNX feature health. */
  AiRuntimeStatusResponse getStatus();

  /** Begin activating the named variant; refuse if another attempt is running.
   * The stage resolves after terminal status and owner lease cleanup. */
  Attempt startActivate(String variantId);

  /** Begin deactivating the currently-active variant; resolve after terminal status and cleanup. */
  Attempt startDeactivate();

  /**
   * Tempdoc 737 (task 3): the ONE authoritative admin-policy check for runtime activation
   * (Online AI / GPU acceleration policy). A no-op when no policy is configured.
   *
   * @throws IllegalStateException with a canonical policy-denial message when blocked.
   */
  void enforceActivationPolicy();
}
