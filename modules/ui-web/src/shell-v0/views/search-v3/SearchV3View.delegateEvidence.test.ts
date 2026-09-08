// @vitest-environment happy-dom

/**
 * Tempdoc 859 C-small — a delegate run's EVIDENCE reaches the turn, and only its own run's.
 *
 * The defect these cases pin: the backend resolved a delegate answer's grounding, persisted it, and
 * put it on the wire, and this window never wrote it onto the turn. So a delegated answer rendered
 * with no inline marks and no Sources affordance while the evidence sat one field away on the shared
 * controller. `SearchV3View.delegate()` had no evidence sink at all — `setTurnEvidence` was called
 * from exactly one place, the ASK path's `onEvidence`.
 *
 * Two properties, asserted as mechanisms:
 *
 *  - **T1** — the run's terminal writes the evidence, projected through the shared module, and the
 *    marks reach the renderer that draws the run's ANSWER (N1: until 859 the agent branch had no
 *    citations-bearing renderer at all, so computing marks for it would have delivered nothing).
 *  - **T2** — a run that ends WITHOUT a `done` keeps its predecessor's evidence off the turn. This
 *    is the corrected staleness hazard: `onDone` clears the controller fields unconditionally, so a
 *    done-terminated ungrounded run already forgets, and a test built on THAT would pass vacuously.
 *    Only the no-done terminal (error, abort, watchdog, budget stop) reaches the real hazard.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type {
  AgentSessionController,
  ConversationEntry,
} from '../../controllers/AgentSessionController.js';
import { ReasoningController } from '../../controllers/ReasoningController.js';
import type { BudgetUpdate } from '../../controllers/AgentSessionController.js';
import type {
  AgentSentenceCite,
  AgentSource,
} from '../../../api/generated/shape-handlers/shared.js';
// 859 D live-defect D5 — the two per-disposition sentences, named rather than spelled out, so this
// pin cannot pass against copy that has since drifted.
import {
  SV3_CUT_SHORT_BUDGET_NOTICE,
  SV3_CUT_SHORT_STEPS_NOTICE,
  SV3_CUT_SHORT_STEPS_NO_ANSWER_NOTICE,
  sv3CutShortNotice,
} from './sv3-honesty.js';

const SOURCES: AgentSource[] = [
  {
    parentDocId: 'docs/runbook.md',
    chunkIndex: 7,
    path: 'f:/docs/runbook.md',
    title: 'Runbook',
    excerpt: 'the first passage',
    startLine: 3,
    endLine: 9,
    headingText: 'Setup',
  },
  {
    parentDocId: 'docs/postmortem.md',
    chunkIndex: 1,
    path: 'f:/docs/postmortem.md',
    title: 'Postmortem',
    excerpt: 'the second passage',
    startLine: 40,
    endLine: 52,
    headingText: 'Cause',
  },
];
const CITES: AgentSentenceCite[] = [
  { sentenceText: 'The retry succeeded.', sourceIndex: 1, similarity: 0.88 },
];

interface FakeCtrl {
  conversation: ConversationEntry[];
  toolCalls: Record<string, unknown>;
  streamingText: string;
  isStreaming: boolean;
  runInFlight: boolean;
  runKind: 'agent' | 'workflow' | 'background' | null;
  conversationId: string | null;
  sessionId: string | null;
  /** 859 D live-defect D1 — tri-state: `null` is "no authority has reported a step yet". */
  iterationsUsed: number | null;
  budgetUpdates: BudgetUpdate[];
  reasoning: ReasoningController;
  budgetGate: null;
  contextGate: null;
  runPark: null;
  answerSources: AgentSource[];
  answerCitations: AgentSentenceCite[];
  answerEvidenceRunId: string | null;
  answerCitationScorer: string | null;
  /** Tempdoc 865 §7.1 — the per-call grounding deltas the run stamped, and whose run they are. */
  groundingDeltas: AgentSource[];
  groundingDeltasRunId: string | null;
  /** Tempdoc 859 §D §2.6 — the run’s terminal disposition, as the wire reports it. */
  terminalDisposition: string | null;
  send: ReturnType<typeof vi.fn>;
  cancelSession: ReturnType<typeof vi.fn>;
  reattachActiveRunOnLoad: ReturnType<typeof vi.fn>;
}

