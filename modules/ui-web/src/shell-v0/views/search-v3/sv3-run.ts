// SPDX-License-Identifier: Apache-2.0
/**
 * sv3-run — the Search v3 window's agent-run model (tempdoc 822 Phase F2).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * Everything here is PURE — with ONE stated exception (tempdoc 859 §A §1.3): the live feed's TRAILING
 * reasoning item reads `ReasoningController.elapsedSeconds`, which reads `Date.now()`. That is the
 * same clock the reasoning block already renders its label from, and the controller's 1 s tick is what
 * re-derives the item; but "same input, same output" no longer holds unqualified for that one item,
 * and saying so is cheaper than a purity claim the code does not keep.
 *
 * It reads the shared `AgentSessionController` as a source, never as a place
 * to write: the run itself is hosted by that one controller (the 818 slice-3 finding that it is
 * framework-agnostic), and every directive the reader issues leaves through the ONE
 * `dispatchRunControl` seam in the window. This module exists so the window's RENDERING decisions are
 * derivations rather than a second run model.
 *
 * Four patterns from the design spec that it realizes:
 *
 *  1. **Two-axis run state → ONE derived phase.** A session status (what the shared controller says)
 *     and a turn state (what THIS window's own turn is doing) are separate facts; rendering reads the
 *     one value {@link deriveSv3RunPhase} derives from both. The window also holds an explicit
 *     `activeTurnId` (the {@link Sv3RunLocal} ref), which is what prevents the receipt-counted-from-
 *     the-wrong-origin class: the controller is a product-wide singleton, so "the run" is only ever
 *     the slice this window opened, addressed by the turn it opened it for.
 *  2. **The optimistic-handoff predicate** ({@link hasServerAcknowledgedLocalDispatch}) — a named,
 *     tested condition for when the local "Sending…" echo yields to server truth, instead of an
 *     implicit race. Deliberately NOT `runInFlight`/`isStreaming`: the controller sets both from
 *     inside `send()` before the server has said anything (`AgentSessionController.ts:1368-1370`), so
 *     they are local optimism too. Only evidence the SERVER produced counts.
 *  3. **The primary-action slot as a strict-priority state machine** ({@link sv3PrimaryAction}) —
 *     pending-question ▸ running ▸ follow-up ▸ send, exactly one control, the reason carried in the
 *     aria-label. F1 made "Stop renders INSTEAD of Send" structural; this extends the same rule to
 *     four rungs.
 *  4. **Typed pending states resolved by DEDICATED commands, never chat text** ({@link Sv3RunPrompt}).
 *     A held decision is a value with its own controls; the composer refuses a send while one is
 *     pending, so there is no path by which typing a sentence could resolve it.
 *
 * The feed projection is ONE pass ({@link projectSv3RunFeed}): the live feed renders its items and the
 * run's receipt counts them. The count therefore cannot come to describe a different set than the
 * cards on screen — the same construction the retired search-v2 window used for its receipt,
 * expressed as a projection both halves read.
 */
import type {
  AgentSessionController,
  ConversationEntry,
  ToolCall,
  ToolRisk,
} from '../../controllers/AgentSessionController.js';
import type { Sv3TurnStatus } from './sv3-sessions.js';
// Tempdoc 859 §D §2.6 — the ONE cut-short authority. Imported, never re-derived: a second copy of
// "which dispositions count as truncated" could disagree with the notice line's copy, which is the
// divergence the shared authority exists to prevent.
import { sv3WasCutShort, SV3_CUT_SHORT_BADGE } from './sv3-honesty.js';
import { directionNote } from '../unifiedThreadProjection.js';
// Tempdoc 859 §D §2.4/§2.5 — the SHARED budget-readout authority, already the source of both
// windows' budget bar and context horizon. The gate's facts and its ladder are derivations of the
// same numbers, so they belong beside those and not in a second module that could disagree.
import {
  budgetContinueSteps,
  projectBudgetGateFacts,
  type BudgetContinueStep,
  type BudgetGateFacts,
} from '../budgetProjection.js';

/**
 * The fields of the shared controller this window reads. A structural type rather than the class, so
 * the derivations state their whole dependency surface — and so a test can drive them with a fake run
 * without standing up a controller.
 */
