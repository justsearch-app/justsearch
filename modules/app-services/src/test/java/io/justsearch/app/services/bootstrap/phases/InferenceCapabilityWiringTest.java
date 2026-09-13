/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeChangeListener;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.ModeTransitionListener;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.runtimestate.RuntimeGpuLease;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Tempdoc 737 §12c item 2 (Phase 2a) — spec-aware rekey of {@link InferenceCapabilityWiring}.
 * {@code InferenceLifecycleManager} is a concrete, non-final class with non-final
 * {@code getCurrentMode()} / {@code addModeChangeListener}, so Mockito mocking it directly (rather
 * than a hand-written stub) is the lightest correct test double — no real llama-server process is
 * ever spun up.
 */
final class InferenceCapabilityWiringTest {

  @TempDir Path tmp;
  private final List<RuntimeIntentTestFixture> fixtures = new ArrayList<>();
  private final AtomicInteger fixtureIds = new AtomicInteger();

  private RuntimeSpecStore specStore(boolean chatEnabled) {
    try {
      var fixture = new RuntimeIntentTestFixture(
          tmp.resolve("runtime-intent-" + fixtureIds.incrementAndGet()), chatEnabled);
      fixtures.add(fixture);
      return fixture.spec();
    } catch (Exception failure) {
      throw new AssertionError("Failed to compose runtime intent fixture", failure);
    }
  }

  @AfterEach
  void closeFixtures() {
    fixtures.forEach(RuntimeIntentTestFixture::close);
  }

