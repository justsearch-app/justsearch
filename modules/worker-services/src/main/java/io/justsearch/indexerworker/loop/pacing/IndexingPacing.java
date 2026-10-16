/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.pacing;

import io.justsearch.ort.DeviceEncoderGate;
import io.justsearch.core.scheduling.BulkPacingInput;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Worker's single indexing-pacing policy (tempdoc 885 item 3): a <b>duty cycle</b> driven by
 * {@link ForegroundLoad}, replacing the breath-hold pause that {@code isUserActive()} drove at 16
 * call sites.
 *
 * <p>The contract each call site gets is one method, {@link #pace()}, placed at a natural work
 * boundary (after a batch, after a document, at a walk throttle point). The policy measures the
 * wall time since it last released <i>that thread</i> — the thread's work slice — and, while
 * foreground work is in flight, yields the wall time that brings the caller's share of the
 * interval down toward {@code dutyPct}. Nothing stops: at the default 20% the loop still indexes
 * roughly one fifth of the time under a continuous search load, where the old pause indexed
 * nothing at all.
 *
 * <p><b>Time waiting for a device seat is yield, not work.</b> Spec D §3.3: "pace points every ≤
 * {@code bulkPaceTargetMs} of GPU work". A bulk slice's GPU runs wait at the {@link
 * DeviceEncoderGate}, which seats foreground first; that wait is the device yielded to foreground,
 * so the interval's work is its wall time less the thread's gate wait ({@link
 * DeviceEncoderGate#waitedNanosOnCurrentThread()}), and the wall-clock pause owed for that work is
 * reduced by the wait already spent. A slice that waited at least {@code (100 - dutyPct) / dutyPct}
 * times its work is not paused at all. Otherwise pacing and the gate's aging would each take the
 * foreground's share, and bulk would get less than {@code dutyPct}.
 *
 * <p><b>{@code dutyPct} is a target, and the bounds below bias the error toward indexing, not away
 * from it.</b> A work unit longer than {@link #MAX_WORK_SLICE_MS} claims credit for only that much,
 * and the yield it earns is capped at {@link #MAX_DEBT_MS}, so a single long batch runs at a duty
 * <i>above</i> the target — a 10 s batch yields 2 s (≈83%), not 40 s. The exact ratio holds for
 * work units at or under the slice cap, which is the common case (a document, a sub-batch, a walk
 * tick). Erring toward indexing is deliberate: the failure this item removes was indexing starved
 * to zero, and an unbounded debt would reintroduce multi-second stalls inside the loop.
 *
 * <p>Bounds that make the yield safe to sit inside a loop that must stay responsive:
 *
 * <ul>
 *   <li>A work slice counts for at most {@link #MAX_WORK_SLICE_MS} — a thread that was parked
 *       between cycles must not come back owing seconds of yield for work it never did.
 *   <li>Accrued yield debt is capped at {@link #MAX_DEBT_MS}.
 *   <li>The yield is slept in {@link #MAX_SLEEP_CHUNK_MS} chunks and abandoned as soon as
 *       foreground work drains, so releasing the machine costs at most one chunk.
 *   <li>An interrupt during the yield re-sets the thread's interrupt flag and returns immediately;
 *       every call site already treats interruption as "stop".
 * </ul>
 *
 * <p>{@code dutyPct == 100} disables throttling entirely (the yield formula evaluates to zero), so
 * {@link #unthrottled()} is the honest no-op for scaffolding and tests rather than a null check at
 * every call site.
 *
 * <p><b>Chat generation is the second input of this one governor</b> (Spec E §1.4, step E2). While
 * Switch A's {@link BulkPacingInput#dutyFloor()} holds and no search runs, the duty is the
 * generation duty and the debt cap its own; when search and generation overlap, the search duty
 * governs (ADR-0048 unchanged):
 *
 * <pre>
 * effectiveDuty = searchBusy ? dutyPct     : dutyFloor ? generationDutyPct : 100
 * maxDebt       = searchBusy ? MAX_DEBT_MS : generationMaxDebtMs
 * </pre>
 */
public final class IndexingPacing {

  private static final Logger log = LoggerFactory.getLogger(IndexingPacing.class);

  /** Longest work slice one {@link #pace()} interval may claim credit for. */
  public static final long MAX_WORK_SLICE_MS = 1_000L;

  /** Longest yield debt one thread may accrue. */
  public static final long MAX_DEBT_MS = 2_000L;

  /** Longest single sleep inside a yield; bounds how late a drain is noticed. */
  public static final long MAX_SLEEP_CHUNK_MS = 100L;

  /** Default duty under foreground load ({@code justsearch.indexing.foreground_duty_pct}). */
  public static final int DEFAULT_DUTY_PCT = 20;

  /** Default cooldown ({@code justsearch.indexing.foreground_cooldown_ms}). */
  public static final long DEFAULT_COOLDOWN_MS = 500L;

  private static final long NANOS_PER_MS = TimeUnit.MILLISECONDS.toNanos(1);
  private static final long LOG_INTERVAL_MS = 30_000L;
  private static final long DUTY_WINDOW_MS = 60_000L;

  /** Sleep seam so pacing arithmetic is testable without wall-clock waits. */
  @FunctionalInterface
  public interface Sleeper {
    void sleepMs(long millis) throws InterruptedException;
  }

  private final ForegroundLoad load;
  private final BulkPacingInput bulk;
  private final int dutyPct;
  private final long cooldownMs;
  private final LongSupplier clockMs;
  private final LongSupplier nanoClock;
  private final Sleeper sleeper;

  /**
   * Per-thread {@code [lastReleaseNanos, debtNanos, gateWaitedNanosAtRelease]}; {@code
   * Long.MIN_VALUE} = never paced.
   */
  private final ThreadLocal<long[]> threadState =
      ThreadLocal.withInitial(() -> new long[] {Long.MIN_VALUE, 0L, 0L});

  private final AtomicLong pacedIntervalsTotal = new AtomicLong();
  private final AtomicLong yieldedMsTotal = new AtomicLong();
  private final AtomicLong lastLogAtMs = new AtomicLong();
  private final AtomicLong loggedIntervalsAtLastLog = new AtomicLong();

  private long windowStartMs;
  private long windowWorkedNanos;
  private long windowYieldedNanos;
  private long lastWindowDutyPct = 100L;

  public IndexingPacing(ForegroundLoad load, int dutyPct, long cooldownMs) {
    this(load, BulkPacingInput.NEVER, dutyPct, cooldownMs);
  }

  /** @param bulk Switch A's generation input (Spec E step E2) */
  public IndexingPacing(ForegroundLoad load, BulkPacingInput bulk, int dutyPct, long cooldownMs) {
    this(load, bulk, dutyPct, cooldownMs, System::currentTimeMillis, System::nanoTime, Thread::sleep);
  }

  public IndexingPacing(
      ForegroundLoad load,
      int dutyPct,
      long cooldownMs,
      LongSupplier clockMs,
      LongSupplier nanoClock,
      Sleeper sleeper) {
    this(load, BulkPacingInput.NEVER, dutyPct, cooldownMs, clockMs, nanoClock, sleeper);
  }

  public IndexingPacing(
      ForegroundLoad load,
      BulkPacingInput bulk,
      int dutyPct,
      long cooldownMs,
      LongSupplier clockMs,
      LongSupplier nanoClock,
      Sleeper sleeper) {
    this.load = load;
    this.bulk = Objects.requireNonNull(bulk, "bulk");
    this.clockMs = clockMs;
    this.nanoClock = nanoClock;
    this.sleeper = sleeper;
    int duty = dutyPct;
    if (duty < 1 || duty > 100) {
      log.warn(
          "justsearch.indexing.foreground_duty_pct={} is outside 1..100 — clamping to {}",
          dutyPct,
          duty < 1 ? 1 : 100);
      duty = duty < 1 ? 1 : 100;
    }
    this.dutyPct = duty;
    this.cooldownMs = Math.max(0L, cooldownMs);
    this.windowStartMs = clockMs.getAsLong();
  }

  /** A policy that never throttles — scaffolding, tests, and the deferred-runtime path. */
  public static IndexingPacing unthrottled() {
    return new IndexingPacing(new ForegroundLoad(), 100, 0L);
  }

  public ForegroundLoad foregroundLoad() {
    return load;
  }

  public int dutyPct() {
    return dutyPct;
  }

  public long cooldownMs() {
    return cooldownMs;
  }

  /**
   * Whether indexing is paced now: search work is busy ({@link #searchBusy()}) or chat generation
   * holds Switch A's duty floor.
   */
  public boolean foregroundBusy() {
    return searchBusy() || bulk.dutyFloor();
  }

  /**
   * Whether foreground search work is in flight, or completed recently enough that the next request
   * of a burst has not arrived yet. The cooldown is what keeps a stream of short queries from
   * reading as idle in the gaps between them.
   */
  public boolean searchBusy() {
    if (load.inFlight() > 0) {
      return true;
    }
    long last = load.lastForegroundAtMs();
    return last > 0L && clockMs.getAsLong() - last < cooldownMs;
  }

  /** The duty that applies now: the search duty, else the generation duty, else 100. */
  int effectiveDutyPct() {
    if (searchBusy()) return dutyPct;
    if (!bulk.dutyFloor()) return 100;
    return Math.max(1, Math.min(100, bulk.generationDutyPct(dutyPct)));
  }

  /** The yield-debt cap paired with {@link #effectiveDutyPct()}. */
  long effectiveMaxDebtMs() {
    return searchBusy() ? MAX_DEBT_MS : Math.max(0L, bulk.generationMaxDebtMs(MAX_DEBT_MS));
  }

  /**
   * The throttle point. Accounts the work done since this thread was last released (its wall time
   * less the time it waited at a device gate) and, while {@link #foregroundBusy()}, yields the wall
   * time that brings the thread's share of the interval, gate wait included, down to the effective
   * duty (the search duty {@link #dutyPct()}, or the generation duty while only chat generates).
   */
  public void pace() {
    long[] st = threadState.get();
    long now = nanoClock.getAsLong();
    long waitedTotal = DeviceEncoderGate.waitedNanosOnCurrentThread();
    long workedNanos = 0L;
    long waitedNanos = 0L;
    if (st[0] != Long.MIN_VALUE) {
      long wallNanos = Math.max(0L, now - st[0]);
      waitedNanos = Math.min(wallNanos, Math.max(0L, waitedTotal - st[2]));
      workedNanos = Math.min(wallNanos - waitedNanos, MAX_WORK_SLICE_MS * NANOS_PER_MS);
    }

    if (!foregroundBusy()) {
      st[0] = now;
      st[1] = 0L;
      st[2] = waitedTotal;
      recordWindow(workedNanos, waitedNanos);
      return;
    }

    // The yield owed for the work, less the time the slice already yielded at the device gate.
    int duty = effectiveDutyPct();
    long debtNanos = st[1] + (workedNanos * (100L - duty)) / duty - waitedNanos;
    debtNanos = Math.max(0L, Math.min(debtNanos, effectiveMaxDebtMs() * NANOS_PER_MS));

    long yieldedNanos = 0L;
    while (debtNanos >= NANOS_PER_MS && foregroundBusy()) {
      // Each chunk re-reads the inputs: a search that ends mid-yield leaves the generation cap.
      debtNanos = Math.min(debtNanos, effectiveMaxDebtMs() * NANOS_PER_MS);
      if (debtNanos < NANOS_PER_MS) break;
      long chunkMs = Math.min(debtNanos / NANOS_PER_MS, MAX_SLEEP_CHUNK_MS);
      try {
        sleeper.sleepMs(chunkMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      debtNanos -= chunkMs * NANOS_PER_MS;
      yieldedNanos += chunkMs * NANOS_PER_MS;
    }

    st[0] = nanoClock.getAsLong();
    st[1] = foregroundBusy() ? Math.max(0L, debtNanos) : 0L;
    st[2] = DeviceEncoderGate.waitedNanosOnCurrentThread();

    recordWindow(workedNanos, yieldedNanos + waitedNanos);
    if (yieldedNanos > 0L) {
      pacedIntervalsTotal.incrementAndGet();
      yieldedMsTotal.addAndGet(yieldedNanos / NANOS_PER_MS);
      maybeLog();
    }
  }

  /**
   * {@link #pace()} adapted to the {@code BooleanSupplier} abort-checker slots the prune and sync
   * walks already call at their throttle points. Always returns {@code false}: under a duty cycle
   * the walk is slowed, never abandoned (abandoning it was the old breath-hold behaviour and is the
   * starvation this item removes).
   *
   * @return always {@code false} — never abort
   */
  public boolean paceAndContinue() {
    pace();
    return false;
  }

  /** Number of {@link #pace()} calls that actually yielded. */
  public long pacedIntervalsTotal() {
    return pacedIntervalsTotal.get();
  }

  /** Total milliseconds yielded to foreground work. */
  public long yieldedMsTotal() {
    return yieldedMsTotal.get();
  }

  /**
   * Observed duty over the current (or, if it has no samples yet, the previous) window:
   * {@code worked / (worked + yielded)} as a percentage, {@code 0..100}.
   *
   * <p>The window accounts <b>every</b> {@link #pace()} interval, contended or not — an uncontended
   * interval contributes its work slice and a zero yield. So this reads 100 while nothing is
   * throttled, and during a mixed window it is the duty <i>over the whole window</i>, not over the
   * throttled part of it: a minute that spent 50 s uncontended and 10 s contended reads far above
   * {@link #dutyPct()}. That is the intended question for a field gauge ("what fraction of the last
   * minute did indexing actually work"), but it means the gauge converges on {@link #dutyPct()}
   * only under sustained load.
   */
  public synchronized long observedDutyPct() {
    long worked = windowWorkedNanos;
    long yielded = windowYieldedNanos;
    if (worked + yielded > 0L) {
      return computeDutyPct(worked, yielded);
    }
    return lastWindowDutyPct;
  }

  private static long computeDutyPct(long workedNanos, long yieldedNanos) {
    long total = workedNanos + yieldedNanos;
    if (total <= 0L) {
      return 100L;
    }
    return Math.round((workedNanos * 100.0) / total);
  }

  private synchronized void recordWindow(long workedNanos, long yieldedNanos) {
    long now = clockMs.getAsLong();
    if (now - windowStartMs >= DUTY_WINDOW_MS) {
      lastWindowDutyPct = computeDutyPct(windowWorkedNanos, windowYieldedNanos);
      windowStartMs = now;
      windowWorkedNanos = 0L;
      windowYieldedNanos = 0L;
    }
    windowWorkedNanos += workedNanos;
    windowYieldedNanos += yieldedNanos;
  }

  /**
   * One INFO line per {@link #LOG_INTERVAL_MS} while pacing is happening. The shared Engine
   * logging configuration controls {@code io.justsearch} through {@code JUSTSEARCH_LOG_LEVEL},
   * defaulting to INFO so the duty cycle is observable in normal runs.
   */
  private void maybeLog() {
    long now = clockMs.getAsLong();
    long last = lastLogAtMs.get();
    if (now - last < LOG_INTERVAL_MS || !lastLogAtMs.compareAndSet(last, now)) {
      return;
    }
    long intervals = pacedIntervalsTotal.get();
    long sinceLast = intervals - loggedIntervalsAtLastLog.getAndSet(intervals);
    log.info(
        "Indexing paced by foreground load: {} yields in the last {}s ({} total), {} ms yielded"
            + " total, observed duty {}% (target {}%), foreground inFlight={} cooldownMs={}"
            + " chatGenerating={}",
        sinceLast,
        LOG_INTERVAL_MS / 1000,
        intervals,
        yieldedMsTotal.get(),
        observedDutyPct(),
        effectiveDutyPct(),
        load.inFlight(),
        cooldownMs,
        bulk.dutyFloor());
  }
}
