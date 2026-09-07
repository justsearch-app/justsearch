/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * The Head's BOOT-recovery authority (tempdoc 825): a <b>pure</b> function mapping the observed
 * post-boot state to what the one monitor authority must do next. It was modelled deliberately on
 * {@code SupervisionDecision} — same shape, same testability posture, no IO / clock / process
 * handles — for the one state supervision structurally could not cover:
 * {@code KnowledgeServerBootstrap.start()} failed, so there is no client and nothing supervising
 * anything. Lane F stage A item A11 deleted {@code SupervisionDecision} with the spawner, leaving
 * this the only recovery authority; the shape is kept because stage B's supervisor is declared
 * against it.
 *
 * <p>Law: while no client is bound, a failed boot resolves to a bounded re-{@code ATTEMPT} sequence
 * with exponential backoff and then to exactly one terminal {@code GIVE_UP}
 * ({@code worker.spawn_recovery_exhausted}) — never to silence, and never to an unbounded loop. Two
 * other-authority conditions outrank the budget, and review F2 established that they are NOT the
 * same kind of thing (tempdoc 915 R1 added a third, which is neither: a cause this authority itself
 * owns):
 *
 * <ul>
 *   <li>{@link Veto#RESTART_EXHAUSTED} ⇒ {@link Action#GIVE_UP}, permanently and silently.
 *       Supervision has given up ({@code worker.restart_exhausted}); that verdict is terminal by
 *       contract (825 §D5 decision 2), it is already legible on the wire under supervision's own
 *       code, and boot recovery may not overwrite it with its own.
 *   <li>{@link Veto#SUPERVISION_ENGAGED} ⇒ {@link Action#STAND_DOWN}, for this cycle only. A live
 *       supervisor is holding the restart budget, so re-attempting would multiply the declared
 *       restart intensity — the second-restart-authority hazard the tempdoc-627 review warned about.
 *       But standing down is not a verdict: nothing is narrated, nothing latches, and the next tick
 *       re-asks. Making this one permanent (the pre-review shape) meant a supervised-then-abandoned
 *       boot got zero attempts, no terminal code, and a dead operator hatch — silence, which this
 *       law forbids.
 *   <li>{@link Veto#INDEX_FATAL} ⇒ {@link Action#GIVE_UP}, permanently — and NARRATED, unlike the
 *       other two. The worker wrote a fatal index reason before exiting, so the refusal is a
 *       function of the index directory rather than of the attempt; the terminal state is the cause
 *       itself ({@code worker.index_corrupt} / {@code worker.index_schema_mismatch}) with its
 *       remedy, not this arm's generic {@code worker.spawn_recovery_exhausted}. The operator hatch
 *       bypasses it, because fixing the cause is exactly what an operator request means here.
 * </ul>
 */
public final class BootRecoveryDecision {

  private BootRecoveryDecision() {}

  /** What the boot-recovery arm should do on this tick. */
  public enum Action {
    /** Nothing to do — a client is bound (the health arm owns the worker), or we already gave up. */
    NONE,
    /** An attempt is due but its backoff has not elapsed yet. */
    WAIT,
    /** Re-attempt the bootstrap now. */
    ATTEMPT,
    /**
     * Do nothing THIS cycle because another authority is mid-arc, and re-evaluate on the next one.
     * Distinct from {@link #GIVE_UP} in exactly the way that matters (review F2): standing down is
     * not a verdict, so it neither narrates nor latches — if supervision's arc ends without bringing
     * the worker back, recovery resumes.
     */
    STAND_DOWN,
    /** Stop trying, permanently. Narrated as the terminal reason code unless a {@link Veto} says otherwise. */
    GIVE_UP
  }

  /**
   * Why the sequence stopped without this authority getting to narrate its own terminal code. The
   * two are NOT interchangeable, and review F2 turned on the difference: one is temporary and the
   * other is final.
   */
  public enum Veto {
    /** No veto — the give-up (if any) is this authority's own budget being spent. */
    NONE,
    /**
     * The last attempt left supervision holding the restart budget ({@code supervisionEngaged()}
     * latches on {@code restartCount > 0}, so this says "a supervised restart happened", NOT "it
     * failed"). Pairs with {@link Action#STAND_DOWN}: temporary, re-evaluated every tick.
     */
    SUPERVISION_ENGAGED,
    /**
     * Supervision has declared the terminal {@code worker.restart_exhausted}. Pairs with
     * {@link Action#GIVE_UP}: permanent, by owner decision 2 — and the state is already legible on
     * the wire under supervision's own code, so this authority adds nothing by narrating.
     */
    RESTART_EXHAUSTED,
    /**
     * The dying worker named a fatal INDEX cause ({@code worker.index_corrupt} or
     * {@code worker.index_schema_mismatch}). Pairs with {@link Action#GIVE_UP}: permanent for the
     * AUTOMATIC ladder, because the condition lives in the index directory and a respawn cannot
     * change it — every attempt would spawn a JVM that reads the same bytes and refuses the same way.
     *
     * <p>Tempdoc 915 R1 decision, stated rather than inherited: corruption did NOT short-circuit the
     * ladder before this (there was no such veto), so the two axes are unified here rather than
     * forked. Live arm 2 spent the whole budget respawning a worker that had already written its
     * refusal to disk, and the only thing the attempts produced was a 2.5-minute delay before the
     * terminal state. Unlike the other two vetoes this one IS narrated — the recovery authority holds
     * the cause and the ladder is where a suppressed boot arc's verdict would otherwise die unspoken.
     *
     * <p>An OPERATOR request is exempt: the remedy for both causes is a settings/filesystem change the
     * next spawn will read, so "the operator asked, having just fixed it" is precisely the case a
     * deterministic-refusal veto must not swallow.
     */
    INDEX_FATAL
  }

  /**
   * Observed inputs. All of them are facts the monitor can read without doing anything: whether a
   * gRPC client is bound, what the capability currently holds, and this arm's own bookkeeping.
   *
   * @param clientBound a {@code KnowledgeClient} is bound, i.e. the bootstrap is up and the
   *     ordinary health arm owns it
   * @param supervisionActive a supervisor is alive right now and holding the restart budget
   *     ({@code KnowledgeServerBootstrap.supervisionActive()}) — a live question, re-asked every
   *     tick, not a latch (review F2)
   * @param restartExhaustedHeld the capability currently holds {@code worker.restart_exhausted}
   * @param indexFatalHeld the bootstrap has latched a fatal INDEX verdict from the dying worker's
   *     fatal-reason marker ({@code KnowledgeServerBootstrap.indexFatalCode()}). Deliberately read
   *     from the LATCH and not from {@code pendingReason()}: the marker read can happen inside a
   *     suppressed arc, in which case the cause is known but has not been narrated yet — gating on
   *     the wire state would make the veto depend on whether anyone had spoken (tempdoc 915 R1)
   * @param attemptsMade boot-recovery attempts already made in this arc
   * @param gaveUp this arc has already narrated its terminal state (so it must not narrate twice)
   * @param msSinceLastAttempt elapsed time since the last attempt; {@link Long#MAX_VALUE} when none
   *     has been made yet, which makes the FIRST attempt due immediately (review F8 — an earlier
   *     draft of this javadoc claimed it waited out the base backoff, which the code never did). The
   *     spacing before that first attempt is the monitor's poll interval, since the arm only runs on
   *     a tick; the backoff schedule governs the attempts after it.
   */
  public record Input(
      boolean clientBound,
      boolean supervisionActive,
      boolean restartExhaustedHeld,
      boolean indexFatalHeld,
      int attemptsMade,
      boolean gaveUp,
      long msSinceLastAttempt) {}

  /**
   * The decision. The monitor is intentionally dumb — it executes this verbatim.
   *
   * @param action what to do
   * @param veto which other authority's verdict stopped the sequence ({@link Veto#NONE} otherwise)
   * @param nextAttempt the 1-based attempt number an {@code ATTEMPT} will be (0 otherwise)
   * @param waitMs remaining backoff for a {@code WAIT} (0 otherwise)
   */
  public record Decision(Action action, Veto veto, int nextAttempt, long waitMs) {
    static Decision none() {
      return new Decision(Action.NONE, Veto.NONE, 0, 0);
    }

    static Decision giveUp(Veto veto) {
      return new Decision(Action.GIVE_UP, veto, 0, 0);
    }

    static Decision standDown(Veto veto) {
      return new Decision(Action.STAND_DOWN, veto, 0, 0);
    }
  }

  /**
   * Decides the boot-recovery action for {@code in} under {@code policy}. Pure and total.
   *
   * @throws IllegalArgumentException if {@code in} or {@code policy} is null
   */
  public static Decision decide(Input in, BootRecoveryPolicy policy) {
    if (in == null || policy == null) {
      throw new IllegalArgumentException("input and policy must not be null");
    }
    // A bound client means the bootstrap is up: the health arm owns it, and this arm must not touch a
    // live worker. Checked FIRST so a stale gaveUp/attempt count can never act on a recovered worker.
    if (in.clientBound()) {
      return Decision.none();
    }
    // Terminal states are terminal: narrate once, then stay silent.
    if (in.gaveUp()) {
      return Decision.none();
    }
    // Supervision's TERMINAL verdict outranks everything: permanent, and already on the wire under
    // its own code, so this authority stops and stays quiet (owner decision 2).
    if (in.restartExhaustedHeld()) {
      return Decision.giveUp(Veto.RESTART_EXHAUSTED);
    }
    // Tempdoc 915 R1: a fatal INDEX cause is a property of the bytes on disk, so re-spawning is not a
    // retry of anything — it is the same read producing the same refusal, N more times. Ranked below
    // supervision's terminal verdict (that one is already on the wire and outranks every other
    // authority) and above the attempt budget, which exists for failures a retry could plausibly fix.
    if (in.indexFatalHeld()) {
      return Decision.giveUp(Veto.INDEX_FATAL);
    }
    // Supervision merely ENGAGED is a different animal (review F2): supervisionEngaged() latches on
    // restartCount > 0, so it says a supervised restart happened, not that supervision failed or is
    // still working. Treating it as terminal handed a whole class of bricked boots zero recovery
    // attempts, no terminal code, and a permanently dead operator hatch — while supervision itself
    // had already stopped (its spawner died with the failed start). So: yield the cycle, keep the
    // budget, and re-decide on the next tick.
    if (in.supervisionActive()) {
      return Decision.standDown(Veto.SUPERVISION_ENGAGED);
    }
    int nextAttempt = in.attemptsMade() + 1;
    if (nextAttempt > policy.maxAttempts()) {
      return Decision.giveUp(Veto.NONE);
    }
    long backoff = backoffMs(nextAttempt, policy);
    long elapsed = in.msSinceLastAttempt();
    if (elapsed < backoff) {
      return new Decision(Action.WAIT, Veto.NONE, nextAttempt, backoff - elapsed);
    }
    return new Decision(Action.ATTEMPT, Veto.NONE, nextAttempt, 0);
  }

  /**
   * Exponential backoff for a 1-based attempt number, capped at the policy ceiling:
   * {@code min(base << (attempt-1), max)}. Same schedule shape the deleted
   * {@code SupervisionDecision.backoffMs} used, so the two recovery authorities did not drift in
   * feel while both existed.
   */
  public static long backoffMs(int nextAttempt, BootRecoveryPolicy policy) {
    long base = policy.baseBackoffMs();
    long max = policy.maxBackoffMs();
    if (nextAttempt <= 1 || base == 0) {
      return Math.min(base, max);
    }
    int shift = nextAttempt - 1;
    // Overflow guard by construction rather than by inspecting the result: `base << shift` wraps
    // SILENTLY and can land on a positive value — or exactly 0, which a `scaled < 0` check misses
    // (1000 << 62 == 0, because 1000 is 8*125 and the 125 shifts clean out of the word). A shift at
    // or past the leading-zero count is precisely the shift that would lose the top bit, and any
    // such value has long since passed the ceiling.
    if (shift >= Long.numberOfLeadingZeros(base)) {
      return max;
    }
    return Math.min(base << shift, max);
  }
}
