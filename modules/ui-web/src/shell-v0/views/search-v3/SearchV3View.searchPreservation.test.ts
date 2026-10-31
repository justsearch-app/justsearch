// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/** Search command preservation at the mounted window boundary; no catalog observations. */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import type { ResultsCard } from '../../components/searchResults/ResultsCard.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { resetSearchState } from '../../state/searchState.js';

const key = 'justsearch.draft.attachment.v1.search-v3.main';
const json = (body: unknown): Response => new Response(JSON.stringify(body));
let http: ReturnType<typeof vi.fn<(url: unknown, init?: RequestInit) => Promise<Response>>>;
const composer = (view: SearchV3View): Sv3Composer => view.shadowRoot!.querySelector('jf-sv3-composer')!;
const emit = (view: SearchV3View, name: string, detail: unknown = {}) =>
  composer(view).dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
async function settle(view: SearchV3View): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise(resolve => setTimeout(resolve, 0));
  await view.updateComplete; await composer(view).updateComplete;
}
async function mount(): Promise<SearchV3View> {
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  document.body.append(view); await settle(view); return view;
}
const searches = () => http.mock.calls.filter(call => String(call[0]).includes('/api/knowledge/search'));
function runSearch(view: SearchV3View, action: string): void {
  if (action === 'Enter') emit(view, 'sv3-composer-submit', { tier: 'search', query: 'current draft' });
  if (action === 'typing') emit(view, 'sv3-composer-draft', { previous: 'previous draft', text: 'current draft' });
  if (action === 'palette') view.shadowRoot!.querySelector('jf-sv3-palette')!.dispatchEvent(
    new CustomEvent('sv3-palette-run', { detail: { id: 'cmd-everything' } }));
}
function prepareAsk(view: SearchV3View, action: 'card' | 'chosen'): void {
  if (action === 'card') emit(view, 'card-scope-file', { path: '/chosen.txt' });
  else emit(view, 'sv3-results-ask');
}
async function card(view: SearchV3View): Promise<ResultsCard> {
  const main = view.shadowRoot!.querySelector('jf-sv3-main')!;
  await main.updateComplete;
  const results = main.shadowRoot!.querySelector('jf-sv3-results')!;
  await results.updateComplete;
  const found = results.shadowRoot!.querySelector<ResultsCard>('jf-results-card')!;
  await found.updateComplete; return found;
}
async function refuseStorage(fault: 'write' | 'stale' | 'readback', run: () => Promise<void>): Promise<void> {
  const storage = sessionStorage;
  const get = storage.getItem.bind(storage), set = storage.setItem.bind(storage);
  const descriptors = [
    Object.getOwnPropertyDescriptor(storage, 'getItem') ?? { configurable: true, value: get },
    Object.getOwnPropertyDescriptor(storage, 'setItem') ?? { configurable: true, value: set },
  ];
  let written = false, interceptions = 0;
  if (fault === 'stale') set(key, 'another owner keeps these bytes');
  Object.defineProperty(storage, 'setItem', { configurable: true, value: (candidate: string, value: string) => {
    if (candidate === key && fault === 'write') { interceptions++; throw new Error('storage refused'); }
    set(candidate, value); if (candidate === key) written = true;
  } });
  Object.defineProperty(storage, 'getItem', { configurable: true, value: (candidate: string) => {
    if (candidate === key && (fault === 'stale' || (written && fault === 'readback'))) {
      interceptions++;
      if (fault === 'readback') return 'unconfirmed readback';
    }
    return get(candidate);
  } });
  try { await run(); expect(interceptions).toBe(1); } finally {
    ['getItem', 'setItem'].forEach((name, i) => {
      Object.defineProperty(storage, name, descriptors[i]!);
    });
  }
}
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); resetSearchState();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetAiStateForTest();
  http = vi.fn(async (url: unknown) => String(url).includes('/api/knowledge/search')
    ? json({ results: [{ id: 'chosen', fields: { path: '/chosen.txt' } }], totalHits: 1, matchCount: 1 })
    : String(url).includes('/api/indexing/forgets?') ? json({ items: [], complete: true, nextId: null }) : json({}));
  vi.stubGlobal('fetch', http);
  __feedForTest({ inference: { available: true } as never, status: {
    inference: { chatEnabledSpec: true }, worker: { core: { indexedDocuments: 42 } },
    operating: { chat: { state: 'READY' } },
    readiness: { composites: { aiFeatures: { state: 'READY', reasonCodes: [] } } },
  } as unknown as StatusSnapshot });
  __feedContactForTest();
});
afterEach(() => {
  document.body.replaceChildren(); resetSearchState(); __resetAiStateForTest();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});