function makeCtrl(): FakeCtrl {
  return {
    conversation: [],
    toolCalls: {},
    streamingText: '',
    isStreaming: false,
    runInFlight: false,
    runKind: null,
    conversationId: null,
    sessionId: null,
    iterationsUsed: null,
    budgetUpdates: [],
    // Tempdoc 859 §A — the live run feed derives its open-region item from the real controller.
    reasoning: new ReasoningController(() => {}),
    budgetGate: null,
    contextGate: null,
    runPark: null,
    answerSources: [],
    answerCitations: [],
    answerEvidenceRunId: null,
    answerCitationScorer: null,
    groundingDeltas: [],
    groundingDeltasRunId: null,
    terminalDisposition: null,
    send: vi.fn(async () => {}),
    cancelSession: vi.fn(async () => {}),
    reattachActiveRunOnLoad: vi.fn(async () => {}),
  };
}

let ctrl: FakeCtrl = makeCtrl();
let ctrlExists = false;
let agentListener: (() => void) | null = null;

vi.mock('../../state/agentSessionStore.js', () => ({
  getAgentSessionController: () => {
    ctrlExists = true;
    return ctrl as unknown as AgentSessionController;
  },
  peekAgentSessionController: () => (ctrlExists ? (ctrl as unknown as AgentSessionController) : null),
  subscribeAgentSession: (listener: () => void) => {
    agentListener = listener;
    return () => {
      agentListener = null;
    };
  },
}));

import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { resetSearchState } from '../../state/searchState.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
// The record reader, called DIRECTLY on the same bytes — the round-trip equality is the assertion.
import { projectSv3RecordTurns } from './sv3-record.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

function aiOnline(): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  __resetAiStateForTest();
  ctrl = makeCtrl();
  ctrlExists = false;
  agentListener = null;
  // Every read exit answers EMPTY, so the record can never be the thing that put evidence on the
  // turn: what these cases observe is the LIVE terminal's own write, not a refresh racing it.
  fetchMock = vi.fn(async (url: unknown) => {
    const href = String(url);
    if (href.includes('/api/thread/')) {
      return { ok: true, status: 200, json: async () => ({ conversationId: 'x', events: [] }) };
    }
    if (href.includes('/api/chat/runs/live')) {
      return { ok: true, status: 200, json: async () => ({ runs: [] }) };
    }
    return { ok: true, status: 200, json: async () => ({ sessions: [], results: [] }), body: null };
  });
  vi.stubGlobal('fetch', fetchMock);
  aiOnline();
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
});

async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 8; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

async function mount(): Promise<SearchV3View & Mounted> {
  const el = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  el.setAttribute('api-base', 'http://127.0.0.1:9999');
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

async function region(el: Mounted, tag: string): Promise<Mounted> {
  const found = el.shadowRoot?.querySelector(tag) as Mounted | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

const all = (host: Mounted, testid: string): HTMLElement[] => [
  ...(host.shadowRoot?.querySelectorAll<HTMLElement>(`[data-testid="${testid}"]`) ?? []),
];

const q = (host: Mounted, testid: string): HTMLElement | null =>
  host.shadowRoot?.querySelector(`[data-testid="${testid}"]`) ?? null;

async function frame(el: Mounted, patch: Partial<FakeCtrl>): Promise<void> {
  Object.assign(ctrl, patch);
  agentListener?.();
  await settle(el);
}

/** Delegate a draft the way a reader does — Ctrl+Enter in the composer. */
async function delegate(el: Mounted, draft: string): Promise<void> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  field.value = draft;
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  field.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true, ctrlKey: true }),
  );
  await settle(el);
}

/** The run goes live with two prose steps: an interim note and then the ANSWER. */
async function runWithTwoTexts(el: Mounted, draft: string, runId = 'run-1'): Promise<void> {
  await delegate(el, draft);
  await frame(el, {
    runInFlight: true,
    runKind: 'agent',
    isStreaming: true,
    // The SERVER naming the run is what the window's acknowledgement latch watches for, so a second
    // run in the same window must carry a NEW id — reusing the first one would model a dispatch the
    // server never answered, which is a different case (the last one in this file).
    sessionId: runId,
    conversation: [
      { id: 'u1', type: 'user', content: draft, timestamp: 0 },
      { id: 'a1', type: 'assistant-text', content: 'Reading the runbook first.', timestamp: 1 },
      { id: 'a2', type: 'assistant-text', content: 'The retry succeeded.', timestamp: 2 },
    ],
  });
}

