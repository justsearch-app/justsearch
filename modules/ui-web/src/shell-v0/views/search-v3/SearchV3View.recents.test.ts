// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import { submitInSession, startNewSession } from './sv3-sessions.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import { invokeCommandWithResult } from '../../commands/CommandRegistry.js';
import { parkSearchV3Handoff, takeSearchV3Handoff } from '../../utils/compose.js';
import { __resetConversationListForTest, loadConversations, setConversationTitle, getConversationListState } from '../../state/conversationListStore.js';
import { __resetAiStateForTest, __feedForTest, __feedContactForTest } from '../../state/aiStateStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { resetSearchState, getSearchState, setQuery, submitSearch } from '../../state/searchState.js';
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
let responseBody: Record<string, unknown>;
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
const options = (el: SearchV3View): HTMLButtonElement[] => [...(list(el)?.querySelectorAll<HTMLButtonElement>('button[data-kind=search]') ?? [])];
const labels = (el: SearchV3View): string[] => options(el).map(item => item.querySelector('.recent-query')!.textContent!.trim());
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
  await type(el, ''); el.composerState = 'hero'; await settle(el); field(el).blur(); field(el).focus(); await settle(el);
}
async function search(el: SearchV3View, text: string): Promise<void> { await type(el, text); await key(el, 'Enter'); }
function clear(el: SearchV3View): void { list(el)!.querySelector<HTMLButtonElement>('[data-testid=sv3-recent-clear]')!.click(); }
const writes = (): unknown[][] => http.mock.calls.filter(c => ['POST', 'PUT', 'PATCH', 'DELETE'].includes(String(c[1]?.method)));

