/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.api.inference.RealizedChatIdentity;
import io.justsearch.app.api.status.InferenceFailureView;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.LifecycleCounters;
import io.justsearch.app.api.status.RuntimeIdentityView;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.RuntimeIdentity;
import io.justsearch.app.observability.health.ConditionRecoveryIndex;
import io.justsearch.app.observability.health.ConditionRecoveryIndexBuilder;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpec;
import io.justsearch.app.services.runtimestate.RuntimeStatus;

/**
 * Tempdoc 519 §10 final-push: extracted static projection helpers from
 * {@code HeadAssembly}.
 *
 * <p>Two pure functions:
 * <ul>
 *   <li>{@link #projectInferenceSnapshot} — typed projection of
 *       {@link InferenceLifecycleManager} into {@link InferenceRuntimeView} for status
 *       endpoints. OFFLINE/unavailable view when manager is null.
 *   <li>{@link #projectRealizedChatIdentity} — typed projection of the RUNNING engine's chat
 *       identity into {@link RealizedChatIdentity} for the manifest + runtime-status honesty
 *       surfaces (tempdoc 842 §2.5). Null when no engine is up.
 *   <li>{@link #renderConditionContext} — natural-language render of the current
 *       condition-recovery snapshot, used by AgentLoopService at prompt-construction time.
 * </ul>
 */
public final class BootstrapProjections {

  private BootstrapProjections() {}

  /**
   * Tempdoc 842 §2.5: projects the chat identity the engine is <em>actually</em> running.
   *
   * <p>The one authority is {@code InferenceLifecycleManager.currentConfig()} — the config a live
   * llama-server was started with, carrying its chat-profile claim, its model file and whether a
   * projector is attached. Settings, system properties and the install contract are all
   * <em>declared</em> state and are deliberately not consulted here; the whole point of the
   * projection is that it can disagree with them.
   *
   * <p>Returns {@code null} — not a stale last-known value — when there is no manager, no config,
   * or the engine is not online. An offline engine has no realized identity, and reporting the
   * last one it had is exactly the lie 805 was written about.
   */
  public static RealizedChatIdentity projectRealizedChatIdentity(InferenceLifecycleManager mgr) {
    if (mgr == null || !mgr.isOnline()) {
      return null;
    }
    var cfg = mgr.currentConfig();
    if (cfg == null) {
      return null;
    }
    return RealizedChatIdentity.of(cfg.chatProfileId(), cfg.modelPath(), cfg.mmprojPath());
  }

  /**
   * Tempdoc 412 Phase 3: projects the {@link InferenceLifecycleManager}'s typed accessor surface
   * into the API status record {@link InferenceRuntimeView}. Returns the OFFLINE/unavailable
   * view when {@code mgr} is null (AI disabled).
   *
   * <p>Tempdoc 737 §12c Phase 2a: {@code reconciler} additively feeds the runtime-authority
   * fields ({@code chatEnabledSpec}/{@code engineState}/{@code engineReason}/{@code procedure}/
   * {@code leaseHolder}) from the reconciler's live {@link RuntimeSpec} + {@link RuntimeStatus}.
   * Nullable — an absent reconciler (mgr null, or the rare pre-reconciler-attach boot window)
   * projects them at their empty/default value. A pending VDU restoration that has exhausted
   * automatic retries retains its failure code and adds the stopped-retries status text.
   */
  public static InferenceRuntimeView projectInferenceSnapshot(
      InferenceLifecycleManager mgr, RuntimeReconciler reconciler) {
    if (mgr == null) {
      return new InferenceRuntimeView(
          "OFFLINE", null, false, null, new LifecycleCounters(0L, 0L, 0L), false, "", "", "", "");
    }
    String phase = mgr.getCurrentMode().name();
    boolean usingExternal = mgr.isUsingExternalLlamaServer();
    RuntimeIdentityView identityView =
        mgr.identity()
            .map(
                id ->
                    new RuntimeIdentityView(
                        id.generationId(), id.modelId(), id.port(), id.loadedAtEpochMs()))
            .orElse(null);
    InferenceFailureView failureView =
        mgr.lastFailure()
            .map(f -> new InferenceFailureView(f.wireCode(),
                mgr.chatResidency() != null && mgr.chatResidency().vduRestorationRetriesStopped()
                    ? f.detail() + " Automatic retries stopped after five failed attempts."
                        + " Send a chat request or apply settings to try again."
                    : f.detail()))
            .orElse(null);
    long transitionsTotal = mgr.identity().map(RuntimeIdentity::generationId).orElse(0L);
    LifecycleCounters counters = new LifecycleCounters(0L, 0L, transitionsTotal);

    boolean chatEnabledSpec = false;
    String engineState = "";
    String engineReason = "";
    String procedure = "";
    String leaseHolder = "";
    if (reconciler != null) {
      RuntimeSpec spec = reconciler.currentSpec();
      if (spec != null) {
        chatEnabledSpec = spec.chatEnabled();
      }
      RuntimeStatus status = reconciler.current();
      if (status != null) {
        engineState =
            status
                .condition(RuntimeStatus.Axis.ENGINE)
                .map(RuntimeStatus.Condition::status)
                .orElse("");
        engineReason =
            status
                .condition(RuntimeStatus.Axis.ENGINE)
                .map(RuntimeStatus.Condition::reason)
                .orElse("");
        procedure =
            status
                .condition(RuntimeStatus.Axis.PROCEDURE)
                .map(RuntimeStatus.Condition::status)
                .filter(s -> !"none".equals(s))
                .orElse("");
        leaseHolder =
            status
                .condition(RuntimeStatus.Axis.LEASE)
                .map(RuntimeStatus.Condition::status)
                .orElse("");
      }
    }
    return new InferenceRuntimeView(
        phase,
        identityView,
        usingExternal,
        failureView,
        counters,
        chatEnabledSpec,
        engineState,
        engineReason,
        procedure,
        leaseHolder);
  }

  /**
   * Slice 447 §X.11.5 Phase 5: renders the current condition-recovery snapshot as a
   * natural-language block the agent can read at prompt-construction time. Empty when no
   * asserted conditions carry recoveries.
   */
  public static String renderConditionContext(ConditionStore store) {
    if (store == null) return "";
    ConditionRecoveryIndex index = ConditionRecoveryIndexBuilder.build(store);
    if (index.entries().isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    sb.append("Currently asserted conditions and their recommended recovery operations:");
    for (var entry : index.entries()) {
      for (var ref : entry.conditions()) {
        sb.append("\n- ")
            .append(ref.conditionId())
            .append(" (")
            .append(ref.subject())
            .append(", severity=")
            .append(ref.severity())
            .append(") → ")
            .append(entry.target().value());
      }
    }
    sb.append(
        "\n\nWhen the user asks how to address these issues, reference the recommended"
            + " operations and offer to invoke them through the appropriate tool.");
    return sb.toString();
  }
}