const turnOf = (el: SearchV3View): { evidence: unknown } =>
  el.sessions.sessions[0]!.turns[0]! as unknown as { evidence: unknown };

describe('T1 — the run terminal writes the delegate answer\u2019s evidence onto the turn', () => {
  it('projects sources, matches and marks from the done payload the controller holds', async () => {
    const el = await mount();
    await runWithTwoTexts(el, 'why did it retry?');
    // Before the terminal the turn stands on nothing — which is honest, not empty.
    expect(turnOf(el).evidence).toBeNull();

    // The `done` event landed on the shared controller, then the run terminated.
    await frame(el, {
      answerSources: SOURCES,
      answerCitations: CITES,
      answerEvidenceRunId: 'run-1',
      answerCitationScorer: 'CROSS_ENCODER',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });

    const evidence = el.sessions.sessions[0]!.turns[0]!.evidence!;
    expect(evidence.sources).toHaveLength(2);
    expect(evidence.sources.map((s) => s.parentDocId)).toEqual([
      'docs/runbook.md',
      'docs/postmortem.md',
    ]);
    // §3a — the matches are projected, so the panel can say what the matcher said.
    expect(evidence.matches).toHaveLength(1);
    expect(evidence.matches[0]!.sourceIndex).toBe(1);
    expect(evidence.marks).toHaveLength(1);
    // §5b — no zero-fill survived the round trip into the turn.
    expect(evidence.sources[0]!.startChar).toBeUndefined();
  });

  it('T10 — the marks render on the run\u2019s TERMINAL text item, and on no earlier one', async () => {
    // Asserted on the SETTLED turn, because that is where a run's prose lives after its terminal:
    // the live feed is attention and ends with the run, and the settled turn re-renders from the
    // record's interleaved items. `recordedActivity` and `runBody` hand their items to ONE
    // `runItem` through ONE `terminalTextItemId`, so this covers the rule both draw through.
    const conversationId = 'uc-t10';
    fetchMock.mockImplementation(async (url: unknown) => {
      const href = String(url);
      if (href.includes('/api/thread/')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            conversationId,
            events: [
              // A tool call, so the record's turn derives as an AGENT run rather than an ask.
              // `sv3-record` reads the kind from what HAPPENED, and an ask turn deliberately keeps
              // no activity list (852 §2.3a) — without this the case would be asserting the ask
              // branch's single markdown block, a different renderer entirely.
              {
                id: 'c1',
                occurredAt: '2026-08-13T10:00:00.500Z',
                kind: 'TOOL_ACTIVITY',
                originator: 'agent',
                content: 'core_search',
                attributes: { callId: 'c1', toolName: 'core_search' },
              },
              {
                id: 'a1',
                occurredAt: '2026-08-13T10:00:01.000Z',
                kind: 'ASSISTANT_MESSAGE',
                originator: 'agent',
                content: 'Reading the runbook first.',
                attributes: {},
              },
              {
                id: 'a2',
                occurredAt: '2026-08-13T10:00:02.000Z',
                kind: 'ASSISTANT_MESSAGE',
                originator: 'agent',
                content: 'The retry succeeded.',
                attributes: { sources: SOURCES, citations: CITES, citationScorer: 'CROSS_ENCODER' },
              },
            ],
          }),
        };
      }
      if (href.includes('/api/chat/runs/live')) {
        return { ok: true, status: 200, json: async () => ({ runs: [] }) };
      }
      return { ok: true, status: 200, json: async () => ({ sessions: [], results: [] }), body: null };
    });

    const el = await mount();
    await runWithTwoTexts(el, 'why did it retry?');
    await frame(el, {
      answerSources: SOURCES,
      answerCitations: CITES,
      answerEvidenceRunId: 'run-1',
      answerCitationScorer: 'CROSS_ENCODER',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });

    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    const texts = all(main, 'sv3-run-text');
    expect(texts).toHaveLength(2);
    const blockOf = (host: HTMLElement): { citations?: unknown[] } =>
      host.querySelector('jf-markdown-block') as unknown as { citations?: unknown[] };
    // THE N1 probe: the agent branch now HAS a citations-bearing renderer, and exactly one item in
    // the run wears the marks. Before 859 every one of these carried `.text` and nothing else, so a
    // computed mark set had no consumer at all — the panel would have lit up and the marks, which
    // are the reported defect, would have stayed invisible.
    const marked = texts.filter((t) => (blockOf(t).citations ?? []).length > 0);
    expect(marked).toHaveLength(1);
    expect(marked[0]).toBe(texts[1]);
    // The earlier step's prose gets NONE: an interim item must not capture the answer's grounding.
    expect(blockOf(texts[0]!).citations ?? []).toEqual([]);
  });
});

