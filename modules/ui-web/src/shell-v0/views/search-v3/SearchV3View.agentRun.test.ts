// @vitest-environment happy-dom

/**
 * The delegate tier of the Search v3 window (tempdoc 822 Phase F2).
 *
 * The shared agent controller is mocked at the STORE boundary (`agentSessionStore`), the way
 * the retired search-v2 window's run cases did — but `runControlIntent` is deliberately NOT mocked,
 * so every case here
 * exercises the REAL `dispatchRunControl` seam and its lifecycle predicates against a fake run. A
 * hand-rolled directive would therefore not merely be un-asserted; it would take a different code
 * path than the one the steering register governs.
 *
 * The properties asserted as MECHANISMS rather than appearances:
 *  - **Routing.** Enter reaches the ask fetch; Ctrl+Enter reaches the controller and NOT the fetch.
 *    Each case asserts the other channel is untouched, so a send that fired both would fail.
 *  - **The slot holds exactly one control.** Every rung asserts the other three are absent.
 *  - **A typed prompt is resolved by its own command.** The chat-text path is asserted to dispatch
 *    NOTHING while a prompt is held — that is the probe, not the presence of a button.
 *  - **The receipt derives from the feed.** The rendered card count is read from the DOM and compared
 *    with the receipt's number, so a feed that rendered fewer than it counted fails.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type {
  AgentSessionController,
  ConversationEntry,
  ToolCall,
} from '../../controllers/AgentSessionController.js';
import { AgentSessionController as RealAgentSessionController } from '../../controllers/AgentSessionController.js';
import { ReasoningController } from '../../controllers/ReasoningController.js';
import type { BudgetUpdate } from '../../controllers/AgentSessionController.js';
import { SV3_STANDING_STOP_REASON } from './sv3-run.js';

/** The observable surface the window projects, plus the spies the seam should reach. */
interface FakeCtrl {
  conversation: ConversationEntry[];
  toolCalls: Record<string, ToolCall>;
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
  budgetGate: { tokensNeeded: number; tokensRemaining: number; totalTokensConsumed: number } | null;
  contextGate: { promptTokens: number; contextWindow: number } | null;
  /** Tempdoc 834 §6.2 — why the run is stopped, when it is; the window reads it as `holding`. */
  runPark: { kind: string; sinceEpochMs: number; detail: string } | null;
  /** Tempdoc 859 §D §2.6 — the run's terminal disposition, written only by `onDone`. */
  terminalDisposition: string | null;
  /** …and the run it belongs to, which is how the window refuses a previous run's terminal. */
  answerEvidenceRunId: string | null;
  /** Tempdoc 859 §3b — the terminal's grounding claim; empty here, so no evidence is written. */
  answerSources: unknown[];
  groundingDeltas: unknown[];
  groundingDeltasRunId: string | null;
  send: ReturnType<typeof vi.fn>;
  steer: ReturnType<typeof vi.fn>;
  cancelSession: ReturnType<typeof vi.fn>;
  resolveBudgetGate: ReturnType<typeof vi.fn>;
  resolveContextGate: ReturnType<typeof vi.fn>;
  raiseBudget: ReturnType<typeof vi.fn>;
  resumeSession: ReturnType<typeof vi.fn>;
  /** Tempdoc 834 §15.3 — the cold-load reattach the window asks the shared controller for. */
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
    terminalDisposition: null,
    answerEvidenceRunId: null,
    answerSources: [],
    groundingDeltas: [],
    groundingDeltasRunId: null,
    send: vi.fn(async () => {}),
    steer: vi.fn(async () => true),
    cancelSession: vi.fn(async () => {}),
    resolveBudgetGate: vi.fn(async () => true),
    resolveContextGate: vi.fn(async () => true),
    raiseBudget: vi.fn(async () => true),
    resumeSession: vi.fn(async () => {}),
    reattachActiveRunOnLoad: vi.fn(async () => {}),
  };
}

let ctrl: FakeCtrl = makeCtrl();
let ctrlExists = false;
let agentListener: (() => void) | null = null;
const getCtrl = vi.fn(() => {
  ctrlExists = true;
  return ctrl as unknown as AgentSessionController;
});

vi.mock('../../state/agentSessionStore.js', () => ({
  getAgentSessionController: () => getCtrl(),
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
import { RUN_DISPATCHING } from './fixtures.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

/** The observed state in which BOTH tiers are genuinely available — the window's own precondition. */
function aiOnline(): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

/**
 * The calls to this window ASK exit. Since Phase F6 the window also READS on mount and on a claim
 * (the app-wide conversation list, the canonical thread record), so a bare call count would answer
 * a different question than "did this send reach the ask tier?".
 */
const dispatches = (): unknown[][] =>
  fetchMock.mock.calls.filter((call) => String(call[0]).includes('/api/chat/dispatch'));

beforeEach(() => {
  // Phase F6 wired this window to APP-WIDE, process-lifetime authorities (the conversation store,
  // the per-tab reload pointer, the shared draft controller). Each is a module singleton or a
  // storage key, so a case that did not reset them would be reading the previous case's state.
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  ctrl = makeCtrl();
  ctrlExists = false;
  getCtrl.mockClear();
  fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, body: null });
  vi.stubGlobal('fetch', fetchMock);
  __resetAiStateForTest();
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
});

async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 6; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

async function mount(): Promise<SearchV3View & Mounted> {
  const el = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  el.setAttribute('api-base', 'http://127.0.0.1:9999');
  document.body.appendChild(el);
  // WP1.1 — a fresh window starts in Search mode; these cases are about Ask, so they choose it.
  (el as unknown as { tier: string }).tier = 'ask';
  await el.updateComplete;
  return el;
}

async function region(el: Mounted, tag: string): Promise<Mounted> {
  const found = el.shadowRoot?.querySelector(tag) as Mounted | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

const q = (host: Mounted, testid: string): HTMLElement | null =>
  host.shadowRoot?.querySelector(`[data-testid="${testid}"]`) ?? null;

const all = (host: Mounted, testid: string): HTMLElement[] => [
  ...(host.shadowRoot?.querySelectorAll<HTMLElement>(`[data-testid="${testid}"]`) ?? []),
];

/**
 * Press a control the way a reader does — through the native button inside it. A control born on the
 * shared operability primitive (852 S4's jf-control adoption, which the run-prompt decisions moved
 * to) renders its button in its OWN shadow root, so a click on the host element reaches no handler;
 * a hand-rolled native button is clicked directly. Same helper, same reason, as the branch suite's.
 */
async function pressControl(host: Element | null | undefined): Promise<void> {
  if (!host) throw new Error('pressControl: no control');
  if (host.localName === 'button') {
    (host as HTMLButtonElement).click();
    return;
  }
  await (host as Mounted).updateComplete;
  const button = host.shadowRoot?.querySelector('button');
  if (!button) throw new Error('pressControl: the control rendered no button');
  button.click();
}

async function type(el: Mounted, draft: string): Promise<HTMLTextAreaElement> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  field.value = draft;
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  return field;
}

/** Press Enter, optionally with the delegate modifier — the keys a reader actually has. */
async function press(el: Mounted, opts: { ctrlKey?: boolean; shiftKey?: boolean } = {}): Promise<void> {
  const field = (await region(el, 'jf-sv3-composer')).shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  field?.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true, ...opts }),
  );
  await settle(el);
}

