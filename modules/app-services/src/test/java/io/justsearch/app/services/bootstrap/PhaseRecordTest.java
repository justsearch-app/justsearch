package io.justsearch.app.services.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 541 fix-pass F.1 — PhaseRecord factory + validation unit coverage. */
@DisplayName("PhaseRecord — tempdoc 541 §4.2 factory + validation")
class PhaseRecordTest {

  @Test
  @DisplayName("ready factory populates EAGER + READY with computed duration")
  void readyFactory() {
    PhaseRecord r = PhaseRecord.ready("infra", 100, 150, "span-1");
    assertEquals("infra", r.name());
    assertEquals(Eagerness.EAGER, r.eagerness());
    assertEquals(100L, r.startedAtMs());
    assertEquals(150L, r.completedAtMs());
    assertEquals(50L, r.durationMs());
    assertEquals(PhaseRecord.READY, r.outcome());
    assertNull(r.reasonCode());
    assertEquals("span-1", r.spanId());
  }

  @Test
  @DisplayName("degraded factory carries reasonCode")
  void degradedFactory() {
    PhaseRecord r = PhaseRecord.degraded("capability", 0, 5, "engine.not_started", null);
    assertEquals(Eagerness.EAGER, r.eagerness());
    assertEquals(PhaseRecord.DEGRADED, r.outcome());
    assertEquals("engine.not_started", r.reasonCode());
    assertEquals(5L, r.durationMs());
  }

  @Test
  @DisplayName("failed factory carries reasonCode")
  void failedFactory() {
    PhaseRecord r = PhaseRecord.failed("infra", 0, 1, "grpc.bind_failed", "span-2");
    assertEquals(PhaseRecord.FAILED, r.outcome());
    assertEquals("grpc.bind_failed", r.reasonCode());
    assertEquals("span-2", r.spanId());
  }

  @Test
  @DisplayName("lazyPending factory: LAZY eagerness, PENDING outcome, null completed/duration")
  void lazyPendingFactory() {
    PhaseRecord r =
        PhaseRecord.lazyPending("agent-tools-registration", "deferred until Worker connect");
    assertEquals(Eagerness.LAZY, r.eagerness());
    assertEquals(PhaseRecord.PENDING, r.outcome());
    assertEquals("deferred until Worker connect", r.reasonCode());
    assertEquals(0L, r.startedAtMs(), "LAZY pending uses 0 as startedAtMs sentinel");
    assertNull(r.completedAtMs());
    assertNull(r.durationMs());
    assertNull(r.spanId());
  }

  @Test
  @DisplayName("compact constructor rejects null/blank name")
  void rejectBlankName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PhaseRecord(null, Eagerness.EAGER, 0, 1L, 1L, "READY", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PhaseRecord("  ", Eagerness.EAGER, 0, 1L, 1L, "READY", null, null));
  }

  @Test
  @DisplayName("compact constructor rejects null eagerness")
  void rejectNullEagerness() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PhaseRecord("a", null, 0, 1L, 1L, "READY", null, null));
  }

  @Test
  @DisplayName("compact constructor rejects null outcome")
  void rejectNullOutcome() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PhaseRecord("a", Eagerness.EAGER, 0, 1L, 1L, null, null, null));
  }

  @Test
  @DisplayName("reasonCodeOpt is empty for ready, present for degraded/failed/lazy")
  void reasonCodeOptional() {
    PhaseRecord ready = PhaseRecord.ready("a", 0, 1, null);
    PhaseRecord degraded = PhaseRecord.degraded("a", 0, 1, "reason", null);
    PhaseRecord pending = PhaseRecord.lazyPending("a", "trigger");
    assertTrue(ready.reasonCodeOpt().isEmpty());
    assertTrue(degraded.reasonCodeOpt().isPresent());
    assertEquals("reason", degraded.reasonCodeOpt().get());
    assertEquals("trigger", pending.reasonCodeOpt().get());
  }
}
