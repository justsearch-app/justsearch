// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Shell slice 2 — FR3 (Back and Forward), FR6 (deep links and saved navigation state), C9 (Settings
 * dismissal history) at the frame: the real Shell, router, journal and URL projector, over probe
 * windows. A browser-made arrival (a pasted or opened `#justsearch://` address) is reproduced the way
 * Chromium delivers it — a new history entry with no state, then `popstate` — because happy-dom's
 * `location.hash` assignment fires no popstate.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LitElement, html } from 'lit';
import './Shell.js';
import '../views/search-v3/SearchV3View.js';
import * as paletteProjection from '../commands/CommandPaletteProjection.js';
import type { SearchV3View } from '../views/search-v3/SearchV3View.js';
import { loadConversations, getConversationListState } from '../state/conversationListStore.js';
import { readLastViewedConversation } from '../controllers/lastViewedConversation.js';
import '../components/SurfaceTabs.js';
import type { Shell } from './Shell.js';
import type { SettingsWindow } from './SettingsWindow.js';
import { __resetForTest as resetCatalog, __seedForTest as seedCatalog } from '../../api/registry/SurfaceCatalogClient.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { __resetSurfaceSchemasForTest } from '../router/surfaceSchemas.js';
import { __resetStoreRegistryForTest } from '../router/storeRegistry.js';
import { deactivateProjection } from '../router/URLProjector.js';
import { subscribeMemberTab, takeMemberTabIntent } from '../router/memberTabIntent.js';
import { __resetJournalForTest, getJournalState } from '../state/NavigationJournal.js';
import { __resetUserConfigForTest, getUserConfig, setSurfaceVisibility } from '../state/userConfigState.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { __resetInMemoryStateForTest, getDocument, getPluginSetting, getRecentCommandIds } from '../state/UserStateDocument.js';
import { getSavedViews } from '../state/savedViewState.js';
import { __resetConversationListForTest } from '../state/conversationListStore.js';
import { takeSearchV3Handoff } from '../utils/compose.js';
import { resetSearchState } from '../state/searchState.js';
import type { Surface } from '../../api/types/surface.js';
// happy-dom 21's session history drops the entries after the current one on `replaceState`; the
// HTML spec (and Chromium/WebView2/WebKit) keep them. Forward is only testable with the spec's
// behaviour, so this file restores it for its own run.
import HistoryItemList from 'happy-dom/lib/history/HistoryItemList.js';

const SEARCH = 'core.search-v3-surface';
const LIBRARY = 'core.library-surface';
const BROWSE = 'core.browse-surface';
const BRAIN = 'core.brain-surface';
const SETTINGS = 'core.settings-surface';
const SYSTEM = 'core.system-surface';
const HELP = 'core.help-surface';

function surface(id: string, mountTag: string, extra: Partial<Surface> = {}): Surface {
  return { id, mountTag, presentation: { labelKey: id, descriptionKey: id }, audience: 'USER', placement: 'RAIL',
    consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra };
}

/** A window host following the documented host contract (LibrarySurface, BrainSurface, SystemSurface). */
class ProbeHost extends LitElement {
  static override properties = { activeTab: { state: true } };
  declare activeTab: string;
  private unsub: (() => void) | null = null;
  constructor() { super(); this.activeTab = 'folders'; }
  override connectedCallback(): void {
    super.connectedCallback();
    const requested = takeMemberTabIntent(LIBRARY);
    if (requested) this.activeTab = requested;
    this.unsub = subscribeMemberTab((host, member) => {
      if (host !== LIBRARY) return false;
      this.activeTab = member;
      return true;
    });
  }
  override disconnectedCallback(): void { super.disconnectedCallback(); this.unsub?.(); }
  override render() {
    return html`<jf-surface-tabs tablist-label="Library views" active-id=${this.activeTab}
      .items=${[{ id: 'folders', label: 'Folders', slot: 'tab-folders' }, { id: BROWSE, label: 'Browse', content: () => html`<p>browse</p>` }]}
      @tab-change=${(e: CustomEvent<{ id: string }>) => { this.activeTab = e.detail.id; }}>
      <div slot="tab-folders"><button>Add folder</button></div></jf-surface-tabs>`;
  }
}
for (const [tag, klass] of [['jf-nav-host-probe', ProbeHost]] as const) if (!customElements.get(tag)) customElements.define(tag, klass);
for (const tag of ['jf-nav-search-probe', 'jf-nav-brain-probe', 'jf-nav-system-probe', 'jf-nav-help-probe', 'jf-nav-settings-probe']) {
  if (!customElements.get(tag)) customElements.define(tag, class extends HTMLElement {});
}