/** The controller moved: push the frame, then notify exactly as the shared store would. */
async function frame(el: Mounted, patch: Partial<FakeCtrl>): Promise<void> {
  Object.assign(ctrl, patch);
  agentListener?.();
  await settle(el);
}

const toolCall = (callId: string, over: Partial<ToolCall> = {}): ToolCall => ({
  callId,
  toolName: 'core_search',
  arguments: '{}',
  risk: 'LOW',
  status: 'completed',
  ...over,
});

const groupEntry = (id: string, callIds: string[]): ConversationEntry => ({
  id,
  type: 'tool-call-group',
  content: '',
  callIds,
  timestamp: 0,
});

/** Delegate a draft and drive the run to LIVE with the given tool calls in its feed. */
async function delegateWithCalls(el: Mounted, draft: string, callIds: string[]): Promise<void> {
  await type(el, draft);
  await press(el, { ctrlKey: true });
  await frame(el, {
    runInFlight: true,
    runKind: 'agent',
    sessionId: 'run-1',
    conversation: [
      { id: 'u1', type: 'user', content: draft, timestamp: 0 },
      groupEntry('g1', callIds),
    ],
    toolCalls: Object.fromEntries(callIds.map((id) => [id, toolCall(id)])),
  });
}

describe('the composer routes by KEY, and each key reaches exactly one tier', () => {
  it('sends plain Enter to the ask tier and nowhere near the run controller', async () => {
    aiOnline();
    const el = await mount();
    await type(el, 'why did the renewal fail?');
    await press(el);

    expect(dispatches()).toHaveLength(1);
    expect(String(dispatches()[0]?.[0])).toContain('/api/chat/dispatch');
    expect(getCtrl).not.toHaveBeenCalled();
    expect(ctrl.send).not.toHaveBeenCalled();
  });

  it('delegates on Ctrl+Enter through the seam, with no ask dispatch at all', async () => {
    aiOnline();
    const el = await mount();
    await type(el, 'clean up the vendor folder');
    await press(el, { ctrlKey: true });

    expect(ctrl.send).toHaveBeenCalledTimes(1);
    // Tempdoc 859 §D §2.1 / §3.3 T14 — the EFFORT rung travels with the dispatch, from the CHORD
    // path specifically. Ctrl+Enter delegates regardless of the tier control (852 kept the
    // accelerator), so this is the path where a rung dropped at the boundary would be least
    // noticeable — and a dropped rung fails silently, because the backend defaults it to Standard.
    expect(ctrl.send).toHaveBeenCalledWith('clean up the vendor folder', 'standard');
    // The delegated turn is stamped with THIS window's conversation, not with whatever ran last —
    // and the conversation is the app-wide store's, not a window-local counter (Phase F6).
    expect(ctrl.conversationId).toBe(el.sessions.sessions[0]?.id);
    expect(String(ctrl.conversationId).startsWith('uc-')).toBe(true);
    expect(dispatches()).toHaveLength(0);
    // ...and it opened an AGENT turn in the transcript, in the same session a plain ask would use.
    const main = await region(el, 'jf-sv3-main');
    const turns = all(main, 'sv3-turn');
    expect(turns).toHaveLength(1);
    expect(turns[0]?.dataset.kind).toBe('agent');
  });

  it('leaves Shift+Enter to the field on BOTH tiers', async () => {
    aiOnline();
    const el = await mount();
    await type(el, 'a line');
    await press(el, { shiftKey: true });
    await press(el, { shiftKey: true, ctrlKey: true });
    expect(dispatches()).toHaveLength(0);
    expect(ctrl.send).not.toHaveBeenCalled();
  });

  it('says which key does what in the send slot itself, and adds no chrome to do it', async () => {
    aiOnline();
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    // An EMPTY draft routes nowhere, so the control explains nothing and carries no tooltip: a
    // `title` on a disabled element is suppressed by the browser, and an unreachable reason is worse
    // than none (596 face 1.1).
    expect(q(composer, 'sv3-composer-send')?.hasAttribute('title')).toBe(false);

    await type(el, 'something to route');
    const send = q(composer, 'sv3-composer-send');
    expect((send as HTMLButtonElement).disabled).toBe(false);
    expect(send?.getAttribute('aria-label')).toContain('Ctrl+Enter');
    expect(send?.getAttribute('title')).toBe(send?.getAttribute('aria-label'));
    // The routing lives ON the control. No banner, no mode switch, no second field.
    expect(q(composer, 'sv3-composer-notice')).toBeNull();
    expect(
      composer.shadowRoot?.querySelectorAll('textarea, input').length,
    ).toBe(1);
  });
});

