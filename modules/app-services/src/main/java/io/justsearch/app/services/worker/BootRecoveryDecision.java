/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Local recovery for an index half that has not bound a client. The host supervises the whole
 * Engine process; its restart budget and terminal state are not inputs to this in-process ladder.
 *
 * <p>A failed boot gets bounded attempts with exponential backoff, then one terminal
 * {@code worker.spawn_recovery_exhausted} verdict. A fatal index/schema cause stops automatic
 * attempts because retrying the same bytes cannot repair them. An operator request may retry that
 * cause after an external repair, while preserving the local attempt budget.
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
    /** Stop trying, permanently. Narrated as the terminal reason code unless a {@link Veto} says otherwise. */
    GIVE_UP
  }

  /** Why automatic recovery stopped before spending its attempt budget. */
  public enum Veto {
    /** No veto: the local attempt budget is the only limit. */
    NONE,
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
     * terminal state. The fatal cause is narrated — the recovery authority holds
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
   * local client is bound, whether a fatal index cause is latched, and this arm's own bookkeeping.
   *
   * @param clientBound a {@code KnowledgeClient} is bound, i.e. the bootstrap is up and the
   *     ordinary health arm owns it
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
      boolean indexFatalHeld,
      int attemptsMade,
      boolean gaveUp,
      long msSinceLastAttempt) {}

  /**
   * The decision. The monitor is intentionally dumb — it executes this verbatim.
   *
   * @param action what to do
   * @param veto the fatal index cause that stopped automatic recovery ({@link Veto#NONE} otherwise)
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
    // A fatal index cause belongs to the stored bytes; another local attempt cannot repair it.
    if (in.indexFatalHeld()) {
      return Decision.giveUp(Veto.INDEX_FATAL);
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
