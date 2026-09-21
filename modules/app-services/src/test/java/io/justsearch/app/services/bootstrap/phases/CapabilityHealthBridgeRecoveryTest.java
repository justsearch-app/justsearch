/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.worker.RecoveryContext;
import io.justsearch.app.services.worker.RecoveryOccurrence;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Recovery events come from the monitor's direct decision stream, not readiness inference. */
@DisplayName("CapabilityHealthBridge — direct recovery occurrences")
final class CapabilityHealthBridgeRecoveryTest {

  private static final Source HEAD = Source.forProcess("head", "instance-1", "1.0");

  @Test
  void attemptedOccurrenceRendersMonitorForensicContextAndBroadcasts() {
    OccurrenceLog occurrences = new OccurrenceLog();
    HealthEventChangeRegistry changes = new HealthEventChangeRegistry();

    CapabilityHealthBridge.emitRecoveryOccurrence(
        new RecoveryOccurrence(
            RecoveryOccurrence.Kind.ATTEMPTED, new RecoveryContext(2, "boot", 2000)),
        occurrences,
        changes,
        HEAD);

    HealthEvent event = onlyEvent(occurrences, "worker.restart-attempted");
    LifecycleEvent body = (LifecycleEvent) event.body();
    assertEquals(2, body.attributes().get("attempt"));
    assertEquals("boot", body.attributes().get("faultKind"));
    assertEquals(2000L, body.attributes().get("backoffMs"));
    assertEquals("health-events.worker.restart-attempted.message",
        event.i18nKey().orElseThrow());
    assertEquals(1L, changes.currentSeq(), "the appended occurrence is broadcast once");
  }

  @Test
  void recoveredOccurrenceRendersAttemptWithoutInventingFaultAttributes() {
    OccurrenceLog occurrences = new OccurrenceLog();
    HealthEventChangeRegistry changes = new HealthEventChangeRegistry();

    CapabilityHealthBridge.emitRecoveryOccurrence(
        new RecoveryOccurrence(
            RecoveryOccurrence.Kind.RECOVERED, new RecoveryContext(3, "boot", 4000)),
        occurrences,
        changes,
        HEAD);

    LifecycleEvent body =
        (LifecycleEvent) onlyEvent(occurrences, "worker.recovered").body();
    assertEquals(1, body.attributes().size());
    assertEquals(3, body.attributes().get("recoveredAfterAttempts"));
  }

  @Test
  void registryTransitionsNeverFabricateRecoveryOccurrences() throws Exception {
    HealthEventChangeRegistry changes = new HealthEventChangeRegistry();
    var kinds = new ArrayList<HealthEventChangeRegistry.Kind>();
    var subscription = changes.subscribeTyped(change -> kinds.add(change.kind()));
    try {
      try (var components = TestEngineComponents.fourComponents();
          AutoCloseable ignored = CapabilityHealthBridge.wireListeners(
              components,
              new ConditionStore(),
              changes,
              HEAD)) {
        components.handle("index").transition(
            ComponentState.STARTING, "worker.starting", "attempt in progress");
        components.handle("index").transition(
            ComponentState.FAILED, "worker.spawn_recovery_exhausted", "attempts exhausted");
        components.handle("index").transition(ComponentState.READY, null, "recovered");

        assertTrue(
            kinds.stream().noneMatch(
                kind -> kind == HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED),
            "coalesced readiness is not an event-history authority");
      }
    } finally {
      subscription.unsubscribe();
    }
  }

  private static HealthEvent onlyEvent(OccurrenceLog log, String expectedId) {
    assertEquals(1, log.recent().size());
    HealthEvent event = log.recent().getFirst();
    assertEquals(expectedId, event.id());
    return event;
  }
}