describe('the primary slot is a strict-priority state machine in the DOM', () => {
  const slotIds = [
    'sv3-composer-answer',
    'sv3-composer-stop',
    'sv3-composer-send',
  ] as const;

  const occupants = (composer: Mounted): string[] =>
    slotIds.filter((id) => q(composer, id) !== null);

  it('holds Send alone when nothing is running', async () => {
    aiOnline();
    const el = await mount();
    expect(occupants(await region(el, 'jf-sv3-composer'))).toEqual(['sv3-composer-send']);
  });

  it('holds Stop alone while the run is live — Send is NOT rendered', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    const composer = await region(el, 'jf-sv3-composer');
    expect(occupants(composer)).toEqual(['sv3-composer-stop']);
    expect(composer.shadowRoot?.querySelector('button.send')).toBeNull();
  });

  it('holds Answer in the SLOT while a typed prompt is held — over the LIVE run below it', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    await frame(el, { budgetGate: { tokensNeeded: 500, tokensRemaining: 10, totalTokensConsumed: 90 } });
    const composer = await region(el, 'jf-sv3-composer');
    // The run is still live, so this is the PRIORITY that is being asserted, not mere presence.
    expect(ctrl.runInFlight).toBe(true);
    // Owner decision 2026-08-26 — the SLOT still holds exactly one control, and Answer is still the
    // one that wins it. What changed is that the Stop no longer disappears with the rung it lost:
    // the reader was measured with no stop control for 300 s of a live run, because a supervised
    // run is held on a decision for most of its life. The Stop stands BESIDE the slot (the same
    // place the tier control stands), so the strict-priority law is intact and the capability is
    // not conditional on the run having nothing else outstanding.
    expect(occupants(composer)).toEqual(['sv3-composer-answer', 'sv3-composer-stop']);
    expect(composer.shadowRoot?.querySelectorAll('[data-testid="sv3-composer-stop"]')).toHaveLength(
      1,
    );
    // DOM ORDER, which `occupants` cannot see (it filters a fixed id list): the standing Stop sits
    // BEFORE the primary slot, so the slot keeps the row's trailing position it has in every other
    // state and the reader's eye does not have to re-find the send/answer control per run phase.
    expect(
      [...(composer.shadowRoot?.querySelectorAll('.actions > button') ?? [])].map(
        (b) => b.getAttribute('data-testid'),
      ),
    ).toEqual(['sv3-composer-stop', 'sv3-composer-answer']);
    // It says what it does, in its own words — not the slot rung's "Enter steers", which would name
    // a key that does nothing here (a submit is refused while a decision is held, asserted below).
    expect(q(composer, 'sv3-composer-stop')?.getAttribute('aria-label')).toBe(
      SV3_STANDING_STOP_REASON,
    );

    // The rung does the one thing it honestly can: it takes the reader to the decision. It cannot
    // resolve it — the dedicated controls in the prompt block are the only things that can.
    (q(composer, 'sv3-composer-answer') as HTMLButtonElement).click();
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    // The prompt's FIRST control, which since Phase F7 (inventory B8) is the raise remedy rather
    // than a concession — landing the reader on "give something up" would be the wrong default.
    expect(main.shadowRoot?.activeElement).toBe(q(main, 'sv3-run-budget-raise-little'));
    expect(ctrl.resolveBudgetGate).not.toHaveBeenCalled();

    // …and the standing Stop is the SAME halt the slot rung fires — one stop, one path to the run,
    // no second halt mechanism minted for the held case.
    (q(composer, 'sv3-composer-stop') as HTMLButtonElement).click();
    await settle(el);
    expect(ctrl.cancelSession).toHaveBeenCalledTimes(1);
    expect(ctrl.resolveBudgetGate).not.toHaveBeenCalled();
  });

  it('falls to the follow-up rung once the run has ended, over a bare Send', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    await frame(el, { runInFlight: false, isStreaming: false });
    const composer = await region(el, 'jf-sv3-composer');
    expect(occupants(composer)).toEqual(['sv3-composer-send']);
    // Same control, different promise: with something to send, the reason says the message
    // CONTINUES this conversation rather than opening one.
    await type(el, 'and the archive?');
    expect(q(composer, 'sv3-composer-send')?.getAttribute('aria-label')).toContain('follow-up');
  });
});

