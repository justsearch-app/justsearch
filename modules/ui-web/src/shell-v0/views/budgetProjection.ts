// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 561 P-A3 — the agent loop's budget readout as a pure, honest projection of the budget
 * object (the §2.2 phase-totality obligation: "over budget" is a declared lifecycle phase that must
 * render honestly, never a raw negative leaking to the UI as the live audit's `Remain -383`).
 *
 * The backend accounting is honest data: every consumed token decrements `tokensRemaining` by the
 * same amount, so `tokensConsumed + tokensRemaining === initialBudget` is invariant even once a
 * final completion overruns the ceiling (driving `tokensRemaining` negative). This projection keeps
 * that raw value as the source of truth but presents it as two distinct, non-conflated facets:
 * a clamped `remaining` (never negative) and an explicit `overBudget` / `overBy` phase.
 */

/** The minimal budget shape this projection reads (an {@code AgentBudgetUpdate} on the wire). */
export interface BudgetInput {
  /** Tokens of THIS phase (per-call/projected — NOT cumulative; 577 §2.9 V4). */
  readonly tokensConsumed: number;
  readonly tokensRemaining: number;
  /** Tempdoc 577 Ext III: run-cumulative consumption. Absent/0 on legacy records ⇒ the projection
   * falls back to the per-phase figure (correct only for the first update of a run). */
  readonly totalTokensConsumed?: number;
  /** Tempdoc 577 §2.14 Root II (#14): the latest call's prompt size = current context occupancy. */
  readonly promptTokens?: number;
  /** Tempdoc 577 §2.14 Root II (#14): the model's context window (n_ctx); 0/absent ⇒ no horizon. */
  readonly contextWindow?: number;
}

/**
 * Tempdoc 577 §2.14 Root II (#14) — the COGNITIVE-headroom meter, sibling of the ECONOMIC budget
 * above: how full the model's context window is (latest prompt occupancy ÷ n_ctx), so the user
 * distinguishes "ran out of money" (budget) from "ran out of memory" (context). A pure projection
 * of the same {@code AgentBudgetUpdate} fields.
 */
export interface ContextHorizon {
  /** Current context occupancy in tokens (the latest call's prompt size). */
  readonly occupancy: number;
  /** The model's context window (n_ctx). */
  readonly window: number;
  /** Fraction of the window occupied, 0..100 (clamped). */
  readonly pct: number;
  /** Display band — green plenty of headroom, yellow tightening, red near the ceiling. */
  readonly color: 'green' | 'yellow' | 'red';
}

/**
 * Project the context-headroom meter. Returns {@code null} when no horizon data is available
 * (no n_ctx on the wire, or a non-occupancy phase), so the surface simply omits the meter rather
 * than rendering a misleading 0%.
 */
export function projectContextHorizon(
  latest: BudgetInput | null | undefined,
): ContextHorizon | null {
  if (!latest) return null;
  const window = latest.contextWindow ?? 0;
  const occupancy = latest.promptTokens ?? 0;
  // No denominator (n_ctx unknown) or no occupancy reported yet ⇒ nothing honest to show.
  if (window <= 0 || occupancy <= 0) return null;
  const pct = Math.min(100, Math.round((occupancy / window) * 100));
  const color: ContextHorizon['color'] = pct < 50 ? 'green' : pct < 80 ? 'yellow' : 'red';
  return { occupancy, window, pct, color };
}

/* ── The budget gate's fact panel (tempdoc 859 §D §2.4) ──────────────────────────────────────── */

/**
 * What the reader needs in order to answer the gate: what this run has already spent, and on what.
 *
 * Every field is a projection of something the run already reported — the panel adds NO wire
 * fields. `askedAt` is the turn's own start time, which the record carries too, so the elapsed
 * figure survives a reload instead of resetting.
 */
export interface BudgetGateFactsInput {
  /** Run-cumulative spend, from the gate event's `totalTokensConsumed`. */
  readonly totalTokensConsumed: number;
  /** Tool calls the run has made, as the window's own feed counts them. */
  readonly toolCallsExecuted: number;
  /**
   * Steps the run has taken, or `null` when NOTHING HAS SAID (859 D live-defect D1). It was a plain
   * number whose only writer was the run's terminal, so every mid-run gate rendered a structural
   * zero; the tri-state is what keeps "we were not told" out of the fact panel.
   */
  readonly iterationsUsed: number | null;
  /** The turn's `askedAt` (epoch ms); `null` only if the window never stamped one. */
  readonly askedAt: number | null;
  /** Read at render time by the caller, never by this module — a pure projection has no clock. */
  readonly now: number;
  /** The last tool the run ran. Genuinely absent before the first tool call. */
  readonly lastAction: string | null;
}

export interface BudgetGateFacts {
  readonly tokensUsed: number;
  readonly toolCalls: number;
  /** `null` when no authority has reported a step count — omitted, never rendered as 0. */
  readonly steps: number | null;
  /** `null` when the turn carries no start time — omitted, never rendered as 0 s. */
  readonly elapsedMs: number | null;
  /** `null` before the first tool call — omitted, never rendered as "none". */
  readonly lastAction: string | null;
}

/**
 * Project the gate's fact panel.
 *
 * Tri-state discipline applies wherever a value is GENUINELY absent: no tool call has happened yet,
 * the turn carries no start time, or — 859 D live-defect D1 — nothing has yet reported how many
 * steps the run has taken. A panel that showed "0 steps" for "we were not told" would be inventing a
 * fact at the exact moment the reader is deciding whether to spend more money on this run, which is
 * precisely what the live audit caught it doing beside a correct "Last action".
 *
 * `tokensUsed` and `toolCalls` stay plain numbers because both are things the WINDOW knows by
 * construction: the spend rides the gate event itself, and the tool-call count is the feed's own
 * (see `sv3-run.projectSv3RunPrompts`), so a zero there is a counted zero.
 */
export function projectBudgetGateFacts(input: BudgetGateFactsInput): BudgetGateFacts {
  const elapsed =
    input.askedAt === null || !Number.isFinite(input.askedAt)
      ? null
      : Math.max(0, input.now - input.askedAt);
  const lastAction =
    input.lastAction !== null && input.lastAction.trim() !== '' ? input.lastAction : null;
  return {
    tokensUsed: Math.max(0, input.totalTokensConsumed),
    toolCalls: Math.max(0, input.toolCallsExecuted),
    steps: input.iterationsUsed === null ? null : Math.max(0, input.iterationsUsed),
    elapsedMs: elapsed,
    lastAction,
  };
}

/* ── The sized-continue ladder (tempdoc 859 §D §2.5) ─────────────────────────────────────────── */

/** One "keep going" arm: what it says, and what it will actually spend. */
export interface BudgetContinueStep {
  readonly id: 'little' | 'again' | 'plenty';
  readonly label: string;
  /** The amount the raise directive carries. The fine print quotes THIS, not a rounded cousin. */
  readonly addTokens: number;
}

export interface BudgetContinueInput {
  /** The run's own burn so far — the ladder is denominated in it, not in a fabricated task model. */
  readonly totalTokensConsumed: number;
  /** What the gate says the next prompt needs. */
  readonly tokensNeeded: number;
  /**
   * What is left — taken RAW. It genuinely goes negative when a forced tool call is allowed through
   * at the budget edge (the E0a bypass), and that negative is the load-bearing fact here.
   */
  readonly tokensRemaining: number;
  /** The provider-reported size of the last prompt, when the run has reported one. */
  readonly promptTokens?: number;
}

/** Steps are quoted in round numbers; the reader is choosing an order of magnitude, not a total. */
const STEP_ROUNDING = 500;

/** The visible gap between adjacent steps once the floor has pushed them together. */
const MIN_STEP_SEPARATION = 1000;

const ceilTo = (value: number, unit: number): number => Math.ceil(value / unit) * unit;

/**
 * The three sized continues, denominated in the run's OWN burn: about half as much again, about as
 * much again, and plenty.
 *
 * **The floor rule.** Every step must clear the gate it is answering. A step that does not resumes
 * the loop straight into an immediate re-gate — a click that visibly does nothing, which is the
 * worst possible response to "will you spend more on this". So each step is floored at
 * `shortfall + headroom`:
 *
 *  - `shortfall` uses the RAW `tokensRemaining`. Display clamps must not enter this arithmetic: they would under-fund an over-budget gate.
 *  - `headroom` is the provider-REPORTED last prompt, not a fraction of `tokensNeeded`.
 *    `tokensNeeded` is the loop's PROJECTION of the next prompt. It was also schema-blind and
 *    measured ~40% low (577) until tempdoc 878 §D.6 threaded the iteration's tool list through it,
 *    and sizing a safety margin from a biased number biases the margin the same way. That
 *    particular bias is gone, but the choice stands on its own remaining ground: a projection is a
 *    forecast of a prompt not yet built, while the reported prompt is a measurement of one that
 *    was — and a safety margin should be sized from a measurement.
 *
 * **Monotone separation.** Flooring collapses steps: three arms can land on the same number with
 * three different labels. After flooring, each step is pushed strictly above the previous one by a
 * visible margin. The fine-print token figure is the authority — if two labels would spend the
 * same amount, the ladder is wrong, not the label.
 */
export function budgetContinueSteps(input: BudgetContinueInput): readonly BudgetContinueStep[] {
  const burn = Math.max(0, input.totalTokensConsumed);
  const needed = Math.max(0, input.tokensNeeded);
  // RAW, deliberately — see the doc comment above.
  const shortfall = Math.max(0, needed - input.tokensRemaining);
  const reportedPrompt = input.promptTokens ?? 0;
  // Before the first LLM response there IS no reported prompt; the projection is then the only
  // figure that exists, so it is used rather than pretending the headroom is zero.
  const headroom = reportedPrompt > 0 ? reportedPrompt : needed;
  const floor = shortfall + headroom;

  // The ladder is denominated in the run's OWN burn: half as much again, as much again, plenty.
  const rungs: ReadonlyArray<{
    readonly id: BudgetContinueStep['id'];
    readonly label: string;
    readonly base: number;
  }> = [
    { id: 'little', label: 'Keep going — a little more', base: burn * 0.5 },
    { id: 'again', label: 'Keep going — as much again', base: burn },
    { id: 'plenty', label: 'Keep going — plenty', base: burn * 3 },
  ];

  const steps: BudgetContinueStep[] = [];
  let previous = 0;
  for (const rung of rungs) {
    const wanted = Math.max(rung.base, floor, previous + MIN_STEP_SEPARATION);
    const addTokens = ceilTo(wanted, STEP_ROUNDING);
    previous = addTokens;
    steps.push({ id: rung.id, label: rung.label, addTokens });
  }
  return steps;
}
