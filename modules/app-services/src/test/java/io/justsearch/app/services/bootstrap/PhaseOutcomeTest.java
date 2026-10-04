package io.justsearch.app.services.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 541 §5.3 — sealed-sum outcome primitive unit coverage. */
@DisplayName("PhaseOutcome<O> — tempdoc 541 §5.3 primitive")
class PhaseOutcomeTest {

  @Test
  @DisplayName("Ready carries value, hasValue true, no reasons")
  void readyArm() {
    PhaseOutcome<String> r = new PhaseOutcome.Ready<>("hello");
    assertTrue(r.hasValue());
    assertEquals("hello", r.orThrow());
    assertEquals(Optional.of("hello"), r.optionalValue());
    assertTrue(r.reasonCodes().isEmpty());
  }

  @Test
  @DisplayName("Degraded carries value AND non-empty reason set")
  void degradedArm() {
    PhaseOutcome<String> d = new PhaseOutcome.Degraded<>("partial", Set.of("engine.not_started"));
    assertTrue(d.hasValue());
    assertEquals("partial", d.orThrow());
    assertEquals(Optional.of("partial"), d.optionalValue());
    assertEquals(Set.of("engine.not_started"), d.reasonCodes());
  }

  @Test
  @DisplayName("Degraded with null reason set normalizes to empty")
  void degradedNullReasons() {
    PhaseOutcome.Degraded<String> d = new PhaseOutcome.Degraded<>("v", null);
    assertTrue(d.reasons().isEmpty());
  }

  @Test
  @DisplayName("Failed orThrow re-throws the cause; no value present")
  void failedArm() {
    RuntimeException cause = new RuntimeException("boom");
    PhaseOutcome<String> f = PhaseOutcome.Failed.of(cause);
    assertFalse(f.hasValue());
    IllegalStateException thrown = assertThrows(IllegalStateException.class, f::orThrow);
    assertSame(cause, thrown.getCause());
    assertEquals(Optional.empty(), f.optionalValue());
    assertEquals(Set.of("RuntimeException"), f.reasonCodes());
  }

  @Test
  @DisplayName("Failed null-cause is rejected")
  void failedRejectsNullCause() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PhaseOutcome.Failed<>(null, Set.of(), Optional.empty()));
  }

  @Test
  @DisplayName("Failed with typed reasons returns those (not the class simple-name fallback)")
  void failedWithTypedReasons() {
    RuntimeException cause = new RuntimeException("boom");
    PhaseOutcome<String> f =
        PhaseOutcome.Failed.of(cause, Set.of("grpc.bind_failed", "infra.partial"));
    assertEquals(Set.of("grpc.bind_failed", "infra.partial"), f.reasonCodes());
  }

  @Test
  @DisplayName("Failed with empty reasons falls back to cause's class simple-name")
  void failedWithoutReasonsFallsBack() {
    RuntimeException cause = new IllegalStateException("nope");
    PhaseOutcome<String> f = PhaseOutcome.Failed.of(cause);
    assertEquals(Set.of("IllegalStateException"), f.reasonCodes());
  }

  @Test
  @DisplayName("pattern-match exhaustiveness across all three arms")
  void patternMatchExhaustive() {
    PhaseOutcome<String>[] outcomes =
        new PhaseOutcome[] {
          new PhaseOutcome.Ready<>("r"),
          new PhaseOutcome.Degraded<>("d", Set.of("reason")),
          PhaseOutcome.Failed.of(new RuntimeException("f"))
        };
    int readys = 0, degradeds = 0, faileds = 0;
    for (PhaseOutcome<String> o : outcomes) {
      switch (o) {
        case PhaseOutcome.Ready<String> r -> readys++;
        case PhaseOutcome.Degraded<String> d -> degradeds++;
        case PhaseOutcome.Failed<String> f -> faileds++;
      }
    }
    assertEquals(1, readys);
    assertEquals(1, degradeds);
    assertEquals(1, faileds);
  }
}
