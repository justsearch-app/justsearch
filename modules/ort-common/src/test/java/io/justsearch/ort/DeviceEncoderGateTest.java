/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Spec D tests G2 and G3 (§3.2 rules 3 and 5): the device gate's bound on a foreground wait, and
 * its aging, under a constant foreground stream.
 *
 * <p>The test thread plays the device: each seat is a fake 50 ms run on a controllable clock, which
 * the test advances before it releases the seat. Callers are threads that wait in {@link
 * DeviceEncoderGate#seat}; each is queued while a seat is held, so the queue order is the order the
 * test queued them in.
 */
final class DeviceEncoderGateTest {

  private static final long SEAT_MS = 50;
  private static final int SEATS = 400;
  private static final int FOREGROUND_WAITING = 2;

  /**
   * G2 (S4 bound): with fake 50 ms background seats under a constant foreground stream, a
   * foreground call waits at most the seated remainder, plus one aged run, plus the foreground
   * calls queued before it.
   */
  @Test
  void aForegroundWaitIsBoundedByTheSeatedRemainderOneAgedRunAndEarlierCalls() throws Exception {
    Simulation sim = new Simulation(DeviceGatePolicy.DEFAULTS, 1);
    sim.run();

    List<Caller> foreground = sim.seated(true);
    assertTrue(foreground.size() > SEATS / 2, "the foreground stream was served");
    for (Caller call : foreground) {
      long bound = SEAT_MS * (1 + 1 + call.foregroundAhead);
      assertTrue(
          call.waitMs() <= bound,
          call.name + " waited " + call.waitMs() + " ms; bound " + bound + " ms (seated remainder,"
              + " one aged run, " + call.foregroundAhead + " earlier foreground calls)");
    }
    assertTrue(sim.seated(false).size() > 1, "the bulk producer was seated by aging");
  }

  /**
   * G3 (S4 aging): with two background producers and a continuous foreground stream, each
   * foreground call is overtaken by at most one aged run, and background work is seated at least
   * once per {@code agingWallMs}. Aging by seatings is set out of reach here, so the progress comes
   * from the wall-clock bound alone.
   */
  @Test
  void eachForegroundCallIsOvertakenAtMostOnceAndBulkProgressesEveryAgingWall() throws Exception {
    DeviceGatePolicy wallOnly = new DeviceGatePolicy(1, 1_000_000, 2_000L, 10_000L, 10_000L, 5_000L);
    Simulation sim = new Simulation(wallOnly, 2);
    sim.run();

    for (Caller call : sim.seated(true)) {
      long overtakes =
          sim.seated(false).stream()
              .filter(bg -> bg.seatIndex >= call.seatsAtQueue && bg.seatIndex < call.seatIndex)
              .count();
      assertTrue(overtakes <= 1, call.name + " was overtaken by " + overtakes + " aged runs");
    }
    List<Caller> background = sim.seated(false);
    long elapsedMs = sim.nowMs() - sim.startMs;
    assertTrue(
        background.size() >= elapsedMs / wallOnly.agingWallMs(),
        background.size() + " background seats in " + elapsedMs + " ms");
    for (Caller bulk : background) {
      // Aged after agingWallMs, then seated after at most the seated remainder, the other
      // producer's aged run and the foreground calls that the other's aging passed.
      long bound = wallOnly.agingWallMs() + SEAT_MS * (2 + FOREGROUND_WAITING);
      assertTrue(bulk.waitMs() <= bound, bulk.name + " waited " + bulk.waitMs() + " ms");
    }
  }

  /** One caller of the simulated device. Fields are written by the test thread only. */
  private static final class Caller {
    private final String name;
    private final boolean foreground;
    private final Object session;
    private final CountDownLatch mayRelease = new CountDownLatch(1);
    private final AtomicLong seatedAtMs = new AtomicLong(-1);
    private Thread thread;
    private long queuedAtMs;
    private int seatsAtQueue;
    private int foregroundAhead;
    private int seatIndex = -1;

    private Caller(String name, boolean foreground, Object session) {
      this.name = name;
      this.foreground = foreground;
      this.session = session;
    }

    private long waitMs() {
      return seatedAtMs.get() - queuedAtMs;
    }
  }

  /** A device with one seat, a fake clock, a constant foreground stream and bulk producers. */
  private static final class Simulation {
    private final AtomicLong clockNanos = new AtomicLong(TimeUnit.SECONDS.toNanos(1_000));
    private final DeviceEncoderGate gate;
    private final BlockingQueue<Caller> seatedEvents = new LinkedBlockingQueue<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final List<Caller> callers = new ArrayList<>();
    private final Object[] producerSessions;
    private final Caller[] producerOutstanding;
    private final long startMs;
    private int seatings;
    private int queries;
    private int bulkRequests;

    private Simulation(DeviceGatePolicy policy, int producers) {
      this.gate = new DeviceEncoderGate(policy, clockNanos::get);
      this.producerSessions = new Object[producers];
      this.producerOutstanding = new Caller[producers];
      for (int i = 0; i < producers; i++) producerSessions[i] = new Object();
      this.startMs = nowMs();
    }

    private long nowMs() {
      return TimeUnit.NANOSECONDS.toMillis(clockNanos.get());
    }

    /** Runs {@link #SEATS} seats with the streams topped up, then drains the queue. */
    private void run() throws Exception {
      try {
        topUp();
        for (int i = 0; i < SEATS; i++) {
          Caller holder = nextSeated();
          topUp();
          runSeat(holder);
        }
        for (Caller holder = nextSeatedOrNull(); holder != null; holder = nextSeatedOrNull()) {
          runSeat(holder);
        }
        assertNull(failure.get());
        for (Caller caller : callers) {
          assertTrue(caller.seatIndex >= 0, caller.name + " was never seated");
        }
      } finally {
        for (Caller caller : callers) caller.mayRelease.countDown();
        for (Caller caller : callers) caller.thread.join(5_000);
      }
    }

    private List<Caller> seated(boolean foreground) {
      return callers.stream().filter(c -> c.foreground == foreground && c.seatIndex >= 0).toList();
    }

    /** Keeps {@link #FOREGROUND_WAITING} queries waiting and one request per bulk producer. */
    private void topUp() throws Exception {
      long waitingQueries =
          callers.stream().filter(c -> c.foreground && c.seatIndex < 0).count();
      for (long i = waitingQueries; i < FOREGROUND_WAITING; i++) {
        queue(new Caller("query-" + queries++, true, new Object()));
      }
      for (int p = 0; p < producerSessions.length; p++) {
        Caller outstanding = producerOutstanding[p];
        if (outstanding == null || outstanding.mayRelease.getCount() == 0) {
          Caller next = new Caller("bulk-" + p + "-" + bulkRequests++, false, producerSessions[p]);
          producerOutstanding[p] = next;
          queue(next);
        }
      }
    }

    private void queue(Caller caller) throws Exception {
      caller.queuedAtMs = nowMs();
      caller.seatsAtQueue = seatings;
      caller.foregroundAhead =
          (int) callers.stream().filter(c -> c.foreground && c.seatIndex < 0).count();
      int waitingBefore = gate.waiting();
      int seatedBefore = seatedEvents.size();
      callers.add(caller);
      SessionAcquisitionRequest request =
          SessionAcquisitionRequest.within(
              caller.foreground
                  ? SessionAcquisitionRequest.Urgency.FOREGROUND
                  : SessionAcquisitionRequest.Urgency.BACKGROUND,
              Duration.ofMinutes(2));
      caller.thread = new Thread(() -> {
        try (DeviceEncoderGate.Seat _ = gate.seat(caller.session, request)) {
          caller.seatedAtMs.set(nowMs());
          seatedEvents.add(caller);
          assertTrue(caller.mayRelease.await(2, TimeUnit.MINUTES));
        } catch (Throwable thrown) {
          failure.compareAndSet(null, thrown);
        }
      }, "gate-sim-" + caller.name);
      caller.thread.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (gate.waiting() == waitingBefore
          && seatedEvents.size() == seatedBefore
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertTrue(
          gate.waiting() > waitingBefore || seatedEvents.size() > seatedBefore,
          caller.name + " did not reach the gate");
    }

    private Caller nextSeated() throws InterruptedException {
      Caller holder = nextSeatedOrNull();
      assertNotNull(holder, "a waiter is seated once the seat frees: " + failure.get());
      return holder;
    }

    private Caller nextSeatedOrNull() throws InterruptedException {
      Caller holder = seatedEvents.poll(2, TimeUnit.SECONDS);
      if (holder != null) holder.seatIndex = seatings++;
      return holder;
    }

    /** The holder's fake run: the clock advances one seat, then the seat is released. */
    private void runSeat(Caller holder) throws InterruptedException {
      clockNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(SEAT_MS));
      holder.mayRelease.countDown();
      holder.thread.join(5_000);
      assertEquals(Thread.State.TERMINATED, holder.thread.getState(), holder.name + " released");
    }
  }
}
