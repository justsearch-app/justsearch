// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import { parkSearchV3Handoff, takeSearchV3Handoff } from '../../utils/compose.js';
import { __resetConversationListForTest, loadConversations, setConversationTitle, getConversationListState } from '../../state/conversationListStore.js';
import { __resetAiStateForTest, __feedForTest, __feedContactForTest } from '../../state/aiStateStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { resetSearchState, setQuery, submitSearch, getSearchState } from '../../state/searchState.js';
import { __resetSearchFiltersForTest, toggleFacetValue } from '../../state/searchFiltersState.js';
import { __resetUserStateForTest, createProfile, setActiveProfileId, getActiveProfileId } from '../../state/UserStateDocument.js';
import { __resetProfilesForTest, createProfileFromCurrent, activateProfile, getActiveProfileId as getWorkspaceProfileId } from '../../substrates/profiles/index.js';
import { __seedForTest } from '../../../i18n/resourceCatalog.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

let http: ReturnType<typeof vi.fn>;
let rows: Record<string, unknown>[];
let searches: Record<string, unknown>[];
let holdLists: boolean;
let heldLists: (() => void)[];
let holdSearches: boolean;
let heldSearches: (() => void)[];
let historyStatus: number;
const json = (data: unknown, status = 200): Response => new Response(JSON.stringify(data), { status });
const row = (id: string, at = 100, title: string | null = id): Record<string, unknown> => ({
  sessionId: id, title, createdAtMs: 1, lastActiveAtMs: at, firstUserMessage: `preview-${id}`, shapeId: 'core.rag-ask', messageCount: 2,
});
function lock(state: 'locked' | 'unlocked'): void {
  __feedForTest({ inference: { available: false } as never, status: {
    worker: { core: { indexedDocuments: 42 } }, conversationProtection: { state },
  } as unknown as StatusSnapshot });
  __feedContactForTest();
}
async function settle(el: SearchV3View): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise<void>(resolve => setTimeout(resolve, 0));
  await el.updateComplete; await composer(el).updateComplete;
}
const composer = (el: SearchV3View): Sv3Composer => el.shadowRoot!.querySelector('jf-sv3-composer')!;
const field = (el: SearchV3View): HTMLTextAreaElement => composer(el).shadowRoot!.querySelector('textarea')!;
const list = (el: SearchV3View): HTMLElement | null => composer(el).shadowRoot!.querySelector('[data-testid=sv3-recents]');
const options = (el: SearchV3View, kind?: string): HTMLElement[] => [...(list(el)?.querySelectorAll<HTMLElement>(`[role=option]${kind ? `[data-kind=${kind}]` : ''}`) ?? [])];
const labels = (el: SearchV3View, kind?: string): string[] => options(el, kind).map(item => item.textContent!.trim());
const emit = (el: SearchV3View, name: string, detail: unknown): void => { composer(el).dispatchEvent(new CustomEvent(name, { bubbles: true, composed: true, detail })); };
async function mount(): Promise<SearchV3View> {
  const el = document.createElement('jf-sv3-window'); document.body.append(el); await settle(el); return el;
}
async function type(el: SearchV3View, text: string): Promise<void> {
  field(el).value = text; field(el).dispatchEvent(new Event('input', { bubbles: true })); await settle(el);
}
async function key(el: SearchV3View, key: string, modifiers: KeyboardEventInit = {}): Promise<KeyboardEvent> {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, composed: true, cancelable: true, ...modifiers });
  field(el).dispatchEvent(event); await settle(el); return event;
}
async function focusEmpty(el: SearchV3View): Promise<void> {
  await type(el, ''); field(el).blur(); field(el).focus(); await settle(el);
}
async function search(el: SearchV3View, text: string): Promise<void> { await type(el, text); await key(el, 'Enter'); }
function clear(el: SearchV3View): void { list(el)!.querySelector<HTMLButtonElement>('[data-testid=sv3-recent-clear]')!.click(); }
const writes = (): unknown[][] => http.mock.calls.filter(c => ['POST', 'PUT', 'PATCH', 'DELETE'].includes(String(c[1]?.method)));

