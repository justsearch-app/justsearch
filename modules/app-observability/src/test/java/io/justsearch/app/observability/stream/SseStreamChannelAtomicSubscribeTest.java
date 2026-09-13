package io.justsearch.app.observability.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tempdoc 834 S3a — the atomic subscribe-and-replay contract on the RESUME path.
 *
 * <p>The property under test is the law the deleted per-run event hub used to state, hoisted onto
 * the shared substrate (S3b retired that hub entirely): a frame published while a client is
 * attaching reaches it <em>either</em> via the replay <em>or</em> via the live fan-out — never
 * both, never neither.
 */
@DisplayName("SseStreamChannel — atomic subscribe-and-replay (834 S3a)")
final class SseStreamChannelAtomicSubscribeTest {

  private static final StreamId STREAM = StreamId.surface("test-stream");

  private static SseStreamChannel channel() {
    return new SseStreamChannel(STREAM);
  }

  private static long marker(SseEnvelope frame) {
    return ((Number) ((Map<?, ?>) frame.payload()).get("n")).longValue();
  }

  private static void publish(SseStreamChannel channel, int n) {
    channel.publish(SseFrameKind.UPDATE, Map.of("n", n));
  }

  @RepeatedTest(20)
  @Timeout(30)
  @DisplayName("never both, never neither: a subscriber racing a publisher sees each frame once")
  void subscribeDuringPublishIsAtomic() throws Exception {
    SseStreamChannel channel = channel();
    int total = 400;

    // Seed one frame so the cursor sits inside the resume window.
    publish(channel, 0);
    long cursor = channel.framesSince(0L).get(0).seq();

    List<SseEnvelope> received = new CopyOnWriteArrayList<>();
    CountDownLatch publisherStarted = new CountDownLatch(1);
    AtomicReference<Throwable> publisherFailure = new AtomicReference<>();

    Thread publisher =
        new Thread(
            () -> {
              try {
                publisherStarted.countDown();
                for (int n = 1; n <= total; n++) {
                  publish(channel, n);
                }
              } catch (RuntimeException e) {
                publisherFailure.set(e);
              }
            },
            "publisher");
    publisher.start();
    assertTrue(publisherStarted.await(10, TimeUnit.SECONDS), "publisher must start");

    Optional<SseStreamChannel.Subscription> subscription =
        channel.subscribeAndReplay(received::add, cursor);
    assertTrue(subscription.isPresent(), "cursor is inside the window");

    publisher.join(TimeUnit.SECONDS.toMillis(20));
    assertFalse(publisher.isAlive(), "publisher must finish");
    assertNull(publisherFailure.get(), "publisher must not fail");

    // Frames published after the subscribe returns are pure live fan-out; wait for the
    // stream to quiesce so the assertion covers the whole run.
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (received.size() < total && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    subscription.get().unsubscribe();

    // NEVER NEITHER: every published frame arrived.
    // NEVER BOTH: none arrived twice, and they arrived in seq order.
    List<Long> markers = new ArrayList<>();
    for (SseEnvelope frame : received) {
      markers.add(marker(frame));
    }
    List<Long> expected = new ArrayList<>();
    for (long n = 1; n <= total; n++) {
      expected.add(n);
    }
    assertEquals(expected, markers, "each frame exactly once, in order, via replay or fan-out");
  }

  @Test
  @Timeout(30)
  @DisplayName("replay handoff does not stall publishers behind a slow consumer's socket")
  void slowSubscriberDoesNotStallPublisher() throws Exception {
    SseStreamChannel channel = channel();
    for (int n = 1; n <= 50; n++) {
      publish(channel, n);
    }
    long cursor = 0L;

    CountDownLatch consumerEnteredReplay = new CountDownLatch(1);
    CountDownLatch releaseConsumer = new CountDownLatch(1);
    AtomicInteger delivered = new AtomicInteger();
    List<SseEnvelope> received = new CopyOnWriteArrayList<>();

    Thread subscriber =
        new Thread(
            () -> {
              channel.subscribeAndReplay(
                  frame -> {
                    received.add(frame);
                    // Block inside the FIRST replay write, exactly as a slow-but-alive
                    // socket would.
                    if (delivered.getAndIncrement() == 0) {
                      consumerEnteredReplay.countDown();
                      try {
                        releaseConsumer.await(20, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }
                  },
                  cursor);
            },
            "slow-subscriber");
    subscriber.start();
    assertTrue(
        consumerEnteredReplay.await(20, TimeUnit.SECONDS), "subscriber must reach the replay");

    // The subscriber is stuck mid-replay. A publisher must still make progress: pre-834
    // the replay ran inside the monitor the publisher needs, so this would deadlock until
    // the consumer returned.
    AtomicInteger published = new AtomicInteger();
    Thread publisher =
        new Thread(
            () -> {
              for (int n = 51; n <= 80; n++) {
                publish(channel, n);
                published.incrementAndGet();
              }
            },
            "publisher-during-replay");
    publisher.start();
    publisher.join(TimeUnit.SECONDS.toMillis(15));
    assertFalse(publisher.isAlive(), "publisher must not be blocked by the stalled replay");
    assertEquals(30, published.get(), "publisher completed while the consumer was stuck");

    releaseConsumer.countDown();
    subscriber.join(TimeUnit.SECONDS.toMillis(20));
    assertFalse(subscriber.isAlive(), "subscriber must finish its handoff");

    // The stalled consumer still ends up with every frame, in order: the 50 replayed and
    // the 30 buffered during the drain.
    assertEquals(80, received.size(), "50 replayed + 30 buffered-then-drained");
    for (int i = 0; i < received.size(); i++) {
      assertEquals(i + 1L, marker(received.get(i)), "frame " + i + " in order");
    }
  }

  @Test
  @Timeout(30)
  @DisplayName("atomic attachment replays an appended frame while another socket remains blocked")
  void subscribeDoesNotWaitForAnInFlightSocketWrite() throws Exception {
    SseStreamChannel channel = channel();
    publish(channel, 1);
    long cursor = 0L;

    CountDownLatch publisherInsideFanOut = new CountDownLatch(1);
    CountDownLatch releasePublisher = new CountDownLatch(1);
    // Socket delivery is outside the publication boundary. The frame is already appended
    // and enqueued before this callback blocks, so another subscriber can safely replay it.
    channel.subscribe(
        frame -> {
          if (marker(frame) == 2L) {
            publisherInsideFanOut.countDown();
            try {
              releasePublisher.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });

    Thread publisher = new Thread(() -> publish(channel, 2), "blocked-publisher");
    publisher.start();
    assertTrue(publisherInsideFanOut.await(20, TimeUnit.SECONDS), "publisher must be mid-fan-out");

    List<SseEnvelope> received = new CopyOnWriteArrayList<>();
    AtomicReference<Optional<SseStreamChannel.Subscription>> handle = new AtomicReference<>();
    Thread subscriber =
        new Thread(
            () -> handle.set(channel.subscribeAndReplay(received::add, cursor)),
            "atomic-subscriber");
    subscriber.start();

    try {
      subscriber.join(5_000);
      assertFalse(subscriber.isAlive(), "subscribe completes while the existing socket is blocked");
    } finally {
      releasePublisher.countDown();
      publisher.join(TimeUnit.SECONDS.toMillis(20));
      subscriber.join(TimeUnit.SECONDS.toMillis(20));
    }
    assertFalse(publisher.isAlive(), "publisher completes after releasing its callback");

    assertNotNull(handle.get());
    assertTrue(handle.get().isPresent(), "cursor 0 is inside the window");
    // NEVER NEITHER: the frame published during the attach is in the replay, not lost.
    assertEquals(2, received.size(), "both frames replayed");
    assertEquals(1L, marker(received.get(0)));
    assertEquals(2L, marker(received.get(1)));
  }

  @Test
  @DisplayName("a cursor outside the window registers nothing and replays nothing")
  void outsideWindowRegistersNoListener() {
    SseStreamChannel channel = channel();
    publish(channel, 1);

    List<SseEnvelope> received = new ArrayList<>();
    // A cursor from a future / different server lifetime.
    assertTrue(
        channel.subscribeAndReplay(received::add, channel.currentSeq() + 100).isEmpty(),
        "future cursor is a window miss");
    assertTrue(received.isEmpty(), "nothing replayed on a miss");

    publish(channel, 2);
    assertTrue(received.isEmpty(), "no listener was registered on a miss");
  }

  @Test
  @DisplayName("empty buffer with a positive cursor is a window miss (slice 436 Fix B)")
  void emptyBufferPositiveCursorIsAMiss() {
    SseStreamChannel channel = channel();
    // Lifecycle frames advance the seq without entering the ring, so the buffer is empty
    // while currentSeq() is positive — the "previous server lifetime" shape.
    channel.nextEnvelope(SseFrameKind.LIFECYCLE, Map.of("kind", "connected"));
    channel.nextEnvelope(SseFrameKind.LIFECYCLE, Map.of("kind", "heartbeat"));

    List<SseEnvelope> received = new ArrayList<>();
    assertTrue(channel.subscribeAndReplay(received::add, 1L).isEmpty(), "empty buffer is a miss");
    assertTrue(channel.subscribeAndReplay(received::add, 0L).isPresent(), "cursor 0 is not");
  }

  @Test
  @Timeout(30)
  @DisplayName("a listener throwing during the handoff is unregistered, not left dangling")
  void throwingListenerIsUnregistered() {
    SseStreamChannel channel = channel();
    publish(channel, 1);

    AtomicInteger calls = new AtomicInteger();
    try {
      channel.subscribeAndReplay(
          frame -> {
            calls.incrementAndGet();
            throw new IllegalStateException("socket gone");
          },
          0L);
    } catch (IllegalStateException expected) {
      // propagated to the caller, matching publish's evict-on-throw contract
    }
    assertEquals(1, calls.get(), "threw on the first replay frame");

    publish(channel, 2);
    assertEquals(1, calls.get(), "the failed listener must not still be subscribed");
  }
}
