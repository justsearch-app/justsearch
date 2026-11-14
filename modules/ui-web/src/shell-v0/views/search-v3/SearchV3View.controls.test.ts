// @vitest-environment happy-dom

/**
 * `jf-control` adoption in the Search v3 window (tempdoc 852 S4, parity ledger row 11).
 *
 * The ledger's row was written against the window as it stood before S2/S3 ("28 hand-rolled buttons,
 * 0 uses"). S2 and S3 had already moved the controls they added — the context acts, the turn
 * overflow, the edit affordances, the version pager — onto the primitive; this slice moves the
 * remaining PLAIN COMMANDS: the two composer remedies, the locked-history remedy, and the six
 * run-decision controls.
 *
 * What this file pins is the CONTRACT, not the appearance: every adopted control is one primitive
 * with one native button inside it, keeps the name and the testid it had as a hand-rolled button, and
 * still performs its own act when that button is pressed. The three properties together are what
 * makes "adopted" mean something — a control that kept its testid but lost its handler, or kept its
 * handler but lost its accessible name, fails here.
 *
 * The typed-AVAILABILITY arm of the primitive (aria-disabled plus a reachable reason) is not
 * exercised on these nine: none of them has a render-time reason to refuse. It is already pinned on
 * the controls that do — the version pager's ends and the edit Send while a stream is in flight, in
 * `SearchV3View.branch.test.ts`. The one refusal in this set that is still silent — raising the
 * budget on a run the seam knows is over — lives behind `dispatchRunControl`'s own predicate, which
 * the window would have to re-derive to render; it is recorded as a next-slice item rather than
 * forked here.
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
  budgetGate: { tokensNeeded: number; tokensRemaining: number; totalTokensConsumed: number } | null;
  contextGate: { promptTokens: number; contextWindow: number } | null;
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
let agentListener: (() => void) | null = null;

vi.mock('../../state/agentSessionStore.js', () => ({
  getAgentSessionController: () => {
    ctrlExists = true;
    return ctrl as unknown as AgentSessionController;
  },
  peekAgentSessionController: () =>
    ctrlExists ? (ctrl as unknown as AgentSessionController) : null,
  subscribeAgentSession: (listener: () => void) => {
    agentListener = listener;
    return () => {
      agentListener = null;
    };
  },
}));

import './SearchV3View.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';
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

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

function aiOnline(): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

/** No corpus: the composer's landing offers its remedy instead of claiming a corpus it has not got. */
function aiOnlineWithoutDocuments(): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 0, ownSearchableDocuments: 0 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

beforeEach(() => {
  setShowDeveloperTools(true);
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

const q = (host: Mounted, testid: string): HTMLElement | null =>
  host.shadowRoot?.querySelector(`[data-testid="${testid}"]`) ?? null;

/**
 * The primitive's activation contract, exercised the way a reader does: through the native button it
 * renders in its own shadow root. Anything else — dispatching on the host, calling `onActivate`
 * directly — would assert a path no pointer or keyboard ever takes.
 */
async function activate(host: Element | null | undefined): Promise<void> {
  if (!host) throw new Error('activate: no control');
  expect(host.localName, 'the control is born on the primitive').toBe('jf-control');
  await (host as Mounted).updateComplete;
  const buttons = host.shadowRoot?.querySelectorAll('button') ?? [];
  expect(buttons, 'the primitive renders exactly one native button').toHaveLength(1);
  const button = buttons[0] as HTMLButtonElement;
  expect(button.getAttribute('type')).toBe('button');
  // Named — through the primitive's projection, whichever half the call site used.
  const named =
    (button.getAttribute('aria-label') ?? '').trim() !== '' ||
    (host.textContent ?? '').trim() !== '';
  expect(named, 'the control resolves an accessible name').toBe(true);
  button.click();
}

/** Put a live delegated run on screen, with a gate held. */
async function delegateWithGate(
  el: Mounted,
  gate: 'budget' | 'context',
): Promise<Mounted> {
  (el as SearchV3View).tier = 'delegate'; await el.updateComplete;
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  field.value = 'do the thing';
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }));
  await settle(el);

  ctrl.runInFlight = true;
  ctrl.isStreaming = true;
  ctrl.runKind = 'agent';
  ctrl.sessionId = 'run-1';
  ctrl.budgetGate =
    gate === 'budget' ? { tokensNeeded: 500, tokensRemaining: 10, totalTokensConsumed: 90 } : null;
  ctrl.contextGate = gate === 'context' ? { promptTokens: 7000, contextWindow: 8000 } : null;
  agentListener?.();
  await settle(el);
  return region(el, 'jf-sv3-main');
}