beforeEach(async () => {
  await __resetProfilesForTest();
  localStorage.clear(); sessionStorage.clear(); __resetConversationListForTest(); __resetUserStateForTest();
  __resetDraftProvidersForTest(); __resetDraftKeptForTest(); __resetAiStateForTest(); __resetSearchFiltersForTest(); resetSearchState();
  rows = []; searches = []; heldLists = []; holdLists = false; heldSearches = []; holdSearches = false; historyStatus = 200;
  __seedForTest({ 'search.recent': 'Recent', 'search.clear-recent': 'Clear recent',
    'search.conversations-locked': 'Conversations are locked', 'search.untitled-conversation': 'Untitled conversation' });
  http = vi.fn(async (url: unknown, init?: RequestInit) => {
    const href = String(url);
    if (href.includes('/api/knowledge/search')) {
      const body = JSON.parse(String(init?.body)); searches.push(body);
      const answer = () => json(body.query === 'failed' ? { error: 'Unavailable' } : body.query === '"' ? { error: 'Invalid query' } : { results: [], totalHits: 0 }, body.query === 'failed' ? 503 : body.query === '"' ? 400 : 200);
      return holdSearches ? new Promise<Response>(resolve => heldSearches.push(() => resolve(answer()))) : answer();
    }
    if (href.includes('/history')) return json({ sessionId: href.split('/conversations/')[1]!.split('/')[0], messages: [] }, historyStatus);
    if (href.includes('/api/chat/conversations?')) {
      const snapshot = structuredClone(rows);
      return holdLists ? new Promise<Response>(resolve => heldLists.push(() => resolve(json({ sessions: snapshot })))) : json({ sessions: snapshot });
    }
    if (href.includes('/api/thread/')) return json({ conversationId: href.split('/').at(-1), events: [] });
    return json({});
  });
  vi.stubGlobal('fetch', http); lock('unlocked');
});
afterEach(() => {
  takeSearchV3Handoff(); document.body.replaceChildren(); heldLists.forEach(release => release()); heldSearches.forEach(release => release());
  resetSearchState(); __resetConversationListForTest(); __resetAiStateForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks();
});

it('Y1 opens only for an empty focused Search bar and owns selection and dismiss keys', async () => {
  rows = [row('latest', 20), row('older', 10)]; const el = await mount(); await focusEmpty(el);
  expect(list(el), 'empty focused Search exposes Recent').not.toBeNull();
  expect(options(el).every(item => item.getAttribute('aria-selected') === 'false')).toBe(true);
  await key(el, 'ArrowDown'); expect(options(el)[0]!.getAttribute('aria-selected')).toBe('true');
  await key(el, 'ArrowUp'); expect(options(el)[1]!.getAttribute('aria-selected')).toBe('true');
  await key(el, 'Escape'); expect(list(el)).toBeNull(); expect(el.composerState).toBe('hero');
  await focusEmpty(el);
  const clearButton = list(el)!.querySelector<HTMLButtonElement>('[data-testid=sv3-recent-clear]')!;
  clearButton.focus(); clearButton.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true }));
  await settle(el); expect(list(el)).toBeNull(); expect(composer(el).shadowRoot!.activeElement).toBe(field(el));
  await focusEmpty(el); await key(el, 'Enter'); expect(searches).toHaveLength(0); expect(list(el)).not.toBeNull();
  await key(el, 'Enter', { isComposing: true }); expect(list(el)).toBeNull(); expect(searches).toHaveLength(0);
  await focusEmpty(el); expect((await key(el, 'Enter', { shiftKey: true })).defaultPrevented).toBe(false); expect(list(el)).toBeNull();
  for (const modifier of [{ ctrlKey: true }, { metaKey: true }]) {
    await focusEmpty(el); await key(el, 'Enter', modifier); expect(list(el)).toBeNull(); expect(searches).toHaveLength(0);
  }
  await focusEmpty(el); await type(el, 'typed'); expect(list(el)).toBeNull();
  await type(el, ''); emit(el, 'sv3-tier-change', { tier: 'ask' }); await settle(el); field(el).blur(); field(el).focus(); await settle(el); expect(list(el)).toBeNull();
});

