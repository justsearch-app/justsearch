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
const OLD_DRAFT = 'justsearch.draft.unified-chat.composer';
const DRAFT = 'justsearch.draft.search-v3.composer';
const MARKER = OLD_DRAFT + '.migrated-to.search-v3.composer';
const legacy = 'Legacy draft — punctuation & 日本';
const prompt = '# & % "日本語" — سؤال';
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
function noSend(): void {
  expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/api/chat/dispatch'))).toHaveLength(0);
}
function retained(): void {
  expect(localStorage.getItem(OLD_DRAFT), 'the sole old draft remains exact').toBe(legacy);
  expect(localStorage.getItem(MARKER), 'incoming work must not dispose of the old draft').toBeNull();
}
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
    return new Response(JSON.stringify({ entries: [], conversations: [] }), { status: 200 });
  });
  vi.stubGlobal('fetch', fetchMock);
  localStorage.setItem(OLD_DRAFT, legacy);
});
afterEach(async () => {
  releaseSchemas();
  document.body.replaceChildren();
  await drain();
  deactivateProjection(); resetSearchState(); __resetAiStateForTest(); __resetAgentSessionStore();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});

it.each([
  { label: 'old alias Ask', url: `surface/core.unified-chat-surface?query=${encodeURIComponent(prompt)}&mode=ask`, tier: 'ask' },
  { label: 'answer FreeChat', url: `answer?q=${encodeURIComponent(prompt)}&shape=core.free-chat`, tier: 'ask' },
  { label: 'retired Search query', url: `surface/core.search-surface?query=${encodeURIComponent(prompt)}`, tier: 'search' },
  { label: 'retired Agent files', url: `surface/core.agent-surface?query=${encodeURIComponent(prompt)}&mode=ask&docIds=chosen%26%E6%97%A5%E6%9C%AC.md`, tier: 'ask', notice: 'chosen&日本.md' },
  { label: 'answer Structured files', url: `answer?q=${encodeURIComponent(prompt)}&shape=core.extract&docIds=chosen.md`, tier: 'structured', notice: 'chosen.md' },
  { label: 'invalid empty scope', url: `surface/core.unified-chat-surface?query=${encodeURIComponent(prompt)}&mode=ask&docIds=`, tier: 'ask', notice: 'Invalid task data' },
  { label: 'invalid selection', url: `surface/core.unified-chat-surface?query=${encodeURIComponent(prompt)}&mode=ask&selection=invalid`, tier: 'ask', notice: 'Invalid selection' },
  { label: 'invalid mode', url: `surface/core.unified-chat-surface?query=${encodeURIComponent(prompt)}&mode=invalid`, tier: 'search', notice: 'Unknown hand-off mode' },
])('FC15 cold $label preserves legacy until routing has prepared the task', async ({ url, tier, notice }) => {
  window.location.hash = '#justsearch://' + url;
  const shell = await mount();
  retained();
  await complete();
  expect(input(shell).value).toBe(prompt);
  const composer = view(shell)!.shadowRoot!.querySelector('jf-sv3-composer')!;
  expect(composer.getAttribute('tier')).toBe(tier);
  if (notice) expect(composer.shadowRoot!.textContent).toContain(notice);
  retained(); noSend();
});

it('FC15 cold conversation address outranks the pointer without disposing of the old draft', async () => {
  setLastViewedConversation('previous-conversation');
  window.location.hash = '#justsearch://surface/core.unified-chat-surface?conversationId=address-conversation';
  const shell = await mount();
  retained();
  await complete();
  expect(getConversationListState().activeId).toBe('address-conversation');
  expect(view(shell)!.sessions.activeId).toBe('address-conversation');
  retained(); noSend();
});

it('FC15 cold incoming edits survive leave return reload and restart while legacy stays saved', async () => {
  window.location.hash = `#justsearch://answer?q=${encodeURIComponent(prompt)}&shape=core.free-chat`;
  let shell = await mount(); await complete();
  input(shell).value += ' + edit — 文'; input(shell).dispatchEvent(new Event('input')); await drain();
  shell.activateSurface(LIBRARY, {}, 'BUTTON'); await drain();
  shell.activateSurface(SEARCH, {}, 'BUTTON'); await drain();
  expect(input(shell).value).toBe(prompt + ' + edit — 文');
  expect(localStorage.getItem(OLD_DRAFT)).toBe(legacy);
  shell.remove(); await drain();
  shell = await mount(); await complete(); // same-tab reload: fresh Shell and retained browser storage
  expect(input(shell).value).toBe(prompt + ' + edit — 文');
  expect(localStorage.getItem(OLD_DRAFT)).toBe(legacy);
  shell.remove(); await drain(); sessionStorage.clear(); __resetConversationListForTest();
  shell = await mount(); await complete(); // full restart: no per-tab pointer
  expect(input(shell).value).toBe(prompt + ' + edit — 文');
  expect(localStorage.getItem(OLD_DRAFT)).toBe(legacy); noSend();
});

