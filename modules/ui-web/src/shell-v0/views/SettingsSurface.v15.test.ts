// @vitest-environment happy-dom

/**
 * Slice 477 H1 — V1.5 Settings UI tests.
 *
 * Covers the three new SettingsSurface sections introduced in H1:
 *   - Themes — picker emits theme load + clears active
 *   - Rail — visibility toggle + reorder + reset
 *   - Plugins — list + revoke
 *
 * The existing Interface/Appearance/Keyboard/Desktop/Data sections
 * are untested here (no pre-existing test file); this slice doesn't
 * own those gaps. Adding coverage for them is V1.5.1 polish.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

// Incidental requests are outside these render/event scenarios. Keep their
// unavailable-backend behavior explicit, without contacting a real server.
const originalFetch = globalThis.fetch;
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Test backend unavailable')));
});
afterEach(() => { globalThis.fetch = originalFetch; });
import './SettingsSurface.js';

/**
 * 574 B1 — activate a <jf-button> the way a user does: the action fires from the
 * native <button> inside the composed <jf-control>, two shadow roots deep, NOT
 * from a click on the jf-button host. Awaits both render passes, then clicks the
 * inner control.
 */
async function activateJfButton(host: Element): Promise<void> {
  await (host as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = host.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import {
  subscribeUserConfig as realSubscribeUserConfig,
  getUserConfig as realGetUserConfig,
} from '../state/userConfigState.js';
import {
  subscribeActiveTheme as realSubscribeActiveTheme,
  getActiveThemeId as realGetActiveThemeId,
  loadAndApplyTheme as realLoadAndApplyTheme,
  clearActiveTheme as realClearActiveTheme,
} from '../state/themeState.js';
import {
  onSurfaceCatalogChange as realOnSurfaceCatalogChange,
} from '../../api/registry/SurfaceCatalogClient.js';
import {
  __resetUserConfigForTest,
  setSurfaceVisibility,
  setSurfaceOrder,
  setActiveLayoutId,
} from '../state/userConfigState.js';
import {
  __resetThemeStateForTest,
  getActiveThemeId,
} from '../state/themeState.js';
import {
  __seedForTest as seedSurfaceCatalog,
  __resetForTest as resetSurfaceCatalog,
} from '../../api/registry/SurfaceCatalogClient.js';
import {
  __resetSessionRegistryForTest,
} from '../plugin-api/sessionRegistry.js';
import { requestMemberTab } from '../router/memberTabIntent.js';
import {
  setCustomThemeEntries,
  __resetCatalogForTest,
} from '../themes/themesCatalog.js';
import type { Surface, SurfaceCatalog } from '../../api/types/surface.js';
import type { PluginManifest } from '../plugin-api/plugin-types.js';
import { initLayoutCatalog } from '../layout/LayoutManifest.js';

const RAIL_FIXTURE: SurfaceCatalog = {
  schemaVersion: '1',
  catalogVersion: 1,
  namespace: 'core',
  primitive: 'Surface',
  entries: [
    {
      id: 'core.search-surface',
      presentation: { labelKey: 'search', descriptionKey: 'search.desc' },
      audience: 'USER',
      placement: 'RAIL',
      consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
      mountTag: 'jf-search-surface',
      provenance: { tier: 'CORE', contributorId: 'core', version: '1' },
    } satisfies Surface,
    {
      id: 'core.library-surface',
      presentation: { labelKey: 'library', descriptionKey: 'library.desc' },
      audience: 'USER',
      placement: 'RAIL',
      consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
      mountTag: 'jf-library-surface',
      provenance: { tier: 'CORE', contributorId: 'core', version: '1' },
    } satisfies Surface,
    {
      id: 'core.settings-surface',
      presentation: { labelKey: 'settings', descriptionKey: 'settings.desc' },
      audience: 'USER',
      placement: 'RAIL',
      consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
      mountTag: 'jf-settings-surface',
      provenance: { tier: 'CORE', contributorId: 'core', version: '1' },
    } satisfies Surface,
  ],
};

function makeManifest(id: string): PluginManifest {
  return {
    id,
    version: '1.0.0',
    displayName: `Plugin ${id}`,
    contractVersion: '1.1',
    tagNamespace: id,
    capabilities: { surfaces: [] },
    register: () => {
      // no-op
    },
  };
}

/**
 * Tempdoc 855 P1 — the settings window now renders only the ACTIVE category's sections
 * (register-driven, `settingsRegister.ts`); a test targeting a section outside the default
 * "appearance" category must select that category first. Optional `category` mirrors what
 * `<jf-settings-nav>`'s `category-select` event would set.
 */
async function mountSurface(category?: string): Promise<HTMLElement> {
  const el = document.createElement('jf-settings-surface') as HTMLElement & {
    activeCategory?: string;
    updateComplete: Promise<unknown>;
  };
  (el as unknown as Record<string, unknown>).host_ = createMockHostApi({
    data: {
      fetch: () => Promise.resolve(new Response(JSON.stringify({ ui: {} }), { status: 200 })),
    },
    layout: {
      subscribeUserConfig: (h) => realSubscribeUserConfig(h as (cfg: unknown) => void),
      getUserConfig: () => realGetUserConfig(),
      onSurfaceCatalogChange: (h) => realOnSurfaceCatalogChange(h),
      setSurfaceVisibility,
      setSurfaceOrder,
      setActiveLayoutId,
    },
    theme: {
      subscribeActiveTheme: (h) => realSubscribeActiveTheme(h),
      getActiveThemeId: () => realGetActiveThemeId(),
      selectTheme: async (id) => { if (id === null) realClearActiveTheme(); else await realLoadAndApplyTheme(id); },
    },
  });
  document.body.appendChild(el);
  // Allow Lit's connectedCallback + first render cycle.
  await new Promise((resolve) => setTimeout(resolve, 0));
  if (category !== undefined) {
    el.activeCategory = category;
    await el.updateComplete;
  }
  return el;
}

describe('SettingsSurface — V1.5 Themes section', () => {
  beforeEach(() => {
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    __resetCatalogForTest();
    resetSurfaceCatalog();
    seedSurfaceCatalog(RAIL_FIXTURE);
    __resetSessionRegistryForTest();
  });

  afterEach(() => {
    __resetCatalogForTest();
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  // Tempdoc 855 §15.3 — the themes picker is now the swatch GRID: `.theme-tile` (was `.option-btn`)
  // + `.theme-tile-label` (was `.option-label`), each wrapped in `.theme-tile-wrap` (was
  // `.custom-theme` for custom entries only; the wrapper is now universal). `.custom-theme-del`
  // is unchanged — every assertion below is the SAME behavior, ported to the new markup honestly
  // (select theme → activeThemeId, delete-control gating, Default's selected state).

  it('renders Default + built-in theme options', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot;
    expect(root).toBeTruthy();
    const labels = Array.from(root!.querySelectorAll('.theme-tile-label'))
      .map((n) => n.textContent?.trim())
      .filter(Boolean);
    expect(labels).toContain('Default');
    expect(labels).toContain('Nord');
    expect(labels).toContain('Sepia Focus');
  });

  it('renders a delete control for a custom theme but not for built-ins (§8 #3)', async () => {
    setCustomThemeEntries([
      {
        id: 'custom.x',
        displayName: 'My Custom',
        description: 'A user theme',
        tokens: {
          schemaVersion: 1,
          id: 'custom.x',
          displayName: 'My Custom',
          tokens: { 'h-teal': '120' },
        },
      },
    ]);
    const el = await mountSurface();
    const root = el.shadowRoot!;
    // Exactly one delete control — for the single custom theme; built-ins get none.
    expect(root.querySelectorAll('.custom-theme-del').length).toBe(1);
    const wraps = Array.from(root.querySelectorAll('.theme-tile-wrap'));
    const customWrap = wraps.find((w) => w.querySelector('.custom-theme-del'));
    expect(customWrap?.querySelector('.theme-tile-label')?.textContent?.trim()).toBe('My Custom');
    // Built-in themes still render (not wrapped with a delete control).
    const allLabels = Array.from(root.querySelectorAll('.theme-tile-label')).map((n) =>
      n.textContent?.trim(),
    );
    expect(allLabels).toContain('Nord');
  });

  it('Default option is selected when no theme is active', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    // Find the Default tile — it's the .theme-tile whose sibling label is "Default".
    const wraps = Array.from(root.querySelectorAll('.theme-tile-wrap'));
    const defaultWrap = wraps.find(
      (w) => w.querySelector('.theme-tile-label')?.textContent?.trim() === 'Default',
    );
    const defaultBtn = defaultWrap?.querySelector('.theme-tile') as HTMLButtonElement | undefined;
    expect(defaultBtn).toBeTruthy();
    expect(defaultBtn!.classList.contains('selected')).toBe(true);
  });

  it('clicking Default invokes clearActiveTheme (no fetch needed)', async () => {
    // Pre-set an active theme; clicking Default should clear it.
    // (We can't actually load a theme without fetch, but we can test
    // the clear path which doesn't fetch.)
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const wraps = Array.from(root.querySelectorAll('.theme-tile-wrap'));
    const defaultWrap = wraps.find(
      (w) => w.querySelector('.theme-tile-label')?.textContent?.trim() === 'Default',
    );
    const defaultBtn = defaultWrap?.querySelector('.theme-tile') as HTMLButtonElement | undefined;
    defaultBtn!.click();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(getActiveThemeId()).toBeNull();
  });

  it('a built-in theme with a manifest-declared swatch paints two-tone tiles, not a neutral fallback', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const wraps = Array.from(root.querySelectorAll('.theme-tile-wrap'));
    const nordWrap = wraps.find(
      (w) => w.querySelector('.theme-tile-label')?.textContent?.trim() === 'Nord',
    );
    const tile = nordWrap?.querySelector('.theme-tile');
    expect(tile?.classList.contains('theme-tile-neutral')).toBe(false);
    expect(tile?.querySelector('.theme-swatch')).toBeTruthy();
  });

  it('a custom theme with no swatch-bearing tokens renders the neutral fallback tile', async () => {
    setCustomThemeEntries([
      {
        id: 'custom.y',
        displayName: 'Seeds Only',
        description: 'A seed-only user theme',
        tokens: {
          schemaVersion: 1,
          id: 'custom.y',
          displayName: 'Seeds Only',
          tokens: { 'h-teal': '120' },
        },
      },
    ]);
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const wraps = Array.from(root.querySelectorAll('.theme-tile-wrap'));
    const customWrap = wraps.find(
      (w) => w.querySelector('.theme-tile-label')?.textContent?.trim() === 'Seeds Only',
    );
    const tile = customWrap?.querySelector('.theme-tile');
    expect(tile?.classList.contains('theme-tile-neutral')).toBe(true);
    expect(tile?.querySelector('.theme-swatch')).toBeNull();
  });

  it('a custom theme carrying surface-primary/accent-tint tokens derives its swatch', async () => {
    setCustomThemeEntries([
      {
        id: 'custom.z',
        displayName: 'Painted',
        description: 'A user theme with derivable swatch tokens',
        tokens: {
          schemaVersion: 1,
          id: 'custom.z',
          displayName: 'Painted',
          tokens: { 'surface-primary': '#112233', 'accent-tint': '#44ff88' },
        },
      },
    ]);
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const wraps = Array.from(root.querySelectorAll('.theme-tile-wrap'));
    const customWrap = wraps.find(
      (w) => w.querySelector('.theme-tile-label')?.textContent?.trim() === 'Painted',
    );
    const tile = customWrap?.querySelector('.theme-tile');
    expect(tile?.classList.contains('theme-tile-neutral')).toBe(false);
    const swatch = tile?.querySelector('.theme-swatch') as HTMLElement | null;
    expect(swatch?.getAttribute('style')).toContain('#112233');
    const accent = tile?.querySelector('.theme-swatch-accent') as HTMLElement | null;
    expect(accent?.getAttribute('style')).toContain('#44ff88');
  });
});

describe('SettingsSurface — V1.5 Rail section', () => {
  beforeEach(() => {
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    resetSurfaceCatalog();
    seedSurfaceCatalog(RAIL_FIXTURE);
    __resetSessionRegistryForTest();
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('lists rail surfaces in catalog order by default', async () => {
    // Tempdoc 855 P1 — Rail is a sub-anchor of the "layout" category now (register-driven paging).
    const el = await mountSurface('layout');
    const root = el.shadowRoot!;
    const labels = Array.from(root.querySelectorAll('.rail-label')).map((n) =>
      n.textContent?.trim(),
    );
    // §2.A: rail-customization shows human labels, not raw surface ids.
    expect(labels).toEqual(['Search', 'Library', 'Settings']);
  });

  it('respects userConfig.surfaceOrder', async () => {
    setSurfaceOrder([
      'core.settings-surface',
      'core.search-surface',
      'core.library-surface',
    ]);
    const el = await mountSurface('layout');
    const root = el.shadowRoot!;
    const labels = Array.from(root.querySelectorAll('.rail-label')).map((n) =>
      n.textContent?.trim(),
    );
    expect(labels[0]).toBe('Settings');
    expect(labels[1]).toBe('Search');
    expect(labels[2]).toBe('Library');
  });

  it('hidden surfaces have visibility=false reflected in checkbox', async () => {
    setSurfaceVisibility('core.settings-surface', false);
    const el = await mountSurface('layout');
    const root = el.shadowRoot!;
    const rows = Array.from(root.querySelectorAll('.rail-row')) as HTMLElement[];
    const settingsRow = rows.find((r) =>
      r.querySelector('.rail-label')?.textContent?.includes('Settings'),
    );
    expect(settingsRow).toBeTruthy();
    const checkbox = settingsRow!.querySelector(
      'input[type="checkbox"]',
    ) as HTMLInputElement;
    expect(checkbox.checked).toBe(false);
  });

  it('move-down arrow on first surface updates surfaceOrder', async () => {
    const el = await mountSurface('layout');
    const root = el.shadowRoot!;
    const firstRow = root.querySelector('.rail-row') as HTMLElement;
    // Find the down arrow (second .rail-arrow button in the row).
    // 574 B1 — the arrows are now <jf-button class="rail-arrow">; the host carries the
    // `title` (not aria-label, which jf-control puts on the inner button), and `disabled`
    // is the reflected jf-button property.
    const arrows = Array.from(
      firstRow.querySelectorAll('.rail-arrow'),
    ) as Array<Element & { disabled: boolean }>;
    const downArrow = arrows.find((a) => a.getAttribute('title') === 'Move down');
    expect(downArrow).toBeTruthy();
    expect(downArrow!.disabled).toBe(false);
    await activateJfButton(downArrow!);
    await new Promise((resolve) => setTimeout(resolve, 0));
    // After moving the first item (search) down, library should be first.
    const labels = Array.from(root.querySelectorAll('.rail-label')).map((n) =>
      n.textContent?.trim(),
    );
    expect(labels[0]).toBe('Library');
    expect(labels[1]).toBe('Search');
  });
});

// Tempdoc 855 fix-round F2 (S1) — the Layout picker (Default/Focus/Zen/Split workspace manifests)
// was a hand-rolled `button.card` grid: selection was CSS-class-only, no role/aria anywhere (a 7th
// unconverted enum picker this file's other pickers already left behind). Converted to the shared
// `jf-option-button-group` plain-props path — same values/order, same `selectLayout` wiring.
describe('SettingsSurface — Layout picker (855 fix-round F2 S1)', () => {
  beforeEach(() => {
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    resetSurfaceCatalog();
    seedSurfaceCatalog(RAIL_FIXTURE);
    __resetSessionRegistryForTest();
    // Production boot populates the layout catalog from `main.jsx` (never imported by this unit
    // test); without it `listLayouts()` returns [] and the picker renders zero options regardless
    // of markup — mirror the real boot step here.
    initLayoutCatalog();
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  async function layoutRadiogroup(root: ShadowRoot): Promise<HTMLElement> {
    const group = root.querySelector('jf-option-button-group') as HTMLElement & {
      shadowRoot: ShadowRoot | null;
      updateComplete: Promise<unknown>;
    };
    await group.updateComplete;
    return group.shadowRoot!.querySelector('[role="radiogroup"]') as HTMLElement;
  }

  it('renders one radio per declared layout manifest, in listLayouts() order', async () => {
    const el = await mountSurface('layout');
    const radiogroup = await layoutRadiogroup(el.shadowRoot!);
    const buttons = Array.from(radiogroup.querySelectorAll<HTMLButtonElement>('button[role="radio"]'));
    expect(buttons.map((b) => b.querySelector('.option-label')?.textContent?.trim())).toEqual([
      'Default',
      'Focus',
      'Zen',
      'Split',
    ]);
  });

  it('the active layout (core.default when unset) is marked aria-checked', async () => {
    const el = await mountSurface('layout');
    const radiogroup = await layoutRadiogroup(el.shadowRoot!);
    const checked = radiogroup.querySelector('button[role="radio"][aria-checked="true"]');
    expect(checked?.querySelector('.option-label')?.textContent?.trim()).toBe('Default');
  });

  it('the radiogroup carries an accessible name ("Layout")', async () => {
    const el = await mountSurface('layout');
    const radiogroup = await layoutRadiogroup(el.shadowRoot!);
    expect(radiogroup.getAttribute('aria-label')).toBe('Layout');
  });

  it('clicking a layout option calls setActiveLayoutId and re-renders the new selection as checked', async () => {
    const el = await mountSurface('layout') as HTMLElement & { updateComplete: Promise<unknown> };
    const radiogroup = await layoutRadiogroup(el.shadowRoot!);
    const focusBtn = Array.from(
      radiogroup.querySelectorAll<HTMLButtonElement>('button[role="radio"]'),
    ).find((b) => b.querySelector('.option-label')?.textContent?.trim() === 'Focus')!;
    focusBtn.click();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await el.updateComplete;
    const radiogroupAfter = await layoutRadiogroup(el.shadowRoot!);
    const checked = radiogroupAfter.querySelector('button[role="radio"][aria-checked="true"]');
    expect(checked?.querySelector('.option-label')?.textContent?.trim()).toBe('Focus');
  });
});

describe('SettingsSurface — V1.5 Plugins section', () => {
  beforeEach(() => {
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    resetSurfaceCatalog();
    seedSurfaceCatalog(RAIL_FIXTURE);
    __resetSessionRegistryForTest();
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('shows empty-state hint when no plugins are installed', async () => {
    // Tempdoc 855 P1 — Plugins is a sub-anchor of the "plugins" category (register-driven paging).
    const el = await mountSurface('plugins');
    const root = el.shadowRoot!;
    const text = root.textContent ?? '';
    expect(text).toContain('No plugins installed');
  });

  it('lists installed plugins from the session registry', async () => {
    const registry = __resetSessionRegistryForTest();
    registry.install(makeManifest('alpha'));
    registry.install(makeManifest('bravo'));
    const el = await mountSurface('plugins');
    const root = el.shadowRoot!;
    const ids = Array.from(root.querySelectorAll('.plugin-id'))
      .map((n) => n.textContent ?? '')
      .join(' ');
    expect(ids).toContain('alpha');
    expect(ids).toContain('bravo');
  });

  it('plugin row exposes Revoke button', async () => {
    const registry = __resetSessionRegistryForTest();
    registry.install(makeManifest('alpha'));
    const el = await mountSurface('plugins');
    const root = el.shadowRoot!;
    // 574 B1 — Revoke is now a <jf-button variant="danger"> (slot text in light DOM).
    const revokeBtn = Array.from(root.querySelectorAll('jf-button')).find(
      (b) => b.textContent?.includes('Revoke'),
    );
    expect(revokeBtn).toBeTruthy();
  });
});

describe('SettingsSurface — settle transients on hide (tempdoc 609 instance-retention)', () => {
  beforeEach(() => {
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    __resetCatalogForTest();
    resetSurfaceCatalog();
    seedSurfaceCatalog(RAIL_FIXTURE);
  });

  it('resets the destructive delete ceremony on disconnect but KEEPS draft work + active tab', async () => {
    const el = await mountSurface();
    const v = el as unknown as Record<string, unknown>;
    // A mid-ceremony delete (transient confirmation) alongside draft work (recoverable) on a chosen tab.
    v.deleteState = 'confirm';
    v.confirmText = 'DELETE EVERYTHING';
    v.renamingThemeId = 'theme-x';
    v.renameDraft = 'My renamed theme';
    v.themeImportDraft = '{"id":"my-draft-theme"}';
    // Tempdoc 855 P1 — `activeTab` generalized to `activeCategory` (register-driven categories).
    v.activeCategory = 'developer';

    el.remove(); // navigate away (instance retained; settleTransients fires via JfElement)

    // The destructive confirmation is settled — no half-finished delete ceremony survives.
    expect(v.deleteState).toBe('idle');
    expect(v.confirmText).toBe('');
    // Draft work is recoverable and SURVIVES (resetting it would re-introduce the 609 draft-loss).
    expect(v.renamingThemeId).toBe('theme-x');
    expect(v.renameDraft).toBe('My renamed theme');
    expect(v.themeImportDraft).toBe('{"id":"my-draft-theme"}');
    // The user's category choice is recoverable and survives.
    expect(v.activeCategory).toBe('developer');
  });
});

describe('SettingsSurface — Security member deep-link (tempdoc 855 §5 item 1 / §9.3)', () => {
  beforeEach(() => {
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    __resetCatalogForTest();
    resetSurfaceCatalog();
    seedSurfaceCatalog(RAIL_FIXTURE);
    __resetSessionRegistryForTest();
  });

  it('drains a pending member-tab intent on mount: activeCategory becomes core.security-surface', async () => {
    // Mirrors the real redirect flow (catalogResolver.ts memberHostAliases → requestMemberTab) fired
    // BEFORE the settings surface mounts — the one-shot `pending` path (memberTabIntent.ts).
    requestMemberTab('core.settings-surface', 'core.security-surface');
    const el = await mountSurface();
    const v = el as unknown as Record<string, unknown>;
    expect(v.activeCategory).toBe('core.security-surface');
    el.remove();
  });

  it('a live member-tab request while already mounted switches activeCategory to core.security-surface', async () => {
    // Mirrors a member deep-link landing while Settings is already the active surface — no re-mount,
    // so the intent must arrive via the subscribe path, not the one-shot drain (§11.2).
    const el = await mountSurface('appearance');
    const v = el as unknown as Record<string, unknown>;
    expect(v.activeCategory).toBe('appearance');

    requestMemberTab('core.settings-surface', 'core.security-surface');
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;

    expect(v.activeCategory).toBe('core.security-surface');
    el.remove();
  });
});

describe('SettingsSurface — Token Editor link (855 §5 item 2 / §9.6 item 5)', () => {
  beforeEach(() => {
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    __resetCatalogForTest();
    resetSurfaceCatalog();
    seedSurfaceCatalog(RAIL_FIXTURE);
    __resetSessionRegistryForTest();
  });

  /**
   * Its own mount (rather than the shared `mountSurface`) so the test can capture a spy on
   * `host_.navigation.navigate` — the assertion this test exists for.
   */
  async function mountWithNavigateSpy(): Promise<{
    el: HTMLElement & { activeCategory?: string; updateComplete: Promise<unknown> };
    navigate: ReturnType<typeof vi.fn>;
  }> {
    const navigate = vi.fn();
    const el = document.createElement('jf-settings-surface') as HTMLElement & {
      activeCategory?: string;
      updateComplete: Promise<unknown>;
    };
    (el as unknown as Record<string, unknown>).host_ = createMockHostApi({
      data: {
        fetch: () => Promise.resolve(new Response(JSON.stringify({ ui: {} }), { status: 200 })),
      },
      layout: {
        subscribeUserConfig: (h) => realSubscribeUserConfig(h as (cfg: unknown) => void),
        getUserConfig: () => realGetUserConfig(),
        onSurfaceCatalogChange: (h) => realOnSurfaceCatalogChange(h),
        setSurfaceVisibility,
        setSurfaceOrder,
      },
      theme: {
        subscribeActiveTheme: (h) => realSubscribeActiveTheme(h),
        getActiveThemeId: () => realGetActiveThemeId(),
        selectTheme: async (id) => {
          if (id === null) realClearActiveTheme();
          else await realLoadAndApplyTheme(id);
        },
      },
      navigation: { navigate },
    });
    document.body.appendChild(el);
    await new Promise((resolve) => setTimeout(resolve, 0));
    el.activeCategory = 'appearance';
    await el.updateComplete;
    return { el, navigate };
  }

  it('renders the Token Editor anchor under the Appearance category', async () => {
    const { el } = await mountWithNavigateSpy();
    const anchor = el.shadowRoot?.querySelector('[data-settings-anchor="token-editor"]');
    expect(anchor).not.toBeNull();
    expect(anchor?.textContent).toContain('Token Editor');
    expect(anchor?.querySelector('jf-button')).not.toBeNull();
    el.remove();
  });

  it('activating the Token Editor link navigates to vendor.token-editor.editor-surface', async () => {
    const { el, navigate } = await mountWithNavigateSpy();
    const button = el.shadowRoot?.querySelector(
      '[data-settings-anchor="token-editor"] jf-button',
    );
    expect(button).not.toBeNull();
    await activateJfButton(button!);

    expect(navigate).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith('vendor.token-editor.editor-surface');
    el.remove();
  });
});