describe('a typed prompt is resolved by its OWN command, never by chat text', () => {
  it('dispatches NOTHING when the reader types and sends while a decision is held', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    await frame(el, {
      budgetGate: { tokensNeeded: 500, tokensRemaining: 10, totalTokensConsumed: 90 },
    });
    ctrl.send.mockClear();
    ctrl.steer.mockClear();

    await type(el, 'yes go ahead');
    await press(el);
    await press(el, { ctrlKey: true });

    // No new run, no steer, no ask — and the gate is still held, because nothing resolved it.
    expect(ctrl.send).not.toHaveBeenCalled();
    expect(ctrl.steer).not.toHaveBeenCalled();
    expect(ctrl.resolveBudgetGate).not.toHaveBeenCalled();
    expect(dispatches()).toHaveLength(0);
    const main = await region(el, 'jf-sv3-main');
    expect(q(main, 'sv3-run-prompt')?.dataset.kind).toBe('budget');
  });

  /* ── 859 D live-defect D1 — the gate's fact panel states the work, not two structural zeros ── */

  /** The rows the fact panel rendered, as `label → value`, so a MISSING row is legible as missing. */
  const factRows = (main: Mounted): Record<string, string> => {
    const dl = q(main, 'sv3-run-budget-facts');
    const rows: Record<string, string> = {};
    for (const div of dl?.querySelectorAll('div') ?? []) {
      const label = div.querySelector('dt')?.textContent?.trim() ?? '';
      rows[label] = div.querySelector('dd')?.textContent?.trim() ?? '';
    }
    return rows;
  };

  it('states the tool calls and steps this run has ACTUALLY made, mid-run', async () => {
    // THE LIVE DEFECT. Every gate in the 2026-08-25 audit rendered "Tool calls 0 · Steps 0" beside a
    // correct "Last action" while the backend was five iterations and four tool calls in. Both
    // fields were initialised to 0 with `onDone` as their only writer, so at a gate — which happens
    // by definition BEFORE the terminal — they were structurally zero. That is a fabricated fact at
    // the exact moment the reader is deciding whether to spend more money on the run, and it broke
    // the panel's own rule that absent facts are omitted rather than shown as zero.
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', ['c1', 'c2', 'c3']);
    await frame(el, {
      // What the run's `progress` frames have reported by the time the gate lands.
      iterationsUsed: 5,
      budgetGate: { tokensNeeded: 500, tokensRemaining: 10, totalTokensConsumed: 90 },
    });
    const rows = factRows(await region(el, 'jf-sv3-main'));
    // The tool-call figure is the FEED's own count — the same number the receipt shows — so the
    // panel and the receipt can never describe different runs.
    expect(rows['Tool calls']).toBe('3');
    expect(rows['Steps']).toBe('5');
    expect(rows['Last action']).toBe('core_search');
  });

  it('OMITS the step count entirely when nothing has reported one', async () => {
    // The other half of the rule: the fix must not simply move the fabricated zero somewhere else.
    // A window that has been told no step count says nothing, exactly as it already does for the
    // elapsed time and the last action.
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', ['c1']);
    await frame(el, {
      iterationsUsed: null,
      budgetGate: { tokensNeeded: 500, tokensRemaining: 10, totalTokensConsumed: 90 },
    });
    const rows = factRows(await region(el, 'jf-sv3-main'));
    expect(Object.keys(rows)).not.toContain('Steps');
    // And the facts it DOES have still render — omission is per-fact, not a blanked panel.
    expect(rows['Tool calls']).toBe('1');
    expect(rows['Tokens used']).toBe('90');
  });

  it('resolves the budget gate through the dedicated control, via the seam', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    await frame(el, {
      budgetGate: { tokensNeeded: 500, tokensRemaining: 10, totalTokensConsumed: 90 },
    });
    const main = await region(el, 'jf-sv3-main');
    await pressControl(q(main, 'sv3-run-budget-finalize'));
    await settle(el);
    expect(ctrl.resolveBudgetGate).toHaveBeenCalledWith('finalize');
  });

  it('offers RAISING the budget as the third option, through the same seam (inventory B8)', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    await frame(el, {
      budgetGate: { tokensNeeded: 500, tokensRemaining: 10, totalTokensConsumed: 90 },
    });
    const main = await region(el, 'jf-sv3-main');
    // Tempdoc 859 §D §2.5 — THREE sized arms now, not one fixed step. Each dispatches its OWN
    // amount, and each amount must clear this gate: `tokensNeeded` 500 against `tokensRemaining` 10
    // is a shortfall of 490, so an arm that offered less would resume the loop into an immediate
    // re-gate. That is the property the retired 4,096-token constant could not guarantee.
    const shortfall = 500 - 10;
    const spends: number[] = [];
    for (const rung of ['little', 'again', 'plenty']) {
      const arm = q(main, `sv3-run-budget-raise-${rung}`) as HTMLButtonElement | null;
      expect(arm, `the ${rung} arm renders`).not.toBeNull();
      // The label states the amount the directive actually spends, so a button that promised one
      // number and dispatched another fails here.
      const promised = Number((arm?.textContent ?? '').replace(/[^0-9]/g, ''));
      expect(promised).toBeGreaterThanOrEqual(shortfall);
      spends.push(promised);
    }
    // Strictly ascending: three labels that spend the same amount would be three lies.
    expect(spends[1]).toBeGreaterThan(spends[0]!);
    expect(spends[2]).toBeGreaterThan(spends[1]!);

    const raise = q(main, 'sv3-run-budget-raise-again') as HTMLButtonElement | null;
    await pressControl(raise);
    await settle(el);

    // Through the seam's own `raise-budget` directive, not by resolving the gate: raising is not a
    // decision about the gate, it is more allowance (`controllers/runControlIntent.ts:34-36`).
    expect(ctrl.raiseBudget).toHaveBeenCalledWith(spends[1]);
    expect(ctrl.resolveBudgetGate).not.toHaveBeenCalled();
    expect(ctrl.cancelSession).not.toHaveBeenCalled();
  });

  it('refuses the raise the moment the run is no longer live — the seam\'s predicate, not a guess', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    await frame(el, {
      budgetGate: { tokensNeeded: 500, tokensRemaining: 10, totalTokensConsumed: 90 },
    });
    const main = await region(el, 'jf-sv3-main');
    // The backend evicts finished sessions, so a raise on one is the 404 class tempdoc 577 Ext III
    // made structural. The window dispatches; the SEAM refuses.
    await frame(el, { runInFlight: false, isStreaming: false });
    await pressControl(q(main, 'sv3-run-budget-raise-little'));
    await settle(el);
    expect(ctrl.raiseBudget).not.toHaveBeenCalled();
  });

  it('renders a held tool call as a typed prompt WITHOUT a second approve/deny of its own', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    await frame(el, {
      conversation: [
        { id: 'u1', type: 'user', content: 'do the thing', timestamp: 0 },
        groupEntry('g1', ['c1']),
      ],
      toolCalls: { c1: toolCall('c1', { status: 'pending', toolName: 'core_write', risk: 'HIGH' }) },
    });
    const main = await region(el, 'jf-sv3-main');
    const prompt = q(main, 'sv3-run-prompt');
    expect(prompt?.dataset.kind).toBe('approval');
    expect(prompt?.textContent).toContain('core_write');
    // The product has ONE approve/deny ceremony (`operations/authorizationBroker.ts`). A window-local
    // pair here would be the retired per-card buttons coming back under a new name.
    expect(prompt?.querySelectorAll('button')).toHaveLength(0);
  });
});