it('Y2 records only accepted explicit Search actions with exact dedup and an eight item cap', async () => {
  const el = await mount();
  for (const text of ['one', 'two', 'three', 'four', 'five', 'six', 'seven', 'failed', '"']) await search(el, text);
  await focusEmpty(el); expect(labels(el, 'search')).toEqual(['"', 'failed', 'seven', 'six', 'five', 'four', 'three', 'two']);
  await search(el, '  three  '); await search(el, 'Three'); await search(el, 'three  words'); await search(el, 'three words');
  await type(el, 'palette'); el.shadowRoot!.querySelector('jf-sv3-palette')!.dispatchEvent(new CustomEvent('sv3-palette-run', { detail: { id: 'cmd-everything' } })); await settle(el);
  await focusEmpty(el); expect(labels(el, 'search').slice(0, 5)).toEqual(['palette', 'three words', 'three  words', 'Three', 'three']);
  const before = labels(el, 'search');
  await type(el, 'automatic'); await new Promise(resolve => setTimeout(resolve, 750));
  toggleFacetValue('file_kind', 'pdf'); await settle(el);
  setQuery('restored or plugin'); submitSearch(); await settle(el); await focusEmpty(el);
  expect(labels(el, 'search')).toEqual(before);
  options(el, 'search')[0]!.click(); await settle(el); await focusEmpty(el);
  expect(labels(el, 'search')).toEqual(before); // Selection promotes once, without duplicating.
});

it('Y3 replays operators with current restrictions and selects conversations through navigation', async () => {
  rows = [row('conversation')]; const el = await mount(); const text = 'title:"budget review" -draft author:Ada';
  await search(el, text); toggleFacetValue('file_kind', 'pdf'); await settle(el); await focusEmpty(el);
  expect(labels(el, 'search')).toContain(text);
  const before = searches.length; await key(el, 'ArrowDown'); await key(el, 'Enter');
  expect(field(el).value).toBe(text); expect(searches.slice(before)).toHaveLength(1);
  expect(searches.at(-1)).toMatchObject({ query: text, filters: { fileKind: ['pdf'] } });
  await focusEmpty(el); options(el, 'conversation')[0]!.click(); await settle(el);
  expect(getConversationListState().activeId).toBe('conversation');
  expect(http.mock.calls.some(c => String(c[0]).endsWith('/conversation/history'))).toBe(true);
  expect(http.mock.calls.some(c => String(c[0]).includes('/dispatch'))).toBe(false);
});

it.each(['retained question', ''])('Y3 protects a retained preparation with text %j during search and conversation replay', async text => {
  const path = text === '' ? 'retained-schema.md' : 'retained-alpha.md';
  const older = { place: null, text, preparation: text === ''
    ? { kind: 'structured', schema: '', scope: { kind: 'files', paths: [path] } }
    : { kind: 'ask', scope: { kind: 'files', paths: [path] } } };
  const key = 'justsearch.draft.attachment.v1.search-v3.main';
  rows = [row('destination')]; const el = await mount(); await search(el, 'replay'); await focusEmpty(el);
  parkSearchV3Handoff({ query: text, forceShapeId: text === '' ? 'core.extract' : 'core.rag-ask', docIds: [path] });
  await settle(el); parkSearchV3Handoff({ query: '' }); await settle(el); await focusEmpty(el);
  expect(labels(el, 'search')).toEqual(['replay']);
  options(el, 'search')[0]!.click(); await settle(el);
  expect(JSON.parse(sessionStorage.getItem(key)!).payload.drafts).toEqual(expect.arrayContaining([older]));
  await focusEmpty(el); options(el, 'conversation')[0]!.click(); await settle(el);
  expect(JSON.parse(sessionStorage.getItem(key)!).payload.drafts).toEqual(expect.arrayContaining([older]));
  expect(http.mock.calls.some(c => String(c[0]).includes('/dispatch'))).toBe(false);
});

it('Y4 Clear hides the full population with no backfill, rename reactivation or mutation', async () => {
  rows = Array.from({ length: 8 }, (_, i) => row(`conversation-${i}`, Date.now() - 1000 - i));
  const el = await mount(); await search(el, 'session query'); await focusEmpty(el);
  expect(options(el, 'conversation')).toHaveLength(5); expect(options(el, 'search')).toHaveLength(1);
  const before = writes().length; clear(el); await settle(el); expect(options(el)).toHaveLength(0); expect(writes()).toHaveLength(before);
  for (let i = 0; i < 2; i++) { await loadConversations(); await settle(el); expect(options(el)).toHaveLength(0); }
  rows[0]!.title = 'Renamed'; await loadConversations(); await settle(el); expect(options(el)).toHaveLength(0);
  rows[7]!.lastActiveAtMs = Date.now() + 1; await loadConversations(); await settle(el);
  expect(labels(el, 'conversation')).toEqual(['conversation-7']); expect(getConversationListState().conversations).toHaveLength(8);
});

