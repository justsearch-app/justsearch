/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.operations;

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

/** Tempdoc 882 item 24: the operation-history recovery notice asserts on the condition store. */
class OperationRecoveryNoticeTest {

  private static final Source HEAD = new Source("head", "test-instance", Optional.empty());

  @Test
  @DisplayName("publish asserts the closed-vocabulary condition naming only the backup file name")
  void publishAssertsCondition() {
    ConditionStore store = new ConditionStore();
    var recovery =
        new io.justsearch.app.api.operations.OperationStore.Recovery(
            Path.of("C:", "data", "ui", "operations.db.corrupt-20260912"), 1_789_203_600_001L);

    OperationRecoveryNotice.publish(
        store, new HealthEventChangeRegistry(), recovery, HEAD, Clock.systemUTC());

    Optional<HealthEvent> found =
        store.find(LifecycleReasonCode.OPERATIONS_HISTORY_RESET.code(), "operations");
    assertTrue(found.isPresent(), "the condition must be asserted under the reason code as its id");
    assertEquals(Severity.WARNING, found.get().severity());
    AssertedCondition condition = (AssertedCondition) found.get().body();
    String message = condition.message().orElseThrow();
    assertTrue(
        message.contains("operations.db.corrupt-20260912"),
        "the backup file name is the actionable part: " + message);
    assertTrue(message.contains(java.time.Instant.ofEpochMilli(recovery.historySinceMillis()).toString()));
    assertTrue(message.contains("Earlier outcomes cannot be recovered"));
    // The full path is machine-local noise in a user-facing sentence, and leaks the data dir.
    assertFalse(
        message.contains(recovery.preservedDirectory().toString()),
        "the message must name the file, not the full path: " + message);
  }

}
