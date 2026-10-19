// @vitest-environment happy-dom
/**
 * The Search v3 run model's semantics (tempdoc 822 Phase F2).
 *
 * No DOM: every derivation here is pure, so the two-axis phase, the optimistic-handoff predicate, the
 * feed projection and the slot state machine are all decidable from values alone. The window-level
 * consequences (which control renders, where a directive goes, what the receipt says) live in
 * `SearchV3View.agentRun.test.ts`.
 *
 * The properties asserted as MECHANISMS rather than appearances:
 *  - the slot's PRIORITY, not just its membership: each rung is asserted while the lower ones are
 *    also true, so a re-ordered machine fails;
 *  - the handoff predicate is FALSE on the controller's own optimism (the echoed user entry, the
 *    locally-set flags) — a predicate that ignored that would be true before the request left;
 *  - the receipt count is the feed's own item count, so it cannot be produced by a second counter.
 */
import { describe, it, expect, vi } from 'vitest';
import {
  deriveSv3RunPhase,
  hasServerAcknowledgedLocalDispatch,
  projectSv3RunFeed,
  projectSv3RunPrompts,
  sv3PrimaryAction,
  sv3RunNeedsPresence,
  sv3RunOutcome,
  sv3RunPresenceStart,
  sv3RunPresenceTitle,
  sv3RunReceiptLabel,
  sv3RunSessionStatus,
  SV3_RUN_FEED_EMPTY,
  SV3_RUN_PRESENCE_TITLE,
  SV3_STANDING_STOP_REASON,
  type Sv3RunLocal,
  type Sv3RunSource,
} from './sv3-run.js';
import type { ConversationEntry, ToolCall } from '../../controllers/AgentSessionController.js';
import { ReasoningController } from '../../controllers/ReasoningController.js';

const entry = (over: Partial<ConversationEntry> & Pick<ConversationEntry, 'type'>): ConversationEntry => ({
  id: `e-${Math.random().toString(36).slice(2)}`,
  content: '',
  timestamp: 0,
  ...over,
});

const call = (over: Partial<ToolCall> & Pick<ToolCall, 'callId'>): ToolCall => ({
  toolName: 'core_search',
  arguments: '{}',
  risk: 'LOW',
  status: 'completed',
  ...over,
});

const source = (over: Partial<Sv3RunSource> = {}): Sv3RunSource => ({
  conversation: [],
  toolCalls: {},
  streamingText: '',
  isStreaming: false,
  runInFlight: false,
  sessionId: null,
  budgetGate: null,
  contextGate: null,
  runPark: null,
  // 859 D live-defect D1 — `null` is the controller's own starting value: "nothing has reported a
  // step yet", which the gate panel omits rather than rendering as 0.
  iterationsUsed: null,
  budgetUpdates: [],
  // Tempdoc 859 §A — a real controller, not a stub: the trailing live-region item is derived from
  // its actual state machine, and a hand-rolled `{isThinking:true}` would let the projection pass
  // against a shape the controller can no longer produce.
  reasoning: new ReasoningController(() => {}),
  ...over,
});

const local = (over: Partial<Sv3RunLocal> = {}): Sv3RunLocal => ({
  sessionId: 'sv3-session-1',
  turnId: 'sv3-session-1#t1',
  entryStart: 0,
  sessionIdAtDispatch: null,
  acknowledged: false,
  haltRequested: false,
  haltDispatched: false,
  autoContinueTokens: null,
  autoContinuesUsed: 0,
  ...over,
});

