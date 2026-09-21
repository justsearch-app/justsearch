/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;

/**
 * Projects inference intent and physical runtime observations onto the Engine's generative
 * component. The registry handle is the only lifecycle writer; the inference manager and runtime
 * spec remain the physical and desired-state authorities.
 */
public final class InferenceCapabilityWiring {

  private InferenceCapabilityWiring() {}

  /**
   * F3 reorder: Phase 3 service-construction late-bind. Wires the inference manager's
   * mode-change listener AND the runtime-authority spec to drive the generative component
   * state transitions. Called from {@code ServicePhase} after the manager is constructed.
   *
   * <p>Tempdoc 737 §12c item 2 (Phase 2a) — spec-aware rekey. Previously {@code ONLINE} alone
   * drove {@code READY}; the reported gap (tempdoc 737 §15 Phase 2b "Phase-3 finding") was that a
   * background procedure (VDU) can hold the engine {@code ONLINE} under soft-off
   * ({@code chatEnabled=false}), and the old derivation projected chat as available to users
   * during that window. {@code READY} now requires BOTH engine Healthy ({@code mode==ONLINE})
   * AND the user's persisted chat-enabled spec bit; engine Healthy with {@code chatEnabled=false}
   * yields {@code DEGRADED} with {@link RuntimeStatus#REASON_ENGINE_UP_FOR_BACKGROUND} — the same
   * reason code {@code RuntimeReconciler.refreshStatus} stamps onto the ENGINE condition for the
   * identical situation, so the two surfaces agree. OFFLINE / TRANSITIONING / INDEXING mappings
   * are unchanged.
   *
   * <p><b>Re-derivation mechanism</b> (mirror-initial-then-forward, the
   * {@code standalone-capability-stays-stuck} medicine): the mode-change listener re-derives on
   * every ENGINE mode transition (unchanged trigger). ADDITIONALLY, {@code runtimeReconciler}'s
   * {@link RuntimeReconciler#addSpecChangeListener} re-derives — using the manager's live mode —
   * whenever the spec flips WITHOUT an accompanying mode change (e.g. {@code chatEnabled} toggles
   * off while a VDU procedure holds the engine {@code ONLINE}: no {@code ModeChangeListener} fires
   * because the observed mode never changes, but the derived capability must). This is the
   * smallest correct mechanism — one shared derivation function ({@link #deriveAndApply}), two
   * triggers, no polling. {@code runtimeSpecStore} / {@code runtimeReconciler} are both nullable
   * (defensive; production wiring always supplies both when {@code manager} is non-null) — a null
   * store resolves the spec bit to {@code false} (mirrors {@code RuntimeSpec.fromSettings(null)}),
   * so an unattached authority never accidentally reports {@code READY}; a null reconciler simply
   * means no spec-change re-derivation is wired (mode-change re-derivation still is).
   */
  public static void attachInferenceModeListener(
      InferenceLifecycleManager manager,
      ComponentHandle generativeComponent,
      RuntimeSpecStore runtimeSpecStore,
      RuntimeReconciler runtimeReconciler) {
    if (manager == null || generativeComponent == null) {
      return;
    }

    // Mirror initial state synchronously BEFORE forwarding transitions (R3 discipline).
    deriveAndApply(manager, generativeComponent, runtimeSpecStore, null,
        TransitionReason.UNKNOWN, Observation.INITIAL);

    // Tempdoc 837 S5 (§D.2 option c): subscribe to the REASON-bearing listener so an OFFLINE landing
    // can say WHY. The 2-arg ModeChangeListener cannot carry it, and moving TransitionReason into
    // app-api to widen that interface was measured at 18 files against 3 for this.
    manager.addModeTransitionListener(
        (from, to, reason) ->
            deriveAndApply(manager, generativeComponent, runtimeSpecStore, to, reason,
                Observation.MODE_TRANSITION));

    if (runtimeReconciler != null) {
      runtimeReconciler.addSpecChangeListener(
          () ->
              deriveAndApply(manager, generativeComponent, runtimeSpecStore, null,
                  TransitionReason.UNKNOWN, Observation.SPEC_CHANGE));
    }
  }

  private enum Observation { INITIAL, MODE_TRANSITION, SPEC_CHANGE }

