/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 882 item 24: publishes the "your settings were reset" condition when {@link
 * UiSettingsStore} recovered from an unreadable settings file, and clears it once the user has
 * re-authored their settings.
 *
 * <p>Shaped exactly like {@code CapabilityHealthBridge.pushCondition} (the one condition-store
 * write pattern in the Head): upsert a {@link HealthEvent} and broadcast only on a real transition.
 * Best-effort: a notice failing must never take the boot down, because the boot is precisely what
 * this change exists to keep alive.
 */
public final class SettingsRecoveryNotice {

  private static final Logger log = LoggerFactory.getLogger(SettingsRecoveryNotice.class);

  /** Condition id and reason slot: the closed readiness vocabulary, not a local literal. */
  public static final String CONDITION_ID = LifecycleReasonCode.SETTINGS_RESET_FROM_CORRUPT.code();

  /** The condition subject: the settings store, not a capability. */
  public static final String SUBJECT = "settings";

  /**
   * k8s PascalCase reason (see {@code AssertedCondition.REASON_PATTERN}, which rejects the dotted
   * code form). The dotted code travels as the condition {@code id} above.
   */
  private static final String REASON = "SettingsResetFromCorrupt";

  private SettingsRecoveryNotice() {}

  /** Observe the fixed owner's first unresolved issue, including one reported before Health existed. */
  public static void observeCommitRecovery(SettingsCommitOwner owner, ConditionStore conditionStore,
      HealthEventChangeRegistry changeRegistry, Source source, Clock clock) {
    var unused = owner.recoveryIssue().thenAccept(issue -> {
      try {
        String reason = switch (issue.reason()) {
          case UNREADABLE_WITNESS -> "SettingsWitnessUnreadable";
          case CONTRADICTORY_WITNESS -> "SettingsWitnessContradictory";
          case INVALID_PREPARATION -> "SettingsPreparationInvalid";
          case COMPOSITION_FAILED -> "SettingsCompositionFailed";
          case MULTIPLE_ARMED_ROWS -> "MultipleSettingsCommits";
          case PERSISTENCE_DISABLED -> "SettingsPersistenceDisabled";
        };
        String message = switch (issue.reason()) {
          case UNREADABLE_WITNESS -> "Settings history cannot be verified. Settings changes are paused until recovery completes.";
          case CONTRADICTORY_WITNESS -> "Saved settings disagree with an unfinished change. Settings changes are paused to preserve the available history.";
          case INVALID_PREPARATION -> "An unfinished settings change has invalid accepted preparation. Settings changes are paused.";
          case COMPOSITION_FAILED -> "An unfinished settings change could not restore its runtime component. Settings changes are paused.";
          case MULTIPLE_ARMED_ROWS -> "Several settings changes have unresolved outcomes. Settings changes are paused to preserve the available history.";
          case PERSISTENCE_DISABLED -> "An unfinished settings change needs writable storage for recovery. Settings changes are paused.";
        };
        String code = LifecycleReasonCode.SETTINGS_RECOVERY_REQUIRED.code();
        Instant now = Instant.now(clock);
        var condition = new AssertedCondition(SUBJECT, ConditionStatus.TRUE, reason, now,
            Optional.of(message), Optional.empty(), List.of());
        var event = new HealthEvent(code, now, source, Severity.ERROR, Optional.of(code), condition);
        var transition = conditionStore.upsert(event);
        if (transition != ConditionStore.Transition.UNCHANGED) {
          changeRegistry.broadcast(transition == ConditionStore.Transition.ADDED
              ? HealthEventChangeRegistry.Kind.CONDITION_ADDED : HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, event);
        }
      } catch (RuntimeException publicationFailure) {
        log.error("Failed to publish settings recovery condition: reason={} record={}",
            issue.reason(), issue.operationRecordId(), publicationFailure);
      }
    });
  }

  /** Assert the condition. The message names the backup FILE only, never its full path. */
  public static void publish(
      ConditionStore conditionStore,
      HealthEventChangeRegistry changeRegistry,
      UiSettingsStore.RecoveredFromCorrupt recovery,
      Source headSource,
      Clock clock) {
    try {
      String backupName = String.valueOf(recovery.backupPath().getFileName());
      AssertedCondition condition =
          new AssertedCondition(
              SUBJECT,
              ConditionStatus.TRUE,
              REASON,
              Instant.now(clock),
              Optional.of(
                  "Your settings file could not be read and was reset to defaults. "
                      + "The original was kept next to it as "
                      + backupName
                      + "."),
              Optional.empty(),
              List.of());
      HealthEvent event =
          new HealthEvent(
              CONDITION_ID,
              Instant.now(clock),
              headSource,
              Severity.WARNING,
              Optional.of(CONDITION_ID),
              condition);
      ConditionStore.Transition transition = conditionStore.upsert(event);
      if (transition != ConditionStore.Transition.UNCHANGED) {
        changeRegistry.broadcast(
            transition == ConditionStore.Transition.ADDED
                ? HealthEventChangeRegistry.Kind.CONDITION_ADDED
                : HealthEventChangeRegistry.Kind.CONDITION_MODIFIED,
            event);
      }
    } catch (Exception e) {
      log.debug("Failed to publish settings-recovery condition: {}", e.getMessage());
    }
  }

  /** Clear the condition once a successful save has superseded the reset. */
  public static void clear(ConditionStore conditionStore, HealthEventChangeRegistry changeRegistry) {
    try {
      conditionStore
          .clear(CONDITION_ID, SUBJECT)
          .ifPresent(
              removed ->
                  changeRegistry.broadcast(
                      HealthEventChangeRegistry.Kind.CONDITION_REMOVED, removed));
    } catch (Exception e) {
      log.debug("Failed to clear settings-recovery condition: {}", e.getMessage());
    }
  }
}