describe('presence: when a live run needs a session it does not have (Phase F3)', () => {
  const probe = (over: Partial<Parameters<typeof sv3RunNeedsPresence>[0]> = {}) => ({
    status: 'live' as const,
    represented: false,
    runId: 'run-1',
    adoptedRunIds: new Set<string>(),
    ...over,
  });

  it('adopts a live or holding run that nothing in the window stands for', () => {
    expect(sv3RunNeedsPresence(probe())).toBe(true);
    expect(sv3RunNeedsPresence(probe({ status: 'holding' }))).toBe(true);
    // A run named by nothing yet is still a live run — the id latch cannot be a precondition.
    expect(sv3RunNeedsPresence(probe({ runId: null }))).toBe(true);
  });

  it('adopts nothing when there is no run, or the window already stands for one', () => {
    expect(sv3RunNeedsPresence(probe({ status: 'settled' }))).toBe(false);
    expect(sv3RunNeedsPresence(probe({ status: 'absent' }))).toBe(false);
    // An OPEN turn already renders this run; adopting again would double it in the sidebar.
    expect(sv3RunNeedsPresence(probe({ represented: true }))).toBe(false);
  });

  it('adopts one run ONCE, so a settled turn does not re-adopt the same run every frame', () => {
    expect(sv3RunNeedsPresence(probe({ adoptedRunIds: new Set(['run-1']) }))).toBe(false);
    // A DIFFERENT run started elsewhere is still news.
    expect(sv3RunNeedsPresence(probe({ runId: 'run-2', adoptedRunIds: new Set(['run-1']) }))).toBe(true);
  });

  it('starts the adopted run at the LIVE run\'s task, not at the conversation\'s beginning', () => {
    // The controller appends across runs (`send()` never clears the conversation) and `user` is
    // written only at a run's start, so the LAST one begins the run that is live now. Starting at 0
    // would count a finished run's tool calls into this run's receipt.
    const conversation = [
      entry({ type: 'user', content: 'a run that already ended' }),
      entry({ type: 'tool-call-group', callIds: ['old'] }),
      entry({ type: 'user', content: 'the live task' }),
      entry({ type: 'tool-call-group', callIds: ['mine'] }),
    ];
    expect(sv3RunPresenceStart(source({ conversation }))).toBe(2);
    // With nothing to exclude, the slice is the whole conversation.
    expect(sv3RunPresenceStart(source())).toBe(0);
    expect(
      sv3RunPresenceStart(source({ conversation: [entry({ type: 'assistant-text' })] })),
    ).toBe(0);
    // The feed taken from that start holds only the live run's card.
    const feed = projectSv3RunFeed(
      source({ conversation, toolCalls: { old: call({ callId: 'old' }), mine: call({ callId: 'mine' }) } }),
      sv3RunPresenceStart(source({ conversation })),
    );
    expect(feed.toolCallCount).toBe(1);
    expect(feed.items.map((item) => item.id)).toEqual(['mine']);
  });

  it('titles the adopted session with the run\'s own task text, and claims nothing otherwise', () => {
    expect(
      sv3RunPresenceTitle(source({ conversation: [entry({ type: 'user', content: 'index the vendor folder' })] })),
    ).toBe('index the vendor folder');
    // The task of the run that is LIVE — an earlier run's prompt is not this row's label.
    expect(
      sv3RunPresenceTitle(
        source({
          conversation: [
            entry({ type: 'user', content: 'the previous task' }),
            entry({ type: 'assistant-text', content: 'done' }),
            entry({ type: 'user', content: 'the live task' }),
          ],
        }),
      ),
    ).toBe('the live task');
    // One line only — a 36px row cannot show a paragraph, and a truncated middle would misquote it.
    expect(sv3RunPresenceTitle(source({ conversation: [entry({ type: 'user', content: 'first\nsecond' })] })))
      .toBe('first');
    // With nothing the reader would recognise, the row says only that a run is in progress.
    expect(sv3RunPresenceTitle(source())).toBe(SV3_RUN_PRESENCE_TITLE);
    expect(sv3RunPresenceTitle(source({ conversation: [entry({ type: 'user', content: '  ' })] })))
      .toBe(SV3_RUN_PRESENCE_TITLE);
  });
});

