// @vitest-environment happy-dom

/**
 * The composer's effort control (tempdoc 822 Phase F10) — the §4b ADAPTATION RATIFIED: the spec's
 * per-session provider picker maps to an effort control, because there is one local model and
 * therefore no provider to pick.
 *
 * The property that matters, and the reason this file exists at all: **the control is not chrome.**
 * A picker that renders three rungs and leaves the request identical is exactly the failure the
 * window's honesty laws are about, and it is invisible to any test that only looks at the DOM. So
 * the central case here is a MUTATION PROBE ON THE WIRE: the same question is asked on each rung and
 * the POSTed body is read back. If the rung stopped reaching `sv3EffortParams`, or the parameters
 * stopped reaching the body, the bodies would be identical and the probe fails.
 *
 * Also pinned: the rungs' parameters are the ones the BACKEND actually reads (the names are asserted
 * literally here, so a rename on either side shows up as a failure rather than as a silently ignored
 * field), the menu's radio semantics, and the Escape ladder's newest rung — an open menu is more
 * local than the pane.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
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
import { SV3_CITATION_OPEN } from './Sv3Main.js';
import {
  SV3_EFFORT_DEFAULT,
  SV3_EFFORT_OPTIONS,
  sv3EffortLabel,
  sv3EffortParams,
  type Sv3Effort,
} from './sv3-ask.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

function aiOnline(): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

/** A dispatch that completes immediately — this file cares about the REQUEST, not the stream. */
function stubDispatch(): void {
  const encoder = new TextEncoder();
  fetchMock.mockImplementation(async (url: unknown) => {
    if (!String(url).includes('/api/chat/dispatch')) {
      return { ok: true, status: 200, json: () => Promise.resolve({}) };
    }
    const frames = [
      { done: false, value: encoder.encode('event: chunk\ndata: {"text":"ok"}\n\n') },
      { done: false, value: encoder.encode('event: done\ndata: {}\n\n') },
      { done: true },
    ];
    return {
      ok: true,
      status: 200,
      body: { getReader: () => ({ read: async () => frames.shift(), releaseLock: () => {} }) },
    };
  });
}

/** Every issuance body (the store's auto-titling has its own `…/auto-title` route). */
const askBodies = (): Array<Record<string, unknown>> =>
  fetchMock.mock.calls
    .filter((call) => String(call[0]).includes('/api/chat/dispatch'))
    .map((call) => JSON.parse(String((call[1] as { body?: unknown }).body ?? '{}')))
    .filter((body: Record<string, unknown>) => body.shapeId === 'core.rag-ask');

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({}) });
  vi.stubGlobal('fetch', fetchMock);
  __resetAiStateForTest();
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

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

async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 6; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

const trigger = (composer: Mounted): HTMLButtonElement => {
  const found = q<HTMLButtonElement>(composer, 'sv3-composer-effort');
  if (!found) throw new Error('no effort control in the composer');
  return found;
};

const rungs = (composer: Mounted): HTMLButtonElement[] => [
  ...(composer.shadowRoot?.querySelectorAll<HTMLButtonElement>(
    '[data-testid="sv3-composer-effort-option"]',
  ) ?? []),
];

/** Open the menu and pick a rung the way a reader does — click, click. */
async function choose(el: Mounted, effort: Sv3Effort): Promise<void> {
  const composer = await region(el, 'jf-sv3-composer');
  trigger(composer).click();
  await composer.updateComplete;
  const rung = rungs(composer).find((item) => item.dataset.effort === effort);
  if (!rung) throw new Error(`no rung for ${effort}`);
  rung.click();
  await composer.updateComplete;
  await el.updateComplete;
}

async function ask(el: Mounted, draft: string): Promise<void> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  field.value = draft;
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  q<HTMLButtonElement>(composer, 'sv3-composer-send')?.click();
  await settle(el);
}

/* ── 1. The rungs are request parameters, not adjectives ─────────────────────────────────────── */

