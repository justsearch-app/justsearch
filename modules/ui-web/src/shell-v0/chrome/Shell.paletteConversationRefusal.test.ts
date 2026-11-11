// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * SP4(b) — a palette opening that Search refuses leaves nothing of itself behind: the address, the
 * reload pointer and browser history still name the conversation Search shows, and reload reopens
 * it with its draft. The real Shell, router, URL projector and Search window; the draft departure is
 * refused through the existing path (the draft attachment write is refused).
 */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './Shell.js';
import '../views/search-v3/SearchV3View.js';
import { invokePaletteEntry, searchPaletteEntries } from '../commands/CommandPaletteProjection.js';
import type { SearchV3View } from '../views/search-v3/SearchV3View.js';
import type { Shell } from './Shell.js';
import { __resetConversationListForTest, getConversationListState, loadConversations } from '../state/conversationListStore.js';
import { readLastViewedConversation } from '../controllers/lastViewedConversation.js';
import { __resetForTest as resetCatalog, __seedForTest as seedCatalog } from '../../api/registry/SurfaceCatalogClient.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { __resetSurfaceSchemasForTest } from '../router/surfaceSchemas.js';
import { __resetStoreRegistryForTest } from '../router/storeRegistry.js';
import { __flushPendingWriteForTest, deactivateProjection } from '../router/URLProjector.js';
import { __resetJournalForTest, getJournalState } from '../state/NavigationJournal.js';
import { __resetUserConfigForTest, setActiveLayoutId, setSecondaryActiveSurface } from '../state/userConfigState.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { takeSearchV3Handoff } from '../utils/compose.js';
import { resetSearchState } from '../state/searchState.js';
import type { Surface } from '../../api/types/surface.js';
import { initLayoutCatalog } from '../layout/LayoutManifest.js';
// happy-dom 21 drops later entries on replaceState; the HTML spec keeps them (as Shell.navigation does).
import HistoryItemList from 'happy-dom/lib/history/HistoryItemList.js';

const SEARCH = 'core.search-v3-surface';
const LIBRARY = 'core.library-surface';
const SETTINGS = 'core.settings-surface';
const SECOND_SEARCH = 'vendor.second-search';

function surface(id: string, mountTag: string, extra: Partial<Surface> = {}): Surface {
  return { id, mountTag, presentation: { labelKey: id, descriptionKey: id }, audience: 'USER', placement: 'RAIL',
    consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra };
}
for (const tag of ['jf-refusal-library-probe', 'jf-refusal-settings-probe']) {
  if (!customElements.get(tag)) customElements.define(tag, class extends HTMLElement {});
}
(HistoryItemList.prototype as unknown as { replace: (item: unknown) => void }).replace = function (
  this: { items: unknown[]; currentItem: unknown }, item: unknown): void {
  const index = this.items.indexOf(this.currentItem);
  if (index === -1) throw new Error('Current history item not found');
  this.currentItem = item;
  this.items[index] = item;
};

