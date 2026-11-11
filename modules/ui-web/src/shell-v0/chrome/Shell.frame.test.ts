// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Shell slice 2 — the frame around the windows: FR1 (top bar and switcher), FR4 (what left the
 * everyday chrome), FR5 (layout zones), FR7 (plugin windows, overrides, provenance), FR8 (landmarks,
 * F6 regions, the pill's name), L3 (the unknown-destination notice). Navigation (FR3, FR6, C9) is in
 * Shell.navigation.test.ts; the bar's own presentation in TopBar.test.ts.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import axe from 'axe-core';
import { LitElement, html } from 'lit';
import './Shell.js';
import '../components/SurfaceTabs.js';
import type { Shell } from './Shell.js';
import type { TopBar } from './TopBar.js';
import type { StatusPill } from '../components/StatusPill.js';
import { __resetForTest as resetCatalog, __seedForTest as seedCatalog } from '../../api/registry/SurfaceCatalogClient.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { __resetSurfaceSchemasForTest } from '../router/surfaceSchemas.js';
import { __resetStoreRegistryForTest } from '../router/storeRegistry.js';
import { deactivateProjection } from '../router/URLProjector.js';
import { __resetJournalForTest } from '../state/NavigationJournal.js';
import { __resetUserConfigForTest, setActiveLayoutId, setSurfaceOverride } from '../state/userConfigState.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { mutateDocument } from '../state/UserStateDocument.js';
import { setViewerAudience } from '../state/viewerAudienceState.js';
import { initLayoutCatalog, registerLayout, __resetLayoutCatalogForTest } from '../layout/LayoutManifest.js';
import { getAction, invokeAction } from '../substrates/actions/index.js';
import { __resetConversationListForTest } from '../state/conversationListStore.js';
import { takeSearchV3Handoff } from '../utils/compose.js';
import { EPHEMERAL_TOAST_EVENT, type EphemeralToastSpec } from '../components/advisory/ephemeralToast.js';
import type { Surface } from '../../api/types/surface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

const SEARCH = 'core.search-v3-surface';
const LIBRARY = 'core.library-surface';
const BROWSE = 'core.browse-surface';
const BRAIN = 'core.brain-surface';
const SETTINGS = 'core.settings-surface';
const SYSTEM = 'core.system-surface';
const HELP = 'core.help-surface';
const NOTES = 'vendor.acme.notes-surface';

function surface(id: string, mountTag: string, extra: Partial<Surface> = {}): Surface {
  return { id, mountTag, presentation: { labelKey: id, descriptionKey: id }, audience: 'USER', placement: 'RAIL',
    consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra };
}

/** A host window with a tab row and a details pane, as Library and Search have. */
class FrameHostProbe extends LitElement {
  override render() {
    return html`<jf-surface-tabs tablist-label="Library views" active-id="folders"
      .items=${[{ id: 'folders', label: 'Folders', slot: 'tab-folders' }, { id: BROWSE, label: 'Browse', content: () => html`<p>browse</p>` }]}>
      <div slot="tab-folders"><button class="content-action">Add folder</button>
        <aside data-details-pane aria-label="Details"><button class="details-action">Open file</button></aside></div>
    </jf-surface-tabs>`;
  }
}
/** A plugin window: records the host it was mounted with (host injection, FR7). */
class NotesProbe extends HTMLElement { host_?: PluginHostApi; }
if (!customElements.get('jf-frame-host-probe')) customElements.define('jf-frame-host-probe', FrameHostProbe);
if (!customElements.get('jf-acme-notes')) customElements.define('jf-acme-notes', NotesProbe);
for (const tag of ['jf-frame-search-probe', 'jf-frame-brain-probe', 'jf-frame-system-probe', 'jf-frame-help-probe', 'jf-frame-settings-probe']) {
  if (!customElements.get(tag)) customElements.define(tag, class extends HTMLElement {});
}

function seed(): void {
  seedCatalog({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
    surface(SEARCH, 'jf-frame-search-probe'),
    surface(LIBRARY, 'jf-frame-host-probe', { members: [BROWSE] }),
    surface(BROWSE, 'jf-frame-browse-unused', { placement: 'DEEPLINK' }),
    surface(BRAIN, 'jf-frame-brain-probe'),
    surface(SYSTEM, 'jf-frame-system-probe'),
    surface(HELP, 'jf-frame-help-probe', { placement: 'DEEPLINK' }),
    surface(SETTINGS, 'jf-frame-settings-probe', { placement: 'MODAL' }),
    surface(NOTES, 'jf-acme-notes', { provenance: { tier: 'TRUSTED_PLUGIN', contributorId: 'vendor.acme', version: '1.0.0' } }),
  ] });
}