it.each(['Enter', 'typing', 'palette'].flatMap(action =>
  (['write', 'stale', 'readback'] as const).map(fault => ({ action, fault }))))(
  '$action refuses Search when attachment acknowledgement fails ($fault)', async ({ action, fault }) => {
    const view = await mount(); composer(view).draft = 'current draft'; await settle(view);
    const place = view.sessions.activeId;
    await refuseStorage(fault, async () => {
      runSearch(view, action);
      await settle(view); await new Promise(resolve => setTimeout(resolve, 230));
    });
    expect(view.sessions.activeId).toBe(place);
    expect(composer(view).draft).toBe('current draft');
    expect(view.shadowRoot!.querySelector('jf-sv3-main')!.shadowRoot!.querySelector('jf-sv3-results')).toBeNull();
    expect(searches()).toHaveLength(0);
    expect(http.mock.calls.filter(call => String(call[0]).includes('/dispatch'))).toHaveLength(0);
    if (fault === 'stale') expect(sessionStorage.getItem(key)).toBe('another owner keeps these bytes');
  });

it.each(['Enter', 'typing', 'palette'])('%s searches when attachment storage is writable', async action => {
  const view = await mount(); composer(view).draft = 'current draft'; await settle(view);
  runSearch(view, action);
  await settle(view); await new Promise(resolve => setTimeout(resolve, 230));
  expect(searches()).toHaveLength(1);
  expect((await card(view)).snapshot!.results).toHaveLength(1);
  expect(composer(view).draft).toBe('current draft');
});

it.each(['Enter', 'typing'] as const)('entering results from %s refuses a failed second attachment acknowledgement', async action => {
  const view = await mount(); composer(view).draft = 'current draft'; await settle(view);
  const place = view.sessions.activeId;
  const storage = sessionStorage, set = storage.setItem.bind(storage);
  const descriptor = Object.getOwnPropertyDescriptor(storage, 'setItem') ?? { configurable: true, value: set };
  let attempts = 0;
  Object.defineProperty(storage, 'setItem', { configurable: true, value: (candidate: string, value: string) => {
    if (candidate === key && ++attempts === 2) throw new Error('results-place retention refused');
    set(candidate, value);
  } });
  try {
    runSearch(view, action);
    await settle(view); await new Promise(resolve => setTimeout(resolve, 230));
    expect(attempts).toBe(2);
    expect(JSON.parse(storage.getItem(key)!).payload.drafts[0]).toMatchObject({ place, text: 'current draft' });
    expect(view.sessions.activeId).toBe(place); expect(composer(view).draft).toBe('current draft');
    expect(view.shadowRoot!.querySelector('jf-sv3-main')!.shadowRoot!.querySelector('jf-sv3-results')).toBeNull();
    expect(searches()).toHaveLength(0);
  } finally { Object.defineProperty(storage, 'setItem', descriptor); }
});

it.each(['card', 'chosen'] as const)('preparing %s-file Ask keeps Search preparation when storage refuses', async action => {
  const view = await mount(); composer(view).draft = 'find files';
  emit(view, 'sv3-composer-submit', { tier: 'search', query: 'find files' }); await settle(view);
  emit(view, 'card-selection', { ids: ['chosen'] }); await settle(view);
  await refuseStorage('write', async () => {
    prepareAsk(view, action);
    await settle(view);
  });
  expect(composer(view).tier).toBe('search'); expect(composer(view).scopeLabel).toBe('');
  expect(composer(view).draft).toBe('find files');
  expect(http.mock.calls.filter(call => String(call[0]).includes('/dispatch'))).toHaveLength(0);
});

it.each(['card', 'chosen'] as const)('preparing %s-file Ask succeeds when attachment storage is writable', async action => {
  const view = await mount(); composer(view).draft = 'find files';
  emit(view, 'sv3-composer-submit', { tier: 'search', query: 'find files' }); await settle(view);
  emit(view, 'card-selection', { ids: ['chosen'] }); await settle(view);
  prepareAsk(view, action); await settle(view);
  expect(composer(view).tier).toBe('ask'); expect(composer(view).scopeLabel).toBe('chosen.txt');
  expect(composer(view).draft).toBe('find files');
  expect(http.mock.calls.filter(call => String(call[0]).includes('/dispatch'))).toHaveLength(0);
});

