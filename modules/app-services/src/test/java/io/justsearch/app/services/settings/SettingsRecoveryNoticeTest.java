/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 882 item 24: the settings-reset notice asserts and clears on the condition store. */
class SettingsRecoveryNoticeTest {

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(io.justsearch.app.api.settings.SettingsCommitOwner.RecoveryReason.class)
  void earlyAndLateOwnerFailuresRemainStickyAfterCorruptionNoticeClears(
      io.justsearch.app.api.settings.SettingsCommitOwner.RecoveryReason reason) {
    for (boolean alreadyCompleted : new boolean[] {false, true}) {
      var signal = new java.util.concurrent.CompletableFuture<io.justsearch.app.api.settings.SettingsCommitOwner.RecoveryIssue>();
      var owner = org.mockito.Mockito.mock(io.justsearch.app.api.settings.SettingsCommitOwner.class);
      org.mockito.Mockito.when(owner.recoveryIssue()).thenReturn(signal.minimalCompletionStage());
      var issue = new io.justsearch.app.api.settings.SettingsCommitOwner.RecoveryIssue(reason, 42L);
      var store = new ConditionStore(); var changes = new HealthEventChangeRegistry();
      if (alreadyCompleted) signal.complete(issue);
      SettingsRecoveryNotice.observeCommitRecovery(owner, store, changes, HEAD, Clock.systemUTC());
      if (!alreadyCompleted) { assertEquals(0, changes.currentSeq()); signal.complete(issue); }
      String code = LifecycleReasonCode.SETTINGS_RECOVERY_REQUIRED.code();
      var event = store.find(code, "settings").orElseThrow();
      assertEquals(Severity.ERROR, event.severity());
      var condition = (AssertedCondition) event.body();
      String expectedReason = switch (reason) {
        case UNREADABLE_WITNESS -> "SettingsWitnessUnreadable";
        case CONTRADICTORY_WITNESS -> "SettingsWitnessContradictory";
        case INVALID_PREPARATION -> "SettingsPreparationInvalid";
        case COMPOSITION_FAILED -> "SettingsCompositionFailed";
        case MULTIPLE_ARMED_ROWS -> "MultipleSettingsCommits";
        case PERSISTENCE_DISABLED -> "SettingsPersistenceDisabled";
      };
      assertEquals(expectedReason, condition.reason());
      assertTrue(condition.message().orElseThrow().contains("Settings changes are paused"));
      assertEquals(1, changes.currentSeq());
      SettingsRecoveryNotice.clear(store, changes);
      assertTrue(store.find(code, "settings").isPresent());
      assertEquals(1, changes.currentSeq());
      assertEquals(io.justsearch.app.api.lifecycle.RetentionClass.STICKY,
          LifecycleReasonCode.SETTINGS_RECOVERY_REQUIRED.retentionClass());
    }
  }

  private static final Source HEAD = new Source("head", "test-instance", Optional.empty());

  @Test
  @DisplayName("publish asserts the closed-vocabulary condition naming only the backup file name")
  void publishAssertsCondition() {
    ConditionStore store = new ConditionStore();
    var recovery =
        new UiSettingsStore.RecoveredFromCorrupt(
            Path.of("C:", "data", "ui", "settings.json.corrupt-20260901-101112"), "expected a JSON object");

    SettingsRecoveryNotice.publish(
        store, new HealthEventChangeRegistry(), recovery, HEAD, Clock.systemUTC());

    Optional<HealthEvent> found =
        store.find(LifecycleReasonCode.SETTINGS_RESET_FROM_CORRUPT.code(), "settings");
    assertTrue(found.isPresent(), "the condition must be asserted under the reason code as its id");
    assertEquals(Severity.WARNING, found.get().severity());
    AssertedCondition condition = (AssertedCondition) found.get().body();
    String message = condition.message().orElseThrow();
    assertTrue(
        message.contains("settings.json.corrupt-20260901-101112"),
        "the backup file name is the actionable part: " + message);
    // The full path is machine-local noise in a user-facing sentence, and leaks the data dir.
    assertFalse(
        message.contains(recovery.backupPath().toString()),
        "the message must name the file, not the full path: " + message);
  }

  @Test
  @DisplayName("clear removes the condition a prior publish asserted")
  void clearRemovesCondition() {
    ConditionStore store = new ConditionStore();
    HealthEventChangeRegistry changes = new HealthEventChangeRegistry();
    var recovery =
        new UiSettingsStore.RecoveredFromCorrupt(
            Path.of("settings.json.corrupt-20260901-101112"), "cannot parse");
    SettingsRecoveryNotice.publish(store, changes, recovery, HEAD, Clock.systemUTC());

    SettingsRecoveryNotice.clear(store, changes);

    assertTrue(
        store.find(LifecycleReasonCode.SETTINGS_RESET_FROM_CORRUPT.code(), "settings").isEmpty(),
        "the notice must come down once the user re-authors settings");
  }
}
