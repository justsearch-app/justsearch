/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class SsePublicationOrderingTest {
  @Test
  void reentrantPublicationAndFailedListenerPreserveHealthyOrder() {
    SseStreamChannel channel = new SseStreamChannel(StreamId.surface("reentrant"));
    List<Long> healthy = new CopyOnWriteArrayList<>();
    channel.subscribe(frame -> {
      channel.publish(SseFrameKind.UPDATE, "nested");
      throw new IllegalStateException("failed socket");
    });
    channel.subscribe(frame -> healthy.add(frame.seq()));
    channel.publish(SseFrameKind.UPDATE, "outer");
    assertEquals(List.of(1L, 2L), healthy);
    assertEquals(1, channel.listenerCount());
  }

  @Test
  void fatalSubscriberFailuresStillDrainHealthyListeners() {
    SseStreamChannel channel = new SseStreamChannel(StreamId.surface("fatal-socket"));
    AssertionError failure = new AssertionError("same failure instance");
    channel.subscribe(frame -> { throw failure; });
    channel.subscribe(frame -> { throw failure; });
    List<Long> healthy = new CopyOnWriteArrayList<>();
    channel.subscribe(frame -> healthy.add(frame.seq()));
    assertSame(failure, assertThrows(AssertionError.class,
        () -> channel.publish(SseFrameKind.UPDATE, "one")));
    assertEquals(List.of(1L), healthy);
    assertEquals(1, channel.listenerCount());
  }

  @Test
  void sequenceAllocationAndPublicationAreOneOrderedBoundary() throws Exception {
    BlockingClock clock = new BlockingClock();
    SseStreamChannel channel = new SseStreamChannel(StreamId.surface("ordered"),
        new StreamSequenceTracker(), new FrameHistoryRingBuffer(20), clock);
    List<Long> delivered = new CopyOnWriteArrayList<>();
    channel.subscribe(frame -> delivered.add(frame.seq()));
    Thread first = new Thread(() -> channel.publish(SseFrameKind.UPDATE, "first"));
    Thread second = new Thread(() -> channel.publish(SseFrameKind.UPDATE, "second"));
    first.start();
    try {
      assertTrue(clock.entered.await(5, TimeUnit.SECONDS));
      second.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (second.isAlive() && second.getState() != Thread.State.BLOCKED
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertTrue(!second.isAlive() || second.getState() == Thread.State.BLOCKED,
          "second publisher reaches the publication boundary");
    } finally {
      clock.release.countDown();
      first.join(5_000);
      second.join(5_000);
    }
    assertFalse(first.isAlive());
    assertFalse(second.isAlive());
    assertEquals(List.of(1L, 2L), delivered);
    assertEquals(List.of(1L, 2L), channel.framesSince(0).stream().map(SseEnvelope::seq).toList());
  }

  @Test
  void blockedLiveSocketHasOneDrainerAndDoesNotStrandHealthyListeners() throws Exception {
    SseStreamChannel channel = new SseStreamChannel(StreamId.surface("serial-delivery"));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger concurrent = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    List<Long> slow = new CopyOnWriteArrayList<>();
    List<Long> healthy = new CopyOnWriteArrayList<>();
    channel.subscribe(frame -> {
      maximum.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
      try {
        if (frame.seq() == 1) {
          entered.countDown();
          await(release);
        }
        slow.add(frame.seq());
      } finally {
        concurrent.decrementAndGet();
      }
    });
    channel.subscribe(frame -> healthy.add(frame.seq()));
    Thread first = new Thread(() -> channel.publish(SseFrameKind.UPDATE, "first"));
    Thread second = new Thread(() -> channel.publish(SseFrameKind.UPDATE, "second"));
    first.start();
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      second.start();
      second.join(5_000);
      assertFalse(second.isAlive(), "publisher only enqueues behind the blocked socket");
      assertEquals(List.of(1L, 2L), healthy, "healthy listener drains while the first socket blocks");
      assertEquals(1, maximum.get(), "a socket never has concurrent writers");
    } finally {
      release.countDown();
      first.join(5_000);
      second.join(5_000);
    }
    assertFalse(first.isAlive());
    assertEquals(List.of(1L, 2L), slow);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  private static final class BlockingClock extends Clock {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() {
      if (calls.getAndIncrement() == 0) {
        entered.countDown();
        await(release);
      }
      return Instant.parse("2026-09-13T00:00:00Z");
    }
  }
}
