// @vitest-environment happy-dom

/**
 * Tests for slice 472's userConfig-driven rail filtering + ordering.
 *
 * The chrome `<jf-shell>` reads `userConfig.surfaceVisibility` +
 * `userConfig.surfaceOrder` and applies them to the rail catalog
 * snapshot in `refreshSurfaces()`. This test exercises that
 * pipeline by seeding the SurfaceCatalog, mutating userConfig, and
 * asserting Shell's `surfaces` array reflects the changes.
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

/** Every rail button that claims to navigate to `id` — the pinned affordance AND catalog slots. */
async function railButtonsFor(shell: ShellElement, id: string): Promise<Element[]> {
  const rail = shell.shadowRoot?.querySelector('jf-rail') as
    | (HTMLElement & { updateComplete: Promise<void> })
    | null;
  if (!rail) return [];
  await rail.updateComplete;
  return [...(rail.shadowRoot?.querySelectorAll(`[data-surface-id="${id}"]`) ?? [])];
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

  it('renders exactly ONE settings button — the pinned affordance, never a catalog slot (855 S1)', async () => {
    seedFiveCoreSurfaces();
    const shell = await renderShell();
    const buttons = await railButtonsFor(shell, 'core.settings-surface');
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

describe('Shell — Simple/Detailed rail filter (tempdoc 586 F-2)', () => {
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

  it('Simple mode hides System from the rail but keeps AI Brain (Theme Editor is DEEPLINK — never on the rail)', async () => {
    seedSurfacesWithDiagnostics();
    setUiMode('simple');
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    // The advanced/diagnostic surface drops off in Simple mode...
    expect(ids).not.toContain('core.system-surface');
    // ...while AI Brain stays (the user's explicit choice), as do the consumer surfaces.
    expect(ids).toContain('core.brain-surface');
    expect(ids).toContain('core.library-surface');
    expect(ids).toContain('core.search-surface');
    // 855 S1 — and Settings is out of the rail set in BOTH modes, not a Simple-mode trim.
    expect(ids).not.toContain('core.settings-surface');
    // 855 §5 item 2 — Token Editor is DEEPLINK-placement: it never reaches `railSurfaces` at all,
    // regardless of mode, so it is absent here too (not because Simple mode hid it).
    expect(ids).not.toContain('vendor.token-editor.editor-surface');
  });

  it('Detailed mode restores System (Theme Editor stays off the rail — it is DEEPLINK, not RAIL)', async () => {
    seedSurfacesWithDiagnostics();
    setUiMode('advanced');
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids).toContain('core.system-surface');
    expect(ids).toContain('core.brain-surface');
    // 855 §5 item 2 — DEEPLINK placement means Detailed mode does not restore it either; it was
    // never excluded by the Simple-mode filter to begin with.
    expect(ids).not.toContain('vendor.token-editor.editor-surface');
  });
});
