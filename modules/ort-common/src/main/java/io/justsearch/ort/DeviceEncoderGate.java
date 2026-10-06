/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.WeakHashMap;
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
 *   <li><b>Breaker</b> (Spec D §2). The breaker is open while a held seat that is not its
 *       session's first run has been held longer than {@link DeviceGatePolicy#stuckAfterMs()}, at
 *       any urgency; a session's first run (a cold run: kernel selection, an optimised-store miss)
 *       never opens it. While it is open, a foreground query leg with a keyword fallback is turned
 *       away on arrival ({@code RUN}, with {@link
 *       SessionAcquireDeadlineExceededException#breakerOpen()}), so it does not wait out its budget
 *       behind a stuck run. Every other caller queues as usual. It closes in the lock step that
 *       releases the last such seat; there is no timer and no probe.
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
  private final long stuckAfterNanos;

  private final ReentrantLock lock = new ReentrantLock();
  private final ArrayDeque<Waiter> foreground = new ArrayDeque<>();
  private final ArrayDeque<Waiter> background = new ArrayDeque<>();
  private final Set<Object> seatedSessions = Collections.newSetFromMap(new IdentityHashMap<>());
  private final Set<Waiter> held = Collections.newSetFromMap(new IdentityHashMap<>());
  // Sessions that have released a seat at least once: their next seat is not a first run. Weak,
  // so a closed session does not stay reachable through the process-lifetime gate.
  private final Set<Object> ranSessions = Collections.newSetFromMap(new WeakHashMap<>());
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
    this.stuckAfterNanos = TimeUnit.MILLISECONDS.toNanos(policy.stuckAfterMs());
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
   * @throws SessionAcquireDeadlineExceededException {@code GATE} when the deadline passes first;
   *     {@code RUN} with {@code breakerOpen} when the request is a foreground query leg with a
   *     keyword fallback and the device's breaker is open on arrival
   * @throws java.util.concurrent.CancellationException when the caller is cancelled
   * @throws InterruptedException when the waiting thread is interrupted
   */
  public Seat seat(Object session, SessionAcquisitionRequest request) throws InterruptedException {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(request, "request");
    boolean isForeground = request.urgency() == SessionAcquisitionRequest.Urgency.FOREGROUND;
    lock.lock();
    try {
      if (turnedAwayByBreaker(request) && breakerOpenSinceLocked().isPresent()) {
        throw new SessionAcquireDeadlineExceededException(
            "the device's breaker is open: a GPU run has been stuck past "
                + policy.stuckAfterMs() + " ms",
            SessionAcquireDeadlineExceededException.Phase.RUN,
            true);
      }
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

  /**
   * When the device's breaker opened, on this gate's clock, or empty while it is closed (Spec D
   * §2.1): the earliest moment a held seat that is not its session's first run passed {@link
   * DeviceGatePolicy#stuckAfterMs()}. It is derived from the held seats each time, so it is empty
   * from the release of the last such seat on.
   */
  public OptionalLong breakerOpenSinceNanos() {
    lock.lock();
    try {
      return breakerOpenSinceLocked();
    } finally {
      lock.unlock();
    }
  }

  private OptionalLong breakerOpenSinceLocked() {
    long now = nanoClock.getAsLong();
    long since = Long.MAX_VALUE;
    boolean open = false;
    for (Waiter seat : held) {
      if (seat.firstRunOfSession || now - seat.seatedAtNanos < stuckAfterNanos) continue;
      long stuckAt = seat.seatedAtNanos + stuckAfterNanos;
      if (!open || stuckAt - since < 0) since = stuckAt;
      open = true;
    }
    return open ? OptionalLong.of(since) : OptionalLong.empty();
  }

  /**
   * The callers the breaker turns away (Spec D §2.2): a foreground query leg that has a keyword
   * fallback ({@code mayCreateGpuSession} false) and is released at its deadline. Bulk work,
   * VECTOR-only legs, prewarm and the reranker queue as usual; CPU legs never reach the gate.
   */
  private static boolean turnedAwayByBreaker(SessionAcquisitionRequest request) {
    return request.urgency() == SessionAcquisitionRequest.Urgency.FOREGROUND
        && request.releaseCallerAtDeadline()
        && !request.mayCreateGpuSession();
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
    waiter.seatedAtNanos = nanoClock.getAsLong();
    waiter.firstRunOfSession = !ranSessions.contains(waiter.session);
    seated++;
    seatedSessions.add(waiter.session);
    held.add(waiter);
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
    held.remove(waiter);
    ranSessions.add(waiter.session);
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
    private long seatedAtNanos;
    private boolean firstRunOfSession;

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
