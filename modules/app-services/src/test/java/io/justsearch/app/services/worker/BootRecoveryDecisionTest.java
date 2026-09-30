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

/** Pins the local recovery budget, backoff and fatal-index boundary without process I/O. */
@DisplayName("boot recovery: the decision law")
final class BootRecoveryDecisionTest {

  private static final BootRecoveryPolicy POLICY = new BootRecoveryPolicy(3, 1_000, 4_000);

  /** A failed boot with nothing else going on: no client, no vetoes, no attempts yet. */
  private static Input freshlyBricked() {
    return new Input(false, false, 0, false, Long.MAX_VALUE);
  }

  @Test
  @DisplayName("a bound client means the health arm owns the worker — never touch it")
  void boundClientYieldsNone() {
    Decision d =
        BootRecoveryDecision.decide(
            new Input(true, false, 0, false, Long.MAX_VALUE), POLICY);

    assertEquals(Action.NONE, d.action());
  }

  @Test
  @DisplayName("a bound client wins even over a stale give-up/attempt count")
  void boundClientWinsOverStaleState() {
    Decision d =
        BootRecoveryDecision.decide(new Input(true, false, 99, true, 0), POLICY);

    assertEquals(Action.NONE, d.action(), "a live worker must never be re-spawned by this arm");
  }

  @Test
  @DisplayName("a bricked boot attempts once its base backoff has elapsed")
  void firstAttemptWaitsOutTheBaseBackoff() {
    Decision waiting =
        BootRecoveryDecision.decide(
            new Input(false, false, 0, false, POLICY.baseBackoffMs() - 1), POLICY);
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
          BootRecoveryDecision.decide(new Input(false, false, made, false, 999_999), POLICY);
      assertEquals(Action.ATTEMPT, d.action(), "attempt " + (made + 1) + " is within budget");
      assertEquals(made + 1, d.nextAttempt());
    }

    Decision spent =
        BootRecoveryDecision.decide(
            new Input(false, false, POLICY.maxAttempts(), false, 999_999), POLICY);
    assertEquals(Action.GIVE_UP, spent.action());
    assertEquals(Veto.NONE, spent.veto(), "our own budget being spent is not a veto");

    // ...and once narrated, the arc goes quiet: the terminal code is emitted exactly once.
    Decision afterGiveUp =
        BootRecoveryDecision.decide(
            new Input(false, false, POLICY.maxAttempts(), true, 999_999), POLICY);
    assertEquals(Action.NONE, afterGiveUp.action());
  }

  @Test
  @DisplayName("VETO: a latched fatal index cause stops the ladder before it spends an attempt")
  void indexFatalShortCircuitsTheLadder() {
    Decision d =
        BootRecoveryDecision.decide(
            new Input(false, true, 0, false, Long.MAX_VALUE), POLICY);

    assertEquals(
        Action.GIVE_UP,
        d.action(),
        "tempdoc 915 R1: the worker wrote its refusal to disk, so every attempt re-reads the same"
            + " bytes and refuses the same way — the budget buys nothing but delay");
    assertEquals(Veto.INDEX_FATAL, d.veto());
    assertEquals(0, d.nextAttempt(), "and no attempt is offered");
  }

  @Test
  @DisplayName("a bound client still outranks everything: never touch a live worker")
  void clientBoundOutranksIndexFatal() {
    Decision d =
        BootRecoveryDecision.decide(
            new Input(true, true, 0, false, Long.MAX_VALUE), POLICY);

    assertEquals(Action.NONE, d.action());
    assertEquals(Veto.NONE, d.veto());
  }

  @Test
  @DisplayName("re-deciding mid-flight refuses an attempt the state no longer licenses (F5)")
  void reDecideRefusesWhatTheCallerAskedFor() {
    // The three states a queued manual request can land in after the fact. Each must resolve to
    // something the executor-side re-decide will NOT treat as an attempt.
    assertEquals(
        Action.NONE,
        BootRecoveryDecision.decide(new Input(true, false, 1, false, 999_999), POLICY)
            .action(),
        "a worker came up in the meantime (handover already ran)");
    assertEquals(
        Action.GIVE_UP,
        BootRecoveryDecision.decide(
                new Input(false, false, POLICY.maxAttempts(), false, 999_999), POLICY)
            .action(),
        "the budget was spent by the requests ahead of this one");
    assertEquals(
        Action.NONE,
        BootRecoveryDecision.decide(
                new Input(false, false, POLICY.maxAttempts(), true, 999_999), POLICY)
            .action(),
        "the arc already gave up");
  }

  @Test
  @DisplayName("a fatal index cause outranks an exhausted budget and keeps its specific remedy")
  void fatalIndexOutranksSpentBudget() {
    Decision decision = BootRecoveryDecision.decide(
        new Input(false, true, POLICY.maxAttempts(), false, Long.MAX_VALUE), POLICY);
    assertEquals(Action.GIVE_UP, decision.action());
    assertEquals(Veto.INDEX_FATAL, decision.veto());
    assertEquals(0, decision.nextAttempt());
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