describe('the run ends in ONE receipt whose counts come from the feed it summarises', () => {
  it('counts exactly the cards the feed rendered', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'audit the folder', ['c1', 'c2', 'c3']);

    const main = await region(el, 'jf-sv3-main');
    const rendered = all(main, 'sv3-run-tool').length;
    expect(rendered).toBe(3);

    await frame(el, { runInFlight: false, isStreaming: false });
    const receipts = all(main, 'sv3-run-receipt');
    expect(receipts).toHaveLength(1);
    // THE probe: the number in the receipt is the number of cards that were on screen.
    expect(receipts[0]?.textContent?.trim()).toBe(`${rendered} tool calls · finished`);
    expect(receipts[0]?.dataset.outcome).toBe('complete');
    // The live feed is attention, not record: it ends with the run.
    expect(q(main, 'sv3-run-feed')).toBeNull();
  });

  it('says NOTHING about the outcome when the halt reached no terminal at all', async () => {
    // Owner decision 2026-08-26 (answerless cancel). This used to read "2 tool calls · stopped by
    // you" LIVE while the same run, cold-loaded, read "2 tool calls · failed" — a run stopped before
    // it answered emits `AgentError`/CANCELLED and never `AgentDone`, so no disposition reaches
    // either plane and the record's derivation fell to the error entry. The owner's ruling is that
    // no outcome word is needed that early. It is worded ONCE, off the absent terminal, so both
    // planes reach it with the same value and cannot tell different stories again.
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'audit the folder', ['c1', 'c2']);
    const composer = await region(el, 'jf-sv3-composer');
    (q(composer, 'sv3-composer-stop') as HTMLButtonElement | null)?.click();
    await settle(el);
    expect(ctrl.cancelSession).toHaveBeenCalledTimes(1);

    await frame(el, { runInFlight: false, isStreaming: false });
    const main = await region(el, 'jf-sv3-main');
    const receipt = q(main, 'sv3-run-receipt');
    expect(receipt?.textContent?.trim()).toBe('2 tool calls');
    // The status underneath is still the halt, so nothing downstream reads this as a completion.
    expect(receipt?.dataset.outcome).toBe('halted');
    // A halt is the reader's own act: it is an outcome, not a break.
    expect(receipt?.dataset.broken).toBe('false');
  });

  it('says stopped-by-you when the halt DID reach a terminal — the late-cancel race', async () => {
    // The other half of the same rule, and the case owner decision 2026-08-26 (late cancel) is
    // about: the reader's Stop lands as the run reaches its terminal, so the run really did finish
    // and carries a disposition. There IS an ending to report now, and the reader's act is what it
    // is called — in the outcome slot beside "finished", never in an error position.
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'audit the folder', ['c1', 'c2']);
    const composer = await region(el, 'jf-sv3-composer');
    (q(composer, 'sv3-composer-stop') as HTMLButtonElement | null)?.click();
    await settle(el);
    expect(ctrl.cancelSession).toHaveBeenCalledTimes(1);

    // …and the run's own terminal arrives anyway, because it had already been reached.
    ctrl.terminalDisposition = 'COMPLETED';
    ctrl.answerEvidenceRunId = 'run-1';
    await frame(el, { runInFlight: false, isStreaming: false });
    const main = await region(el, 'jf-sv3-main');
    const receipt = q(main, 'sv3-run-receipt');
    expect(receipt?.textContent?.trim()).toBe('2 tool calls · stopped by you');
    // The halt still wins the STATUS over the completion — the reader's act outranks the run's own
    // account of itself.
    expect(receipt?.dataset.outcome).toBe('halted');
    expect(receipt?.dataset.broken).toBe('false');
  });

  it('is not re-written by a LATER run on the shared controller', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'audit the folder', ['c1']);
    await frame(el, { runInFlight: false, isStreaming: false });
    const main = await region(el, 'jf-sv3-main');
    expect(q(main, 'sv3-run-receipt')?.textContent?.trim()).toBe('1 tool call · finished');

    // The controller is product-wide: someone else's run now starts, makes three calls, and ends.
    // Its numbers must not reach this window's concluded turn — the wrong-origin class the explicit
    // activeTurnId + the one-terminal rule exist to prevent.
    await frame(el, {
      runInFlight: true,
      sessionId: 'run-2',
      conversation: [
        { id: 'u1', type: 'user', content: 'audit the folder', timestamp: 0 },
        groupEntry('g1', ['c1']),
        { id: 'u2', type: 'user', content: 'reindex the archive', timestamp: 1 },
        groupEntry('g2', ['c2', 'c3', 'c4']),
      ],
      toolCalls: {
        c1: toolCall('c1'),
        c2: toolCall('c2'),
        c3: toolCall('c3'),
        c4: toolCall('c4'),
      },
    });
    await frame(el, { runInFlight: false, isStreaming: false });

    // The CONCLUDED turn is untouched — that is the whole guarantee, and a wrong-origin write would
    // show up right here as "4 tool calls".
    const receipts = all(main, 'sv3-run-receipt');
    expect(receipts[0]?.textContent?.trim()).toBe('1 tool call · finished');
    // The later run gets its OWN turn instead, because the controller says it belongs to this
    // conversation (Phase F6: the run's conversation is the identity, so it is not a second row).
    expect(receipts).toHaveLength(2);
    expect(receipts[1]?.textContent?.trim()).toBe('3 tool calls · finished');
  });
});

// WP6.2 S8.2 (review 5) — the note of a steer send whose outcome is unconfirmed is window-local: the
// record never carries it, so the settled turn must not drop it when the live feed yields to the record.
// It stays for as long as the conversation is open here, until the run's report of that send removes it.
describe('an unconfirmed steer send keeps its note after the run ends', () => {
  /** The note the real controller shows for a steer send whose connection failed. */
  async function unconfirmedNote(): Promise<ConversationEntry> {
    const real = new RealAgentSessionController('http://127.0.0.1:9999', () => {});
    real.sessionId = 'run-1';
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))));
    try {
      expect(await real.steer('Answer in French.')).toBe(false);
    } finally {
      vi.stubGlobal('fetch', fetchMock);
      real.destroy();
    }
    const note = real.conversation.at(-1)!;
    expect(note.content).toMatch(/^Could not confirm your direction reached the run/);
    return { ...note, id: 'n1' };
  }

  const transcript = (main: Mounted): string => q(main, 'sv3-transcript')?.textContent ?? '';

  /** The canonical record a settled turn yields to: the task, a step and the run's answer, and no note. */
  function serveRecord(): void {
    const event = (id: string, kind: string, content: string, second: number) => ({
      id, occurredAt: `2026-10-19T10:00:0${second}Z`, kind, originator: kind === 'USER_MESSAGE' ? 'user' : 'agent',
      content, attributes: {},
    });
    fetchMock.mockImplementation(async (url: unknown) =>
      String(url).includes('/api/thread/')
        ? {
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                conversationId: 'conv-1',
                events: [
                  event('r1', 'USER_MESSAGE', 'audit the folder', 0),
                  event('r2', 'PROGRESS', 'Recorded step.', 1),
                  event('r3', 'ASSISTANT_MESSAGE', 'The recorded answer.', 2),
                ],
                lifecycles: [],
              }),
          }
        : { ok: true, status: 200, body: null, json: () => Promise.resolve({}) },
    );
  }

  it('shows the note on the settled turn, through a later run, until its own report removes it', async () => {
    const note = await unconfirmedNote();
    serveRecord();
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'audit the folder', ['c1']);
    const live = [...ctrl.conversation, note];
    await frame(el, { conversation: live });
    const main = await region(el, 'jf-sv3-main');
    expect(transcript(main)).toContain(note.content);

    await frame(el, { runInFlight: false, isStreaming: false });
    expect(q(main, 'sv3-run-feed')).toBeNull();
    expect(transcript(main)).toContain('Recorded step.');
    expect(transcript(main)).toContain(note.content);

    // A later run in the same conversation leaves the first turn's note where it is.
    const later = [...live, { id: 'u2', type: 'user' as const, content: 'and the archive', timestamp: 1 }];
    await frame(el, { runInFlight: true, sessionId: 'run-2', conversation: later });
    await frame(el, { runInFlight: false, isStreaming: false });
    expect(all(main, 'sv3-run-receipt')).toHaveLength(2);
    expect(transcript(main).split(note.content)).toHaveLength(2);
  });

  it('drops the note once the run reports the direction that send produced', async () => {
    const note = await unconfirmedNote();
    serveRecord();
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'audit the folder', ['c1']);
    const live = [...ctrl.conversation, note];
    await frame(el, { conversation: live });
    await frame(el, { runInFlight: false, isStreaming: false });
    const main = await region(el, 'jf-sv3-main');
    expect(transcript(main)).toContain(note.content);
    // The controller removes a settled note from its conversation; the turn follows it.
    await frame(el, { conversation: live.filter((e) => e.id !== note.id) });
    expect(transcript(main)).not.toContain(note.content);
  });
});

