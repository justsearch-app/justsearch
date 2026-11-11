// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom

/**
 * SettingsSurface × `<jf-settings-nav>` search wiring — tempdoc 855 §6 Phase 4.
 *
 * Covers the seam the tempdoc names explicitly: activating a search result (a category-only hit,
 * and a section hit under a DIFFERENT category than the one currently active) lands
 * `activeCategory` + scrolls to the anchor via the SAME `selectCategory`/`jumpToAnchor` paths
 * `category-select`/`anchor-jump` already drive (`SettingsSurface.ts` `activateSearchHit`).
 *
 * `activeAnchor`'s value is NOT asserted after a render settles: happy-dom's layout is a stub
 * (`getBoundingClientRect()` always reports zero-height), so `measureAnchors()` — re-run from
 * `updated()` on every render (855 §9.5 scroll-spy) — sees zero landmarks and resets `activeAnchor`
 * to null on the very next render, independent of anything this test does. That's a pre-existing
 * property of the (untested-until-now) `jumpToAnchor` mechanism, not something Phase 4 introduces
 * — so the anchor assertion here is the same one the `scrollIntoView` call already relies on
 * elsewhere in this codebase (`UnifiedChatView.test.ts`): capture the scrolled-to element via a
 * `this`-preserving `scrollIntoView` stub and assert on ITS identity instead.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mutateDocument } from '../state/UserStateDocument.js';
import './SettingsSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetThemeStateForTest } from '../state/themeState.js';
import {
  __seedForTest as seedSurfaceCatalog,
  __resetForTest as resetSurfaceCatalog,
} from '../../api/registry/SurfaceCatalogClient.js';
import { __resetSessionRegistryForTest } from '../plugin-api/sessionRegistry.js';
import { __resetUserStateForTest } from '../state/UserStateDocument.js';
import type { SurfaceCatalog } from '../../api/types/surface.js';
import {
  __resetForTest as resetResourceCatalog,
  __seedForTest as seedResourceCatalog,
} from '../../i18n/resourceCatalog.js';

const EMPTY_RAIL: SurfaceCatalog = {
  schemaVersion: '1',
  catalogVersion: 1,
  namespace: 'core',
  primitive: 'Surface',
  entries: [],
};

interface MountedSurface extends HTMLElement {
  host_?: unknown;
  activeCategory: string;
  activeAnchor: string | null;
  updateComplete: Promise<unknown>;
}

async function mountSurface(): Promise<MountedSurface> {
  const el = document.createElement('jf-settings-surface') as MountedSurface;
  el.host_ = createMockHostApi();
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify({ ui: {} }), { status: 200 }),
  );
  el.activeCategory = 'appearance';
  document.body.appendChild(el);
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
  return el;
}

function dispatchSearchSelect(
  el: MountedSurface,
  detail: { categoryId: string; sectionKey?: string },
): void {
  const nav = el.shadowRoot!.querySelector('jf-settings-nav')!;
  nav.dispatchEvent(
    new CustomEvent('search-select', { detail, bubbles: true, composed: true }),
  );
}

/** A `this`-preserving `scrollIntoView` stub (a plain `vi.fn` loses the call's `this`) so a test
 *  can assert WHICH element was scrolled to, independent of `activeAnchor`'s ephemeral state
 *  (see file header). Reassigned fresh in each test's `beforeEach`. */
let scrolledTo: HTMLElement | null;

