import { describe, it, expect } from 'vitest';
import {
  budgetContinueSteps,
  projectBudgetGateFacts,
  projectContextHorizon,
} from './budgetProjection.js';

/* ── Tempdoc 859 §D §3.3 T7 — the gate's fact panel ──────────────────────────────────────────── */

describe('projectBudgetGateFacts (859 §D §2.4)', () => {
  const input = {
    totalTokensConsumed: 20_000,
    toolCallsExecuted: 4,
    iterationsUsed: 5,
    askedAt: 1_000,
    now: 46_000,
    lastAction: 'core_search_index',
  };

  it('projects what the run has spent, and on what', () => {
    const facts = projectBudgetGateFacts(input);
    expect(facts.tokensUsed).toBe(20_000);
    expect(facts.toolCalls).toBe(4);
    expect(facts.steps).toBe(5);
    expect(facts.lastAction).toBe('core_search_index');
  });

  it('measures elapsed from the TURN’s askedAt — which the record carries, so a reload keeps it', () => {
    // The elapsed figure is the one fact that could have been invented as a window-local
    // `run.startedAt`, which would reset to nothing after a reload. It rides the turn instead.
    expect(projectBudgetGateFacts(input).elapsedMs).toBe(45_000);
    // A record-restored turn carries its OWN askedAt, so the figure survives the reload it would
    // otherwise be lost to.
    expect(projectBudgetGateFacts({ ...input, askedAt: 40_000, now: 46_000 }).elapsedMs).toBe(6_000);
  });

  it('OMITS what is genuinely absent rather than rendering a zero that reads as a fact', () => {
    // Tri-state discipline where a value really is missing: before the first tool call there IS no
    // last action, and "none" would be a claim. It does NOT apply to the three counters, which the
    // run always knows — "0 tool calls" for "we were not told" would mislead at the exact moment
    // the reader is deciding whether to spend more.
    const early = projectBudgetGateFacts({ ...input, lastAction: null, askedAt: null });
    expect(early.lastAction).toBeNull();
    expect(early.elapsedMs).toBeNull();
    expect(early.toolCalls).toBe(4);
    expect(projectBudgetGateFacts({ ...input, lastAction: '   ' }).lastAction).toBeNull();
  });

  it('never reports a negative elapsed (a clock that moved backwards is not a duration)', () => {
    expect(projectBudgetGateFacts({ ...input, askedAt: 90_000, now: 46_000 }).elapsedMs).toBe(0);
  });
});

/* ── Tempdoc 859 §D §3.3 T8 — the sized-continue ladder ──────────────────────────────────────── */