async function drain(): Promise<void> { for (let i = 0; i < 6; i++) await new Promise<void>(r => setTimeout(r, 0)); }
async function settle(shell: Shell): Promise<void> { await drain(); await shell.updateComplete; await drain(); }
async function mount(): Promise<Shell> {
  const shell = document.createElement('jf-shell') as Shell;
  document.body.append(shell);
  await settle(shell);
  return shell;
}
function bar(shell: Shell): TopBar { return shell.shadowRoot!.querySelector<TopBar>('jf-top-bar')!; }
function stageTags(shell: Shell): string[] {
  const stage = shell.shadowRoot!.querySelector('jf-stage')!;
  return [...stage.shadowRoot!.querySelectorAll('*')].map(el => el.localName).filter(n => n.startsWith('jf-'));
}
function key(init: KeyboardEventInit): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
  document.dispatchEvent(event);
  return event;
}
/** The element focus is on, through open shadow roots. */
function focused(): Element | null {
  let el: Element | null = document.activeElement;
  while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
  return el;
}

beforeEach(() => {
  vi.spyOn(window.performance, 'getEntriesByType').mockImplementation(type => type === 'navigation'
    ? [{ type: 'navigate' } as unknown as PerformanceEntry] : []);
  localStorage.clear(); sessionStorage.clear();
  window.history.pushState(null, '', '/');
  resetCatalog(); __resetBootstrapForTest(); __resetSurfaceSchemasForTest(); __resetStoreRegistryForTest();
  __resetUserConfigForTest(); __resetJournalForTest(); __resetAiStateForTest(); deactivateProjection();
  __resetConversationListForTest(); takeSearchV3Handoff();
  __resetLayoutCatalogForTest(); initLayoutCatalog();
  setViewerAudience('USER');
  seed();
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ entries: [] }),
    { status: 200, headers: { 'Content-Type': 'application/json' } })));
});
afterEach(() => {
  document.querySelectorAll('jf-shell').forEach(el => el.remove());
  deactivateProjection();
  setViewerAudience('USER');
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('FR1 / FR4 / FR8 the frame is one top bar over the window area', () => {
  it('has a banner (the top bar), a navigation (the switcher) and one main with the page heading', async () => {
    const shell = await mount();
    const top = bar(shell);
    expect(top.getAttribute('role')).toBe('banner');
    expect(top.shadowRoot!.querySelector('nav')!.getAttribute('aria-label')).toBe('Areas');
    const main = shell.shadowRoot!.querySelector('[role="main"]')!;
    expect(main.querySelector('jf-stage')).not.toBeNull();
    expect(main.querySelector('h1')!.textContent).toBe('Search');
    const result = await axe.run(document, { runOnly: ['landmark-one-main', 'landmark-no-duplicate-banner',
      'landmark-no-duplicate-main', 'landmark-unique', 'landmark-banner-is-top-level', 'landmark-main-is-top-level',
      'page-has-heading-one'] });
    expect(result.violations.map(v => v.id)).toEqual([]);
  });

  it('carries no rail, back/forward buttons, recents, bookmarks, Copy URL, Simple/Detailed toggle or backend address', async () => {
    const shell = await mount();
    const root = shell.shadowRoot!;
    expect(root.querySelector('jf-rail, jf-recents-menu, jf-bookmarks-popover, .topbar')).toBeNull();
    const text = [root, bar(shell).shadowRoot!].map(r => (r as ShadowRoot).textContent ?? '').join(' ');
    for (const gone of ['Copy URL', 'Manage indexed', 'Simple', 'Detailed', 'Go back', 'Go forward']) expect(text).not.toContain(gone);
    expect(root.querySelector('[role="group"][aria-label="Detail level"]')).toBeNull();
    expect(bar(shell).shadowRoot!.querySelector('[aria-label*="ookmark"], [data-testid="backend-address"]')).toBeNull();
    // Ctrl+D (the bookmark button's chord) is no longer taken from the platform.
    expect(key({ key: 'd', ctrlKey: true }).defaultPrevented).toBe(false);
  });

  it('the status pill sits in the top bar and its name states its state', async () => {
    const shell = await mount();
    const pill = shell.shadowRoot!.querySelector<StatusPill>('jf-top-bar > jf-status-pill[slot="status"]')!;
    expect(pill).not.toBeNull();
    await pill.updateComplete;
    const trigger = pill.shadowRoot!.querySelector('.trigger')!;
    expect(trigger.getAttribute('aria-label')).toBe(pill.status.model.pill.label);
    expect(trigger.getAttribute('aria-label')!.length).toBeGreaterThan(0);
  });

  it('Ctrl+1/2/3 switch areas and the switcher follows', async () => {
    const shell = await mount();
    for (const [digit, id] of [['2', LIBRARY], ['3', BRAIN], ['1', SEARCH]] as const) {
      expect(key({ key: digit, code: `Digit${digit}`, ctrlKey: true }).defaultPrevented).toBe(true);
      await settle(shell);
      expect(shell.activeId).toBe(id);
      expect(bar(shell).shadowRoot!.querySelector('[aria-current="page"]')!.getAttribute('data-surface-id')).toBe(id);
    }
  });

  it('F1 opens Help & shortcuts', async () => {
    const shell = await mount();
    expect(key({ key: 'F1' }).defaultPrevented).toBe(true);
    await settle(shell);
    expect(shell.activeId).toBe(HELP);
  });
});

describe('FR5 saved layouts map onto the frame', () => {
  it.each(['core.focus', 'core.zen', 'acme.custom-no-rail'])('%s (rail.visible:false) hides the switcher; Ctrl+2 and the palette still reach the areas', async (layout) => {
    registerLayout({ id: 'acme.custom-no-rail', displayName: 'Custom', version: '1', zones: { rail: { visible: false }, stage: { exclusive: true } } });
    setActiveLayoutId(layout);
    const shell = await mount();
    expect(bar(shell).shadowRoot!.querySelector('nav')!.hidden).toBe(true);
    key({ key: '2', code: 'Digit2', ctrlKey: true });
    await settle(shell);
    expect(shell.activeId).toBe(LIBRARY);
    expect(getAction('core.action.shell.go-to-brain')?.shortcut).toBe('Ctrl+3');
    await invokeAction('core.action.shell.go-to-brain');
    await settle(shell);
    expect(shell.activeId).toBe(BRAIN);
    // Settings and More stay in every layout.
    expect(bar(shell).shadowRoot!.querySelector('[data-surface-id="core.settings-surface"]')).not.toBeNull();
  });

  it('statusBar.visible:false hides plugin status in the pill only', async () => {
    setActiveLayoutId('core.focus');
    const shell = await mount();
    const pill = shell.shadowRoot!.querySelector<StatusPill>('jf-status-pill')!;
    expect(pill.pluginsVisible).toBe(false);
    setActiveLayoutId('core.default');
    await settle(shell);
    expect(pill.pluginsVisible).toBe(true);
  });

  it('a split layout keeps the second window pane with its picker', async () => {
    setActiveLayoutId('core.split');
    const shell = await mount();
    expect(bar(shell).shadowRoot!.querySelector('nav')!.hidden).toBe(false);
    const stage = shell.shadowRoot!.querySelector('jf-stage')!;
    expect(stage.shadowRoot!.querySelectorAll('.pane')).toHaveLength(2);
    expect(stage.shadowRoot!.querySelector('jf-pane-picker')).not.toBeNull();
  });

  it('the window list respects a layout\'s rail.surfaces (switcher and More list only what it names)', async () => {
    registerLayout({ id: 'acme.two', displayName: 'Two', version: '1', zones: { rail: { visible: true, surfaces: [SEARCH, NOTES] }, stage: { exclusive: true } } });
    setActiveLayoutId('acme.two');
    const shell = await mount();
    expect([...bar(shell).shadowRoot!.querySelectorAll('nav .segment')].map(s => s.getAttribute('data-surface-id'))).toEqual([SEARCH]);
    expect(bar(shell).windows.map(w => w.id)).toEqual([NOTES]);
  });
});

describe('FR7 plugin windows, overrides and provenance', () => {
  it('a catalog window contribution is listed under More › More and mounts with host injection', async () => {
    const shell = await mount();
    expect(bar(shell).windows.map(w => w.id)).toEqual([NOTES]);
    expect(bar(shell).shadowRoot!.querySelector(`nav [data-surface-id="${NOTES}"]`), 'not an area').toBeNull();
    bar(shell).shadowRoot!.querySelector<HTMLButtonElement>('.more-trigger')!.click();
    await bar(shell).updateComplete;
    bar(shell).shadowRoot!.querySelector<HTMLButtonElement>(`[role="menu"] [data-surface-id="${NOTES}"]`)!.click();
    await settle(shell);
    expect(shell.activeId).toBe(NOTES);
    const notes = shell.shadowRoot!.querySelector('jf-stage')!.shadowRoot!.querySelector<NotesProbe>('jf-acme-notes')!;
    expect(notes).not.toBeNull();
    expect(notes.host_).toBeDefined();
    expect(typeof notes.host_!.data.fetch).toBe('function');
  });

  it('a surface override mounts the override and the provenance badge says a non-core element is active', async () => {
    setSurfaceOverride(LIBRARY, NOTES);
    const shell = await mount();
    key({ key: '2', code: 'Digit2', ctrlKey: true });
    await settle(shell);
    expect(stageTags(shell)).toContain('jf-acme-notes');
    const badge = shell.shadowRoot!.querySelector('jf-overlay-host > jf-provenance-badge')! as HTMLElement & { updateComplete: Promise<unknown> };
    await badge.updateComplete;
    expect(badge.shadowRoot!.textContent).toContain('non-core');
    expect(badge.shadowRoot!.textContent).toContain(LIBRARY);
  });

  it('SD1 the Developer group follows Show developer tools independently of audience (API explorer listed only when present)', async () => {
    const shell = await mount();
    expect(bar(shell).developer).toBe(false);
    setViewerAudience('DEVELOPER');
    await settle(shell);
    expect(bar(shell).developer).toBe(true);
    expect(bar(shell).developerWindows).toEqual([]);
    mutateDocument(doc => ({ ...doc, showDeveloperTools: false }));
    await settle(shell);
    expect(bar(shell).developer).toBe(false);
    setViewerAudience('USER');
    mutateDocument(doc => ({ ...doc, showDeveloperTools: true }));
    await settle(shell);
    expect(bar(shell).developer).toBe(true);
  });

  it('About opens Settings at the section that shows the version; Copy link copies this view\'s address', async () => {
    const writeText = vi.fn(async () => undefined);
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });
    const shell = await mount();
    const toasts: EphemeralToastSpec[] = [];
    const listen = (e: Event) => toasts.push((e as CustomEvent<EphemeralToastSpec>).detail);
    document.addEventListener(EPHEMERAL_TOAST_EVENT, listen);
    try {
      bar(shell).dispatchEvent(new CustomEvent('top-bar-select', { detail: { kind: 'window', id: 'about' }, bubbles: true, composed: true }));
      await settle(shell);
      expect(decodeURIComponent(window.location.hash)).toBe(`#justsearch://surface/${SETTINGS}?section=about`);
      bar(shell).dispatchEvent(new CustomEvent('top-bar-select', { detail: { kind: 'copy-link' }, bubbles: true, composed: true }));
      await settle(shell);
      expect(writeText).toHaveBeenCalledWith(window.location.href);
      expect(toasts.map(t => t.message)).toContain('Link copied');
    } finally {
      document.removeEventListener(EPHEMERAL_TOAST_EVENT, listen);
    }
  });
});

