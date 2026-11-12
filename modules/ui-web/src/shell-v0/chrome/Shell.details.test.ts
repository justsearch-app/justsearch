// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * The frame after its side drawers: no right-drawer slot, one details pane per window, and every
 * pointer that opened a drawer arriving at the new home (DP3, DP5, C4).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './Shell.js';
import type { Shell, Stage } from './Shell.js';
import { __resetForTest as resetCatalog, __seedForTest as seedCatalog, getSurface } from '../../api/registry/SurfaceCatalogClient.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { __resetSurfaceSchemasForTest } from '../router/surfaceSchemas.js';
import { __resetStoreRegistryForTest } from '../router/storeRegistry.js';
import { deactivateProjection } from '../router/URLProjector.js';
import { invokeAction, __resetActionsForTest } from '../substrates/actions/index.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetJournalForTest } from '../state/NavigationJournal.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';
import { getInspectorState, resetInspectorState, setSelected } from '../state/inspectorState.js';
import { takeActivitySection } from '../state/activitySection.js';
import { __resetAgentSessionStore, peekAgentSessionController } from '../state/agentSessionStore.js';
import type { WindowDetails } from '../components/detailsPane/WindowDetails.js';

const SEARCH = 'core.search-v3-surface';
const LIBRARY = 'core.library-surface';
const BRAIN = 'core.brain-surface';
const ACTIVITY = 'core.activity-surface';

const surface = (id: string, mountTag: string, extra: Record<string, unknown> = {}): Record<string, unknown> => ({ id, mountTag, audience: 'USER',
  placement: 'RAIL', presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [], subscribedEvents: [], conversationShapes: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });

for (const tag of ['jf-details-search-probe', 'jf-details-library-probe', 'jf-details-brain-probe', 'jf-details-activity-probe']) {
  if (!customElements.get(tag)) customElements.define(tag, class extends HTMLElement {
    connectedCallback(): void {
      if (this.shadowRoot) return;
      this.attachShadow({ mode: 'open' }).innerHTML = '<button>inside</button>';
    }
  });
}

function seed(): void {
  seedCatalog({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
    surface(SEARCH, 'jf-details-search-probe'),
    surface(LIBRARY, 'jf-details-library-probe'),
    surface(BRAIN, 'jf-details-brain-probe'),
    surface(ACTIVITY, 'jf-details-activity-probe', { placement: 'DEEPLINK' }),
  ] } as never);
}

async function drain(): Promise<void> { for (let i = 0; i < 6; i += 1) await new Promise<void>(resolve => setTimeout(resolve, 0)); }
async function settle(shell: Shell): Promise<void> { await drain(); await shell.updateComplete; await drain(); }
async function mount(): Promise<Shell> {
  const shell = document.createElement('jf-shell') as Shell;
  document.body.append(shell);
  await settle(shell);
  return shell;
}

const stage = (shell: Shell): Stage => shell.shadowRoot!.querySelector('jf-stage') as Stage;
const details = (shell: Shell): WindowDetails | null => stage(shell).shadowRoot!.querySelector('jf-window-details');
const shown = (el: WindowDetails | null): string | null =>
  el?.shadowRoot?.querySelector<HTMLElement & { docPath: string }>('jf-document-pane')?.docPath ?? null;

beforeEach(() => {
  vi.spyOn(window.performance, 'getEntriesByType').mockImplementation(type => type === 'navigation' ? [{ type: 'navigate' } as unknown as PerformanceEntry] : []);
  localStorage.clear(); sessionStorage.clear();
  window.history.pushState(null, '', '/');
  resetCatalog(); __resetBootstrapForTest(); __resetSurfaceSchemasForTest(); __resetStoreRegistryForTest();
  __resetUserConfigForTest(); __resetJournalForTest(); deactivateProjection();
  vi.stubGlobal('fetch', vi.fn(async () => Response.json({ entries: [] })));
  vi.stubGlobal('EventSource', class { addEventListener(): void {} removeEventListener(): void {} close(): void {} });
  seed();
});