export type Sv3RunSource = Pick<
  AgentSessionController,
  | 'conversation'
  | 'toolCalls'
  | 'streamingText'
  | 'isStreaming'
  | 'runInFlight'
  | 'sessionId'
  | 'budgetGate'
  | 'contextGate'
  // Tempdoc 834 §6.2 — the run's own answer to "why am I stopped", carried on the reattach primer.
  // The two gate fields above are announced by frames the replay ring can evict; this one is not.
  | 'runPark'
  // Tempdoc 859 §D §2.4 — the budget gate's fact panel: what this run has already spent, and on
  // what. A figure the run already reported; the panel adds no wire field. `toolCallsExecuted` is
  // NOT read here — 859 D live-defect D1 moved the panel's tool-call count onto the feed, which is
  // the same number the receipt shows and is not blind until the run's terminal.
  | 'iterationsUsed'
  | 'budgetUpdates'
  // Tempdoc 859 §A §1.3 — the OPEN region, which has no conversation entry yet because it has not
  // been cut. Every CLOSED region is already an ordered `conversation` entry; this is the one item
  // the array cannot hold, and it is the only item that may wear the live affordance.
  | 'reasoning'
>;

/* ── Axis 1: the session ─────────────────────────────────────────────────────────────────────── */

/**
 * What the shared controller says about the run. `holding` outranks `live` because a run parked on a
 * decision is not making progress — it is waiting for the reader, which is the act-now colour.
 */
export type Sv3RunSessionStatus = 'absent' | 'live' | 'holding' | 'settled';

/* ── Axis 2: this window's turn ──────────────────────────────────────────────────────────────── */

/**
 * What THIS window's own agent turn is doing. `dispatching` is the optimistic window: the reader's
 * text left, and nothing from the server has come back yet.
 */
export type Sv3RunTurnState = 'none' | 'dispatching' | 'open' | 'settled';

/**
 * The ONE value rendering reads. Five, not four: the spec's four phases assume the dispatch is
 * server-confirmed by construction (its client speaks one RPC socket), whereas this window dispatches
 * optimistically and must be able to SAY so — `dispatching` is that state, and pattern 2's predicate
 * is what leaves it.
 */
export type Sv3RunPhase = 'idle' | 'dispatching' | 'running' | 'holding' | 'ended';

export interface Sv3RunAxes {
  readonly session: Sv3RunSessionStatus;
  readonly turn: Sv3RunTurnState;
}

/**
 * The turn axis decides WHETHER a run is on screen; the session axis decides WHAT KIND of open it is.
 * Ordering them this way is what keeps the receipt honest: a settled turn is `ended` no matter what
 * the product-wide controller is doing next, so a later run started from another surface can never
 * re-open this window's concluded turn.
 */
export function deriveSv3RunPhase({ session, turn }: Sv3RunAxes): Sv3RunPhase {
  if (turn === 'none') return 'idle';
  if (turn === 'settled') return 'ended';
  if (turn === 'dispatching') return 'dispatching';
  return session === 'holding' ? 'holding' : 'running';
}

/* ── The feed: ONE projection the live half renders and the receipt half counts ──────────────── */

export interface Sv3RunFeedText {
  readonly kind: 'text';
  readonly id: string;
  readonly text: string;
}

export interface Sv3RunFeedTool {
  readonly kind: 'tool';
  readonly id: string;
  readonly call: ToolCall;
}

/** A run step that is neither the agent's prose nor a tool call — progress, a handoff, an error. */
export interface Sv3RunFeedNote {
  readonly kind: 'note';
  readonly id: string;
  readonly label: string;
  readonly text: string;
}

/**
 * Tempdoc 859 §A §1.1 — one closed region of the model's thinking, IN THE POSITION IT WAS PRODUCED.
 *
 * A fourth variant of the item union rather than a fourth accumulator: there was already ONE ordered
 * timeline shape rendered by ONE renderer, and it was simply missing reasoning. `streaming` is true
 * for at most one item — the run's open region, which is always the newest — so a finished thought
 * has no path at all to the live affordance (the A3 leak, closed by construction rather than by a
 * render-time suppression).
 */
export interface Sv3RunFeedReasoning {
  readonly kind: 'reasoning';
  readonly id: string;
  readonly text: string;
  readonly durationMs: number;
  readonly streaming: boolean;
}

export type Sv3RunFeedItem =
  | Sv3RunFeedText
  | Sv3RunFeedTool
  | Sv3RunFeedNote
  | Sv3RunFeedReasoning;

/**
 * The id of the live feed's trailing (still-open) reasoning item. A constant, because there is at
 * most one at a time and it has no controller entry to take an id from; it is replaced by the
 * entry-id-bearing closed item the moment the region is cut.
 */
export const SV3_LIVE_REASONING_ITEM_ID = 'run-reasoning-live';