describe('FR8 F6 cycles top bar → window tabs → content → details pane', () => {
  it('moves through the present regions in order, wraps, and Shift+F6 goes back', async () => {
    const shell = await mount();
    key({ key: '2', code: 'Digit2', ctrlKey: true });
    await settle(shell);
    const order: Array<string | null> = [];
    for (let i = 0; i < 5; i++) {
      key({ key: 'F6' });
      const el = focused();
      order.push(el?.getAttribute('data-surface-id') ?? el?.getAttribute('role') ?? el?.getAttribute('class') ?? el?.localName ?? null);
    }
    expect(order).toEqual([LIBRARY, 'tab', 'main', 'details-action', LIBRARY]);
    key({ key: 'F6', shiftKey: true });
    expect(focused()?.getAttribute('class')).toBe('details-action');
    key({ key: 'F6', shiftKey: true });
    expect(focused()?.getAttribute('role')).toBe('main');
  });

  it('skips a region a window does not have (Search has no tab row here)', async () => {
    await mount();
    const order: Array<string | null> = [];
    for (let i = 0; i < 3; i++) {
      key({ key: 'F6' });
      const el = focused();
      order.push(el?.getAttribute('data-surface-id') ?? el?.getAttribute('role') ?? null);
    }
    expect(order).toEqual([SEARCH, 'main', SEARCH]);
  });
});

describe('L3 the unknown-destination notice', () => {
  it('names what happened in words that fit the top bar and offers the destinations', async () => {
    const shell = await mount();
    const toasts: EphemeralToastSpec[] = [];
    const listen = (e: Event) => toasts.push((e as CustomEvent<EphemeralToastSpec>).detail);
    document.addEventListener(EPHEMERAL_TOAST_EVENT, listen);
    try {
      window.history.pushState(null, '', '#justsearch://surface/core.librray-surface');
      window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
      await settle(shell);
    } finally {
      document.removeEventListener(EPHEMERAL_TOAST_EVENT, listen);
    }
    const notice = toasts.find(t => t.actionLabel === 'Choose destination')!;
    expect(notice.message).toBe("Couldn't find that window");
    expect(notice.message).not.toContain('core.');
  });
});
