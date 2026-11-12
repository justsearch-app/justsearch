/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A producer that ends its stream reports completion once, never during a delivery. */
@Timeout(15)
final class BoundedHandoffCompletionTest {
  @Test
  void completionWaitsForTheDeliveryInProgressAndIsTheOnlyTerminal() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var delivering = new java.util.concurrent.atomic.AtomicBoolean();
    var completedDuringDelivery = new java.util.concurrent.atomic.AtomicBoolean();
    var completions = new AtomicInteger();
    var errors = new AtomicInteger();
    var completedOn = new AtomicReference<Thread>();
    var deliveryThread = new AtomicReference<Thread>();
    var closed = new AtomicInteger();
    try (var delivery = Executors.newSingleThreadExecutor()) {
      var flow = new BoundedHandoff<String>("test-flow", frame -> {
        deliveryThread.set(Thread.currentThread());
        delivering.set(true);
        entered.countDown();
        try {
          assertTrue(release.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        delivering.set(false);
        throw new IllegalStateException("a late consumer failure is not a second terminal");
      }, failure -> errors.incrementAndGet(), delivery, BoundedHandoff.Backpressure.FAIL_FAST,
          frame -> null, (previous, latest) -> latest, true);
      flow.onClose(closed::incrementAndGet);
      assertTrue(flow.publish("held"));
      assertTrue(entered.await(5, TimeUnit.SECONDS));

      flow.complete(() -> {
        if (delivering.get()) completedDuringDelivery.set(true);
        completedOn.set(Thread.currentThread());
        completions.incrementAndGet();
      });

      assertEquals(1, closed.get(), "ownership is released at retirement, not after delivery");
      assertEquals(0, completions.get(), "completion waits for the delivery in progress");
      assertFalse(flow.publish("after"), "a retired flow refuses new frames");
      release.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (completions.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
      assertEquals(1, completions.get());
      assertFalse(completedDuringDelivery.get());
      assertSame(deliveryThread.get(), completedOn.get());
      assertEquals(0, errors.get(), "exactly one terminal notification");
      flow.complete(completions::incrementAndGet);
      flow.fail(new IllegalStateException("late"));
      assertEquals(1, completions.get());
      assertEquals(0, errors.get());
      assertEquals(1, closed.get());
    }
  }

  @Test
  void idleCompletionRunsAtOnceAndAFailedFlowDoesNotComplete() {
    var completions = new AtomicInteger();
    var errors = new AtomicInteger();
    var idle = new BoundedHandoff<String>("idle", frame -> {}, failure -> errors.incrementAndGet(),
        Runnable::run, BoundedHandoff.Backpressure.FAIL_FAST, frame -> null,
        (previous, latest) -> latest, true);
    idle.complete(completions::incrementAndGet);
    assertEquals(1, completions.get());

    var failed = new BoundedHandoff<String>("failed", frame -> {}, failure -> errors.incrementAndGet(),
        Runnable::run, BoundedHandoff.Backpressure.FAIL_FAST, frame -> null,
        (previous, latest) -> latest, true);
    failed.fail(new IllegalStateException("stream failed"));
    failed.complete(completions::incrementAndGet);
    assertEquals(1, completions.get());
    assertEquals(1, errors.get());
  }
}
