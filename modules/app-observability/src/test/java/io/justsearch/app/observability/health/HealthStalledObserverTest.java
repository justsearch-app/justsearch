/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * A Health observer that stalls is waited for at most once per stall, and a stall ends only when
 * the observer has caught up with its whole backlog, not when it finishes one frame. An observer
 * Error that arrives after the publisher stopped waiting reaches the channel owner's log instead
 * of vanishing in the delivery task, and never reaches or interrupts the publisher.
 */
@Timeout(60)
final class HealthStalledObserverTest {

  /** Well under the one-second bound, well above a prompt hand-off. */
  private static final long PROMPT_MS = 500;

  private static HealthEvent event(int i) {
    return new HealthEvent("worker.recovered", Instant.now(), Source.forProcess("head", "b81", "1"),
        Severity.INFO, Optional.of("health-events.worker.recovered.message"),
        new LifecycleEvent(Map.of("i", i), Optional.empty()));
  }

  /** What a producer saw: how long broadcast held it, its interrupt flag, anything thrown. */
  record Outcome(long heldMillis, boolean interrupted, Throwable thrown) {}

  private static Outcome publish(HealthEventChangeRegistry changes, int i) throws Exception {
    var out = new AtomicReference<Outcome>();
    Thread producer = Thread.ofPlatform().name("b81-producer-" + i).start(() -> {
      long start = System.nanoTime();
      Throwable thrown = null;
      try {
        changes.broadcast(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, event(i));
      } catch (RuntimeException | Error failure) {
        thrown = failure;
      }
      long held = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      out.set(new Outcome(held, Thread.currentThread().isInterrupted(), thrown));
    });
    producer.join(10_000);
    assertFalse(producer.isAlive(), "producer must return from broadcast");
    return out.get();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void slowAdvancingObserverIsNotWaitedForUntilBacklogDrains() throws Exception {
    var changes = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());
    int frames = 12;
    List<CountDownLatch> entered = new ArrayList<>();
    List<CountDownLatch> release = new ArrayList<>();
    for (int i = 0; i < frames; i++) {
      entered.add(new CountDownLatch(1));
      release.add(new CountDownLatch(1));
    }
    var arrivals = new AtomicInteger();
    var slow = new CopyOnWriteArrayList<Long>();
    changes.subscribe(envelope -> {
      int i = arrivals.getAndIncrement();
      slow.add(envelope.seq());
      entered.get(i).countDown();
      await(release.get(i));
    });
    var healthy = new CopyOnWriteArrayList<Long>();
    changes.subscribe(envelope -> healthy.add(envelope.seq()));
    try {
      // Frame 1 blocks the observer: the publisher is held once, for at most the bound.
      Outcome first = publish(changes, 1);
      assertTrue(entered.get(0).await(5, TimeUnit.SECONDS), "observer entered frame 1");
      assertTrue(first.heldMillis() < 3_000, "a stalled observer holds a publisher at most the bound");
      // Frame 2 finds the observer stalled and is not held.
      Outcome second = publish(changes, 2);
      assertTrue(second.heldMillis() < PROMPT_MS, "held " + second.heldMillis() + " ms by a stall");

      // The observer advances one frame at a time, always with a backlog left: its stall goes on,
      // so no later publisher waits for it.
      int published = 2;
      for (int k = 0; k < 4; k++) {
        release.get(k).countDown();
        assertTrue(entered.get(k + 1).await(5, TimeUnit.SECONDS), "observer entered frame " + (k + 2));
        Outcome later = publish(changes, ++published);
        assertTrue(later.heldMillis() < PROMPT_MS, "frame " + published + " held the publisher "
            + later.heldMillis() + " ms although the observer's backlog never drained");
        assertFalse(later.interrupted(), "a publisher is never left interrupted");
        assertNull(later.thrown(), "a publisher is never failed by a stalled observer");
      }
      assertEquals(published, healthy.size(), "the healthy subscriber received every frame");

      // Catching up ends the stall: once the backlog drains, a publish waits for delivery again.
      for (int i = 0; i < frames; i++) release.get(i).countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (slow.size() < published && System.nanoTime() < deadline) Thread.sleep(10);
      assertEquals(published, slow.size(), "the slow observer caught up with its backlog");
      Thread.sleep(50);
      publish(changes, ++published);
      assertEquals(published, slow.size(),
          "after catching up the observer is waited for again, so it has the frame on return");
      for (int i = 1; i < slow.size(); i++) {
        assertTrue(slow.get(i) > slow.get(i - 1), "slow observer order " + slow);
      }
      for (int i = 1; i < healthy.size(); i++) {
        assertTrue(healthy.get(i) > healthy.get(i - 1), "healthy observer order " + healthy);
      }
    } finally {
      for (CountDownLatch latch : release) latch.countDown();
      changes.channel().close();
    }
  }