describe('the optimistic handoff yields to server truth exactly once', () => {
  it('shows the local echo until the server produces something, then never again', async () => {
    aiOnline();
    const el = await mount();
    await type(el, 'do the thing');
    await press(el, { ctrlKey: true });
    const main = await region(el, 'jf-sv3-main');

    // The controller echoes the reader's own prompt and sets its flags optimistically. Neither is
    // the server speaking, so the window is still SENDING.
    await frame(el, {
      isStreaming: true,
      conversation: [{ id: 'u1', type: 'user', content: 'do the thing', timestamp: 0 }],
    });
    expect(q(main, 'sv3-run-echo')?.textContent?.trim()).toBe(RUN_DISPATCHING);
    expect(q(main, 'sv3-run-feed')).toBeNull();

    await frame(el, { runInFlight: true, sessionId: 'run-1', streamingText: 'Looking…' });
    expect(q(main, 'sv3-run-echo')).toBeNull();
    expect(q(main, 'sv3-run-feed')).not.toBeNull();

    // Latched: a frame in which the evidence disappears does not put the window back into sending.
    await frame(el, { streamingText: '', sessionId: null });
    expect(q(main, 'sv3-run-echo')).toBeNull();
  });
});

describe('a mid-run submit JOINS the live turn instead of starting a second one', () => {
  it('steers through the seam, and starts no competing run on either key', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'audit the folder', []);
    ctrl.send.mockClear();

    await type(el, 'skip the archive');
    await press(el);
    expect(ctrl.steer).toHaveBeenCalledWith('skip the archive');
    expect(ctrl.send).not.toHaveBeenCalled();
    expect(dispatches()).toHaveLength(0);

    await type(el, 'and the drafts too');
    await press(el, { ctrlKey: true });
    expect(ctrl.steer).toHaveBeenLastCalledWith('and the drafts too');
    expect(ctrl.send).not.toHaveBeenCalled();
    // One turn, not three: a steer is a directive, not a commitment.
    const main = await region(el, 'jf-sv3-main');
    expect(all(main, 'sv3-turn')).toHaveLength(1);
  });

  it('refuses a mid-ASK submit, because an ask stream has no steer channel', async () => {
    aiOnline();
    const el = await mount();
    // Hold the ask open: the stub never resolves a body, so the turn stays streaming.
    fetchMock.mockImplementation(
      () => new Promise(() => {}) as unknown as Promise<Response>,
    );
    await type(el, 'why did it fail?');
    await press(el);
    expect(dispatches()).toHaveLength(1);

    await type(el, 'actually, never mind');
    await press(el);
    expect(dispatches()).toHaveLength(1);
    expect(ctrl.steer).not.toHaveBeenCalled();
  });
});

describe('the sidebar spends act-now only while a typed prompt is pending', () => {
  it('moves the run session through in-motion, act-now, and back to resting', async () => {
    aiOnline();
    const el = await mount();
    const statusOf = async (): Promise<string | undefined> => {
      const sidebar = await region(el, 'jf-sv3-sidebar');
      const row = sidebar.shadowRoot?.querySelector('jf-sv3-session-row') as HTMLElement | null;
      return row?.getAttribute('status') ?? undefined;
    };

    await delegateWithCalls(el, 'audit the folder', []);
    expect(await statusOf()).toBe('in-motion');

    await frame(el, { budgetGate: { tokensNeeded: 5, tokensRemaining: 1, totalTokensConsumed: 9 } });
    expect(await statusOf()).toBe('act-now');

    await frame(el, { budgetGate: null });
    expect(await statusOf()).toBe('in-motion');

    await frame(el, { runInFlight: false, isStreaming: false });
    expect(await statusOf()).toBe('resting');
  });
});