export interface Sv3RunFeed {
  readonly items: readonly Sv3RunFeedItem[];
  /**
   * The receipt's number, and the ONLY one: it is `items.filter(tool).length`, so it counts exactly
   * the cards the feed rendered. A separately-maintained counter is what lets a receipt disagree with
   * the run it summarises.
   */
  readonly toolCallCount: number;
  /** A tool call the run has not been allowed to make yet — the act-now half of the session axis. */
  readonly pendingApprovals: readonly Sv3RunPromptApproval[];
  /** The run reported an error entry. Feeds the outcome, so a failure cannot settle as a completion. */
  readonly errored: boolean;
}

export const SV3_RUN_FEED_EMPTY: Sv3RunFeed = {
  items: [],
  toolCallCount: 0,
  pendingApprovals: [],
  errored: false,
};

/** The label a non-prose run step carries. Closed vocabulary; an unlisted kind falls back to "Step". */
const RUN_NOTE_LABEL: Partial<Record<ConversationEntry['type'], string>> = {
  error: 'Error',
  progress: 'Progress',
  handoff: 'Handoff',
  'run-node': 'Node',
};

/**
 * Project the run's own slice of the shared conversation into the feed.
 *
 * `from` is the entry count captured at dispatch — the window's slice of a product-wide controller.
 * The reader's own turn is skipped: the transcript already holds it as the question that started the
 * run, and rendering it again here would be a second conversation model.
 */
export function projectSv3RunFeed(source: Sv3RunSource, from: number): Sv3RunFeed {
  const items: Sv3RunFeedItem[] = [];
  const pendingApprovals: Sv3RunPromptApproval[] = [];
  const seenCalls = new Set<string>();
  let errored = false;

  for (const entry of source.conversation.slice(Math.max(0, from))) {
    if (entry.type === 'user') continue;
    if (entry.type === 'assistant-text') {
      items.push({ kind: 'text', id: entry.id, text: entry.content });
      continue;
    }
    if (entry.type === 'tool-call-group') {
      for (const callId of entry.callIds ?? []) {
        const call = source.toolCalls[callId];
        // A group can name a call the controller has not recorded yet; counting it would put a number
        // on the receipt with no card behind it.
        if (call === undefined || seenCalls.has(callId)) continue;
        seenCalls.add(callId);
        items.push({ kind: 'tool', id: callId, call });
        if (call.status === 'pending') {
          pendingApprovals.push({
            kind: 'approval',
            id: callId,
            toolName: call.toolName,
            risk: call.risk,
          });
        }
      }
      continue;
    }
    // Tempdoc 859 §A §1.1 — a CLOSED region, in position. Branched BEFORE the note fallback: left to
    // it, a thought would have been labelled "Step" and drawn as a one-line progress note.
    if (entry.type === 'reasoning') {
      items.push({
        kind: 'reasoning',
        id: entry.id,
        text: entry.content,
        durationMs: entry.durationMs ?? 0,
        streaming: false,
      });
      continue;
    }
    if (entry.type === 'error') errored = true;
    if (entry.type === 'steer-directive') {
      // WP6.2 — named by what happened to the direction, the same way the record names it.
      items.push({
        kind: 'note',
        id: entry.id,
        ...directionNote(entry.content, entry.directiveOutcome, entry.directiveReason),
      });
      continue;
    }
    items.push({
      kind: 'note',
      id: entry.id,
      label: RUN_NOTE_LABEL[entry.type] ?? 'Step',
      text: entry.content,
    });
  }

  // Tempdoc 859 §A §1.3 — the region that is still open, appended LAST because it is by definition
  // the newest thing the run has produced. It is the only item that can be `streaming`, which is what
  // makes the false-liveness leak unreachable rather than merely suppressed.
  const live = source.reasoning;
  if (live.reasoningText !== '') {
    items.push({
      kind: 'reasoning',
      id: SV3_LIVE_REASONING_ITEM_ID,
      text: live.reasoningText,
      // The controller's own answer, not a second derivation from its clock: once output has been
      // seen the duration is already settled, and re-deriving it here would make the row's figure
      // snap back the moment the region was cut.
      durationMs: live.openRegionDurationMs,
      streaming: live.isThinking,
    });
  }

  return {
    items,
    toolCallCount: items.reduce((n, item) => (item.kind === 'tool' ? n + 1 : n), 0),
    pendingApprovals,
    errored,
  };
}

/* ── Typed pending states (pattern 4) ────────────────────────────────────────────────────────── */

