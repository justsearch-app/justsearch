/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationDispatchPlan;
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

/** Prepared-dispatch tests for the recorded chat-intent writer. */
final class SetChatEnabledHandlerTest {
  @TempDir Path tmp;

  private RuntimeReconciler reconciler(RuntimeSpecStore spec) {
    return new RuntimeReconciler(
        null, () -> Mode.OFFLINE, () -> false, null, null, spec, new RuntimeGpuLease());
  }

  @Test
  void enableWritesOnlyAfterAcceptanceAndRetryDoesNotNudgeAgain() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(tmp.resolve("enable"))) {
      RuntimeSpecStore spec = fixture.spec();
      RuntimeReconciler reconciler = reconciler(spec);
      AtomicInteger nudges = new AtomicInteger();
      reconciler.addSpecChangeListener(nudges::incrementAndGet);
      var handler = new SetChatEnabledHandler(() -> spec, () -> reconciler);
      var dispatch = fixture.dispatcher(CoreOperationCatalog.SET_CHAT_ENABLED.value(), handler);
      assertEquals(OperationKind.SETTINGS_APPLY, dispatch.operation().policy().recordKind());
      String key = OperationKeys.generate(Clock.systemUTC());

      var ready = (OperationDispatchPlan.Ready) dispatch.prepare("{\"enabled\":true}", key);
      assertFalse(spec.load().chatEnabled(), "preparation cannot apply the effect");
      var result = dispatch.dispatch("{\"enabled\":true}", key, ready.preparationNonce());

      assertTrue(result.success());
      assertTrue(spec.load().chatEnabled(), "spec bit persisted true");
      assertTrue(spec.load().chatEnabledExplicit(), "written explicitly");
      assertEquals(1, nudges.get(), "reconciler nudge fired exactly once");
      assertEquals(Boolean.TRUE, result.structuredData().get("chatEnabled"));
      assertEquals("Down", result.structuredData().get("engineState"));

      assertTrue(dispatch.dispatch("{\"enabled\":true}", key).success());
      assertEquals(1, nudges.get(), "recorded retry cannot execute or observe again");
      assertThrows(IllegalStateException.class,
          () -> handler.execute("{\"enabled\":true}", TestEngineContexts.internal()));
    }
  }

  @Test
  void disableWritesSpecFalseAndFiresNudge() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(tmp.resolve("disable"), true)) {
      RuntimeSpecStore spec = fixture.spec();
      RuntimeReconciler reconciler = reconciler(spec);
      AtomicInteger nudges = new AtomicInteger();
      reconciler.addSpecChangeListener(nudges::incrementAndGet);
      var dispatch = fixture.dispatcher(CoreOperationCatalog.SET_CHAT_ENABLED.value(),
          new SetChatEnabledHandler(() -> spec, () -> reconciler));

      var result = dispatch.dispatch("{\"enabled\":false}",
          OperationKeys.generate(Clock.systemUTC()));

      assertTrue(result.success());
      assertFalse(spec.load().chatEnabled());
      assertEquals(1, nudges.get());
      assertEquals(Boolean.FALSE, result.structuredData().get("chatEnabled"));
    }
  }

  @Test
  void stalePreparedFullWitnessRefusesWithoutOverwritingNewerIntent() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(tmp.resolve("stale"), false)) {
      RuntimeSpecStore spec = fixture.spec();
      RuntimeReconciler reconciler = reconciler(spec);
      AtomicInteger nudges = new AtomicInteger();
      reconciler.addSpecChangeListener(nudges::incrementAndGet);
      var dispatch = fixture.dispatcher(CoreOperationCatalog.SET_CHAT_ENABLED.value(),
          new SetChatEnabledHandler(() -> spec, () -> reconciler));
      String staleKey = OperationKeys.generate(Clock.systemUTC());
      var stale = (OperationDispatchPlan.Ready) dispatch.prepare("{\"enabled\":true}", staleKey);

      assertTrue(dispatch.dispatch("{\"enabled\":true}",
          OperationKeys.generate(Clock.systemUTC())).success());
      assertEquals(1, nudges.get());
      var current = fixture.settings().inspect().witness();

      var refused = dispatch.dispatch("{\"enabled\":true}", staleKey, stale.preparationNonce());
      assertEquals("VERSION_CONFLICT", refused.errorCode().orElseThrow());
      assertEquals(current, fixture.settings().inspect().witness(), "stale full witness cannot write");
      assertEquals(1, nudges.get(), "refused stale intent cannot nudge convergence");
    }
  }

  @Test
  void invalidAndUnavailableRequestsFailWithoutRawExecution() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(tmp.resolve("invalid"))) {
      var spec = fixture.spec();
      var validAuthority = fixture.dispatcher(CoreOperationCatalog.SET_CHAT_ENABLED.value(),
          new SetChatEnabledHandler(() -> spec, () -> reconciler(spec)));
      var missing = validAuthority.dispatch("{}", OperationKeys.generate(Clock.systemUTC()));
      assertFalse(missing.success());
      assertTrue(missing.message().contains("enabled"));
      assertFalse(validAuthority.dispatch("{\"enabled\":\"yes\"}",
          OperationKeys.generate(Clock.systemUTC())).success());

      var unavailable = fixture.dispatcher(CoreOperationCatalog.SET_CHAT_ENABLED.value(),
          new SetChatEnabledHandler(() -> null, () -> null));
      var result = unavailable.dispatch("{\"enabled\":true}",
          OperationKeys.generate(Clock.systemUTC()));
      assertFalse(result.success());
      assertTrue(result.message().contains("unavailable"));
    }
  }
}