beforeEach(async () => {
  await __resetProfilesForTest();
  localStorage.clear(); sessionStorage.clear(); __resetConversationListForTest(); __resetUserStateForTest();
  __resetDraftProvidersForTest(); __resetDraftKeptForTest(); __resetAiStateForTest(); __resetSearchFiltersForTest(); resetSearchState();
  rows = []; searches = []; heldLists = []; holdLists = false; heldSearches = []; holdSearches = false; historyStatus = 200;
  responseBody = { results: [], totalHits: 0 };
  __seedForTest({ 'search.recent': 'Recent', 'search.clear-recent': 'Clear recent',
    'search.conversations-locked': 'Conversations are locked', 'search.untitled-conversation': 'Untitled conversation' });
  http = vi.fn(async (url: unknown, init?: RequestInit) => {
    const href = String(url);
    if (href.includes('/api/knowledge/search')) {
      const body = JSON.parse(String(init?.body)); searches.push(body);
      const answer = () => json(body.query === 'failed' ? { error: 'Unavailable' } : body.query === '"' ? { error: 'Invalid query' } : responseBody, body.query === 'failed' ? 503 : body.query === '"' ? 400 : 200);
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

it('S4-11 empty recents render nothing; successful responses have exact header count and completion time', async () => {
  const el = await mount(); await focusEmpty(el); expect(list(el)).toBeNull();
  await search(el, 'budget'); await focusEmpty(el);
  expect(labels(el)).toEqual(['budget']); expect(list(el)!.textContent).toContain('0 matches');
  expect(list(el)!.querySelector('time')!.getAttribute('datetime')).toBeTruthy();
  field(el).blur(); await settle(el); expect(list(el)).not.toBeNull();
  expect(field(el).hasAttribute('aria-activedescendant')).toBe(false);
  expect(options(el)[0]!.tabIndex).toBe(0);
});
it('S4-12 pointer activation replay the query with current restrictions', async () => {
  const el = await mount(); const query = 'title:"budget review" -draft author:Ada';
  await search(el, query); toggleFacetValue('file_kind', 'pdf'); await settle(el); await focusEmpty(el);
  const before = searches.length; options(el)[0]!.focus(); options(el)[0]!.click(); await settle(el);
  expect(field(el).value).toBe(query); expect(searches.slice(before)).toHaveLength(1);
  expect(searches.at(-1)).toMatchObject({query, filters:{fileKind:['pdf']}});
});
it('S4-11 failed and invalid attempts show the query without a count', async () => {
  const el = await mount();
  for (const query of ['failed','"']) { await search(el,query); await focusEmpty(el); expect(labels(el)[0]).toBe(query);
    expect(options(el)[0]!.querySelector('.recent-meta')!.textContent).not.toContain('matches'); }
});
it('S4-11 exact dedup and eight-item cap apply only to explicit attempts; typing sends nothing', async () => {
  const el = await mount();
  for (const query of ['one','two','three','four','five','six','seven','eight','nine']) await search(el,query);
  await focusEmpty(el); expect(labels(el)).toEqual(['nine','eight','seven','six','five','four','three','two']);
  await search(el,'  three  '); await search(el,'Three');
  await search(el,'three  words'); await search(el,'three words');
  await type(el,'palette'); await invokeCommandWithResult('cmd-everything'); await settle(el); await focusEmpty(el);
  expect(labels(el).slice(0,5)).toEqual(['palette','three words','three  words','Three','three']);
  const before=searches.length; const saved = labels(el); await type(el,'draft'); await new Promise(resolve=>setTimeout(resolve,750));
  expect(searches).toHaveLength(before);
  toggleFacetValue('file_kind','pdf'); await settle(el);
  setQuery('restored or plugin'); submitSearch(); await settle(el); await focusEmpty(el);
  expect(labels(el)).toEqual(saved);
  options(el)[2]!.click(); await settle(el); await focusEmpty(el);
  expect(labels(el)).toEqual([saved[2], ...saved.filter((_,i)=>i !== 2)]);
});
it('S4-12 Clear empties only searches and a delayed completion cannot repopulate it', async () => {
  rows=[row('saved')]; const el=await mount(); await search(el,'one'); await focusEmpty(el);
  holdSearches=true; await search(el,'pending'); el.composerState='hero'; await settle(el);
  const storageKeys = [Object.keys(localStorage).sort(), Object.keys(sessionStorage).sort()];
  const beforeClear = writes().length;
  clear(el); await settle(el);
  expect(writes()).toHaveLength(beforeClear);
  expect([Object.keys(localStorage).sort(), Object.keys(sessionStorage).sort()]).toEqual(storageKeys);
  heldSearches.splice(0).forEach(release=>release()); await settle(el);
  expect(list(el)).toBeNull(); expect(getConversationListState().conversations.map(c=>c.id)).toEqual(['saved']);
  expect(writes().every(c=>String(c[0]).includes('/api/knowledge/search'))).toBe(true);
});
it('S4-11 superseded attempts keep their query and never acquire another attempt count', async () => {
  const el=await mount(); holdSearches=true; await search(el,'old'); await search(el,'new');
  holdSearches=false; heldSearches.splice(0).reverse().forEach(release=>release()); await settle(el); await focusEmpty(el);
  expect(labels(el)).toEqual(['new','old']);
  expect(options(el)[0]!.textContent).toContain('0 matches'); expect(options(el)[1]!.textContent).not.toContain('matches');
});
it('S4-12 new windows forget searches; conversations remain only in the column', async () => {
  rows=[row('saved')]; const el=await mount(); await search(el,'private'); await focusEmpty(el);
  expect(labels(el)).toEqual(['private']); expect(list(el)!.textContent).not.toContain('saved');
  el.remove(); const next=await mount(); await focusEmpty(next); expect(list(next)).toBeNull();
  expect(next.shadowRoot!.querySelector('jf-sv3-sidebar')).not.toBeNull();
});
it('S4-12 profile ABA discards searches and pre-switch completions', async () => {
  createProfile('work','Work'); const initial=getActiveProfileId(); const el=await mount();
  await search(el,'private'); holdSearches=true; await search(el,'pending');
  rows=[row('stale profile')]; holdLists=true; const pending=loadConversations(); await settle(el);
  const oldLists=heldLists.splice(0);
  setActiveProfileId('work'); setActiveProfileId(initial); await settle(el);
  oldLists.forEach(release=>release()); await pending; await settle(el);
  expect(getConversationListState().conversations.map(c=>c.id)).not.toContain('stale profile');
  expect(await conversationLabels(el)).not.toContain('stale profile');
  holdLists=false; rows=[row('fresh profile')]; await loadConversations();
  heldLists.splice(0).forEach(release=>release());
  holdSearches=false; heldSearches.splice(0).forEach(release=>release()); await settle(el); await focusEmpty(el);
  expect(list(el)).toBeNull(); expect(getSearchState().query).toBe('');
  expect(await conversationLabels(el)).toEqual(['fresh profile']);
});
it('S4-12 workspace profile switches discard pending recents and cached conversation rows', async () => {
  createProfileFromCurrent('workspace-a','A'); await activateProfile('workspace-a');
  const el=await mount(); await search(el,'private'); await focusEmpty(el); expect(labels(el)).toEqual(['private']);
  createProfileFromCurrent('workspace-b','B'); await settle(el); expect(labels(el)).toEqual(['private']);
  rows=[row('old')]; holdLists=true; const pending=loadConversations(); await settle(el);
  const releases=heldLists.splice(0); rows=[]; holdSearches=true; await search(el,'pending');
  await activateProfile('workspace-b'); await activateProfile('workspace-a'); await settle(el);
  releases.forEach(release=>release()); await pending; await settle(el);
  expect(getConversationListState().conversations.map(c=>c.id)).not.toContain('old');
  expect(await conversationLabels(el)).not.toContain('old');
  heldLists.splice(0).forEach(release=>release());
  holdLists=false; holdSearches=false; heldSearches.splice(0).forEach(release=>release()); await settle(el); await focusEmpty(el);
  expect(getWorkspaceProfileId()).toBe('workspace-a'); expect(list(el)).toBeNull();
  expect(getConversationListState().conversations.map(c=>c.id)).not.toContain('old');
  rows=[row('fresh workspace')]; await loadConversations(); await settle(el);
  expect(await conversationLabels(el)).toEqual(['fresh workspace']);
  expect(getSearchState().query).toBe('');
});
it('S4-16 locked conversation labels never leak into recents or the column', async () => {
  rows=[row('secret',100,'Secret')]; const el=await mount(); await search(el,'public search'); await focusEmpty(el);
  lock('locked'); await settle(el);
  expect(labels(el)).toEqual(['public search']); expect(list(el)!.textContent).not.toContain('Secret');
  const sidebar=el.shadowRoot!.querySelector('jf-sv3-sidebar')!; await sidebar.updateComplete;
  for(const r of sidebar.shadowRoot!.querySelectorAll('jf-sv3-session-row')) { await r.updateComplete; expect(r.shadowRoot!.textContent).not.toContain('Secret'); expect(r.shadowRoot!.textContent).not.toContain('preview-secret'); }
  expect(sidebar.shadowRoot!.textContent).not.toContain('Secret');
  lock('unlocked'); await settle(el); await sidebar.updateComplete;
  const restored = sidebar.shadowRoot!.querySelector('jf-sv3-session-row')!;
  expect(restored).not.toBeNull(); await restored.updateComplete;
  expect(restored.shadowRoot!.textContent).toContain('Secret');
});
it('S4-16 a plain search never creates or mutates a saved conversation or historical SEARCH event', async () => {
  rows=[row('existing')]; const el=await mount(); await search(el,'new search');
  expect(getConversationListState().conversations.map(c=>c.id)).toEqual(['existing']);
  expect(writes().every(c=>String(c[0]).includes('/api/knowledge/search'))).toBe(true);
  const {projectUnifiedThread}=await import('../unifiedThreadProjection.js');
  expect(projectUnifiedThread([{id:'historic',kind:'SEARCH',occurredAt:'2026-01-01T00:00:00Z',originator:'user',content:'',attributes:{query:'historical query'}}])[0]).toMatchObject({id:'historic',kind:'progress',attributes:{query:'historical query',searchEvent:true}});
});

async function conversationLabels(el: SearchV3View): Promise<string[]> {
  const sidebar = el.shadowRoot!.querySelector('jf-sv3-sidebar');
  if (!sidebar) return [];
  await sidebar.updateComplete;
  const rows = [...sidebar.shadowRoot!.querySelectorAll('jf-sv3-session-row')];
  await Promise.all(rows.map(row => row.updateComplete));
  return rows.map(row => row.label);
}
async function openConversation(el: SearchV3View): Promise<void> {
  const sidebar = el.shadowRoot!.querySelector('jf-sv3-sidebar')!; await sidebar.updateComplete;
  const row = sidebar.shadowRoot!.querySelector('jf-sv3-session-row')!; await row.updateComplete;
  const button = row.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-session-row-button"]');
  expect(button).not.toBeNull(); button!.click(); await settle(el);
}
it.each(['retained question', ''])('Y3 protects retained preparation %j on search replay and column navigation', async text => {
  const path = text === '' ? 'retained-schema.md' : 'retained-alpha.md';
  const older = { place: null, text, preparation: text === ''
    ? { kind: 'structured', schema: '', scope: { kind: 'files', paths: [path] } }
    : { kind: 'ask', scope: { kind: 'files', paths: [path] } } };
  const key = 'justsearch.draft.attachment.v1.search-v3.main';
  rows = [row('destination')]; const el = await mount(); await search(el,'replay'); await focusEmpty(el);
  parkSearchV3Handoff({ query: text, forceShapeId: text === '' ? 'core.extract' : 'core.rag-ask', docIds: [path] });
  await settle(el); parkSearchV3Handoff({ query: '' }); await settle(el); await focusEmpty(el);
  expect(labels(el)).toEqual(['replay']); options(el)[0]!.click(); await settle(el);
  expect(JSON.parse(sessionStorage.getItem(key)!).payload.drafts).toEqual(expect.arrayContaining([older]));
  await openConversation(el);
  expect(JSON.parse(sessionStorage.getItem(key)!).payload.drafts).toEqual(expect.arrayContaining([older]));
  expect(http.mock.calls.some(c=>String(c[0]).includes('/destination/history'))).toBe(true);
  expect(http.mock.calls.some(c=>String(c[0]).includes('/dispatch'))).toBe(false);
});
it('Y6 lock hides optimistic, legacy and untitled rows and rejects pre-lock responses until refresh', async () => {
  localStorage.setItem('jf-conversation-titles',JSON.stringify({ legacy:'Legacy secret' }));
  rows=[row('named',100,'Secret'),row('legacy',99,null),{...row('untitled',98,null),firstUserMessage:''}];
  const el=await mount();
  expect(await conversationLabels(el)).toContain('Secret');
  expect(await conversationLabels(el)).toContain('Untitled conversation');
  await setConversationTitle('named','Optimistic secret'); await settle(el);
  expect(await conversationLabels(el)).toContain('Optimistic secret');
  holdLists=true; const pending=loadConversations(); lock('locked'); await settle(el);
  expect(await conversationLabels(el)).toEqual([]);
  heldLists.splice(0).forEach(release=>release()); await pending; await settle(el);
  expect(await conversationLabels(el)).toEqual([]);
  const sidebar=el.shadowRoot!.querySelector('jf-sv3-sidebar');
  expect(sidebar?.shadowRoot!.textContent ?? '').not.toMatch(/Secret|secret|preview|Legacy|Untitled/);
  rows=[row('unlocked',200,'Refreshed')]; lock('unlocked'); await settle(el);
  expect(await conversationLabels(el)).toEqual([]);
  holdLists=false; heldLists.splice(0).forEach(release=>release()); await settle(el);
  expect(getConversationListState().conversations.map(c=>c.id)).toEqual(['unlocked']);
  expect(await conversationLabels(el)).toEqual(['Refreshed']);
  rows=[]; await loadConversations(); await settle(el);
  expect(await conversationLabels(el)).toEqual([]); expect(el.shadowRoot!.querySelector('jf-sv3-sidebar')).toBeNull();
});
it('Y6 history 423 immediately suppresses disclosure through the remaining column route', async () => {
  rows=[row('locked-on-open',1,'Sensitive')]; const el=await mount();
  expect(await conversationLabels(el)).toEqual(['Sensitive']); historyStatus=423;
  await openConversation(el);
  expect(await conversationLabels(el)).toEqual([]);
  expect(el.shadowRoot!.textContent).not.toContain('Sensitive');
});
it('Y1 empty Enter and IME or modified Enter never search from visible recents', async () => {
  const el=await mount(); await search(el,'saved'); await focusEmpty(el);
  const before=searches.length;
  for(const modifiers of [{},{isComposing:true},{shiftKey:true},{ctrlKey:true},{metaKey:true}]) {
    await key(el,'Enter',modifiers); expect(searches).toHaveLength(before);
    expect(labels(el)).toEqual(['saved']);
  }
  await type(el,'new draft');
  for(const modifiers of [{isComposing:true},{shiftKey:true},{ctrlKey:true},{metaKey:true}]) {
    await key(el,'Enter',modifiers); expect(searches).toHaveLength(before);
    expect(labels(el)).toEqual(['saved']);
  }
});
it('S4-11 recent count equals the rendered header and time belongs to response completion', async () => {
  const el=await mount();
  responseBody={results:[{id:'one',fields:{path:'/one.md'}}],totalHits:1000,matchCount:1000,totalHitsRelation:'gte'};
  holdSearches=true; await search(el,'bounded');
  const afterSubmit=Date.now(); await new Promise(resolve=>setTimeout(resolve,25));
  const beforeCompletion=Date.now(); heldSearches.splice(0).forEach(release=>release()); await settle(el);
  const afterCompletion=Date.now();
  const main=el.shadowRoot!.querySelector('jf-sv3-main')!; await main.updateComplete;
  const results=main.shadowRoot!.querySelector('jf-sv3-results')!; await results.updateComplete;
  const card=results.shadowRoot!.querySelector<HTMLElement & {updateComplete: Promise<unknown>}>('jf-results-card')!; await card.updateComplete;
  const header=card.shadowRoot!.querySelector('.meta-info')!.textContent!.replace(/\s+/g,' ').trim().replace(/ · found in \S+$/,'');
  await focusEmpty(el);
  const meta=options(el)[0]!.querySelector('.recent-meta')!;
  expect(meta.textContent!.split(' · ')[0]!.trim()).toBe(header);
  const at=Date.parse(meta.querySelector('time')!.getAttribute('datetime')!);
  expect(at).toBeGreaterThan(afterSubmit); expect(at).toBeGreaterThanOrEqual(beforeCompletion); expect(at).toBeLessThanOrEqual(afterCompletion);
});

it('the column reconciles canonical rows without losing pending local turns or cached transcripts', async () => {
  rows=[row('stored',100,'Canonical')]; const el=await mount();
  const withPending=submitInSession(startNewSession(el.sessions),'Pending local question',200,'ask','local');
  const loaded=submitInSession(startNewSession(withPending),'Loaded old question',100,'ask','loaded');
  el.sessions={ ...loaded, sessions:loaded.sessions.map(session => session.id !== 'loaded' ? session
    : { ...session, turns: session.turns.map(turn => ({ ...turn, recordId:'saved-user-message', status:'complete' as const })) }) };
  await settle(el);
  expect(await conversationLabels(el)).toEqual(['Pending local question','Canonical']);
  rows=[]; await loadConversations(); await settle(el);
  expect(await conversationLabels(el)).toEqual(['Pending local question']);
  expect(el.sessions.sessions.map(session=>session.id)).toEqual(['loaded','local','stored']);
  lock('locked'); await settle(el); expect(await conversationLabels(el)).toEqual([]);
  lock('unlocked'); await settle(el); expect(await conversationLabels(el)).toEqual(['Pending local question']);
  rows=[row('local',200,'Saved local question')]; await loadConversations(); await settle(el);
  expect(await conversationLabels(el)).toEqual(['Saved local question']);
  el.sessions={ ...el.sessions, sessions: el.sessions.sessions.map(session=>session.id !== 'local' ? session
    : { ...session, turns:session.turns.map(turn=>({ ...turn, recordId:'now-saved', status:'complete' as const })) }) };
  rows=[]; await loadConversations(); await settle(el);
  expect(await conversationLabels(el)).toEqual([]);
});