export interface Sv3RunPromptBudget {
  readonly kind: 'budget';
  readonly id: 'run-budget-gate';
  readonly tokensNeeded: number;
  readonly tokensRemaining: number;
  /**
   * Tempdoc 859 §D §2.4 — what the run has spent so far, so the reader can answer "is this worth
   * more?" from what actually happened rather than from a token count alone. Carried on the prompt
   * VALUE so the render site stays a renderer: a surface that computed these itself would be a
   * second answer to "what did this run do".
   */
  readonly facts: BudgetGateFacts;
  /**
   * Tempdoc 859 §D §2.5 — the three sized continues, each already floored to clear THIS gate.
   * The `addTokens` on each is what the directive spends, so a label can never promise a different
   * number than the click delivers.
   */
  readonly steps: readonly BudgetContinueStep[];
}

export interface Sv3RunPromptContext {
  readonly kind: 'context';
  readonly id: 'run-context-gate';
  readonly promptTokens: number;
  readonly contextWindow: number;
}

/**
 * A tool call held for a human decision. The window RENDERS it and says what is held; it does NOT
 * offer its own Approve/Deny, because the product has exactly one approve/deny ceremony
 * (`operations/authorizationBroker.ts:14-21` — the inline per-card buttons were retired INTO it) and a
 * second set here would be that fork coming back under a new window's name.
 */
export interface Sv3RunPromptApproval {
  readonly kind: 'approval';
  readonly id: string;
  readonly toolName: string;
  readonly risk: ToolRisk;
}

export type Sv3RunPrompt = Sv3RunPromptBudget | Sv3RunPromptContext | Sv3RunPromptApproval;

/**
 * Every decision the run is currently parked on. Order is fixed and structural — economic gate, then
 * cognitive gate, then the held calls — so a prompt cannot move under the pointer as another arrives.
 */
export function projectSv3RunPrompts(
  source: Sv3RunSource,
  feed: Sv3RunFeed,
  // Tempdoc 859 §D §2.4 — the two facts the CONTROLLER cannot know: when this window's turn was
  // asked, and what time it is now. `askedAt` rides the turn (and the record), so the elapsed
  // figure survives a reload; `now` is passed in because a pure projection has no clock.
  gateContext: { readonly askedAt: number | null; readonly now: number } = {
    askedAt: null,
    now: 0,
  },
): readonly Sv3RunPrompt[] {
  const prompts: Sv3RunPrompt[] = [];
  const budget = source.budgetGate;
  if (budget) {
    const lastUpdate = source.budgetUpdates.at(-1) ?? null;
    const lastTool = [...feed.items].reverse().find((item) => item.kind === 'tool');
    prompts.push({
      kind: 'budget',
      id: 'run-budget-gate',
      tokensNeeded: budget.tokensNeeded,
      tokensRemaining: budget.tokensRemaining,
      facts: projectBudgetGateFacts({
        totalTokensConsumed: budget.totalTokensConsumed,
        // 859 D live-defect D1 — the FEED's own count, not a controller counter written only at the
        // run's terminal (which was therefore structurally 0 at every mid-run gate). It is also the
        // number the receipt uses, so the panel and the receipt cannot describe different runs — the
        // single-authority rule {@link Sv3RunFeed.toolCallCount} already states for this window.
        //
        // DELIBERATE BOUNDARY, worth knowing before "reconciling" the two: this counts CARDS, and a
        // handoff draws one (`AgentStepRunner` emits ToolCallProposed/ToolExecutionCompleted for it)
        // without calling `session.recordExecution`, so the backend's own `done.toolCallsExecuted`
        // excludes it. The panel wants what the reader can see and scroll to; the terminal payload
        // wants tool executions. They answer different questions and are not expected to match.
        toolCallsExecuted: feed.toolCallCount,
        // Live from the run's `progress` frames (and the reattach primer); `null` while nothing has
        // said, which the panel OMITS rather than rendering as zero.
        iterationsUsed: source.iterationsUsed,
        askedAt: gateContext.askedAt,
        now: gateContext.now,
        // Genuinely absent before the first tool call — omitted, never rendered as "none".
        lastAction: lastTool !== undefined && lastTool.kind === 'tool' ? lastTool.call.toolName : null,
      }),
      steps: budgetContinueSteps({
        totalTokensConsumed: budget.totalTokensConsumed,
        tokensNeeded: budget.tokensNeeded,
        // RAW: the display clamp lives in `projectBudget`, and borrowing it here would under-fund
        // every over-budget gate by exactly the overrun (859 §D P4).
        tokensRemaining: budget.tokensRemaining,
        promptTokens: lastUpdate?.promptTokens,
      }),
    });
  }
  const context = source.contextGate;
  if (context) {
    prompts.push({
      kind: 'context',
      id: 'run-context-gate',
      promptTokens: context.promptTokens,
      contextWindow: context.contextWindow,
    });
  }
  prompts.push(...feed.pendingApprovals);
  return prompts;
}

