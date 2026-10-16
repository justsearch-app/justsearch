package io.justsearch.app.services.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HeadHealthEventsEmitter")
final class HeadHealthEventsEmitterTest {

  private static final Instant T0 = Instant.parse("2026-05-02T10:00:00Z");
  private static final Source HEAD_SRC = Source.forProcess("head", "instance-1", "1.0");
  private static final String SESSION_A = "session-a";
  private static final String SESSION_B = "session-b";

  private OccurrenceLog occurrences;
  private HealthEventChangeRegistry changes;
  private RecordingListener listener;
  private Clock clock;
  private HeadHealthEventsEmitter emitter;

  @BeforeEach
  void setUp() {
    occurrences = new OccurrenceLog();
    changes = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());
    listener = new RecordingListener();
    changes.subscribeTyped(listener);
    clock = Clock.fixed(T0, ZoneId.of("UTC"));
    emitter = new HeadHealthEventsEmitter(occurrences, changes, HEAD_SRC, clock);
  }

  // ============================================================
  // Per-disposition mapping (5 tests)
  // ============================================================

  @Test
  @DisplayName("COMPLETED → agent.session.completed Occurrence (INFO)")
  void completedSessionEmitsCompletedOccurrence() {
    emitter.onSessionTerminated(SESSION_A, "COMPLETED", null, null, 1234L, 5, 7, 8192);

    assertEquals(1, listener.size());
    HealthEventChangeRegistry.HealthChangeEvent e0 = listener.events.get(0);
    assertEquals(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, e0.kind());
    HealthEvent event = e0.event();
    assertEquals("agent.session.completed", event.id());
    assertEquals(Severity.INFO, event.severity());
    LifecycleEvent body = (LifecycleEvent) event.body();
    Map<String, Object> attrs = body.attributes();
    assertEquals(SESSION_A, attrs.get("sessionId"));
    assertEquals("agent.session/" + SESSION_A, attrs.get("subject"));
    assertEquals("COMPLETED", attrs.get("disposition"));
    assertEquals(1234L, attrs.get("durationMs"));
    assertEquals(5, attrs.get("iterationsUsed"));
    assertEquals(7, attrs.get("toolCallsExecuted"));
    assertEquals(8192, attrs.get("contextSizeBytes"));
    assertEquals(1, occurrences.size());
  }

  @Test
  @DisplayName("CANCELLED with cancelTrigger → agent.session.cancelled Occurrence (WARNING)")
  void cancelledSessionWithTriggerEmitsCancelledOccurrence() {
    emitter.onSessionTerminated(SESSION_A, "CANCELLED", null, "USER", 500L, 2, 1, 1024);

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("agent.session.cancelled", event.id());
    assertEquals(Severity.WARNING, event.severity());
    Map<String, Object> attrs = ((LifecycleEvent) event.body()).attributes();
    assertEquals("USER", attrs.get("cancelTrigger"));
    assertFalse(attrs.containsKey("errorCode"));
  }

  @Test
  @DisplayName("BUDGET_EDGE_FINALIZE → agent.session.budget-edge-finalize Occurrence (WARNING)")
  void budgetEdgeFinalizeEmitsHyphenatedId() {
    emitter.onSessionTerminated(
        SESSION_A, "BUDGET_EDGE_FINALIZE", null, null, 800L, 3, 0, 2048);

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    // Hyphen-form catalog ID, NOT underscore form (wire stability check).
    assertEquals("agent.session.budget-edge-finalize", event.id());
    assertEquals(Severity.WARNING, event.severity());
  }

  @Test
  @DisplayName("MAX_ITERATIONS → agent.session.max-iterations Occurrence (WARNING)")
  void maxIterationsEmitsMaxIterationsOccurrence() {
    emitter.onSessionTerminated(SESSION_A, "MAX_ITERATIONS", null, null, 9000L, 50, 25, 16384);

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("agent.session.max-iterations", event.id());
    assertEquals(Severity.WARNING, event.severity());
  }

  @Test
  @DisplayName("ERRORED with errorCode → agent.session.errored Occurrence (ERROR)")
  void erroredSessionWithCodeEmitsErroredOccurrence() {
    emitter.onSessionTerminated(
        SESSION_A, "ERRORED", "INTERNAL_ERROR", null, 600L, 1, 0, 512);

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("agent.session.errored", event.id());
    assertEquals(Severity.ERROR, event.severity());
    Map<String, Object> attrs = ((LifecycleEvent) event.body()).attributes();
    assertEquals("INTERNAL_ERROR", attrs.get("errorCode"));
    assertFalse(attrs.containsKey("cancelTrigger"));
  }

  // ============================================================
  // Null-attribute discipline (§B.V.5 / §B.S #1 pattern)
  // ============================================================

  @Test
  @DisplayName("null errorCode → 'errorCode' key absent from body (not literal 'null')")
  void nullErrorCodeOmittedFromBody() {
    emitter.onSessionTerminated(SESSION_A, "COMPLETED", null, null, 1L, 1, 1, 1);

    assertEquals(1, listener.size());
    Map<String, Object> attrs = ((LifecycleEvent) listener.events.get(0).event().body()).attributes();
    assertFalse(attrs.containsKey("errorCode"), "errorCode key must be absent when null");
    assertFalse(attrs.containsValue("null"), "no attribute should hold the literal string 'null'");
  }

  @Test
  @DisplayName("null cancelTrigger → 'cancelTrigger' key absent from body")
  void nullCancelTriggerOmittedFromBody() {
    emitter.onSessionTerminated(SESSION_A, "ERRORED", "INTERNAL_ERROR", null, 1L, 1, 1, 1);

    assertEquals(1, listener.size());
    Map<String, Object> attrs = ((LifecycleEvent) listener.events.get(0).event().body()).attributes();
    assertFalse(attrs.containsKey("cancelTrigger"), "cancelTrigger key must be absent when null");
  }

  // ============================================================
  // Order + idempotency
  // ============================================================

  @Test
  @DisplayName("multiple emissions preserve order; broadcast count == emit count")
  void multipleEmissionsPreserveOrder() {
    emitter.onSessionTerminated("s-1", "COMPLETED", null, null, 1L, 1, 1, 1);
    emitter.onSessionTerminated("s-2", "ERRORED", "INTERNAL_ERROR", null, 2L, 2, 2, 2);
    emitter.onSessionTerminated("s-3", "CANCELLED", null, "USER", 3L, 3, 3, 3);

    assertEquals(3, listener.size());
    assertEquals("agent.session.completed", listener.events.get(0).event().id());
    assertEquals("agent.session.errored", listener.events.get(1).event().id());
    assertEquals("agent.session.cancelled", listener.events.get(2).event().id());
    List<HealthEvent> stored = occurrences.recent();
    assertEquals(3, stored.size());
    assertEquals("s-1", ((LifecycleEvent) stored.get(0).body()).attributes().get("sessionId"));
    assertEquals("s-2", ((LifecycleEvent) stored.get(1).body()).attributes().get("sessionId"));
    assertEquals("s-3", ((LifecycleEvent) stored.get(2).body()).attributes().get("sessionId"));
  }

  // ============================================================
  // Concurrency (§B.S #2 / §B.Q.5 pattern)
  // ============================================================

  @Test
  @DisplayName("32 concurrent emits → all 32 land in OccurrenceLog; broadcast count == emit count")
  void concurrentEmitRacesCorrectness() throws Exception {
    int threads = 32;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    Set<String> sessionIds = new HashSet<>();
    for (int i = 0; i < threads; i++) {
      String sid = "concurrent-session-" + i;
      sessionIds.add(sid);
      pool.submit(
          () -> {
            try {
              start.await();
              emitter.onSessionTerminated(sid, "COMPLETED", null, null, 1L, 1, 1, 1);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    assertTrue(done.await(5, TimeUnit.SECONDS));
    pool.shutdown();
    assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

    assertEquals(threads, listener.size());
    assertEquals(threads, occurrences.size());
    Set<String> observedSessionIds = new HashSet<>();
    for (HealthEvent e : occurrences.recent()) {
      observedSessionIds.add(
          (String) ((LifecycleEvent) e.body()).attributes().get("sessionId"));
    }
    assertEquals(sessionIds, observedSessionIds);
  }

  // ============================================================
  // Subject parametrization
  // ============================================================

  @Test
  @DisplayName("subject is 'agent.session/{sessionId}' (body attribute)")
  void subjectIsAgentSessionSlashId() {
    emitter.onSessionTerminated("session-xyz-123", "COMPLETED", null, null, 1L, 1, 1, 1);

    assertEquals(1, listener.size());
    Map<String, Object> attrs = ((LifecycleEvent) listener.events.get(0).event().body()).attributes();
    assertEquals("agent.session/session-xyz-123", attrs.get("subject"));
  }

  // ============================================================
  // WARN-once dedup for unmapped TerminalDisposition (§B.V.6 / §B.S #4 pattern)
  // ============================================================

  @Test
  @DisplayName("unmapped disposition string → 0 broadcasts, no crash")
  void unmappedDispositionDoesNotEmit() {
    // 100 calls with the same unmapped disposition; expect 0 broadcasts and no crash.
    for (int i = 0; i < 100; i++) {
      emitter.onSessionTerminated("s-" + i, "FUTURE_DISPOSITION", null, null, 1L, 1, 1, 1);
    }
    assertEquals(0, listener.size());
    assertEquals(0, occurrences.size());
  }

  @Test
  @DisplayName("null sessionId or null disposition → defensive skip (no broadcast)")
  void nullArgumentsAreDefensivelySkipped() {
    emitter.onSessionTerminated(null, "COMPLETED", null, null, 1L, 1, 1, 1);
    emitter.onSessionTerminated(SESSION_A, null, null, null, 1L, 1, 1, 1);
    assertEquals(0, listener.size());
  }

  // ============================================================
  // Wire-format completeness
  // ============================================================

  @Test
  @DisplayName("event has i18nKey, source, and timestamp populated")
  void eventCarriesProvenance() {
    emitter.onSessionTerminated(SESSION_B, "COMPLETED", null, null, 1L, 1, 1, 1);

    HealthEvent event = listener.events.get(0).event();
    assertNotNull(event.timestamp());
    assertEquals(T0, event.timestamp());
    assertEquals(HEAD_SRC, event.source());
    assertTrue(event.i18nKey().isPresent());
    assertEquals("health-events.agent.session.completed.message", event.i18nKey().get());
  }

  // ============================================================
  // Helpers
  // ============================================================

  private static final class RecordingListener
      implements java.util.function.Consumer<HealthEventChangeRegistry.HealthChangeEvent> {
    final List<HealthEventChangeRegistry.HealthChangeEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void accept(HealthEventChangeRegistry.HealthChangeEvent change) {
      events.add(change);
    }

    int size() {
      return events.size();
    }
  }
}
