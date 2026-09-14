/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeChangeListener;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.runtimestate.RuntimeGpuLease;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 737 §12c Phase 2a — {@link BootstrapProjections#projectInferenceSnapshot} now ALSO
 * reads the reconciler's live {@code RuntimeSpec}/{@code RuntimeStatus}, additively, without
 * changing the pre-existing field derivations (byte-identical {@code phase} logic).
 */
final class BootstrapProjectionsTest {

  @TempDir Path tmp;

  private RuntimeSpecStore specStore(boolean chatEnabled) throws Exception {
    UiSettingsStore store =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    UiSettings initial = new UiSettings();
    initial.setChatEnabled(chatEnabled);
    store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));
    return new RuntimeSpecStore(store);
  }

  @Test
  void nullManager_returnsOfflineDefaultsIncludingNewFields() {
    InferenceRuntimeView view = BootstrapProjections.projectInferenceSnapshot(null, null);

    assertEquals("OFFLINE", view.phase());
    assertFalse(view.chatEnabledSpec());
    assertEquals("", view.engineState());
    assertEquals("", view.engineReason());
    assertEquals("", view.procedure());
    assertEquals("", view.leaseHolder());
  }

  @Test
  void managerPresent_nullReconciler_newFieldsDefaultOldFieldsUnchanged() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    when(manager.isUsingExternalLlamaServer()).thenReturn(false);
    when(manager.identity()).thenReturn(Optional.empty());
    when(manager.lastFailure()).thenReturn(Optional.empty());

    InferenceRuntimeView view = BootstrapProjections.projectInferenceSnapshot(manager, null);

    assertEquals("ONLINE", view.phase(), "pre-existing phase derivation unchanged");
    assertFalse(view.usingExternal());
    assertFalse(view.chatEnabledSpec(), "absent authority -> default false");
    assertEquals("", view.engineState());
    assertEquals("", view.engineReason());
    assertEquals("", view.procedure());
    assertEquals("", view.leaseHolder());
  }

  @Test
  void managerAndReconcilerPresent_projectsRuntimeAuthorityFields() throws Exception {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    when(manager.isUsingExternalLlamaServer()).thenReturn(false);
    when(manager.identity()).thenReturn(Optional.empty());
    when(manager.lastFailure()).thenReturn(Optional.empty());

    FakeControl control = new FakeControl(Mode.ONLINE);
    RuntimeSpecStore spec = specStore(true);
    RuntimeReconciler reconciler =
        new RuntimeReconciler(
            control, control::mode, control::external, control::detach, null, spec, new RuntimeGpuLease());
    reconciler.start();
    assertTrue(reconciler.awaitQuiescent(5_000));

    InferenceRuntimeView view = BootstrapProjections.projectInferenceSnapshot(manager, reconciler);

    assertEquals("ONLINE", view.phase(), "pre-existing phase derivation unchanged");
    assertTrue(view.chatEnabledSpec());
    assertEquals(RuntimeStatus.ENGINE_HEALTHY, view.engineState());
    assertEquals(RuntimeStatus.REASON_ENGINE_HEALTHY, view.engineReason());
    assertEquals("", view.procedure(), "no procedure in flight -> empty, not the literal 'none'");
    assertEquals("CHAT", view.leaseHolder());

    reconciler.close();
  }

  @Test
  void activeProcedure_projectsProcedureKind() throws Exception {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    when(manager.isUsingExternalLlamaServer()).thenReturn(false);
    when(manager.identity()).thenReturn(Optional.empty());
    when(manager.lastFailure()).thenReturn(Optional.empty());

    FakeControl control = new FakeControl(Mode.ONLINE);
    RuntimeSpecStore spec = specStore(true);
    RuntimeReconciler reconciler =
        new RuntimeReconciler(
            control, control::mode, control::external, control::detach, null, spec, new RuntimeGpuLease());
    reconciler.start();
    reconciler.beginProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH, "vdu-test");
    assertTrue(reconciler.awaitQuiescent(5_000));

    InferenceRuntimeView view = BootstrapProjections.projectInferenceSnapshot(manager, reconciler);

    assertEquals("VDU_BATCH", view.procedure());

    reconciler.close();
  }

  /** Minimal {@link OnlineAiLifecycleControl} double, mirroring InferenceCapabilityWiringTest's. */
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