/**
 * Axis 1, derived. `holding` is read from the gates AND from a held tool call, because both are the
 * same fact to the reader: the run stopped and is waiting for them.
 */
export function sv3RunSessionStatus(source: Sv3RunSource | null, feed: Sv3RunFeed): Sv3RunSessionStatus {
  if (source === null) return 'absent';
  // Tempdoc 834 §6.2 — `runPark` is the fourth way the same fact arrives, and the only one that
  // survives a reattach whose announcing frame the ring evicted. Without it a parked run that this
  // tab did not watch park would read as `live`, i.e. as silently idle: streaming, saying nothing.
  if (
    source.budgetGate !== null ||
    source.contextGate !== null ||
    source.runPark !== null ||
    feed.pendingApprovals.length > 0
  ) {
    return 'holding';
  }
  // Both flags, not `runInFlight` alone: the controller's first notification of a run arrives from
  // inside `send()` before its abort controller exists, and they clear together in the stream's
  // `finally` (`AgentSessionController.ts:1368-1370` / `:1436-1441`), so the terminal edge stays exact.
  return source.runInFlight || source.isStreaming ? 'live' : 'settled';
}

/**
 * Everything the content surface needs to render the ONE live run, handed down as a single value.
 * `turnId` is the explicit activeTurnId: the surface renders the feed against the turn that OPENED
 * the run and against no other, so a run cannot appear under a turn it did not come from.
 */
export interface Sv3RunView {
  readonly turnId: string;
  readonly phase: Sv3RunPhase;
  readonly feed: Sv3RunFeed;
  readonly prompts: readonly Sv3RunPrompt[];
}

/* ── The optimistic-handoff predicate (pattern 2) ────────────────────────────────────────────── */

/**
 * What this window remembers about the run it dispatched. The `ref` IS the explicit activeTurnId:
 * every stream effect is written to the turn the dispatch opened, never to "the active session".
 */
export interface Sv3RunLocal {
  readonly sessionId: string;
  readonly turnId: string;
  /** The controller's entry count at dispatch — this window's slice of a product-wide conversation. */
  readonly entryStart: number;
  /** The controller's run id at dispatch; a DIFFERENT one afterwards is the server having spoken. */
  readonly sessionIdAtDispatch: string | null;
  /** Latched by the window once {@link hasServerAcknowledgedLocalDispatch} first holds. */
  acknowledged: boolean;
  /** The reader asked to stop. Kept so the receipt can say `halted` rather than guess at a cause. */
  haltRequested: boolean;
  /**
   * The halt actually reached the run. A stop pressed during `dispatching` cannot be delivered yet —
   * the controller's abort handle does not exist until the stream opens, so the seam's lifecycle
   * predicate correctly refuses it. Remembering the request lets the window deliver it once the run
   * IS live, instead of dropping the reader's decision on the floor or firing it every frame.
   */
  haltDispatched: boolean;
  /**
   * Tempdoc 859 §D §2.5 — the reader said "don't ask me again for this run", and the amount they
   * chose when they said it. PER RUN by construction (it lives on this object, which a new dispatch
   * replaces), so it can never become standing autonomy across runs.
   */
  autoContinueTokens: number | null;
  /**
   * How many gates this run has auto-answered. Bounded by {@link AUTO_CONTINUE_LIMIT}, after which
   * the gate asks again rather than stopping — the reader's decision is re-applied, not surrendered
   * to, and the run narrates every re-application (the backend's `budget_raised` note).
   */
  autoContinuesUsed: number;
}

/** Tempdoc 859 §D §2.5 — how many gates one run may auto-answer before it asks again. */
export const AUTO_CONTINUE_LIMIT = 3;

/**
 * Should this gate be answered without asking? True only while the reader has an active per-run
 * choice AND the run has not already spent its allowance of silent continues.
 */
export function shouldAutoContinue(local: Sv3RunLocal): boolean {
  return local.autoContinueTokens !== null && local.autoContinuesUsed < AUTO_CONTINUE_LIMIT;
}

/**
 * Has the SERVER acknowledged the locally-dispatched run yet?
 *
 * This is the condition under which the local "Sending…" echo yields to server truth, named and
 * tested rather than raced. Only server-produced evidence counts: an entry appended to the run's own
 * slice, streaming text, or a run id the controller did not have at dispatch (`onSessionStarted`).
 * `runInFlight` and `isStreaming` are deliberately absent — the controller sets both optimistically
 * inside `send()`, so treating them as acknowledgment would make the predicate always true and the
 * "Sending…" state unreachable.
 *
 * MONOTONE BY CALLER: the window latches the first true and never reads it again, so a run whose
 * evidence later disappears (a reset, another surface's run) cannot push this window back into
 * claiming it is still sending.
 */