/**
 * Tempdoc 865 §7.1 / §7.9 A9 — PLANE AUTHORITY on the LIVE path.
 *
 * T2 below pins that a run which ends without a `done` must not wear a PREVIOUS run's evidence. This
 * is the other half of the same question, and 865 changes the answer: a run that ends without a
 * `done` may now stand on ITS OWN evidence, because every tool call stamped what it established
 * before the terminal that could not carry it ever ran. The run-identity guard is what keeps the two
 * apart — deltas are written only when the stamp names the terminating run.
 */
describe('865 §7.1 — a run with no grounded terminal stands on its own deltas', () => {
  it('RED BEFORE / GREEN AFTER: an errored run keeps what its searches established', async () => {
    const el = await mount();
    await runWithTwoTexts(el, 'why did it retry?');
    // The run errors out: no `done`, so nothing writes answerSources/answerEvidenceRunId. Before
    // 865 the turn settled on `null` and everything the run read was gone.
    await frame(el, {
      groundingDeltas: SOURCES,
      groundingDeltasRunId: 'run-1',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });

    const evidence = el.sessions.sessions[0]!.turns[0]!.evidence!;
    expect(evidence).not.toBeNull();
    expect(evidence.sources.map((s) => s.parentDocId)).toEqual([
      'docs/runbook.md',
      'docs/postmortem.md',
    ]);
    // No answer existed, so no matcher ran: the sources stand, and nothing claims a verdict on them.
    expect(evidence.matches).toEqual([]);
    expect(evidence.marks).toEqual([]);
    expect(evidence.groundingIncomplete).toBe(true);
  });

  it('F-1: a done that OWNS the turn but claims no grounding — the deltas still win', async () => {
    // The gate this fixes. An answerless terminal still emits a `done`: its payload always carries a
    // `sources` key (`AgentEventPayloads` puts it unconditionally) and `onDone` writes
    // `answerEvidenceRunId` unconditionally — so the OWNERSHIP predicate is true for the exact
    // terminals that report nothing. Gating the evidence write on ownership alone made the empty
    // terminal win and the delta arm unreachable: the run's evidence was minted, journaled,
    // delivered — and then dropped at the last read site.
    //
    // Tempdoc 878 §D.1 — the fixture keeps `MAX_ITERATIONS` as its label, but that disposition is no
    // longer a PRODUCER of this shape: the step ceiling now finalizes through the grounded seam and
    // carries its sources. The shape itself is still reachable (a cancelled or errored run, or a
    // ceiling whose finalize established nothing), and it is the SHAPE this gate is about — an owned
    // terminal with an empty claim — not the disposition on it.
    const el = await mount();
    await runWithTwoTexts(el, 'why did it retry?');
    await frame(el, {
      // An owned terminal with an empty grounding claim.
      answerSources: [],
      answerCitations: [],
      answerEvidenceRunId: 'run-1',
      answerCitationScorer: 'NONE',
      terminalDisposition: 'MAX_ITERATIONS',
      groundingDeltas: SOURCES,
      groundingDeltasRunId: 'run-1',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });

    const turn = el.sessions.sessions[0]!.turns[0]!;
    const evidence = turn.evidence!;
    expect(evidence.sources.map((s) => s.parentDocId)).toEqual([
      'docs/runbook.md',
      'docs/postmortem.md',
    ]);
    // The two facts that ride WITH the sources must be the deltas' own, not the empty terminal's —
    // a mismatched pair is what turns a reconcile into a false verdict.
    expect(evidence.groundingIncomplete).toBe(true);
    expect(evidence.sourceCoverage).toEqual([]);
    // OWNERSHIP is untouched: the cut-short badge still comes from the same terminal, which is why
    // the fix adds a second predicate instead of narrowing the existing one.
    expect(turn.disposition).toBe('MAX_ITERATIONS');
  });

  it('the TERMINAL wins when it arrived — the deltas are the same set, never added to it', async () => {
    const el = await mount();
    await runWithTwoTexts(el, 'why did it retry?');
    await frame(el, {
      // Both planes describe the same run: the terminal spoke AND the deltas are held.
      answerSources: SOURCES,
      answerCitations: CITES,
      answerEvidenceRunId: 'run-1',
      answerCitationScorer: 'CROSS_ENCODER',
      groundingDeltas: SOURCES,
      groundingDeltasRunId: 'run-1',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });

    const evidence = el.sessions.sessions[0]!.turns[0]!.evidence!;
    // TWO, not four. And the terminal's verdict survives, which the delta arm cannot produce.
    expect(evidence.sources).toHaveLength(2);
    expect(evidence.matches).toHaveLength(1);
    expect(evidence.groundingIncomplete).toBe(false);
  });

  it('a delta stamped by a DIFFERENT run never lands on this one', async () => {
    const el = await mount();
    await runWithTwoTexts(el, 'why did it retry?');
    await frame(el, {
      groundingDeltas: SOURCES,
      // The stamp names a run that is not the one terminating — the same identity hazard T2 pins
      // for the terminal fields, and it must be answered the same way.
      groundingDeltasRunId: 'run-0',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });
    expect(el.sessions.sessions[0]!.turns[0]!.evidence).toBeNull();
  });
});

