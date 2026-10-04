/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Round-trip tests for {@link RuntimeSpecStore} over a temp-dir {@link UiSettingsStore}. */
final class RuntimeSpecStoreTest {

  @TempDir Path tmp;

  private UiSettingsStore store() {
    return new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
  }

  @Test
  void freshProfileIsUnsetAndResolvesFalse() {
    RuntimeSpec spec = new RuntimeSpecStore(store()).load();
    assertFalse(spec.chatEnabled(), "null resolves to false (autostart-default-false)");
    assertFalse(spec.chatEnabledExplicit(), "never persisted");
  }

  @Test
  void setChatEnabledPersistsAndSurvivesReload() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(tmp.resolve("set"))) {
      fixture.spec().setChatEnabled(true);

      RuntimeSpec reloaded = new RuntimeSpecStore(fixture.settings()).load();
      assertTrue(reloaded.chatEnabled());
      assertTrue(reloaded.chatEnabledExplicit());
    }
  }

  @Test
  void recordUserEnabledSetsTrueOnFreshProfile() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(tmp.resolve("record"))) {
      fixture.spec().recordUserEnabled();
      assertTrue(new RuntimeSpecStore(fixture.settings()).load().chatEnabled());
    }
  }

  @Test
  void seedAutostartSeedsOnlyWhenUnset() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(tmp.resolve("seed"))) {
      RuntimeSpecStore spec = fixture.spec();

      assertTrue(spec.seedAutostartIfUnset(), "seeds a fresh profile");
      assertTrue(spec.load().chatEnabled());
      assertFalse(spec.seedAutostartIfUnset(), "does not re-seed once explicit");
    }
  }

  @Test
  void seedAutostartDoesNotOverrideExplicitOff() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(tmp.resolve("explicit-off"), false)) {
      RuntimeSpecStore spec = fixture.spec();

      assertFalse(spec.seedAutostartIfUnset(), "explicit off is not overridden by the env seed");
      assertFalse(spec.load().chatEnabled());
      assertTrue(spec.load().chatEnabledExplicit());
    }
  }

  @Test
  void keyedRetryDoesNotRepeatObservationAndChangedTargetCannotReuseKey() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(tmp.resolve("keyed"))) {
      var observations = new AtomicInteger();
      String key = OperationKeys.generate(Clock.systemUTC());
      var first = fixture.spec().writeIntent(true, TestEngineContexts.internal(), key,
          observations::incrementAndGet);
      assertTrue(first.response().success());
      assertEquals(1, observations.get());

      var replay = fixture.spec().writeIntent(true, TestEngineContexts.internal(), key,
          observations::incrementAndGet);
      assertTrue(replay.response().success());
      assertEquals(1, observations.get(), "a recorded key cannot run its observation twice");
      assertEquals(1, fixture.settings().inspect().witness().acceptedRevision());

      var reused = assertThrows(OperationStoreException.class,
          () -> fixture.spec().writeIntent(false, TestEngineContexts.internal(), key));
      assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED, reused.code());
      assertTrue(fixture.spec().load().chatEnabled(), "changed public input cannot reuse the key");
    }
  }
}