export function hasServerAcknowledgedLocalDispatch(
  local: Sv3RunLocal,
  source: Sv3RunSource | null,
): boolean {
  if (source === null) return false;
  // The reader's OWN prompt is echoed into the conversation synchronously by `send()`
  // (`AgentSessionController.ts:1350-1353`) — counting it would make the predicate true before the
  // request had even left, and the "Sending…" state unreachable. Only an entry the RUN produced counts.
  if (source.conversation.slice(local.entryStart).some((entry) => entry.type !== 'user')) return true;
  if (source.streamingText !== '') return true;
  return source.sessionId !== null && source.sessionId !== local.sessionIdAtDispatch;
}

/* ── Presence: a run this window did not dispatch (tempdoc 822 Phase F3) ─────────────────────── */

/** What an adopted run is called when the controller holds no text the reader would recognise. */
export const SV3_RUN_PRESENCE_TITLE = 'Agent run in progress';

/**
 * Where the LIVE run's slice of the shared conversation begins.
 *
 * The controller APPENDS across runs — `send()` and `runWorkflow()` push a `user` entry onto the
 * existing conversation and never clear it (`AgentSessionController.ts:1350-1353` / `:1454-1462`),
 * and those two are the only writers of that entry type (a steer is a `steer-directive`). So the LAST
 * user entry is where the run that is live now was asked for, and everything before it belongs to a
 * run that already ended. Starting an adopted run at 0 instead would put a finished run's steps in
 * this one's feed and its tool calls in this one's receipt — the wrong-origin class this module's
 * `activeTurnId` exists to prevent, arriving through the back door.
 */
export function sv3RunPresenceStart(source: Sv3RunSource): number {
  for (let index = source.conversation.length - 1; index >= 0; index -= 1) {
    if (source.conversation[index]?.type === 'user') return index;
  }
  return 0;
}

/**
 * The run's own words, for the session row it is about to get: the task the LIVE run was given
 * ({@link sv3RunPresenceStart}), first line only — what a 36px row can show. Nothing is invented:
 * with no user entry to read, the row says only that a run is in progress.
 */
export function sv3RunPresenceTitle(source: Sv3RunSource): string {
  const start = source.conversation[sv3RunPresenceStart(source)];
  const line =
    start?.type === 'user' ? ((start.content ?? '').trim().split('\n')[0]?.trim() ?? '') : '';
  return line === '' ? SV3_RUN_PRESENCE_TITLE : line;
}

export interface Sv3PresenceProbe {
  /** Axis 1 — what the shared controller says right now. */
  readonly status: Sv3RunSessionStatus;
  /** This window already has an OPEN turn standing for a run; a settled turn stands for nothing. */
  readonly represented: boolean;
  /** The controller's run id, or null before the server has named one. */
  readonly runId: string | null;
  /** Run ids this window has already given a session, so one run is adopted at most once. */
  readonly adoptedRunIds: ReadonlySet<string>;
}

/**
 * Should the window synthesise a session for the controller's run?
 *
 * The F2 named finding in predicate form: the shared controller is the authority on whether a run is
 * live, so a window whose in-memory list cannot account for a live run must say the run is there
 * rather than render an empty sidebar beside a working agent. Adoption is once per run id — without
 * the latch, the same run would be adopted again on the next notification after its turn settled.
 */
export function sv3RunNeedsPresence({
  status,
  represented,
  runId,
  adoptedRunIds,
}: Sv3PresenceProbe): boolean {
  if (status !== 'live' && status !== 'holding') return false;
  if (represented) return false;
  return runId === null || !adoptedRunIds.has(runId);
}

/* ── The receipt's outcome ───────────────────────────────────────────────────────────────────── */

/** The three honest ends of a run. `halted` is the reader's own act and is never worded as a failure. */
export type Sv3RunOutcome = 'complete' | 'halted' | 'failed';

/**
 * Halt wins over an error entry: a run the reader stopped may well have logged something on its way
 * down, and reporting that as a failure would blame the product for the reader's decision.
 */
export function sv3RunOutcome(feed: Sv3RunFeed, haltRequested: boolean): Sv3RunOutcome {
  if (haltRequested) return 'halted';
  return feed.errored ? 'failed' : 'complete';
}

/**
 * The receipt, in words: what the run did, and how it ended. The count is the feed's own, so the
 * sentence cannot describe a set the reader never saw.
 *
 * "Stopped by you" is an OUTCOME here, sitting in the same slot as "finished" rather than in an error
 * position: the reader ended the run on purpose, and a receipt that hedged about it ("incomplete",
 * "interrupted") would be describing their decision as a malfunction.
 */
