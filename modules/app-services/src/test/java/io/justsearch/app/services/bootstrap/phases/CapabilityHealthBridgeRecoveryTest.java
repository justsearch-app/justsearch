package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.services.worker.RecoveryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 627: the supervised-recovery loop self-narrates as one-shot occurrences in the RECENT EVENTS
 * stream. This pins that {@link CapabilityHealthBridge} emits the milestone occurrences off the worker
 * capability transitions it observes, and — critically — that the terminal give-up does NOT emit an
 * occurrence (it is left to the persistent condition, avoiding a double-render).
 */
@DisplayName("CapabilityHealthBridge — recovery occurrences")
final class CapabilityHealthBridgeRecoveryTest {

  private static final Source HEAD = Source.forProcess("head", "instance-1", "1.0");

  private OccurrenceLog occurrences;
  private WorkerCapability worker;

  @BeforeEach
  void setUp() {
    ConditionStore conditions = new ConditionStore();
    occurrences = new OccurrenceLog();
    HealthEventChangeRegistry changes = new HealthEventChangeRegistry();
    worker = new WorkerCapability();
    CapabilityHealthBridge.wireListeners(
        worker, new InferenceCapability(true), conditions, changes, HEAD, occurrences);
  }

  private boolean emitted(String id) {
    return occurrences.recent().stream().map(HealthEvent::id).anyMatch(id::equals);
  }

  @Test
  @DisplayName("→ RECOVERING emits restart-attempted; RECOVERING → READY emits recovered")
  void milestonesEmitOnTransitions() {
    worker.transition(CapabilityHealth.RECOVERING, "worker unresponsive; restarting");
    assertTrue(emitted("worker.restart-attempted"), "entering RECOVERING should emit restart-attempted");
    assertFalse(emitted("worker.recovered"), "not recovered yet");

    worker.transition(CapabilityHealth.READY, null);
    assertTrue(emitted("worker.recovered"), "RECOVERING → READY should emit recovered");
  }

  @Test
  @DisplayName("restart-attempted carries the parked recovery context (attempt, faultKind) as attributes")
  void restartAttemptedCarriesForensicAttributes() {
    // Tempdoc 627 (N2): the supervision bridge parks the context on the capability just before the
    // RECOVERING transition; the bridge (a synchronous listener) reads it for the occurrence attributes.
    worker.setRecoveryContext(new RecoveryContext(2, "hang", 2000));
    worker.transition(CapabilityHealth.RECOVERING, "worker unresponsive; restarting");

    HealthEvent event =
        occurrences.recent().stream()
            .filter(e -> "worker.restart-attempted".equals(e.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no restart-attempted occurrence emitted"));
    LifecycleEvent body = (LifecycleEvent) event.body();
    assertEquals(2, body.attributes().get("attempt"), "attempt# rides as a forensic attribute");
    assertEquals("hang", body.attributes().get("faultKind"), "fault kind (hang vs death) rides too");
  }

  @Test
  @DisplayName("restart-attempted fires once per episode (repeated RECOVERING is a same-state no-op)")
  void restartAttemptedNotSpammedPerAttempt() {
    worker.transition(CapabilityHealth.RECOVERING, "attempt 1");
    worker.transition(CapabilityHealth.RECOVERING, "attempt 2"); // same-state: no listener fire
    long count =
        occurrences.recent().stream().map(HealthEvent::id).filter("worker.restart-attempted"::equals).count();
    assertTrue(count == 1, "exactly one restart-attempted per recovery episode, got " + count);
  }

  @Test
  @DisplayName("terminal give-up (RECOVERING → DEGRADED + restart_exhausted) emits NO occurrence")
  void terminalGiveUpDoesNotEmitOccurrence() {
    worker.transition(CapabilityHealth.RECOVERING, "restarting");
    int before = occurrences.recent().size();
    worker.transition(
        CapabilityHealth.DEGRADED, LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code());
    assertFalse(emitted("worker.recovered"), "give-up is not a recovery");
    assertTrue(
        occurrences.recent().size() == before,
        "give-up adds no occurrence — the persistent condition covers the terminal state");
  }
}
