/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.configuration.model.ChatModelProfile;

/**
 * Operator/runtime control surface for the Online AI runtime.
 *
 * <p>This is intentionally separate from {@link OnlineAiService} so user-facing operations (ask/summarize)
 * do not need to expose lifecycle/reload controls.
 */
public interface OnlineAiRuntimeControl {

  /**
   * Restart/apply policy for config changes.
   */
  enum RestartPolicy {
    /** Apply config changes without starting/restarting llama-server. */
    APPLY_ONLY,
    /** Restart llama-server only if it is currently running in Online mode. */
    RESTART_IF_ONLINE,
    /** Always restart llama-server (may start it even if currently offline). */
    RESTART_ALWAYS
  }

  /**
   * Result of detaching from an adopted external llama-server instance.
   *
   * <p>Detaching starts a JustSearch-owned llama-server process on a new free port, leaving the
   * external server untouched.
   */
  record DetachExternalServerResult(boolean detached, int previousPort, int newPort) {}

  /**
   * Apply runtime overrides for llama-server configuration.
   *
   * <p>Callers typically commit settings through the recorded settings owner first, then request an
   * apply/reload so the running llama-server (if any) is restarted with the updated config.
   *
   * @param llmModelPath full path to the generative model file (GGUF), or blank to keep current
   * @param contextLength desired context window size (tokens), or null/<=0 to keep current
   * @param gpuLayers desired GPU layer offload, or null/<0 to keep current
   * @param restartPolicy restart behavior
   */
  void applyRuntimeOverrides(
      String llmModelPath,
      Integer contextLength,
      Integer gpuLayers,
      RestartPolicy restartPolicy);

  /**
   * Tempdoc 412 follow-up Bug E: admin-attributed apply-runtime path. Same as
   * {@link #applyRuntimeOverrides(String, Integer, Integer, RestartPolicy)} but the underlying
   * transition is tagged {@code reason=admin_triggered} so operator-triggered reloads are
   * distinguishable from settings-page-driven applies in the metric stream. Default impl falls
   * back to the four-arg form (preserves legacy reason); overrides should propagate the
   * admin-triggered classification.
   */
  default void applyRuntimeOverridesAdmin(
      String llmModelPath,
      Integer contextLength,
      Integer gpuLayers,
      RestartPolicy restartPolicy) {
    applyRuntimeOverrides(llmModelPath, contextLength, gpuLayers, restartPolicy);
  }

  /**
   * Applies a chat model profile — the profile-driven counterpart of
   * {@link #applyRuntimeOverrides(String, Integer, Integer, RestartPolicy)} (tempdoc 842 §2.3).
   *
   * <p>Both methods change which model llama-server loads, and the difference between them is the
   * whole point:
   *
   * <ul>
   *   <li>{@code applyRuntimeOverrides} takes a <b>bare path</b>. A bare path genuinely has no
   *       known projector, so that call stays defensive and drops the mmproj — which is correct
   *       there, and is exactly why dev stacks ran silently text-only when it was used for
   *       everything.
   *   <li>{@code applyChatProfile} takes a <b>named bundle</b>. Model, projector, and profile id
   *       are applied together as one unit, so a half-swap (new model, stale or dropped projector)
   *       is unrepresentable and vision survives the switch.
   * </ul>
   *
   * <p>Everything the profile does not name — context size, GPU layers, port, server executable —
   * is carried over from the running configuration untouched. If the profile's projector file is
   * missing on disk the runtime warns and starts text-only rather than failing the switch;
   * llama-server refuses to start on a projector path that does not resolve.
   *
   * @param profile the profile to apply; must not be null
   * @param restartPolicy restart behavior, as for {@link #applyRuntimeOverrides}
   * @throws UnsupportedOperationException if this control surface cannot apply profiles
   */
  default void applyChatProfile(ChatModelProfile profile, RestartPolicy restartPolicy) {
    // Deliberately NOT a fallback onto applyRuntimeOverrides(profile.modelFile(), ...): that would
    // re-enter the bare-path branch, silently drop the projector, and reintroduce the exact defect
    // this method exists to fix. An implementation that cannot apply pairs must say so.
    throw new UnsupportedOperationException(
        "applyChatProfile is not supported by " + getClass().getName());
  }

  /**
   * Applies a named chat profile together with an explicit runtime target in one config change.
   *
   * <p>This is the activation counterpart of {@link #applyChatProfile}: the profile still owns the
   * atomic model/projector pair, while the caller supplies the server executable and may replace
   * context size and GPU layers. A {@code null} or non-positive context keeps the current value; a
   * {@code null} or negative GPU layer count keeps the current value, while zero explicitly selects
   * CPU execution. The executable is required and must be non-blank.
   *
   * <p>The default fails closed because decomposing this operation into separate applies would lose
   * the true previous runtime configuration needed by lifecycle rollback.
   *
   * @param profile the profile whose model/projector pair is applied
   * @param serverExecutable explicit llama-server executable path
   * @param contextLength desired context size, or null/non-positive to keep the current value
   * @param gpuLayers desired GPU layer count, including zero, or null/negative to keep current
   * @param restartPolicy restart behavior
   * @throws UnsupportedOperationException if this control surface cannot apply the combined target
   */
  default void applyChatProfileWithRuntime(
      ChatModelProfile profile,
      String serverExecutable,
      Integer contextLength,
      Integer gpuLayers,
      RestartPolicy restartPolicy) {
    throw new UnsupportedOperationException(
        "applyChatProfileWithRuntime is not supported by " + getClass().getName());
  }

  /**
   * Detaches from an adopted external llama-server instance (if any) and starts a JustSearch-owned
   * llama-server on a new free port.
   *
   * <p>If the runtime is not currently using an external server, returns {@code detached=false}.
   */
  DetachExternalServerResult detachExternalServer();

  /**
   * Tempdoc 412 Phase 5: triggers a config-driven restart (RESTART_IF_ONLINE) without changing
   * any config values. Used by the admin endpoint {@code POST /api/admin/inference/reload} to
   * cycle the inference runtime — operators apply config changes via
   * {@link #applyRuntimeOverrides} when they need to change values; this is the no-arg
   * "restart with same config" affordance.
   *
   * <p>Default implementation delegates to {@link #applyRuntimeOverrides} with all-null
   * overrides and {@link RestartPolicy#RESTART_IF_ONLINE}, then returns elapsed wall-clock ms.
   *
   * @return elapsed ms for the apply call (0 if not online — RESTART_IF_ONLINE is a no-op)
   */
  default long reloadRuntime() {
    long startMs = System.currentTimeMillis();
    applyRuntimeOverridesAdmin(null, null, null, RestartPolicy.RESTART_IF_ONLINE);
    return System.currentTimeMillis() - startMs;
  }
}