describe('T2 — a run that ends WITHOUT a done carries no evidence at all', () => {
  it('leaves the failed turn standing on nothing, not on the previous run\u2019s sources', async () => {
    const el = await mount();
    // Run 1: grounded, terminated normally. Its evidence is on the controller and on ITS turn.
    await runWithTwoTexts(el, 'why did it retry?');
    await frame(el, {
      answerSources: SOURCES,
      answerCitations: CITES,
      answerEvidenceRunId: 'run-1',
      answerCitationScorer: 'CROSS_ENCODER',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });
    expect(el.sessions.sessions[0]!.turns[0]!.evidence?.sources).toHaveLength(2);

    // Run 2 in the same window: it ERRORS OUT. No `done` fires, so nothing clears the controller's
    // evidence — `onDone` is the only writer and `resetRunState` is reached only on replay exit. The
    // stamp still names RUN 1, and that mismatch is the whole guard.
    await runWithTwoTexts(el, 'and the ledger?', 'run-2');
    await frame(el, {
      runInFlight: false,
      isStreaming: false,
      runKind: null,
      // answerSources / answerCitations / answerEvidenceRunId DELIBERATELY untouched — this is the
      // hazard's precondition, not an oversight in the fixture. The stamp still names run 1.
    });

    const second = el.sessions.sessions[0]!.turns[1]!;
    expect(second.status).not.toBe('streaming');
    // Run 1's 2 sources must not be wearing run 2's failed turn.
    expect(second.evidence).toBeNull();
    // ...and run 1 keeps its own, so the guard withheld rather than wiped.
    expect(el.sessions.sessions[0]!.turns[0]!.evidence?.sources).toHaveLength(2);
  });

  it('writes the evidence when the stamp DOES name the terminating run (the guard is not a block)', async () => {
    // The discriminator for the case above: if the guard simply never wrote, that case would pass
    // for the wrong reason. Same second run, same shape, with the stamp updated as `onDone` would.
    const el = await mount();
    await runWithTwoTexts(el, 'why did it retry?');
    await frame(el, {
      answerSources: SOURCES,
      answerCitations: CITES,
      answerEvidenceRunId: 'run-1',
      answerCitationScorer: 'CROSS_ENCODER',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });
    await runWithTwoTexts(el, 'and the ledger?', 'run-2');
    await frame(el, {
      answerSources: SOURCES,
      answerCitations: CITES,
      // `onDone` fired for run 2 this time, so the stamp names IT.
      answerEvidenceRunId: 'run-2',
      answerCitationScorer: 'CROSS_ENCODER',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });
    expect(el.sessions.sessions[0]!.turns[1]!.evidence?.sources).toHaveLength(2);
  });

  it('a dispatch the server never NAMED gets no evidence, though the stale id would match', async () => {
    // The hole the id test alone leaves: run 2's dispatch is never acknowledged, so
    // `ctrl.sessionId` still holds RUN 1's id — and `answerEvidenceRunId === sessionId` is
    // therefore true about a run that is over. Only the window's own acknowledgement latch can
    // tell "this evidence belongs to the run I am concluding" from "nothing has moved since".
    const el = await mount();
    await runWithTwoTexts(el, 'why did it retry?');
    await frame(el, {
      answerSources: SOURCES,
      answerCitations: CITES,
      answerEvidenceRunId: 'run-1',
      answerCitationScorer: 'CROSS_ENCODER',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });

    // Run 2: delegated, then it goes live and ends WITHOUT the controller ever naming a new run.
    // `sessionId` is deliberately left at 'run-1' — that IS the scenario, not a lazy fixture.
    await delegate(el, 'and the ledger?');
    await frame(el, { runInFlight: true, runKind: 'agent', isStreaming: true });
    await frame(el, { runInFlight: false, isStreaming: false, runKind: null });

    const second = el.sessions.sessions[0]!.turns[1]!;
    expect(second.evidence).toBeNull();
  });
});

