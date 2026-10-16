package io.justsearch.app.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HealthEventChangeRegistry")
final class HealthEventChangeRegistryTest {

  private static final Source SRC = Source.forProcess("head", "instance-1", "1.0");

  private static HealthEvent sample() {
    return new HealthEvent(
        "memory.pressure",
        Instant.parse("2026-04-30T12:00:00Z"),
        SRC,
        Severity.WARNING,
        Optional.of("health-events.memory.pressure.message"),
        new ThresholdState(
            "head.memory",
            ThresholdPhase.FIRING,
            java.util.Map.of("ratio_pct", 91),
            Instant.parse("2026-04-30T11:59:00Z"),
            Optional.empty(),
            Optional.empty(),
            List.of()));
  }

  @Test
  @DisplayName("subscribe + broadcast delivers an envelope with bumped seq")
  void subscribeAndBroadcast() {
    HealthEventChangeRegistry registry = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());
    AtomicInteger count = new AtomicInteger();
    long initialSeq = registry.currentSeq();
    registry.subscribe(envelope -> count.incrementAndGet());
    registry.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, sample());
    assertEquals(1, count.get());
    assertTrue(registry.currentSeq() > initialSeq);
  }

  @Test
  @DisplayName("unsubscribe removes the listener")
  void unsubscribe() {
    HealthEventChangeRegistry registry = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());
    AtomicInteger count = new AtomicInteger();
    var sub = registry.subscribe(change -> count.incrementAndGet());
    sub.unsubscribe();
    registry.broadcast(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, sample());
    assertEquals(0, count.get());
  }

  @Test
  @DisplayName("dead listener (throws) removed inline on broadcast")
  void deadListenerCleanup() {
    HealthEventChangeRegistry registry = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());
    AtomicInteger goodCount = new AtomicInteger();
    registry.subscribe(
        change -> {
          throw new RuntimeException("simulated subscriber failure");
        });
    registry.subscribe(change -> goodCount.incrementAndGet());
    registry.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, sample());
    // First broadcast triggered failure for bad listener — removed inline.
    registry.broadcast(HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, sample());
    assertEquals(2, goodCount.get(), "Good listener should still receive after bad listener removed");
  }

  @Test
  @DisplayName("broadcast wraps payload in SseEnvelope with HealthDelta payload")
  void broadcastEnvelopeShape() {
    HealthEventChangeRegistry registry = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());
    List<SseEnvelope> seen = new ArrayList<>();
    registry.subscribe(seen::add);

    HealthEvent event = sample();
    registry.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, event);

    assertEquals(1, seen.size());
    SseEnvelope env = seen.get(0);
    assertEquals(HealthEventChangeRegistry.STREAM_ID, env.streamId());
    assertEquals(SseFrameKind.UPDATE, env.frameKind());
    assertTrue(env.seq() >= 1, "seq must be monotonic");
    HealthEventChangeRegistry.HealthDelta delta =
        (HealthEventChangeRegistry.HealthDelta) env.payload();
    assertEquals("condition-added", delta.kind());
    assertEquals(event, delta.event());
  }
}
