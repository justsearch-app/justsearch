// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/** Preservation checks at the session opening boundary, before its owner is extracted. */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import { __resetConversationListForTest, getConversationListState, restoreActiveConversation } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { readLastViewedConversation } from '../../controllers/lastViewedConversation.js';
import { resetSearchState } from '../../state/searchState.js';

const attachment = 'justsearch.draft.attachment.v1.search-v3.main';
let http: ReturnType<typeof vi.fn>;
const composer = (view: SearchV3View): Sv3Composer => view.shadowRoot!.querySelector('jf-sv3-composer')!;
// The actual event handlers are installed on the regions, so enter through those regions.
const select = (view: SearchV3View, id: string): void => {
  view.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-select', {
    detail: { id }, bubbles: true, composed: true,
  }));
};
async function settle(view: SearchV3View): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise(resolve => setTimeout(resolve, 0));
  await view.updateComplete; await composer(view).updateComplete;
}
async function mount(): Promise<SearchV3View> {
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  document.body.append(view); await settle(view);
  select(view, 'a'); await settle(view);
  composer(view).draft = 'retained follow-up';
  return view;
}
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); resetSearchState();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetAiStateForTest();
  http = vi.fn(async (input: unknown) => {
    const url = new URL(String(input), 'http://engine.test');
    if (url.pathname === '/api/chat/conversations') return Response.json({ sessions: ['a', 'b'].map(sessionId => ({
      sessionId, title: sessionId, titleSource: 'user', createdAtMs: 1, lastActiveAtMs: 2,
    })) });
    if (url.pathname.endsWith('/history')) return Response.json({ sessionId: url.pathname.split('/')[4], messages: [] });
    if (url.pathname.startsWith('/api/thread/')) return Response.json({ conversationId: url.pathname.split('/').at(-1), events: [] });
    return Response.json({ runs: [] });
  });
  vi.stubGlobal('fetch', http);
});
afterEach(() => {
  document.body.replaceChildren(); resetSearchState(); __resetAiStateForTest();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});

it.each(['row', 'version', 'address'].flatMap(path =>
  ['unavailable', 'nonack', 'stale', 'capacity'].map(fault => ({ path, fault }))))(
  '$path opening keeps the shown conversation when draft acknowledgement fails ($fault)', async ({ path, fault }) => {
    const view = await mount();
    const storage = sessionStorage, get = storage.getItem.bind(storage), set = storage.setItem.bind(storage);
    const descriptor = Object.getOwnPropertyDescriptor(storage, 'setItem')
      ?? { configurable: true, writable: true, value: set };
    const bytes = get(attachment);
    let writes = 0;
    if (fault === 'capacity') composer(view).draft = 'x'.repeat(300_000);
    Object.defineProperty(storage, 'setItem', { ...descriptor, value: (key: string, value: string) => {
      if (key !== attachment) { set(key, value); return; }
      writes++;
      if (fault === 'unavailable') throw new DOMException('unavailable', 'QuotaExceededError');
      if (fault === 'nonack') return;
      set(key, value);
      if (fault === 'stale') composer(view).draft = 'newer follow-up';
    } });
    http.mockClear();
    try {
      if (path === 'row') select(view, 'b');
      if (path === 'version') view.shadowRoot!.querySelector('jf-sv3-main')!.dispatchEvent(new CustomEvent('sv3-version-select', {
        detail: { sessionId: 'outside-sidebar' }, bubbles: true, composed: true,
      }));
      if (path === 'address') restoreActiveConversation('b');
      await settle(view);
      expect(view.sessions.activeId).toBe('a');
      expect(readLastViewedConversation()).toBe('a');
      expect(composer(view).draft).toBe(fault === 'capacity' ? 'x'.repeat(300_000)
        : fault === 'stale' ? 'newer follow-up' : 'retained follow-up');
      expect(http.mock.calls).toEqual([]);
      if (fault === 'nonack') expect(get(attachment)).toBe(bytes);
      if (fault === 'capacity') expect(writes).toBe(0);
      else expect(writes).toBeGreaterThan(0);
      expect(getConversationListState().activeId).toBe(path === 'address' ? 'b' : 'a');
    } finally { Object.defineProperty(storage, 'setItem', descriptor); }
  });

it('unknown rows do nothing; reselecting the active row refreshes both records without closing evidence', async () => {
  const view = await mount();
  view.paneDocPath = '/evidence.txt'; await settle(view);
  const ids = view.sessions.sessions.map(row => row.id);
  http.mockClear(); select(view, 'unknown'); await settle(view);
  expect(view.sessions.sessions.map(row => row.id)).toEqual(ids);
  expect(view.sessions.activeId).toBe('a'); expect(http.mock.calls).toEqual([]);
  select(view, 'a'); await settle(view);
  expect(view.sessions.sessions.map(row => row.id)).toEqual(ids);
  expect(view.paneDocPath).toBe('/evidence.txt');
  expect(http.mock.calls.map(call => new URL(String(call[0]), 'http://engine.test').pathname)).toEqual([
    '/api/thread/a', '/api/chat/conversations/a/history',
  ]);
});

it('A to B to A restores each conversation draft, tier, schema and file scope without sending', async () => {
  __feedForTest({ inference: { available: true } as never, status: {
    inference: { chatEnabledSpec: true }, worker: { core: { indexedDocuments: 42 } },
    operating: { chat: { state: 'READY' } },
    readiness: { composites: { aiFeatures: { state: 'READY', reasonCodes: [] } } },
  } as unknown as StatusSnapshot });
  __feedContactForTest();
  const view = await mount();
  const prepare = (name: string, detail: unknown): void => {
    composer(view).dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
  };
  prepare('sv3-tier-change', { tier: 'structured' }); await settle(view);
  (view as unknown as { search: { prepare(scope: { paths: string[] }): void } }).search.prepare({ paths: ['/a.txt'] });
  await settle(view);
  prepare('sv3-schema-change', { text: '{"type":"object"}' }); await settle(view);
  const scope = composer(view).scopeLabel;
  expect(scope).toContain('a.txt');
  expect(composer(view).draft).toBe('retained follow-up');
  expect(composer(view).tier).toBe('structured');
  expect(composer(view).schemaDraft).toBe('{"type":"object"}');
  select(view, 'b'); await settle(view);
  composer(view).draft = 'B follow-up';
  prepare('sv3-tier-change', { tier: 'delegate' }); await settle(view);
  select(view, 'a'); await settle(view);
  expect(composer(view).draft).toBe('retained follow-up');
  expect(composer(view).tier).toBe('structured');
  expect(composer(view).schemaDraft).toBe('{"type":"object"}');
  expect(composer(view).scopeLabel).toBe(scope);
  expect(view.shadowRoot!.querySelector('[data-testid=sv3-draft-review]')).toBeNull();
  select(view, 'b'); await settle(view);
  expect(composer(view).draft).toBe('B follow-up');
  expect(composer(view).tier).toBe('delegate');
  expect(composer(view).scopeLabel).toBe('');
  expect(http.mock.calls.filter(call => String(call[0]).includes('/dispatch'))).toEqual([]);
});
