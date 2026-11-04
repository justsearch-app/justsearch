// @vitest-environment happy-dom

/**
 * Tests for slice 472's userConfig-driven window list filtering + ordering (was ShellRail.test.ts;
 * shell slice 2 moved the rail's windows into the top bar's switcher and More menu).
 *
 * The chrome `<jf-shell>` reads `userConfig.surfaceVisibility` +
 * `userConfig.surfaceOrder` and applies them to the RAIL-placement catalog
 * snapshot in `refreshSurfaces()`. This test exercises that
 * pipeline by seeding the SurfaceCatalog, mutating userConfig, and
 * asserting Shell's `surfaces` array (the switcher areas + More windows) reflects the changes.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import '../chrome/Shell.js';
import {
  __resetForTest as resetSurfaceCatalog,
  __seedForTest as seedSurfaceCatalog,
} from '../../api/registry/SurfaceCatalogClient.js';
import {
  __resetUserConfigForTest,
  setSurfaceOrder,
  setSurfaceVisibility,
} from '../state/userConfigState.js';
import { __resetUiModeForTest, setUiMode } from '../state/uiModeState.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
// Tempdoc 586 follow-up — renderShell() triggers fire-and-forget lazy-surface imports; drain them in
// afterEach so a dynamic import() can't resolve after teardown (vitest-4 EnvironmentTeardownError).
import { __flushInFlightSurfaces } from '../views/lazySurfaceRegistry.js';
import type { Surface, SurfaceCatalog } from '../../api/types/surface.js';

function makeRailSurface(id: string, mountTag: string): Surface {
  return {
    id,
    presentation: {
      labelKey: `${id}.label`,
      descriptionKey: `${id}.description`,
    },
    audience: 'USER',
    placement: 'RAIL',
    consumes: {
      operations: [],
      resources: [],
      prompts: [],
      diagnosticChannels: [],
    },
    mountTag,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' },
  };
}

const SEARCH = makeRailSurface('core.search-surface', 'jf-search-surface');
const LIBRARY = makeRailSurface('core.library-surface', 'jf-library-surface');
const BRAIN = makeRailSurface('core.brain-surface', 'jf-brain-surface');
const HELP = makeRailSurface('core.help-surface', 'jf-help-surface');
const SETTINGS = makeRailSurface(
  'core.settings-surface',
  'jf-settings-surface',
);
// Tempdoc 586 F-2 — the surface hidden from the rail in Simple mode.
const SYSTEM = makeRailSurface('core.system-surface', 'jf-system-surface');
// Tempdoc 855 §5 item 2 — Token Editor is DEEPLINK-placement now, not RAIL: seeded here (not via
// `makeRailSurface`) so the fixture is honest about what the real catalog wire declares, to assert
// it never reaches the rail's `railSurfaces` base filter, in either Simple or Detailed mode.
const THEME_EDITOR: Surface = {
  ...makeRailSurface('vendor.token-editor.editor-surface', 'jf-token-editor-surface'),
  placement: 'DEEPLINK',
};

function seedSurfacesWithDiagnostics(): void {
  const catalog: SurfaceCatalog = {
    schemaVersion: '1.0.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Surface',
    entries: [LIBRARY, BRAIN, SEARCH, SETTINGS, SYSTEM, THEME_EDITOR],
  };
  seedSurfaceCatalog(catalog);
}

function seedFiveCoreSurfaces(): void {
  const catalog: SurfaceCatalog = {
    schemaVersion: '1.0.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Surface',
    entries: [SEARCH, LIBRARY, BRAIN, HELP, SETTINGS],
  };
  seedSurfaceCatalog(catalog);
}

interface ShellElement extends HTMLElement {
  apiBase: string;
  surfaces: Surface[];
  updateComplete: Promise<void>;
}

type TopBarElement = HTMLElement & { updateComplete: Promise<void> };

/** Every top-bar control that claims to navigate to `id`: switcher, Settings, an open menu's rows. */
async function topBarButtonsFor(shell: ShellElement, id: string): Promise<Element[]> {
  const bar = shell.shadowRoot?.querySelector('jf-top-bar') as TopBarElement | null;
  if (!bar) return [];
  await bar.updateComplete;
  return [...(bar.shadowRoot?.querySelectorAll(`[data-surface-id="${id}"]`) ?? [])];
}

