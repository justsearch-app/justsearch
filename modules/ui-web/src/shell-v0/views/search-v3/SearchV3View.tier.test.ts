// @vitest-environment happy-dom

/**
 * The composer's MODE control (tempdoc 852 S4, parity ledger row 12) — the visible affordance for a
 * routing that was already live and reachable only by chord.
 *
 * The property that matters, and the reason this file exists: **the control must not be a second way
 * to route.** Ctrl/⌘+Enter has delegated since Phase F2; if the control took its own path to the
 * agent — its own dispatch, its own gate, its own event shape — the window would have two answers to
 * "what does delegating mean" and they would drift. So the central case is an EQUALITY PROBE: the
 * submit the control produces and the submit the chord produces are compared field by field, and the
 * window's downstream act is asserted on BOTH channels (the agent seam reached, the ask endpoint
 * untouched — and the other way round for `ask`).
 *
 * Also pinned: the keyboard is unchanged (the chord still delegates from either mode, plain Enter
 * still asks in the default one); the send control's routing hint follows the chosen tier rather than
 * claiming "Enter asks" while Enter delegates; the availability NOTICE follows the chosen tier, which
 * is what keeps a refusal reachable once Enter can route two ways; and the Escape ladder plus the
 * mutual exclusion of the row's two menus.
 *
 * The agent controller is mocked at the STORE boundary, as `SearchV3View.agentRun.test.ts` does, so
 * the real `dispatchRunControl` seam and the real request path are exercised against a fake run.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { AgentSessionController } from '../../controllers/AgentSessionController.js';
import { ReasoningController } from '../../controllers/ReasoningController.js';
import type { BudgetUpdate } from '../../controllers/AgentSessionController.js';

interface FakeCtrl {
  conversation: unknown[];
  toolCalls: Record<string, unknown>;
  streamingText: string;
  isStreaming: boolean;
  runInFlight: boolean;
  runKind: 'agent' | 'workflow' | 'background' | null;
  conversationId: string | null;
  sessionId: string | null;
  iterationsUsed: number;
  toolCallsExecuted: number;
  budgetUpdates: BudgetUpdate[];
  reasoning: ReasoningController;
  budgetGate: null;
  contextGate: null;
  runPark: null;
  send: ReturnType<typeof vi.fn>;
  steer: ReturnType<typeof vi.fn>;
  cancelSession: ReturnType<typeof vi.fn>;
  resolveBudgetGate: ReturnType<typeof vi.fn>;
  resolveContextGate: ReturnType<typeof vi.fn>;
  raiseBudget: ReturnType<typeof vi.fn>;
  resumeSession: ReturnType<typeof vi.fn>;
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
    iterationsUsed: 0,
    toolCallsExecuted: 0,
    budgetUpdates: [],
    // Tempdoc 859 §A — the live run feed derives its open-region item from the real controller.
    reasoning: new ReasoningController(() => {}),
    budgetGate: null,
    contextGate: null,
    runPark: null,
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

vi.mock('../../state/agentSessionStore.js', () => ({
  getAgentSessionController: () => {
    ctrlExists = true;
    return ctrl as unknown as AgentSessionController;
  },
  peekAgentSessionController: () =>
    ctrlExists ? (ctrl as unknown as AgentSessionController) : null,
  subscribeAgentSession: () => () => {},
}));

import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { resetSearchState } from '../../state/searchState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import {
  SV3_COMPOSER_SUBMIT,
  type Sv3ComposerSubmit,
} from './Sv3Composer.js';
import {
  SV3_DELEGATE_SEND_HINT,
  SV3_SEARCH_SEND_HINT,
  SV3_SEND_HINT,
  SV3_TIER_DEFAULT,
  SV3_TIER_MENU_LABEL,
  SV3_TIER_OPTIONS,
  sv3TierLabel,
  type Sv3ComposerTier,
} from './sv3-run.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

/** The observed state in which BOTH tiers are available — the window's own precondition. */
function aiOnline(): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

/**
 * Online model, ZERO indexed documents: the ask tier's gate closes (nothing to ground an answer in)
 * while delegate's stays open. The one state in which the two tiers' availability differs, which is
 * what makes the notice case below a real test rather than a tautology.
 */
function aiOnlineWithoutDocuments(): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 0 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

const askDispatches = (): unknown[][] =>
  fetchMock.mock.calls.filter((call) => String(call[0]).includes('/api/chat/dispatch'));

const searchRequests = (): unknown[][] =>
  fetchMock.mock.calls.filter((call) => String(call[0]).includes('/api/knowledge/search'));

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  ctrl = makeCtrl();
  ctrlExists = false;
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
  await el.updateComplete;
  return el;
}

