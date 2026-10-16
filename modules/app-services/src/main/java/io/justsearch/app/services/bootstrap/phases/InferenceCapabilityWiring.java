/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.chat.ChatReasonCodes;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.chat.ChatStatusProjection;
import io.justsearch.app.services.lifecycle.ReasonRetainingComponentHandle;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentRegistry;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
   * during that window. {@code READY} requires both a healthy engine ({@code mode==ONLINE})
   * and the user's persisted chat-enabled spec bit. An online background-only runtime projects
   * {@code ABSENT} with {@link RuntimeStatus#REASON_ENGINE_UP_FOR_BACKGROUND}. A transition from
   * READY retains RELOADING; initial activation and recovery project STARTING so the component's
   * declared start deadline applies.
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
      RuntimeReconciler runtimeReconciler,
      ReentrantReadWriteLock publicationLock) {
    if (manager == null || generativeComponent == null) {
      return;
    }
    Objects.requireNonNull(publicationLock, "publicationLock");

    // Mirror initial state synchronously BEFORE forwarding transitions (R3 discipline).
    deriveAndApply(manager, generativeComponent, runtimeSpecStore, null,
        TransitionReason.UNKNOWN, Observation.INITIAL, publicationLock);

    // Tempdoc 837 S5 (§D.2 option c): subscribe to the REASON-bearing listener so an OFFLINE landing
    // can say WHY. The 2-arg ModeChangeListener cannot carry it, and moving TransitionReason into
    // app-api to widen that interface was measured at 18 files against 3 for this.
    manager.addModeTransitionListener(
        (from, to, reason) ->
            deriveAndApply(manager, generativeComponent, runtimeSpecStore, to, reason,
                 Observation.MODE_TRANSITION, publicationLock));

    if (runtimeReconciler != null) {
      runtimeReconciler.addSpecChangeListener(
          () ->
              deriveAndApply(manager, generativeComponent, runtimeSpecStore, null,
                   TransitionReason.UNKNOWN, Observation.SPEC_CHANGE, publicationLock));
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
      Observation observation,
      ReentrantReadWriteLock publicationLock) {
    while (true) {
      var expected = component.snapshot();
      boolean requested = runtimeSpecStore != null && runtimeSpecStore.load().chatEnabled();
      Mode current = manager.getCurrentMode();
      if (observedDestination != null && current != observedDestination) {
        return;
      }
      Projection projection = derive(expected.state(), current, requested, reason, observation,
          current == Mode.OFFLINE ? manager.lastChatFailedCode() : null,
          current == Mode.ONLINE ? contextWindowReason(manager) : null,
          current == Mode.INDEXING ? manager.residencyUnloadDetail() : null);
      if (projection == null) {
        return;
      }
      if (current == Mode.ONLINE) {
        EngineComponentRegistry.PreparedBatch[] prepared = {null};
        boolean verified = manager.withVerifiedServingConfiguration((inference, resolved) -> {
          String version = HeadAssembly.generativeAppliedVersion(inference, resolved);
          publicationLock.writeLock().lock();
          try {
            if (!expected.equals(component.snapshot())) return;
            var replacement = ReasonRetainingComponentHandle.appliedObservation(
                expected, projection.state(), projection.reasonCode(), projection.evidence(), version);
            var batch = component.prepareReplacement(replacement);
            batch.validate();
            batch.install();
            prepared[0] = batch;
          } finally {
            publicationLock.writeLock().unlock();
          }
        });
        if (verified) {
          if (prepared[0] == null) continue;
          prepared[0].notifyObservers();
          return;
        }
      }
      if (component.transitionIfUnchanged(
          expected, projection.state(), projection.reasonCode(), projection.evidence())) {
        return;
      }
    }
  }

  /** The reason the serving window was chosen ({@code stepped-from:<rung>} after a step-down). */
  private static String contextWindowReason(InferenceLifecycleManager manager) {
    var window = manager.launchedContextWindow();
    return window == null ? null : window.reason();
  }

  /**
   * @param latched the chat failure latched when the runtime is OFFLINE (Spec A step 7), or null
   * @param contextWindowReason why the serving window was chosen when ONLINE, or null
   * @param residencyUnload why the controller unloaded chat for another GPU user when the runtime
   *     is INDEXING (Spec A step 9), or null
   */
  private static Projection derive(
      ComponentState currentState,
      Mode mode,
      boolean requested,
      TransitionReason reason,
      Observation observation,
      FailedCode latched,
      String contextWindowReason,
      StopDetail residencyUnload) {
    if (!requested) {
      String reasonCode = mode == Mode.ONLINE
          ? RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND
          : LifecycleReasonCode.INFERENCE_DEACTIVATED.code();
      return new Projection(ComponentState.ABSENT, reasonCode, "generative intent is disabled");
    }
    return switch (mode) {
      // Spec A §7.2: a server stepped down to a smaller window serves with the context_reduced
      // notice.
      case ONLINE -> {
        var ready = ChatStatusProjection.ready(contextWindowReason);
        yield new Projection(ready.state(), ready.reasonCode(), "inference runtime is online");
      }
      case TRANSITIONING -> new Projection(
          currentState == ComponentState.READY || currentState == ComponentState.RELOADING
              ? ComponentState.RELOADING : ComponentState.STARTING,
          LifecycleReasonCode.INFERENCE_STARTING.code(),
          "inference runtime is transitioning");
      // Spec A step 9: an idle model unloaded for another GPU user says so; it reloads on demand.
      case INDEXING -> residencyUnload != null
          ? new Projection(
              ComponentState.UNAVAILABLE,
              ChatReasonCodes.forResidencyUnload(residencyUnload).code(),
              "chat unloaded for another GPU user: " + residencyUnload.token())
          : new Projection(
              ComponentState.UNAVAILABLE,
              LifecycleReasonCode.INFERENCE_GPU_YIELDED_TO_INDEXING.code(),
              "GPU is assigned to indexing");
      case OFFLINE -> offlineProjection(currentState, reason, observation, latched);
    };
  }

  private static Projection offlineProjection(
      ComponentState currentState, TransitionReason reason, Observation observation,
      FailedCode latched) {
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
    // Spec A step 7: a landing that left a classified failure latched says which failure (out of
    // memory, a start timeout, a model that would not load...) rather than only that it crashed.
    if (latched != null) {
      var failed = ChatStatusProjection.failed(latched);
      return new Projection(failed.state(), failed.reasonCode(),
          "chat failed: " + latched.name().toLowerCase(java.util.Locale.ROOT));
    }
    if (reason == TransitionReason.AUTO_START
        && (currentState == ComponentState.STARTING || currentState == ComponentState.FAILED)) {
      return new Projection(ComponentState.FAILED,
          LifecycleReasonCode.INFERENCE_ACTIVATION_FAILED.code(),
          "requested inference activation returned offline");
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
          // Stopped for an activation self-test; the session's end loads it again.
          SELF_TEST,
          // A residency unload lands INDEXING; an OFFLINE landing here is a stop step's failure.
          RESIDENCY_UNLOAD,
          RESIDENCY_RELOAD,
          EXTERNAL_DETACH,
          SHUTDOWN,
          UNKNOWN -> LifecycleReasonCode.INFERENCE_OFFLINE;
    };
  }
}