it('blank palette Search changes preparation without submitting', async () => {
  const view = await mount(); emit(view, 'sv3-tier-change', { tier: 'ask' }); await settle(view);
  composer(view).draft = '  ';
  view.shadowRoot!.querySelector('jf-sv3-palette')!.dispatchEvent(
    new CustomEvent('sv3-palette-run', { detail: { id: 'cmd-everything' } }));
  await settle(view);
  expect(composer(view).tier).toBe('search'); expect(composer(view).draft).toBe('  ');
  expect(searches()).toHaveLength(0);
});

it('obsolete capability reads cannot enable Forget after disconnect, reconnect or API-base change', async () => {
  const held: Array<(response: Response) => void> = [];
  const signals: AbortSignal[] = [];
  const original = http.getMockImplementation()!;
  http.mockImplementation((url: unknown, init?: RequestInit) => String(url).includes('/api/indexing/forgets?')
    ? new Promise<Response>(resolve => { held.push(resolve); signals.push(init!.signal!); }) : original(url));
  const view = await mount(); composer(view).draft = 'find files';
  emit(view, 'sv3-composer-submit', { tier: 'search', query: 'find files' }); await settle(view);
  expect((await card(view)).forgetAvailable).toBe(false);
  view.remove(); expect(signals[0]!.aborted).toBe(true);
  document.body.append(view); await settle(view);
  held[0]!(json({ items: [], complete: true, nextId: null })); await settle(view);
  expect((await card(view)).forgetAvailable).toBe(false);
  view.apiBase = 'http://different-host'; await settle(view);
  expect(signals[1]!.aborted).toBe(true);
  held[1]!(json({ items: [], complete: true, nextId: null })); await settle(view);
  expect((await card(view)).forgetAvailable).toBe(false);
  held[2]!(json({ items: [], complete: true, nextId: null })); await settle(view);
  expect((await card(view)).forgetAvailable).toBe(true);
});

it.each(['card', 'chosen'] as const)('a whitespace-only %s-file path cannot prepare unrestricted Ask', async action => {
  const original = http.getMockImplementation()!;
  http.mockImplementation((url, init) => String(url).includes('/api/knowledge/search')
    ? Promise.resolve(json({ results: [{ id: 'blank-path', fields: { path: '   ' } }], totalHits: 1, matchCount: 1 })) : original(url, init));
  const view = await mount(); composer(view).draft = 'find files';
  emit(view, 'sv3-composer-submit', { tier: 'search', query: 'find files' }); await settle(view);
  emit(view, 'card-selection', { ids: ['blank-path'] }); await settle(view);
  if (action === 'card') emit(view, 'card-scope-file', { path: '   ' });
  else emit(view, 'sv3-results-ask');
  await settle(view);
  expect(composer(view).tier).toBe('search'); expect(composer(view).scopeLabel).toBe('');
  expect(composer(view).draft).toBe('find files');
  expect(http.mock.calls.filter(call => String(call[0]).includes('/dispatch'))).toHaveLength(0);
});

it.each([
  { id: 'wrong-id', path: '/chosen.txt' },
  { id: 'chosen', path: '/different.txt' },
])('Forget refuses a target whose hit id and path do not both match ($id, $path)', async target => {
  const view = await mount(); composer(view).draft = 'find files';
  emit(view, 'sv3-composer-submit', { tier: 'search', query: 'find files' }); await settle(view);
  emit(view, 'card-selection', { ids: ['chosen'] }); await settle(view);
  emit(view, 'card-forget', { ...target, scope: 'FILE', invoker: null }); await settle(view);
  expect(http.mock.calls.filter(call => String(call[0]).includes('/api/operations/'))).toHaveLength(0);
  expect((await card(view)).snapshot!.results).toHaveLength(1);
  expect([...(await card(view)).selectedIds]).toEqual(['chosen']);
  expect(document.querySelector('jf-confirm-dialog')).toBeNull();
});

it('Forget folder refuses a filesystem root before invoking an operation', async () => {
  const view = await mount(); composer(view).draft = 'find files';
  emit(view, 'sv3-composer-submit', { tier: 'search', query: 'find files' }); await settle(view);
  emit(view, 'card-forget', { id: 'chosen', path: '/chosen.txt', scope: 'FOLDER', invoker: null }); await settle(view);
  expect(http.mock.calls.filter(call => String(call[0]).includes('/api/operations/'))).toHaveLength(0);
  expect(view.shadowRoot!.querySelector('[data-testid=sv3-forget-notice]')!.textContent)
    .toContain('Use Library root controls for a filesystem root.');
  expect((await card(view)).snapshot!.results).toHaveLength(1);
});
