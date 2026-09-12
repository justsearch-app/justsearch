/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.operations;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
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

/** Publishes loss of operation outcome history after preserving an unreadable database. */
public final class OperationRecoveryNotice {

  private static final Logger log = LoggerFactory.getLogger(OperationRecoveryNotice.class);

  /** Condition id and reason slot: the closed readiness vocabulary, not a local literal. */
  public static final String CONDITION_ID = LifecycleReasonCode.OPERATIONS_HISTORY_RESET.code();

  /** The condition subject: the operations store, not a capability. */
  public static final String SUBJECT = "operations";

  /**
   * k8s PascalCase reason (see {@code AssertedCondition.REASON_PATTERN}, which rejects the dotted
   * code form). The dotted code travels as the condition {@code id} above.
   */
  private static final String REASON = "OperationsHistoryReset";

  private OperationRecoveryNotice() {}

  /** Assert the condition. The message names the backup directory only, never its full path. */
  public static void publish(
      ConditionStore conditionStore,
      HealthEventChangeRegistry changeRegistry,
      io.justsearch.app.api.operations.OperationStore.Recovery recovery,
      Source headSource,
      Clock clock) {
    try {
      String backupName = String.valueOf(recovery.preservedDirectory().getFileName());
      AssertedCondition condition =
          new AssertedCondition(
              SUBJECT,
              ConditionStatus.TRUE,
              REASON,
              Instant.now(clock),
              Optional.of(
                  "Operation history could not be read and was reset. "
                      + "The original was kept next to it as "
                      + backupName
                      + ". Earlier outcomes cannot be recovered; new operation keys are accepted after "
                      + Instant.ofEpochMilli(recovery.historySinceMillis()) + "."),
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
      log.debug("Failed to publish operation-history recovery condition: {}", e.getMessage());
    }
  }

}