/** Open the More (⋯) menu and return the surface ids its rows open. */
async function moreMenuIds(shell: ShellElement): Promise<string[]> {
  const bar = shell.shadowRoot?.querySelector('jf-top-bar') as TopBarElement | null;
  await bar?.updateComplete;
  bar?.shadowRoot?.querySelector<HTMLButtonElement>('.more-trigger')?.click();
  await bar?.updateComplete;
  return [...(bar?.shadowRoot?.querySelectorAll('[role="menu"] [data-surface-id]') ?? [])]
    .map(row => row.getAttribute('data-surface-id') ?? '');
}

async function renderShell(): Promise<ShellElement> {
  const shell = document.createElement('jf-shell') as ShellElement;
  shell.apiBase = '';
  document.body.appendChild(shell);
  await shell.updateComplete;
  // Allow the post-connectedCallback userConfig subscription's initial
  // notification + refreshSurfaces() to settle.
  await shell.updateComplete;
  return shell;
}

describe('Shell — userConfig-driven rail (slice 472)', () => {
  beforeEach(() => {
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(JSON.stringify({ entries: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
  });

  afterEach(async () => {
    // Tempdoc 586 follow-up — drain the fire-and-forget lazy-surface imports renderShell() triggered,
    // so a dynamic import() cannot resolve after the happy-dom env is torn down (the deterministic
    // vitest-4 EnvironmentTeardownError that reddened the full-suite exit code while all tests passed).
    await __flushInFlightSurfaces();
    document.querySelectorAll('jf-shell').forEach((el) => el.remove());
    __resetAiStateForTest();
    vi.unstubAllGlobals();
    resetSurfaceCatalog();
    __resetUserConfigForTest();
  });

  it('rail shows all RAIL-placement surfaces when no overrides are set', async () => {
    seedFiveCoreSurfaces();
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids).toContain('core.search-surface');
    expect(ids).toContain('core.library-surface');
    expect(ids).toContain('core.brain-surface');
    expect(ids).toContain('core.help-surface');
    // Tempdoc 855 §11 S1 — Settings is NEVER a catalog rail button, even when a (stale/hostile)
    // catalog declares it RAIL as this fixture does: it is the one MODAL surface, reachable only
    // through the pinned affordance that opens <jf-settings-window>. Without the unconditional
    // exclusion this session would render TWO settings buttons and stage-mount the surface.
    expect(ids).not.toContain('core.settings-surface');
  });

  it('renders exactly ONE settings button — the top bar\'s gear, never a catalog slot (855 S1)', async () => {
    seedFiveCoreSurfaces();
    const shell = await renderShell();
    // Shell slice 2 — the pinned affordance moved from the rail foot to the top bar; a stale catalog
    // declaring Settings RAIL must still not add a second one (in the switcher or the More menu).
    expect(await moreMenuIds(shell)).not.toContain('core.settings-surface');
    const buttons = await topBarButtonsFor(shell, 'core.settings-surface');
    expect(buttons).toHaveLength(1);
    // The survivor is the pinned MODAL affordance: it opens a window, so it declares
    // aria-haspopup="dialog" — the catalog slot never does.
    expect(buttons[0]!.getAttribute('aria-haspopup')).toBe('dialog');
  });

  it('surfaceVisibility=false hides a surface from the rail', async () => {
    seedFiveCoreSurfaces();
    setSurfaceVisibility('core.help-surface', false);
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids).not.toContain('core.help-surface');
    // Other surfaces still visible.
    expect(ids).toContain('core.library-surface');
  });

  it('surfaceVisibility=true is the same as absent (default visible)', async () => {
    seedFiveCoreSurfaces();
    setSurfaceVisibility('core.help-surface', true);
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids).toContain('core.help-surface');
  });

  it('surfaceOrder reorders the rail; unlisted surfaces follow in catalog order', async () => {
    seedFiveCoreSurfaces();
    setSurfaceOrder(['core.brain-surface', 'core.search-surface']);
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids[0]).toBe('core.brain-surface');
    expect(ids[1]).toBe('core.search-surface');
    // Library / Help follow in catalog order (Settings is excluded unconditionally — 855 S1).
    expect(ids.slice(2)).toEqual(['core.library-surface', 'core.help-surface']);
  });

  it('surfaceOrder ids that are not in the catalog are silently skipped', async () => {
    seedFiveCoreSurfaces();
    setSurfaceOrder([
      'core.brain-surface',
      'acme.uninstalled-surface', // not in catalog
      'core.search-surface',
    ]);
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids[0]).toBe('core.brain-surface');
    expect(ids[1]).toBe('core.search-surface');
  });

  it('visibility + order compose: hidden surfaces are not in the ordered list', async () => {
    seedFiveCoreSurfaces();
    setSurfaceVisibility('core.help-surface', false);
    setSurfaceOrder([
      'core.help-surface', // hidden — should be skipped
      'core.brain-surface',
      'core.search-surface',
    ]);
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids).not.toContain('core.help-surface');
    expect(ids[0]).toBe('core.brain-surface');
    expect(ids[1]).toBe('core.search-surface');
  });
});

