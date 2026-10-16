/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.time.Duration;

/**
 * The one-way embedding readiness of the query encoders (Spec D §4.2 item 3).
 *
 * <p>The embedding is reported ready the first time its query encoders are warm, or once the
 * readiness bound has passed while they are still not warm (a prewarm that keeps failing must not
 * hold readiness forever). After that it stays ready: a later GPU release or session re-creation
 * changes only the component-level state ({@code query_encoders_cold}), never the readiness that
 * was already reported.
 *
 * <p>One instance serves every service set of a process, so a service-set swap does not reset it.
 */
public final class QueryEncoderReadiness {
  private volatile boolean reached;
  private volatile boolean bounded;
  private volatile long boundStartNanos;
  private volatile long boundNanos;

  /** Starts the readiness bound; the first call wins, later calls keep its start and length. */
  public synchronized void startBound(Duration bound) {
    if (bounded) return;
    long nanos;
    try {
      nanos = Math.max(0L, bound.toNanos());
    } catch (ArithmeticException overflow) {
      nanos = Long.MAX_VALUE;
    }
    boundNanos = nanos;
    boundStartNanos = System.nanoTime();
    bounded = true;
  }

  /** Whether the embedding has been ready once, given whether its query encoders are warm now. */
  public boolean ready(boolean warmNow) {
    if (reached) return true;
    if (warmNow || (bounded && System.nanoTime() - boundStartNanos >= boundNanos)) {
      reached = true;
    }
    return reached;
  }
}