it('FC15 cold native link is settled before ordinary boot can transfer legacy', async () => {
  native.enabled = true;
  const cold = deferred<string[] | null>(); native.current = cold.promise;
  const shell = await mount(); await complete();
  retained();
  cold.resolve([`justsearch://answer?q=${encodeURIComponent(prompt)}&shape=core.free-chat`]); await drain();
  expect(input(shell).value).toBe(prompt); retained(); noSend();
});

it('FC15 cold queued native delivery waits for the initial drain before draft disposition', async () => {
  native.enabled = true;
  const queued = deferred<unknown>(); native.pending = queued.promise;
  const shell = await mount(); await complete();
  retained();
  queued.resolve({ kind: 'deepLink', url: `justsearch://answer?q=${encodeURIComponent(prompt)}&shape=core.free-chat` }); await drain();
  expect(input(shell).value).toBe(prompt); retained(); noSend();
});

it('FC15 cold no-ingress transfer waits for native absence then remains one time after Clear', async () => {
  native.enabled = true;
  const cold = deferred<string[] | null>(); native.current = cold.promise;
  let shell = await mount(); await complete();
  retained();
  cold.resolve(null); await drain();
  expect(input(shell).value).toBe(legacy);
  expect(localStorage.getItem(DRAFT)).toBe(legacy);
  expect(localStorage.getItem(OLD_DRAFT)).toBeNull();
  expect(localStorage.getItem(MARKER)).toBe('transferred');
  input(shell).value = ''; input(shell).dispatchEvent(new Event('input')); await drain();
  shell.remove(); await drain(); sessionStorage.clear();
  localStorage.setItem(OLD_DRAFT, 'stale old writer');
  shell = await mount(); await complete();
  expect(input(shell).value).toBe(''); noSend();
});

it('DS1 FC15 a restored journal task cannot replace legacy text or issue it', async () => {
  recordNavigation('core.unified-chat-surface', `justsearch://surface/core.unified-chat-surface?query=${encodeURIComponent(prompt)}&mode=ask`, 'Search', 'URL_BAR');
  const shell = await mount(); retained(); await complete();
  expect(input(shell).value).toBe(legacy);
  expect(localStorage.getItem(DRAFT)).toBe(legacy);
  expect(localStorage.getItem(MARKER)).toBe('transferred');
  expect(localStorage.getItem(OLD_DRAFT)).toBeNull();
  expect(view(shell)!.shadowRoot!.querySelector('[data-testid=sv3-draft-review]')).not.toBeNull();
  input(shell).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  await drain(); noSend();
});

it('FC15 cold rapid arrivals before hydration preserve only the latest prepared scope', async () => {
  const shell = await mount();
  shell.activateSurface(SEARCH, { query: 'first', mode: 'ask', docIds: ['first.md'] }, 'BUTTON');
  shell.activateSurface(SEARCH, { query: prompt, mode: 'ask', docIds: ['latest.md'] }, 'BUTTON');
  await complete();
  expect(input(shell).value).toBe(prompt);
  const text = view(shell)!.shadowRoot!.querySelector('jf-sv3-composer')!.shadowRoot!.textContent;
  expect(text).toContain('latest.md'); expect(text).not.toContain('first.md'); retained(); noSend();
});

it('FC15 cold reconnect ignores startup completion from the disconnected Shell lifecycle', async () => {
  native.enabled = true;
  const previous = deferred<string[] | null>(); native.current = previous.promise;
  const shell = await mount(); await complete(); retained();
  shell.remove(); await drain();
  const current = deferred<string[] | null>(); native.current = current.promise;
  document.body.append(shell); await drain();
  previous.resolve(null); await drain();
  retained(); // The previous startup cannot authorize this mount's disposition.
  current.resolve([`justsearch://answer?q=${encodeURIComponent(prompt)}&shape=core.free-chat`]); await drain();
  expect(input(shell).value).toBe(prompt); retained(); noSend();
});

