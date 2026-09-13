/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Concurrency regressions for retirement listener registration. */
@Timeout(30)
final class RunRetirementRegistrationTest {
  private static final int ROUNDS = 10_000;

  @Test
  void concurrentRegistrationAndRetirementDeliverEveryListenerExactlyOnce() throws Exception {
    var registry = new RunChannelRegistry();
    var callbacks = new AtomicInteger();
    ExecutorService racers = Executors.newFixedThreadPool(2);

    try {
      for (int round = 0; round < ROUNDS; round++) {
        RunId id = new RunId("registration-race-" + round);
        RunChannel run =
            registry.open(
                id,
                new RunDescriptor("core.test", "conversation", round),
                RunChannelPolicy.conversational());
        var start = new CyclicBarrier(3);

        Future<?> registration =
            racers.submit(
                () -> {
                  await(start);
                  run.onRetire(callbacks::incrementAndGet);
                });
        Future<?> retirement =
            racers.submit(
                () -> {
                  await(start);
                  registry.retire(id, Duration.ZERO);
                });

        await(start);
        registration.get(5, TimeUnit.SECONDS);
        retirement.get(5, TimeUnit.SECONDS);
        assertEquals(round + 1, callbacks.get(), "lost or duplicated callback in round " + round);
      }
    } finally {
      racers.shutdownNow();
    }
  }

  @Test
  void retirementCallbackCanRegisterAnotherRetirementCallback() {
    var registry = new RunChannelRegistry();
    RunId id = new RunId("reentrant-registration");
    RunChannel run =
        registry.open(
            id,
            new RunDescriptor("core.test", "conversation", 1),
            RunChannelPolicy.conversational());
    var outer = new AtomicInteger();
    var reentrant = new AtomicInteger();

    run.onRetire(
        () -> {
          outer.incrementAndGet();
          run.onRetire(reentrant::incrementAndGet);
        });

    registry.retire(id, Duration.ofMinutes(1));
    registry.retire(id, Duration.ofMinutes(1));

    assertEquals(1, outer.get());
    assertEquals(1, reentrant.get());
    assertTrue(run.retired());
  }

  @Test
  void lateRegistrationCompletesWhileAnEarlierRetirementCallbackIsBlocked() throws Exception {
    var registry = new RunChannelRegistry();
    RunId id = new RunId("callback-outside-lock");
    RunChannel run =
        registry.open(
            id,
            new RunDescriptor("core.test", "conversation", 1),
            RunChannelPolicy.conversational());
    var firstEntered = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var lateCalls = new AtomicInteger();
    ExecutorService threads = Executors.newFixedThreadPool(2);

    run.onRetire(
        () -> {
          firstEntered.countDown();
          await(releaseFirst);
        });
    try {
      Future<?> retirement =
          threads.submit(() -> registry.retire(id, Duration.ofMinutes(1)));
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

      Future<?> lateRegistration = threads.submit(() -> run.onRetire(lateCalls::incrementAndGet));
      lateRegistration.get(5, TimeUnit.SECONDS);
      assertEquals(1, lateCalls.get(), "late callback must run outside the listener-owner lock");

      releaseFirst.countDown();
      retirement.get(5, TimeUnit.SECONDS);
    } finally {
      releaseFirst.countDown();
      threads.shutdownNow();
    }
  }

  @Test
  void throwingRetirementCallbackCannotBlockFollowingCallbacks() {
    var registry = new RunChannelRegistry();
    RunId id = new RunId("throwing-listener");
    RunChannel run =
        registry.open(
            id,
            new RunDescriptor("core.test", "conversation", 1),
            RunChannelPolicy.conversational());
    var reached = new AtomicInteger();

    run.onRetire(
        () -> {
          throw new IllegalStateException("writer already closed");
        });
    run.onRetire(reached::incrementAndGet);

    registry.retire(id, Duration.ZERO);

    assertEquals(1, reached.get());
    assertTrue(run.retired());
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new AssertionError("race barrier failed", failure);
    }
  }

  private static void await(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
