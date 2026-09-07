/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

/**
 * The Worker's heartbeat-suicide decision authority (tempdoc 630): a <b>pure</b> function deciding
 * whether the Worker should self-terminate. Modelled on {@code
 * io.justsearch.app.services.worker.SupervisionDecision} (the Head's recovery authority, deleted at
 * lane F stage A item A11) and {@code
 * io.justsearch.app.services.ai.PolledStateLiveness} — the project's pure-injectable-time idiom
 * ({@code nowMs} is a parameter, not a {@code Clock}), so the full truth table is unit-testable
 * without constructing a signal bus. Lives in worker-services next to {@link BackoffPolicy}, the
 * sibling pure worker-policy seam (mutation-governed), and is consumed by the impure {@code
 * MmfWorkerSignalBus.shouldDie} shell in {@code indexer-worker}.
 *
 * <p>Law: the Worker self-exits iff an explicit shutdown is signalled, OR — past the startup grace —
 * the Main (Head) heartbeat is stale <b>AND</b> Head is not known-alive. A stale heartbeat
 * <em>alone</em> no longer kills: a stale beat is a benign artifact of an OS suspend/resume (Head's
 * heartbeat scheduler pauses during suspend and has not yet written its catch-up beat), so it must
 * be corroborated by an actual Head-liveness signal (Head PID gone) before it is read as "Head
 * died". This is tempdoc 630's liveness-continuity precondition applied to the 627 "zombie"
 * suicide-pact: a staleness observation is only actionable if the substrate was continuously live
 * during the measurement window. When Head-liveness is {@link HeadLiveness#UNKNOWN} (no PID signal —
 * standalone runs / tests), the pre-630 behavior is preserved (stale ⇒ die) so the zombie pact still
 * protects where no PID signal exists.
 *
 * <p>Clock-immune by construction: a suspended Head keeps its PID, so {@link HeadLiveness#ALIVE}
 * holds across a suspend and the false-suicide-on-wake cannot occur — without depending on
 * monotonic-clock behavior across suspend (which is platform-inconsistent; tempdoc 630 research
 * pass). The decision deliberately requires <em>both</em> a stale beat and a dead Head, so the
 * fail-safe direction under PID reuse (Head looks ALIVE ⇒ do not die) is the benign one: a
 * truly-orphaned Worker is still reaped by the Windows Job Object, and a not-suicide is strictly
 * better than today's suicide on every wake.
 */
public final class WorkerLivenessDecision {

  private WorkerLivenessDecision() {}

  /** Whether the Worker can observe the Main (Head) process's liveness. */
  public enum HeadLiveness {
    /** Head's PID is alive — Head exists (possibly suspended). Never suicide on a stale beat alone. */
    ALIVE,
    /** Head's PID is gone — Head actually died. A stale beat now means a real orphan: suicide. */
    DEAD,
    /** No Head-liveness signal available (standalone / tests) — fall back to heartbeat-only (legacy). */
    UNKNOWN
  }

  /**
   * Decides whether the Worker should self-terminate. Pure and total.
   *
   * @param shutdownRequested explicit shutdown signal from Head (always honoured first)
   * @param uptimeMs how long this Worker has been running (epoch-now minus its own start)
   * @param startupGraceMs grace window after startup during which the heartbeat is not checked
   * @param heartbeatEpochMs last Main heartbeat (epoch ms); {@code <= 0} means never written
   * @param nowMs the current time (epoch ms)
   * @param heartbeatStaleMs staleness threshold for the heartbeat
   * @param head the observed Head liveness ({@link HeadLiveness})
   * @return true iff the Worker should self-terminate
   */
  public static boolean shouldDie(
      boolean shutdownRequested,
      long uptimeMs,
      long startupGraceMs,
      long heartbeatEpochMs,
      long nowMs,
      long heartbeatStaleMs,
      HeadLiveness head) {
    if (shutdownRequested) {
      return true;
    }
    if (uptimeMs < startupGraceMs) {
      return false; // startup grace — heartbeat not yet checked
    }
    if (heartbeatEpochMs <= 0) {
      return false; // no heartbeat ever written (test / standalone run)
    }
    boolean stale = (nowMs - heartbeatEpochMs) > heartbeatStaleMs;
    if (!stale) {
      return false;
    }
    // Stale heartbeat: only a real Head death (DEAD) or the absence of any liveness signal (UNKNOWN,
    // legacy) is a suicide condition. ALIVE means Head exists (e.g. suspended then resumed) — the
    // stale beat is benign, so the Worker must NOT die. This is the tempdoc 630 fix.
    return head != HeadLiveness.ALIVE;
  }
}
