/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.ModeTransitionListener;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.app.services.lifecycle.ReasonRetainingComponentHandle;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Spec A step 9: a chat model the controller unloaded for another GPU user is published as the
 * generative component's UNAVAILABLE observation with its own reason code, which the status block
 * reads as UNLOADED; the indexing yield keeps its code.
 */
final class InferenceResidencyStatusTest {

  @TempDir Path tmp;
  private final List<RuntimeIntentTestFixture> intents = new ArrayList<>();
  private final List<TestEngineComponents> registries = new ArrayList<>();

  @AfterEach
  void closeFixtures() {
    intents.forEach(RuntimeIntentTestFixture::close);
    registries.forEach(TestEngineComponents::close);
  }

  @Test
  void aPressureUnloadPublishesTheGpuPressureReason() {
    var fixture = fixture(Mode.ONLINE, null);
    fixture.mode().set(Mode.INDEXING);
    when(fixture.manager().residencyUnloadDetail()).thenReturn(StopDetail.GPU_BUSY);
    transitionListener(fixture.manager()).onModeTransition(
        Mode.ONLINE, Mode.INDEXING, TransitionReason.RESIDENCY_UNLOAD);
    assertEquals(ComponentState.UNAVAILABLE, fixture.handle().snapshot().state());
    assertEquals(LifecycleReasonCode.INFERENCE_UNLOADED_GPU_PRESSURE.code(),
        fixture.handle().snapshot().reasonCode());
    assertEquals("inference.unloaded_gpu_pressure", fixture.handle().snapshot().reasonCode());
  }

  @Test
  void aGameModeUnloadPublishesTheGameModeReason() {
    var fixture = fixture(Mode.INDEXING, StopDetail.GAME_MODE);
    assertEquals(ComponentState.UNAVAILABLE, fixture.handle().snapshot().state());
    assertEquals("inference.unloaded_game_mode", fixture.handle().snapshot().reasonCode());
  }

  @Test
  void theIndexingYieldKeepsItsOwnReason() {
    var fixture = fixture(Mode.INDEXING, null);
    assertEquals(ComponentState.UNAVAILABLE, fixture.handle().snapshot().state());
    assertEquals(LifecycleReasonCode.INFERENCE_GPU_YIELDED_TO_INDEXING.code(),
        fixture.handle().snapshot().reasonCode());
  }

  @Test
  void theReloadAfterAnUnloadIsReadyAgain() {
    var fixture = fixture(Mode.INDEXING, StopDetail.GPU_BUSY);
    when(fixture.manager().residencyUnloadDetail()).thenReturn(null);
    fixture.mode().set(Mode.ONLINE);
    transitionListener(fixture.manager()).onModeTransition(
        Mode.INDEXING, Mode.ONLINE, TransitionReason.RESIDENCY_RELOAD);
    assertEquals(ComponentState.READY, fixture.handle().snapshot().state());
  }

  private Fixture fixture(Mode initialMode, StopDetail unloaded) {
    try {
      var intent = new RuntimeIntentTestFixture(tmp.resolve("runtime-intent-" + intents.size()), true);
      intents.add(intent);
      var registry = TestEngineComponents.fourComponents();
      registries.add(registry);
      ComponentHandle handle = new ReasonRetainingComponentHandle(registry.handle("generative"));
      AtomicReference<Mode> mode = new AtomicReference<>(initialMode);
      InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
      when(manager.getCurrentMode()).thenAnswer(ignored -> mode.get());
      when(manager.residencyUnloadDetail()).thenReturn(unloaded);
      InferenceCapabilityWiring.attachInferenceModeListener(
          manager, handle, intent.spec(), null,
          new java.util.concurrent.locks.ReentrantReadWriteLock());
      return new Fixture(manager, handle, mode);
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
      InferenceLifecycleManager manager, ComponentHandle handle, AtomicReference<Mode> mode) {}
}
