/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Lane F stage A items A7 and A8 — the declared flow policy, driven past its bound.
 *
 * <p>The item asks for "a test that drives more events than the bound and asserts the declared
 * policy". The declared policy is <b>block, never drop</b>, with a timeout that fails the flow
 * rather than pacing the producer forever, so there are three properties to separate:
 *
 * <ol>
 *   <li>past the bound with a draining consumer, EVERY frame arrives, in order — this is what
 *       distinguishes "block" from "drop-oldest", and it is the assertion that would fail if
 *       someone swapped the policy for a cheaper one;
 *   <li>with a consumer that has STOPPED draining, the producer is not blocked forever: the flow
 *       reports and closes, and {@code publish} refuses;
 *   <li>closing stops production within one delivery tick, which is what makes cancellation of a
 *       scan or a subscription real rather than cosmetic.
 * </ol>
 */
@Timeout(60)
final class BoundedHandoffTest {

  private ExecutorService threads;

  @AfterEach
  void tearDown() {
    if (threads != null) {
      threads.shutdownNow();
    }
  }

  private ExecutorService threads() {
    threads = Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "bounded-handoff-test");
      t.setDaemon(true);
      return t;
    });
    return threads;
  }

  @Test
  @DisplayName("past the bound with a draining consumer, every frame arrives in order (block, not drop)")
  void blocksRatherThanDropping() throws Exception {
    int capacity = 8;
    int frames = capacity * 10; // ten times the bound
    List<Integer> delivered = new CopyOnWriteArrayList<>();
    CountDownLatch allDelivered = new CountDownLatch(frames);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    BoundedHandoff<Integer> flow =
        new BoundedHandoff<>(
            "test",
            frame -> {
              // A deliberately slow consumer: without a bound this would never matter, and with a
              // drop policy the assertions below would be unsatisfiable.
              try {
                Thread.sleep(1);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              delivered.add(frame);
              allDelivered.countDown();
            },
            failure::set,
            threads(),
            capacity,
            BoundedHandoff.DEFAULT_OFFER_TIMEOUT_MS);

    for (int i = 0; i < frames; i++) {
      assertTrue(flow.publish(i), "publish " + i + " must be accepted, not dropped");
    }
    assertTrue(allDelivered.await(30, TimeUnit.SECONDS), "every published frame must be delivered");

    assertEquals(frames, delivered.size(), "no frame may be dropped: the policy is block");
    List<Integer> expected = new ArrayList<>();
    for (int i = 0; i < frames; i++) {
      expected.add(i);
    }
    assertEquals(expected, new ArrayList<>(delivered), "frames must arrive in producer order");
    assertNull(failure.get());
    flow.close();
  }

  @Test
  @DisplayName("a consumer that stops draining fails the flow instead of pacing the producer forever")
  void aStalledConsumerFailsTheFlowRatherThanBlockingForever() throws Exception {
    int capacity = 4;
    CountDownLatch stalled = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch reported = new CountDownLatch(1);

    BoundedHandoff<Integer> flow =
        new BoundedHandoff<>(
            "test",
            frame -> {
              try {
                // Never returns: a wedged SSE writer.
                stalled.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            t -> {
              failure.set(t);
              reported.countDown();
            },
            threads(),
            capacity,
            250L);

    // Fill the queue and the in-flight frame, then push one more: the producer blocks for the
    // timeout and then the flow fails.
    boolean refused = false;
    for (int i = 0; i < capacity + 4 && !refused; i++) {
      refused = !flow.publish(i);
    }
    assertTrue(refused, "publish must eventually refuse rather than block forever");
    assertTrue(reported.await(10, TimeUnit.SECONDS), "the stall must be reported to the consumer");
    assertNotNull(failure.get());
    // "A failed flow is a closed flow" is observable exactly here: the next publish is refused.
    // Asserting it through a boolean accessor as well would need a method main code never reads,
    // which is the dead-code shape UnreferencedCodeTest exists to catch.
    assertFalse(flow.publish(99), "a failed flow is a closed flow: it refuses immediately");
    stalled.countDown();
  }

  @Test
  @DisplayName("close stops production within one delivery tick and runs the unsubscribe once")
  void closeStopsProductionWithinOneTick() throws Exception {
    AtomicInteger delivered = new AtomicInteger();
    AtomicInteger unsubscribes = new AtomicInteger();
    CountDownLatch firstDelivered = new CountDownLatch(1);
    int published = 40;

    BoundedHandoff<Integer> flow =
        new BoundedHandoff<>(
            "test",
            frame -> {
              // Slow enough that the queue still holds most of the batch when close() lands, so
              // "stopped delivering" is distinguishable from "finished delivering".
              try {
                Thread.sleep(20);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              delivered.incrementAndGet();
              firstDelivered.countDown();
            },
            t -> {},
            threads(),
            /* capacity= */ 64,
            BoundedHandoff.DEFAULT_OFFER_TIMEOUT_MS);
    flow.onClose(unsubscribes::incrementAndGet);

    for (int i = 0; i < published; i++) {
      assertTrue(flow.publish(i), "the batch fits under the bound");
    }
    assertTrue(firstDelivered.await(10, TimeUnit.SECONDS), "delivery must have started");

    int atClose = delivered.get();
    flow.close();

    // One tick is the delivery loop's poll interval; wait several so the assertion is about the
    // bound and not about scheduler jitter. At most the frame already handed to the consumer when
    // close() landed may still complete.
    Thread.sleep(BoundedHandoff.POLL_TICK_MS * 20);
    int afterClose = delivered.get();
    assertTrue(
        afterClose - atClose <= 1,
        "close must stop delivery within one tick, not drain the backlog: "
            + atClose + " -> " + afterClose);
    assertTrue(
        afterClose < published,
        "precondition: the backlog must still have been queued when close() landed");
    assertFalse(flow.publish(999), "close must stop production");
    assertEquals(1, unsubscribes.get(), "the producer-side unsubscribe runs exactly once");

    flow.close();
    assertEquals(1, unsubscribes.get(), "close is idempotent");
  }

  @Test
  @DisplayName("an unsubscribe registered after close still runs, so no subscription leaks")
  void lateUnsubscribeRegistrationStillRuns() {
    AtomicInteger unsubscribes = new AtomicInteger();
    BoundedHandoff<Integer> flow =
        new BoundedHandoff<>("test", frame -> {}, t -> {}, threads(), 4, 100L);
    flow.close();
    flow.onClose(unsubscribes::incrementAndGet);
    assertEquals(1, unsubscribes.get(), "a handler registered after close must run immediately");
  }
}