  @Test
  void engineOnlineSpecOn_yieldsReady() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    InferenceCapability cap = new InferenceCapability(true);

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(true), null);

    assertEquals(CapabilityHealth.READY, cap.health());
  }

  @Test
  void engineOnlineSpecOff_yieldsDegradedWithBackgroundReason() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    InferenceCapability cap = new InferenceCapability(true);

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(false), null);

    assertEquals(CapabilityHealth.DEGRADED, cap.health());
    assertEquals(RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND, cap.pendingReason());
  }

  @Test
  // Tempdoc 837 S4: converted from the prose "Inference offline". Same state, same intent — the
  // OFFLINE arm still yields the generic cause (crash vs user-deactivate needs the TransitionReason
  // S5 threads); it is now the CODE the consumer forwards instead of prose it had to discard.
  void offlineUnchanged() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.OFFLINE);
    InferenceCapability cap = new InferenceCapability(true);

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(true), null);

    assertEquals(CapabilityHealth.OFFLINE, cap.health());
    assertEquals(LifecycleReasonCode.INFERENCE_OFFLINE.code(), cap.pendingReason());
  }

  @Test
  void indexingUnchanged() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.INDEXING);
    InferenceCapability cap = new InferenceCapability(true);

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(true), null);

    assertEquals(CapabilityHealth.DEGRADED, cap.health());
    assertEquals(
        LifecycleReasonCode.INFERENCE_GPU_YIELDED_TO_INDEXING.code(), cap.pendingReason());
  }

  @Test
  void transitioningUnchanged() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.TRANSITIONING);
    InferenceCapability cap = new InferenceCapability(true);

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(true), null);

    assertEquals(CapabilityHealth.RECOVERING, cap.health());
    assertEquals(LifecycleReasonCode.INFERENCE_STARTING.code(), cap.pendingReason());
  }

  /**
   * Tempdoc 837 S5 (§D.2 option c): the subscription moved from the 2-arg {@code ModeChangeListener}
   * to the reason-bearing {@link ModeTransitionListener}, because crash recovery and a user
   * deactivation are the same {@code * → OFFLINE} without the reason. Same behaviour is asserted;
   * only the seam the wiring subscribes to changed.
   */
  private static ModeTransitionListener captureTransitionListener(
      InferenceLifecycleManager manager) {
    ArgumentCaptor<ModeTransitionListener> captor =
        ArgumentCaptor.forClass(ModeTransitionListener.class);
    verify(manager).addModeTransitionListener(captor.capture());
    return captor.getValue();
  }

  @Test
  void modeChangeListenerReDerivesOnTransition() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.OFFLINE);
    InferenceCapability cap = new InferenceCapability(true);

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(true), null);
    assertEquals(CapabilityHealth.OFFLINE, cap.health(), "initial mirror");

    // Simulate the engine coming online: the callback's `to` argument drives re-derivation.
    captureTransitionListener(manager)
        .onModeTransition(Mode.OFFLINE, Mode.ONLINE, TransitionReason.AUTO_START);

    assertEquals(CapabilityHealth.READY, cap.health(), "mode-change listener re-derived");
  }

  /**
   * Tempdoc 837 §1.3 — the OFFLINE arm's reason→code mapping, one case per class. The distinction
   * this proves is invisible to every other check: crash recovery and a deactivation are the SAME
   * {@code (from, to)} pair.
   */
  @Test
  void crashRecoveryYieldsTheCrashCode() {
    InferenceCapability cap = onlineCapabilityWithListener(TransitionReason.CRASH_RECOVERY);
    assertEquals(CapabilityHealth.OFFLINE, cap.health());
    assertEquals(LifecycleReasonCode.INFERENCE_CRASHED.code(), cap.pendingReason());
  }

  @Test
  void userSwitchYieldsTheDeactivatedCode() {
    InferenceCapability cap = onlineCapabilityWithListener(TransitionReason.USER_SWITCH);
    assertEquals(LifecycleReasonCode.INFERENCE_DEACTIVATED.code(), cap.pendingReason());
  }

  @Test
  void adminTriggeredYieldsTheDeactivatedCode() {
    InferenceCapability cap = onlineCapabilityWithListener(TransitionReason.ADMIN_TRIGGERED);
    assertEquals(LifecycleReasonCode.INFERENCE_DEACTIVATED.code(), cap.pendingReason());
  }

  @Test
  void everyOtherReasonYieldsTheGenericCode() {
    for (TransitionReason reason :
        List.of(
            TransitionReason.AUTO_START,
            TransitionReason.CONFIG_APPLY,
            TransitionReason.VDU_ENTER,
            TransitionReason.VDU_EXIT,
            TransitionReason.EXTERNAL_DETACH,
            TransitionReason.SHUTDOWN,
            TransitionReason.UNKNOWN)) {
      InferenceCapability cap = onlineCapabilityWithListener(reason);
      assertEquals(
          LifecycleReasonCode.INFERENCE_OFFLINE.code(),
          cap.pendingReason(),
          reason + " is a transient restart step or teardown — generic is the honest answer");
    }
  }

  /** Engine ONLINE + chat on (so the capability starts READY, clearing any hold), then OFFLINE. */
  private InferenceCapability onlineCapabilityWithListener(TransitionReason reason) {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    InferenceCapability cap = new InferenceCapability(true);
    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(true), null);
    assertEquals(CapabilityHealth.READY, cap.health(), "precondition: the engine was up");

    captureTransitionListener(manager).onModeTransition(Mode.ONLINE, Mode.OFFLINE, reason);
    return cap;
  }

  /**
   * Tempdoc 837 §D.1 test 2 — {@code specificFaultSurvivesSpecToggle}, the point of the slice.
   *
   * <p>After a crash the user toggles a setting. The spec-change re-derivation
   * ({@code addSpecChangeListener}) has no transition in hand, so it passes {@code UNKNOWN} → the
   * GENERIC code. Without the §D.1 retention rule that write erases {@code inference.crashed} and
   * S5 ships the very collapse it exists to fix — with its happy-path tests all green.
   */
  @Test
  void specificFaultSurvivesSpecToggle() throws Exception {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.OFFLINE);
    InferenceCapability cap = new InferenceCapability(true);
    RuntimeSpecStore spec = specStore(true);

    FakeControl control = new FakeControl(Mode.OFFLINE);
    RuntimeReconciler reconciler =
        new RuntimeReconciler(
            control, control::mode, control::external, control::detach, null, spec, new RuntimeGpuLease());
    reconciler.start();
    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, spec, reconciler);

    // The engine crashes.
    captureTransitionListener(manager)
        .onModeTransition(Mode.ONLINE, Mode.OFFLINE, TransitionReason.CRASH_RECOVERY);
    assertEquals(LifecycleReasonCode.INFERENCE_CRASHED.code(), cap.pendingReason());

    // The user flips chat off, then on again. Each flip re-derives with UNKNOWN ⇒ inference.offline.
    spec.setChatEnabled(false);
    reconciler.specChanged();
    assertEquals(
        LifecycleReasonCode.INFERENCE_CRASHED.code(),
        cap.pendingReason(),
        "a setting toggle is not a cause — it must not overwrite the crash");

    spec.setChatEnabled(true);
    reconciler.specChanged();
    assertEquals(LifecycleReasonCode.INFERENCE_CRASHED.code(), cap.pendingReason());

    // …and the crash cause clears the moment the engine actually comes back.
    captureAllTransitionListeners(manager)
        .onModeTransition(Mode.OFFLINE, Mode.ONLINE, TransitionReason.USER_SWITCH);
    assertEquals(CapabilityHealth.READY, cap.health());
    assertNull(cap.pendingReason(), "recovery is the bound");

    reconciler.close();
  }

  /** Same capture, tolerating the several invocations the test above has already made. */
  private static ModeTransitionListener captureAllTransitionListeners(
      InferenceLifecycleManager manager) {
    ArgumentCaptor<ModeTransitionListener> captor =
        ArgumentCaptor.forClass(ModeTransitionListener.class);
    verify(manager, atLeastOnce()).addModeTransitionListener(captor.capture());
    return captor.getValue();
  }

  /**
   * The reverse ordering of §1.4's hazard: the generic mode-change lands FIRST, then the precise
   * activation failure arrives. Retention must never block better information.
   */
  @Test
  void modeChangeThenActivationFailure() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.OFFLINE);
    InferenceCapability cap = new InferenceCapability(true);
    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(true), null);
    assertEquals(LifecycleReasonCode.INFERENCE_OFFLINE.code(), cap.pendingReason());

    // RuntimeActivationService.reportToCapability, after the mode change.
    cap.transition(
        CapabilityHealth.OFFLINE, LifecycleReasonCode.INFERENCE_MODEL_NOT_FOUND.code(), "no file");
    assertEquals(LifecycleReasonCode.INFERENCE_MODEL_NOT_FOUND.code(), cap.pendingReason());

    // And a subsequent generic re-derivation cannot take it back.
    captureTransitionListener(manager)
        .onModeTransition(Mode.OFFLINE, Mode.OFFLINE, TransitionReason.UNKNOWN);
    assertEquals(LifecycleReasonCode.INFERENCE_MODEL_NOT_FOUND.code(), cap.pendingReason());
  }

  /**
   * The "listener contract" case the mode-change listener alone MISSES: {@code chatEnabled} flips
   * while the observed engine mode never changes (e.g. a VDU procedure holds the engine ONLINE
   * under soft-off). Proves {@link RuntimeReconciler#addSpecChangeListener} is the mechanism that
   * catches it.
   */
  @Test
  void specChangeWhileOnlineReDerivesViaReconciler() throws Exception {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    InferenceCapability cap = new InferenceCapability(true);
    RuntimeSpecStore spec = specStore(false);

    FakeControl control = new FakeControl(Mode.ONLINE);
    RuntimeReconciler reconciler =
        new RuntimeReconciler(
            control, control::mode, control::external, control::detach, null, spec, new RuntimeGpuLease());
    reconciler.start();

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, spec, reconciler);
    assertEquals(CapabilityHealth.DEGRADED, cap.health(), "spec off, engine already online");
    assertEquals(RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND, cap.pendingReason());

    // Flip the spec WITHOUT any observed mode change — manager.getCurrentMode() still reports
    // ONLINE throughout, so no ModeChangeListener fires; only the spec-change listener can catch this.
    spec.setChatEnabled(true);
    reconciler.specChanged();

    assertEquals(CapabilityHealth.READY, cap.health(), "spec-change listener re-derived");

    reconciler.close();
  }

  @Test
  void nullReconcilerSkipsSpecChangeWiringButModeListenerStillWorks() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    InferenceCapability cap = new InferenceCapability(true);

    // Must not throw with a null reconciler.
    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(true), null);
    assertEquals(CapabilityHealth.READY, cap.health());
  }

  @Test
  void nullSpecStoreResolvesToChatDisabled() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    InferenceCapability cap = new InferenceCapability(true);

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, null, null);

    assertEquals(CapabilityHealth.DEGRADED, cap.health(), "unattached authority never reports READY");
  }

  /** Minimal {@link OnlineAiLifecycleControl} double for the reconciler-based test above. */
  private static final class FakeControl implements OnlineAiLifecycleControl {
    private volatile Mode currentMode;

    FakeControl(Mode initial) {
      this.currentMode = initial;
    }

    Mode mode() {
      return currentMode;
    }

    boolean external() {
      return false;
    }

    void detach() {}

    @Override
    public boolean isOnline() {
      return currentMode == Mode.ONLINE;
    }

    @Override
    public boolean isIndexing() {
      return currentMode == Mode.INDEXING;
    }

    @Override
    public void switchToOnlineMode() throws ModeTransitionException {
      currentMode = Mode.ONLINE;
    }

    @Override
    public void switchToIndexingMode() throws ModeTransitionException {
      currentMode = Mode.INDEXING;
    }

    @Override
    public void enterVduMode() throws ModeTransitionException {}

    @Override
    public void exitVduMode() throws ModeTransitionException {}

    @Override
    public void addModeChangeListener(ModeChangeListener listener) {}

    @Override
    public void removeModeChangeListener(ModeChangeListener listener) {}
  }
}
