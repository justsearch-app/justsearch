/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.pacing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ort.DeviceEncoderGate;
import io.justsearch.ort.DeviceGatePolicy;
import io.justsearch.ort.SessionAcquisitionRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Spec D test P5a (S5 regression guard, §3.5): under a constant foreground stream that always has a
 * query waiting at the device, the bulk producer — paced by {@link IndexingPacing} and seated by
 * the device gate — still gets the share of the device's seats that the gate's aging guarantees
 * over 60 s: one in {@code agingSeatings + 2}, derived from {@link DeviceGatePolicy}, less a stated
 * tolerance of three points. Spec D's 20% assumed one in five; S5's owner-agreed minimum indexing rate stays the
 * live check P5b.
 *
 * <p>The starvation risk is introduced by the gate itself: a gate that always seats foreground
 * first would seat bulk never under this stream. Aging is what bounds it. Its fail-before is
 * therefore a mutation (aging disabled), recorded in the evidence notes, not a before-state run.
 *
 * <p>Fake time: one clock drives the gate's aging wall, the pacing arithmetic, the foreground
 * cooldown and the pacing sleeper. A seated caller advances it by its run's cost, so with one seat
 * the clock is the device's busy time. Before a seated caller releases, it waits (in real time)
 * until every other foreground caller has queued again, so each seat is chosen with the full
 * foreground stream waiting — the "constant stream" of the scenario, not an artefact of thread
 * scheduling. For the same reason a seated foreground caller also waits until the bulk producer
 * has queued again for its next run, or is pacing: on the device a bulk producer re-queues within
 * microseconds of its run, long before a 50 ms query run ends, but here a run takes no real time,
 * so without that wait the gate's choices would depend on when the OS schedules the bulk thread.
 */
final class BulkDutyFloorUnderDeviceGateTest {