it('Y4 Clear before a first delayed list response suppresses every old row', async () => {
  rows = Array.from({ length: 8 }, (_, i) => row(`old-${i}`, Date.now() - 1000)); holdLists = true;
  const el = await mount(); await focusEmpty(el); expect(list(el)).not.toBeNull(); clear(el);
  holdLists = false; heldLists.splice(0).forEach(release => release()); await settle(el);
  expect(options(el)).toHaveLength(0); await loadConversations(); await settle(el); expect(options(el)).toHaveLength(0);
});

it('Y5 fresh windows forget searches and Clear while profile ABA drops pending responses', async () => {
  const el = await mount(); await search(el, 'profile A search'); await focusEmpty(el);
  expect(labels(el, 'search')).toEqual(['profile A search']);
  const storageKeys = [Object.keys(localStorage).sort(), Object.keys(sessionStorage).sort()];
  clear(el); await search(el, 'profile A pending');
  expect([Object.keys(localStorage).sort(), Object.keys(sessionStorage).sort()]).toEqual(storageKeys);
  const original = getActiveProfileId(); createProfile('other', 'Other');
  rows = [row('stale A')]; holdLists = true; const pending = loadConversations();
  holdSearches = true; await search(el, 'late search'); expect(heldSearches.length).toBeGreaterThan(0);
  setActiveProfileId('other'); setActiveProfileId(original); await settle(el);
  holdLists = false; rows = [row('fresh')]; await loadConversations();
  heldLists.splice(0).forEach(release => release()); await pending;
  holdSearches = false; heldSearches.splice(0).forEach(release => release()); await settle(el); await focusEmpty(el);
  expect(labels(el, 'search')).toEqual([]); expect(labels(el, 'conversation')).toEqual(['fresh']); expect(getSearchState().query).toBe('');
  await search(el, 'new session'); await focusEmpty(el); clear(el); el.remove();
  const fresh = await mount(); await focusEmpty(fresh); expect(labels(fresh, 'search')).toEqual([]); expect(labels(fresh, 'conversation')).toEqual(['fresh']);
});

it('R5a Settings workspace profile activation resets searches and Clear and drops pending replies', async () => {
  createProfileFromCurrent('workspace-a', 'Workspace A'); await activateProfile('workspace-a');
  const el = await mount(); await search(el, 'workspace A private search'); await focusEmpty(el);
  expect(labels(el, 'search')).toEqual(['workspace A private search']);
  createProfileFromCurrent('workspace-b', 'Workspace B'); await settle(el);
  // Registry edits are not switches.
  expect(labels(el, 'search')).toEqual(['workspace A private search']);
  clear(el); await search(el, 'workspace A pending');
  rows = [row('stale workspace A')]; holdLists = true; const pending = loadConversations();
  await settle(el); const oldLists = heldLists.splice(0);
  holdSearches = true; await search(el, 'late workspace search');
  await activateProfile('workspace-b'); await activateProfile('workspace-a'); await settle(el);
  expect(getWorkspaceProfileId()).toBe('workspace-a');
  oldLists.forEach(release => release()); await pending; await settle(el);
  expect(getConversationListState().conversations.map(c => c.id)).not.toContain('stale workspace A');
  holdLists = false; rows = [row('fresh workspace')]; await loadConversations();
  heldLists.splice(0).forEach(release => release()); await pending;
  holdSearches = false; heldSearches.splice(0).forEach(release => release()); await settle(el); await focusEmpty(el);
  expect(labels(el, 'search')).toEqual([]);
  expect(labels(el, 'conversation')).toEqual(['fresh workspace']);
  expect(getSearchState().query).toBe('');
});

