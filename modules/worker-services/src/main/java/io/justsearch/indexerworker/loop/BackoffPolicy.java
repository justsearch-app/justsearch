/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

/**
 * Pure exponential-backoff arithmetic — the law-bearing core extracted from {@link BackfillScheduler}
 * (tempdoc 555 §4, functional-core/imperative-shell). The scheduler is clock-entangled
 * (System.currentTimeMillis for the next-retry timestamp); this isolates the pure delay computation
 * so it is testable without a clock and mutation-targeted.
 *
 * <p>The LAW: delay = min(baseMs * 2^consecutiveFailures, capMs) — exponential in the failure count,
 * clamped to capMs, and non-decreasing in the failure count until the cap. A silent off-by-one in the
 * exponent (e.g. {@code 1L << (n-1)}) or a wrong cap comparison causes retry starvation or thrashing.
 */
final class BackoffPolicy {

  /** Cap matching the SPLADE retry ceiling in BackfillScheduler (60s). */
  static final long SPLADE_BACKOFF_CAP_MS = 60_000L;

  private BackoffPolicy() {}

  /**
   * Exponential backoff with a ceiling: {@code min(baseMs * 2^consecutiveFailures, capMs)}.
   *
   * @param baseMs the base (1-failure) delay in milliseconds; clamped to >= 0
   * @param consecutiveFailures the number of consecutive failures so far; clamped to >= 0
   * @param capMs the maximum delay in milliseconds
   */
  static long backoffMs(long baseMs, int consecutiveFailures, long capMs) {
    long base = Math.max(0L, baseMs);
    int failures = Math.max(0, consecutiveFailures);
    if (base == 0) return Math.min(0L, capMs);
    if (base >= capMs || failures >= Long.SIZE - 1 || base > (capMs >> failures)) {
      return capMs;
    }
    return base << failures;
  }

  /** SPLADE retry backoff: exponential on {@code baseMs}, capped at {@link #SPLADE_BACKOFF_CAP_MS}. */
  static long spladeBackoffMs(long baseMs, int consecutiveFailures) {
    return backoffMs(baseMs, consecutiveFailures, SPLADE_BACKOFF_CAP_MS);
  }
}
