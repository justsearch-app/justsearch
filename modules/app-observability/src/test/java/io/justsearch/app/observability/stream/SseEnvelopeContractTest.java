package io.justsearch.app.observability.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.CapabilitiesChangeRegistry;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.observability.operations.OperationHistoryChangeRegistry;
import io.justsearch.app.observability.operations.OperationHistoryEntry;
import io.justsearch.app.observability.operations.OperationOutcome;
import io.justsearch.app.observability.runtime.RuntimeContext;
import io.justsearch.app.observability.runtime.RuntimeContextChangeRegistry;
import io.justsearch.app.observability.runtime.SystemMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cross-cutting closeout coverage for slice 436 Phase 10. Asserts that every shipped
 * change registry adopts the universal SSE envelope shape — same SSE event name,
 * envelope-typed listeners, monotonic seq, expected StreamId.
 *
 * <p>Per slice 436 §"Phase 10": every SSE controller in {@code modules/ui/src/main/java/}
 * either adopts the envelope or is listed as a non-envelope stream (language streams
 * — RagStreamingHandler, agent token streams — explicitly out of scope per §"Out of
 * scope for V1"). This test pins the four envelope adopters; new SSE controllers must
 * either join the list (envelope) or be added to a parallel non-envelope list (with
 * justification in their tempdoc).
 */
@DisplayName("SSE envelope contract (slice 436 Phase 10 closeout)")
final class SseEnvelopeContractTest {

  @Test
  @DisplayName("HealthEventChangeRegistry emits envelopes on streamId surface:health-events")
  void healthEvent() {
    HealthEventChangeRegistry registry = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());
    List<SseEnvelope> seen = new ArrayList<>();
    registry.subscribe(seen::add);

    Source src = Source.forProcess("head", "instance-1", "1.0");
    HealthEvent event =
        new HealthEvent(
            "index.unavailable",
            Instant.parse("2026-04-30T12:00:00Z"),
            src,
            Severity.ERROR,
            Optional.of("health-events.index.unavailable.message"),
            new AssertedCondition(
                "worker",
                ConditionStatus.FALSE,
                "WorkerStarting",
                Instant.parse("2026-04-30T11:59:00Z"),
                Optional.empty(),
                Optional.empty(),
                List.of()));
    registry.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, event);

    assertContractHolds(seen, StreamId.surface("health-events"));
  }

  @Test
  @DisplayName("CapabilitiesChangeRegistry emits envelopes on streamId registry:capabilities")
  void capabilities() {
    CapabilitiesChangeRegistry registry = new CapabilitiesChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    registry.subscribe(seen::add);

    registry.broadcast(
        CapabilitiesChangeRegistry.CapabilityChangeEvent.Kind.ADDED, "new-entry");

    assertContractHolds(seen, StreamId.registry("capabilities"));
  }

  @Test
  @DisplayName("RuntimeContextChangeRegistry emits envelopes on streamId system:runtime-context")
  void runtimeContext() {
    RuntimeContextChangeRegistry registry = new RuntimeContextChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    registry.subscribe(seen::add);

    registry.broadcast(new RuntimeContext(SystemMode.EVAL, true));

    assertContractHolds(seen, StreamId.system("runtime-context"));
  }

  @Test
  @DisplayName("OperationHistoryChangeRegistry emits envelopes on streamId surface:operation-history")
  void operationHistory() {
    OperationHistoryChangeRegistry registry = new OperationHistoryChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    registry.subscribe(seen::add);

    registry.broadcast(
        new OperationHistoryEntry(
            new io.justsearch.agent.api.registry.OperationRef("core.test-entry"),
            "head",
            Instant.parse("2026-04-30T12:00:00Z"),
            Instant.parse("2026-04-30T12:00:01Z"),
            OperationOutcome.SUCCESS,
            Optional.empty(),
            io.justsearch.agent.api.registry.InvocationProvenance.systemInternal(
                Instant.parse("2026-04-30T12:00:00Z")),
            Optional.empty()));

    assertContractHolds(seen, StreamId.surface("operation-history"));
  }

  /**
   * Asserts the envelope contract for a single broadcast: exactly one envelope received,
   * with the expected StreamId, UPDATE frameKind, monotonic seq >= 1, non-null
   * resumeToken.
   */
  private static void assertContractHolds(List<SseEnvelope> seen, StreamId expectedStreamId) {
    assertEquals(1, seen.size(), "exactly one envelope per broadcast");
    SseEnvelope env = seen.get(0);
    assertEquals(expectedStreamId, env.streamId(), "envelope must carry the expected StreamId");
    assertSame(SseFrameKind.UPDATE, env.frameKind(), "broadcast emits UPDATE frameKind");
    assertTrue(env.seq() >= 1, "seq must be monotonic >= 1");
    assertNotNull(env.resumeToken(), "resumeToken must be present");
    assertNotNull(env.ts(), "ts must be present");
  }

  // ============================================================
  // Fix D: tightened closeout — lifecycle + resume + isolation
  // ============================================================

  @Test
  @DisplayName(
      "Fix D: cross-stream sequence isolation — broadcasts on one channel don't bump another")
  void crossStreamSequenceIsolation() {
    HealthEventChangeRegistry health = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());
    RuntimeContextChangeRegistry runtime =
        new RuntimeContextChangeRegistry();

    long healthBefore = health.currentSeq();
    long runtimeBefore = runtime.currentSeq();

    // Broadcast on health only
    Source src = Source.forProcess("head", "instance-1", "1.0");
    HealthEvent event =
        new HealthEvent(
            "index.unavailable",
            Instant.parse("2026-04-30T12:00:00Z"),
            src,
            Severity.ERROR,
            Optional.of("health-events.index.unavailable.message"),
            new AssertedCondition(
                "worker",
                ConditionStatus.FALSE,
                "WorkerStarting",
                Instant.parse("2026-04-30T11:59:00Z"),
                Optional.empty(),
                Optional.empty(),
                List.of()));
    health.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, event);

    assertTrue(health.currentSeq() > healthBefore, "health seq must advance");
    assertEquals(
        runtimeBefore,
        runtime.currentSeq(),
        "runtime-context seq MUST NOT advance from health broadcast (each registry has its own"
            + " SseStreamChannel)");
  }

  @Test
  @DisplayName(
      "Fix D: lifecycle frames consume seqs but DO NOT enter the ring buffer")
  void lifecycleFramesNotInRingBuffer() {
    SseStreamChannel channel = new SseStreamChannel(StreamId.surface("test-stream"));

    long seqBefore = channel.currentSeq();
    SseEnvelope lifecycle =
        channel.nextEnvelope(SseFrameKind.LIFECYCLE, java.util.Map.of("kind", "connected"));

    // Seq advanced (lifecycle consumes the counter)
    assertTrue(channel.currentSeq() > seqBefore, "seq must advance even for lifecycle");
    assertSame(SseFrameKind.LIFECYCLE, lifecycle.frameKind());

    // But the ring buffer is empty (lifecycle frames don't enter it)
    assertEquals(
        0,
        channel.framesSince(0L).size(),
        "ring buffer must NOT contain lifecycle frames (resume only replays UPDATE frames)");
  }

  @Test
  @DisplayName("Fix D: framesSince returns retained UPDATE frames only, in chronological order")
  void framesSinceReturnsUpdateFramesOnly() {
    SseStreamChannel channel = new SseStreamChannel(StreamId.surface("test-stream"));

    // Mix UPDATE and LIFECYCLE; only UPDATE should be in the ring.
    channel.publish(SseFrameKind.UPDATE, java.util.Map.of("seq-marker", 1));
    channel.nextEnvelope(SseFrameKind.LIFECYCLE, java.util.Map.of("kind", "heartbeat"));
    channel.publish(SseFrameKind.UPDATE, java.util.Map.of("seq-marker", 2));
    channel.nextEnvelope(SseFrameKind.LIFECYCLE, java.util.Map.of("kind", "heartbeat"));
    channel.publish(SseFrameKind.UPDATE, java.util.Map.of("seq-marker", 3));

    var retained = channel.framesSince(0L);
    assertEquals(3, retained.size(), "3 UPDATE frames retained; 2 LIFECYCLE excluded");
    // Frames in chronological order
    assertTrue(retained.get(0).seq() < retained.get(1).seq(), "chronological order");
    assertTrue(retained.get(1).seq() < retained.get(2).seq(), "chronological order");
    // All retained frames are UPDATE
    for (SseEnvelope f : retained) {
      assertSame(SseFrameKind.UPDATE, f.frameKind(), "retained frames are UPDATE only");
    }
  }

  @Test
  @DisplayName("Fix D: oldestRetainedSeq reflects the buffer's first frame, 0 when empty")
  void oldestRetainedSeqContract() {
    SseStreamChannel channel = new SseStreamChannel(StreamId.surface("test-stream"));

    assertEquals(0L, channel.oldestRetainedSeq(), "empty buffer reports 0");

    channel.publish(SseFrameKind.UPDATE, java.util.Map.of("a", 1));
    long firstUpdateSeq = channel.framesSince(0L).get(0).seq();
    assertEquals(
        firstUpdateSeq,
        channel.oldestRetainedSeq(),
        "oldest = seq of first retained UPDATE frame");
  }
}