describe('T7 — live and record produce the SAME evidence from the same bytes', () => {
  /** The bytes `AgentInteractionMapper` persists — one ASSISTANT_MESSAGE, action plane. */
  const recordEvents = [
    {
      id: 'a1',
      occurredAt: '2026-08-13T10:00:01.000Z',
      kind: 'ASSISTANT_MESSAGE' as const,
      originator: 'agent',
      content: 'The retry succeeded.',
      attributes: { sources: SOURCES, citations: CITES, citationScorer: 'CROSS_ENCODER' },
    },
  ];

  it('the record projection of the run’s own bytes equals what the live terminal wrote', async () => {
    const el = await mount();
    await runWithTwoTexts(el, 'why did it retry?');
    await frame(el, {
      answerSources: SOURCES,
      answerCitations: CITES,
      answerEvidenceRunId: 'run-1',
      answerCitationScorer: 'CROSS_ENCODER',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });
    const live = el.sessions.sessions[0]!.turns[0]!.evidence!;

    // The SAME bytes, through the RECORD reader. One projection module, so this is an equality the
    // two paths cannot drift out of — which is 859 §2.2's spine, asserted rather than asserted-about.
    const recorded = projectSv3RecordTurns(recordEvents)[0]!.evidence!;
    expect(recorded.sources).toEqual(live.sources);
    expect(recorded.matches).toEqual(live.matches);
    expect(recorded.marks).toEqual(live.marks);
  });

  it('the terminal’s own record refresh does not blank the evidence it just wrote', async () => {
    const conversationId = 'uc-t7';
    fetchMock.mockImplementation(async (url: unknown) => {
      const href = String(url);
      if (href.includes('/api/thread/')) {
        return { ok: true, status: 200, json: async () => ({ conversationId, events: recordEvents }) };
      }
      if (href.includes('/api/chat/runs/live')) {
        return { ok: true, status: 200, json: async () => ({ runs: [] }) };
      }
      return { ok: true, status: 200, json: async () => ({ sessions: [], results: [] }), body: null };
    });
    const el = await mount();
    await runWithTwoTexts(el, 'why did it retry?');
    // `concludeRun` refreshes the record at the terminal (inventory D1), so this exercises the real
    // order: the live write, then `applySv3Record` + `reconcileEvidence` over the same run.
    await frame(el, {
      answerSources: SOURCES,
      answerCitations: CITES,
      answerEvidenceRunId: 'run-1',
      answerCitationScorer: 'CROSS_ENCODER',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });
    await settle(el);
    const after = el.sessions.sessions[0]!.turns[0]!.evidence!;
    expect(after.sources).toHaveLength(2);
    expect(after.matches).toHaveLength(1);
    expect(after.marks).toHaveLength(1);
  });
});

/* ── Tempdoc 859 §D §2.6 / §3.3 T11 — the cut-short disclosure ─────────────────────── */

