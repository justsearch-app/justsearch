/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

/**
 * Declared supervision policy for the Brain (llama-server) process. Its values are checked against
 * {@code governance/supervision-contract.v1.json}.
 *
 * <p>Spec A §7.2: there is no same-configuration retry loop. The chat controller decides every
 * relaunch from the classified exit ({@link LlamaServerExitClassifier}) through {@link
 * ChatCrashPolicy}; the component monitor admits no automatic
 * generative attempt, only an operator's explicit retry. A start that fails is not retried with
 * the same configuration ({@link #STARTING_SAME_CONFIG_RETRIES}); a server that crashes after
 * {@link #DEFAULT_STABILITY_WINDOW_MS} of READY earns {@link #SAME_CONFIG_RESTARTS_PER_STABLE_WINDOW}
 * same-configuration restart.
 *
 * <p>Every timer here runs on the monotonic clock. Probe and kill timeouts stay operational
 * constants in {@link LlamaServerOps}: they are probe tuning, not the supervision contract.
 *
 * @param consecutiveFailuresBeforeRestart periodic-health failures (process alive but {@code /health}
 *     unresponsive — the hang/"liveness" signal) before a component failure is reported
 * @param periodicHealthIntervalMs interval between periodic {@code /health} probes while ONLINE
 * @param healthCheckTimeoutMs cold-start deadline for the server to become {@code /health}-ready
 *     (overridable via {@code justsearch.inference.health_check_timeout_ms} for eval/slow installs)
 */
public record BrainSupervisionPolicy(
    int consecutiveFailuresBeforeRestart,
    long periodicHealthIntervalMs,
    long healthCheckTimeoutMs) {
  /** Default hang threshold (historical {@code CONSECUTIVE_FAILURES_BEFORE_RESTART}). */
  public static final int DEFAULT_CONSECUTIVE_FAILURES_BEFORE_RESTART = 3;
  /** Default periodic-health interval (historical {@code PERIODIC_HEALTH_INTERVAL_MS}). */
  public static final long DEFAULT_PERIODIC_HEALTH_INTERVAL_MS = 30_000;
  /** Default cold-start health deadline (historical {@code HEALTH_CHECK_TIMEOUT_MS}). */
  public static final long DEFAULT_HEALTH_CHECK_TIMEOUT_MS = 120_000;
  /**
   * How long a server must have been READY, on the monotonic clock, for an unexpected exit of class
   * OTHER to earn a same-configuration restart (Spec A §3.3, T28).
   */
  public static final long DEFAULT_STABILITY_WINDOW_MS = 600_000;
  /** Same-configuration restarts one stable READY window earns (T28); any later crash is T29. */
  public static final int SAME_CONFIG_RESTARTS_PER_STABLE_WINDOW = 1;
  /** Same-configuration retries of a failed start (T9, T10): none. */
  public static final int STARTING_SAME_CONFIG_RETRIES = 0;
  /**
   * Periodic health runs with a fixed delay between probes, never a fixed rate, so a resume from
   * sleep cannot release a burst of overdue probes (Spec A §3.3).
   */
  public static final String PERIODIC_HEALTH_SCHEDULING = "fixed-delay";

  /** System property overriding the cold-start health deadline (eval / slow-install path). */
  public static final String HEALTH_CHECK_TIMEOUT_PROP = "justsearch.inference.health_check_timeout_ms";

  public BrainSupervisionPolicy {
    if (consecutiveFailuresBeforeRestart < 1) {
      throw new IllegalArgumentException("consecutiveFailuresBeforeRestart must be >= 1");
    }
    if (periodicHealthIntervalMs <= 0 || healthCheckTimeoutMs <= 0) {
      throw new IllegalArgumentException("intervals/timeouts must be > 0");
    }
  }

  /**
   * The shipped Brain supervision policy. {@code healthCheckTimeoutMs} reads the legacy sysprop at call
   * time (same as the historical static-init read in {@link LlamaServerOps}, since the field that holds
   * this is itself {@code static final} — identical timing, no behavior change).
   */
  public static BrainSupervisionPolicy defaults() {
    long healthTimeout = DEFAULT_HEALTH_CHECK_TIMEOUT_MS;
    String raw = System.getProperty(HEALTH_CHECK_TIMEOUT_PROP); // SYS-PROP-LEGACY-COMPAT: pre-ConfigStore
    if (raw != null && !raw.isBlank()) {
      try {
        healthTimeout = Long.parseLong(raw.trim());
      } catch (NumberFormatException ignored) {
        healthTimeout = DEFAULT_HEALTH_CHECK_TIMEOUT_MS;
      }
    }
    return new BrainSupervisionPolicy(
        DEFAULT_CONSECUTIVE_FAILURES_BEFORE_RESTART,
        DEFAULT_PERIODIC_HEALTH_INTERVAL_MS,
        healthTimeout);
  }
}