it('FC15 cold stale native startup cannot replace a newer task after Shell reconnects', async () => {
  native.enabled = true;
  const previous = deferred<string[] | null>(); native.current = previous.promise;
  const shell = await mount(); await complete();
  shell.remove(); await drain();
  const current = deferred<string[] | null>(); native.current = current.promise;
  document.body.append(shell); await drain();
  shell.activateSurface(SEARCH, { query: prompt, mode: 'ask', docIds: ['latest.md'] }, 'BUTTON'); await drain();
  current.resolve(null); await drain();
  previous.resolve(['justsearch://answer?q=obsolete&shape=core.free-chat']); await drain();
  expect(input(shell).value).toBe(prompt);
  expect(view(shell)!.shadowRoot!.querySelector('jf-sv3-composer')!.shadowRoot!.textContent).toContain('latest.md');
  expect(localStorage.getItem(OLD_DRAFT)).toBe(legacy); noSend();
});

it('FC15 neighboring successor collision keeps its text and the exact old value after restart', async () => {
  localStorage.setItem(DRAFT, 'successor — 先');
  let shell = await mount(); await complete();
  expect(input(shell).value).toBe('successor — 先');
  expect(localStorage.getItem(OLD_DRAFT)).toBe(legacy);
  expect(localStorage.getItem(MARKER)).toBe('kept-successor');
  input(shell).value = ''; input(shell).dispatchEvent(new Event('input')); await drain();
  shell.remove(); await drain(); sessionStorage.clear();
  shell = await mount(); await complete();
  expect(input(shell).value).toBe('');
  expect(localStorage.getItem(OLD_DRAFT)).toBe(legacy); noSend();
});

it('FC15 neighboring destination write refusal keeps the old copy for a later clean mount', async () => {
  const write = localStorage.setItem.bind(localStorage);
  const fault = vi.spyOn(localStorage, 'setItem').mockImplementation((key, value) => {
    if (key === DRAFT) throw new DOMException('Injected destination write refusal', 'QuotaExceededError');
    write(key, value);
  });
  let shell = await mount(); await complete();
  retained();
  shell.remove(); await drain(); fault.mockRestore();
  shell = await mount(); await complete();
  expect(input(shell).value).toBe(legacy);
  expect(localStorage.getItem(DRAFT)).toBe(legacy);
  expect(localStorage.getItem(OLD_DRAFT)).toBeNull(); noSend();
});

it('FC15 neighboring two pending Shell mounts retain the durable transfer when the last tab closes', async () => {
  const first = await mount(), last = await mount();
  await complete();
  expect(input(first).value).toBe(legacy);
  expect(input(last).value).toBe(legacy);
  first.remove(); last.remove(); await drain();
  expect(localStorage.getItem(DRAFT)).toBe(legacy);
  noSend();
});

it('FC15 neighboring a tab closing before its ingress settles cannot erase another tab transfer', async () => {
  const firstReady = deferred<void>(), lastReady = deferred<void>();
  let requests = 0;
  fetchMock.mockImplementation(async (url: unknown) => {
    if (String(url).endsWith('/api/registry/surfaces')) await (++requests === 1 ? firstReady.promise : lastReady.promise);
    return new Response(JSON.stringify({ entries: [], conversations: [] }), { status: 200 });
  });
  const first = await mount(), last = await mount();
  firstReady.resolve(); await drain();
  expect(input(first).value).toBe(legacy);
  first.remove(); last.remove(); await drain();
  lastReady.resolve(); await drain();
  expect(localStorage.getItem(DRAFT)).toBe(legacy);
  noSend();
});

it('DS1 a real reload origin never applies an old URL task over successor raw B or disposes legacy A', async () => {
  vi.mocked(window.performance.getEntriesByType).mockReturnValue([{ type: 'reload' } as unknown as PerformanceEntry]);
  localStorage.setItem(DRAFT, 'newer raw B');
  window.location.hash = '#justsearch://surface/core.unified-chat-surface?query=old%20A&mode=ask&docIds=alpha.md';
  const shell = await mount(); await complete();
  expect(input(shell).value).toBe('newer raw B');
  expect(localStorage.getItem(DRAFT)).toBe('newer raw B');
  expect(localStorage.getItem(OLD_DRAFT)).toBe(legacy);
  expect(localStorage.getItem(MARKER)).toBe('kept-successor');
  expect(view(shell)!.shadowRoot!.querySelector('[data-testid=sv3-draft-review]')).not.toBeNull();
  input(shell).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); await drain();
  noSend(); expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/api/knowledge/search'))).toHaveLength(0);
});
