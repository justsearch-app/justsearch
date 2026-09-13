/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import java.time.Clock;
import java.time.Instant;
import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@DisplayName("SseStreamChannel — bounded replay handoff")
final class SseStreamChannelBoundedHandoffTest {

  private static final StreamId STREAM = StreamId.surface("bounded-handoff");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), java.time.ZoneOffset.UTC);

  private static SseStreamChannel channel(int capacity) {
    return new SseStreamChannel(
        STREAM,
        new StreamSequenceTracker(),
        new FrameHistoryRingBuffer(capacity),
        CLOCK);
  }

  private static void publish(SseStreamChannel channel, int marker) {
    channel.publish(SseFrameKind.UPDATE, Map.of("n", marker));
  }

  private static int marker(SseEnvelope frame) {
    return ((Number) ((Map<?, ?>) frame.payload()).get("n")).intValue();
  }

  private static Object handoffListener(SseStreamChannel channel)
      throws ReflectiveOperationException {
    var listenersField = SseStreamChannel.class.getDeclaredField("listeners");
    listenersField.setAccessible(true);
    for (Object listener : (Set<?>) listenersField.get(channel)) {
      try {
        if (Queue.class.isAssignableFrom(listener.getClass().getDeclaredField("buffered").getType())) {
          return listener;
        }
      } catch (NoSuchFieldException ignored) {
        // The ordinary subscriber has no handoff queue.
      }
    }
    throw new AssertionError("blocked replay must have registered its handoff listener");
  }

  private static void replaceBuffer(Object handoff, Queue<SseEnvelope> replacement)
      throws ReflectiveOperationException {
    var bufferedField = handoff.getClass().getDeclaredField("buffered");
    bufferedField.setAccessible(true);
    bufferedField.set(handoff, replacement);
  }

  private static Queue<?> handoffBuffer(Object handoff) throws ReflectiveOperationException {
    var bufferedField = handoff.getClass().getDeclaredField("buffered");
    bufferedField.setAccessible(true);
    return (Queue<?>) bufferedField.get(handoff);
  }

  @Test
  @Timeout(10)
  @DisplayName("actual capacity overflow evicts the blocked handoff and releases its frames")
  void actualCapacityOverflowEvictsBlockedHandoff() throws Exception {
    SseStreamChannel channel = channel(2);
    publish(channel, 1);
    publish(channel, 2);

    CountDownLatch enteredReplay = new CountDownLatch(1);
    CountDownLatch releaseReplay = new CountDownLatch(1);
    AtomicInteger delivered = new AtomicInteger();
    AtomicReference<Throwable> handoffFailure = new AtomicReference<>();
    Thread subscriber = new Thread(() -> {
      try {
        channel.subscribeAndReplay(frame -> {
          if (delivered.getAndIncrement() == 0) {
            enteredReplay.countDown();
            try {
              releaseReplay.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        }, 0L);
      } catch (Throwable failure) {
        handoffFailure.set(failure);
      }
    }, "actual-capacity-handoff-subscriber");
    Thread publisher = null;
    subscriber.start();
    try {
      assertTrue(enteredReplay.await(5, TimeUnit.SECONDS), "replay must block in the listener");
      Queue<?> buffered = handoffBuffer(handoffListener(channel));

      AtomicInteger published = new AtomicInteger();
      publisher = new Thread(() -> {
        for (int marker = 3; marker <= 5; marker++) {
          publish(channel, marker);
          published.incrementAndGet();
        }
      }, "actual-capacity-handoff-publisher");
      publisher.start();
      publisher.join(2_000);

      assertFalse(publisher.isAlive(), "publisher must not wait for a blocked replay consumer");
      assertEquals(3, published.get(), "publisher completes after bounded queue overflow");
      assertEquals(0, channel.listenerCount(), "overflow evicts the blocked handoff listener");
      assertTrue(buffered.isEmpty(), "overflow must release buffered frame references immediately");
      assertNull(handoffFailure.get(), "handoff fails only after its blocked replay write returns");

      releaseReplay.countDown();
      subscriber.join(2_000);
      assertFalse(subscriber.isAlive(), "handoff must finish after the blocked write returns");
      assertInstanceOf(IllegalStateException.class, handoffFailure.get());
      assertEquals(1, delivered.get(), "failed handoff stops after its blocked replay write");
    } finally {
      releaseReplay.countDown();
      if (publisher != null) {
        publisher.interrupt();
        publisher.join(2_000);
      }
      subscriber.interrupt();
      subscriber.join(2_000);
    }
  }

  @Test
  @Timeout(10)
  @DisplayName("concurrent overflow retires only the slow handoff and cannot refill its queue")
  void concurrentOverflowCannotRefillRetiredHandoff() throws Exception {
    SseStreamChannel channel = channel(2);
    publish(channel, 1);
    publish(channel, 2);

    List<SseEnvelope> healthyFrames = new CopyOnWriteArrayList<>();
    SseStreamChannel.Subscription healthy = channel.subscribe(healthyFrames::add);

    CountDownLatch enteredReplay = new CountDownLatch(1);
    CountDownLatch releaseReplay = new CountDownLatch(1);
    AtomicInteger delivered = new AtomicInteger();
    AtomicReference<Throwable> handoffFailure = new AtomicReference<>();
    Thread subscriber = new Thread(() -> {
      try {
        channel.subscribeAndReplay(frame -> {
          if (delivered.getAndIncrement() == 0) {
            enteredReplay.countDown();
            try {
              releaseReplay.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        }, 0L);
      } catch (Throwable failure) {
        handoffFailure.set(failure);
      }
    }, "bounded-handoff-subscriber");
    RacingOverflowQueue buffered = null;
    Thread firstPublisher = null;
    Thread racingPublisher = null;
    subscriber.start();
    try {
      assertTrue(enteredReplay.await(5, TimeUnit.SECONDS), "replay must block in the listener");
      Object handoff = handoffListener(channel);
      buffered = new RacingOverflowQueue();
      replaceBuffer(handoff, buffered);

      firstPublisher = new Thread(() -> publish(channel, 3), "overflowing-publisher");
      racingPublisher = new Thread(() -> publish(channel, 4), "racing-publisher");
      firstPublisher.start();
      assertTrue(buffered.firstOfferEntered.await(5, TimeUnit.SECONDS), "first offer must enter");
      racingPublisher.start();

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (buffered.secondOfferEntered.getCount() != 0
          && racingPublisher.getState() != Thread.State.BLOCKED
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertTrue(
          buffered.secondOfferEntered.getCount() == 0
              || racingPublisher.getState() == Thread.State.BLOCKED,
          "second publisher must reach the buffer transition");

      buffered.releaseFirstOffer.countDown();
      firstPublisher.join(2_000);
      assertFalse(firstPublisher.isAlive(), "overflowing publisher must finish");
      buffered.releaseSecondOffer.countDown();
      racingPublisher.join(2_000);
      assertFalse(racingPublisher.isAlive(), "racing publisher must finish");

      assertEquals(1, channel.listenerCount(), "overflow evicts only the slow handoff listener");
      assertTrue(buffered.isEmpty(), "retired handoff queue must not be refilled after overflow");
      assertEquals(2, healthyFrames.size(), "healthy subscriber receives each frame exactly once");
      assertEquals(
          Set.of(3, 4),
          Set.of(marker(healthyFrames.get(0)), marker(healthyFrames.get(1))),
          "an unrelated healthy subscriber receives both concurrent frames");
      assertNull(handoffFailure.get(), "handoff reports overflow when its blocked write returns");

      releaseReplay.countDown();
      subscriber.join(2_000);
      assertFalse(subscriber.isAlive(), "handoff must finish after the blocked write returns");
      assertInstanceOf(IllegalStateException.class, handoffFailure.get());
      assertEquals(1, delivered.get(), "failed handoff stops after its blocked replay write");
    } finally {
      healthy.unsubscribe();
      if (buffered != null) {
        buffered.releaseFirstOffer.countDown();
        buffered.releaseSecondOffer.countDown();
      }
      if (firstPublisher != null) {
        firstPublisher.interrupt();
        firstPublisher.join(2_000);
      }
      if (racingPublisher != null) {
        racingPublisher.interrupt();
        racingPublisher.join(2_000);
      }
      releaseReplay.countDown();
      subscriber.interrupt();
      subscriber.join(2_000);
    }
  }

  @Test
  @DisplayName("a successful bounded handoff preserves replay then live order")
  void successfulHandoffPreservesReplayThenLiveOrder() {
    SseStreamChannel channel = channel(2);
    publish(channel, 1);
    publish(channel, 2);
    List<SseEnvelope> received = new CopyOnWriteArrayList<>();

    Optional<SseStreamChannel.Subscription> subscription =
        channel.subscribeAndReplay(received::add, 0L);
    assertTrue(subscription.isPresent());
    publish(channel, 3);
    subscription.orElseThrow().unsubscribe();

    List<Integer> markers = new ArrayList<>();
    for (SseEnvelope frame : received) markers.add(marker(frame));
    assertEquals(List.of(1, 2, 3), markers);
    assertEquals(0, channel.listenerCount());
  }

  @Test
  @DisplayName("a failing handoff always removes its listener")
  void failingHandoffAlwaysRemovesListener() {
    SseStreamChannel channel = channel(2);
    publish(channel, 1);
    AssertionError failure = new AssertionError("socket failed");

    AssertionError thrown = assertThrows(AssertionError.class, () ->
        channel.subscribeAndReplay(frame -> { throw failure; }, 0L));

    assertEquals(failure, thrown);
    assertEquals(0, channel.listenerCount());
    publish(channel, 2);
  }

  /**
   * Forces the stale-check race hidden by {@code synchronized (buffered)}: the first offer reports
   * overflow and clears, while an unsynchronized second offer has already passed the overflow flag
   * check and commits only after that clear.
   */
  private static final class RacingOverflowQueue extends AbstractQueue<SseEnvelope> {
    private final Queue<SseEnvelope> delegate = new ConcurrentLinkedQueue<>();
    private final AtomicInteger offerCalls = new AtomicInteger();
    private final CountDownLatch firstOfferEntered = new CountDownLatch(1);
    private final CountDownLatch secondOfferEntered = new CountDownLatch(1);
    private final CountDownLatch releaseFirstOffer = new CountDownLatch(1);
    private final CountDownLatch releaseSecondOffer = new CountDownLatch(1);

    @Override
    public boolean offer(SseEnvelope envelope) {
      int call = offerCalls.incrementAndGet();
      if (call == 1) {
        firstOfferEntered.countDown();
        await(releaseFirstOffer);
        return false;
      }
      secondOfferEntered.countDown();
      await(releaseSecondOffer);
      return delegate.offer(envelope);
    }

    @Override
    public SseEnvelope poll() {
      return delegate.poll();
    }

    @Override
    public SseEnvelope peek() {
      return delegate.peek();
    }

    @Override
    public Iterator<SseEnvelope> iterator() {
      return delegate.iterator();
    }

    @Override
    public int size() {
      return delegate.size();
    }

    @Override
    public void clear() {
      delegate.clear();
    }

    private static void await(CountDownLatch latch) {
      try {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "test barrier must be released");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted at test barrier", e);
      }
    }
  }
}
