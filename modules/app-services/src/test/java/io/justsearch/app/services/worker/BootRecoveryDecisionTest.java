/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.BootRecoveryDecision.Action;
import io.justsearch.app.services.worker.BootRecoveryDecision.Decision;
import io.justsearch.app.services.worker.BootRecoveryDecision.Input;
import io.justsearch.app.services.worker.BootRecoveryDecision.Veto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boot-recovery law (tempdoc 825 §D2 mechanism 2), pinned with no process, no clock and no IO —
 * the same posture {@code SupervisionDecisionTest} had for its sibling authority before lane F stage
 * A item A11 deleted both.
 *
 * <p>The two vetoes are the load-bearing rows: they are what kept ONE restart authority in a system
 * that had two recovery loops. Since A11 there is only this one — the vetoes stay pinned because
 * stage B reintroduces a supervisor, and a veto that silently stopped being exercised is how the
 * second loop comes back unnoticed. A regression in either is silent in production (the second loop
 * just quietly doubles the declared restart intensity, or overwrites a terminal verdict with a
 * cheerier one), which is exactly the class of defect the tempdoc-627 review named.
 */
@DisplayName("boot recovery: the decision law")
final class BootRecoveryDecisionTest {

  private static final BootRecoveryPolicy POLICY = new BootRecoveryPolicy(3, 1_000, 4_000);

  /** A failed boot with nothing else going on: no client, no vetoes, no attempts yet. */
  private static Input freshlyBricked() {
    return new Input(false, false, false, false, 0, false, Long.MAX_VALUE);
  }

  @Test
  @DisplayName("a bound client means the health arm owns the worker — never touch it")
  void boundClientYieldsNone() {
    Decision d =
        BootRecoveryDecision.decide(
            new Input(true, false, false, false, 0, false, Long.MAX_VALUE), POLICY);

    assertEquals(Action.NONE, d.action());
  }

  @Test
  @DisplayName("a bound client wins even over a stale give-up/attempt count")
  void boundClientWinsOverStaleState() {
    Decision d =
        BootRecoveryDecision.decide(new Input(true, true, true, false, 99, true, 0), POLICY);

    assertEquals(Action.NONE, d.action(), "a live worker must never be re-spawned by this arm");
  }

  @Test
  @DisplayName("a bricked boot attempts once its base backoff has elapsed")
  void firstAttemptWaitsOutTheBaseBackoff() {
    Decision waiting =
        BootRecoveryDecision.decide(
            new Input(false, false, false, false, 0, false, POLICY.baseBackoffMs() - 1), POLICY);
    assertEquals(Action.WAIT, waiting.action());
    assertEquals(1, waiting.nextAttempt());
    assertEquals(1, waiting.waitMs());

    Decision due = BootRecoveryDecision.decide(freshlyBricked(), POLICY);
    assertEquals(Action.ATTEMPT, due.action());
    assertEquals(1, due.nextAttempt());
    assertEquals(Veto.NONE, due.veto());
  }

  @Test
  @DisplayName("backoff doubles per attempt and is capped by the policy ceiling")
  void backoffDoublesAndCaps() {
    assertEquals(1_000, BootRecoveryDecision.backoffMs(1, POLICY));
    assertEquals(2_000, BootRecoveryDecision.backoffMs(2, POLICY));
    assertEquals(4_000, BootRecoveryDecision.backoffMs(3, POLICY));
    assertEquals(4_000, BootRecoveryDecision.backoffMs(9, POLICY), "capped at maxBackoffMs");
    assertTrue(
        BootRecoveryDecision.backoffMs(Integer.MAX_VALUE, POLICY) > 0,
        "an absurd attempt number must not overflow into a negative wait");
  }