  private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60);
  /** A query encode run (M4: semantic legs in tens of ms). */
  private static final long FOREGROUND_RUN_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
  /** One bulk ORT run: an 8-document embedding sub-batch at M5's 7.3 ms/doc. */
  private static final long BULK_RUN_NANOS = 58_400_000L;
  /** Bulk runs per pace slice: 128 documents (embeddingPhaseDocs) in 8-document runs. */
  private static final int BULK_RUNS_PER_SLICE = 16;
  private static final int FOREGROUND_CALLERS = 2;

  /**
   * Allowance below the gate's bound, in share of seats: the 60 s window cuts the last seat cycle,
   * and a pace slice whose gate wait was shorter than the pause it owes yields the device for the
   * difference (0 to 4 such pauses per window, at most 2.3 s in total). Over 20 runs of this
   * simulation the share was 16.78% to 17.05% (median 16.88%) against the 16.67% bound; three
   * points (about 35 of the window's roughly 1 170 seats) keeps the floor well under that and well
   * above the 1.3% the gate gives bulk with aging disabled.
   */
  private static final double FAKE_CLOCK_EDGE_TOLERANCE = 0.03;

  /**
   * The gate's own guarantee to a bulk producer that queues again as soon as its run ends (Spec D
   * §3.2 aging rule, line 256: aged "after {@code agingSeatings} foreground seatings since it
   * queued"). When the bulk run releases its seat, a waiting foreground call takes it before bulk
   * has queued again, and bulk then waits for {@code agingSeatings} more foreground seatings: one
   * bulk seat per {@code agingSeatings + 2} seats. The spec's 20% assumed one in five.
   */
  private static double minimumBulkShare(DeviceGatePolicy policy) {
    return 1.0 / (policy.agingSeatings() + 2) - FAKE_CLOCK_EDGE_TOLERANCE;
  }

  @Test
  @Timeout(60)
  void bulkKeepsItsShareOfSeatsUnderAConstantForegroundStream() throws Exception {
    DeviceGatePolicy policy = DeviceGatePolicy.DEFAULTS;
    double minimumBulkShare = minimumBulkShare(policy);
    Result result = simulate(policy);

    assertTrue(result.foregroundSeats() > 0, "the foreground stream ran");
    double share = (double) result.bulkSeats() / (result.bulkSeats() + result.foregroundSeats());
    assertTrue(
        share >= minimumBulkShare,
        "bulk got "
            + result.bulkSeats()
            + " of "
            + (result.bulkSeats() + result.foregroundSeats())
            + " seats ("
            + Math.round(share * 1000) / 10.0
            + "%) over 60 s; paced "
            + result.paces()
            + " times, yielding "
            + result.yieldedMs()
            + " ms; the floor is "
            + Math.round(minimumBulkShare * 1000) / 10.0
            + "% (1/(agingSeatings + 2) less "
            + Math.round(FAKE_CLOCK_EDGE_TOLERANCE * 1000) / 10.0
            + " points)");
  }

  private record Result(int bulkSeats, int foregroundSeats, long paces, long yieldedMs) {}

  private static Result simulate(DeviceGatePolicy policy) throws Exception {
    AtomicLong clock = new AtomicLong();
    AtomicInteger foregroundQueued = new AtomicInteger();
    AtomicInteger bulkSeats = new AtomicInteger();
    AtomicInteger foregroundSeats = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    DeviceEncoderGate gate = new DeviceEncoderGate(policy, clock::get);
    ForegroundLoad load = new ForegroundLoad(() -> clock.get() / 1_000_000L);
    load.started(); // the stream never drains during the window
    AtomicBoolean bulkSleeping = new AtomicBoolean();
    IndexingPacing pacing =
        new IndexingPacing(
            load,
            IndexingPacing.DEFAULT_DUTY_PCT,
            IndexingPacing.DEFAULT_COOLDOWN_MS,
            () -> clock.get() / 1_000_000L,
            clock::get,
            millis -> {
              // A yield lasts while the foreground stream advances the device clock.
              long until = clock.get() + TimeUnit.MILLISECONDS.toNanos(millis);
              bulkSleeping.set(true);
              try {
                while (clock.get() < until) {
                  if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("simulation over");
                  }
                  Thread.onSpinWait();
                }
              } finally {
                bulkSleeping.set(false);
              }
            });

    // The bulk producer is ready for the next seat choice while it sleeps in a pacing yield or
    // waits at the gate; between the two it is still on its way to the gate.
    AtomicReference<Thread> bulkThread = new AtomicReference<>();
    BooleanSupplier bulkReady =
        () -> {
          Thread bulk = bulkThread.get();
          return bulk == null
              || bulkSleeping.get()
              || bulk.getState() == Thread.State.TIMED_WAITING
              || bulk.getState() == Thread.State.TERMINATED;
        };

    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < FOREGROUND_CALLERS; i++) {
      Object session = new Object();
      threads.add(
          new Thread(
              () -> {
                try {
                  while (!Thread.currentThread().isInterrupted()) {
                    foregroundQueued.incrementAndGet();
                    boolean seated = false;
                    try (DeviceEncoderGate.Seat _ =
                        gate.seat(session, request(SessionAcquisitionRequest.Urgency.FOREGROUND))) {
                      seated = true;
                      foregroundQueued.decrementAndGet();
                      run(
                          clock,
                          FOREGROUND_RUN_NANOS,
                          foregroundSeats,
                          () ->
                              foregroundQueued.get() >= FOREGROUND_CALLERS - 1
                                  && bulkReady.getAsBoolean());
                    } finally {
                      if (!seated) {
                        foregroundQueued.decrementAndGet();
                      }
                    }
                  }
                } catch (InterruptedException | java.util.concurrent.CancellationException done) {
                  // simulation over
                } catch (Throwable thrown) {
                  failure.compareAndSet(null, thrown);
                }
              },
              "p5a-foreground-" + i));
    }
    Object bulkSession = new Object();
    Thread bulk =
        new Thread(
            () -> {
              try {
                while (!Thread.currentThread().isInterrupted()) {
                  pacing.pace();
                  for (int run = 0; run < BULK_RUNS_PER_SLICE; run++) {
                    try (DeviceEncoderGate.Seat _ =
                        gate.seat(bulkSession, request(SessionAcquisitionRequest.Urgency.BACKGROUND))) {
                      run(
                          clock,
                          BULK_RUN_NANOS,
                          bulkSeats,
                          () -> foregroundQueued.get() >= FOREGROUND_CALLERS);
                    }
                  }
                }
              } catch (InterruptedException | java.util.concurrent.CancellationException done) {
                // simulation over
              } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
              }
            },
            "p5a-bulk");
    bulkThread.set(bulk);
    threads.add(bulk);

    threads.forEach(Thread::start);
    long realDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
    while (clock.get() < WINDOW_NANOS && failure.get() == null) {
      if (System.nanoTime() > realDeadline) {
        break;
      }
      Thread.sleep(5);
    }
    threads.forEach(Thread::interrupt);
    for (Thread thread : threads) {
      thread.join(5_000);
    }
    if (failure.get() != null) {
      throw new AssertionError("simulation thread failed", failure.get());
    }
    assertTrue(clock.get() >= WINDOW_NANOS, "the simulation covered 60 s of device time");
    for (Thread thread : threads) {
      assertEquals(Thread.State.TERMINATED, thread.getState(), thread.getName() + " ended");
    }
    return new Result(
        bulkSeats.get(), foregroundSeats.get(), pacing.pacedIntervalsTotal(), pacing.yieldedMsTotal());
  }

  /**
   * One seated run: counted if it starts inside the window, and advances the device clock by its
   * cost. It waits until every other caller is ready for the next seat choice both before it
   * advances the clock (so a caller that re-queues after the previous run is dated before this run,
   * as on the device) and before it releases the seat.
   */
  private static void run(
      AtomicLong clock, long costNanos, AtomicInteger seats, BooleanSupplier othersReady)
      throws InterruptedException {
    if (clock.get() < WINDOW_NANOS) {
      seats.incrementAndGet();
    }
    awaitReady(othersReady);
    clock.addAndGet(costNanos);
    awaitReady(othersReady);
  }

  private static void awaitReady(BooleanSupplier othersReady) throws InterruptedException {
    while (!othersReady.getAsBoolean()) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("simulation over");
      }
      Thread.onSpinWait();
    }
  }

  private static SessionAcquisitionRequest request(SessionAcquisitionRequest.Urgency urgency) {
    return SessionAcquisitionRequest.within(urgency, Duration.ofHours(1));
  }
}
