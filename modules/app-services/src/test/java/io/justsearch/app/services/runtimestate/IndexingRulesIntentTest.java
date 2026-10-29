/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.services.registry.operations.handlers.IndexingPauseHandler;
import io.justsearch.core.scheduling.IndexingHold;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** D4: accepted UI-only rules mutation uses witness/key/replay and preserves manual intent. */
final class IndexingRulesIntentTest {
  @TempDir Path directory;
  private final JsonMapper json = JsonMapper.builder().build();
  private static final String RULES = "{\"schedule\":{\"enabled\":true,\"days\":[1],"
      + "\"startMinute\":600,\"endMinute\":660},\"pauseOnBattery\":true}";
  private String key() { return OperationKeys.generate(Clock.systemUTC()); }
  private OperationHandler handler(RuntimeSpecStore store) {
    return assertDoesNotThrow(() -> (OperationHandler) Class.forName(
        "io.justsearch.app.services.registry.operations.handlers.IndexingRulesHandler")
        .getConstructor(Supplier.class).newInstance((Supplier<RuntimeSpecStore>) () -> store));
  }

  @Test
  void acceptedRulesPauseResumeUnrelatedWritesAndReplayPreserveEachOther() throws Exception {
    try (var f = new RuntimeIntentTestFixture(directory)) {
      var hold = new IndexingHold();
      f.settings().bindIndexingHold(hold);
      var rules = f.dispatcher("core.set-indexing-rules", handler(f.spec()));
      var pause = f.dispatcher("core.indexing-pause", new IndexingPauseHandler(f::spec, true));
      var resume = f.dispatcher("core.indexing-resume", new IndexingPauseHandler(f::spec, false));
      long deadline = System.currentTimeMillis() + 60_000;
      assertTrue(pause.dispatch("{\"untilEpochMs\":" + deadline + "}", key()).success());
      String replay = key();
      assertTrue(rules.dispatch(RULES, replay).success());
      var saved = json.valueToTree(f.settings().load()).get("indexingPause");
      assertEquals(deadline, saved.get("untilEpochMs").longValue());
      assertTrue(saved.get("paused").booleanValue());
      assertEquals(json.readTree(RULES).get("schedule"), saved.get("schedule"));
      long revision = f.settings().inspect().witness().acceptedRevision();
      assertTrue(rules.dispatch(RULES, replay).success());
      assertEquals(revision, f.settings().inspect().witness().acceptedRevision());
      assertThrows(io.justsearch.app.api.operations.OperationStoreException.class,
          () -> rules.dispatch("{\"schedule\":null,\"pauseOnBattery\":false}", replay));
      assertTrue(resume.dispatch("{}", key()).success());
      var resumed = json.valueToTree(f.settings().load()).get("indexingPause");
      assertFalse(resumed.get("paused").booleanValue());
      assertEquals(saved.get("schedule"), resumed.get("schedule"));
      assertTrue(resumed.get("pauseOnBattery").booleanValue());
      assertTrue(f.spec().writeIntent(true, io.justsearch.app.services.TestEngineContexts.internal(), null)
          .response().success());
      assertEquals(resumed, json.valueToTree(f.settings().load()).get("indexingPause"));
      assertTrue(pause.dispatch("{}", key()).success());
      assertEquals(saved.get("schedule"), json.valueToTree(f.settings().load()).get("indexingPause").get("schedule"));
      assertTrue(rules.dispatch("{\"schedule\":null,\"pauseOnBattery\":false}", key()).success());
      assertTrue(hold.held(), "clearing rules preserves manual pause");
    }
  }