async function region(el: Mounted, tag: string): Promise<Mounted> {
  const found = el.shadowRoot?.querySelector(tag) as Mounted | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

const q = <T extends HTMLElement>(host: Mounted, testid: string): T | null =>
  host.shadowRoot?.querySelector<T>(`[data-testid="${testid}"]`) ?? null;

const tierTrigger = (composer: Mounted): HTMLButtonElement => {
  const found = q<HTMLButtonElement>(composer, 'sv3-composer-tier');
  if (!found) throw new Error('no mode control in the composer');
  return found;
};

const tierRungs = (composer: Mounted): HTMLButtonElement[] => [
  ...(composer.shadowRoot?.querySelectorAll<HTMLButtonElement>(
    '[data-testid="sv3-composer-tier-option"]',
  ) ?? []),
];

/** Open the menu and pick a tier the way a reader does — click, click. */
async function chooseTier(el: Mounted, tier: Sv3ComposerTier): Promise<void> {
  const composer = await region(el, 'jf-sv3-composer');
  tierTrigger(composer).click();
  await composer.updateComplete;
  const rung = tierRungs(composer).find((item) => item.dataset.tier === tier);
  if (!rung) throw new Error(`no rung for ${tier}`);
  rung.click();
  await composer.updateComplete;
  await el.updateComplete;
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

/** Send through the primary control — the affordance the mode control governs. */
async function pressSend(el: Mounted): Promise<void> {
  const composer = await region(el, 'jf-sv3-composer');
  q<HTMLButtonElement>(composer, 'sv3-composer-send')?.click();
  await settle(el);
}

/** Send through the keyboard, with or without the delegate accelerator. */
async function pressEnter(el: Mounted, opts: { ctrlKey?: boolean } = {}): Promise<void> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  field?.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: opts.ctrlKey === true, bubbles: true }),
  );
  await settle(el);
}

/** Every submit the composer announced, in order — the ONE origin of a send. */
function recordSubmits(el: Mounted): Sv3ComposerSubmit[] {
  const seen: Sv3ComposerSubmit[] = [];
  el.addEventListener(SV3_COMPOSER_SUBMIT, (event) => {
    seen.push((event as CustomEvent<Sv3ComposerSubmit>).detail);
  });
  return seen;
}

/* ── 1. The affordance ───────────────────────────────────────────────────────────────────────── */

describe('the mode the next send takes is on screen, not only in a chord', () => {
  it('rests on the default tier, named in full, beside the effort control', async () => {
    aiOnline();
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const control = tierTrigger(composer);

    // BOTH halves in the accessible name: the visible label is only the value, and docking
    // evaporates it into the glyph.
    expect(control.getAttribute('aria-label')).toBe(
      `${SV3_TIER_MENU_LABEL}: ${sv3TierLabel(SV3_TIER_DEFAULT)}`,
    );
    expect(control.textContent).toContain(sv3TierLabel(SV3_TIER_DEFAULT));
    expect(control.getAttribute('aria-haspopup')).toBe('menu');
    expect(control.getAttribute('aria-expanded')).toBe('false');

    // The row order the design fixes: mode, then effort, then the model FACT.
    const row = [
      ...(composer.shadowRoot?.querySelectorAll<HTMLElement>('.controls [data-testid]') ?? []),
    ].map((node) => node.dataset.testid);
    expect(row.slice(0, 2)).toEqual(['sv3-composer-tier', 'sv3-composer-effort']);
  });

  it('opens a radio menu naming every tier, with the default badged', async () => {
    aiOnline();
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    expect(q(composer, 'sv3-composer-tier-menu')).toBeNull();

    tierTrigger(composer).click();
    await composer.updateComplete;

    const rungs = tierRungs(composer);
    expect(rungs.map((rung) => rung.dataset.tier)).toEqual(
      SV3_TIER_OPTIONS.map((option) => option.id),
    );
    for (const rung of rungs) expect(rung.getAttribute('role')).toBe('menuitemradio');
    expect(rungs.filter((rung) => rung.getAttribute('aria-checked') === 'true')).toHaveLength(1);
    expect(
      composer.shadowRoot?.querySelectorAll('[data-testid="sv3-composer-tier-default"]'),
    ).toHaveLength(1);
    // Each rung says what the tier DOES; a menu of two bare words would be two names for one act.
    for (const option of SV3_TIER_OPTIONS) {
      expect(composer.shadowRoot?.textContent).toContain(option.description);
    }
  });

  it('hands the choice to the window and renders it back', async () => {
    aiOnline();
    const el = await mount();
    await chooseTier(el, 'delegate');

    expect(el.tier).toBe('delegate');
    const composer = await region(el, 'jf-sv3-composer');
    expect(tierTrigger(composer).getAttribute('aria-label')).toBe(
      `${SV3_TIER_MENU_LABEL}: ${sv3TierLabel('delegate')}`,
    );
    // Closed on choose, and the choice is checked when it reopens.
    expect(q(composer, 'sv3-composer-tier-menu')).toBeNull();
    tierTrigger(composer).click();
    await composer.updateComplete;
    const checked = tierRungs(composer).find(
      (rung) => rung.getAttribute('aria-checked') === 'true',
    );
    expect(checked?.dataset.tier).toBe('delegate');
  });
});