/**
 * Tempdoc 586 F-2 trimmed System from the rail in Simple mode. Shell slice 2 retired that trim with
 * the rail: System is a fixed row of the More menu in every detail level and never a switcher area
 * (design-system §7 "More (⋯) menu"). What the old tests also pinned — AI Brain stays reachable in
 * Simple mode, Settings is never a window-list entry, a DEEPLINK window never joins the list — is
 * pinned here at the new owner.
 */
describe('Shell — System in the More menu in both detail levels (was the 586 F-2 rail filter)', () => {
  beforeEach(() => {
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetUiModeForTest();
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(JSON.stringify({ entries: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
  });

  afterEach(() => {
    document.querySelectorAll('jf-shell').forEach((el) => el.remove());
    __resetAiStateForTest();
    vi.unstubAllGlobals();
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetUiModeForTest();
  });

  it('Simple mode: System is a More-menu row, not an area; AI Brain stays an area (Theme Editor is DEEPLINK — never listed)', async () => {
    seedSurfacesWithDiagnostics();
    setUiMode('simple');
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    // System is reachable in Simple mode too — from More, never from the switcher.
    expect(await moreMenuIds(shell)).toContain('core.system-surface');
    const bar = shell.shadowRoot?.querySelector('jf-top-bar') as TopBarElement;
    expect(bar.shadowRoot?.querySelector('nav [data-surface-id="core.system-surface"]')).toBeNull();
    // AI Brain stays an area (the user's explicit choice), as do the consumer windows.
    expect(bar.shadowRoot?.querySelector('nav [data-surface-id="core.brain-surface"]')).not.toBeNull();
    expect(ids).toContain('core.brain-surface');
    expect(ids).toContain('core.library-surface');
    expect(ids).toContain('core.search-surface');
    // 855 S1 — and Settings is out of the rail set in BOTH modes, not a Simple-mode trim.
    expect(ids).not.toContain('core.settings-surface');
    // 855 §5 item 2 — Token Editor is DEEPLINK-placement: it never reaches `railSurfaces` at all,
    // regardless of mode, so it is absent here too (not because Simple mode hid it).
    expect(ids).not.toContain('vendor.token-editor.editor-surface');
  });

  it('Detailed mode: System is the same More-menu row (Theme Editor stays unlisted — it is DEEPLINK, not RAIL)', async () => {
    seedSurfacesWithDiagnostics();
    setUiMode('advanced');
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids).toContain('core.system-surface');
    expect(await moreMenuIds(shell)).toContain('core.system-surface');
    expect(ids).toContain('core.brain-surface');
    // 855 §5 item 2 — DEEPLINK placement means Detailed mode does not restore it either; it was
    // never excluded by the Simple-mode filter to begin with.
    expect(ids).not.toContain('vendor.token-editor.editor-surface');
  });
});