  @Test
  void observerWaitResumesAfterBacklogDrainsAndSecondStallIsBounded() throws Exception {
    var delivery = Executors.newSingleThreadExecutor();
    var changes = new HealthEventChangeRegistry(delivery);
    List<CountDownLatch> entered = new ArrayList<>();
    List<CountDownLatch> release = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      entered.add(new CountDownLatch(1));
      release.add(new CountDownLatch(1));
    }
    var seen = new CopyOnWriteArrayList<Long>();
    changes.subscribe(envelope -> {
      int index = seen.size();
      seen.add(envelope.seq());
      entered.get(index).countDown();
      await(release.get(index));
    });
    try {
      Outcome first = publish(changes, 1);
      assertTrue(first.heldMillis() >= PROMPT_MS && first.heldMillis() < 3_000);
      assertTrue(publish(changes, 2).heldMillis() < PROMPT_MS);
      release.get(0).countDown();
      assertTrue(entered.get(1).await(5, TimeUnit.SECONDS));
      release.get(1).countDown();
      // The single delivery executor's barrier completes only after the backlog's drain task
      // exits, including its final delivered-sequence / stall-state update.
      delivery.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertEquals(2, seen.size());

      var secondStall = new java.util.concurrent.FutureTask<Outcome>(() -> publish(changes, 3));
      Thread producer = Thread.ofPlatform().name("b84-second-stall").start(secondStall);
      assertTrue(entered.get(2).await(5, TimeUnit.SECONDS));
      assertThrows(java.util.concurrent.TimeoutException.class,
          () -> secondStall.get(150, TimeUnit.MILLISECONDS),
          "a caught-up observer must be waited for again while its next frame is held");
      Outcome resumed = secondStall.get(5, TimeUnit.SECONDS);
      producer.join(5_000);
      assertFalse(producer.isAlive());
      assertTrue(resumed.heldMillis() >= PROMPT_MS && resumed.heldMillis() < 3_000,
          "the second stall receives one fresh bounded wait: " + resumed.heldMillis());
      assertFalse(resumed.interrupted());
      assertNull(resumed.thrown());
      Outcome skipped = publish(changes, 4);
      assertTrue(skipped.heldMillis() < PROMPT_MS, "later frames skip the second stall");
      assertFalse(skipped.interrupted());
      assertNull(skipped.thrown());
      release.get(2).countDown();
      assertTrue(entered.get(3).await(5, TimeUnit.SECONDS));
      release.get(3).countDown();
      delivery.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertEquals(List.of(1L, 2L, 3L, 4L), seen);
    } finally {
      release.forEach(CountDownLatch::countDown);
      changes.channel().close();
    }
  }

  /** Thrown by an observer after the publisher's bounded wait. */
  static final class LateObserverFailure extends Error {
    private static final long serialVersionUID = 1L;

    LateObserverFailure() {
      super("observer failed after the publisher stopped waiting");
    }
  }

  @Test
  void observerErrorAfterTheBoundedWaitReachesTheChannelOwnersLog() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(SseStreamChannel.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    logger.addAppender(captured);
    var changes = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    changes.subscribe(envelope -> {
      entered.countDown();
      await(release);
      throw new LateObserverFailure();
    });
    var healthy = new CopyOnWriteArrayList<Long>();
    changes.subscribe(envelope -> healthy.add(envelope.seq()));
    try {
      Outcome outcome = publish(changes, 1);
      assertTrue(entered.await(5, TimeUnit.SECONDS), "observer entered");
      assertNull(outcome.thrown(), "an observer Error after the bound never reaches the publisher");
      assertFalse(outcome.interrupted(), "the publisher is not interrupted");
      release.countDown();

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      ILoggingEvent reported = null;
      while (reported == null && System.nanoTime() < deadline) {
        for (ILoggingEvent logged : List.copyOf(captured.list)) {
          if (logged.getThrowableProxy() != null && logged.getThrowableProxy().getClassName()
              .equals(LateObserverFailure.class.getName())) {
            reported = logged;
          }
        }
        if (reported == null) Thread.sleep(20);
      }
      assertNotNull(reported,
          "an observer Error after the publisher's bounded wait must reach the channel owner's log");
      assertEquals(Level.ERROR, reported.getLevel());

      // The failed observer is retired; the publisher and the other subscriber go on.
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (changes.channel().listenerCount() != 1 && System.nanoTime() < deadline) Thread.sleep(10);
      assertEquals(1, changes.channel().listenerCount(), "the failed observer is retired");
      Outcome next = publish(changes, 2);
      assertNull(next.thrown());
      assertFalse(next.interrupted());
      assertEquals(2, healthy.size(), "the healthy subscriber received both frames");
    } finally {
      release.countDown();
      logger.detachAppender(captured);
      changes.channel().close();
    }
  }
}