/* ── 2. The equality probe: one routing, two affordances ─────────────────────────────────────── */

describe('the control and the chord are the same act', () => {
  it('announces an IDENTICAL submit from the control and from Ctrl+Enter', async () => {
    aiOnline();
    const byControl = await mount();
    const fromControl = recordSubmits(byControl);
    await chooseTier(byControl, 'delegate');
    await type(byControl, 'summarise the quarter');
    await pressSend(byControl);

    const byChord = await mount();
    const fromChord = recordSubmits(byChord);
    await type(byChord, 'summarise the quarter');
    await pressEnter(byChord, { ctrlKey: true });

    // Field by field, not "both delegated": a control that reached the agent through its own
    // differently-shaped announcement would satisfy the weaker claim and drift from the chord.
    expect(fromControl).toHaveLength(1);
    expect(fromChord).toHaveLength(1);
    expect(fromControl[0]).toEqual(fromChord[0]);
    expect(fromControl[0]).toEqual({ query: 'summarise the quarter', tier: 'delegate' });
  });

  it('routes the control-sent draft to the agent seam and NOT to the ask endpoint', async () => {
    aiOnline();
    const el = await mount();
    await chooseTier(el, 'delegate');
    await type(el, 'do the thing');
    await pressSend(el);

    expect(ctrl.send).toHaveBeenCalledTimes(1);
    expect(ctrl.send.mock.calls[0]?.[0]).toBe('do the thing');
    // The OTHER channel is asserted untouched, so a send that fired both would fail here.
    expect(askDispatches()).toHaveLength(0);
  });

  it('searches on the default tier — no ask, no agent — and asks on the ask tier', async () => {
    // WP1.1: the default tier is Search, which lists documents and creates no turn.
    aiOnline();
    const el = await mount();
    await type(el, 'what is in the corpus');
    await pressSend(el);
    await settle(el);

    expect(searchRequests().length).toBeGreaterThan(0);
    expect(askDispatches()).toHaveLength(0);
    expect(ctrl.send).not.toHaveBeenCalled();

    await chooseTier(el, 'ask');
    await type(el, 'what is in the corpus');
    await pressSend(el);

    expect(askDispatches().length).toBeGreaterThan(0);
    expect(ctrl.send).not.toHaveBeenCalled();
  });
});

/* ── 3. The keyboard is unchanged ────────────────────────────────────────────────────────────── */

describe('the accelerator keeps its meaning', () => {
  it('delegates on Ctrl+Enter from the DEFAULT mode, as it did before the control existed', async () => {
    aiOnline();
    const el = await mount();
    const submits = recordSubmits(el);
    await type(el, 'delegate this');
    await pressEnter(el, { ctrlKey: true });

    expect(submits).toEqual([{ query: 'delegate this', tier: 'delegate' }]);
    expect(ctrl.send).toHaveBeenCalledTimes(1);
    expect(askDispatches()).toHaveLength(0);
  });

  it('delegates on Ctrl+Enter from the delegate mode too — the chord is not a toggle', async () => {
    aiOnline();
    const el = await mount();
    await chooseTier(el, 'delegate');
    const submits = recordSubmits(el);
    await type(el, 'delegate this too');
    await pressEnter(el, { ctrlKey: true });

    expect(submits).toEqual([{ query: 'delegate this too', tier: 'delegate' }]);
    expect(ctrl.send).toHaveBeenCalledTimes(1);
  });

  it('sends plain Enter at the CHOSEN tier — the mode governs the default key', async () => {
    aiOnline();
    const search = await mount();
    const searched = recordSubmits(search);
    await type(search, 'plain enter searches');
    await pressEnter(search);
    expect(searched).toEqual([{ query: 'plain enter searches', tier: 'search' }]);

    const ask = await mount();
    await chooseTier(ask, 'ask');
    const asked = recordSubmits(ask);
    await type(ask, 'plain enter asks');
    await pressEnter(ask);
    expect(asked).toEqual([{ query: 'plain enter asks', tier: 'ask' }]);

    const delegate = await mount();
    await chooseTier(delegate, 'delegate');
    const delegated = recordSubmits(delegate);
    await type(delegate, 'plain enter delegates');
    await pressEnter(delegate);
    expect(delegated).toEqual([{ query: 'plain enter delegates', tier: 'delegate' }]);
  });
});

