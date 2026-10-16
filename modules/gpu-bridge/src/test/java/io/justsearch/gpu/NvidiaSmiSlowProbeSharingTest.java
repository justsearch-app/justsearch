/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

/**
 * Task b6d536 b101: callers already waiting on a slow nvidia-smi probe share its outcome, a
 * timeout included, even when the probe used up the five-second interval. They receive the last
 * good reading marked stale, or the failure when there is none, and never start a probe of their
 * own. The next probe still waits for the interval, and a later probe never changes what an
 * earlier probe's waiters receive. From the round-4 and round-5 verifiers' sharing checks.
 */
final class NvidiaSmiSlowProbeSharingTest {
  private final AtomicLong millis = new AtomicLong();
  private final Clock clock = new Clock() {
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.EPOCH.plusMillis(millis.get()); }
  };
  private final AtomicInteger launches = new AtomicInteger();
  private final AtomicReference<CountDownLatch> entered = new AtomicReference<>();
  private final AtomicReference<CountDownLatch> release = new AtomicReference<>();
  private final AtomicReference<NvidiaSmiReadings.Outcome> next = new AtomicReference<>();

  @Test void callersWaitingOnATimedOutProbeShareItsOutcome() throws Exception {
    var readings = new NvidiaSmiReadings(clock, () -> {
      launches.incrementAndGet();
      CountDownLatch gate = release.get();
      if (gate != null) {
        entered.get().countDown();
        if (!gate.await(10, TimeUnit.SECONDS)) throw new AssertionError("probe release missing");
      }
      return next.get();
    });

    // No good reading yet: the five waiters share the timeout itself.
    List<NvidiaSmiReadings.Reading> first = slowProbe(readings, 0, 5_001);
    assertEquals(6, first.size());
    assertEquals(1, launches.get(), "six callers entered while one probe was in flight and share its outcome");
    for (var answer : first) {
      assertFalse(answer.available(), "no good reading yet: every caller receives the timeout");
      assertEquals("nvidia-smi timed out", answer.error());
    }

    // A good reading, then another slow timeout: the waiters receive that reading marked stale.
    millis.set(10_002);
    next.set(new NvidiaSmiReadings.Outcome(0, "8192, 4096, 591.59"));
    var good = readings.read();
    assertTrue(good.available());
    assertEquals(2, launches.get());
    List<NvidiaSmiReadings.Reading> second = slowProbe(readings, 15_003, 20_004);
    assertEquals(3, launches.get(), "the waiters on the second timeout start no probe either");
    assertFalse(second.getFirst().available(), "the caller that ran the probe receives its timeout");
    for (var answer : second.subList(1, second.size())) {
      assertTrue(answer.available() && answer.stale(), "a waiter receives the last good reading, marked stale");
      assertEquals(good.totalBytes(), answer.totalBytes());
      assertEquals(good.at(), answer.at());
    }

    // Launches keep the interval: the next one at 20.004 s, none again before 25.004 s.
    next.set(new NvidiaSmiReadings.Outcome(0, "8192, 2048, 591.59"));
    assertTrue(readings.read().available());
    assertEquals(4, launches.get(), "five seconds after the last launch the next one is allowed");
    millis.set(25_003);
    readings.invalidate();
    assertTrue(readings.read().stale());
    assertEquals(4, launches.get(), "no launch within five seconds of the last one, even when invalidated");
    millis.set(25_004);
    assertFalse(readings.read().stale());
    assertEquals(5, launches.get());
  }

  /**
   * A cohort of eight waiters on a slow probe, then the caller that ran it reads again at once and
   * runs the next probe, which the interval now allows. Each waiter receives the outcome of the probe
   * it joined, never the later probe's, whichever thread the lock goes to first. Repeated, since the
   * overwrite this guards against depends on the order the woken threads take the lock; the
   * round-5 verifier saw it within two trials. From the verifier's cohort check.
   */
  @Test void everyWaiterReceivesTheOutcomeOfTheProbeItJoined() throws Exception {
    for (boolean firstTimesOut : new boolean[] {true, false}) {
      for (int trial = 0; trial < 100; trial++) {
        cohortTrial(firstTimesOut, trial);
      }
    }
  }

  private static void cohortTrial(boolean firstTimesOut, int trial) throws Exception {
    AtomicLong ms = new AtomicLong();
    Clock trialClock = new Clock() {
      @Override public ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(ZoneId zone) { return this; }
      @Override public Instant instant() { return Instant.EPOCH.plusMillis(ms.get()); }
    };
    CountDownLatch inFirst = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger runs = new AtomicInteger();
    var readings = new NvidiaSmiReadings(trialClock, () -> {
      int run = runs.incrementAndGet();
      if (run == 1) {
        inFirst.countDown();
        if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new AssertionError("probe release missing");
        if (firstTimesOut) return new NvidiaSmiReadings.Outcome(-1, null);
      }
      return new NvidiaSmiReadings.Outcome(0, "8192, " + (run == 1 ? 4096 : 2048) + ", probe-" + run);
    });
    var answers = new CopyOnWriteArrayList<NvidiaSmiReadings.Reading>();
    var failures = new CopyOnWriteArrayList<Throwable>();
    Thread leader = Thread.ofPlatform().priority(Thread.MAX_PRIORITY).unstarted(() -> {
      try {
        readings.read(); // runs the first probe
        readings.read(); // at once runs the second, which the interval allows
      } catch (Throwable t) {
        failures.add(t);
      }
    });
    leader.start();
    assertTrue(inFirst.await(10, TimeUnit.SECONDS));
    var waiters = new ArrayList<Thread>();
    for (int i = 0; i < 8; i++) {
      waiters.add(Thread.ofPlatform().priority(Thread.MIN_PRIORITY).start(() -> {
        try {
          answers.add(readings.read());
        } catch (Throwable t) {
          failures.add(t);
        }
      }));
    }
    try {
      long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while ((probeWaiterCount(readings) != 8
          || waiters.stream().anyMatch(t -> t.getState() != Thread.State.WAITING))
          && System.nanoTime() < limit) {
        Thread.sleep(1);
      }
      assertEquals(8, probeWaiterCount(readings),
          "all eight readers joined the first probe's completion condition");
      assertTrue(waiters.stream().allMatch(t -> t.getState() == Thread.State.WAITING),
          "all eight readers wait on the first probe before it completes");
      ms.set(5_001); // the first probe used the interval: the next one may run at once
    } finally {
      releaseFirst.countDown();
      leader.join(10_000);
      for (Thread waiter : waiters) waiter.join(10_000);
    }
    assertEquals(List.of(), failures);
    assertEquals(2, runs.get(), "the caller that ran the first probe ran the second; no waiter ran one");
    assertEquals(8, answers.size());
    long other = answers.stream().filter(answer -> firstTimesOut
        ? answer.available() || !"nvidia-smi timed out".equals(answer.error())
        : !"probe-1".equals(answer.driverVersion())).count();
    assertEquals(0, other, (firstTimesOut ? "timeout" : "success") + " trial " + trial
        + ": waiters received another probe's outcome instead of the one they joined: " + answers);
  }

  /** Readers on the completion condition have joined the probe, rather than only its lock queue. */
  private static int probeWaiterCount(NvidiaSmiReadings readings) throws ReflectiveOperationException {
    Field lockField = NvidiaSmiReadings.class.getDeclaredField("lock");
    Field settledField = NvidiaSmiReadings.class.getDeclaredField("settled");
    lockField.setAccessible(true);
    settledField.setAccessible(true);
    ReentrantLock lock = (ReentrantLock) lockField.get(readings);
    Condition settled = (Condition) settledField.get(readings);
    lock.lock();
    try {
      return lock.getWaitQueueLength(settled);
    } finally {
      lock.unlock();
    }
  }

  /**
   * One caller starts a probe at {@code startMillis} that times out at {@code endMillis}; five more
   * callers wait on it. The runner's answer comes first.
   */
  private List<NvidiaSmiReadings.Reading> slowProbe(NvidiaSmiReadings readings, long startMillis, long endMillis)
      throws Exception {
    millis.set(startMillis);
    entered.set(new CountDownLatch(1));
    release.set(new CountDownLatch(1));
    next.set(new NvidiaSmiReadings.Outcome(-1, null)); // timeout: the real launch waits five seconds
    var runnerAnswer = new AtomicReference<NvidiaSmiReadings.Reading>();
    var waiterAnswers = new CopyOnWriteArrayList<NvidiaSmiReadings.Reading>();
    var callers = new ArrayList<Thread>();
    callers.add(Thread.ofPlatform().start(() -> runnerAnswer.set(readings.read())));
    assertTrue(entered.get().await(10, TimeUnit.SECONDS));
    for (int i = 0; i < 5; i++) callers.add(Thread.ofPlatform().start(() -> waiterAnswers.add(readings.read())));
    try {
      long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (callers.subList(1, callers.size()).stream().anyMatch(t -> t.getState() != Thread.State.WAITING)
          && System.nanoTime() < limit) {
        Thread.sleep(2);
      }
      assertTrue(callers.getFirst().getState() == Thread.State.TIMED_WAITING
              && callers.subList(1, callers.size()).stream().allMatch(t -> t.getState() == Thread.State.WAITING),
          "the probe runner and five readers must be waiting before the probe completes");
      millis.set(endMillis); // the probe has used its timeout before returning
    } finally {
      release.get().countDown();
      for (Thread caller : callers) caller.join(10_000);
      release.set(null);
    }
    var answers = new ArrayList<NvidiaSmiReadings.Reading>();
    answers.add(runnerAnswer.get());
    answers.addAll(waiterAnswers);
    return answers;
  }
}