describe('T11 — a truncated run says so, whatever its answer says', () => {
  /** The answer a cut-short run actually produced in the live audit: confident, and silent about it. */
  const CONFIDENT = 'The retry succeeded.';

  it('renders the cut-short line and badge from the DISPOSITION, not from the answer text', async () => {
    const el = await mount();
    await runWithTwoTexts(el, 'why did it retry?');
    await frame(el, {
      terminalDisposition: 'BUDGET_EDGE_FINALIZE',
      answerEvidenceRunId: 'run-1',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });
    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    expect(q(main, 'sv3-turn-cut-short'), 'the settled turn discloses the truncation').not.toBeNull();
    // THE fail-closed property: the model's own text says nothing about being cut short, and the
    // disclosure fires anyway. 859 §7 watched exactly this answer shape hide a truncated run.
    // The answer this run produced, read from the controller that holds it. It is confident and it
    // says nothing about being cut short — and the disclosure above fired regardless. That is the
    // whole property: a model cannot suppress it by writing well.
    const prose = ctrl.conversation
      .filter((entry) => entry.type === 'assistant-text')
      .map((entry) => entry.content)
      .join(' ');
    expect(prose, 'the run really did produce a confident-sounding answer').toContain(CONFIDENT);
    expect(prose.toLowerCase(), 'and it said nothing at all about being cut short').not.toContain(
      'cut short',
    );
    // 859 D live-defect D5 — and it names the limit that ACTUALLY fired. Asserted against the
    // exported constant, not a literal, so the copy and the pin cannot drift apart.
    expect(q(main, 'sv3-turn-cut-short')?.textContent?.trim()).toBe(SV3_CUT_SHORT_BUDGET_NOTICE);
    expect(SV3_CUT_SHORT_BUDGET_NOTICE.toLowerCase()).toContain('budget');
    // And the receipt tail carries the compact badge beside the outcome.
    expect(q(main, 'sv3-run-receipt')?.getAttribute('data-cut-short')).toBe('true');
    expect(q(main, 'sv3-run-receipt')?.textContent).toContain('cut short');

    // Tempdoc 859 (live audit 2026-08-25, D1) — WHERE the notice lands, because its style rule's own
    // comment used to claim the wrong surface: that it "sits on the same 8% --success wash" as
    // `.run-prompt-fine`, and so failed contrast the same way. It does not. It renders inside the
    // transcript turn, on `--background`; `.run-prompt` — the held-decision box that owns the wash —
    // is a sibling of the feed and never contains it. The comment was corrected against this, so the
    // measurement is pinned here rather than left as prose a later reader would reason from.
    const cutShort = q(main, 'sv3-turn-cut-short') as HTMLElement;
    expect(cutShort.closest('.turn'), 'the notice left the transcript turn').not.toBeNull();
    expect(cutShort.closest('.run-prompt'), 'the notice is NOT on the --success wash').toBeNull();
  });

  it('discloses the ITERATION ceiling too — and blames the STEP limit, not the budget', async () => {
    // Closing both truncating dispositions is the point; leaving one unstamped would be the same
    // hole under a different name. (Tempdoc 878 §D.1: the ceiling used to produce no answer text at
    // all — it made no LLM call — so the disposition was the ONLY thing that could disclose the
    // truncation. It now attempts a synthesis, which is why this case and the answerless one below
    // are two tests with two sentences rather than one.)
    //
    // 859 D live-defect D5: one shared sentence used to serve BOTH terminals, so this run — which
    // the live audit watched end with 59% of its budget UNSPENT — told the reader that tokens had
    // stopped it. The two limits have different remedies (more budget fixes nothing here), so a
    // wrong attribution is not a wording nit; it points the reader at the wrong lever.
    const el = await mount();
    await runWithTwoTexts(el, 'why did it retry?');
    await frame(el, {
      terminalDisposition: 'MAX_ITERATIONS',
      answerEvidenceRunId: 'run-1',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });
    await settle(el);
    const notice = q(await region(el, 'jf-sv3-main'), 'sv3-turn-cut-short');
    expect(notice).not.toBeNull();
    // Tempdoc 878 §I.5 finding 4 — the WITH-ANSWER arm, and the fixture is why: this run streamed
    // two text items before terminating, so the reader watched an answer arrive. It used to assert
    // the answerless arm, because the notice read the PHASE-FILTERED run (null once the run ends)
    // and so could not see the feed that still holds that text — printing "before reaching an
    // answer" over an answer already on screen, permanently if the record fetch never lands. The
    // answerless arm is exercised at the function level below.
    expect(notice?.textContent?.trim()).toBe(SV3_CUT_SHORT_STEPS_NOTICE);
    expect(SV3_CUT_SHORT_STEPS_NOTICE.toLowerCase()).toContain('step');
    expect(
      SV3_CUT_SHORT_STEPS_NOTICE.toLowerCase(),
      'a step-ceiling run must not be told the budget stopped it',
    ).not.toContain('budget');
    // The two sentences are genuinely different — a split that produced one string twice would
    // satisfy every assertion above and fix nothing.
    expect(SV3_CUT_SHORT_STEPS_NOTICE).not.toBe(SV3_CUT_SHORT_BUDGET_NOTICE);
  });

  it('878 §D.1: the step ceiling has TWO honest sentences, and the turn picks by whether an answer landed', () => {
    // The backend now makes a no-tools synthesis call when the loop hits its step ceiling, so a
    // MAX_ITERATIONS turn may or may not carry an answer. One sentence cannot be true in both
    // cases: "this answer is based on what the run had gathered" is false above an empty turn, and
    // "the run used all its steps before reaching an answer" is false above a paragraph of answer.
    // Both outcomes are real — a compact model can still decline the finalize (859 §7) — so the
    // disposition alone no longer determines the copy.
    expect(sv3CutShortNotice('MAX_ITERATIONS', true)).toBe(SV3_CUT_SHORT_STEPS_NOTICE);
    expect(sv3CutShortNotice('MAX_ITERATIONS', false)).toBe(SV3_CUT_SHORT_STEPS_NO_ANSWER_NOTICE);
    expect(SV3_CUT_SHORT_STEPS_NO_ANSWER_NOTICE).not.toBe(SV3_CUT_SHORT_STEPS_NOTICE);
    expect(
      SV3_CUT_SHORT_STEPS_NO_ANSWER_NOTICE.toLowerCase(),
      'the answerless arm still names the STEP limit, not the budget',
    ).not.toContain('budget');

    // The budget wall reaches its notice only by producing text, so its sentence is unchanged in
    // both directions — the second argument must not silently rewrite the OTHER terminal's copy.
    expect(sv3CutShortNotice('BUDGET_EDGE_FINALIZE', false)).toBe(SV3_CUT_SHORT_BUDGET_NOTICE);
    expect(sv3CutShortNotice('COMPLETED', false)).toBeNull();
  });

  it('says NOTHING for a run that completed, or for one that never stated a disposition', async () => {
    // An unknown disposition discloses nothing rather than claiming success — and a COMPLETED run
    // must not wear a badge, or the badge stops meaning anything.
    for (const disposition of ['COMPLETED', null]) {
      const el = await mount();
      await runWithTwoTexts(el, 'why did it retry?');
      await frame(el, {
        terminalDisposition: disposition,
        answerEvidenceRunId: 'run-1',
        runInFlight: false,
        isStreaming: false,
        runKind: null,
      });
      await settle(el);
      const main = await region(el, 'jf-sv3-main');
      expect(q(main, 'sv3-turn-cut-short'), String(disposition)).toBeNull();
      expect(q(main, 'sv3-run-receipt')?.getAttribute('data-cut-short')).toBe('false');
      for (const child of [...document.body.children]) child.remove();
    }
  });

  it('SURVIVES A RELOAD — the record carries it, so the disclosure does not expire', async () => {
    // An honesty fact that shows live and vanishes after a reload is worse than one never made: the
    // reader has already learned to trust it, so its absence then reads as 'this one was fine'.
    const conversationId = 'uc-cutshort';
    fetchMock.mockImplementation(async (url: unknown) => {
      const href = String(url);
      if (href.includes('/api/thread/')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            conversationId,
            events: [
              {
                id: 'c1',
                occurredAt: '2026-08-13T10:00:00.500Z',
                kind: 'TOOL_ACTIVITY',
                originator: 'agent',
                content: 'core_search',
                attributes: { callId: 'c1', toolName: 'core_search' },
              },
              {
                id: 'a2',
                occurredAt: '2026-08-13T10:00:02.000Z',
                kind: 'ASSISTANT_MESSAGE',
                originator: 'agent',
                content: CONFIDENT,
                // Persisted by AgentInteractionMapper beside the answer it qualifies.
                attributes: { disposition: 'BUDGET_EDGE_FINALIZE' },
              },
            ],
          }),
        };
      }
      if (href.includes('/api/chat/runs/live')) {
        return { ok: true, status: 200, json: async () => ({ runs: [] }) };
      }
      return { ok: true, status: 200, json: async () => ({ sessions: [], results: [] }), body: null };
    });

    const el = await mount();
    await runWithTwoTexts(el, 'why did it retry?');
    // NO live disposition on the controller — this run is being read from the record, exactly as a
    // reloaded tab reads it. The badge must come from the record leg and from nothing else.
    await frame(el, {
      answerEvidenceRunId: 'run-1',
      runInFlight: false,
      isStreaming: false,
      runKind: null,
    });
    await settle(el);

    expect(el.sessions.sessions[0]!.turns[0]!.disposition).toBe('BUDGET_EDGE_FINALIZE');
    expect(q(await region(el, 'jf-sv3-main'), 'sv3-turn-cut-short')).not.toBeNull();
  });
});