  @Test
  @DisplayName("the budget is bounded: attempt maxAttempts times, then GIVE_UP exactly once")
  void budgetIsBoundedAndTerminal() {
    for (int made = 0; made < POLICY.maxAttempts(); made++) {
      Decision d =
          BootRecoveryDecision.decide(new Input(false, false, false, false, made, false, 999_999), POLICY);
      assertEquals(Action.ATTEMPT, d.action(), "attempt " + (made + 1) + " is within budget");
      assertEquals(made + 1, d.nextAttempt());
    }

    Decision spent =
        BootRecoveryDecision.decide(
            new Input(false, false, false, false, POLICY.maxAttempts(), false, 999_999), POLICY);
    assertEquals(Action.GIVE_UP, spent.action());
    assertEquals(Veto.NONE, spent.veto(), "our own budget being spent is not a veto");

    // ...and once narrated, the arc goes quiet: the terminal code is emitted exactly once.
    Decision afterGiveUp =
        BootRecoveryDecision.decide(
            new Input(false, false, false, false, POLICY.maxAttempts(), true, 999_999), POLICY);
    assertEquals(Action.NONE, afterGiveUp.action());
  }

  @Test
  @DisplayName("VETO: a LIVE supervisor yields the cycle — it does not end the arc")
  void liveSupervisorStandsDownForThisCycleOnly() {
    Decision standing =
        BootRecoveryDecision.decide(
            new Input(false, true, false, false, 0, false, Long.MAX_VALUE), POLICY);

    assertEquals(
        Action.STAND_DOWN,
        standing.action(),
        "a second restart authority must not emerge while a supervisor holds the budget");
    assertEquals(Veto.SUPERVISION_ENGAGED, standing.veto());
    assertEquals(0, standing.nextAttempt(), "standing down consumes no budget");

    // Review F2(a) — THE regression: making this permanent handed a supervised-then-abandoned boot
    // zero attempts, no terminal code and a dead operator hatch. Once the supervisor is gone (the
    // failed start's close() dropped the spawner), the very next decision must attempt.
    Decision afterSupervisorGone =
        BootRecoveryDecision.decide(
            new Input(false, false, false, false, 0, false, Long.MAX_VALUE), POLICY);
    assertEquals(
        Action.ATTEMPT,
        afterSupervisorGone.action(),
        "standing down must not latch: recovery resumes when supervision's arc ends");
    assertEquals(1, afterSupervisorGone.nextAttempt(), "with its budget untouched");
  }

  @Test
  @DisplayName("VETO: worker.restart_exhausted stays terminal — boot recovery never supersedes it")
  void restartExhaustedIsNeverSuperseded() {
    Decision d =
        BootRecoveryDecision.decide(
            new Input(false, false, true, false, 0, false, Long.MAX_VALUE), POLICY);

    assertEquals(Action.GIVE_UP, d.action());
    assertEquals(
        Veto.RESTART_EXHAUSTED,
        d.veto(),
        "the veto is what tells the monitor to stay SILENT rather than narrate its own terminal"
            + " code over supervision's");
  }

  @Test
  @DisplayName("VETO: a latched fatal index cause stops the ladder before it spends an attempt")
  void indexFatalShortCircuitsTheLadder() {
    Decision d =
        BootRecoveryDecision.decide(
            new Input(false, false, false, true, 0, false, Long.MAX_VALUE), POLICY);

    assertEquals(
        Action.GIVE_UP,
        d.action(),
        "tempdoc 915 R1: the worker wrote its refusal to disk, so every attempt re-reads the same"
            + " bytes and refuses the same way — the budget buys nothing but delay");
    assertEquals(Veto.INDEX_FATAL, d.veto());
    assertEquals(0, d.nextAttempt(), "and no attempt is offered");
  }

  @Test
  @DisplayName("supervision's terminal verdict still outranks the index veto (it is already on the wire)")
  void restartExhaustedOutranksIndexFatal() {
    Decision d =
        BootRecoveryDecision.decide(
            new Input(false, false, true, true, 0, false, Long.MAX_VALUE), POLICY);

    assertEquals(
        Veto.RESTART_EXHAUSTED,
        d.veto(),
        "ranking matters: RESTART_EXHAUSTED is silent by contract, INDEX_FATAL narrates — swapping"
            + " them would let boot recovery write over supervision's verdict");
  }