describe('two axes derive ONE phase', () => {
  it('reads the turn axis for whether a run is on screen at all', () => {
    expect(deriveSv3RunPhase({ session: 'live', turn: 'none' })).toBe('idle');
    expect(deriveSv3RunPhase({ session: 'holding', turn: 'none' })).toBe('idle');
    // A SETTLED turn is ended whatever the product-wide controller went on to do next — which is what
    // stops a later run, started from another surface, re-opening this window's concluded turn.
    expect(deriveSv3RunPhase({ session: 'live', turn: 'settled' })).toBe('ended');
    expect(deriveSv3RunPhase({ session: 'holding', turn: 'settled' })).toBe('ended');
  });

  it('reads the session axis for what KIND of open an open turn is', () => {
    expect(deriveSv3RunPhase({ session: 'live', turn: 'open' })).toBe('running');
    expect(deriveSv3RunPhase({ session: 'settled', turn: 'open' })).toBe('running');
    expect(deriveSv3RunPhase({ session: 'holding', turn: 'open' })).toBe('holding');
  });

  it('keeps the optimistic window distinct from running, whatever the session says', () => {
    expect(deriveSv3RunPhase({ session: 'absent', turn: 'dispatching' })).toBe('dispatching');
    expect(deriveSv3RunPhase({ session: 'live', turn: 'dispatching' })).toBe('dispatching');
  });

  it('spends holding on a gate OR a held tool call, and settles only when neither is true', () => {
    expect(sv3RunSessionStatus(null, SV3_RUN_FEED_EMPTY)).toBe('absent');
    expect(sv3RunSessionStatus(source({ runInFlight: true }), SV3_RUN_FEED_EMPTY)).toBe('live');
    // isStreaming alone counts: the controller's flags clear together, so the terminal edge is exact.
    expect(sv3RunSessionStatus(source({ isStreaming: true }), SV3_RUN_FEED_EMPTY)).toBe('live');
    expect(sv3RunSessionStatus(source(), SV3_RUN_FEED_EMPTY)).toBe('settled');
    const gated = source({
      runInFlight: true,
      budgetGate: { tokensNeeded: 100, tokensRemaining: 5, totalTokensConsumed: 900 },
    });
    expect(sv3RunSessionStatus(gated, SV3_RUN_FEED_EMPTY)).toBe('holding');
    // A held tool call is the same fact to the reader: the run stopped and is waiting for them.
    const held = projectSv3RunFeed(
      source({
        conversation: [entry({ type: 'tool-call-group', callIds: ['c1'] })],
        toolCalls: { c1: call({ callId: 'c1', status: 'pending' }) },
      }),
      0,
    );
    expect(sv3RunSessionStatus(source({ runInFlight: true }), held)).toBe('holding');
  });

  // Tempdoc 834 §6.2 — the reattach case: the run is parked, and the frame that said so was evicted
  // from the replay ring before this observer arrived. Without the snapshot's `park` the window
  // would read a streaming-but-silent run as `live`, i.e. as idle with no explanation.
  it('reads the snapshot park as holding, even with no gate value and no held call on this observer', () => {
    const parked = source({
      runInFlight: true,
      runPark: { kind: 'unobserved', sinceEpochMs: 42, detail: 'no observer' },
    });
    expect(sv3RunSessionStatus(parked, SV3_RUN_FEED_EMPTY)).toBe('holding');
    // ...and it is the park doing the work: the same run without it is merely live.
    expect(sv3RunSessionStatus(source({ runInFlight: true }), SV3_RUN_FEED_EMPTY)).toBe('live');
  });
});

