/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.Mode;
import io.justsearch.app.services.runtimestate.RuntimeGpuLease;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the superseded {@link SwitchInferenceModeHandler} alias (tempdoc 737 §12b): it maps
 * {@code online}/{@code indexing} onto the {@code chatEnabled} spec write through the SAME path as
 * {@link SetChatEnabledHandler}, no longer touching {@code BrainRuntimeService} directly.
 */
final class SwitchInferenceModeHandlerTest {

  @TempDir Path tmp;

  private RuntimeSpecStore specStore() {
    return new RuntimeSpecStore(
        new UiSettingsStore(
            UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json")));
  }

  private RuntimeReconciler reconciler(RuntimeSpecStore spec) {
    return new RuntimeReconciler(
        null, () -> Mode.OFFLINE, () -> false, null, null, spec, new RuntimeGpuLease());
  }

  @Test
  void onlineMapsToChatEnabledTrue() {
    RuntimeSpecStore spec = specStore();
    RuntimeReconciler r = reconciler(spec);
    AtomicInteger nudges = new AtomicInteger();
    r.addSpecChangeListener(nudges::incrementAndGet);

    OperationResult result =
        new SwitchInferenceModeHandler(() -> spec, () -> r).execute("{\"mode\":\"online\"}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertTrue(spec.load().chatEnabled(), "online → chatEnabled true");
    assertEquals(1, nudges.get(), "routed through the spec-write path (nudge fired)");
    assertEquals(Boolean.TRUE, result.structuredData().get("chatEnabled"));
  }

  @Test
  void indexingMapsToChatEnabledFalse() {
    RuntimeSpecStore spec = specStore();
    spec.setChatEnabled(true);
    RuntimeReconciler r = reconciler(spec);

    OperationResult result =
        new SwitchInferenceModeHandler(() -> spec, () -> r).execute("{\"mode\":\"indexing\"}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertFalse(spec.load().chatEnabled(), "indexing → chatEnabled false");
    assertEquals(Boolean.FALSE, result.structuredData().get("chatEnabled"));
  }

  @Test
  void unknownModeFails() {
    RuntimeSpecStore spec = specStore();
    OperationResult result =
        new SwitchInferenceModeHandler(() -> spec, () -> reconciler(spec))
            .execute("{\"mode\":\"bananas\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("Invalid mode"));
  }

  @Test
  void missingModeFails() {
    RuntimeSpecStore spec = specStore();
    OperationResult result =
        new SwitchInferenceModeHandler(() -> spec, () -> reconciler(spec)).execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("mode"));
  }
}
