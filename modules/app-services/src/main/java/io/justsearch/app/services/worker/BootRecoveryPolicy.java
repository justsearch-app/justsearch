/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Declared BOOT-recovery policy for the Worker process (tempdoc 825) — the sibling of
 * the Worker's former {@code SupervisionPolicy} (deleted at item A11), for the one state
 * supervision could not reach: the worker NEVER started, so
 * there is no spawner holding a restart budget and no process to supervise.
 *
 * <p>The two policies are deliberately separate authorities over disjoint states, not two budgets for
 * the same one: {@code SupervisionPolicy} governed a worker that was running and faulted (the spawner
 * owns it), this one governs the Head re-attempting a bootstrap that failed. The handover between
 * them is a veto, not a shared counter — see {@link BootRecoveryDecision}.
 *
 * @param maxAttempts hard cap on boot-recovery attempts before the terminal give-up
 *     ({@code worker.spawn_recovery_exhausted}). Bounded rather than unbounded-with-capped-backoff so
 *     there is a terminal state for the isolated-backend fixture to fail fast on (825 §D5 decision 1)
 * @param baseBackoffMs backoff before the first re-attempt; doubles each attempt
 * @param maxBackoffMs ceiling on the exponential backoff
 */
public record BootRecoveryPolicy(int maxAttempts, long baseBackoffMs, long maxBackoffMs) {

  /**
   * Default attempt cap. Four attempts spread over ~2 minutes of backoff covers the measured
   * transient shape (821 §O.4: a PID-validation window lost to contended-host load) without leaving a
   * genuinely broken installation re-spawning a doomed worker forever.
   */
  public static final int DEFAULT_MAX_ATTEMPTS = 4;

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
}