afterEach(() => {
  document.body.replaceChildren();
  resetInspectorState();
  deactivateProjection();
  __resetActionsForTest();
  __resetAgentSessionStore();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('no frame drawers', () => {
  it('the overlay host has no right-drawer slot, and the frame mounts no drawer', async () => {
    const shell = await mount();
    const overlay = shell.shadowRoot!.querySelector('jf-overlay-host')!;
    await (overlay as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(overlay.shadowRoot!.querySelector('slot[name="right-drawer"]')).toBeNull();
    expect(shell.shadowRoot!.querySelector('[slot="right-drawer"]')).toBeNull();
    for (const tag of ['jf-sources-pane', 'jf-context-inspector-pane', 'jf-failed-jobs-drawer', 'jf-interaction-retrospective-panel', 'jf-effect-audit-log', 'jf-document-pane']) {
      expect(shell.shadowRoot!.querySelector(tag), tag).toBeNull();
    }
  });
});

describe('every window has at most one details pane', () => {
  it('Search composes its own; any other window gets one beside it', async () => {
    const shell = await mount();
    expect(stage(shell).shadowRoot!.querySelector('jf-details-search-probe')).not.toBeNull();
    expect(details(shell)).toBeNull();
    requestSurfaceNavigation(LIBRARY);
    await settle(shell);
    expect(stage(shell).shadowRoot!.querySelectorAll('jf-window-details')).toHaveLength(1);
  });

  it('host.ui.showInspector outside Search opens that window’s pane, with no navigation', async () => {
    const shell = await mount();
    requestSurfaceNavigation(LIBRARY);
    await settle(shell);
    setSelected({ id: '/read.txt', title: 'read.txt', path: '/read.txt', highlightStartLine: 6, highlightEndLine: 8 });
    await settle(shell);
    expect(stage(shell).shadowRoot!.querySelector('jf-details-library-probe')).not.toBeNull();
    expect(shown(details(shell))).toBe('/read.txt');
    expect(getInspectorState().owner).toBe(LIBRARY);
  });

  it('a selection in Search belongs to Search', async () => {
    const shell = await mount();
    setSelected({ id: '/read.txt', title: 'read.txt', path: '/read.txt' });
    await settle(shell);
    expect(getInspectorState().owner).toBe(SEARCH);
  });

  it('a window change closes what the window left behind', async () => {
    const shell = await mount();
    requestSurfaceNavigation(LIBRARY);
    await settle(shell);
    setSelected({ id: '/read.txt', title: 'read.txt', path: '/read.txt' });
    await settle(shell);
    requestSurfaceNavigation(BRAIN);
    await settle(shell);
    expect(shown(details(shell))).toBeNull();
    expect(getInspectorState().isOpen).toBe(false);
  });
});

describe('C4 a split layout', () => {
  async function split(shell: Shell): Promise<Stage> {
    const el = stage(shell);
    el.surface = getSurface(LIBRARY) ?? null;
    el.secondarySurface = getSurface(BRAIN) ?? null;
    el.splitAxis = 'horizontal';
    await settle(shell);
    return el;
  }

  it('gives each window its own pane, and a selection goes to the window holding the keyboard', async () => {
    const shell = await mount();
    const el = await split(shell);
    const [left, right] = [...el.shadowRoot!.querySelectorAll('jf-window-details')];
    el.shadowRoot!.querySelector('jf-details-brain-probe')!.shadowRoot!.querySelector('button')!.focus();
    setSelected({ id: '/right.txt', title: 'right.txt', path: '/right.txt' });
    await settle(shell);
    expect(shown(right as WindowDetails)).toBe('/right.txt');
    expect(shown(left as WindowDetails)).toBeNull();
    expect(getInspectorState().owner).toBe(BRAIN);
  });

  it('with the keyboard nowhere, it goes to the primary window', async () => {
    const shell = await mount();
    const el = await split(shell);
    (document.activeElement as HTMLElement | null)?.blur();
    expect(el.windowHolding(null)).toBe(LIBRARY);
  });
});

describe('what the drawers did besides drawing', () => {
  it('the shared agent controller has the frame\u2019s host from the start, so a delegated run has its transport', async () => {
    await mount();
    const controller = peekAgentSessionController() as unknown as { host_?: unknown } | null;
    expect(controller).not.toBeNull();
    expect(controller?.host_).toBeDefined();
  });
});

describe('what opened a drawer goes to the new home', () => {
  it('Ctrl+K "Show Effect Audit Log" arrives at the journal in Activity', async () => {
    const shell = await mount();
    await invokeAction('core.action.shell.show-audit-log');
    await settle(shell);
    const activity = stage(shell).shadowRoot!.querySelector('jf-details-activity-probe');
    expect(activity).not.toBeNull();
    expect(takeActivitySection(activity!)).toBe('effect-journal');
  });
});