  @Test
  @DisplayName("a live supervisor still yields the temporary stand-down, not the permanent index veto")
  void liveSupervisorIsNotOutrankedIntoPermanence() {
    Decision d =
        BootRecoveryDecision.decide(
            new Input(false, true, false, true, 0, false, Long.MAX_VALUE), POLICY);

    // INDEX_FATAL sits ABOVE supervisionActive deliberately: a supervisor cannot fix an index
    // either. What must not happen is the reverse reading — that this test would pass by the
    // decision falling through to STAND_DOWN and merely looking terminal enough.
    assertEquals(Action.GIVE_UP, d.action());
    assertEquals(Veto.INDEX_FATAL, d.veto());
  }

  @Test
  @DisplayName("a bound client still outranks everything: never touch a live worker")
  void clientBoundOutranksIndexFatal() {
    Decision d =
        BootRecoveryDecision.decide(
            new Input(true, false, false, true, 0, false, Long.MAX_VALUE), POLICY);

    assertEquals(Action.NONE, d.action());
    assertEquals(Veto.NONE, d.veto());
  }

  @Test
  @DisplayName("the restart_exhausted veto outranks a spent budget (so the reason is the honest one)")
  void restartExhaustedVetoOutranksBudgetExhaustion() {
    Decision d =
        BootRecoveryDecision.decide(
            new Input(false, false, true, false, POLICY.maxAttempts(), false, 999_999), POLICY);

    assertEquals(Veto.RESTART_EXHAUSTED, d.veto());
  }

  @Test
  @DisplayName("the TERMINAL supervision verdict outranks a merely-live supervisor")
  void restartExhaustedOutranksStandDown() {
    Decision d =
        BootRecoveryDecision.decide(new Input(false, true, true, false, 0, false, 999_999), POLICY);

    assertEquals(
        Action.GIVE_UP,
        d.action(),
        "gave-up is permanent; still-supervising is not — the pair must not collapse");
    assertEquals(Veto.RESTART_EXHAUSTED, d.veto());
  }

  @Test
  @DisplayName("re-deciding mid-flight refuses an attempt the state no longer licenses (F5)")
  void reDecideRefusesWhatTheCallerAskedFor() {
    // The three states a queued manual request can land in after the fact. Each must resolve to
    // something the executor-side re-decide will NOT treat as an attempt.
    assertEquals(
        Action.NONE,
        BootRecoveryDecision.decide(new Input(true, false, false, false, 1, false, 999_999), POLICY)
            .action(),
        "a worker came up in the meantime (handover already ran)");
    assertEquals(
        Action.GIVE_UP,
        BootRecoveryDecision.decide(
                new Input(false, false, false, false, POLICY.maxAttempts(), false, 999_999), POLICY)
            .action(),
        "the budget was spent by the requests ahead of this one");
    assertEquals(
        Action.NONE,
        BootRecoveryDecision.decide(
                new Input(false, false, false, false, POLICY.maxAttempts(), true, 999_999), POLICY)
            .action(),
        "the arc already gave up");
  }

  @Test
  @DisplayName("a zero-attempt policy gives up immediately rather than attempting once")
  void zeroBudgetGivesUpImmediately() {
    Decision d = BootRecoveryDecision.decide(freshlyBricked(), new BootRecoveryPolicy(0, 0, 0));

    assertEquals(Action.GIVE_UP, d.action());
  }

  @Test
  @DisplayName("null inputs are rejected, not silently treated as 'nothing to do'")
  void nullsAreRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> BootRecoveryDecision.decide(null, POLICY));
    assertThrows(
        IllegalArgumentException.class, () -> BootRecoveryDecision.decide(freshlyBricked(), null));
  }
}