  /**
   * Publishes a current-state projection. The component observation is captured before reading the
   * two authorities, so a concurrent newer callback either wins the CAS or follows this write and
   * corrects it. A delayed mode callback is discarded when its destination is no longer current.
   */
  private static void deriveAndApply(
      InferenceLifecycleManager manager,
      ComponentHandle component,
      RuntimeSpecStore runtimeSpecStore,
      Mode observedDestination,
      TransitionReason reason,
      Observation observation) {
    while (true) {
      var expected = component.snapshot();
      boolean requested = runtimeSpecStore != null && runtimeSpecStore.load().chatEnabled();
      Mode current = manager.getCurrentMode();
      if (observedDestination != null && current != observedDestination) {
        return;
      }
      Projection projection = derive(expected.state(), current, requested, reason, observation);
      if (projection == null) {
        return;
      }
      if (component.transitionIfUnchanged(
          expected, projection.state(), projection.reasonCode(), projection.evidence())) {
        return;
      }
    }
  }

  private static Projection derive(
      ComponentState currentState,
      Mode mode,
      boolean requested,
      TransitionReason reason,
      Observation observation) {
    if (!requested) {
      String reasonCode = mode == Mode.ONLINE
          ? RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND
          : LifecycleReasonCode.INFERENCE_DEACTIVATED.code();
      return new Projection(ComponentState.ABSENT, reasonCode, "generative intent is disabled");
    }
    return switch (mode) {
      case ONLINE -> new Projection(ComponentState.READY, null, "inference runtime is online");
      case TRANSITIONING -> new Projection(
          ComponentState.RELOADING,
          LifecycleReasonCode.INFERENCE_STARTING.code(),
          "inference runtime is transitioning");
      case INDEXING -> new Projection(
          ComponentState.UNAVAILABLE,
          LifecycleReasonCode.INFERENCE_GPU_YIELDED_TO_INDEXING.code(),
          "GPU is assigned to indexing");
      case OFFLINE -> offlineProjection(currentState, reason, observation);
    };
  }

  private static Projection offlineProjection(
      ComponentState currentState, TransitionReason reason, Observation observation) {
    if (observation != Observation.MODE_TRANSITION) {
      if (currentState == ComponentState.ABSENT) {
        return new Projection(
            ComponentState.STARTING,
            LifecycleReasonCode.INFERENCE_STARTING.code(),
            "requested inference runtime has not reported a stable mode");
      }
      // A spec observation has no physical failure cause. Preserve the last physical observation
      // until a mode callback or activation producer publishes newer evidence.
      return null;
    }
    LifecycleReasonCode reasonCode = offlineCode(reason);
    ComponentState state = reasonCode == LifecycleReasonCode.INFERENCE_CRASHED
        ? ComponentState.FAILED : ComponentState.UNAVAILABLE;
    return new Projection(state, reasonCode.code(), "inference runtime reported offline");
  }

  private record Projection(ComponentState state, String reasonCode, String evidence) {}

  /**
   * Tempdoc 837 §1.3 — WHY the runtime is OFFLINE, from the reason the transition already carried.
   *
   * <p>Only two reasons name a user-visible truth of their own. Everything else — an auto-start that
   * did not take, a config apply, a VDU enter/exit step, an external detach, app teardown, or no
   * reason at all — is either a transient step of a restart or process shutdown, where the generic
   * code is the honest answer and the FE already words it well.
   */
  private static LifecycleReasonCode offlineCode(TransitionReason reason) {
    if (reason == null) {
      return LifecycleReasonCode.INFERENCE_OFFLINE;
    }
    return switch (reason) {
      // The engine stopped on its own: the periodic-health threshold tripped and recovery forced it
      // down. Nobody chose this, and the remedy is a reload, not a switch.
      case CRASH_RECOVERY -> LifecycleReasonCode.INFERENCE_CRASHED;
      // Somebody chose this. Reporting it as a fault is what trains alarm-blindness.
      case USER_SWITCH, ADMIN_TRIGGERED -> LifecycleReasonCode.INFERENCE_DEACTIVATED;
      case AUTO_START,
          CONFIG_APPLY,
          VDU_ENTER,
          VDU_EXIT,
          EXTERNAL_DETACH,
          SHUTDOWN,
          UNKNOWN -> LifecycleReasonCode.INFERENCE_OFFLINE;
    };
  }
}