describe('SettingsSurface — search-select activation (855 §6 Phase 4)', () => {
  beforeEach(() => {
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    __resetUserStateForTest();
    resetSurfaceCatalog();
    seedSurfaceCatalog(EMPTY_RAIL);
    __resetSessionRegistryForTest();
    scrolledTo = null;
    // happy-dom does not implement scrollIntoView (UnifiedChatView.test.ts stubs it the same way).
    HTMLElement.prototype.scrollIntoView = function (this: HTMLElement): void {
      scrolledTo = this;
    };
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('mounts on the default (first) category', async () => {
    const el = await mountSurface();
    expect(el.activeCategory).toBe('appearance');
  });

  it('a category-only hit (no sectionKey) selects that category, same as category-select', async () => {
    const el = await mountSurface();
    dispatchSearchSelect(el, { categoryId: 'advanced' });
    await el.updateComplete;
    expect(el.activeCategory).toBe('advanced');
  });

  it('a section hit under the CURRENT category jumps immediately (content already rendered)', async () => {
    const el = await mountSurface();
    dispatchSearchSelect(el, { categoryId: 'appearance', sectionKey: 'theme' });
    await el.updateComplete;
    expect(el.activeCategory).toBe('appearance');
    expect(scrolledTo?.getAttribute('data-settings-anchor')).toBe('theme');
  });

  it('a section hit under a DIFFERENT category switches category then jumps to the anchor', async () => {
    const el = await mountSurface();
    expect(el.activeCategory).toBe('appearance');

    mutateDocument(doc => ({ ...doc, showDeveloperTools: true }));
    dispatchSearchSelect(el, { categoryId: 'advanced', sectionKey: 'view-tier' });
    // The category switch and the anchor jump are two chained `updateComplete` continuations
    // (SettingsSurface.ts `activateSearchHit`) — await lets both settle.
    await el.updateComplete;

    expect(el.activeCategory).toBe('advanced');
    const anchorEl = el.shadowRoot!.querySelector('[data-settings-anchor="view-tier"]');
    expect(anchorEl).not.toBeNull();
    expect(scrolledTo).toBe(anchorEl);
  });

  it('activating a category-only hit for the category ALREADY active is a no-op (mirrors category-select)', async () => {
    const el = await mountSurface();
    expect(el.activeCategory).toBe('appearance');
    dispatchSearchSelect(el, { categoryId: 'appearance' });
    await el.updateComplete;
    expect(el.activeCategory).toBe('appearance');
    expect(scrolledTo).toBeNull();
  });

  // 855 P4 review MERGE-BLOCKER, real host+child integration: a real click on a rendered
  // search-result row (not a synthetic `dispatchSearchSelect`) must not drop focus to `<body>`.
  // This is the exact multi-hop timing the review found: `activateSearchHit` → `selectCategory`
  // sets THIS surface's `activeCategory` synchronously, but that only reaches the nested
  // `<jf-settings-nav>` as a re-rendered `active-category` attribute one Lit update cycle later
  // than the nav's own query-clear update — so the nav's fix has to settle across a real host
  // round-trip, not just its own single render.
  it('activating a search result via a real click focuses the nav active-category row, not body', async () => {
    const el = await mountSurface();
    expect(el.activeCategory).toBe('appearance');
    const nav = el.shadowRoot!.querySelector('jf-settings-nav') as HTMLElement & {
      updateComplete: Promise<unknown>;
    };
    const input = nav.shadowRoot!.querySelector<HTMLInputElement>('input.search-input')!;
    // No i18n catalog is seeded in this file — `localizeResourceKey` falls back to the raw key
    // (`settings.category.developer`), so a plain substring query still finds it.
    input.value = 'advanced';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await nav.updateComplete;

    const resultRow = nav.shadowRoot!.querySelector<HTMLButtonElement>('button.search-result-row')!;
    expect(resultRow).not.toBeNull();
    resultRow.focus();
    expect(nav.shadowRoot!.activeElement).toBe(resultRow);

    resultRow.click();
    // Flush every pending Lit update on both elements (surface + nav), however many hops the
    // round-trip takes, before asserting the settled state.
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(el.activeCategory).toBe('advanced');
    const activeRow = nav.shadowRoot!.querySelector<HTMLButtonElement>('.category-row.active');
    expect(activeRow).not.toBeNull();
    expect(activeRow?.dataset.settingsCategory).toBe('advanced');
    expect(nav.shadowRoot!.activeElement).toBe(activeRow);
  });
});

// Tempdoc 855 fix-round F2 (S2) — `localizeResourceKey` falls back to the raw i18n key on a cold
// deep-link boot (this surface mounts before the async backend catalog fetch resolves) and,
// before this fix, nothing re-rendered once the catalog arrived: the raw key stuck around
// forever. `renderRelatedSettingsRow`'s `.link-row` label is a real production consumer.
describe('SettingsSurface — re-renders on a late-arriving catalog (855 fix-round F2 S2)', () => {
  beforeEach(() => {
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    __resetUserStateForTest();
    resetSurfaceCatalog();
    seedSurfaceCatalog(EMPTY_RAIL);
    __resetSessionRegistryForTest();
    resetResourceCatalog();
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    resetResourceCatalog();
  });

  it('the related-settings cross-link label resolves once a late catalog merge fires', async () => {
    const el = await mountSurface();
    const linkRow = () => el.shadowRoot!.querySelector('.link-row');
    // No catalog seeded yet — raw key fallback (this file's other tests rely on the same
    // no-catalog-seeded default; see the comment above the real-click test).
    expect(linkRow()?.textContent?.trim()).toContain('settings.section.accessibility');

    // The catalog "arrives" — `__seedForTest` fires the SAME `onCatalogUpdated` notify a real
    // bootMessageCatalog-family merge takes.
    seedResourceCatalog({ 'settings.section.accessibility': 'Accessibility' });
    await el.updateComplete;

    expect(linkRow()?.textContent?.trim()).toContain('Accessibility');
  });
});
