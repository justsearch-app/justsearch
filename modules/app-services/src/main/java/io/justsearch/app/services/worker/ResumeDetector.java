/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Detects an OS suspend/resume by observing a wall-clock gap between successive ticks of a periodic
 * loop (tempdoc 630 latency-hardening slice). A task scheduled every {@code expectedIntervalMs} that
 * observes an inter-tick gap far larger than its interval was almost certainly frozen — the machine
 * suspended and resumed. Pure + {@code nowMs}-injected (the project idiom; see {@code
 * BootRecoveryDecision} / {@code PolledStateLiveness}) so it is
 * unit-testable without a real clock or a real suspend.
 *
 * <p>Wall-clock by necessity: only a clock that <em>advances</em> during the freeze can see the gap
 * (tempdoc 630 research pass — a monotonic clock's suspend behavior is platform-inconsistent, so it
 * cannot be relied on here). The host loop ({@code KnowledgeServerHealthMonitor}) reacts to a
 * detected resume by re-registering watchers and reconciling, instead of waiting for the reactive
 * (periodic-sync) recovery. It also used to reconnect the gRPC channel; lane F stage A item A11
 * deleted that half, because there is no channel to reconnect — the watcher half is filesystem-
 * shaped and survives, since a watcher frozen through a suspend missed every event in the window
 * whatever the process count.
 *
 * <p>Benign in both directions, deliberately: a missed resume (gap just under threshold) simply
 * falls back to the existing reactive recovery; a false positive (a genuine long pause) only
 * triggers one extra freshness-skipping reconcile. The tolerance is therefore set
 * generously so ordinary GC / scheduler jitter never trips it.
 */
public final class ResumeDetector {

  private ResumeDetector() {}

  /**
   * The wall-clock gap (ms) indicating a probable suspend since the last tick, or {@code 0} when no
   * resume is inferred. Pure and total.
   *
   * @param lastTickWallMs wall-clock (epoch ms) of the previous tick; {@code <= 0} means "no prior
   *     tick yet" (the first tick after start) — never a resume
   * @param nowWallMs wall-clock (epoch ms) of the current tick
   * @param expectedIntervalMs the loop's scheduled interval
   * @param toleranceFactor the observed gap must exceed {@code expectedIntervalMs * toleranceFactor}
   *     to count as a resume (generous, so jitter does not false-trigger)
   * @return the observed gap in ms when a resume is inferred, else {@code 0}
   */
  public static long resumeGapMs(
      long lastTickWallMs, long nowWallMs, long expectedIntervalMs, long toleranceFactor) {
    if (lastTickWallMs <= 0) {
      return 0; // first tick — nothing to compare against
    }
    long gap = nowWallMs - lastTickWallMs;
    long threshold = expectedIntervalMs * toleranceFactor;
    return gap > threshold ? gap : 0;
  }
}
