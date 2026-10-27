// @vitest-environment happy-dom

/**
 * Search v3 FOLLOWS the conversation in the address — tempdoc 864 Layer 3(a) / PR C, window half.
 *
 * `router/sv3ConversationUrl.test.ts` proves the address round-trips: a claim pushes a history
 * entry, a Back press restores the store. That is only half an undo. The reader is looking at a
 * transcript, and a Back that moved a pointer without moving the screen would be the same defect
 * one layer down — so this asserts the other half against the real window: the store moves, and the
 * conversation on screen moves with it.
 *
 * The store is the REAL one and it is moved from OUTSIDE the window, which is exactly how a
 * popstate reaches it (`NavigationHandler.applyState` → the sv3 adapter's `restore`). A case that
 * clicked a row instead would pass with the whole projection deleted.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { createNavigationHandler } from '../../router/navigationHandler.js';
import { resetSearchState } from '../../state/searchState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import {
  __resetConversationListForTest,
  getConversationListState,
  restoreActiveConversation,
  setActiveConversation,
  subscribeConversationList,
  type ConversationListChange,
} from '../../state/conversationListStore.js';
import {
  __resetBootstrapForTest,
  registerCoreStores,
} from '../../router/bootstrap.js';
import { __resetStoreRegistryForTest } from '../../router/storeRegistry.js';
import { __resetSurfaceSchemasForTest } from '../../router/surfaceSchemas.js';
import {
  __flushPendingWriteForTest,
  activateProjection,
  deactivateProjection,
} from '../../router/URLProjector.js';
import {
  readLastViewedConversation,
  setLastViewedConversation,
} from '../../controllers/lastViewedConversation.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';

type Mounted = SearchV3View & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

const conversationRow = (id: string, firstUserMessage: string): Record<string, unknown> => ({
  sessionId: id,
  title: null,
  titleSource: null,
  createdAtMs: 1_700_000_000_000,
  lastActiveAtMs: 1_700_000_000_000,
  firstUserMessage,
  shapeId: 'core.unified-chat',
});

const threadFor = (id: string, answer: string): Record<string, unknown> => ({
  conversationId: id,
  events: [
    {
      id: `${id}-1`,
      occurredAt: '2026-08-25T10:00:00Z',
      kind: 'USER_MESSAGE',
      originator: 'user',
      content: `question in ${id}`,
      attributes: {},
    },
    {
      id: `${id}-2`,
      occurredAt: '2026-08-25T10:00:01Z',
      kind: 'ASSISTANT_MESSAGE',
      originator: 'agent',
      content: answer,
      attributes: {},
    },
  ],
});

function stubFetch(): void {
  fetchMock.mockImplementation(async (url: unknown) => {
    const href = String(url);
    if (href.includes('/api/chat/runs/live')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ runs: [] }) };
    }
    if (href.includes('/api/chat/conversations') && href.endsWith('/history')) {
      return { ok: true, status: 200, json: () => Promise.resolve({}) };
    }
    if (href.includes('/api/chat/conversations')) {
      return {
        ok: true,
        status: 200,
        json: () =>
          Promise.resolve({
            sessions: [conversationRow('conv-a', 'about A'), conversationRow('conv-b', 'about B')],
          }),
      };
    }
    if (href.includes('/api/thread/')) {
      const id = decodeURIComponent(href.slice(href.lastIndexOf('/api/thread/') + 12));
      return { ok: true, status: 200, json: () => Promise.resolve(threadFor(id, `answer ${id}`)) };
    }
    return { ok: true, status: 200, json: () => Promise.resolve({ results: [] }) };
  });
}

function aiOnline(): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

async function mount(): Promise<Mounted> {
  const el = document.createElement('jf-sv3-window') as Mounted;
  document.body.appendChild(el);
  await el.updateComplete;
  await settle(el);
  return el;
}

async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 8; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

/** The answer text the window is currently rendering, across every turn on screen. */
const transcript = (el: Mounted): string =>
  el.sessions.sessions
    .filter((s) => s.id === el.sessions.activeId)
    .flatMap((s) => s.turns.map((t) => t.answer))
    .join(' ');

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  __resetAiStateForTest();
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  stubFetch();
  aiOnline();
});

