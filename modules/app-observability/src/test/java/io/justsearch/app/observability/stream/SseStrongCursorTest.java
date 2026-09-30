/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class SseStrongCursorTest {
  private static final StreamId STREAM = StreamId.surface("strong-cursor");

  private static SseStreamChannel channel(int capacity) {
    return new SseStreamChannel(STREAM, new StreamSequenceTracker(),
        new FrameHistoryRingBuffer(capacity), Clock.systemUTC());
  }

  @Test
  void lifecycleOnlyCursorIsValidForItsOwnIncarnation() {
    SseStreamChannel channel = channel(2);
    String cursor = channel.nextEnvelope(SseFrameKind.LIFECYCLE, Map.of("kind", "connected")).resumeToken();
    AtomicInteger prefix = new AtomicInteger();
    var subscription = channel.subscribeAndReplay(frame -> {}, cursor, prefix::incrementAndGet);
    assertTrue(subscription.isPresent(), "no UPDATE was discarded from the empty ring");
    subscription.orElseThrow().unsubscribe();
    assertEquals(1, prefix.get());
    assertFalse(channel.isWithinResumeWindow(1), "numeric run policy remains separate");
  }

  @Test
  void oldIncarnationAndLegacyTokenNeverMatchGrowingReplacementChannel() {
    SseStreamChannel old = channel(2);
    String cursor = old.nextEnvelope(SseFrameKind.LIFECYCLE, Map.of()).resumeToken();
    SseStreamChannel replacement = channel(2);
    replacement.publish(SseFrameKind.UPDATE, "one");
    replacement.publish(SseFrameKind.UPDATE, "two");
    AtomicInteger prefix = new AtomicInteger();
    assertTrue(replacement.subscribeAndReplay(frame -> {}, cursor, prefix::incrementAndGet).isEmpty());
    String legacy = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        (STREAM.value() + ":1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertTrue(replacement.subscribeAndReplay(frame -> {}, legacy,
        prefix::incrementAndGet).isEmpty());
    assertEquals(0, prefix.get());
    assertEquals(0, replacement.listenerCount());
  }

  @Test
  void boundaryCannotBelongToAnotherChannelOrPointAtFutureState() {
    SseStreamChannel channel = channel(2);
    var boundary = channel.captureSnapshotBoundary();
    SseStreamChannel other = channel(2);
    assertTrue(other.subscribeAndReplay(frame -> {}, boundary, () -> {}).isEmpty());
    var decoded = ResumeTokenCodec.decode(boundary.resumeToken()).orElseThrow();
    String future = ResumeTokenCodec.encode(STREAM, 10, decoded.incarnation());
    assertTrue(channel.subscribeAndReplay(frame -> {}, future, () -> {}).isEmpty());
    assertEquals(0, channel.listenerCount());
  }

  @Test
  void discardedFrameFenceRejectsOnlyCursorsThatMissThatFrame() {
    SseStreamChannel channel = channel(2);
    var beforeFirst = channel.captureSnapshotBoundary();
    channel.publish(SseFrameKind.UPDATE, "one");
    var afterFirst = channel.captureSnapshotBoundary();
    channel.publish(SseFrameKind.UPDATE, "two");
    channel.publish(SseFrameKind.UPDATE, "three");
    AtomicInteger invalidPrefix = new AtomicInteger();
    assertTrue(channel.subscribeAndReplay(frame -> {}, beforeFirst, invalidPrefix::incrementAndGet).isEmpty());
    assertEquals(0, invalidPrefix.get(), "an invalid candidate snapshot must never be sent");
    List<Long> delivered = new ArrayList<>();
    var subscription = channel.subscribeAndReplay(frame -> delivered.add(frame.seq()), afterFirst, () -> {});
    assertTrue(subscription.isPresent(), "cursor already covers the discarded frame");
    subscription.orElseThrow().unsubscribe();
    assertEquals(List.of(2L, 3L), delivered);
  }

  @Test
  void prefixPublicationQueuesBehindCapturedReplayAndFailureCleansUp() {
    SseStreamChannel channel = channel(4);
    var boundary = channel.captureSnapshotBoundary();
    channel.publish(SseFrameKind.UPDATE, "replay");
    List<Long> delivered = new ArrayList<>();
    var subscription = channel.subscribeAndReplay(frame -> delivered.add(frame.seq()), boundary,
        () -> channel.publish(SseFrameKind.UPDATE, "during-prefix"));
    subscription.orElseThrow().unsubscribe();
    assertEquals(List.of(1L, 2L), delivered);
    assertThrows(IllegalStateException.class, () -> channel.subscribeAndReplay(frame -> {}, boundary,
        () -> { throw new IllegalStateException("snapshot write failed"); }));
    assertEquals(0, channel.listenerCount());
  }

  @Test
  void strongReplayOrdersEvidenceAndNarrativeBySourceSequence() {
    FrameRetentionPolicy policy = new FrameRetentionPolicy(4, 10_000, 10_000,
        frame -> "evidence".equals(frame.payload()) ? Optional.of("e") : Optional.empty());
    SseStreamChannel channel = new SseStreamChannel(STREAM, new StreamSequenceTracker(),
        new FrameHistoryRingBuffer(policy), Clock.systemUTC());
    var boundary = channel.captureSnapshotBoundary();
    channel.publish(SseFrameKind.UPDATE, "narrative");
    channel.publish(SseFrameKind.UPDATE, "evidence");
    List<Long> delivered = new ArrayList<>();
    var subscription = channel.subscribeAndReplay(frame -> delivered.add(frame.seq()), boundary, () -> {});
    subscription.orElseThrow().unsubscribe();
    assertEquals(List.of(1L, 2L), delivered);
    assertEquals(List.of(2L, 1L), channel.framesSince(0).stream().map(SseEnvelope::seq).toList(),
        "numeric run replay retains its evidence-first policy");
  }

  @Test
  void byteEvictionAndEvidenceReplacementAdvanceTheCoverageFence() {
    for (FrameRetentionPolicy policy : List.of(
        new FrameRetentionPolicy(20, 1, 0, FrameRetentionPolicy.EvidenceClassifier.NARRATIVE_ONLY),
        new FrameRetentionPolicy(20, 10_000, 10_000, frame -> Optional.of("same-key")),
        new FrameRetentionPolicy(20, 10_000, 1, frame -> Optional.of(frame.payload().toString())))) {
      var channel = new SseStreamChannel(STREAM, new StreamSequenceTracker(),
          new FrameHistoryRingBuffer(policy), Clock.systemUTC());
      var before = channel.captureSnapshotBoundary();
      channel.publish(SseFrameKind.UPDATE, "first");
      var covered = channel.captureSnapshotBoundary();
      channel.publish(SseFrameKind.UPDATE, "second");
      assertTrue(channel.subscribeAndReplay(frame -> {}, before, () -> {}).isEmpty(),
          "every discard path must invalidate an uncovered cursor: " + policy);
      var retained = new ArrayList<Long>();
      channel.subscribeAndReplay(frame -> retained.add(frame.seq()), covered, () -> {})
          .orElseThrow().unsubscribe();
      assertEquals(List.of(2L), retained);
    }
  }
}
