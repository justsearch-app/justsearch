/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

/**
 * Declared health-probe policy for the Brain (llama-server) process. The component monitor owns
 * recovery admission and retry budgets; this policy controls when the native owner reports a
 * health failure. Its values are checked against {@code governance/supervision-contract.v1.json}.
 *
 * <p>Only the health-monitoring knobs are lifted (hang threshold, periodic interval,
 * health-check deadline); probe/kill timeouts stay as operational constants in
 * {@link LlamaServerOps} since they are probe tuning, not the component recovery contract.
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
