/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Bounded policy shared by generic component recovery actions. The component registry owns each
 * component's cumulative attempt count; this value caps attempts within one unavailable episode.
 *
 * @param maxAttempts hard cap on physical recovery attempts within one episode
 * @param baseBackoffMs backoff before the first re-attempt; doubles each attempt
 * @param maxBackoffMs ceiling on the exponential backoff
 */
public record BootRecoveryPolicy(int maxAttempts, long baseBackoffMs, long maxBackoffMs) {

  /**
   * One initial start plus two admitted physical recovery attempts is the shipped lifecycle budget.
   */
  public static final int DEFAULT_MAX_ATTEMPTS = 2;

  /**
   * Default first backoff. One health-monitor poll interval
   * ({@link KnowledgeServerHealthMonitor#DEFAULT_POLL_INTERVAL_MS}) — a shorter value cannot be
   * honoured anyway, because the arm only runs on a tick.
   */
  public static final long DEFAULT_BASE_BACKOFF_MS = 10_000;

  /** Default backoff ceiling: 60s, so the last attempts do not stretch the arc past ~2 minutes. */
  public static final long DEFAULT_MAX_BACKOFF_MS = 60_000;

  public BootRecoveryPolicy {
    if (maxAttempts < 0) {
      throw new IllegalArgumentException("maxAttempts must be >= 0");
    }
    if (baseBackoffMs < 0 || maxBackoffMs < 0) {
      throw new IllegalArgumentException("backoffs must be >= 0");
    }
  }

  /** The shipped default policy. */
  public static BootRecoveryPolicy defaults() {
    return new BootRecoveryPolicy(
        DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_BACKOFF_MS, DEFAULT_MAX_BACKOFF_MS);
  }

  /** Exponential delay before the numbered recovery attempt, capped without overflow. */
  public long backoffMs(int nextAttempt) {
    if (nextAttempt < 1 || baseBackoffMs == 0 || maxBackoffMs == 0) return 0;
    int shift = Math.min(nextAttempt - 1, Long.SIZE - 2);
    long multiplier = 1L << shift;
    if (baseBackoffMs > maxBackoffMs / multiplier) return maxBackoffMs;
    return Math.min(maxBackoffMs, baseBackoffMs * multiplier);
  }
}