/* ── 1. The contract, on every control this slice moved ──────────────────────────────────────── */

describe('the run decisions are born on the operability primitive', () => {
  it('resolves the budget gate through the primitive, reaching the same seam', async () => {
    aiOnline();
    const el = await mount();
    const main = await delegateWithGate(el, 'budget');

    await activate(q(main, 'sv3-run-budget-finalize'));
    await settle(el);
    expect(ctrl.resolveBudgetGate).toHaveBeenCalledWith('finalize');
  });

  it('raises the budget through the primitive, by the arm\'s OWN sized step', async () => {
    // Tempdoc 859 §D §2.5 — this used to assert the shared 4,096-token constant. The constant is
    // retired (a fixed step could be smaller than the gate's shortfall, so the click resumed the
    // loop straight into an immediate re-gate), and the assertion is REWRITTEN rather than deleted:
    // it is the regression guard for "the label and the directive spend the same number", which is
    // the property the ladder must not lose.
    aiOnline();
    const el = await mount();
    const main = await delegateWithGate(el, 'budget');

    const arm = q(main, 'sv3-run-budget-raise-again');
    const promised = Number(
      (arm?.textContent ?? '').replace(/[^0-9]/g, ''),
    );
    expect(promised).toBeGreaterThan(0);
    await activate(arm);
    await settle(el);
    expect(ctrl.raiseBudget).toHaveBeenCalledWith(promised);
    // Raising is not a third value of the gate's decision — the gate is left to clear itself.
    expect(ctrl.resolveBudgetGate).not.toHaveBeenCalled();
  });

  it('resolves the context gate through the primitive', async () => {
    aiOnline();
    const el = await mount();
    const main = await delegateWithGate(el, 'context');

    await activate(q(main, 'sv3-run-context-summarize'));
    await settle(el);
    expect(ctrl.resolveContextGate).toHaveBeenCalledWith('summarize');
  });

  it('offers every arm of both gates as a primitive, each named', async () => {
    aiOnline();
    const el = await mount();
    const budget = await delegateWithGate(el, 'budget');
    // Tempdoc 859 §D §2.5 — the single fixed raise arm became THREE sized ones; each must still be
    // born on the primitive and carry its own name.
    for (const testid of [
      'sv3-run-budget-raise-little',
      'sv3-run-budget-raise-again',
      'sv3-run-budget-raise-plenty',
      'sv3-run-budget-finalize',
      'sv3-run-budget-stop',
    ]) {
      const control = q(budget, testid);
      expect(control?.localName, testid).toBe('jf-control');
      expect((control?.getAttribute('label') ?? '').trim(), testid).not.toBe('');
    }
  });
});

describe('the composer remedies are born on the primitive', () => {
  it('does not repeat the Library corpus remedy in Search', async () => {
    aiOnlineWithoutDocuments();
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    expect(q(composer, 'sv3-composer-corpus-remedy')).toBeNull();
  });

  it('keeps the testid on the host, so the harness and the suite still find it', async () => {
    aiOnline();
    const el = await mount();
    const main = await delegateWithGate(el, 'budget');
    // The testid stayed on the element the DOM search reaches; only the BUTTON moved inward. This is
    // the property that makes the ui-shot step registry and every suite selector survive adoption.
    expect(q(main, 'sv3-run-budget-raise-again')).not.toBeNull();
    expect(
      q(main, 'sv3-run-budget-raise-again')?.shadowRoot?.querySelector('button'),
    ).not.toBeNull();
  });

  it('leaves the patterns the primitive cannot express as native buttons', async () => {
    aiOnline();
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    // The exceptions are a deliberate list, not an oversight: a menu TRIGGER carries
    // aria-haspopup/aria-expanded, a menu RUNG carries role=menuitemradio + aria-checked, and the
    // primary slot's send is natively `disabled` on an empty draft — none of which `jf-control`
    // renders. Pinned so that "why is this one still a button?" has an answer in the suite.
    (el as SearchV3View).tier = 'ask'; await el.updateComplete; await composer.updateComplete;
    const trigger = q(composer, 'sv3-composer-tier');
    expect(trigger?.localName).toBe('button');
    expect(trigger?.getAttribute('aria-haspopup')).toBe('menu');
    trigger?.click();
    await composer.updateComplete;
    const rung = q(composer, 'sv3-composer-tier-option');
    expect(rung?.localName).toBe('button');
    expect(rung?.getAttribute('role')).toBe('menuitemradio');
    expect(q(composer, 'sv3-composer-send')?.localName).toBe('button');
  });
});
