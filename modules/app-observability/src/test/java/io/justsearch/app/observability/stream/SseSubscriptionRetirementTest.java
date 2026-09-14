/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class SseSubscriptionRetirementTest {
  private static SseStreamChannel channel() {
    return new SseStreamChannel(StreamId.system("retirement"), new StreamSequenceTracker(),
        new FrameHistoryRingBuffer(2), Clock.systemUTC());
  }

  @Test
  @Timeout(10)
  void overflowNotifiesOutsideSourceLockBeforeBlockedDeliveryReturns() throws Exception {
    var channel = channel();
    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger notified = new AtomicInteger();
    AtomicInteger healthy = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var slow = channel.subscribe(frame -> {
        blocked.countDown();
        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
      });
      var fast = channel.subscribe(frame -> healthy.incrementAndGet());
      slow.onRetire(() -> {
        // Another thread must acquire the channel lock while this callback is still running.
        try { assertEquals(1, executor.submit(channel::listenerCount).get(2, TimeUnit.SECONDS)); }
        catch (Exception failure) { throw new AssertionError("retirement held source lock", failure); }
        notified.incrementAndGet();
      });
      var first = executor.submit(() -> channel.publish(SseFrameKind.UPDATE, "first"));
      try {
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        for (int n = 0; n < 3; n++) channel.publish(SseFrameKind.UPDATE, n);
        assertEquals(1, notified.get());
        assertFalse(first.isDone(), "overflow notification must not await blocked socket");
        assertEquals(4, healthy.get());
        slow.unsubscribe();
        slow.onRetire(notified::incrementAndGet);
        assertEquals(2, notified.get(), "late registration fires once without repeating prior callback");
      } finally {
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        slow.unsubscribe();
        fast.unsubscribe();
      }
    }
  }

  @Test
  void callbackFailuresDoNotMaskDeliveryFailureOrSkipOtherRetireObservers() {
    var channel = channel();
    var original = new AssertionError("socket fatal");
    var cleanup = new IllegalStateException("cleanup failed");
    AtomicInteger notified = new AtomicInteger();
    AtomicInteger healthy = new AtomicInteger();
    var slow = channel.subscribe(frame -> { throw original; });
    slow.onRetire(() -> { throw cleanup; });
    slow.onRetire(() -> { notified.incrementAndGet(); slow.unsubscribe(); });
    var fast = channel.subscribe(frame -> healthy.incrementAndGet());
    assertSame(original, assertThrows(AssertionError.class,
        () -> channel.publish(SseFrameKind.UPDATE, "frame")));
    assertEquals(1, notified.get());
    assertEquals(1, healthy.get());
    assertSame(cleanup, original.getSuppressed()[0]);
    fast.unsubscribe();
  }
}