afterEach(() => {
  for (const el of [...document.querySelectorAll('jf-sv3-window')]) el.remove();
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('the window follows the conversation in the address (864 PR C)', () => {
  it('a store restore from outside the window swaps the transcript', async () => {
    setActiveConversation('conv-a');
    const el = await mount();
    expect(el.sessions.activeId).toBe('conv-a');
    expect(transcript(el)).toContain('answer conv-a');

    // What a Back press does: the NavigationHandler distributes the address to the sv3 adapter,
    // which restores the store. Nothing touches the window directly.
    restoreActiveConversation('conv-b');
    await settle(el);

    expect(el.sessions.activeId).toBe('conv-b');
    expect(transcript(el)).toContain('answer conv-b');
  });

  it('following the address also moves the per-tab reload pointer', async () => {
    setActiveConversation('conv-a');
    const el = await mount();

    restoreActiveConversation('conv-b');
    await settle(el);

    // Otherwise a Back followed by a reload would land back on the conversation the reader left.
    expect(readLastViewedConversation()).toBe('conv-b');
  });

  it('a restore to no conversation returns the window to the hero', async () => {
    setActiveConversation('conv-a');
    const el = await mount();
    expect(el.sessions.activeId).toBe('conv-a');

    restoreActiveConversation(null);
    await settle(el);

    expect(el.sessions.activeId).toBeNull();
  });

  it('the address outranks the per-tab reload pointer on mount', async () => {
    // A deep link is distributed to the store BEFORE the window mounts, so the pointer must not
    // second-guess it — the reader followed a link to conv-b, not to whatever they read last.
    setLastViewedConversation('conv-a');
    restoreActiveConversation('conv-b');

    const el = await mount();

    expect(el.sessions.activeId).toBe('conv-b');
  });

  it('with nothing in the address the reload pointer still restores, without re-claiming', async () => {
    setLastViewedConversation('conv-a');
    expect(getConversationListState().activeId).toBeNull();

    const el = await mount();

    expect(el.sessions.activeId).toBe('conv-a');
    expect(getConversationListState().activeId).toBe('conv-a');
  });
});

/**
 * Handling a popstate must not WRITE history — independent review of PR #556, finding F1.
 *
 * The first cut had the hero branch call `startFreshSession`, which claims. A claim during popstate
 * handling is indistinguishable to the projector from a fresh one, so it pushed the bare address
 * onto a stack the browser had already walked back through — truncating the forward tail, and
 * Forward silently lost the conversation. It was masked by production listener order and by the
 * `isCurrentUrl` downgrade, neither of which is a guarantee.
 *
 * So the ADVERSE order is what these cases run in: the window subscribes to the store BEFORE the
 * projector's adapter does, which is the order the reviewer reproduced the failure in.
 */
describe('a popstate landing on the hero writes no history (F1)', () => {
  let pushSpy: ReturnType<typeof vi.spyOn>;
  let replaceSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    registerCoreStores();
    pushSpy = vi.spyOn(window.history, 'pushState').mockImplementation(() => {
      /* swallow */
    });
    replaceSpy = vi.spyOn(window.history, 'replaceState').mockImplementation(() => {
      /* swallow */
    });
  });

  afterEach(() => {
    deactivateProjection();
    pushSpy.mockRestore();
    replaceSpy.mockRestore();
  });

  /** Mount first, project second — the window's store listener lands ahead of the adapter's. */
  async function mountInAdverseOrder(): Promise<Mounted> {
    setActiveConversation('conv-a');
    const el = await mount();
    activateProjection('core.search-v3-surface');
    return el;
  }

  it('adds no history entry when the address returns to the hero', async () => {
    const el = await mountInAdverseOrder();
    expect(el.sessions.activeId).toBe('conv-a');
    const pushesBefore = pushSpy.mock.calls.length;

    // What `NavigationHandler.applyState` does on a Back that lands on the bare surface address.
    restoreActiveConversation(null);
    await settle(el);
    __flushPendingWriteForTest();

    expect(el.sessions.activeId).toBeNull();
    expect(pushSpy.mock.calls.length).toBe(pushesBefore);
    // The URL still has to be corrected to the hero address — the fix removes the ENTRY, not the
    // projection, and a case asserting only "no push" would also pass with the projection dead.
    expect(replaceSpy.mock.calls.at(-1)?.[2]).toBe(
      '#justsearch://surface/core.search-v3-surface',
    );
  });

  it('makes no claim at all while following the address to the hero', async () => {
    // The order-independent form of the case above: no claim is emitted, so no listener ordering
    // can turn one into a push. This is the property; the push assertion is the consequence.
    const el = await mountInAdverseOrder();
    const reasons: ConversationListChange[] = [];
    const unsubscribe = subscribeConversationList((_s, change) => reasons.push(change));
    reasons.length = 0;

    restoreActiveConversation(null);
    await settle(el);
    unsubscribe();

    expect(reasons).not.toContain('claim');
  });
});

