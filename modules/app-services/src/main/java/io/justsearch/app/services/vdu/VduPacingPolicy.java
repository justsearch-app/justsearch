/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

/**
 * Tempdoc 672 follow-up: the Head-side counterpart to {@code LoopPacingPolicy} (worker-services),
 * which already gates the Worker's own embedding/NER/SPLADE backfill on the same three-signal
 * shape (tempdoc 630). {@code LoopPacingPolicy} cannot be imported here directly — it lives in
 * the {@code worker-services} module (Lucene-adjacent; Hard Invariant: Head never touches
 * Lucene) — so this is a sibling with the same decision shape, not a shared class. Tempdoc 630
 * §A already named VDU as a workload this arbitration was meant to cover; it could not be built
 * until tempdoc 672's own fix made VDU triggerable at all.
 *
 * <p>Signals, each already existing elsewhere and reused here (no new sensing):
 *
 * <ul>
 *   <li><b>Recent activity</b> — {@code KnowledgeServerBootstrap.msSinceLastUserActivity}, the
 *       Head-local record of real search/suggest/folder-listing activity. Tempdoc 885 item 3
 *       retired the Worker-side twin of this signal: the Worker now paces on its own in-flight
 *       foreground-RPC gauge instead of on a Head-written activity byte.
 *   <li><b>Energy intent</b> — {@code EnergyState.reduced()} (tempdoc 630), already Head-native.
 *   <li><b>Chat claim</b> — the chat controller's answer ({@code ChatHolds.vduMayStart()}, Spec A
 *       step 6), used only by {@link #shouldTrigger}, checked <i>before</i> starting a batch (not
 *       during VDU's own run, which holds the server itself — see {@link #shouldInterrupt}'s
 *       javadoc). The chat is claimed while the server runs, while any hold or self-test session is
 *       open, while a chat failure is latched, or while residency pressure keeps chat unloaded.
 *       The sampler may clear the effective claim when the controller offers an automatic idle
 *       VDU hold; that hold rechecks eligibility and closes admission atomically before relaunch.
 * </ul>
 */
public final class VduPacingPolicy {

  /** Idle window before auto-triggering offline processing. Deliberately conservative. */
  public static final long DEFAULT_IDLE_THRESHOLD_MS = 5 * 60 * 1000L;

  private VduPacingPolicy() {}

  /**
   * Whether it's currently a good time to auto-trigger {@code
   * OfflineCoordinator.startOfflineProcessing()}. Mirrors {@code
   * LoopPacingPolicy.shouldRunBackfill}'s energy-first-then-exclusivity ordering.
   */
  public static boolean shouldTrigger(
      long msSinceLastActivity, boolean energyReduced, boolean chatClaimed, boolean bulkHeld) {
    if (energyReduced) {
      return false; // power: defer regardless of idle state
    }
    if (bulkHeld) {
      return false; // Spec E section 1.3 item 6: VDU is GPU bulk; no batch starts while held
    }
    if (chatClaimed) {
      return false; // the chat controller has the server (running, held, self-testing or failed)
    }
    return msSinceLastActivity >= DEFAULT_IDLE_THRESHOLD_MS;
  }

  /**
   * Whether an in-progress batch should stop early because the user just became active.
   * Mirrors {@code LoopPacingPolicy.shouldInterruptBackfill}'s cooperative-checkpoint shape —
   * checked between units of work, not enforced via locking.
   *
   * <p><b>Deliberately does NOT take the {@code chatClaimed} signal</b> — unlike {@link
   * #shouldTrigger}, where an unreleased chat claim prevents starting another workload.
   * Once a VDU batch is running, the LLM is legitimately Online *because this batch itself put it
   * there* ({@code enterVduMode()} requires {@code Mode.ONLINE} as a precondition and doesn't
   * change {@code currentMode()}, only a {@code vduMode} flag within it) — so {@code isOnline()}
   * is unconditionally true for the batch's entire duration and would self-interrupt on the very
   * first checkpoint if included here. Caught live: a real batch logged "interrupted... leaving 3
   * docs PENDING" immediately after entering VDU mode, before processing a single document, when
   * this method's first version reused {@code shouldTrigger}'s three-signal composition verbatim.
   *
   * <p>Spec E step E3 adds {@code bulkHeld}, the bulk work gate's {@code held()} (game or external
   * GPU memory pressure; never its duty floor): the batch stops at its next checkpoint, so no new
   * VDU unit starts while held.
   */
  public static boolean shouldInterrupt(
      long msSinceLastActivity, boolean energyReduced, boolean bulkHeld) {
    return msSinceLastActivity < DEFAULT_IDLE_THRESHOLD_MS || energyReduced || bulkHeld;
  }

  /** Interrupt an accepted pass only for activity since its start, or a current resource hold. */
  public static boolean shouldInterrupt(
      long msSinceLastActivity, long elapsedSinceBatchStart, boolean energyReduced, boolean bulkHeld) {
    return msSinceLastActivity <= elapsedSinceBatchStart || energyReduced || bulkHeld;
  }
}
