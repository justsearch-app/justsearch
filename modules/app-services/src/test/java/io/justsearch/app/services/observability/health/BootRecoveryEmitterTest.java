package io.justsearch.app.services.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.time.Clock;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 627 (N1): {@link BootRecoveryEmitter} narrates an unclean previous shutdown as a calm INFO
 * occurrence on the existing substrate, carrying the crashed predecessor's PID as a forensic attribute.
 */
@DisplayName("BootRecoveryEmitter — unclean-shutdown recovery occurrence")
final class BootRecoveryEmitterTest {

  private static final Source HEAD = Source.forProcess("head", "instance-1", "1.0");

  @Test
  @DisplayName("emits head.unclean-shutdown-recovered (INFO) with previousPid attribute")
  void emitsOccurrenceWithPreviousPid() {
    OccurrenceLog occurrences = new OccurrenceLog();
    HealthEventChangeRegistry changes = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());

    BootRecoveryEmitter.emitUncleanShutdownRecovered(
        occurrences, changes, HEAD, Clock.systemUTC(), OptionalLong.of(4242L));

    HealthEvent event =
        occurrences.recent().stream()
            .filter(e -> "head.unclean-shutdown-recovered".equals(e.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no unclean-shutdown-recovered occurrence emitted"));
    assertEquals(Severity.INFO, event.severity(), "recovery narration must be calm (INFO)");
    LifecycleEvent body = (LifecycleEvent) event.body();
    assertEquals(4242L, body.attributes().get("previousPid"), "carries the crashed predecessor PID");
  }

  @Test
  @DisplayName("emits with no previousPid attribute when the PID is unknown")
  void emitsWithoutPidWhenUnknown() {
    OccurrenceLog occurrences = new OccurrenceLog();
    HealthEventChangeRegistry changes = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());

    BootRecoveryEmitter.emitUncleanShutdownRecovered(
        occurrences, changes, HEAD, Clock.systemUTC(), OptionalLong.empty());

    HealthEvent event =
        occurrences.recent().stream()
            .filter(e -> "head.unclean-shutdown-recovered".equals(e.id()))
            .findFirst()
            .orElseThrow();
    LifecycleEvent body = (LifecycleEvent) event.body();
    assertTrue(body.attributes().isEmpty(), "no forensic attribute when the PID is unknown");
  }

  @Test
  @DisplayName("emittableIds() declares the occurrence id")
  void emittableIdsDeclaresId() {
    assertTrue(
        BootRecoveryEmitter.emittableIds().contains("head.unclean-shutdown-recovered"),
        "emittableIds must declare the occurrence id for the coverage gate");
  }
}