it('Y6 observed lock hides cached optimistic and legacy labels and pre-lock responses until unlock refresh', async () => {
  localStorage.setItem('jf-conversation-titles', JSON.stringify({ legacy: 'Legacy secret' }));
  rows = [row('named', 100, 'Secret'), row('legacy', 99, null), row('untitled', 98, null)];
  const el = await mount(); await focusEmpty(el); expect(labels(el)).toContain('Secret'); expect(labels(el)).toContain('Untitled conversation');
  await setConversationTitle('named', 'Optimistic secret'); await settle(el);
  holdLists = true; const pending = loadConversations(); lock('locked'); await settle(el);
  expect(options(el, 'conversation')).toHaveLength(0); expect(list(el)!.textContent).toContain('Conversations are locked');
  heldLists.splice(0).forEach(release => release()); await pending; await settle(el);
  expect(list(el)!.textContent).not.toMatch(/Secret|secret|preview|Legacy/);
  holdLists = false; rows = [row('unlocked', 200, 'Refreshed')]; lock('unlocked'); await settle(el);
  expect(labels(el, 'conversation')).toEqual(['Refreshed']);
  rows = []; await loadConversations(); await settle(el); expect(options(el, 'conversation')).toHaveLength(0);
});

it('Y6 a history 423 immediately suppresses conversation disclosure', async () => {
  rows = [row('locked-on-open', 1, 'Sensitive')]; const el = await mount(); await focusEmpty(el);
  expect(labels(el)).toEqual(['Sensitive']); historyStatus = 423; options(el)[0]!.click(); await settle(el); emit(el, 'sv3-tier-change', { tier: 'search' }); await settle(el); await focusEmpty(el);
  expect(options(el, 'conversation')).toHaveLength(0); expect(list(el)!.textContent).toContain('Conversations are locked');
});

it('Y7 exposes a named list and active descendant in hero, docked and narrow themes', async () => {
  rows = [row('readable')]; const el = await mount(); await focusEmpty(el);
  expect(list(el)?.getAttribute('aria-labelledby')).toBe('sv3-recent-heading');
  for (const state of ['hero', 'docked'] as const) for (const theme of ['light', 'dark'] as const) {
    el.composerState = state; el.theme = theme; el.style.width = '360px'; await settle(el);
    await key(el, 'ArrowDown'); expect(field(el).getAttribute('aria-expanded')).toBe('true');
    const id = field(el).getAttribute('aria-activedescendant'); expect(composer(el).shadowRoot!.getElementById(id!)?.textContent).toContain('readable');
    expect(list(el)!.querySelector('[role=listbox]')?.getAttribute('aria-labelledby')).toBe('sv3-recent-heading');
  }
});

it('R1a keyboard selection scrolls the Recent list without scrolling workspace ancestors', async () => {
  rows = Array.from({ length: 5 }, (_, i) => row(`conversation-${i}`));
  const el = await mount();
  for (let i = 0; i < 8; i++) await search(el, `search-${i}`);
  await focusEmpty(el);
  const scroller = list(el)!.querySelector<HTMLElement>('[role=listbox]')!;
  vi.spyOn(scroller, 'getBoundingClientRect').mockImplementation(() => ({ top: 100, bottom: 150 }) as DOMRect);
  for (const [index, option] of options(el).entries()) {
    vi.spyOn(option, 'getBoundingClientRect').mockImplementation(() => ({
      top: 100 + index * 25 - scroller.scrollTop, bottom: 125 + index * 25 - scroller.scrollTop,
    }) as DOMRect);
  }
  for (let i = 0; i < 13; i++) await key(el, 'ArrowDown');
  expect(scroller.scrollTop).toBe(275);
  expect(options(el)[12]!.getAttribute('aria-selected')).toBe('true');
  await key(el, 'ArrowDown'); expect(scroller.scrollTop).toBe(0);
  expect(el.shadowRoot!.querySelector('.column')!.scrollTop).toBe(0);
});

it('Y8 keeps saved conversations and historical SEARCH readable without writing events', async () => {
  rows = [row('existing')];
  const el = await mount(); await search(el, 'new search'); await settle(el);
  expect(getConversationListState().conversations.map(c => c.id)).toEqual(['existing']);
  expect(writes().every(c => String(c[0]).includes('/api/knowledge/search'))).toBe(true);
  const { projectUnifiedThread } = await import('../unifiedThreadProjection.js');
  expect(projectUnifiedThread([{ id: 'historic', kind: 'SEARCH', occurredAt: '2026-01-01T00:00:00Z',
    originator: 'user', content: '', attributes: { query: 'historical query' } }])[0]).toMatchObject({
      id: 'historic', kind: 'progress', attributes: { query: 'historical query', searchEvent: true },
    });
});
