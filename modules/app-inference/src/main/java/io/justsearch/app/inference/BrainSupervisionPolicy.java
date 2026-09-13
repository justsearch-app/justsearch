/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

/**
 * Declared supervision policy for the Brain (llama-server) process — the recovery-contract parameters
 * as named data (tempdoc 627). This is the Brain's analogue of the Worker's
 * the Worker's former {@code SupervisionPolicy} (deleted at lane F stage A item A11 with the
 * Worker process): it lifts the crash/health-recovery
 * constants that were scattered as {@code private static final} fields in {@link LlamaServerOps} into
 * one legible value object, so the recovery contract is declared (and assertable against
 * {@code governance/supervision-contract.v1.json}) rather than implicit.
 *
 * <p>Only the core recovery-contract knobs are lifted (cap, crash backoff, hang threshold, periodic
 * interval, health-check deadline); probe/kill timeouts stay as operational constants in
 * {@link LlamaServerOps} since they are probe tuning, not the recovery contract. Unlike the Worker,
 * the Brain's decision logic is not extracted into a pure function — its branches are trivial and its
 * recovery is orchestration-bound (process stop/start/health-wait), so a pure-decision lift would add
 * abstraction without value (tempdoc 627 investigation).
 *
 * @param maxCrashes hard cap on automatic restarts before terminal OFFLINE (mirrors OTP restart
 *     intensity; the Brain's give-up)
 * @param crashRecoveryDelayMs backoff before a restart attempt (0 for the first crash, this value
 *     thereafter)
 * @param consecutiveFailuresBeforeRestart periodic-health failures (process alive but {@code /health}
 *     unresponsive — the hang/"liveness" signal) before a restart is triggered
 * @param periodicHealthIntervalMs interval between periodic {@code /health} probes while ONLINE
 * @param healthCheckTimeoutMs cold-start deadline for the server to become {@code /health}-ready
 *     (overridable via {@code justsearch.inference.health_check_timeout_ms} for eval/slow installs)
 */
public record BrainSupervisionPolicy(
    int maxCrashes,
    long crashRecoveryDelayMs,
    int consecutiveFailuresBeforeRestart,
    long periodicHealthIntervalMs,
    long healthCheckTimeoutMs) {

  /** Default crash cap (historical {@code LlamaServerOps.MAX_CRASHES}). */
  public static final int DEFAULT_MAX_CRASHES = 3;
  /** Default crash backoff (historical {@code CRASH_RECOVERY_DELAY_MS}). */
  public static final long DEFAULT_CRASH_RECOVERY_DELAY_MS = 5000;
  /** Default hang threshold (historical {@code CONSECUTIVE_FAILURES_BEFORE_RESTART}). */
  public static final int DEFAULT_CONSECUTIVE_FAILURES_BEFORE_RESTART = 3;
  /** Default periodic-health interval (historical {@code PERIODIC_HEALTH_INTERVAL_MS}). */
  public static final long DEFAULT_PERIODIC_HEALTH_INTERVAL_MS = 30_000;
  /** Default cold-start health deadline (historical {@code HEALTH_CHECK_TIMEOUT_MS}). */
  public static final long DEFAULT_HEALTH_CHECK_TIMEOUT_MS = 120_000;

  /** System property overriding the cold-start health deadline (eval / slow-install path). */
  public static final String HEALTH_CHECK_TIMEOUT_PROP = "justsearch.inference.health_check_timeout_ms";

  public BrainSupervisionPolicy {
    if (maxCrashes < 0) {
      throw new IllegalArgumentException("maxCrashes must be >= 0");
    }
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
        DEFAULT_MAX_CRASHES,
        DEFAULT_CRASH_RECOVERY_DELAY_MS,
        DEFAULT_CONSECUTIVE_FAILURES_BEFORE_RESTART,
        DEFAULT_PERIODIC_HEALTH_INTERVAL_MS,
        healthTimeout);
  }
}
