// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

const native = vi.hoisted(() => ({
  enabled: false,
  current: Promise.resolve<string[] | null>(null),
  pending: Promise.resolve<unknown>(null),
  listeners: new Map<string, (event: { payload: unknown }) => void>(),
}));
vi.mock('../../utils/tauriRuntime.js', () => ({ isTauriRuntime: () => native.enabled }));
vi.mock('@tauri-apps/plugin-deep-link', () => ({ getCurrent: () => native.current }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: async (command: string) =>
  command === 'take_search_summon' ? native.pending : { active: true } }));
vi.mock('@tauri-apps/api/event', () => ({ listen: async (name: string, listener: (event: { payload: unknown }) => void) => {
  native.listeners.set(name, listener);
  return () => { native.listeners.delete(name); };
} }));

import './Shell.js';
import '../views/search-v3/SearchV3View.js';
import type { Shell } from './Shell.js';
import type { SearchV3View } from '../views/search-v3/SearchV3View.js';
import type { Surface } from '../../api/types/surface.js';
import { __resetForTest as resetCatalog, __seedForTest as seedCatalog } from '../../api/registry/SurfaceCatalogClient.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { __resetSurfaceSchemasForTest } from '../router/surfaceSchemas.js';
import { __resetStoreRegistryForTest } from '../router/storeRegistry.js';
import { deactivateProjection } from '../router/URLProjector.js';
import { __resetJournalForTest, recordNavigation } from '../state/NavigationJournal.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetConversationListForTest, getConversationListState } from '../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../controllers/draftPersistence.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { __resetAgentSessionStore } from '../state/agentSessionStore.js';
import { setLastViewedConversation } from '../controllers/lastViewedConversation.js';
import { takeSearchV3Handoff } from '../utils/compose.js';
import { resetSearchState } from '../state/searchState.js';

const SEARCH = 'core.search-v3-surface';
const LIBRARY = 'core.library-surface';
let releaseSchemas: () => void;
let fetchMock: ReturnType<typeof vi.fn>;

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(r => { resolve = r; });
  return { promise, resolve };
}
function surface(id: string, mountTag: string): Surface {
  return { id, mountTag, presentation: { labelKey: id, descriptionKey: id }, audience: 'USER', placement: 'RAIL',
    consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' } };
}
if (!customElements.get('jf-cold-library-probe')) customElements.define('jf-cold-library-probe', class extends HTMLElement {});
async function drain(): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise<void>(resolve => setTimeout(resolve, 0));
}
function view(shell: Shell): SearchV3View | null {
  return shell.shadowRoot?.querySelector('jf-stage')?.shadowRoot?.querySelector('jf-sv3-window') ?? null;
}
function input(shell: Shell): HTMLTextAreaElement {
  return view(shell)!.shadowRoot!.querySelector('jf-sv3-composer')!.shadowRoot!.querySelector('[data-testid="sv3-composer-input"]')!;
}
async function mount(): Promise<Shell> {
  const shell = document.createElement('jf-shell') as Shell;
  document.body.append(shell);
  await drain();
  expect(view(shell), 'real Search mounted before schema hydration').not.toBeNull();
  return shell;
}
async function complete(): Promise<void> { releaseSchemas(); await drain(); }
beforeEach(() => {
  // These boot fixtures represent an explicit fresh address, not an automatic reload.
  vi.spyOn(window.performance, 'getEntriesByType').mockImplementation(type => type === 'navigation'
    ? [{ type: 'navigate' } as unknown as PerformanceEntry] : []);
  localStorage.clear(); sessionStorage.clear();
  resetCatalog(); __resetBootstrapForTest(); __resetSurfaceSchemasForTest(); __resetStoreRegistryForTest();
  __resetUserConfigForTest(); __resetJournalForTest(); __resetConversationListForTest();
  __resetDraftProvidersForTest(); __resetAiStateForTest(); deactivateProjection(); takeSearchV3Handoff();
  window.history.replaceState(null, '', '/');
  native.enabled = false; native.current = Promise.resolve(null); native.pending = Promise.resolve(null); native.listeners.clear();
  seedCatalog({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
    surface(SEARCH, 'jf-sv3-window'), surface(LIBRARY, 'jf-cold-library-probe'),
  ] });
  const schemas = deferred<void>(); releaseSchemas = () => schemas.resolve();
  fetchMock = vi.fn(async (url: unknown) => {
    if (String(url).endsWith('/api/registry/surfaces')) await schemas.promise;
    const href = String(url);
    if (/\/api\/chat\/conversations(?:\?|$)/.test(href)) return new Response(JSON.stringify({ sessions: [
      { sessionId: 'another-tabs-conversation', createdAtMs: 1, lastActiveAtMs: 2,
        messageCount: 2, firstUserMessage: 'Saved in another tab', shapeId: 'core.rag-ask' },
    ] }), { status: 200 });
    return new Response(JSON.stringify({ entries: [], conversations: [] }), { status: 200 });
  });
  vi.stubGlobal('fetch', fetchMock);

});
afterEach(async () => {
  releaseSchemas();
  document.body.replaceChildren();
  await drain();
  deactivateProjection(); resetSearchState(); __resetAiStateForTest(); __resetAgentSessionStore();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});