/* ── 4. What the send control says it will do ────────────────────────────────────────────────── */

describe('the routing explanation follows the mode', () => {
  it('names the key that now runs, in every mode', async () => {
    aiOnline();
    const el = await mount();
    await type(el, 'anything');
    const composer = await region(el, 'jf-sv3-composer');
    const send = () => q<HTMLButtonElement>(composer, 'sv3-composer-send');

    expect(send()?.getAttribute('aria-label')).toContain(SV3_SEARCH_SEND_HINT);

    await chooseTier(el, 'ask');
    await composer.updateComplete;
    expect(send()?.getAttribute('aria-label')).toContain(SV3_SEND_HINT);

    await chooseTier(el, 'delegate');
    await composer.updateComplete;
    // The hint is not merely different — it is the delegate wording, and the ask wording is GONE.
    expect(send()?.getAttribute('aria-label')).toContain(SV3_DELEGATE_SEND_HINT);
    expect(send()?.getAttribute('aria-label')).not.toContain(SV3_SEND_HINT);
    expect(send()?.getAttribute('title')).toBe(send()?.getAttribute('aria-label'));
  });
});

/* ── 5. A refusal stays reachable once Enter can route two ways ──────────────────────────────── */

describe('the notice states the CHOSEN tier availability', () => {
  it('yields the ask tier reason for the delegate tier when the mode changes', async () => {
    // Model online, corpus empty: ask is gated, delegate is not — the one state where the two
    // differ, so a notice that ignored the mode would state a refusal that no longer applies.
    aiOnlineWithoutDocuments();
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const notice = () => q(composer, 'sv3-composer-notice');
    // Search, the default, refuses nothing here: the ask tier's reason is not its to state.
    expect(notice()).toBeNull();

    await chooseTier(el, 'ask');
    await composer.updateComplete;
    const askReason = notice()?.textContent?.trim() ?? '';
    expect(askReason).not.toBe('');

    await chooseTier(el, 'delegate');
    await composer.updateComplete;
    // Delegate needs no indexed document, so the refusal is gone with the tier that owned it.
    expect(notice()).toBeNull();

    // And the send is live again: a mode whose gate is open must not stay refused behind the other
    // tier's reason.
    await type(el, 'run the agent');
    await pressSend(el);
    expect(ctrl.send).toHaveBeenCalledTimes(1);
  });
});

/* ── 6. Two menus, one row ───────────────────────────────────────────────────────────────────── */

describe('the control row holds at most one open menu', () => {
  it('closes the effort menu when the mode menu opens, and the other way round', async () => {
    aiOnline();
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');

    q<HTMLButtonElement>(composer, 'sv3-composer-effort')?.click();
    await composer.updateComplete;
    expect(q(composer, 'sv3-composer-effort-menu')).not.toBeNull();

    tierTrigger(composer).click();
    await composer.updateComplete;
    expect(q(composer, 'sv3-composer-effort-menu')).toBeNull();
    expect(q(composer, 'sv3-composer-tier-menu')).not.toBeNull();

    q<HTMLButtonElement>(composer, 'sv3-composer-effort')?.click();
    await composer.updateComplete;
    expect(q(composer, 'sv3-composer-tier-menu')).toBeNull();
    expect(q(composer, 'sv3-composer-effort-menu')).not.toBeNull();
  });

  it('gives Escape to the open mode menu before the window sees it', async () => {
    aiOnline();
    const el = await mount();
    // Dock the window first, so "the composer went back to hero" is a state this case can observe.
    await type(el, 'a question');
    await pressSend(el);
    await settle(el);
    expect(el.getAttribute('composer-state')).toBe('docked');

    const composer = await region(el, 'jf-sv3-composer');
    tierTrigger(composer).click();
    await composer.updateComplete;
    const menu = q(composer, 'sv3-composer-tier-menu');
    menu?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }));
    await settle(el);

    expect(q(composer, 'sv3-composer-tier-menu')).toBeNull();
    // The rung the ladder is about: the menu is the most local transient, so the window keeps its
    // state rather than treating the same Escape as "leave the transcript".
    expect(el.getAttribute('composer-state')).toBe('docked');
  });
});