describe('every rung names parameters the backend reads', () => {
  /**
   * The names are asserted LITERALLY because they are a cross-language contract:
   * `enableThinking` → `ConversationEngine.java:781`, `maxTokens` → `:772`, `topK` →
   * `RAGContext.java:422`. A rename on either side has to fail here rather than degrade into a
   * silently ignored field, which is what an unknown key does on this endpoint.
   */
  it('maps each rung to the declared body fields, and the default rung to none', () => {
    expect(sv3EffortParams('quick')).toEqual({ enableThinking: false, maxTokens: 512 });
    expect(sv3EffortParams('thorough')).toEqual({
      enableThinking: true,
      maxTokens: 3072,
      topK: 12,
    });
    // Standard sends NOTHING: restating the backend's defaults here would fork them.
    expect(sv3EffortParams('standard')).toEqual({});
    expect(SV3_EFFORT_DEFAULT).toBe('standard');
  });

  it('offers exactly one default rung, each with a label and a description', () => {
    expect(SV3_EFFORT_OPTIONS.filter((option) => option.isDefault)).toHaveLength(1);
    expect(SV3_EFFORT_OPTIONS.find((option) => option.isDefault)?.id).toBe(SV3_EFFORT_DEFAULT);
    for (const option of SV3_EFFORT_OPTIONS) {
      expect(option.label.length, `${option.id} has no label`).toBeGreaterThan(0);
      expect(option.description.length, `${option.id} has no description`).toBeGreaterThan(0);
    }
  });
});

/* ── 2. THE MUTATION PROBE — the control changes the wire ────────────────────────────────────── */

describe('choosing a rung changes the request that leaves the window', () => {
  it('sends the chosen rungs parameters, and a different body per rung', async () => {
    aiOnline();
    stubDispatch();
    const el = await mount();

    await ask(el, 'default rung');
    await choose(el, 'thorough');
    await ask(el, 'raised rung');
    await choose(el, 'quick');
    await ask(el, 'lowered rung');

    const bodies = askBodies();
    expect(bodies).toHaveLength(3);
    const [standard, thorough, quick] = bodies as Array<Record<string, unknown>>;

    // The default rung leaves every sampling decision to the backend — the fields are ABSENT, not
    // present-and-defaulted, which is the difference between "we said nothing" and "we said 1024".
    expect(standard).not.toHaveProperty('enableThinking');
    expect(standard).not.toHaveProperty('maxTokens');
    expect(standard).not.toHaveProperty('topK');

    expect(thorough).toMatchObject({ enableThinking: true, maxTokens: 3072, topK: 12 });
    expect(quick).toMatchObject({ enableThinking: false, maxTokens: 512 });
    expect(quick).not.toHaveProperty('topK');

    // The probe's teeth: a control wired to nothing would leave these three identical.
    expect(JSON.stringify(thorough)).not.toBe(JSON.stringify(quick));
    expect(JSON.stringify(standard)).not.toBe(JSON.stringify(thorough));
  });

  it('keeps the shared builders own fields intact beside the rung', async () => {
    aiOnline();
    stubDispatch();
    const el = await mount();
    await choose(el, 'thorough');
    await ask(el, 'what is in the corpus');

    const [body] = askBodies();
    // The rung is ADDED to the shared body, never a replacement for it.
    expect(body).toMatchObject({ shapeId: 'core.rag-ask', question: 'what is in the corpus' });
    expect(body?.docIds).toEqual([]);
    expect(typeof body?.conversationId).toBe('string');
  });

  it('does not re-parameterise a request that has already left', async () => {
    aiOnline();
    stubDispatch();
    const el = await mount();
    await ask(el, 'first');
    const before = JSON.stringify(askBodies()[0]);
    await choose(el, 'thorough');
    await settle(el);
    // The body of the FIRST dispatch is a sent artefact; changing the rung cannot rewrite it.
    expect(JSON.stringify(askBodies()[0])).toBe(before);
  });
});

/* ── 3. The menu is a radio group, and the trigger is its value ──────────────────────────────── */

