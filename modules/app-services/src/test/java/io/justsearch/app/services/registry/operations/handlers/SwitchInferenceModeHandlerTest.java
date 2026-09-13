/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.runtimestate.RuntimeGpuLease;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prepared-dispatch tests for the legacy inference-mode alias. */
final class SwitchInferenceModeHandlerTest {
  @TempDir Path tmp;

  private RuntimeReconciler reconciler(RuntimeSpecStore spec) {
    return new RuntimeReconciler(
        null, () -> Mode.OFFLINE, () -> false, null, null, spec, new RuntimeGpuLease());
  }

  @Test
  void onlineMapsToChatEnabledTrueAndRetryDoesNotNudgeAgain() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(tmp.resolve("online"))) {
      RuntimeSpecStore spec = fixture.spec();
      RuntimeReconciler reconciler = reconciler(spec);
      AtomicInteger nudges = new AtomicInteger();
      reconciler.addSpecChangeListener(nudges::incrementAndGet);
      var handler = new SwitchInferenceModeHandler(() -> spec, () -> reconciler);
      var dispatch = fixture.dispatcher(CoreOperationCatalog.SWITCH_INFERENCE_MODE.value(), handler);
      assertEquals(OperationKind.SETTINGS_APPLY, dispatch.operation().policy().recordKind());
      String key = OperationKeys.generate(Clock.systemUTC());

      var result = dispatch.dispatch("{\"mode\":\"online\"}", key);

      assertTrue(result.success());
      assertTrue(spec.load().chatEnabled(), "online maps to chatEnabled true");
      assertEquals(1, nudges.get());
      assertEquals(Boolean.TRUE, result.structuredData().get("chatEnabled"));
      assertTrue(dispatch.dispatch("{\"mode\":\"online\"}", key).success());
      assertEquals(1, nudges.get(), "recorded alias retry cannot nudge twice");
      assertThrows(IllegalStateException.class,
          () -> handler.execute("{\"mode\":\"online\"}", TestEngineContexts.internal()));
    }
  }

  @Test
  void indexingMapsToChatEnabledFalse() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(tmp.resolve("indexing"), true)) {
      RuntimeSpecStore spec = fixture.spec();
      RuntimeReconciler reconciler = reconciler(spec);
      var dispatch = fixture.dispatcher(CoreOperationCatalog.SWITCH_INFERENCE_MODE.value(),
          new SwitchInferenceModeHandler(() -> spec, () -> reconciler));

      var result = dispatch.dispatch("{\"mode\":\"indexing\"}",
          OperationKeys.generate(Clock.systemUTC()));

      assertTrue(result.success());
      assertFalse(spec.load().chatEnabled());
      assertEquals(Boolean.FALSE, result.structuredData().get("chatEnabled"));
    }
  }

  @Test
  void invalidModesFailThroughDispatcher() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(tmp.resolve("invalid"))) {
      RuntimeSpecStore spec = fixture.spec();
      var dispatch = fixture.dispatcher(CoreOperationCatalog.SWITCH_INFERENCE_MODE.value(),
          new SwitchInferenceModeHandler(() -> spec, () -> reconciler(spec)));

      var unknown = dispatch.dispatch("{\"mode\":\"bananas\"}",
          OperationKeys.generate(Clock.systemUTC()));
      assertFalse(unknown.success());
      assertEquals("BAD_REQUEST", unknown.errorCode().orElseThrow(), "catalog validation precedes handler preparation");
      var invalid = assertThrows(io.justsearch.agent.api.registry.OperationPreparationRefused.class,
          () -> new SwitchInferenceModeHandler(() -> spec, () -> reconciler(spec))
              .prepare("{\"mode\":\"bananas\"}", null, TestEngineContexts.internal()));
      assertTrue(invalid.refusal().message().contains("Invalid mode"));
      var missing = dispatch.dispatch("{}", OperationKeys.generate(Clock.systemUTC()));
      assertFalse(missing.success());
      assertTrue(missing.message().contains("mode"));
    }
  }
}
