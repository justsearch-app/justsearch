/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.pacing;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Worker-local gauge of in-flight <b>foreground</b> work (tempdoc 885 item 3).
 *
 * <p>Foreground means "a search-family RPC the user is waiting on". Before this type the Worker
 * inferred user activity from a wall-clock byte the Head wrote into the memory-mapped signal file
 * ({@code MmfWorkerSignalBus.isUserActive()}, a 2000 ms window), which made the Worker's pacing
 * depend on a Head-side signal, on a clock instead of on load, and — because the verb was a full
 * pause — starved indexing outright under a continuous search loop (tempdoc 885 baseline arm (c):
 * 699 of 5184 documents in 22 minutes).
 *
 * <p>This gauge is the quantity the policy is actually about: how many foreground requests are
 * executing <i>right now</i>. It is deliberately a plain {@code worker-services} type with no gRPC
 * or MMF dependency — whatever feeds it is a thin adapter, which is why the gauge survived the
 * Head/Worker merge (lane F stage A) that deleted both. Until item A9 the producer was
 * {@code ForegroundLoadInterceptor}, a gRPC {@code ServerInterceptor}; since A9 it is
 * {@code ForegroundLoadGate}, wrapping the Engine's in-process port adapter.
 *
 * <p>Thread-safe: incremented and decremented on whichever caller thread entered the search port,
 * read from the indexing loop and backfill threads.
 */
public final class ForegroundLoad {

  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicLong lastForegroundAtMs = new AtomicLong(0L);
  private final AtomicLong startedTotal = new AtomicLong(0L);
  private final LongSupplier clockMs;

  public ForegroundLoad() {
    this(System::currentTimeMillis);
  }

  /**
   * @param clockMs epoch-millis clock (injectable so pacing tests are deterministic)
   */
  public ForegroundLoad(LongSupplier clockMs) {
    this.clockMs = clockMs;
  }

  /** Records the start of one foreground call. */
  public void started() {
    inFlight.incrementAndGet();
    startedTotal.incrementAndGet();
    lastForegroundAtMs.set(clockMs.getAsLong());
  }

  /**
   * Records the completion of one foreground call — normal completion, error, and cancellation all
   * land here, so a cancelled stream cannot leak a permanently-elevated gauge. Clamped at zero: an
   * unbalanced decrement must not make the gauge negative (which would read as "never busy").
   */
  public void finished() {
    inFlight.updateAndGet(v -> v > 0 ? v - 1 : 0);
    lastForegroundAtMs.set(clockMs.getAsLong());
  }

  /** Number of foreground calls currently executing. */
  public int inFlight() {
    return inFlight.get();
  }

  /**
   * Epoch-millis of the most recent foreground start or completion, {@code 0} if none has happened
   * in this process. Drives the cooldown that keeps a burst of short queries from reading as idle
   * between calls.
   */
  public long lastForegroundAtMs() {
    return lastForegroundAtMs.get();
  }

  /** Total foreground calls observed since start (monotonic; attribution for the after-run). */
  public long startedTotal() {
    return startedTotal.get();
  }
}