describe('the control presents the spec menu anatomy', () => {
  it('opens a labelled radio menu with the current rung checked and the default badged', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    expect(trigger(composer).getAttribute('aria-expanded')).toBe('false');
    expect(q(composer, 'sv3-composer-effort-menu')).toBeNull();

    trigger(composer).click();
    await composer.updateComplete;
    expect(trigger(composer).getAttribute('aria-expanded')).toBe('true');
    const menu = q(composer, 'sv3-composer-effort-menu');
    expect(menu?.getAttribute('role')).toBe('menu');
    expect(rungs(composer)).toHaveLength(SV3_EFFORT_OPTIONS.length);
    for (const rung of rungs(composer)) expect(rung.getAttribute('role')).toBe('menuitemradio');

    const checked = rungs(composer).filter((r) => r.getAttribute('aria-checked') === 'true');
    expect(checked).toHaveLength(1);
    expect(checked[0]?.dataset.effort).toBe(SV3_EFFORT_DEFAULT);
    // The spec badges the provider's own default rung.
    expect(
      composer.shadowRoot?.querySelectorAll('[data-testid="sv3-composer-effort-default"]'),
    ).toHaveLength(1);
    // Each rung says what it does, in the menu, next to the choice it explains.
    for (const option of SV3_EFFORT_OPTIONS) {
      const rung = rungs(composer).find((r) => r.dataset.effort === option.id);
      expect(rung?.textContent).toContain(option.description);
    }
  });

  it('closes on a pick and makes the trigger read the new value', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    expect(trigger(composer).textContent).toContain(sv3EffortLabel(SV3_EFFORT_DEFAULT));

    await choose(el, 'quick');
    await composer.updateComplete;
    expect(q(composer, 'sv3-composer-effort-menu')).toBeNull();
    expect(trigger(composer).textContent).toContain('Quick');
    expect(trigger(composer).getAttribute('aria-label')).toBe('Effort: Quick');
    // Re-opened, the menu shows the NEW rung as the checked one — one source of truth, not two.
    trigger(composer).click();
    await composer.updateComplete;
    const checked = rungs(composer).filter((r) => r.getAttribute('aria-checked') === 'true');
    expect(checked.map((r) => r.dataset.effort)).toEqual(['quick']);
  });

  it('survives the docking morph with its choice intact', async () => {
    const el = await mount();
    await choose(el, 'thorough');
    await (el as SearchV3View).setComposerState('docked');
    const composer = await region(el, 'jf-sv3-composer');
    // Compacted, the label is width-collapsed by CSS — the VALUE is still what the control holds.
    expect(trigger(composer).getAttribute('aria-label')).toBe('Effort: Thorough');
    expect(composer.getAttribute('effort')).toBe('thorough');
  });
});

/* ── 4. The Escape ladder gains its most local rung ──────────────────────────────────────────── */

describe('an open control menu is more local than the pane', () => {
  const escape = (from: HTMLElement): void => {
    from.dispatchEvent(
      new KeyboardEvent('keydown', {
        key: 'Escape',
        bubbles: true,
        composed: true,
        cancelable: true,
      }),
    );
  };

  it('closes the menu and leaves an open document open', async () => {
    const el = await mount();
    const main = await region(el, 'jf-sv3-main');
    main.dispatchEvent(
      new CustomEvent(SV3_CITATION_OPEN, {
        detail: { docPath: 'C:/corpus/notes.md', anchor: null, turnId: '', sourceIndex: -1 },
        bubbles: true,
        composed: true,
      }),
    );
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('jf-sv3-pane')).not.toBeNull();

    const composer = await region(el, 'jf-sv3-composer');
    trigger(composer).click();
    await composer.updateComplete;
    escape(rungs(composer)[0] as HTMLElement);
    await composer.updateComplete;
    await el.updateComplete;

    // The menu went; the pane — which the SAME keystroke closes when nothing more local is open —
    // stayed. Without the window's yield this is the F9 DEFECT-7 shape all over again.
    expect(q(composer, 'sv3-composer-effort-menu')).toBeNull();
    expect(el.shadowRoot?.querySelector('jf-sv3-pane')).not.toBeNull();

    // And the next Escape, now that nothing more local owns it, reaches the pane.
    escape(trigger(composer));
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('jf-sv3-pane')).toBeNull();
  });

  it('closes when focus leaves the control row', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    trigger(composer).click();
    await composer.updateComplete;
    expect(q(composer, 'sv3-composer-effort-menu')).not.toBeNull();

    const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-composer-input"]',
    );
    (rungs(composer)[0] as HTMLElement).dispatchEvent(
      new FocusEvent('focusout', { relatedTarget: field ?? null, bubbles: true, composed: true }),
    );
    await composer.updateComplete;
    expect(q(composer, 'sv3-composer-effort-menu')).toBeNull();
  });
});