const POINTER = 'justsearch.lastViewedConversation.v1';
function journal(id: string | null, target = 'core.search-v3-surface'): void {
  recordNavigation(target, `justsearch://surface/${target}${id ? '?conversationId=' + id : ''}`, 'Search', 'URL_BAR');
}
function noSend(): void {
  expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/api/chat/dispatch'))).toHaveLength(0);
}
it.each(['core.search-v3-surface', 'core.unified-chat-surface'])('A6 a new tab ignores conversation identity in the shared %s journal', async target => {
  journal('another-tabs-conversation', target);
  expect(sessionStorage.getItem(POINTER)).toBeNull();
  const shell = await mount(); await complete();
  expect(getConversationListState().activeId).toBeNull();
  expect(view(shell)!.sessions.activeId).toBeNull();
  expect(sessionStorage.getItem(POINTER)).toBeNull();
  expect(window.location.hash).not.toContain('conversationId');
  const sidebar = view(shell)!.shadowRoot!.querySelector('jf-sv3-sidebar')!;
  expect((sidebar as unknown as { groups: { rows: { id: string }[] }[] }).groups.flatMap(g => g.rows).map(r => r.id))
    .toContain('another-tabs-conversation'); noSend();
});
it('A6 the surviving tab pointer outranks another tabs shared journal conversation', async () => {
  journal('another-tabs-conversation'); setLastViewedConversation('this-tabs-conversation');
  const shell = await mount(); await complete();
  expect(view(shell)!.sessions.activeId).toBe('this-tabs-conversation');
  expect(sessionStorage.getItem(POINTER)).toBe('this-tabs-conversation'); noSend();
});
it('A6 preservation restart from an old bare journal opens fresh', async () => {
  journal(null, 'core.unified-chat-surface');
  const shell = await mount(); await complete();
  expect(view(shell)!.sessions.activeId).toBeNull();
  expect(sessionStorage.getItem(POINTER)).toBeNull(); noSend();
});
it('A6 preservation an explicit conversation address outranks the pointer and journal', async () => {
  journal('another-tabs-conversation'); setLastViewedConversation('this-tabs-conversation');
  window.location.hash = '#justsearch://surface/core.unified-chat-surface?conversationId=explicit';
  const shell = await mount(); await complete();
  expect(view(shell)!.sessions.activeId).toBe('explicit'); noSend();
});
it('A6 preservation an explicit query outranks the pointer and journal', async () => {
  journal('another-tabs-conversation'); setLastViewedConversation('this-tabs-conversation');
  window.location.hash = '#justsearch://surface/core.unified-chat-surface?query=Explicit%20question&mode=ask';
  const shell = await mount(); await complete();
  expect(input(shell).value).toBe('Explicit question');
  expect(view(shell)!.sessions.activeId).toBeNull(); noSend();
});
it('A6 preservation same-tab reload with a pointer and a bare journal restores that tab', async () => {
  journal(null, 'core.unified-chat-surface'); setLastViewedConversation('this-tabs-conversation');
  const shell = await mount(); await complete();
  expect(view(shell)!.sessions.activeId).toBe('this-tabs-conversation');
  expect(sessionStorage.getItem(POINTER)).toBe('this-tabs-conversation'); noSend();
});
it.each([404, 423])('A6 preservation a %s record pointer never borrows the shared journal identity', async status => {
  journal(null); setLastViewedConversation('unavailable-record');
  const previous = fetchMock.getMockImplementation()! as (url: unknown) => Promise<Response>;
  fetchMock.mockImplementation(async (url: unknown) => String(url).includes('/unavailable-record')
    ? new Response(JSON.stringify({ error: status === 423 ? 'STORE_LOCKED' : 'NOT_FOUND', messages: [] }), { status })
    : previous(url));
  const shell = await mount(); await complete();
  expect(view(shell)!.sessions.activeId).toBe('unavailable-record');
  expect(sessionStorage.getItem(POINTER)).toBe('unavailable-record'); noSend();
});
it('A6 the tab pointer restores even when its sidebar row arrives before startup settles', async () => {
  journal(null, 'core.unified-chat-surface'); setLastViewedConversation('another-tabs-conversation');
  const shell = await mount();
  expect(view(shell)!.sessions.sessions.map(s => s.id)).toContain('another-tabs-conversation');
  await complete();
  expect(view(shell)!.sessions.activeId).toBe('another-tabs-conversation');
  expect(window.location.hash).toContain('conversationId=another-tabs-conversation'); noSend();
});