export function sv3RunReceiptLabel(
  toolCalls: number,
  status: Sv3TurnStatus,
  // Tempdoc 859 §D §2.6 — the run's terminal disposition. A delegate turn's receipt IS its tail
  // (`projectSv3AnswerFrame` refuses anything but an ask), so this is where the cut-short badge has
  // to land for the runs that can actually BE cut short.
  disposition: string | null = null,
): string {
  const calls = `${toolCalls} tool ${toolCalls === 1 ? 'call' : 'calls'}`;
  // Owner decision 2026-08-26 (answerless cancel) — A HALT WITH NO TERMINAL AT ALL gets the count
  // and NOTHING ELSE.
  //
  // An absent disposition is not a missing detail here, it is the whole fact: a run stopped before
  // it produced an answer never emits `AgentDone` (`AgentStepRunner` emits `AgentError` with
  // `CANCELLED` and returns), so there is no terminal on either plane and nothing that could have a
  // disposition. There is no outcome to report that early — "stopped by you" is the ending of a
  // response, and this run never began one. What the record used to say instead was "failed", off
  // the error entry the cancel logged on its way down; the reader knows they pressed Stop, and the
  // honest receipt is the work that had happened by then.
  //
  // KEYED ON THE TERMINAL, NOT ON THE ANSWER TEXT, and the difference is load-bearing: a live
  // delegate turn's `answer` is empty until the record catches up (only the ask path streams into
  // it), so a text test would word the same halt two ways one fetch apart. Both planes carry the
  // disposition at the moment they settle, so both reach this line with the same value.
  if (status === 'halted' && disposition === null) {
    return calls;
  }
  const ending =
    status === 'halted'
      ? 'stopped by you'
      : status === 'failed'
        ? 'failed'
        : status === 'refused'
          ? 'refused'
          : status === 'streaming'
            ? 'running'
            : 'finished';
  const receipt = `${calls} · ${ending}`;
  // The run "finished" AND was cut short — two orthogonal facts, both true, neither replacing the
  // other. The status stays what it was: a truncated run really did complete its terminal.
  return sv3WasCutShort(disposition) ? `${receipt} · ${SV3_CUT_SHORT_BADGE}` : receipt;
}

/* ── The primary-action slot (pattern 3) ─────────────────────────────────────────────────────── */

/**
 * The four rungs, in strict priority order. Exactly ONE is ever rendered — the design spec's
 * primary actions early-return rather than disabling the loser, and F1 made that structural
 * here for Stop-vs-Send; this is the same rule with two more rungs.
 */
export type Sv3SlotKind = 'answer' | 'stop' | 'follow-up' | 'send';

/**
 * WHICH TIER a send routes to (tempdoc 822 Phase F2; the visible control is 852 S4 / ledger row 12).
 *
 * Declared HERE rather than on the composer element because it is the same vocabulary the send hint
 * below words and the window's dispatch reads — one declaration, three readers. The composer
 * re-exports it so its `sv3-composer-submit` contract is still readable in one file.
 *
 * Both tiers are already live and tested: `ask` posts `core.rag-ask` through the shared request
 * builder, `delegate` goes through the shared agent controller. What S4 adds is the AFFORDANCE — the
 * routing was reachable only by chord until then.
 */
export type Sv3ComposerTier = 'ask' | 'delegate';

export interface Sv3TierOption {
  readonly id: Sv3ComposerTier;
  /** The trigger's label when this tier is chosen — the control's label IS the current value. */
  readonly label: string;
  /** One line, in the menu, saying what the tier actually does differently. */
  readonly description: string;
  /** The tier a window starts on; the menu badges it, as the effort menu badges its own. */
  readonly isDefault: boolean;
}

/** The menu's group label. "Mode", not "Tier": the word has to mean something to a reader. */
export const SV3_TIER_MENU_LABEL = 'Mode';

export const SV3_TIER_OPTIONS: readonly Sv3TierOption[] = [
  {
    id: 'ask',
    label: 'Ask',
    description: 'Answers from your documents, in this conversation.',
    isDefault: true,
  },
  {
    id: 'delegate',
    label: 'Delegate',
    description: 'Hands the task to the agent, which works in steps and can use tools.',
    isDefault: false,
  },
];

export const SV3_TIER_DEFAULT: Sv3ComposerTier = 'ask';

export const isSv3Tier = (value: unknown): value is Sv3ComposerTier =>
  SV3_TIER_OPTIONS.some((option) => option.id === value);

