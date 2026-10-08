/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class StreamCallbackBackpressureTest {
  @Test
  void interruptedReaderLeavesFullQueueWithoutReleasingActiveCallback() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var waiting = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    try (var consumer = Executors.newSingleThreadExecutor()) {
      var pump = new StreamCallbackPump(consumer, consumer, 1, 1, null);
      pump.dispatch(() -> {
        entered.countDown();
        try { release.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      pump.dispatch(() -> {});
      Thread reader = Thread.ofVirtual().start(() -> {
        waiting.countDown();
        try { pump.dispatchAwaitCapacity(() -> fail("interrupted delta was dispatched")); }
        catch (Throwable cancelled) { failure.set(cancelled); }
      });
      try {
        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        reader.interrupt();
        reader.join(5_000);
        assertFalse(reader.isAlive());
        assertInstanceOf(CancellationException.class, failure.get());
        assertNull(pump.failure(), "the active callback retains its lifetime");
      } finally {
        release.countDown();
        pump.awaitDrain();
      }
    }
  }
}
