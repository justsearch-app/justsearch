/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * One quit deadline, cut into numbered sub-budgets (Spec F §4.3, step 7).
 *
 * <p>The supervisor forces the Engine at {@code deadlineAtEpochMs − quitForceMarginMs}, so the
 * Engine's own budget is that instant minus now. Every wait the ordered close makes for a quit is
 * capped at {@code min(sub-budget, engineBudget − elapsed − QB_JVM_EXIT_RESERVE_MS)}: the close
 * finishes, cleanly or not, before the force instead of racing it.
 *
 * <p>Only a quit is budgeted. Restart, upgrade and hang closes keep today's waits, which is what
 * {@link #unbounded()} answers: {@link #waitFor} returns the caller's own wait unchanged.
 *
 * <p>The elapsed time is measured on the monotonic clock from the moment the budget is made, which
 * is when the quit arrived; the deadline itself is a wall-clock instant because that is what the
 * supervisor writes.
 */
public final class QuitBudget {

  /** QB1: admission, manifest and interactive cancellation; the chat stop starts here. */
  public static final long QB1_ADMISSION_MS = 100L;

  /** QB2: the cooperative window recorded producers and live work get to relinquish. */
  public static final long QB2_COOPERATIVE_MS = 2_500L;

  /**
   * QB2: the acknowledgement window after it, in which yielded durable bodies are handed off.
   *
   * <p>A body that has not yielded by then is abandoned to its durable replay at the next start
   * (S16-D1). The close then follows the D1 shutdown design, not an exit 0: the steps that need the
   * drain refuse, the exit is {@link EngineExit#FATAL_OR_UNCAUGHT}, and native work still running
   * selects the controlled hard stop. The quit still ends inside its budget.
   */
  public static final long QB2_CANCEL_ACK_MS = 500L;

  /** QB3: the llama-server stop through the chat controller, started at QB1, run in parallel. */
  public static final long QB3_CHAT_STOP_MS = 2_000L;

  /** QB4: extraction EOF, serial after QB2. */
  public static final long QB4_EXTRACTION_MS = 1_000L;

  /** QB5: child exit records and their fsync. */
  public static final long QB5_LEDGER_MS = 200L;

  /** Kept back from every wait so the JVM exit itself happens before the force. */
  public static final long QB_JVM_EXIT_RESERVE_MS = 300L;

  private static final QuitBudget UNBOUNDED = new QuitBudget(false, Long.MAX_VALUE, Long.MAX_VALUE,
      0L, System::nanoTime);

  private final boolean bounded;
  private final long deadlineAtEpochMs;
  private final long engineBudgetMs;
  private final long startedNanos;
  private final LongSupplier nanoClock;

  private QuitBudget(boolean bounded, long deadlineAtEpochMs, long engineBudgetMs,
      long startedNanos, LongSupplier nanoClock) {
    this.bounded = bounded;
    this.deadlineAtEpochMs = deadlineAtEpochMs;
    this.engineBudgetMs = engineBudgetMs;
    this.startedNanos = startedNanos;
    this.nanoClock = nanoClock;
  }

  /** Today's waits: restart, upgrade and hang closes, and every close that names no deadline. */
  public static QuitBudget unbounded() {
    return UNBOUNDED;
  }

  /** The budget of a quit whose supervisor forces at {@code deadlineAtEpochMs − margin}. */
  public static QuitBudget of(long deadlineAtEpochMs) {
    return of(deadlineAtEpochMs, System.currentTimeMillis(), System::nanoTime);
  }

  /** A quit that arrived without a deadline: the contract's quit deadline from now. */
  public static QuitBudget fromNow() {
    long now = System.currentTimeMillis();
    return of(now + EngineSupervisionPolicy.QUIT_DEADLINE_MS, now, System::nanoTime);
  }

  /** Test seam: the wall-clock instant the quit arrived and the monotonic clock to measure on. */
  static QuitBudget of(long deadlineAtEpochMs, long nowEpochMs, LongSupplier nanoClock) {
    Objects.requireNonNull(nanoClock, "nanoClock");
    long budget;
    try {
      budget = Math.subtractExact(
          Math.subtractExact(deadlineAtEpochMs, EngineSupervisionPolicy.QUIT_FORCE_MARGIN_MS),
          nowEpochMs);
    } catch (ArithmeticException overflow) {
      budget = deadlineAtEpochMs > nowEpochMs ? Long.MAX_VALUE : 0L;
    }
    return new QuitBudget(true, deadlineAtEpochMs, Math.max(0L, budget), nanoClock.getAsLong(),
        nanoClock);
  }

  /** Whether this close is a budgeted quit. */
  public boolean bounded() {
    return bounded;
  }

  /** The supervisor's deadline; {@link Long#MAX_VALUE} when unbounded. */
  public long deadlineAtEpochMs() {
    return deadlineAtEpochMs;
  }

  /** {@code deadlineAtEpochMs − quitForceMarginMs − now} at the moment the quit arrived. */
  public long engineBudgetMs() {
    return engineBudgetMs;
  }

  /** Milliseconds since the quit arrived. */
  public long elapsedMs() {
    return Math.max(0L, (nanoClock.getAsLong() - startedNanos) / 1_000_000L);
  }

  /** What any wait may still take: {@code engineBudget − elapsed − reserve}, never negative. */
  public long remainingMs() {
    if (!bounded) return Long.MAX_VALUE;
    long left = engineBudgetMs == Long.MAX_VALUE
        ? Long.MAX_VALUE : engineBudgetMs - elapsedMs() - QB_JVM_EXIT_RESERVE_MS;
    return Math.max(0L, left);
  }

  /**
   * The wait a step may make.
   *
   * @param today the step's wait outside a quit; returned unchanged when unbounded
   * @param subBudgetMs the step's numbered sub-budget
   * @return {@code today} when unbounded, else {@code min(subBudgetMs, remainingMs())}
   */
  public Duration waitFor(Duration today, long subBudgetMs) {
    Objects.requireNonNull(today, "today");
    if (!bounded) return today;
    return Duration.ofMillis(Math.max(0L, Math.min(subBudgetMs, remainingMs())));
  }

  @Override
  public String toString() {
    return bounded
        ? "QuitBudget[deadlineAtEpochMs=" + deadlineAtEpochMs + ", engineBudgetMs=" + engineBudgetMs
            + ", elapsedMs=" + elapsedMs() + "]"
        : "QuitBudget[unbounded]";
  }
}