describe('budgetContinueSteps (859 §D §2.5)', () => {
  /** The gate as the loop reports it: needs more than is left, so there is a real shortfall. */
  const gate = {
    totalTokensConsumed: 20_000,
    tokensNeeded: 4_000,
    tokensRemaining: 500,
    promptTokens: 3_600,
  };

  it('offers three ascending arms denominated in the run’s own burn', () => {
    const steps = budgetContinueSteps(gate);
    expect(steps.map((s) => s.id)).toEqual(['little', 'again', 'plenty']);
    expect(steps[1]!.addTokens).toBeGreaterThan(steps[0]!.addTokens);
    expect(steps[2]!.addTokens).toBeGreaterThan(steps[1]!.addTokens);
  });

  it('EVERY arm clears the gate it is answering — the floor rule', () => {
    // An arm that does not clear the shortfall resumes the loop straight into an immediate re-gate:
    // a click that visibly does nothing. This is the property the retired fixed 4,096-token step
    // could not offer, and the reason it was retired rather than re-tuned.
    const steps = budgetContinueSteps(gate);
    const shortfall = gate.tokensNeeded - gate.tokensRemaining;
    for (const step of steps) {
      expect(step.addTokens).toBeGreaterThan(shortfall);
    }
  });

  it('uses the UNCLAMPED remaining — a negative one is the load-bearing fact, not a display bug', () => {
    // `tokensRemaining` genuinely goes negative when the forced-tool (E0a) bypass lets a call
    // through at the budget edge; the live audit saw -383. `projectBudget` clamps that for DISPLAY,
    // and borrowing that clamp into arithmetic would under-fund the raise by exactly the overrun.
    const overBudget = { ...gate, tokensRemaining: -383 };
    const shortfall = overBudget.tokensNeeded - overBudget.tokensRemaining; // 4,383 — not 4,000
    for (const step of budgetContinueSteps(overBudget)) {
      expect(step.addTokens).toBeGreaterThan(shortfall);
    }
    // And the overrun really did MOVE the floor. Two conditions have to hold for that to be
    // visible, and both are stated rather than stumbled into: the burn must be small enough that
    // the floor is what binds (at the 20k burn above the base ladder already exceeds it, so the two
    // readings would agree and the assertion would prove nothing), and the overrun must exceed the
    // 500-token display rounding (a 383-token overrun rounds to the same arm either way).
    const smallBurn = { ...gate, totalTokensConsumed: 2_000 };
    const honest = budgetContinueSteps({ ...smallBurn, tokensRemaining: -2_400 })[0]!.addTokens;
    const clamped = budgetContinueSteps({ ...smallBurn, tokensRemaining: 0 })[0]!.addTokens;
    expect(honest).toBeGreaterThan(clamped);
  });

  it('takes headroom from the REPORTED prompt, not from the biased projection', () => {
    // `tokensNeeded` is `countPromptTokens` — a PROJECTION of a prompt not yet built (and, before
    // tempdoc 878 §D.6 threaded the tool list through it, a schema-blind one measured ~40% low, 577).
    // A safety margin should be sized from a measurement, not a forecast. A run whose reported
    // prompt is much larger must therefore get larger arms at an identical `tokensNeeded`.
    const modest = budgetContinueSteps({ ...gate, promptTokens: 1_000 })[0]!.addTokens;
    const heavy = budgetContinueSteps({ ...gate, promptTokens: 12_000 })[0]!.addTokens;
    expect(heavy).toBeGreaterThan(modest);
  });

  it('falls back to the projection when no prompt has been reported yet', () => {
    const steps = budgetContinueSteps({ ...gate, promptTokens: undefined });
    expect(steps).toHaveLength(3);
    expect(steps[0]!.addTokens).toBeGreaterThan(gate.tokensNeeded - gate.tokensRemaining);
  });

  it('keeps the three arms DISTINCT after flooring collapses them', () => {
    // The rev-1 worked case: a small burn beside a large floor pushed two arms onto the same number
    // — two buttons, identical spend, different labels. The fine-print figure is the authority, so
    // when the labels would lie it is the LADDER that has to give, not the label.
    const collapsing = {
      totalTokensConsumed: 3_200,
      tokensNeeded: 4_500,
      tokensRemaining: 490,
      promptTokens: 4_000,
    };
    const spends = budgetContinueSteps(collapsing).map((s) => s.addTokens);
    expect(new Set(spends).size).toBe(3);
    expect(spends[1]!).toBeGreaterThan(spends[0]!);
    expect(spends[2]!).toBeGreaterThan(spends[1]!);
  });

  it('still offers three usable arms on a run that has barely burned anything', () => {
    // The ladder is denominated in burn, so a near-zero burn would collapse all three onto the
    // floor without the separation rule.
    const fresh = {
      totalTokensConsumed: 0,
      tokensNeeded: 900,
      tokensRemaining: 800,
      promptTokens: 900,
    };
    const spends = budgetContinueSteps(fresh).map((s) => s.addTokens);
    expect(new Set(spends).size).toBe(3);
    for (const spend of spends) expect(spend).toBeGreaterThan(100);
  });
});

  describe('projectContextHorizon (tempdoc 577 §2.14 Root II — cognitive headroom)', () => {
    it('returns null without budget data', () => {
      expect(projectContextHorizon(null)).toBeNull();
      expect(projectContextHorizon(undefined)).toBeNull();
    });

    it('returns null when n_ctx is unknown (no denominator) or occupancy not yet reported', () => {
      // n_ctx absent ⇒ cannot show a ratio
      expect(
        projectContextHorizon({ tokensConsumed: 1, tokensRemaining: 1, promptTokens: 1024 }),
      ).toBeNull();
      // occupancy absent (e.g. the iteration_start phase) ⇒ nothing honest to show
      expect(
        projectContextHorizon({ tokensConsumed: 1, tokensRemaining: 1, contextWindow: 8192 }),
      ).toBeNull();
    });

    it('projects occupancy ÷ n_ctx into a clamped percentage + status band', () => {
      const h = projectContextHorizon({
        tokensConsumed: 100,
        tokensRemaining: 100,
        promptTokens: 2048,
        contextWindow: 8192,
      })!;
      expect(h.occupancy).toBe(2048);
      expect(h.window).toBe(8192);
      expect(h.pct).toBe(25);
      expect(h.color).toBe('green');
    });

    it('bands yellow at >=50% and red at >=80%, clamping over-full to 100', () => {
      expect(
        projectContextHorizon({ tokensConsumed: 0, tokensRemaining: 0, promptTokens: 4096, contextWindow: 8192 })!
          .color,
      ).toBe('yellow');
      expect(
        projectContextHorizon({ tokensConsumed: 0, tokensRemaining: 0, promptTokens: 7000, contextWindow: 8192 })!
          .color,
      ).toBe('red');
      const over = projectContextHorizon({
        tokensConsumed: 0,
        tokensRemaining: 0,
        promptTokens: 9000,
        contextWindow: 8192,
      })!;
      expect(over.pct).toBe(100);
      expect(over.color).toBe('red');
    });
  });
