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
 * Tests for {@link SetChatEnabledHandler} (tempdoc 737 §12b): the intent write persists the
 * {@code chatEnabled} spec bit and fires the reconciler nudge; it carries no preconditions.
 */
final class SetChatEnabledHandlerTest {

  @TempDir Path tmp;

  private RuntimeSpecStore specStore() {
    return new RuntimeSpecStore(
        new UiSettingsStore(
            UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json")));
  }

  /** A reconciler without a running thread — enough for specChanged()/currentSpec()/current(). */
  private RuntimeReconciler reconciler(RuntimeSpecStore spec) {
    return new RuntimeReconciler(
        null, () -> Mode.OFFLINE, () -> false, null, null, spec, new RuntimeGpuLease());
  }

  @Test
  void enableWritesSpecTrueAndFiresNudge() {
    RuntimeSpecStore spec = specStore();
    RuntimeReconciler r = reconciler(spec);
    AtomicInteger nudges = new AtomicInteger();
    r.addSpecChangeListener(nudges::incrementAndGet);

    SetChatEnabledHandler handler = new SetChatEnabledHandler(() -> spec, () -> r);
    OperationResult result = handler.execute("{\"enabled\":true}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertTrue(spec.load().chatEnabled(), "spec bit persisted true");
    assertTrue(spec.load().chatEnabledExplicit(), "written explicitly");
    assertEquals(1, nudges.get(), "reconciler nudge fired exactly once");
    assertEquals(Boolean.TRUE, result.structuredData().get("chatEnabled"));
    assertEquals("Down", result.structuredData().get("engineState"), "observed engine still down");
  }

  @Test
  void disableWritesSpecFalseAndFiresNudge() {
    RuntimeSpecStore spec = specStore();
    spec.setChatEnabled(true); // start enabled
    RuntimeReconciler r = reconciler(spec);
    AtomicInteger nudges = new AtomicInteger();
    r.addSpecChangeListener(nudges::incrementAndGet);

    SetChatEnabledHandler handler = new SetChatEnabledHandler(() -> spec, () -> r);
    OperationResult result = handler.execute("{\"enabled\":false}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertFalse(spec.load().chatEnabled(), "spec bit persisted false");
    assertEquals(1, nudges.get());
    assertEquals(Boolean.FALSE, result.structuredData().get("chatEnabled"));
  }

  @Test
  void missingEnabledArgFails() {
    RuntimeSpecStore spec = specStore();
    SetChatEnabledHandler handler = new SetChatEnabledHandler(() -> spec, () -> reconciler(spec));
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("enabled"));
  }

  @Test
  void nonBooleanEnabledArgFails() {
    RuntimeSpecStore spec = specStore();
    SetChatEnabledHandler handler = new SetChatEnabledHandler(() -> spec, () -> reconciler(spec));
    OperationResult result = handler.execute("{\"enabled\":\"yes\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
  }

  @Test
  void unavailableRuntimeAuthorityFailsGracefully() {
    SetChatEnabledHandler handler = new SetChatEnabledHandler(() -> null, () -> null);
    OperationResult result = handler.execute("{\"enabled\":true}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("unavailable"));
  }
}
