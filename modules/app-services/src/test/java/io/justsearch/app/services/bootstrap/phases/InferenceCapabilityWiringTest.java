/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.ModeTransitionListener;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.app.services.lifecycle.ReasonRetainingComponentHandle;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

final class InferenceCapabilityWiringTest {

  @TempDir Path tmp;
  private final List<RuntimeIntentTestFixture> intents = new ArrayList<>();
  private final List<TestEngineComponents> registries = new ArrayList<>();
  private final AtomicInteger ids = new AtomicInteger();

  @AfterEach
  void closeFixtures() {
    intents.forEach(RuntimeIntentTestFixture::close);
    registries.forEach(TestEngineComponents::close);
  }

  @Test
  void requestedOnlineIsReady() {
    Fixture fixture = fixture(Mode.ONLINE, true, null);
    assertEquals(ComponentState.READY, fixture.handle().snapshot().state());
    assertNull(fixture.handle().snapshot().reasonCode());
  }

  @Test
  void backgroundOnlyRuntimeIsAbsent() {
    Fixture fixture = fixture(Mode.ONLINE, false, null);
    assertEquals(ComponentState.ABSENT, fixture.handle().snapshot().state());
    assertEquals(RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND,
        fixture.handle().snapshot().reasonCode());
  }

  @Test
  void requestedOfflineStartsUntilPhysicalModeIsObserved() {
    Fixture fixture = fixture(Mode.OFFLINE, true, null);
    assertEquals(ComponentState.STARTING, fixture.handle().snapshot().state());
    assertEquals(LifecycleReasonCode.INFERENCE_STARTING.code(),
        fixture.handle().snapshot().reasonCode());
  }

  @Test
  void transitioningAndIndexingMapToReloadingAndUnavailable() {
    Fixture transitioning = fixture(Mode.TRANSITIONING, true, null);
    assertEquals(ComponentState.RELOADING, transitioning.handle().snapshot().state());

    Fixture indexing = fixture(Mode.INDEXING, true, null);
    assertEquals(ComponentState.UNAVAILABLE, indexing.handle().snapshot().state());
    assertEquals(LifecycleReasonCode.INFERENCE_GPU_YIELDED_TO_INDEXING.code(),
        indexing.handle().snapshot().reasonCode());
  }

  @Test
  void crashAndChosenStopKeepDistinctFailureClasses() {
    Fixture crash = fixture(Mode.ONLINE, true, null);
    crash.mode().set(Mode.OFFLINE);
    transitionListener(crash.manager()).onModeTransition(
        Mode.ONLINE, Mode.OFFLINE, TransitionReason.CRASH_RECOVERY);
    assertEquals(ComponentState.FAILED, crash.handle().snapshot().state());
    assertEquals(LifecycleReasonCode.INFERENCE_CRASHED.code(),
        crash.handle().snapshot().reasonCode());

    Fixture chosen = fixture(Mode.ONLINE, true, null);
    chosen.mode().set(Mode.OFFLINE);
    transitionListener(chosen.manager()).onModeTransition(
        Mode.ONLINE, Mode.OFFLINE, TransitionReason.USER_SWITCH);
    assertEquals(ComponentState.UNAVAILABLE, chosen.handle().snapshot().state());
    assertEquals(LifecycleReasonCode.INFERENCE_DEACTIVATED.code(),
        chosen.handle().snapshot().reasonCode());
  }

  @Test
  void disablingAfterCrashMakesOptionalComponentAbsentAndRetainsCause() throws Exception {
    RuntimeReconciler reconciler = mock(RuntimeReconciler.class);
    AtomicReference<Runnable> specListener = captureSpecListener(reconciler);
    Fixture fixture = fixture(Mode.ONLINE, true, reconciler);
    fixture.mode().set(Mode.OFFLINE);
    transitionListener(fixture.manager()).onModeTransition(
        Mode.ONLINE, Mode.OFFLINE, TransitionReason.CRASH_RECOVERY);

    fixture.intent().spec().setChatEnabled(false);
    specListener.get().run();

    assertEquals(ComponentState.ABSENT, fixture.handle().snapshot().state());
    assertEquals(LifecycleReasonCode.INFERENCE_CRASHED.code(),
        fixture.handle().snapshot().reasonCode(), "decorated handle retains the precise cause");
  }

  @Test
  void reEnablingOfflineRuntimePublishesStarting() throws Exception {
    RuntimeReconciler reconciler = mock(RuntimeReconciler.class);
    AtomicReference<Runnable> specListener = captureSpecListener(reconciler);
    Fixture fixture = fixture(Mode.OFFLINE, false, reconciler);

    fixture.intent().spec().setChatEnabled(true);
    specListener.get().run();

    assertEquals(ComponentState.STARTING, fixture.handle().snapshot().state());
    assertEquals(LifecycleReasonCode.INFERENCE_STARTING.code(),
        fixture.handle().snapshot().reasonCode());
  }

  @Test
  void delayedModeCallbackCannotOverwriteNewerCurrentMode() {
    Fixture fixture = fixture(Mode.ONLINE, true, null);
    transitionListener(fixture.manager()).onModeTransition(
        Mode.ONLINE, Mode.OFFLINE, TransitionReason.CRASH_RECOVERY);
    assertEquals(ComponentState.READY, fixture.handle().snapshot().state());
    assertNull(fixture.handle().snapshot().reasonCode());
  }

  private static AtomicReference<Runnable> captureSpecListener(RuntimeReconciler reconciler) {
    AtomicReference<Runnable> listener = new AtomicReference<>();
    doAnswer(invocation -> {
      listener.set(invocation.getArgument(0));
      return null;
    }).when(reconciler).addSpecChangeListener(any());
    return listener;
  }

  private Fixture fixture(Mode initialMode, boolean enabled, RuntimeReconciler reconciler) {
    try {
      var intent = new RuntimeIntentTestFixture(
          tmp.resolve("runtime-intent-" + ids.incrementAndGet()), enabled);
      intents.add(intent);
      var registry = TestEngineComponents.fourComponents();
      registries.add(registry);
      ComponentHandle handle =
          new ReasonRetainingComponentHandle(registry.handle("generative"));
      AtomicReference<Mode> mode = new AtomicReference<>(initialMode);
      InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
      when(manager.getCurrentMode()).thenAnswer(ignored -> mode.get());
      InferenceCapabilityWiring.attachInferenceModeListener(
          manager, handle, intent.spec(), reconciler);
      return new Fixture(manager, handle, intent, mode);
    } catch (Exception failure) {
      throw new AssertionError("Failed to compose fixture", failure);
    }
  }

  private static ModeTransitionListener transitionListener(InferenceLifecycleManager manager) {
    ArgumentCaptor<ModeTransitionListener> captor =
        ArgumentCaptor.forClass(ModeTransitionListener.class);
    verify(manager).addModeTransitionListener(captor.capture());
    return captor.getValue();
  }

  private record Fixture(
      InferenceLifecycleManager manager,
      ComponentHandle handle,
      RuntimeIntentTestFixture intent,
      AtomicReference<Mode> mode) {}
}