describe('the feed is ONE projection the receipt counts', () => {
  it('counts exactly the tool items it renders, and skips the reader own turn', () => {
    const feed = projectSv3RunFeed(
      source({
        conversation: [
          entry({ type: 'user', content: 'find the renewals' }),
          entry({ type: 'assistant-text', content: 'Looking.' }),
          entry({ type: 'tool-call-group', callIds: ['c1', 'c2'] }),
          entry({ type: 'progress', content: 'step 2' }),
        ],
        toolCalls: { c1: call({ callId: 'c1' }), c2: call({ callId: 'c2' }) },
      }),
      0,
    );
    expect(feed.items.map((i) => i.kind)).toEqual(['text', 'tool', 'tool', 'note']);
    expect(feed.toolCallCount).toBe(2);
    expect(feed.toolCallCount).toBe(feed.items.filter((i) => i.kind === 'tool').length);
    expect(feed.errored).toBe(false);
  });

  it('starts at the window own slice, so another run entries are never counted', () => {
    const src = source({
      conversation: [
        entry({ type: 'tool-call-group', callIds: ['old'] }),
        entry({ type: 'tool-call-group', callIds: ['mine'] }),
      ],
      toolCalls: { old: call({ callId: 'old' }), mine: call({ callId: 'mine' }) },
    });
    expect(projectSv3RunFeed(src, 1).toolCallCount).toBe(1);
    expect(projectSv3RunFeed(src, 1).items[0]).toMatchObject({ kind: 'tool', id: 'mine' });
  });

  it('never counts a call id the controller has no card for, nor the same call twice', () => {
    const feed = projectSv3RunFeed(
      source({
        conversation: [
          entry({ type: 'tool-call-group', callIds: ['c1', 'ghost'] }),
          entry({ type: 'tool-call-group', callIds: ['c1'] }),
        ],
        toolCalls: { c1: call({ callId: 'c1' }) },
      }),
      0,
    );
    expect(feed.toolCallCount).toBe(1);
    expect(feed.items).toHaveLength(1);
  });

  it('reports an error entry, so a failure cannot settle as a completion', () => {
    const feed = projectSv3RunFeed(
      source({ conversation: [entry({ type: 'error', content: 'the loop broke' })] }),
      0,
    );
    expect(feed.errored).toBe(true);
    expect(sv3RunOutcome(feed, false)).toBe('failed');
    // ...and the reader own stop still wins: a run they ended is not a product failure.
    expect(sv3RunOutcome(feed, true)).toBe('halted');
    expect(sv3RunOutcome(SV3_RUN_FEED_EMPTY, false)).toBe('complete');
  });

  it('orders the prompts economic, cognitive, then held calls', () => {
    const src = source({
      conversation: [entry({ type: 'tool-call-group', callIds: ['c1'] })],
      toolCalls: { c1: call({ callId: 'c1', status: 'pending', toolName: 'core_write' }) },
      budgetGate: { tokensNeeded: 10, tokensRemaining: 1, totalTokensConsumed: 2 },
      contextGate: { promptTokens: 9, contextWindow: 10 },
    });
    const feed = projectSv3RunFeed(src, 0);
    expect(projectSv3RunPrompts(src, feed).map((p) => p.kind)).toEqual([
      'budget',
      'context',
      'approval',
    ]);
  });

  it('F-1: reasoning is INTERLEAVED, in conversation order, not lifted into a lane', () => {
    const feed = projectSv3RunFeed(
      source({
        conversation: [
          entry({ type: 'user', content: 'find the renewals' }),
          entry({ type: 'reasoning', content: 'search first', durationMs: 900 }),
          entry({ type: 'tool-call-group', callIds: ['c1'] }),
          entry({ type: 'reasoning', content: 'now read it', durationMs: 400 }),
          entry({ type: 'assistant-text', content: 'It expired.' }),
        ],
        toolCalls: { c1: call({ callId: 'c1' }) },
      }),
      0,
    );
    // The SEQUENCE, not the membership: a projection that appended both thoughts at the end would
    // pass a membership assertion and still draw the wall this slice removes.
    expect(feed.items.map((i) => i.kind)).toEqual(['reasoning', 'tool', 'reasoning', 'text']);
    expect(feed.items[0]).toMatchObject({ text: 'search first', durationMs: 900, streaming: false });
  });

  it('F-2: only the OPEN region is streaming — every closed one is settled (the A3 regression)', () => {
    const live = new ReasoningController(() => {});
    live.handleReasoningChunk({ text: 'still working' });
    const feed = projectSv3RunFeed(
      source({
        conversation: [
          entry({ type: 'reasoning', content: 'a finished thought', durationMs: 3000 }),
          entry({ type: 'tool-call-group', callIds: ['c1'] }),
        ],
        toolCalls: { c1: call({ callId: 'c1' }) },
        reasoning: live,
      }),
      0,
    );
    const reasoning = feed.items.filter((i) => i.kind === 'reasoning');
    expect(reasoning.map((i) => i.kind === 'reasoning' && i.streaming)).toEqual([false, true]);
    // The open region is LAST — it is by definition the newest thing the run has produced, which is
    // what makes "only the newest can be live" true by construction rather than by suppression.
    expect(feed.items.at(-1)).toMatchObject({ kind: 'reasoning', streaming: true, text: 'still working' });
  });

  it('F-2b: a region that produced output is still shown, no longer live, and does not snap back', () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date(0));
      const live = new ReasoningController(() => {});
      live.handleReasoningChunk({ text: 'mid-coalesce' });

      vi.setSystemTime(new Date(2_000));
      live.markOutput();
      // 30 further seconds of PROSE. The thought took 2s; the clock has moved 32.
      vi.setSystemTime(new Date(32_000));

      const feed = projectSv3RunFeed(source({ reasoning: live }), 0);
      expect(feed.items).toHaveLength(1);
      expect(feed.items[0]).toMatchObject({
        kind: 'reasoning',
        streaming: false,
        durationMs: 2_000,
      });

      // …and the row that REPLACES it at the cut carries the identical number, which is the whole
      // point: the reader must not watch a settled thought's duration jump when it settles.
      const block = live.closeRegion();
      expect(block?.durationMs).toBe(2_000);
    } finally {
      vi.useRealTimers();
    }
  });

  it('F-3: toolCallCount ignores reasoning items', () => {
    const feed = projectSv3RunFeed(
      source({
        conversation: [
          entry({ type: 'reasoning', content: 'thinking', durationMs: 1 }),
          entry({ type: 'tool-call-group', callIds: ['c1'] }),
        ],
        toolCalls: { c1: call({ callId: 'c1' }) },
      }),
      0,
    );
    expect(feed.toolCallCount).toBe(1);
  });

  it('F-4: a reasoning entry is NOT a note — it must not fall through to "Step"', () => {
    const feed = projectSv3RunFeed(
      source({ conversation: [entry({ type: 'reasoning', content: 'a thought', durationMs: 5 })] }),
      0,
    );
    expect(feed.items).toHaveLength(1);
    expect(feed.items[0]?.kind).toBe('reasoning');
    expect(feed.items.some((i) => i.kind === 'note')).toBe(false);
  });

  it('says a run receipt in words, with stopped-by-you as an OUTCOME and not an error', () => {
    expect(sv3RunReceiptLabel(0, 'complete')).toBe('0 tool calls · finished');
    expect(sv3RunReceiptLabel(1, 'complete')).toBe('1 tool call · finished');
    expect(sv3RunReceiptLabel(3, 'halted', 'COMPLETED')).toBe('3 tool calls · stopped by you');
    expect(sv3RunReceiptLabel(3, 'halted', 'CANCELLED')).toBe('3 tool calls · stopped by you');
    expect(sv3RunReceiptLabel(2, 'failed')).toBe('2 tool calls · failed');
  });

  it('owner 2026-08-26: a halt with NO terminal gets the count and no outcome word at all', () => {
    // A run stopped before it answered emits `AgentError`/CANCELLED and never `AgentDone`, so
    // neither plane has a disposition for it. There is no ending to report, and the record used to
    // report the WRONG one — "failed", off the error entry the cancel logged on its way down.
    expect(sv3RunReceiptLabel(3, 'halted', null)).toBe('3 tool calls');
    expect(sv3RunReceiptLabel(1, 'halted')).toBe('1 tool call');
    expect(sv3RunReceiptLabel(0, 'halted')).toBe('0 tool calls');
    // It is the HALT that is worded differently, not the absent disposition: every other status
    // still says what it always said when the run reported no terminal.
    expect(sv3RunReceiptLabel(3, 'complete', null)).toBe('3 tool calls · finished');
    expect(sv3RunReceiptLabel(3, 'failed', null)).toBe('3 tool calls · failed');
  });
});