async function drain(): Promise<void> { for (let i = 0; i < 6; i++) await new Promise<void>(r => setTimeout(r, 0)); }
async function settle(shell: Shell): Promise<void> { await drain(); await shell.updateComplete; await drain(); }
async function mount(): Promise<Shell> {
  const shell = document.createElement('jf-shell') as Shell;
  document.body.append(shell);
  await settle(shell);
  return shell;
}
function address(): string { return decodeURIComponent(window.location.hash.replace(/^#justsearch:\/\/surface\//, '')); }
function search(shell: Shell): SearchV3View | null {
  return shell.shadowRoot!.querySelector('jf-stage')!.shadowRoot!.querySelector('jf-sv3-window') as SearchV3View | null;
}
async function field(view: SearchV3View): Promise<HTMLTextAreaElement> {
  const composer = view.shadowRoot!.querySelector('jf-sv3-composer')! as HTMLElement & { updateComplete: Promise<unknown> };
  await composer.updateComplete;
  return composer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-input"]')!;
}
async function open(shell: Shell, id: string): Promise<void> {
  const entry = searchPaletteEntries('').find(row => row.entry.id === `conversation:${id}`)?.entry;
  expect(entry).toBeDefined();
  await invokePaletteEntry(entry!);
  await settle(shell);
  __flushPendingWriteForTest();
  await settle(shell);
}

beforeEach(() => {
  vi.spyOn(window.performance, 'getEntriesByType').mockImplementation(type => type === 'navigation'
    ? [{ type: 'navigate' } as unknown as PerformanceEntry] : []);
  localStorage.clear(); sessionStorage.clear();
  window.history.pushState(null, '', '/');
  resetCatalog(); __resetBootstrapForTest(); __resetSurfaceSchemasForTest(); __resetStoreRegistryForTest();
  __resetUserConfigForTest(); __resetJournalForTest(); __resetAiStateForTest(); deactivateProjection();
  __resetConversationListForTest(); takeSearchV3Handoff(); resetSearchState();
  seedCatalog({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
    surface(SEARCH, 'jf-sv3-window'), surface(LIBRARY, 'jf-refusal-library-probe'),
    surface(SETTINGS, 'jf-refusal-settings-probe', { placement: 'MODAL' }),
    // A contributed window may mount the host's Search element (a `jf-*` tag is admissible even for
    // an untrusted plugin), so the split layout can show two Search windows.
    surface(SECOND_SEARCH, 'jf-sv3-window', { provenance: { tier: 'TRUSTED_PLUGIN', contributorId: 'vendor', version: '1.0.0' } }),
  ] });
  vi.stubGlobal('fetch', vi.fn(async input => {
    const url = String(input);
    if (url.includes('/api/chat/conversations?')) return Response.json({ sessions: [
      { sessionId: 'conv-a', title: 'Conversation A', firstUserMessage: 'Question A', createdAtMs: 1, lastActiveAtMs: 2 },
      { sessionId: 'conv-b', title: 'Conversation B', firstUserMessage: 'Question B', createdAtMs: 1, lastActiveAtMs: 3 },
      { sessionId: 'conv-c', title: 'Conversation C', firstUserMessage: 'Question C', createdAtMs: 1, lastActiveAtMs: 4 },
    ] });
    if (url.includes('/api/chat/runs/live')) return Response.json({ runs: [] });
    if (url.includes('/api/thread/')) return Response.json({ conversationId: url.split('/').at(-1), events: [] });
    return Response.json({ entries: [] });
  }));
});
afterEach(() => {
  document.querySelectorAll('jf-shell').forEach(el => el.remove());
  deactivateProjection();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, '', '/');
});

for (const start of ['active', 'retained'] as const) {
  it(`SP4b ${start} Search refusing a palette opening keeps address, pointer, history and reload on the shown conversation`, async () => {
    const shell = await mount();
    await loadConversations(); await settle(shell);
    await open(shell, 'conv-a');
    const view = search(shell)!;
    const prepare = (name: string, detail: unknown): void => { view.shadowRoot!.querySelector('jf-sv3-composer')!
      .dispatchEvent(new CustomEvent(name, { bubbles: true, composed: true, detail })); };
    prepare('sv3-tier-change', { tier: 'structured' }); await settle(shell);
    prepare('sv3-schema-change', { text: '{"type":"object"}' }); await settle(shell);
    const input = await field(view);
    input.value = 'Keep this draft'; input.dispatchEvent(new Event('input')); await settle(shell);
    if (start === 'retained') { shell.activateSurface(LIBRARY, {}, 'RAIL'); await settle(shell); }
    const origin = window.location.hash, entries = window.history.length;
    expect([view.sessions.activeId, readLastViewedConversation(), view.tier]).toEqual(['conv-a', 'conv-a', 'structured']);

    const pushes = vi.spyOn(window.history, 'pushState');
    const set = sessionStorage.setItem.bind(sessionStorage);
    Object.defineProperty(sessionStorage, 'setItem', { configurable: true, value: (key: string, value: string) => {
      if (key.startsWith('justsearch.draft.attachment.')) throw new Error('Test storage refusal');
      set(key, value);
    } });
    try {
      await open(shell, 'conv-b');
    } finally { Object.defineProperty(sessionStorage, 'setItem', { configurable: true, value: set }); }

    // Search shows its conversation, its text and preparation.
    expect(search(shell)).toBe(view);
    expect(view.sessions.activeId).toBe('conv-a');
    expect((await field(view)).value).toBe('Keep this draft');
    expect(view.tier).toBe('structured');
    expect((view.shadowRoot!.querySelector('jf-sv3-composer') as unknown as { schemaDraft: string }).schemaDraft).toBe('{"type":"object"}');
    // Nothing of the refused opening remains: address, store, pointer and history name A.
    expect(address()).toBe(`${SEARCH}?conversationId=conv-a`);
    expect(getConversationListState().activeId).toBe('conv-a');
    expect(readLastViewedConversation()).toBe('conv-a');
    // Active Search: no entry at all. Retained Search: only the arrival in Search, showing A.
    expect(pushes).toHaveBeenCalledTimes(start === 'active' ? 0 : 1);
    expect(window.history.length).toBe(start === 'active' ? entries : entries + 1);
    // Search says why it stayed: its refused-save explanation. The address did not move, so the
    // view is not described as mismatched.
    expect(view.shadowRoot!.querySelector('[data-testid="sv3-residency-notice"] [role="alert"]')?.textContent).toMatch(/could not be saved safely/);
    // The address did not move, so the view is not described as mismatched.
    expect(view.shadowRoot!.querySelector('[data-testid="sv3-departure-notice"]')).toBeNull();

    // Reload reopens A with its draft.
    shell.remove(); __resetBootstrapForTest(); deactivateProjection(); __resetConversationListForTest();
    const reloaded = await mount();
    await loadConversations(); await settle(reloaded);
    expect(address()).toBe(`${SEARCH}?conversationId=conv-a`);
    expect(getConversationListState().activeId).toBe('conv-a');
    expect(readLastViewedConversation()).toBe('conv-a');
    const again = search(reloaded)!;
    expect(again.sessions.activeId).toBe('conv-a');
    expect((await field(again)).value).toBe('Keep this draft');

    // Back goes where it went before the attempt.
    window.history.back(); await settle(reloaded);
    if (start === 'retained') expect(window.location.hash).toBe(origin);
    else expect(address()).not.toContain('conv-b');
    expect(window.history.length).toBe(start === 'active' ? entries : entries + 1);
  });
}

/** The journal entry the app is on, as the conversation it names. */
function journalled(): string | undefined {
  const { entries, cursor } = getJournalState();
  return entries[cursor]?.url.match(/conversationId=([^&]+)/)?.[1];
}
/** Search shows A with a draft and a prepared request whose departure cannot be saved. */
async function draftOnA(shell: Shell): Promise<SearchV3View> {
  await loadConversations(); await settle(shell);
  await open(shell, 'conv-a');
  const view = search(shell)!;
  view.shadowRoot!.querySelector('jf-sv3-composer')!
    .dispatchEvent(new CustomEvent('sv3-tier-change', { bubbles: true, composed: true, detail: { tier: 'structured' } }));
  await settle(shell);
  const input = await field(view);
  input.value = 'Keep this draft'; input.dispatchEvent(new Event('input')); await settle(shell);
  return view;
}
async function refusedOpen(shell: Shell, id: string): Promise<void> {
  const set = sessionStorage.setItem.bind(sessionStorage);
  Object.defineProperty(sessionStorage, 'setItem', { configurable: true, value: (key: string, value: string) => {
    if (key.startsWith('justsearch.draft.attachment.')) throw new Error('Test storage refusal');
    set(key, value);
  } });
  try { await open(shell, id); } finally { Object.defineProperty(sessionStorage, 'setItem', { configurable: true, value: set }); }
}

for (const start of ['active', 'retained'] as const) {
  it(`SP4b ${start} Search refusing a palette opening journals the conversation it shows, so a bare launch reopens it with its draft`, async () => {
    const shell = await mount();
    const view = await draftOnA(shell);
    if (start === 'retained') { shell.activateSurface(LIBRARY, {}, 'RAIL'); await settle(shell); }
    const before = getJournalState();
    await refusedOpen(shell, 'conv-b');
    expect(view.sessions.activeId).toBe('conv-a');

    // The journal's latest entry is the one Search shows, and it marks the history entry the app is on.
    const after = getJournalState();
    expect(after.cursor).toBe(after.entries.length - 1);
    expect(after.entries.map(entry => entry.surfaceId)).toEqual(start === 'active'
      ? before.entries.map(entry => entry.surfaceId) : [...before.entries.map(entry => entry.surfaceId), SEARCH]);
    expect(journalled()).toBe('conv-a');
    expect(after.entries.some(entry => entry.url.includes('conv-b'))).toBe(false);
    expect(window.history.state?.navigationJournalId).toBe(after.entries[after.cursor]!.id);

    // A bare launch (no address) restores the last journal entry: Search on A with its draft.
    shell.remove(); __resetBootstrapForTest(); deactivateProjection(); __resetConversationListForTest();
    window.history.replaceState(null, '', '/');
    const launched = await mount();
    await loadConversations(); await settle(launched);
    expect(address()).toBe(`${SEARCH}?conversationId=conv-a`);
    const again = search(launched)!;
    expect(again.sessions.activeId).toBe('conv-a');
    expect((await field(again)).value).toBe('Keep this draft');
  });
}

it('SP4b in a split layout with two Search windows, one window refusing a palette opening moves only its own conversation and address', async () => {
  initLayoutCatalog(); setActiveLayoutId('core.split'); setSecondaryActiveSurface(SECOND_SEARCH);
  const shell = await mount();
  const stage = shell.shadowRoot!.querySelector('jf-stage')!;
  const windows = (): SearchV3View[] => [...stage.shadowRoot!.querySelectorAll('jf-sv3-window')] as SearchV3View[];
  expect(windows()).toHaveLength(2);
  // Opening A is for the Search window the address names. The second Search window refuses to
  // depart (its recovery save is refused beside the first window's), and that refusal must not
  // take A away from the first window.
  const primary = await draftOnA(shell);
  const [first, second] = windows();
  expect(first).toBe(primary);
  expect([primary.sessions.activeId, second!.sessions.activeId]).toEqual(['conv-a', null]);
  expect(address()).toBe(`${SEARCH}?conversationId=conv-a`);
  const pointer = readLastViewedConversation();
  const pushes = vi.spyOn(window.history, 'pushState');

  await refusedOpen(shell, 'conv-b');

  // The first window refused: it keeps A and its draft, and the address and store name A again.
  // The second window was not moved, neither by the opening nor by the first window's refusal.
  expect(primary.sessions.activeId).toBe('conv-a');
  expect((await field(primary)).value).toBe('Keep this draft');
  expect(second!.sessions.activeId).toBeNull();
  expect(address()).toBe(`${SEARCH}?conversationId=conv-a`);
  expect(getConversationListState().activeId).toBe('conv-a');
  expect(readLastViewedConversation()).toBe(pointer);
  expect(journalled()).toBe('conv-a');
  expect(pushes).not.toHaveBeenCalled();
});
