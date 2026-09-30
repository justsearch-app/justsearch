package io.justsearch.agent;

import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.EngineContextTestFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 577 Ext III — the budget accounting facets the accountability record projects: the
 * run-cumulative {@link AgentSession#totalTokens()} carried on {@code AgentBudgetUpdate} (the §2.9
 * V4 ceiling fix) and the {@link AgentSession#addBudget(int)} raise-budget remedy.
 *
 * <p>Tempdoc 577 §2.12 Move 2 — also the held budget GATE (createBudgetGate / resolveBudgetGate /
 * budgetGateHeld): the budget analogue of an approval gate, parked and resolved as one decision.
 */
final class AgentSessionBudgetTest {

  private static AgentSession session(int budget) {
    return new AgentSession(List.of(Map.of("role", "user", "content", "q")), budget, EngineContextTestFixtures.AGENT_LOOP);
  }

  @Test
  @DisplayName("totalTokens is run-cumulative while budgetRemaining decrements — ceiling = total + remaining")
  void cumulativeInvariantHoldsAcrossIterations() {
    var s = session(6000);
    s.recordUsage(1000, 500); // iteration 1
    s.recordUsage(2000, 800); // iteration 2
    assertEquals(4300, s.totalTokens());
    assertEquals(1700, s.budgetRemaining());
    // The Ext III invariant the FE ceiling derivation relies on:
    assertEquals(6000, s.totalTokens() + s.budgetRemaining());
  }

  @Test
  @DisplayName("the invariant survives an overrun (remaining goes negative, total keeps counting)")
  void invariantSurvivesOverrun() {
    var s = session(1000);
    s.recordUsage(900, 807);
    assertEquals(1707, s.totalTokens());
    assertEquals(-707, s.budgetRemaining());
    assertEquals(1000, s.totalTokens() + s.budgetRemaining());
  }

  @Test
  @DisplayName("addBudget raises remaining (the raise-budget remedy); non-positive grants are ignored")
  void addBudgetRaisesRemaining() {
    var s = session(1000);
    s.recordUsage(900, 807); // over budget by 707
    s.addBudget(4096);
    assertEquals(3389, s.budgetRemaining());
    s.addBudget(0);
    s.addBudget(-50);
    assertEquals(3389, s.budgetRemaining(), "non-positive grants are no-ops");
    // The ceiling honestly grows with the grant: total + remaining = 1000 + 4096.
    assertEquals(5096, s.totalTokens() + s.budgetRemaining());
  }

  // --- The held budget gate (tempdoc 577 §2.12 Move 2) ---

  @Test
  @DisplayName("a fresh session holds no budget gate; createBudgetGate parks it")
  void createBudgetGateParksTheRun() {
    var s = session(1000);
    assertFalse(s.budgetGateHeld(), "no gate before the boundary");
    var gate = s.createBudgetGate();
    assertTrue(s.budgetGateHeld(), "the run is parked once the gate is created");
    assertFalse(gate.isDone(), "the future is unresolved while parked");
  }

  @Test
  @DisplayName("resolveBudgetGate completes the held future with the human's decision")
  void resolveBudgetGateCompletesTheFuture()
      throws InterruptedException, ExecutionException, TimeoutException {
    var s = session(1000);
    var gate = s.createBudgetGate();
    assertTrue(
        s.resolveBudgetGate(AgentSession.BudgetGateDecision.CONTINUE),
        "resolving a held gate returns true");
    assertEquals(AgentSession.BudgetGateDecision.CONTINUE, gate.get(1, TimeUnit.SECONDS));
    assertFalse(s.budgetGateHeld(), "the gate is no longer held after resolution");
  }

  @Test
  @DisplayName("resolveBudgetGate on an unparked run returns false (the endpoint 404 case)")
  void resolveBudgetGateWithoutAGateReturnsFalse() {
    var s = session(1000);
    assertFalse(
        s.resolveBudgetGate(AgentSession.BudgetGateDecision.STOP),
        "no held gate ⇒ false ⇒ the decision endpoint surfaces 404");
  }

  @Test
  @DisplayName("clearBudgetGate releases the gate (the loop's timeout path)")
  void clearBudgetGateReleasesIt() {
    var s = session(1000);
    s.createBudgetGate();
    assertTrue(s.budgetGateHeld());
    s.clearBudgetGate();
    assertFalse(s.budgetGateHeld(), "cleared ⇒ a later resolve is a no-op 404");
    assertFalse(s.resolveBudgetGate(AgentSession.BudgetGateDecision.FINALIZE));
  }

  // --- The held context gate (tempdoc 577 §2.14 Root II #14) ---

  private static AgentSession sessionWith(List<Map<String, Object>> messages) {
    return new AgentSession(messages, 8192, EngineContextTestFixtures.AGENT_LOOP);
  }

  @Test
  @DisplayName("createContextGate parks the run and marks it fired (park at most once)")
  void createContextGateParksAndMarksFired() {
    var s = session(8192);
    assertFalse(s.contextGateHeld());
    assertFalse(s.contextGateFired());
    var gate = s.createContextGate();
    assertTrue(s.contextGateHeld(), "parked once created");
    // Tempdoc 859 §D §2.7 — this flag's MEANING narrowed and the old wording became inaccurate.
    // It has always pinned only that the gate ASKS at most once, and that is still exactly true.
    // What changed is the loop's response to a LATER crossing: it now auto-compacts (and narrates
    // it) instead of doing nothing. "never re-parks" stays right; "nothing happens again" never was
    // what this asserted, and at the effort multipliers it would be a hole, not a contract.
    assertTrue(s.contextGateFired(), "asked-once flag set so the loop never re-PARKS the run");
    assertFalse(gate.isDone());
  }

  @Test
  @DisplayName("resolveContextGate completes the held future; unparked ⇒ false (404)")
  void resolveContextGateCompletesTheFuture()
      throws InterruptedException, ExecutionException, TimeoutException {
    var s = session(8192);
    var gate = s.createContextGate();
    assertTrue(s.resolveContextGate(AgentSession.ContextGateDecision.SUMMARIZE));
    assertEquals(AgentSession.ContextGateDecision.SUMMARIZE, gate.get(1, TimeUnit.SECONDS));
    assertFalse(s.contextGateHeld());
    assertFalse(
        s.resolveContextGate(AgentSession.ContextGateDecision.STOP),
        "a second resolve on an unparked run is the 404 case");
  }

  @Test
  @DisplayName("compactOlderTurns drops the oldest turns, preserving the system anchor + recent window")
  void compactOlderTurnsKeepsSystemAndRecent() {
    var msgs = new java.util.ArrayList<Map<String, Object>>();
    msgs.add(Map.of("role", "system", "content", "you are an agent"));
    for (int i = 0; i < 10; i++) {
      msgs.add(Map.of("role", "user", "content", "turn " + i));
    }
    var s = sessionWith(msgs);
    int dropped = s.compactOlderTurns(3); // keep the 3 most-recent
    // Tempdoc 859 §D §2.7(a) — the counts changed BECAUSE the contract did: the opening user
    // message is now preserved as the run's TASK anchor alongside the system message.
    assertEquals(6, dropped, "11 messages, keep system + opening task + 3 recent ⇒ drop 6");
    assertEquals(5, s.messages().size());
    assertEquals("system", s.messages().get(0).get("role"), "the system anchor is preserved");
    assertEquals("turn 0", s.messages().get(1).get("content"), "the TASK anchor is preserved");
    assertEquals("turn 9", s.messages().get(4).get("content"), "the newest turn is kept");
  }

  // --- Compaction amendments (tempdoc 859 §D §2.7 a/b) ---

  @Test
  @DisplayName("859 §D §2.7(a) — the opening user message survives compaction (the run's TASK)")
  void compactionPreservesTheTaskAnchor() {
    // The hazard this closes: the task sat at index 1, INSIDE the drop range. An agent that forgets
    // what it was asked, mid-run, is worse than one that stops — and repeated compaction (now
    // reachable, since the gate re-arms) made it the likely outcome rather than an edge case.
    var msgs = new java.util.ArrayList<Map<String, Object>>();
    msgs.add(Map.of("role", "system", "content", "you are an agent"));
    msgs.add(Map.of("role", "user", "content", "READ THE THREE FILES AND SUMMARISE THEM"));
    for (int i = 0; i < 12; i++) {
      msgs.add(Map.of("role", "assistant", "content", "step " + i));
    }
    var s = sessionWith(msgs);
    // Compact TWICE — the re-arm case, which is what makes this amendment load-bearing.
    s.compactOlderTurns(4);
    s.compactOlderTurns(2);
    assertTrue(
        s.messages().stream()
            .anyMatch(m -> "READ THE THREE FILES AND SUMMARISE THEM".equals(m.get("content"))),
        "the task must survive every compaction, not just the first");
    assertEquals("system", s.messages().get(0).get("role"));
    assertEquals("user", s.messages().get(1).get("role"), "the task anchor sits right after system");
  }

  @Test
  @DisplayName("859 §D §2.7(b) — no `tool` message is left without the assistant call it answers")
  void compactionNeverOrphansAToolResult() {
    // A role:"tool" message whose parent assistant tool_calls message was dropped is a malformed
    // conversation for the provider. The keep-window boundary lands wherever it lands, so without
    // this the orphan is a matter of luck.
    var msgs = new java.util.ArrayList<Map<String, Object>>();
    msgs.add(Map.of("role", "system", "content", "sys"));
    msgs.add(Map.of("role", "user", "content", "task"));
    for (int i = 0; i < 4; i++) {
      msgs.add(Map.of("role", "assistant", "content", "calls " + i, "tool_calls", "yes"));
      msgs.add(Map.of("role", "tool", "content", "result " + i));
      msgs.add(Map.of("role", "tool", "content", "result " + i + "b"));
    }
    var s = sessionWith(msgs);
    // keepRecent = 5 would slice mid-group: [.. tool, tool, assistant, tool, tool] leaves the first
    // two tool messages parentless. The boundary must move forward past them instead.
    s.compactOlderTurns(5);
    var kept = s.messages();
    boolean seenAssistantSinceHead = false;
    for (int i = 0; i < kept.size(); i++) {
      String role = String.valueOf(kept.get(i).get("role"));
      if ("assistant".equals(role)) {
        seenAssistantSinceHead = true;
      } else if ("tool".equals(role)) {
        assertTrue(
            seenAssistantSinceHead,
            "tool message at index " + i + " has no assistant call above it: " + kept);
      }
    }
    assertEquals("system", kept.get(0).get("role"));
    assertEquals("task", kept.get(1).get("content"), "and the task anchor still survives");
  }

  // --- The raise narration input (tempdoc 859 §D §3.2(7)) ---

  @Test
  @DisplayName("859 §D — a grant is queued for narration exactly once, then drained")
  void addBudgetQueuesTheRaiseForNarrationExactlyOnce() {
    var s = session(1000);
    assertEquals(0, s.drainPendingRaiseNarration(), "a run nobody raised has nothing to narrate");
    s.addBudget(10_000);
    s.addBudget(0);
    s.addBudget(-5);
    assertEquals(
        10_000, s.drainPendingRaiseNarration(), "rejected grants add nothing to announce");
    assertEquals(
        0,
        s.drainPendingRaiseNarration(),
        "DRAINED — the second observer of the same grant must not announce it again, which is what"
            + " lets the gate branch and the step boundary both call this safely");
  }

  @Test
  @DisplayName("859 §D — two grants between step boundaries narrate ONCE, for the total")
  void grantsAccumulateBetweenNarrations() {
    // The mid-run raise is announced at the next iteration_start. A reader who clicks twice before
    // the loop reaches that boundary granted 30,000 tokens once, not two separate things.
    var s = session(1000);
    s.addBudget(10_000);
    s.addBudget(20_000);
    assertEquals(30_000, s.drainPendingRaiseNarration());
  }

  @Test
  @DisplayName("859 §D §2.7(c) — the session remembers the provider-REPORTED prompt size")
  void recordUsageRemembersTheReportedPromptSize() {
    // The context-pressure trigger reads this ALONGSIDE countPromptTokens, which was schema-blind
    // and ~40% low (577) — low enough that the trigger could fire only after the real prompt had
    // already exceeded n_ctx, i.e. after the damage. Tempdoc 878 §D.6 fixed the schema-blindness at
    // the source; this figure is still read, because it corrects the OTHER error: the projection
    // describes the messages as they stand now, this describes the call that actually happened.
    var s = session(50_000);
    assertEquals(0, s.lastReportedPromptTokens(), "nothing reported before the first response");
    s.recordUsage(3200, 150);
    assertEquals(3200, s.lastReportedPromptTokens());
    s.recordUsage(4100, 150);
    assertEquals(4100, s.lastReportedPromptTokens(), "the LATEST prompt is the occupancy figure");
    s.recordUsage(null, 90);
    assertEquals(4100, s.lastReportedPromptTokens(), "a usage report without a prompt tells us nothing new");
  }

  // --- The zero-observer policy (tempdoc 577 §2.14 Root I #13) ---

  @Test
  @DisplayName("a Watch run with no observer PARKS; with an observer it PROCEEDs")
  void zeroObserverPolicyParksWatchWithoutAWatcher() {
    var s = session(8192);
    s.setAutonomyLevel(io.justsearch.agent.api.registry.AutonomyLevel.WATCH);
    // No observer attached yet ⇒ a Watch run must PARK (don't run unsupervised).
    assertEquals(AgentSession.ZeroObserverPolicy.PARK, s.zeroObserverPolicy());
    // An attached observer ⇒ PROCEED. Tempdoc 834 §1.3: the session-local hub is gone, so this is
    // the initiating observer; a reattaching one is counted by the observation substrate instead.
    s.attachInitiatingObserver(e -> {});
    assertEquals(AgentSession.ZeroObserverPolicy.PROCEED, s.zeroObserverPolicy());
  }

  @Test
  @DisplayName("Assist / Auto runs PROCEED with no observer (their gates self-arbitrate by posture)")
  void zeroObserverPolicyProceedsForAssistAndAuto() {
    var assist = session(8192);
    assist.setAutonomyLevel(io.justsearch.agent.api.registry.AutonomyLevel.ASSIST);
    assertEquals(AgentSession.ZeroObserverPolicy.PROCEED, assist.zeroObserverPolicy());
    var auto = session(8192);
    auto.setAutonomyLevel(io.justsearch.agent.api.registry.AutonomyLevel.AUTO);
    assertEquals(AgentSession.ZeroObserverPolicy.PROCEED, auto.zeroObserverPolicy());
  }

  @Test
  @DisplayName("compactOlderTurns is a no-op when the working set already fits the keep window")
  void compactOlderTurnsNoOpWhenSmall() {
    var s = sessionWith(
        new java.util.ArrayList<>(
            List.of(
                Map.of("role", "system", "content", "s"),
                Map.of("role", "user", "content", "a"))));
    assertEquals(0, s.compactOlderTurns(6), "nothing compactable ⇒ 0 dropped");
    assertEquals(2, s.messages().size(), "messages untouched");
  }
}