function seed(): void {
  seedCatalog({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
    surface(SEARCH, 'jf-nav-search-probe'),
    surface(LIBRARY, 'jf-nav-host-probe', { members: [BROWSE] }),
    surface(BROWSE, 'jf-nav-browse-unused', { placement: 'DEEPLINK' }),
    surface(BRAIN, 'jf-nav-brain-probe'),
    surface(SYSTEM, 'jf-nav-system-probe'),
    surface(HELP, 'jf-nav-help-probe', { placement: 'DEEPLINK' }),
    surface(SETTINGS, 'jf-nav-settings-probe', { placement: 'MODAL' }),
  ] });
}

let schemas: Promise<void> | null = null;
async function drain(): Promise<void> {
  for (let i = 0; i < 6; i++) await new Promise<void>(r => setTimeout(r, 0));
}
async function mount(): Promise<Shell> {
  const shell = document.createElement('jf-shell') as Shell;
  document.body.append(shell);
  await shell.updateComplete;
  await drain();
  if (schemas) await schemas;
  await drain();
  await shell.updateComplete;
  return shell;
}
function stageTag(shell: Shell): string | undefined {
  const stage = shell.shadowRoot!.querySelector('jf-stage')!;
  return [...stage.shadowRoot!.children].find(c => c.localName.startsWith('jf-'))?.localName;
}
function address(): string { return decodeURIComponent(window.location.hash.replace(/^#justsearch:\/\/surface\//, '')); }
function key(init: KeyboardEventInit): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
  document.dispatchEvent(event);
  return event;
}
async function settle(shell: Shell): Promise<void> { await drain(); await shell.updateComplete; await drain(); }
/** An area choice the way the rail dispatched it and the switcher dispatches it now (RAIL transport). */
async function go(shell: Shell, id: string): Promise<void> {
  shell.activateSurface(id, {}, 'RAIL');
  await settle(shell);
}
async function area(shell: Shell, id: string): Promise<void> {
  const bar = shell.shadowRoot!.querySelector('jf-top-bar')!;
  bar.shadowRoot!.querySelector<HTMLButtonElement>(`nav [data-surface-id="${id}"]`)!.click();
  await settle(shell);
}
/** A browser-made arrival: Chromium pushes an entry without state and fires popstate. */
async function arrive(shell: Shell, url: string): Promise<void> {
  window.history.pushState(null, '', `#${url}`);
  window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
  await settle(shell);
}
async function browserBack(shell: Shell): Promise<void> { window.history.back(); await settle(shell); }
async function browserForward(shell: Shell): Promise<void> { window.history.forward(); await settle(shell); }
function hostTab(shell: Shell): string | undefined {
  const host = shell.shadowRoot!.querySelector('jf-stage')!.shadowRoot!.querySelector('jf-nav-host-probe') as ProbeHost | null;
  return host?.activeTab;
}

const replaceWithoutTruncation = function (this: { items: unknown[]; currentItem: unknown }, item: unknown): void {
  const index = this.items.indexOf(this.currentItem);
  if (index === -1) throw new Error('Current history item not found');
  this.currentItem = item;
  this.items[index] = item;
};
(HistoryItemList.prototype as unknown as { replace: typeof replaceWithoutTruncation }).replace = replaceWithoutTruncation;

beforeEach(() => {
  vi.spyOn(window.performance, 'getEntriesByType').mockImplementation(type => type === 'navigation'
    ? [{ type: 'navigate' } as unknown as PerformanceEntry] : []);
  localStorage.clear(); sessionStorage.clear();
  // A fresh entry: everything a previous test left after the current entry is discarded.
  window.history.pushState(null, '', '/');
  resetCatalog(); __resetBootstrapForTest(); __resetSurfaceSchemasForTest(); __resetStoreRegistryForTest();
  __resetUserConfigForTest(); __resetJournalForTest(); __resetAiStateForTest(); deactivateProjection();
  __resetConversationListForTest(); takeSearchV3Handoff(); resetSearchState();
  seed();
  schemas = null;
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ entries: [] }),
    { status: 200, headers: { 'Content-Type': 'application/json' } })));
});
afterEach(() => {
  document.querySelectorAll('jf-shell').forEach(el => el.remove());
  deactivateProjection();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, '', '/');
});