  @Test
  void rulesPreserveSavedRecoveryAndExpiredManualIntentAcrossRestart() throws Exception {
    for (String reason : List.of("SETTINGS_RECOVERY", "USER_PAUSED")) {
      var folder = java.nio.file.Files.createDirectories(directory.resolve(reason));
      var settings = new io.justsearch.app.services.settings.UiSettingsStore(
          io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.READ_WRITE,
          folder.resolve("settings.json"));
      var initial = json.readValue("{\"indexingPause\":{\"paused\":true,\"untilEpochMs\":"
          + (reason.equals("USER_PAUSED") ? "1" : "null") + ",\"reason\":\"" + reason + "\"}}",
          io.justsearch.app.api.UiSettings.class);
      settings.replacePrepared(settings.prepare(initial, new io.justsearch.app.api.settings.SettingsWitness(0, null)));
      var hold = new IndexingHold();
      settings.bindIndexingHold(hold);
      hold.setLocalTime(() -> new IndexingHold.LocalMinute(1, 500));
      try (var f = new RuntimeIntentTestFixture(folder, settings,
          new io.justsearch.configuration.resolved.ConfigStore(
              io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(initial)))) {
        var rules = f.dispatcher("core.set-indexing-rules", handler(f.spec()));
        assertTrue(rules.dispatch(RULES, key()).success());
        assertEquals(reason, f.settings().load().getIndexingPause().reason());
        assertTrue(f.settings().load().getIndexingPause().paused(), "effective expiry is never written back as intent");
        assertEquals(reason.equals("SETTINGS_RECOVERY") ? "SETTINGS_RECOVERY" : "SCHEDULE", hold.snapshot().reason());
      }
      var restarted = new io.justsearch.app.services.settings.UiSettingsStore(
          io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.READ_WRITE, settings.settingsPath());
      restarted.bindIndexingHold(hold);
      hold.setLocalTime(() -> new IndexingHold.LocalMinute(1, 500));
      assertEquals(reason, restarted.load().getIndexingPause().reason());
      assertEquals(json.readTree(RULES).get("schedule"), json.valueToTree(restarted.load().getIndexingPause()).get("schedule"));
      assertEquals(reason.equals("SETTINGS_RECOVERY") ? "SETTINGS_RECOVERY" : "SCHEDULE", hold.snapshot().reason());
    }
  }

  @Test
  void invalidRulesAreRefusedAtomicallyAndStalePreparationConflicts() throws Exception {
    try (var f = new RuntimeIntentTestFixture(directory)) {
      var rules = f.dispatcher("core.set-indexing-rules", handler(f.spec()));
      assertTrue(rules.dispatch(RULES, key()).success());
      var saved = f.settings().inspect();
      for (String invalid : List.of("{}", "{\"schedule\":null}", "{\"pauseOnBattery\":false}",
          RULES.replace("true}", "null}"), RULES.replace("600", "660"),
          RULES.replace("[1]", "[]"), RULES.replace("[1]", "[1,1]"),
          RULES.replace("[1]", "[8]"), RULES.replace("[1]", "true"), RULES.replace("600", "\"600\""),
          RULES.replace("\"enabled\":true", "\"enabled\":true,\"enabled\":false"),
          RULES.replace("\"enabled\":true", "\"unknown\":true,\"enabled\":true"))) {
        var refused = rules.dispatch(invalid, key());
        assertFalse(refused.success(), invalid);
        assertEquals("BAD_REQUEST", refused.errorCode().orElseThrow(), invalid);
        assertTrue(refused.errorDetails().get("field") instanceof String
            || refused.errorDetails().get("violations") instanceof List<?> violations && !violations.isEmpty(),
            "refusal names the invalid field through the handler or existing schema violation details");
        assertEquals(saved.witness(), f.settings().inspect().witness());
        assertEquals(json.valueToTree(saved.settings()), json.valueToTree(f.settings().load()));
      }
      String preparedKey = key();
      var plan = (io.justsearch.agent.api.registry.OperationDispatchPlan.Ready) rules.prepare(RULES, preparedKey);
      assertTrue(f.spec().writeIntent(true, io.justsearch.app.services.TestEngineContexts.internal(), null)
          .response().success());
      var result = rules.dispatch(RULES, preparedKey, plan.preparationNonce());
      assertFalse(result.success());
      assertEquals("VERSION_CONFLICT", result.errorCode().orElseThrow());
    }
  }
}