describe('presence: a live run this window never dispatched (tempdoc 822 Phase F3)', () => {
  /**
   * The REGRESSION for F2's named finding — *window-local in-memory sessions orphan a live run on
   * reload*: a fresh window instance showed zero sessions while the run went on holding server-side.
   * The window is re-mounted here next to a controller that is already running, which is exactly the
   * shape of that incident, and the assertions are the three things the reader lost: the run is
   * VISIBLE, the composer slot is HONEST about it, and claiming it shows the feed.
   */
  const alreadyRunning = (over: Partial<FakeCtrl> = {}): void => {
    ctrlExists = true;
    Object.assign(ctrl, {
      runInFlight: true,
      runKind: 'agent',
      sessionId: 'run-42',
      conversation: [
        { id: 'u1', type: 'user', content: 'index the vendor folder', timestamp: 0 },
        { id: 'a1', type: 'assistant-text', content: 'Reading it now.', timestamp: 0 },
        groupEntry('g1', ['c1']),
      ] as ConversationEntry[],
      toolCalls: { c1: toolCall('c1') },
      ...over,
    });
  };

  const rowsOf = async (el: Mounted): Promise<HTMLElement[]> => {
    const sidebar = await region(el, 'jf-sv3-sidebar');
    return [...(sidebar.shadowRoot?.querySelectorAll<HTMLElement>('jf-sv3-session-row') ?? [])];
  };

  it('gives the run a session on the Active shelf, titled with its own task', async () => {
    aiOnline();
    alreadyRunning();
    const el = await mount();
    await settle(el);

    const rows = await rowsOf(el);
    expect(rows.map((r) => (r as HTMLElement & { label: string }).label)).toEqual([
      'index the vendor folder',
    ]);
    const sidebar = await region(el, 'jf-sv3-sidebar');
    expect(
      [...(sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-group-label"]') ?? [])].map(
        (n) => n.textContent?.trim(),
      ),
    ).toEqual(['Active']);
    // It is not CLAIMED: the reader was not moved into a conversation they did not open.
    expect((rows[0] as HTMLElement & { active: boolean }).active).toBe(false);
  });

  it('keeps the composer slot honest about a run the local list did not start', async () => {
    aiOnline();
    alreadyRunning();
    const el = await mount();
    await settle(el);
    const composer = await region(el, 'jf-sv3-composer');
    // Stop, not Send — the window cannot offer to start something while the product is running one.
    expect(q(composer, 'sv3-composer-stop')).not.toBeNull();
    expect(q(composer, 'sv3-composer-send')).toBeNull();

    // ...and a HELD run reaches the answer rung from presence just as it does from a local dispatch.
    await frame(el, { toolCalls: { c1: toolCall('c1', { status: 'pending' }) } });
    expect(q(composer, 'sv3-composer-answer')).not.toBeNull();
    // Owner decision 2026-08-26 — and the Stop STAYS, because the run is still live. This used to
    // assert its absence; halting a live run is a capability, not a rung the slot may withdraw
    // because a decision is also outstanding.
    expect(q(composer, 'sv3-composer-stop')?.getAttribute('aria-label')).toBe(
      SV3_STANDING_STOP_REASON,
    );
    expect((await rowsOf(el))[0]?.getAttribute('status')).toBe('act-now');
  });

  it('shows the run FEED once the reader claims the adopted session', async () => {
    aiOnline();
    alreadyRunning();
    const el = await mount();
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    // Nothing is claimed yet, so the transcript shows nothing — the sidebar is the only witness.
    expect(all(main, 'sv3-turn')).toHaveLength(0);

    (await rowsOf(el))[0]?.shadowRoot?.querySelector<HTMLButtonElement>('button')?.click();
    await settle(el);
    await main.updateComplete;
    expect(all(main, 'sv3-turn')).toHaveLength(1);
    expect(q(main, 'sv3-run-feed')).not.toBeNull();
    // The feed is the controller's own conversation, rendered through the ONE tool-call primitive.
    expect(main.shadowRoot?.querySelectorAll('jf-tool-call-card')).toHaveLength(1);
  });

  it('adopts the LIVE run only — a finished run in the same conversation is not its feed', async () => {
    aiOnline();
    // The controller appends across runs, so its conversation still holds a run that ended. The
    // adopted session must be titled by the live task and show only the live run's cards, or the
    // receipt would count a run the reader never watched here.
    alreadyRunning({
      conversation: [
        { id: 'u0', type: 'user', content: 'a run that already ended', timestamp: 0 },
        groupEntry('g0', ['old1', 'old2']),
        { id: 'u1', type: 'user', content: 'index the vendor folder', timestamp: 0 },
        groupEntry('g1', ['c1']),
      ] as ConversationEntry[],
      toolCalls: { old1: toolCall('old1'), old2: toolCall('old2'), c1: toolCall('c1') },
    });
    const el = await mount();
    await settle(el);
    const rows = await rowsOf(el);
    expect(rows.map((r) => (r as HTMLElement & { label: string }).label)).toEqual([
      'index the vendor folder',
    ]);

    rows[0]?.shadowRoot?.querySelector<HTMLButtonElement>('button')?.click();
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    await main.updateComplete;
    expect(main.shadowRoot?.querySelectorAll('jf-tool-call-card')).toHaveLength(1);

    // ...and the receipt counts that one card, not the three in the controller's whole conversation.
    await frame(el, { runInFlight: false, isStreaming: false });
    expect(q(main, 'sv3-run-receipt')?.textContent).toContain('1 tool call');
  });

  it('adopts a run ONCE and adopts nothing when the controller is idle', async () => {
    aiOnline();
    alreadyRunning();
    const el = await mount();
    await settle(el);
    // Several notifications later there is still exactly one row — the OPEN turn already stands for
    // this run, so a second adoption would be the same run twice in the sidebar.
    await frame(el, { streamingText: 'still going' });
    await frame(el, { streamingText: 'and going' });
    expect(await rowsOf(el)).toHaveLength(1);

    // The run ends: the adopted turn takes its receipt instead of streaming forever...
    await frame(el, { runInFlight: false, isStreaming: false, streamingText: '' });
    expect(await rowsOf(el)).toHaveLength(1);
    const composer = await region(el, 'jf-sv3-composer');
    expect(q(composer, 'sv3-composer-stop')).toBeNull();
    // ...and the settled run is not re-adopted by the next notification.
    await frame(el, {});
    expect(await rowsOf(el)).toHaveLength(1);

    // The case the ID LATCH is for: the SAME run reports itself live again after this window has
    // already concluded its turn (a flicker between frames). Without the latch that reads as a run
    // with no session, and the sidebar grows a duplicate of a run the reader already has.
    await frame(el, { runInFlight: true, sessionId: 'run-42' });
    expect(await rowsOf(el)).toHaveLength(1);
  });

  it('adopts nothing at all when no controller exists — a mounted window starts no polling', async () => {
    aiOnline();
    const el = await mount();
    await settle(el);
    expect(await rowsOf(el)).toHaveLength(0);
    expect(getCtrl).not.toHaveBeenCalled();
  });
});

describe('the window hosts the run — it does not re-implement one', () => {
  const here = dirname(fileURLToPath(import.meta.url));

  /**
   * Comments are stripped before every scan below, for the same reason the governance gates strip
   * them: a paragraph that NAMES the forbidden shape in order to forbid it must not read as the
   * shape itself, or the honest thing to do would be to stop documenting the rule.
   */
  const strip = (src: string): string =>
    src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');

  const sources = (opts: { code?: boolean } = {}): Array<[string, string]> =>
    readdirSync(here)
      .filter((name) => name.endsWith('.ts'))
      .filter((name) => opts.code !== true || !name.endsWith('.test.ts'))
      .map((name) => [name, strip(readFileSync(join(here, name), 'utf8'))]);

  // The cross-window import scan lives ONCE, in `SearchV3View.ask.test.ts` (Phase F1 wrote it and F2
  // widened it to any import form). A second copy here would be a forked scan drifting from that one.

  it('mounts the SHARED tool-call primitive and defines no tool card of its own', () => {
    const mounts = sources({ code: true }).filter(([, src]) => src.includes('<jf-tool-call-card'));
    expect(mounts.map(([name]) => name).sort()).toEqual(['Sv3Main.ts']);
    // A window-local re-author is the fork `governance/run-renderers.v1.json` exists to catch; here
    // it would show up as sv3 defining a tool/step element of its own.
    const defined = sources({ code: true })
      .flatMap(([, src]) => [...src.matchAll(/customElements\.define\('([^']+)'/g)])
      .map((m) => m[1] ?? '');
    expect(defined.filter((tag) => /tool|run-node|step/.test(tag))).toEqual([]);
  });

  it('reaches the run ONLY through the control-intent seam', () => {
    const DIRECT =
      /\.(steer|cancelSession|raiseBudget|resumeSession|resolveBudgetGate|resolveContextGate)\(/;
    const offenders = sources({ code: true })
      .filter(([, src]) => DIRECT.test(src))
      .map(([name]) => name);
    expect(offenders).toEqual([]);
  });
});

/* ── Tempdoc 859 §D §2.5 / §3.3 T10 — per-run auto-continue ───────────────────────── */

describe('a decision made once is RE-APPLIED, not re-asked (859 §D P3)', () => {
  const GATE = { tokensNeeded: 4000, tokensRemaining: 500, totalTokensConsumed: 20000 };

  /** Park the run at a budget gate, then clear it — one full gate cycle. */
  async function gateCycle(el: Mounted): Promise<void> {
    await frame(el, { budgetGate: GATE });
    await frame(el, { budgetGate: null });
  }

  it('takes the SAME amount at the next gate, without asking, and stops after three', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);

    // Gate 1: the reader ticks 'don't ask again' and chooses an arm. The toggle and the arm leave
    // as ONE decision — the checkbox is read at click time, not stored separately.
    await frame(el, { budgetGate: GATE });
    const main = await region(el, 'jf-sv3-main');
    const toggle = q(main, 'sv3-run-budget-auto')?.querySelector('input') as HTMLInputElement;
    expect(toggle, 'the gate offers the per-run choice').toBeTruthy();
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change'));
    await settle(el);
    const arm = q(main, 'sv3-run-budget-raise-again') as HTMLElement | null;
    const chosen = Number((arm?.textContent ?? '').replace(/[^0-9]/g, ''));
    await pressControl(arm);
    await settle(el);
    expect(ctrl.raiseBudget).toHaveBeenCalledTimes(1);
    await frame(el, { budgetGate: null });

    // Gates 2, 3 and 4 are answered silently — with the SAME amount, not a freshly derived one.
    for (let n = 0; n < 3; n += 1) await gateCycle(el);
    expect(ctrl.raiseBudget).toHaveBeenCalledTimes(4);
    for (const call of ctrl.raiseBudget.mock.calls) expect(call[0]).toBe(chosen);

    // Gate 5 ASKS AGAIN. Bounded, not surrendered: three silent continues is the cap, and the
    // window returns the decision to the reader rather than continuing forever or stopping.
    await frame(el, { budgetGate: GATE });
    expect(ctrl.raiseBudget).toHaveBeenCalledTimes(4);
    expect(q(await region(el, 'jf-sv3-main'), 'sv3-run-budget-raise-again')).not.toBeNull();
  });

  it('answers a held gate ONCE, however many frames the controller sends', async () => {
    // The controller notifies on every frame and the gate stays held until the raise lands, so
    // without the per-gate latch one gate would fire a raise per frame.
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    await frame(el, { budgetGate: GATE });
    const main = await region(el, 'jf-sv3-main');
    const toggle = q(main, 'sv3-run-budget-auto')?.querySelector('input') as HTMLInputElement;
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change'));
    await settle(el);
    await pressControl(q(main, 'sv3-run-budget-raise-again'));
    await settle(el);
    await frame(el, { budgetGate: null });

    await frame(el, { budgetGate: GATE });
    await frame(el, { budgetGate: GATE });
    await frame(el, { budgetGate: GATE });
    expect(ctrl.raiseBudget).toHaveBeenCalledTimes(2);
  });

  it('a NEW run asks again — the choice is scoped to the run it was made about', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'first task', []);
    await frame(el, { budgetGate: GATE });
    const main = await region(el, 'jf-sv3-main');
    const toggle = q(main, 'sv3-run-budget-auto')?.querySelector('input') as HTMLInputElement;
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change'));
    await settle(el);
    await pressControl(q(main, 'sv3-run-budget-raise-again'));
    await settle(el);
    await frame(el, { budgetGate: null, runInFlight: false, isStreaming: false, runKind: null });
    ctrl.raiseBudget.mockClear();

    // A second delegate replaces the window's run object, and the choice dies with it. Carrying it
    // over would be exactly the standing autonomy the per-run scope exists to refuse.
    await delegateWithCalls(el, 'second task', []);
    await frame(el, { budgetGate: GATE });
    expect(ctrl.raiseBudget).not.toHaveBeenCalled();
    expect(q(await region(el, 'jf-sv3-main'), 'sv3-run-budget-raise-again')).not.toBeNull();
  });

  it('a NEW run’s gate renders UNTICKED, even though the prior run’s reader ticked it', async () => {
    // Tempdoc 859 §D review F2 — the OFFER, not the raise. The test above proves the window does not
    // ACT on the stale choice; this proves it does not SHOW one either. The checkbox is pre-click
    // intent held on the region, and it used to be a plain field that outlived the run its own
    // doc-comment said it died with — so run 2's gate rendered pre-ticked, offering a decision the
    // window would not honour. Control and window saying different things is the one state this gate
    // may not have.
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'first task', []);
    await frame(el, { budgetGate: GATE });
    const first = await region(el, 'jf-sv3-main');
    const firstBox = q(first, 'sv3-run-budget-auto')?.querySelector('input') as HTMLInputElement;
    firstBox.checked = true;
    firstBox.dispatchEvent(new Event('change'));
    await settle(el);
    expect(
      (q(first, 'sv3-run-budget-auto')?.querySelector('input') as HTMLInputElement).checked,
      'the tick registered on the run that made it',
    ).toBe(true);
    await frame(el, { budgetGate: null, runInFlight: false, isStreaming: false, runKind: null });

    await delegateWithCalls(el, 'second task', []);
    await frame(el, { budgetGate: GATE });
    const second = await region(el, 'jf-sv3-main');
    const secondBox = q(second, 'sv3-run-budget-auto')?.querySelector('input') as HTMLInputElement;
    expect(secondBox, 'the new run offers the choice').toBeTruthy();
    expect(secondBox.checked, 'and offers it UNTICKED — the prior run’s tick died with it').toBe(
      false,
    );
  });
});
