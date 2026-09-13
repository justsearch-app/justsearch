/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Brain inference runtime control surface exposed to the AppFacade.
 *
 * <p>Slice 3a-2-c continuation (BrainRuntimeSection cluster): backs
 * {@code core.reload-inference} (apply persisted settings to the running
 * inference runtime) and {@code core.switch-inference-mode} (online/indexing
 * toggle). Production wiring: {@code InferenceHandlers} (modules/ui)
 * implements this interface; {@code LocalApiServer} late-binds it onto
 * {@code HeadAssembly} after both are constructed.
 *
 * <p>Why a dedicated service interface rather than extending
 * {@link OnlineAiService}: reloadInference depends on the
 * {@code UiSettingsStore} (modules/ui-side persistence) to read the model
 * path / context length / GPU layers before invoking
 * {@code OnlineAiRuntimeControl.applyRuntimeOverrides}. Pulling that into
 * {@code OnlineAiServiceImpl} would couple inference to settings; the
 * BrainRuntimeService wrapper keeps that coupling at the controller layer
 * where it already exists.
 *
 * <p>Stability: stable (API contract).
 */
public interface BrainRuntimeService {

  /**
   * Apply persisted settings (model path, context length, GPU layers) to the
   * running inference runtime.
   *
   * <p>Must NOT auto-start llama-server when offline — only restarts the
   * server when already in ONLINE mode. Mirrors the pre-existing
   * {@code POST /api/inference/reload} contract bit-identically.
   *
   * @return the post-apply current mode (e.g., {@code "online"},
   *     {@code "indexing"}, {@code "offline"}) for the caller to surface
   * @throws Exception on apply failure (invalid config, missing model,
   *     subprocess restart failure)
   */
  String reloadInference() throws Exception;

  /**
   * Record a chat-enabled intent expressed in the legacy mode vocabulary. Validates the mode
   * string (must be {@code "online"} or {@code "indexing"} — case-insensitive) and writes the
   * intent through the ONE runtime authority (spec write + reconciler nudge); the engine
   * transition itself is the reconciler's business (tempdoc 737 §12b).
   *
   * <p>The write is asynchronous by construction, so the answer is an outcome, not a claim that
   * the target state has been reached (tempdoc 804 §B6).
   *
   * @param mode {@code "online"} or {@code "indexing"} (case-insensitive)
   * @return the requested mode, the live mode at return time, and whether they already agree
   * @throws IllegalArgumentException for an invalid mode string
   * @throws Exception when the intent cannot be recorded (runtime authority unavailable)
   */
  ModeTransitionOutcome switchInferenceMode(String mode) throws Exception;

  /**
   * Start one captured enrichment pass and return its actual completion after owner cleanup.
   * Progress contains only acknowledged index outcomes; embedding work is a mode handoff.
   *
   * @throws UnsupportedOperationException if the offline-processing trigger
   *     isn't configured (test paths, headless modes that don't enable it)
   * @throws RuntimeException on admission or dispatch failure
   */
  java.util.concurrent.CompletionStage<OfflineProcessingOutcome> triggerOfflineProcessing(
      io.justsearch.core.context.EngineContext context,
      java.util.function.Consumer<OfflineProcessingOutcome> progress);

}
