/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A publisher on any channel is never interrupted because a subscription or the channel ended. */
@Timeout(60)
final class SseStreamChannelPublisherInterruptTest {

  private static void spin(long nanos) {
    long end = System.nanoTime() + nanos;
    while (System.nanoTime() < end) Thread.onSpinWait();
  }

  private static Boolean publishAndReadFlag(SseStreamChannel channel) throws InterruptedException {
    var flag = new AtomicReference<Boolean>();
    Thread publisher = Thread.ofPlatform().start(() -> {
      channel.publish(SseFrameKind.UPDATE, "frame");
      flag.set(Thread.currentThread().isInterrupted());
    });
    publisher.join(10_000);
    assertFalse(publisher.isAlive(), "publisher returned");
    return flag.get();
  }

  @Test
  void closingAPlainChannelDuringCallerDeliveryDoesNotInterruptThePublisher() throws Exception {
    var channel = new SseStreamChannel(StreamId.system("publisher-interrupt"));
    var entered = new CountDownLatch(1);
    channel.subscribe(frame -> {
      entered.countDown();
      spin(300_000_000L);
    });
    Thread closer = Thread.ofPlatform().start(() -> {
      try {
        entered.await();
        Thread.sleep(50);
      } catch (InterruptedException ignored) {
        // close anyway
      }
      channel.close();
    });
    Boolean interrupted = publishAndReadFlag(channel);
    closer.join(10_000);
    assertNotNull(interrupted);
    assertFalse(interrupted, "channel close interrupted the thread that was publishing");
  }

  @Test
  void unsubscribeRacesOnAPlainChannelNeverInterruptThePublisher() throws Exception {
    int stray = 0;
    for (int i = 0; i < 200; i++) {
      var channel = new SseStreamChannel(StreamId.system("publisher-interrupt-race"));
      var entered = new CountDownLatch(1);
      var sub = channel.subscribe(frame -> {
        entered.countDown();
        spin(200_000L);
      });
      Thread unsubscriber = Thread.ofPlatform().start(() -> {
        try {
          entered.await();
        } catch (InterruptedException ignored) {
          // unsubscribe anyway
        }
        sub.unsubscribe();
      });
      if (Boolean.TRUE.equals(publishAndReadFlag(channel))) stray++;
      unsubscriber.join(10_000);
    }
    assertEquals(0, stray, "publishers left interrupted after a concurrent unsubscribe");
  }
}