/** New is a completed departure only after all existing owners acknowledge it. */
describe('New retirement at the public boundary', () => {
  const rawKey = 'justsearch.draft.search-v3.composer';
  const pointerKey = 'justsearch.lastViewedConversation.v1';
  let el: Mounted;
  const field = (): HTMLTextAreaElement => el.shadowRoot!.querySelector('jf-sv3-composer')!.shadowRoot!
    .querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-input"]')!;
  const newButton = (): HTMLButtonElement => el.shadowRoot!.querySelector('jf-sv3-sidebar')!.shadowRoot!
    .querySelector<HTMLButtonElement>('[data-testid="sv3-sidebar-new"]')!;
  beforeEach(async () => {
    __resetStoreRegistryForTest(); __resetSurfaceSchemasForTest(); __resetBootstrapForTest();
    deactivateProjection(); registerCoreStores();
    setActiveConversation('conv-a'); el = await mount(); activateProjection('core.search-v3-surface');
    __flushPendingWriteForTest();
    field().value = 'current prepared B'; field().dispatchEvent(new Event('input')); await settle(el);
    window.dispatchEvent(new Event('pagehide')); await settle(el);
    expect(localStorage.getItem(rawKey)).toBe('current prepared B');
    expect(readLastViewedConversation()).toBe('conv-a');
  });
  afterEach(() => deactivateProjection());

  it('N14 C2 settles the accepted New address before an immediate reload can read it', async () => {
    newButton().click(); await el.updateComplete;
    expect(field().value).toBe('');
    expect(window.location.hash).toBe('#justsearch://surface/core.search-v3-surface');
    expect(readLastViewedConversation()).toBeNull();
    expect(localStorage.getItem(rawKey)).toBeNull();
  });

  it.each(['throw', 'nonack', 'read', 'successor'])('N14 C3 raw retirement %s refuses without losing B or another writer', async fault => {
    const get = localStorage.getItem.bind(localStorage);
    const remove = localStorage.removeItem.bind(localStorage);
    const getDescriptor = Object.getOwnPropertyDescriptor(localStorage, 'getItem');
    const removeDescriptor = Object.getOwnPropertyDescriptor(localStorage, 'removeItem');
    const cleanup = () => {
      if (getDescriptor) Object.defineProperty(localStorage, 'getItem', getDescriptor); else Object.defineProperty(localStorage, 'getItem', { configurable: true, writable: true, value: get });
      if (removeDescriptor) Object.defineProperty(localStorage, 'removeItem', removeDescriptor); else Object.defineProperty(localStorage, 'removeItem', { configurable: true, writable: true, value: remove });
    };
    try {
    if (fault === 'successor') localStorage.setItem(rawKey, 'other writer sole A');
    if (fault === 'read') Object.defineProperty(localStorage, 'getItem', { configurable: true, writable: true, value: (key: string) => {
      if (key === rawKey) throw new DOMException('owned read failure', 'SecurityError'); return get(key);
    } });
    else Object.defineProperty(localStorage, 'removeItem', { configurable: true, writable: true, value: (key: string) => {
      if (key === rawKey && fault !== 'successor') {
        if (fault === 'throw') throw new DOMException('owned removal failure', 'SecurityError');
        return;
      }
      remove(key);
    } });
    newButton().click(); await settle(el);
    expect(field().value).toBe('current prepared B');
    expect(get(rawKey)).toBe(fault === 'successor' ? 'other writer sole A' : 'current prepared B');
    window.dispatchEvent(new Event('pagehide')); await settle(el);
    expect(get(rawKey)).toBe(fault === 'successor' ? 'other writer sole A' : 'current prepared B');
    expect(readLastViewedConversation()).toBe('conv-a');
    expect(el.shadowRoot!.querySelector('[data-testid="sv3-residency-notice"]')?.textContent).toMatch(/New.*not.*confirm/i);
    expect(getConversationListState().activeId).toBe('conv-a');
    } finally { cleanup(); }
  });

  it('N14 C8 refused history departure names the moved address and blocks ambient Send and Search', async () => {
    await createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} }).handle(
      { kind: 'navigate', target: 'core.search-v3-surface', state: { query: 'retained A', mode: 'ask', docIds: ['alpha.md'] } });
    await settle(el);
    const row = el.shadowRoot!.querySelector('jf-sv3-sidebar')!;
    row.dispatchEvent(new CustomEvent('sv3-session-select', { bubbles: true, composed: true, detail: { id: 'conv-a' } }));
    await settle(el); window.dispatchEvent(new Event('pagehide')); await settle(el);
    const key = 'justsearch.draft.attachment.v1.search-v3.main', before = sessionStorage.getItem(key);
    expect(JSON.parse(before!).payload.drafts).toHaveLength(2);
    restoreActiveConversation(null); await settle(el);
    expect(getConversationListState().activeId).toBeNull();
    expect(field().value).toBe('current prepared B'); expect(sessionStorage.getItem(key)).toBe(before);
    expect(el.shadowRoot!.querySelector('[data-testid="sv3-residency-notice"]')?.textContent).toMatch(/address.*moved.*view.*not/i);
    const calls = fetchMock.mock.calls.length;
    field().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true }));
    expect(el.shadowRoot!.querySelector('jf-sv3-palette')).not.toBeNull();
    el.shadowRoot!.querySelector('jf-sv3-palette')!.dispatchEvent(new CustomEvent('sv3-palette-run', { bubbles: true, composed: true, detail: { id: 'cmd-everything' } }));
    await settle(el);
    expect(fetchMock.mock.calls.slice(calls).filter(c => /\/(knowledge\/search|chat\/dispatch)/.test(String(c[0])))).toHaveLength(0);
    expect(sessionStorage.getItem(key)).toBe(before);
    restoreActiveConversation('conv-a'); await settle(el);
    expect(field().value).toBe('current prepared B');
  });

  it.each(['before-write', 'readback', 'schema-only'])('N14 C10 %s retains live work and distinguishes post-write uncertainty', async phase => {
    const composer = el.shadowRoot!.querySelector('jf-sv3-composer')!;
    let schema: HTMLTextAreaElement | null = null;
    if (phase === 'schema-only') {
      composer.dispatchEvent(new CustomEvent('sv3-tier-change', { bubbles: true, composed: true, detail: { tier: 'structured' } }));
      await settle(el);
      schema = composer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-schema"]')!;
      schema.value = '{"type":"object","properties":{"kept":{"type":"string"}}}'; schema.dispatchEvent(new Event('input'));
      field().value = ''; field().dispatchEvent(new Event('input')); await settle(el);
      window.dispatchEvent(new Event('pagehide')); await settle(el);
    }
    const key = 'justsearch.draft.attachment.v1.search-v3.main';
    const get = sessionStorage.getItem.bind(sessionStorage), set = sessionStorage.setItem.bind(sessionStorage);
    const getDescriptor = Object.getOwnPropertyDescriptor(sessionStorage, 'getItem');
    const setDescriptor = Object.getOwnPropertyDescriptor(sessionStorage, 'setItem');
    const before = get(key); let applied = false;
    try {
      Object.defineProperty(sessionStorage, 'setItem', { configurable: true, writable: true, value: (candidate: string, value: string) => {
        if (candidate === key && phase === 'before-write') throw new DOMException('owned write failure', 'QuotaExceededError');
        set(candidate, value); if (candidate === key) applied = true;
      } });
      Object.defineProperty(sessionStorage, 'getItem', { configurable: true, writable: true, value: (candidate: string) => {
        if (candidate === key && applied) throw new DOMException('owned post-write readback failure', 'SecurityError'); return get(candidate);
      } });
      newButton().click(); await settle(el);
      expect(field().value).toBe(phase === 'schema-only' ? '' : 'current prepared B');
      if (schema) expect(schema.value).toContain('kept');
      expect(readLastViewedConversation()).toBe('conv-a'); expect(getConversationListState().activeId).toBe('conv-a');
      const notice = el.shadowRoot!.querySelector('[data-testid="sv3-residency-notice"]')?.textContent ?? '';
      if (phase === 'before-write') { expect(get(key)).toBe(before); expect(notice).not.toContain('Partial'); }
      else { expect(get(key)).not.toBe(before); expect(notice).toMatch(/Partial, unconfirmed retirement/); expect(notice).toMatch(/saved settings may already have been retired/); }
      expect(localStorage.getItem(rawKey)).toBe(phase === 'schema-only' ? null : 'current prepared B');
    } finally {
      Object.defineProperty(sessionStorage, 'getItem', getDescriptor ?? { configurable: true, writable: true, value: get });
      Object.defineProperty(sessionStorage, 'setItem', setDescriptor ?? { configurable: true, writable: true, value: set });
    }
  });

  it('N14 C10 partial warning survives C8 origin return and a stale lifecycle write', async () => {
    await createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} }).handle(
      { kind: 'navigate', target: 'core.search-v3-surface', state: { query: 'older whole A', mode: 'ask', docIds: ['alpha.md'] } });
    await settle(el);
    el.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-select', { bubbles: true, composed: true, detail: { id: 'conv-a' } }));
    await settle(el); window.dispatchEvent(new Event('pagehide')); await settle(el);
    restoreActiveConversation(null); await settle(el);
    expect(el.shadowRoot!.querySelector('[data-testid="sv3-departure-notice"]')).not.toBeNull();
    const key = 'justsearch.draft.attachment.v1.search-v3.main';
    const get = sessionStorage.getItem.bind(sessionStorage), set = sessionStorage.setItem.bind(sessionStorage);
    const getDescriptor = Object.getOwnPropertyDescriptor(sessionStorage, 'getItem'), setDescriptor = Object.getOwnPropertyDescriptor(sessionStorage, 'setItem');
    const older = JSON.parse(get(key)!).payload.drafts.find((d: { text: string }) => d.text === 'older whole A');
    let applied = false;
    const restore = () => {
      Object.defineProperty(sessionStorage, 'getItem', getDescriptor ?? { configurable: true, writable: true, value: get });
      Object.defineProperty(sessionStorage, 'setItem', setDescriptor ?? { configurable: true, writable: true, value: set });
    };
    try {
      Object.defineProperty(sessionStorage, 'setItem', { configurable: true, writable: true, value: (candidate: string, value: string) => { set(candidate, value); if (candidate === key) applied = true; } });
      Object.defineProperty(sessionStorage, 'getItem', { configurable: true, writable: true, value: (candidate: string) => { if (candidate === key && applied) throw new DOMException('owned ACK read failure', 'SecurityError'); return get(candidate); } });
      newButton().click(); await settle(el);
      const partial = [...el.shadowRoot!.querySelectorAll('[role="alert"]')].find(n => n.textContent?.includes('Partial, unconfirmed retirement'))?.textContent;
      expect(partial).toContain('saved settings may already have been retired');
      expect(field().value).toBe('current prepared B');
      restore(); restoreActiveConversation('conv-a'); await settle(el);
      expect(el.shadowRoot!.querySelector('[data-testid="sv3-departure-notice"]')).toBeNull();
      window.dispatchEvent(new Event('pagehide')); await settle(el);
      expect([...el.shadowRoot!.querySelectorAll('[role="alert"]')].some(n => n.textContent === partial)).toBe(true);
      expect(JSON.parse(get(key)!).payload.drafts).toEqual(expect.arrayContaining([older]));
      expect(localStorage.getItem(rawKey)).toBe('current prepared B');
    } finally { restore(); }
  });

  it.each(['same-text-files', 'empty-schema'])('N14 C5 C6 retained %s proposal stays manual through New and fresh mount', async variant => {
    el.remove(); __resetDraftProvidersForTest();
    const text = variant === 'same-text-files' ? 'current prepared B' : '';
    const preparation = variant === 'same-text-files'
      ? { kind: 'ask', scope: { kind: 'files', paths: ['retained-alpha.md'] } }
      : { kind: 'structured', schema: '{"type":"object","properties":{"retained":{"type":"string"}}}', scope: { kind: 'files', paths: ['retained-schema.md'] } };
    const older = { place: null, text, preparation };
    localStorage.setItem(rawKey, 'current prepared B'); sessionStorage.removeItem(pointerKey);
    sessionStorage.setItem('justsearch.draft.attachment.v1.search-v3.main', JSON.stringify({ version: 1, incarnation: 'retained-whole-proposal', payload: { version: 2, task: null, drafts: [older] } }));
    setActiveConversation(null); el = await mount(); activateProjection('core.search-v3-surface');
    expect(field().value).toBe('current prepared B');
    newButton().click(); await settle(el); expect(field().value).toBe('');
    const key = 'justsearch.draft.attachment.v1.search-v3.main';
    expect(JSON.parse(sessionStorage.getItem(key)!).payload.drafts).toEqual(expect.arrayContaining([older]));
    el.remove(); __resetDraftProvidersForTest(); el = await mount();
    expect(field().value).toBe('');
    const before = fetchMock.mock.calls.length;
    const review = el.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-recovery-review-copy"]');
    expect(review).not.toBeNull(); review!.click(); await settle(el); expect(field().value).toBe(text);
    el.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-draft-review-confirm"]')!.click(); await settle(el);
    const composer = el.shadowRoot!.querySelector('jf-sv3-composer')!;
    if (variant === 'empty-schema') expect(composer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-schema"]')!.value).toContain('retained');
    expect(fetchMock.mock.calls.slice(before).filter(c => /\/(knowledge\/search|chat\/dispatch)/.test(String(c[0])))).toHaveLength(0);
    if (variant === 'same-text-files') {
      composer.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')!.click(); await settle(el);
      const requests = fetchMock.mock.calls.slice(before).filter(c => String(c[0]).includes('/chat/dispatch'));
      expect(requests).toHaveLength(1);
      expect(JSON.parse((requests[0]![1] as RequestInit).body as string).docIds).toEqual(['retained-alpha.md']);
    }
  });

  it.each(['throw', 'nonack'])('N14 C4 pointer retirement %s refuses and retains recoverable current settings', async fault => {
    const get = sessionStorage.getItem.bind(sessionStorage), remove = sessionStorage.removeItem.bind(sessionStorage);
    const descriptor = Object.getOwnPropertyDescriptor(sessionStorage, 'removeItem');
    try {
    Object.defineProperty(sessionStorage, 'removeItem', { configurable: true, writable: true, value: (key: string) => {
      if (key === pointerKey) {
        if (fault === 'throw') throw new DOMException('owned pointer failure', 'SecurityError');
        return;
      }
      remove(key);
    } });
    newButton().click(); await settle(el);
    expect(field().value).toBe('current prepared B');
    expect(get(pointerKey)).toBe('conv-a');
    expect(el.shadowRoot!.querySelector('[data-testid="sv3-residency-notice"]')?.textContent).toMatch(/New.*not.*confirm/i);
    expect(JSON.parse(get('justsearch.draft.attachment.v1.search-v3.main')!).payload.drafts)
      .toEqual(expect.arrayContaining([expect.objectContaining({ place: 'conv-a', text: 'current prepared B' })]));
    expect(getConversationListState().activeId).toBe('conv-a');
    } finally { if (descriptor) Object.defineProperty(sessionStorage, 'removeItem', descriptor); else Object.defineProperty(sessionStorage, 'removeItem', { configurable: true, writable: true, value: remove }); }
  });
});

it('N14 DS1 every nonempty raw string requires review before an edit can stage Search', async () => {
  deactivateProjection(); __resetStoreRegistryForTest(); __resetSurfaceSchemasForTest(); __resetBootstrapForTest(); registerCoreStores();
  setActiveConversation(null);
  localStorage.setItem('justsearch.draft.search-v3.composer', ' \n\t ');
  const el = await mount();
  const composer = el.shadowRoot!.querySelector('jf-sv3-composer')!;
  const input = composer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-input"]')!;
  expect(input.value).toBe(' \n\t ');
  input.value = 'Meaningful text after whitespace'; input.dispatchEvent(new Event('input'));
  input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true })); await settle(el);
  const searches = () => fetchMock.mock.calls.filter(c => String(c[0]).includes('/knowledge/search'));
  expect(searches()).toHaveLength(0);
  const review = el.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-draft-review-search"]');
  expect(review).not.toBeNull(); review!.click(); await settle(el); expect(searches()).toHaveLength(0);
  input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true })); await settle(el);
  expect(searches().length).toBeGreaterThan(0);
  expect(searches().every(c => JSON.parse((c[1] as RequestInit).body as string).query === 'Meaningful text after whitespace')).toBe(true);
});