describe('FR3 Back and Forward run on browser history, which the journal follows', () => {
  it('(a) an incoming same-window address (conversation B while A is shown) keeps A: Back returns to A', async () => {
    const shell = await mount();
    await arrive(shell, `justsearch://surface/${SEARCH}?conversationId=conv-a`);
    await arrive(shell, `justsearch://surface/${SEARCH}?conversationId=conv-b`);
    const journal = getJournalState();
    expect(journal.entries.map(e => e.url).slice(-2)).toEqual([
      `justsearch://surface/${SEARCH}?conversationId=conv-a`, `justsearch://surface/${SEARCH}?conversationId=conv-b`]);
    key({ key: 'ArrowLeft', altKey: true });
    await settle(shell);
    expect(address()).toBe(`${SEARCH}?conversationId=conv-a`);
    expect(getJournalState().entries[getJournalState().cursor]!.url).toBe(`justsearch://surface/${SEARCH}?conversationId=conv-a`);
    key({ key: 'ArrowRight', altKey: true });
    await settle(shell);
    expect(address()).toBe(`${SEARCH}?conversationId=conv-b`);
  });

  it('(b) Back then Forward after Search → Library returns to Library, and browser history agrees', async () => {
    const shell = await mount();
    expect(stageTag(shell)).toBe('jf-nav-search-probe');
    await go(shell, LIBRARY);
    expect(stageTag(shell)).toBe('jf-nav-host-probe');
    expect(key({ key: 'ArrowLeft', altKey: true }).defaultPrevented).toBe(true);
    await settle(shell);
    expect([stageTag(shell), address()]).toEqual(['jf-nav-search-probe', SEARCH]);
    // The later view is still Forward of here — for the browser's own Forward too.
    await browserForward(shell);
    expect([stageTag(shell), address()]).toEqual(['jf-nav-host-probe', LIBRARY]);
    await browserBack(shell);
    key({ key: 'ArrowRight', altKey: true });
    await settle(shell);
    expect([stageTag(shell), address()]).toEqual(['jf-nav-host-probe', LIBRARY]);
  });

  it('(c) Library → Search → Library by area clicks, then browser Back and Forward: stage, address and journal stay in step', async () => {
    const shell = await mount();
    await go(shell, LIBRARY);
    await go(shell, SEARCH);
    await go(shell, LIBRARY);
    const before = getJournalState();
    expect(before.entries.map(e => e.surfaceId)).toEqual([SEARCH, LIBRARY, SEARCH, LIBRARY]);
    await browserBack(shell);
    expect([stageTag(shell), address()]).toEqual(['jf-nav-search-probe', SEARCH]);
    // The journal moves its cursor to the entry the browser returned to; it does not append one.
    expect(getJournalState().entries).toHaveLength(4);
    expect(getJournalState().cursor).toBe(2);
    await browserForward(shell);
    expect([stageTag(shell), address()]).toEqual(['jf-nav-host-probe', LIBRARY]);
    expect(getJournalState().cursor).toBe(3);
    // Clicking the area that is already shown keeps it shown and adds no entry.
    const length = window.history.length;
    await go(shell, LIBRARY);
    expect([stageTag(shell), address(), window.history.length]).toEqual(['jf-nav-host-probe', LIBRARY, length]);
  });

  it('(c) with a conversation open in Search, browser Back and Forward move the journal cursor instead of appending entries', async () => {
    // Reproduced on a07eb770d6 in Chromium with the sv3-sources fixtures: each browser traversal
    // back to the conversation appended a journal entry, so the journal and history drifted apart.
    const shell = await mount();
    shell.activateSurface(SEARCH, { conversationId: 'conv-1' }, 'RAIL');
    await settle(shell);
    await go(shell, LIBRARY);
    shell.activateSurface(SEARCH, { conversationId: 'conv-1' }, 'RAIL');
    await settle(shell);
    await go(shell, LIBRARY);
    const count = getJournalState().entries.length;
    await browserBack(shell);
    expect([stageTag(shell), address()]).toEqual(['jf-nav-search-probe', `${SEARCH}?conversationId=conv-1`]);
    expect(getJournalState().entries).toHaveLength(count);
    expect(getJournalState().entries[getJournalState().cursor]!.url).toBe(`justsearch://surface/${SEARCH}?conversationId=conv-1`);
    await browserForward(shell);
    expect([stageTag(shell), address()]).toEqual(['jf-nav-host-probe', LIBRARY]);
    expect(getJournalState().entries).toHaveLength(count);
    expect(getJournalState().cursor).toBe(count - 1);
  });

  it('Back on the first view the app owns stays in the app; the mouse back and forward buttons are Back and Forward', async () => {
    const shell = await mount();
    const back = vi.spyOn(window.history, 'back');
    key({ key: 'ArrowLeft', altKey: true });
    expect(back).not.toHaveBeenCalled();
    await area(shell, BRAIN);
    const up = new MouseEvent('mouseup', { button: 3, bubbles: true, cancelable: true });
    window.dispatchEvent(up);
    await settle(shell);
    expect(up.defaultPrevented).toBe(true);
    expect(stageTag(shell)).toBe('jf-nav-search-probe');
    window.dispatchEvent(new MouseEvent('mouseup', { button: 4, bubbles: true, cancelable: true }));
    await settle(shell);
    expect(stageTag(shell)).toBe('jf-nav-brain-probe');
  });

  it('rapid area switching lands on the last area, with each step on the Back stack', async () => {
    const shell = await mount();
    for (const code of ['Digit2', 'Digit3', 'Digit1', 'Digit2']) key({ key: code.slice(-1), code, ctrlKey: true });
    await settle(shell);
    expect([stageTag(shell), address()]).toEqual(['jf-nav-host-probe', LIBRARY]);
    expect(getJournalState().entries.map(e => e.surfaceId)).toEqual([SEARCH, LIBRARY, BRAIN, SEARCH, LIBRARY]);
    await browserBack(shell);
    expect(stageTag(shell)).toBe('jf-nav-search-probe');
  });

  it('a late boot response after the reader switched areas does not move them back', async () => {
    let release!: () => void;
    const held = new Promise<void>(r => { release = r; });
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('schema')) await held;
      return new Response(JSON.stringify({ entries: [] }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
    window.history.replaceState(null, '', `#justsearch://surface/${BRAIN}`);
    const shell = document.createElement('jf-shell') as Shell;
    document.body.append(shell);
    await settle(shell);
    key({ key: '2', code: 'Digit2', ctrlKey: true });
    await settle(shell);
    release();
    await settle(shell);
    expect([stageTag(shell), address()]).toEqual(['jf-nav-host-probe', LIBRARY]);
  });

  it('reload mid-navigation keeps the view and its Back step', async () => {
    let shell = await mount();
    await area(shell, LIBRARY);
    await area(shell, BRAIN);
    shell.remove();
    __resetBootstrapForTest(); deactivateProjection();
    shell = await mount();
    expect([stageTag(shell), address()]).toEqual(['jf-nav-brain-probe', BRAIN]);
    key({ key: 'ArrowLeft', altKey: true });
    await settle(shell);
    expect([stageTag(shell), address()]).toEqual(['jf-nav-host-probe', LIBRARY]);
  });
});

describe('FR3 the window tab is part of the address', () => {
  it('a tab click names the tab in the address as a new entry; Back returns to the tab before; Forward and reload restore it', async () => {
    let shell = await mount();
    await area(shell, LIBRARY);
    const length = window.history.length;
    const journal = getJournalState().entries.length;
    const tabs = shell.shadowRoot!.querySelector('jf-stage')!.shadowRoot!.querySelector('jf-nav-host-probe')!
      .shadowRoot!.querySelector('jf-surface-tabs')!;
    tabs.shadowRoot!.querySelector<HTMLButtonElement>(`button[data-tab-id="${BROWSE}"]`)!.click();
    await settle(shell);
    expect(address()).toBe(`${LIBRARY}?tab=${BROWSE}`);
    // A tab is a step Back can return to (orchestrator decision, FR3); the journal keeps one entry per window.
    expect(window.history.length).toBe(length + 1);
    expect(getJournalState().entries).toHaveLength(journal);
    await browserBack(shell);
    expect([stageTag(shell), hostTab(shell), address()]).toEqual(['jf-nav-host-probe', 'folders', `${LIBRARY}?tab=folders`]);
    await browserForward(shell);
    expect([hostTab(shell), address()]).toEqual([BROWSE, `${LIBRARY}?tab=${BROWSE}`]);
    await area(shell, SEARCH);
    await browserBack(shell);
    expect([hostTab(shell), address()]).toEqual([BROWSE, `${LIBRARY}?tab=${BROWSE}`]);
    // Reload on the tab: a fresh shell opens the tab the address names.
    shell.remove();
    __resetBootstrapForTest(); deactivateProjection();
    shell = await mount();
    expect([stageTag(shell), hostTab(shell)]).toEqual(['jf-nav-host-probe', BROWSE]);
  });

  it('returning to a window by the switcher keeps its tab in the address', async () => {
    const shell = await mount();
    await arrive(shell, `justsearch://surface/${LIBRARY}?tab=${BROWSE}`);
    expect(hostTab(shell)).toBe(BROWSE);
    await area(shell, BRAIN);
    await area(shell, LIBRARY);
    expect([hostTab(shell), address()]).toEqual([BROWSE, `${LIBRARY}?tab=${BROWSE}`]);
  });
});

describe('FR6 deep links land on the right window and tab', () => {
  it.each([
    [SEARCH, 'jf-nav-search-probe', SEARCH],
    [LIBRARY, 'jf-nav-host-probe', LIBRARY],
    [BRAIN, 'jf-nav-brain-probe', BRAIN],
    [SYSTEM, 'jf-nav-system-probe', SYSTEM],
    [HELP, 'jf-nav-help-probe', HELP],
    ['core.unified-chat-surface', 'jf-nav-search-probe', SEARCH],
    ['core.search-surface', 'jf-nav-search-probe', SEARCH],
  ])('justsearch://surface/%s opens %s', async (id, tag, active) => {
    window.history.replaceState(null, '', `#justsearch://surface/${id}`);
    const shell = await mount();
    expect([stageTag(shell), shell.activeId]).toEqual([tag, active]);
  });

  it('a member id opens its host on that tab, and the address names the tab', async () => {
    window.history.replaceState(null, '', `#justsearch://surface/${BROWSE}`);
    const shell = await mount();
    expect([stageTag(shell), hostTab(shell), address()]).toEqual(['jf-nav-host-probe', BROWSE, `${LIBRARY}?tab=${BROWSE}`]);
  });

  it('Settings opens over the window it was reached from', async () => {
    const shell = await mount();
    await area(shell, LIBRARY);
    await arrive(shell, `justsearch://surface/${SETTINGS}`);
    expect(shell.shadowRoot!.querySelector<SettingsWindow>('jf-settings-window')!.open).toBe(true);
    expect(stageTag(shell)).toBe('jf-nav-host-probe');
  });
});

describe('C9 / FR3 the Settings section is part of the address', () => {
  function settingsWindow(shell: Shell): SettingsWindow {
    return shell.shadowRoot!.querySelector<SettingsWindow>('jf-settings-window')!;
  }

  it('a section deep link opens that section; a section change replaces the address; Escape closes once onto the area', async () => {
    const requested: string[] = [];
    const stop = subscribeMemberTab((host, member) => { if (host === SETTINGS) requested.push(member); return false; });
    try {
      const shell = await mount();
      await area(shell, LIBRARY);
      const length = window.history.length;
      settingsWindow(shell).querySelector('*');
      shell.shadowRoot!.querySelector('jf-top-bar')!.shadowRoot!
        .querySelector<HTMLButtonElement>('[data-surface-id="core.settings-surface"]')!.click();
      await settle(shell);
      expect(settingsWindow(shell).open).toBe(true);
      expect(window.history.length).toBe(length + 1);
      // The Settings surface reports its section; the address follows without a new entry.
      settingsWindow(shell).dispatchEvent(new CustomEvent('settings-section-change',
        { detail: { id: 'privacy-trust' }, bubbles: true, composed: true }));
      expect(address()).toBe(`${SETTINGS}?section=security-privacy`);
      expect(window.history.length).toBe(length + 1);
      // Escape closes in one step onto the area underneath; no second Back is needed.
      const back = vi.spyOn(window.history, 'back');
      settingsWindow(shell).shadowRoot!.querySelector('dialog')!.dispatchEvent(new Event('cancel', { cancelable: true }));
      await settle(shell);
      expect(back).toHaveBeenCalledTimes(1);
      expect([settingsWindow(shell).open, stageTag(shell), address()]).toEqual([false, 'jf-nav-host-probe', LIBRARY]);
      // A section address (reload, deep link, Forward) opens that section.
      await arrive(shell, `justsearch://surface/${SETTINGS}?section=appearance`);
      expect(settingsWindow(shell).open).toBe(true);
      expect(requested).toContain('appearance');
      // An unknown section is ignored rather than forwarded.
      await arrive(shell, `justsearch://surface/${SETTINGS}?section=nope`);
      expect(requested).not.toContain('nope');
    } finally {
      stop();
    }
  });

  it('browser Back closes Settings once and keeps the area; Forward reopens it', async () => {
    const shell = await mount();
    await area(shell, BRAIN);
    shell.shadowRoot!.querySelector('jf-top-bar')!.shadowRoot!
      .querySelector<HTMLButtonElement>('[data-surface-id="core.settings-surface"]')!.click();
    await settle(shell);
    await browserBack(shell);
    expect([settingsWindow(shell).open, stageTag(shell), address()]).toEqual([false, 'jf-nav-brain-probe', BRAIN]);
    await browserForward(shell);
    expect([settingsWindow(shell).open, stageTag(shell)]).toEqual([true, 'jf-nav-brain-probe']);
  });

  it('a deep link arriving while Settings is open closes it and lands on the window and tab', async () => {
    const shell = await mount();
    shell.shadowRoot!.querySelector('jf-top-bar')!.shadowRoot!
      .querySelector<HTMLButtonElement>('[data-surface-id="core.settings-surface"]')!.click();
    await settle(shell);
    expect(settingsWindow(shell).open).toBe(true);
    await arrive(shell, `justsearch://surface/${BROWSE}`);
    expect([settingsWindow(shell).open, stageTag(shell), hostTab(shell)]).toEqual([false, 'jf-nav-host-probe', BROWSE]);
  });
});

describe('FR6 / C11 saved navigation state and bookmarks load after the upgrade', () => {
  it('a pre-change journal resumes the last view; bookmarks and co-resident values survive a userState write', async () => {
    // The journal and user-state documents exactly as the title-bar build wrote them.
    localStorage.setItem('justsearch.navigationJournal.v1', JSON.stringify({ cursor: 1, entries: [
      { id: 'j1', surfaceId: 'core.unified-chat-surface', url: 'justsearch://surface/core.unified-chat-surface', label: 'Chat', transport: 'RAIL', timestamp: 1 },
      { id: 'j2', surfaceId: BRAIN, url: `justsearch://surface/${BRAIN}`, label: 'AI Brain', transport: 'RAIL', timestamp: 2 },
    ] }));
    localStorage.setItem('justsearch.userState.v2', JSON.stringify({
      version: 2, activeProfileId: 'default',
      profiles: { default: { id: 'default', label: 'Default', userConfig: { version: 1, railExpanded: true, surfaceOrder: [BRAIN] },
        activeThemeId: null, pinnedSearches: [{ id: 'pin-1', query: 'invoices', pinnedAt: 1 }],
        keybindingOverrides: [{ key: 'mod+j', commandId: 'shell.toggle-palette', source: 'user' }],
        savedViews: [{ id: 'sv-1', label: 'Library', url: `justsearch://surface/${LIBRARY}`, surfaceId: LIBRARY, savedAt: 1 }],
        viewerAudience: 'OPERATOR' } },
      pluginSettings: { 'vendor.acme': { colour: 'teal' } }, recentCommandIds: ['shell.go-to-library'], chatWidth: 'wide',
    }));
    // A page load reads both documents from storage (the journal re-reads on first use after its reset).
    __resetInMemoryStateForTest();
    const shell = await mount();
    expect([stageTag(shell), shell.activeId]).toEqual(['jf-nav-brain-probe', BRAIN]);
    expect(getJournalState().entries.map(e => e.surfaceId).slice(0, 2)).toEqual([SEARCH, BRAIN]);
    expect(getSavedViews().map(v => v.id)).toEqual(['sv-1']);
    // A userState write made by the new frame (hiding a window) keeps every co-resident value.
    setSurfaceVisibility(SYSTEM, false);
    const doc = getDocument();
    expect(doc.pinnedSearches.map(p => p.id)).toEqual(['pin-1']);
    expect(doc.keybindingOverrides).toEqual([{ key: 'mod+j', commandId: 'shell.toggle-palette', source: 'user' }]);
    expect(doc.savedViews?.map(v => v.id)).toEqual(['sv-1']);
    expect(doc.viewerAudience).toBe('OPERATOR');
    expect(getPluginSetting('vendor.acme', 'colour')).toBe('teal');
    expect(getRecentCommandIds()).toEqual(['shell.go-to-library']);
    expect(doc.chatWidth).toBe('wide');
    expect(getUserConfig().railExpanded).toBe(true);
    expect(getUserConfig().surfaceOrder).toEqual([BRAIN]);
    expect(getUserConfig().surfaceVisibility).toEqual({ [SYSTEM]: false });
  });
});

describe('SG1 Settings command and default chord', () => {
  it('SG1 Ctrl+comma opens over Search, repeats in place and honours typing, IME, repeat and modifier guards', async () => {
    const shell = await mount();
    const input = document.createElement('input'); document.body.append(input); input.focus();
    for (const extra of [{ repeat: true }, { isComposing: true }, { shiftKey: true }, { altKey: true }, { metaKey: true }]) {
      key({ key: ',', ctrlKey: true, ...extra }); await settle(shell);
      expect(shell.shadowRoot!.querySelector<SettingsWindow>('jf-settings-window')!.open).toBe(false);
    }
    key({ key: ',', ctrlKey: true }); await settle(shell);
    const win = shell.shadowRoot!.querySelector<SettingsWindow>('jf-settings-window')!;
    expect(win.open).toBe(true); expect(stageTag(shell)).toBe('jf-nav-search-probe');
    const length = window.history.length, hash = window.location.hash;
    key({ key: ',', ctrlKey: true }); await settle(shell);
    expect(win.open).toBe(true); expect(window.location.hash).toBe(hash); expect(window.history.length).toBe(length);
    input.remove();
  });
});

it.each(['section=layout&entry=keyboard', 'category=layout&entry=keyboard', 'tab=layout&anchor=keyboard'])(
  'SG4 legacy coordinate %s arrives at General and the Keyboard anchor', async query => {
    const shell = await mount();
    const requested: { section: string; anchor?: string }[] = [];
    const stop = subscribeMemberTab((host, section, anchor) => {
      if (host === SETTINGS) requested.push({ section, anchor });
    });
    try {
      await arrive(shell, `justsearch://surface/${SETTINGS}?${query}`);
      expect(address()).toBe(`${SETTINGS}?anchor=keyboard&section=general`);
      expect(requested.at(-1)).toEqual({ section: 'general', anchor: 'keyboard' });
    } finally { stop(); }
  },
);

describe('SP4b palette conversation arrivals are one navigation through Search address selection', () => {
  async function setup(start: 'active' | 'cold' | 'retained'): Promise<Shell> {
    resetCatalog();
    seedCatalog({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
      surface(SEARCH, 'jf-sv3-window'), surface(LIBRARY, 'jf-nav-host-probe'), surface(BRAIN, 'jf-nav-brain-probe'),
      surface(SETTINGS, 'jf-nav-settings-probe', { placement: 'MODAL' }),
    ] });
    vi.stubGlobal('fetch', vi.fn(async input => {
      const url = String(input);
      if (url.includes('/api/chat/conversations?')) return Response.json({ sessions: [
        { sessionId: 'conv-a', title: 'Conversation A', firstUserMessage: 'Question A', createdAtMs: 1, lastActiveAtMs: 2 },
        { sessionId: 'conv-b', title: 'Conversation B', firstUserMessage: 'Question B', createdAtMs: 1, lastActiveAtMs: 3 },
      ] });
      if (url.includes('/api/chat/runs/live')) return Response.json({ runs: [] });
      if (url.includes('/api/thread/')) return Response.json({ conversationId: url.split('/').at(-1), events: [] });
      return Response.json({ entries: [] });
    }));
    if (start === 'cold') window.history.replaceState(null, '', `#justsearch://surface/${LIBRARY}`);
    const shell = await mount();
    if (start === 'retained') await go(shell, LIBRARY);
    await loadConversations(); await settle(shell);
    return shell;
  }
  async function open(shell: Shell, id: string): Promise<void> {
    const entry = paletteProjection.searchPaletteEntries('').find(row => row.entry.id === `conversation:${id}`)?.entry;
    expect(entry).toBeDefined();
    if (entry) await paletteProjection.invokePaletteEntry?.(entry);
    await settle(shell);
    // The conversation projector settles on its own debounce; no fixed delay.
    const { __flushPendingWriteForTest } = await import('../router/URLProjector.js'); __flushPendingWriteForTest(); await settle(shell);
  }
  function search(shell: Shell): SearchV3View | null {
    return shell.shadowRoot!.querySelector('jf-stage')!.shadowRoot!.querySelector('jf-sv3-window') as SearchV3View | null;
  }
  async function field(shell: Shell): Promise<HTMLTextAreaElement> {
    const composer = search(shell)!.shadowRoot!.querySelector('jf-sv3-composer')! as HTMLElement & { updateComplete: Promise<unknown> };
    await composer.updateComplete;
    return composer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-input"]')!;
  }
  for (const start of ['active', 'cold', 'retained'] as const) {
    it(`SP4b ${start} Search opens exactly the selected conversation with one history transition and keeps Back Forward and reload correct`, async () => {
      const shell = await setup(start);
      const before = window.location.hash;
      const pushes = vi.spyOn(window.history, 'pushState');
      await open(shell, 'conv-b');
      expect(search(shell)?.isConnected).toBe(true);
      expect(search(shell)!.sessions.activeId).toBe('conv-b');
      expect(getConversationListState().activeId).toBe('conv-b');
      expect(readLastViewedConversation()).toBe('conv-b');
      expect(address()).toBe(`${SEARCH}?conversationId=conv-b`);
      expect(pushes).toHaveBeenCalledTimes(1);
      await browserBack(shell); expect(window.location.hash).toBe(before);
      await browserForward(shell); expect(address()).toBe(`${SEARCH}?conversationId=conv-b`);
      expect(search(shell)!.sessions.activeId).toBe('conv-b');
      shell.remove(); __resetBootstrapForTest(); deactivateProjection(); const reloaded = await mount();
      expect(getConversationListState().activeId).toBe('conv-b'); expect(address()).toBe(`${SEARCH}?conversationId=conv-b`);
      expect(search(reloaded)!.sessions.activeId).toBe('conv-b');
    });
  }
  for (const start of ['active', 'retained'] as const) {
    it(`SP4b ${start} refused departure keeps draft, preparation, shown conversation and reload pointer; Back returns`, async () => {
      const shell = await setup('active'); await open(shell, 'conv-a');
      const view = search(shell)!;
      const prepare = (name: string, detail: unknown): void => { view.shadowRoot!.querySelector('jf-sv3-composer')!
        .dispatchEvent(new CustomEvent(name, { bubbles: true, composed: true, detail })); };
      prepare('sv3-tier-change', { tier: 'structured' }); await settle(shell);
      prepare('sv3-schema-change', { text: '{"type":"object"}' }); await settle(shell);
      const input = await field(shell);
      input.value = 'Keep this draft'; input.dispatchEvent(new Event('input')); await settle(shell);
      if (start === 'retained') await go(shell, LIBRARY);
      const origin = window.location.hash, pointer = readLastViewedConversation(), tier = view.tier;
      expect([view.sessions.activeId, pointer, tier]).toEqual(['conv-a', 'conv-a', 'structured']);
      const pushes = vi.spyOn(window.history, 'pushState');
      const set = sessionStorage.setItem.bind(sessionStorage);
      Object.defineProperty(sessionStorage, 'setItem', { configurable: true, value: (key: string, value: string) => {
        if (key.startsWith('justsearch.draft.attachment.')) throw new Error('Test storage refusal');
        set(key, value);
      } });
      try {
        await open(shell, 'conv-b');
        expect(search(shell)).toBe(view);
        expect(view.sessions.activeId).toBe('conv-a'); expect(readLastViewedConversation()).toBe(pointer);
        expect((await field(shell)).value).toBe('Keep this draft'); expect(view.tier).toBe(tier);
        expect((view.shadowRoot!.querySelector('jf-sv3-composer') as unknown as { schemaDraft: string }).schemaDraft).toBe('{"type":"object"}');
        expect(pushes).toHaveBeenCalledTimes(1);
        expect(view.shadowRoot!.querySelector('[data-testid="sv3-departure-notice"]')).not.toBeNull();
      } finally { Object.defineProperty(sessionStorage, 'setItem', { configurable: true, value: set }); }
      await browserBack(shell);
      expect(window.location.hash).toBe(origin);
      if (start === 'active') {
        expect(view.sessions.activeId).toBe('conv-a'); expect((await field(shell)).value).toBe('Keep this draft');
        expect(readLastViewedConversation()).toBe(pointer);
      }
    });
  }
});