export const sv3TierLabel = (tier: Sv3ComposerTier): string =>
  SV3_TIER_OPTIONS.find((option) => option.id === tier)?.label ?? '';

export interface Sv3SlotInput {
  /** A typed prompt is held. Outranks everything: nothing else can proceed until it is resolved. */
  readonly pendingPrompt: boolean;
  /** A response (ask or agent) is in flight. */
  readonly running: boolean;
  /** The claimed conversation already holds a settled turn, so the next send CONTINUES it. */
  readonly followUp: boolean;
  /**
   * Which tier a plain send now routes to (852 S4). OPTIONAL, defaulting to the tier a window with
   * no control was always on — so this is additive: an omitted tier words exactly the sentence the
   * slot worded before the control existed.
   */
  readonly tier?: Sv3ComposerTier;
}

export interface Sv3Slot {
  readonly kind: Sv3SlotKind;
  /**
   * Why this control is the one in the slot, in the reader's words. Carried into the aria-label and
   * the title so the reason and the control cannot drift apart — they are one derivation.
   */
  readonly reason: string;
  /**
   * Owner decision 2026-08-26 — the STANDING stop's reason, or `''` when no second control is
   * needed.
   *
   * <p>The slot's strict priority is right about which control is PRIMARY and wrong about stopping
   * being optional. A supervised delegate run holds on a tool approval for most of its life, so
   * `pendingPrompt` outranks `running` for most of its life — and the reader was measured with no
   * stop control at all for 300 s of a live run. Halting a run is a capability, not a rung: it
   * cannot be something the window withdraws because a more urgent decision is also outstanding.
   *
   * <p>So the stop keeps its home OUTSIDE the slot in exactly that case, the way the tier control
   * sits beside the slot rather than in it. The slot itself still early-returns exactly one control
   * and still never disables the loser — this is a second control, not a second winner — and the two
   * are mutually exclusive by construction: this is `''` whenever {@link kind} is already `stop`, so
   * the window renders exactly one stop while a run is live and none when it is not.
   */
  readonly haltReason: string;
}

/**
 * The standing stop's words. Not the slot rung's sentence: that one explains what Enter does instead
 * ("Enter steers"), and Enter does NOT steer while a decision is holding the run — the composer
 * refuses a submit on the `answer` rung. A control whose label described a key that does nothing
 * would be the chrome-that-lies this window's slot reasons exist to prevent.
 */
export const SV3_STANDING_STOP_REASON = 'Stop the run';

/**
 * The window's send-routing vocabulary, said once so the composer's label and the window's routing
 * cannot describe different keys. Enter asks; Ctrl+Enter delegates the same draft to the agent.
 */
export const SV3_SEND_HINT = 'Enter asks · Ctrl+Enter delegates to the agent';

/**
 * The same sentence when the reader has put the mode control on DELEGATE: Enter now delegates, and
 * the chord it used to take is the same act. Said as one clause rather than two, because "Ctrl+Enter
 * also delegates" is a key that changes nothing — the routing explanation must describe what the
 * keys DO, not restate that a control was moved.
 */
export const SV3_DELEGATE_SEND_HINT = 'Enter delegates to the agent';

/** Mid-run, a submit JOINS the live turn instead of starting a second one — it is not an interrupt. */
export const SV3_STEER_HINT = 'Enter steers the running agent';

export function sv3PrimaryAction({
  pendingPrompt,
  running,
  followUp,
  tier = SV3_TIER_DEFAULT,
}: Sv3SlotInput): Sv3Slot {
  // The routing explanation lives on the send control and nowhere else, so it has to follow the
  // chosen tier: a send that delegates while its own label says "Enter asks" is chrome that lies.
  const hint = tier === 'delegate' ? SV3_DELEGATE_SEND_HINT : SV3_SEND_HINT;
  if (pendingPrompt) {
    return {
      kind: 'answer',
      reason: 'The run is waiting for your decision',
      // Keyed on the RUN BEING LIVE, not on the prompt: the stop is offered because there is
      // something to stop, and a prompt left over from a run that has already ended is not it.
      haltReason: running ? SV3_STANDING_STOP_REASON : '',
    };
  }
  if (running) {
    // The slot IS the stop here, so there is no second one — one live run, one stop control.
    return { kind: 'stop', reason: `Stop the response · ${SV3_STEER_HINT}`, haltReason: '' };
  }
  if (followUp) {
    return {
      kind: 'follow-up',
      reason: `Send a follow-up in this conversation · ${hint}`,
      haltReason: '',
    };
  }
  return { kind: 'send', reason: `Send · ${hint}`, haltReason: '' };
}