describe('the optimistic-handoff predicate names SERVER evidence only', () => {
  it('is false on the controller own optimism', () => {
    expect(hasServerAcknowledgedLocalDispatch(local(), null)).toBe(false);
    expect(hasServerAcknowledgedLocalDispatch(local(), source())).toBe(false);
    // `send()` sets both flags and echoes the reader prompt into the conversation BEFORE the request
    // leaves. If any of those counted, "Sending…" would be unreachable.
    expect(
      hasServerAcknowledgedLocalDispatch(
        local(),
        source({
          isStreaming: true,
          runInFlight: true,
          conversation: [entry({ type: 'user', content: 'do the thing' })],
        }),
      ),
    ).toBe(false);
    // A run id the controller ALREADY had is not news either.
    expect(
      hasServerAcknowledgedLocalDispatch(
        local({ sessionIdAtDispatch: 'run-7' }),
        source({ sessionId: 'run-7' }),
      ),
    ).toBe(false);
  });

  it('flips on the first thing the server produced, and each such thing alone suffices', () => {
    expect(
      hasServerAcknowledgedLocalDispatch(
        local(),
        source({ conversation: [entry({ type: 'user' }), entry({ type: 'assistant-text' })] }),
      ),
    ).toBe(true);
    expect(hasServerAcknowledgedLocalDispatch(local(), source({ streamingText: 'B' }))).toBe(true);
    expect(
      hasServerAcknowledgedLocalDispatch(
        local({ sessionIdAtDispatch: 'run-7' }),
        source({ sessionId: 'run-8' }),
      ),
    ).toBe(true);
  });

  it('is monotone over a run: once true, later frames keep it true', () => {
    const l = local();
    const frames = [
      source({ conversation: [entry({ type: 'user' })] }),
      source({ conversation: [entry({ type: 'user' })], streamingText: 'Look' }),
      source({ conversation: [entry({ type: 'user' }), entry({ type: 'assistant-text' })] }),
      source({ conversation: [entry({ type: 'user' }), entry({ type: 'tool-call-group' })] }),
    ];
    const flips = frames.map((f) => hasServerAcknowledgedLocalDispatch(l, f));
    expect(flips).toEqual([false, true, true, true]);
    // Exactly ONE false→true transition across the run.
    expect(flips.filter((v, i) => v && !(flips[i - 1] ?? false))).toHaveLength(1);
  });
});

