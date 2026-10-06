/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * One order for the GPU runs of one device, across every session handle on it (Spec D §3.2;
 * ADR-0050 decision 2). It replaces the per-handle inference semaphore as the GPU permit: a GPU
 * lease holds a {@link Seat} for as long as the lease is held, and CPU leases are never gated.
 *
 * <p>Seating rules:
 *
 * <ol>
 *   <li><b>Capacity.</b> At most {@link DeviceGatePolicy#seats()} seats are held at once, and a
 *       session never holds two.
 *   <li><b>Order.</b> Two FIFO queues, foreground and background. When a seat frees, the oldest
 *       foreground waiter whose session is free is seated; if there is none, the oldest background
 *       waiter whose session is free.
 *   <li><b>Aging.</b> The oldest background waiter is aged once it has waited {@link
 *       DeviceGatePolicy#agingWallMs()}, or once {@link DeviceGatePolicy#agingSeatings()}
 *       foreground waiters were seated since it queued. An aged waiter is seated before the
 *       foreground waiters, and every foreground waiter queued at that moment is marked passed.
 *       Aging is suspended while a passed waiter still waits, so a foreground call is overtaken by
 *       at most one aged run.
 *   <li><b>Never "no".</b> {@link #seat} returns a seat, or fails only for the caller's own
 *       deadline ({@code GATE}), cancellation or interrupt. It never redirects anything to the CPU.
 *   <li><b>Waiter removal.</b> A waiter whose deadline passes, or whose caller is cancelled or
 *       interrupted, is removed under the lock. If it was granted a seat in the same instant, that
 *       seat is released at once and the next waiter is seated.
 * </ol>
 *
 * <p>A foreground wait is therefore at most the remaining time of the seated runs, plus one aged
 * run, plus the foreground calls queued before it. Seats belong to the session they were taken for,
 * so the seat of a session that was retired while its run was in flight still counts against the
 * device until that run releases it.
 *
 * <p>Built on a {@link ReentrantLock} with one {@link Condition} per waiter, not {@code
 * synchronized}: waiters may be virtual threads and need timed, cancellable waits. The clock only
 * dates waiters for aging; waits are bounded by each caller's own deadline.
 */
public final class DeviceEncoderGate {

  /** How often a waiter re-reads its caller's cancellation signal, which nothing signals. */
  private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

  /** Per thread: total time spent in {@link #seat} waiting for a seat, at any gate. */
  private static final ThreadLocal<long[]> WAITED_NANOS = ThreadLocal.withInitial(() -> new long[1]);

  private final DeviceGatePolicy policy;
  private final LongSupplier nanoClock;
  private final long agingWallNanos;

  private final ReentrantLock lock = new ReentrantLock();
  private final ArrayDeque<Waiter> foreground = new ArrayDeque<>();
  private final ArrayDeque<Waiter> background = new ArrayDeque<>();
  private final Set<Object> seatedSessions = Collections.newSetFromMap(new IdentityHashMap<>());
  private int seated;
  private long foregroundSeatings;
  private int passedWaiting;

  /**
   * @param policy seat count, aging and recomposition parameters
   * @param nanoClock monotonic clock that dates waiters for aging ({@link System#nanoTime()} in
   *     production)
   */
  public DeviceEncoderGate(DeviceGatePolicy policy, LongSupplier nanoClock) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    this.agingWallNanos = TimeUnit.MILLISECONDS.toNanos(policy.agingWallMs());
  }

  /** The parameters this gate seats by. */
  public DeviceGatePolicy policy() {
    return policy;
  }

  /**
   * Waits for a seat for one GPU run of {@code session}, in the order of {@code request}'s urgency.
   *
   * @param session the native session the run is for; one session never holds two seats
   * @param request the caller's urgency, deadline and cancellation signal
   * @return the seat, which the caller must close exactly once when its run is over
   * @throws SessionAcquireDeadlineExceededException {@code GATE} when the deadline passes first
   * @throws java.util.concurrent.CancellationException when the caller is cancelled
   * @throws InterruptedException when the waiting thread is interrupted
   */
  public Seat seat(Object session, SessionAcquisitionRequest request) throws InterruptedException {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(request, "request");
    boolean isForeground = request.urgency() == SessionAcquisitionRequest.Urgency.FOREGROUND;
    lock.lock();
    try {
      Waiter waiter =
          new Waiter(session, isForeground, lock.newCondition(), nanoClock.getAsLong(),
              foregroundSeatings);
      (isForeground ? foreground : background).addLast(waiter);
      dispatch();
      try {
        while (!waiter.granted) {
          long remaining = request.remainingNanos();
          waiter.wakeup.awaitNanos(Math.min(remaining, POLL_NANOS));
        }
      } catch (Throwable left) {
        withdraw(waiter);
        throw left;
      } finally {
        WAITED_NANOS.get()[0] += Math.max(0L, nanoClock.getAsLong() - waiter.queuedAtNanos);
      }
      return new Seat(waiter);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Total time the current thread has spent waiting for a seat at any device gate, measured by each
   * gate's clock ({@link System#nanoTime()} in production). It only grows.
   *
   * <p>Bulk pacing reads it so that the time a bulk slice waited at the device counts as time it
   * yielded, not as GPU work it did (Spec D §3.3 and §3.5).
   */
  public static long waitedNanosOnCurrentThread() {
    return WAITED_NANOS.get()[0];
  }

  /** Callers waiting for a seat now. */
  int waiting() {
    lock.lock();
    try {
      return foreground.size() + background.size();
    } finally {
      lock.unlock();
    }
  }

  /** Seats the waiters the free capacity allows, in the gate's order. Called under the lock. */
  private void dispatch() {
    while (seated < policy.seats()) {
      Waiter next = agedBackgroundWaiter();
      if (next != null) {
        markForegroundPassed();
      } else {
        next = firstFree(foreground);
        if (next == null) next = firstFree(background);
      }
      if (next == null) return;
      grant(next);
    }
  }

  /** The oldest background waiter when it is aged and may overtake the foreground queue. */
  private Waiter agedBackgroundWaiter() {
    if (passedWaiting > 0 || firstFree(foreground) == null) return null;
    Waiter head = background.peekFirst();
    if (head == null || seatedSessions.contains(head.session)) return null;
    boolean agedByWall = nanoClock.getAsLong() - head.queuedAtNanos >= agingWallNanos;
    boolean agedBySeatings =
        foregroundSeatings - head.foregroundSeatingsAtQueue >= policy.agingSeatings();
    return agedByWall || agedBySeatings ? head : null;
  }

  private void markForegroundPassed() {
    for (Waiter waiter : foreground) {
      if (!waiter.passed) {
        waiter.passed = true;
        passedWaiting++;
      }
    }
  }

  private Waiter firstFree(ArrayDeque<Waiter> queue) {
    for (Waiter waiter : queue) {
      if (!seatedSessions.contains(waiter.session)) return waiter;
    }
    return null;
  }

  private void grant(Waiter waiter) {
    dequeue(waiter);
    waiter.granted = true;
    seated++;
    seatedSessions.add(waiter.session);
    if (waiter.foreground) foregroundSeatings++;
    waiter.wakeup.signal();
  }

  private void dequeue(Waiter waiter) {
    (waiter.foreground ? foreground : background).remove(waiter);
    if (waiter.passed) passedWaiting--;
  }

  /** Removes a waiter that is leaving; a seat granted to it in the same instant is released. */
  private void withdraw(Waiter waiter) {
    if (waiter.granted) {
      release(waiter);
    } else {
      dequeue(waiter);
      dispatch();
    }
  }

  private void release(Waiter waiter) {
    if (waiter.released) return;
    waiter.released = true;
    seated--;
    seatedSessions.remove(waiter.session);
    dispatch();
  }

  /** One held seat. Closing it is idempotent; the first close frees the seat. */
  public final class Seat implements AutoCloseable {
    private final Waiter waiter;

    private Seat(Waiter waiter) {
      this.waiter = waiter;
    }

    @Override
    public void close() {
      lock.lock();
      try {
        release(waiter);
      } finally {
        lock.unlock();
      }
    }
  }

  /** A caller waiting for, or holding, a seat. Guarded by the gate's lock. */
  private static final class Waiter {
    private final Object session;
    private final boolean foreground;
    private final Condition wakeup;
    private final long queuedAtNanos;
    private final long foregroundSeatingsAtQueue;
    private boolean passed;
    private boolean granted;
    private boolean released;

    private Waiter(
        Object session,
        boolean foreground,
        Condition wakeup,
        long queuedAtNanos,
        long foregroundSeatingsAtQueue) {
      this.session = session;
      this.foreground = foreground;
      this.wakeup = wakeup;
      this.queuedAtNanos = queuedAtNanos;
      this.foregroundSeatingsAtQueue = foregroundSeatingsAtQueue;
    }
  }
}