describe('the primary slot is a STRICT priority machine', () => {
  it('gives the slot to the pending question over everything below it', () => {
    expect(
      sv3PrimaryAction({ pendingPrompt: true, running: true, followUp: true }).kind,
    ).toBe('answer');
  });

  it('gives it to Stop over follow-up and Send', () => {
    expect(sv3PrimaryAction({ pendingPrompt: false, running: true, followUp: true }).kind).toBe(
      'stop',
    );
  });

  it('gives it to follow-up over Send, and to Send when nothing else claims it', () => {
    expect(sv3PrimaryAction({ pendingPrompt: false, running: false, followUp: true }).kind).toBe(
      'follow-up',
    );
    expect(sv3PrimaryAction({ pendingPrompt: false, running: false, followUp: false }).kind).toBe(
      'send',
    );
  });

  it('carries a reason on every rung, and the routing keys on the ones that route', () => {
    const kinds = [
      sv3PrimaryAction({ pendingPrompt: true, running: false, followUp: false }),
      sv3PrimaryAction({ pendingPrompt: false, running: true, followUp: false }),
      sv3PrimaryAction({ pendingPrompt: false, running: false, followUp: true }),
      sv3PrimaryAction({ pendingPrompt: false, running: false, followUp: false }),
    ];
    for (const slot of kinds) expect(slot.reason.length).toBeGreaterThan(0);
    // The two sending rungs explain BOTH keys; the running rung explains what Enter does instead.
    expect(kinds[2]?.reason).toContain('Ctrl+Enter');
    expect(kinds[3]?.reason).toContain('Ctrl+Enter');
    expect(kinds[1]?.reason).toContain('steers');
  });

  it('owner 2026-08-26: the STOP stands beside the slot whenever a run is live', () => {
    // The slot's priority is right about which control is primary and was wrong about stopping
    // being optional: a supervised run holds on a decision for most of its life, so `pendingPrompt`
    // outranked `running` for most of its life and the reader had no stop control at all.
    const held = sv3PrimaryAction({ pendingPrompt: true, running: true, followUp: true });
    expect(held.kind).toBe('answer');
    expect(held.haltReason).toBe(SV3_STANDING_STOP_REASON);

    // It is keyed on the RUN, not on the prompt: a prompt left over from a run that has ended has
    // nothing to stop.
    expect(
      sv3PrimaryAction({ pendingPrompt: true, running: false, followUp: false }).haltReason,
    ).toBe('');

    // And it never doubles the slot's own Stop — one live run, one stop control.
    for (const slot of [
      sv3PrimaryAction({ pendingPrompt: false, running: true, followUp: false }),
      sv3PrimaryAction({ pendingPrompt: false, running: false, followUp: true }),
      sv3PrimaryAction({ pendingPrompt: false, running: false, followUp: false }),
    ]) {
      expect(slot.haltReason).toBe('');
    }
  });
});

// WP6.2 S8.2 — a direction is labelled by what happened to it, not "Steered" whatever happened.
describe('a direction reads as what happened to it', () => {
  it('labels applied, queued and not-applied directions differently', () => {
    const conversation = [
      entry({ id: 'd1', type: 'steer-directive', content: 'Focus on Q3.', directiveOutcome: 'applied' }),
      entry({ id: 'd2', type: 'steer-directive', content: 'Skip drafts.', directiveOutcome: 'queued' }),
      entry({
        id: 'd3', type: 'steer-directive', content: 'Answer in French.',
        directiveOutcome: 'not_applied', directiveReason: 'run_finished',
      }),
      // An acknowledgement from a build without outcomes was always an applied direction.
      entry({ id: 'd4', type: 'steer-directive', content: 'Legacy.' }),
    ];
    const notes = projectSv3RunFeed(source({ conversation }), 0).items;
    expect(notes.map((n) => (n.kind === 'note' ? [n.label, n.text] : n.kind))).toEqual([
      ['Steered', 'Focus on Q3.'],
      ['Direction queued', "Skip drafts. (applies at the agent's next step)"],
      ['Not applied', 'Answer in French. (the run finished before the agent\'s next step)'],
      ['Steered', 'Legacy.'],
    ]);
  });
});
