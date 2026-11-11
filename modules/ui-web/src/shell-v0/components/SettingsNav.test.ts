// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom

/**
 * `<jf-settings-nav>` — tempdoc 855 §9.5: the register-driven vertical grouped nav.
 *
 * Covers the projection contract (nav renders exactly what the register declares), the accordion
 * (only the active category shows sub-anchors), the danger-group isolation, the three events the
 * host (`SettingsSurface`) wires (`category-select` / `anchor-jump` / `search-select`, 855 §6
 * Phase 4), and the search box itself: filtering, activation, the no-results state, and the
 * ESC-clears-without-bubbling-to-the-host-dialog contract.
 */
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import './SettingsNav.js';
import type { SettingsGroup } from '../views/settingsRegister.js';
import { __seedForTest, __resetForTest } from '../../i18n/resourceCatalog.js';

const FIXTURE_REGISTER: readonly SettingsGroup[] = [
  {
    id: 'general',
    labelKey: 'settings.group.general',
    categories: [
      {
        id: 'appearance',
        kind: 'native',
        labelKey: 'settings.category.appearance',
        sections: [
          { key: 'interface', labelKey: 'settings.section.interface' },
          { key: 'theme', labelKey: 'settings.section.theme' },
        ],
      },
      { id: 'core.presentation-gallery-surface', kind: 'member', memberSurfaceId: 'core.presentation-gallery-surface' },
    ],
  },
  {
    id: 'danger-group',
    labelKey: 'settings.group.data',
    danger: true,
    categories: [
      {
        id: 'data',
        kind: 'native',
        labelKey: 'settings.category.data',
        sections: [{ key: 'data', labelKey: 'settings.section.data' }],
      },
    ],
  },
];

async function mountNav(
  activeCategory: string,
): Promise<
  HTMLElement & {
    register: unknown;
    activeCategory: string;
    activeAnchor: string | null;
    updateComplete: Promise<unknown>;
  }
> {
  const el = document.createElement('jf-settings-nav') as HTMLElement & {
    register: unknown;
    activeCategory: string;
    activeAnchor: string | null;
    updateComplete: Promise<unknown>;
  };
  el.register = FIXTURE_REGISTER;
  el.activeCategory = activeCategory;
  el.activeAnchor = null;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('jf-settings-nav', () => {
  beforeEach(() => {
    __resetForTest();
    __seedForTest({
      'settings.group.general': 'General',
      'settings.group.data': 'Data',
      'settings.category.appearance': 'Appearance',
      'settings.category.data': 'Data',
      'settings.section.interface': 'Interface',
      'settings.section.theme': 'Theme',
      'settings.section.data': 'Data',
      'settings.search.placeholder': 'Search settings',
      'settings.search.no-results': 'No matching settings',
    });
  });

  afterEach(() => {
    document.body.innerHTML = '';
    __resetForTest();
  });

  it('renders one category row per declared category, in register order', async () => {
    const el = await mountNav('appearance');
    const rows = Array.from(
      el.shadowRoot!.querySelectorAll<HTMLButtonElement>('button.category-row'),
    );
    expect(rows.map((r) => r.dataset.settingsCategory)).toEqual([
      'appearance',
      'core.presentation-gallery-surface',
      'data',
    ]);
  });

  it('accordion: only the ACTIVE category shows its sub-anchors', async () => {
    const el = await mountNav('appearance');
    const anchors = Array.from(el.shadowRoot!.querySelectorAll('button.anchor-row')).map(
      (b) => b.textContent?.trim(),
    );
    expect(anchors).toEqual(['Interface', 'Theme']);
  });

  it('switching the active category moves the accordion', async () => {
    const el = await mountNav('data');
    const anchors = Array.from(el.shadowRoot!.querySelectorAll('button.anchor-row')).map(
      (b) => b.textContent?.trim(),
    );
    expect(anchors).toEqual(['Data']);
  });

  it('a member category (no declared sections) shows no sub-anchors even when active', async () => {
    const el = await mountNav('core.presentation-gallery-surface');
    expect(el.shadowRoot!.querySelectorAll('button.anchor-row').length).toBe(0);
  });

  it('clicking a category row dispatches category-select with its id', async () => {
    const el = await mountNav('appearance');
    let detail: { id: string } | null = null;
    el.addEventListener('category-select', (e) => {
      detail = (e as CustomEvent<{ id: string }>).detail;
    });
    const dataRow = el.shadowRoot!.querySelector<HTMLButtonElement>(
      'button[data-settings-category="data"]',
    )!;
    dataRow.click();
    expect(detail).toEqual({ id: 'data' });
  });

  it('clicking a sub-anchor dispatches anchor-jump with its key', async () => {
    const el = await mountNav('appearance');
    let detail: { key: string } | null = null;
    el.addEventListener('anchor-jump', (e) => {
      detail = (e as CustomEvent<{ key: string }>).detail;
    });
    const themeAnchor = el.shadowRoot!.querySelector<HTMLButtonElement>(
      'button[data-settings-anchor="theme"]',
    )!;
    themeAnchor.click();
    expect(detail).toEqual({ key: 'theme' });
  });

  it('the danger group isolates its category row with the danger class', async () => {
    const el = await mountNav('appearance');
    const dataRow = el.shadowRoot!.querySelector('button[data-settings-category="data"]')!;
    expect(dataRow.classList.contains('danger')).toBe(true);
    const appearanceRow = el.shadowRoot!.querySelector(
      'button[data-settings-category="appearance"]',
    )!;
    expect(appearanceRow.classList.contains('danger')).toBe(false);
  });

  it('aria-current marks the active category row', async () => {
    const el = await mountNav('data');
    const dataRow = el.shadowRoot!.querySelector('button[data-settings-category="data"]')!;
    const appearanceRow = el.shadowRoot!.querySelector(
      'button[data-settings-category="appearance"]',
    )!;
    expect(dataRow.getAttribute('aria-current')).toBe('true');
    expect(appearanceRow.hasAttribute('aria-current')).toBe(false);
  });

  // Tempdoc 855 fix-round F2 (N6) — pins the no-pill rule (855 §13/§15.1 remediation): only the
  // active CATEGORY row carries the neutral --surface-active pill; the active ANCHOR row is plain
  // text (brighter color + aria-current, no background). Mounts the REAL component (so the
  // adopted-stylesheet cascade applies) and injects the theme custom property the CSS reads on
  // :root — the same exact-value technique `SecuritySurface.cardCascade.test.ts` uses, so a
  // "not the pill color" assertion can't pass vacuously against an unresolved var().
  describe('active-row background: category pill vs. anchor no-pill (fix-round F2 N6)', () => {
    const SURFACE_ACTIVE = 'rgb(10, 20, 30)';
    let tokenStyleEl: HTMLStyleElement | null = null;

    beforeEach(() => {
      tokenStyleEl = document.createElement('style');
      tokenStyleEl.textContent = `:root { --surface-active: ${SURFACE_ACTIVE}; }`;
      document.head.appendChild(tokenStyleEl);
    });

    afterEach(() => {
      tokenStyleEl?.remove();
      tokenStyleEl = null;
    });

    it('the active CATEGORY row keeps the --surface-active pill', async () => {
      const el = await mountNav('appearance');
      el.activeAnchor = 'theme';
      await el.updateComplete;
      const categoryRow = el.shadowRoot!.querySelector('button.category-row.active') as HTMLElement;
      expect(categoryRow, 'the active category row must render').toBeTruthy();
      expect(getComputedStyle(categoryRow).backgroundColor).toBe(SURFACE_ACTIVE);
    });

    it('the active ANCHOR row carries aria-current but a transparent background (no pill)', async () => {
      const el = await mountNav('appearance');
      el.activeAnchor = 'theme';
      await el.updateComplete;
      const anchorRow = el.shadowRoot!.querySelector('button.anchor-row.active') as HTMLElement;
      expect(anchorRow, 'the active anchor row must render').toBeTruthy();
      expect(anchorRow.getAttribute('aria-current')).toBe('true');
      const bg = getComputedStyle(anchorRow).backgroundColor;
      // Pins BOTH directions: never the category pill's color, AND actually transparent (not just
      // "some other color") — an unset `background` on the button base rule computes to one of
      // these two representations depending on the engine.
      expect(bg).not.toBe(SURFACE_ACTIVE);
      expect(['rgba(0, 0, 0, 0)', 'transparent']).toContain(bg);
    });
  });

  describe('search (855 §6 Phase 4)', () => {
    function searchInput(el: HTMLElement): HTMLInputElement {
      return el.shadowRoot!.querySelector<HTMLInputElement>('input.search-input')!;
    }

    it('the search input is a real labeled input (aria-label from i18n)', async () => {
      const el = await mountNav('appearance');
      const input = searchInput(el);
      expect(input).not.toBeNull();
      expect(input.getAttribute('aria-label')).toBe('Search settings');
      expect(input.getAttribute('placeholder')).toBe('Search settings');
    });

    it('an empty query shows the grouped view, not the results list', async () => {
      const el = await mountNav('appearance');
      expect(el.shadowRoot!.querySelector('.groups')).not.toBeNull();
      expect(el.shadowRoot!.querySelector('.results')).toBeNull();
    });

    it('typing filters to a flat result list (category row for a category-label match)', async () => {
      const el = await mountNav('appearance');
      const input = searchInput(el);
      input.value = 'appear';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await el.updateComplete;
      expect(el.shadowRoot!.querySelector('.groups')).toBeNull();
      const rows = Array.from(el.shadowRoot!.querySelectorAll('button.search-result-row'));
      expect(rows).toHaveLength(1);
      expect(rows[0]!.textContent).toContain('Appearance');
    });

    it('typing a section term filters to a section row, labeled with its category', async () => {
      const el = await mountNav('appearance');
      const input = searchInput(el);
      input.value = 'theme';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await el.updateComplete;
      const rows = Array.from(el.shadowRoot!.querySelectorAll('button.search-result-row'));
      expect(rows).toHaveLength(1);
      expect(rows[0]!.textContent).toContain('Theme');
      expect(rows[0]!.querySelector('.result-category')?.textContent).toBe('Appearance');
    });

    it('a query with no matches renders the no-results state', async () => {
      const el = await mountNav('appearance');
      const input = searchInput(el);
      input.value = 'zzz-nonexistent';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await el.updateComplete;
      expect(el.shadowRoot!.querySelector('.search-empty')?.textContent?.trim()).toBe(
        'No matching settings',
      );
      expect(el.shadowRoot!.querySelectorAll('button.search-result-row')).toHaveLength(0);
    });

    it('clicking a category-match result dispatches search-select with just the categoryId', async () => {
      const el = await mountNav('appearance');
      const input = searchInput(el);
      input.value = 'appear';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await el.updateComplete;
      let detail: { categoryId: string; sectionKey?: string } | null = null;
      el.addEventListener('search-select', (e) => {
        detail = (e as CustomEvent<{ categoryId: string; sectionKey?: string }>).detail;
      });
      el.shadowRoot!.querySelector<HTMLButtonElement>('button.search-result-row')!.click();
      expect(detail).toEqual({ categoryId: 'appearance', sectionKey: undefined });
    });

    it('clicking a section-match result dispatches search-select with both categoryId and sectionKey', async () => {
      const el = await mountNav('appearance');
      const input = searchInput(el);
      input.value = 'theme';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await el.updateComplete;
      let detail: { categoryId: string; sectionKey?: string } | null = null;
      el.addEventListener('search-select', (e) => {
        detail = (e as CustomEvent<{ categoryId: string; sectionKey?: string }>).detail;
      });
      el.shadowRoot!.querySelector<HTMLButtonElement>('button.search-result-row')!.click();
      expect(detail).toEqual({ categoryId: 'appearance', sectionKey: 'theme' });
    });

    it('activating a result clears the query, restoring the grouped view', async () => {
      const el = await mountNav('appearance');
      const input = searchInput(el);
      input.value = 'appear';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await el.updateComplete;
      el.shadowRoot!.querySelector<HTMLButtonElement>('button.search-result-row')!.click();
      await el.updateComplete;
      expect(searchInput(el).value).toBe('');
      expect(el.shadowRoot!.querySelector('.groups')).not.toBeNull();
    });

    // 855 P4 review MERGE-BLOCKER: clearing `query` on activation removes the just-focused result
    // row from the DOM, so without an explicit focus() call the browser drops focus to `<body>`
    // (empirically verified pre-fix). This mounts nav standalone (no host listener rewriting
    // `activeCategory`), so the already-active 'appearance' row is available from the FIRST render —
    // the multi-hop host round-trip case (host sets `activeCategory` a tick later than this nav's
    // own query-clear update) is covered separately in SettingsSurface.search.test.ts, where the
    // REAL host is present.
    it('activating a result focuses the restored active category row, not body', async () => {
      const el = await mountNav('appearance');
      const input = searchInput(el);
      input.value = 'appear';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await el.updateComplete;
      const resultRow = el.shadowRoot!.querySelector<HTMLButtonElement>('button.search-result-row')!;
      resultRow.focus();
      expect(el.shadowRoot!.activeElement).toBe(resultRow);

      resultRow.click();
      await el.updateComplete;
      // Flush any additional microtask hops the fix's settle-loop needs beyond this one
      // `updateComplete`, so the assertion below observes the loop's end state deterministically.
      await new Promise((resolve) => setTimeout(resolve, 0));

      const activeRow = el.shadowRoot!.querySelector<HTMLButtonElement>('.category-row.active');
      expect(activeRow).not.toBeNull();
      expect(activeRow?.dataset.settingsCategory).toBe('appearance');
      expect(el.shadowRoot!.activeElement).toBe(activeRow);
    });

    it('Escape in the search input clears a non-empty query', async () => {
      const el = await mountNav('appearance');
      const input = searchInput(el);
      input.value = 'appear';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await el.updateComplete;
      expect(el.shadowRoot!.querySelector('.results')).not.toBeNull();

      input.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true }),
      );
      await el.updateComplete;
      expect(input.value).toBe('');
      expect(el.shadowRoot!.querySelector('.groups')).not.toBeNull();
    });

    // The nav lives inside `<jf-settings-window>`'s native <dialog>; an ESC that only means
    // "clear my search" must never bubble to a listener that would interpret it as "close the
    // dialog" (the host's `@cancel` handler). This is the exact regression 855 §6 Phase 4 names.
    it('Escape in the search input does NOT bubble past the nav (would-be dialog cancel stays silent)', async () => {
      const el = await mountNav('appearance');
      const input = searchInput(el);
      input.value = 'appear';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await el.updateComplete;

      const outerKeydown = vi.fn();
      document.addEventListener('keydown', outerKeydown);
      input.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true }),
      );
      document.removeEventListener('keydown', outerKeydown);
      await el.updateComplete;

      expect(outerKeydown).not.toHaveBeenCalled();
      expect(input.value).toBe('');
    });

    // 855 P4 review should-fix: two-stage ESC. A non-empty query's Escape stays nav-local (the test
    // above). An ALREADY-EMPTY query has nothing left for this nav to clear, so Escape must now
    // bubble past the nav — the house convention (CommandPalette closes on ESC unconditionally;
    // Discord's own window closes on ESC) is that the enclosing `<dialog>`'s native Escape handling
    // (not simulated by happy-dom's synthetic keydown here, but reachable — see the
    // SettingsWindow-seam test) gets to run instead of the nav silently eating the keystroke.
    it('Escape in the search input when already empty bubbles past the nav (two-stage ESC lets the dialog close)', async () => {
      const el = await mountNav('appearance');
      const input = searchInput(el);
      const outerKeydown = vi.fn();
      document.addEventListener('keydown', outerKeydown);
      const event = new KeyboardEvent('keydown', {
        key: 'Escape',
        bubbles: true,
        composed: true,
        cancelable: true,
      });
      input.dispatchEvent(event);
      document.removeEventListener('keydown', outerKeydown);
      expect(outerKeydown).toHaveBeenCalledTimes(1);
      expect(event.defaultPrevented).toBe(false);
      expect(el.shadowRoot!.querySelector('.groups')).not.toBeNull();
    });

    it('ArrowDown roves focus between search-result rows (reuses the nav row-navigation handler)', async () => {
      const el = await mountNav('appearance');
      const input = searchInput(el);
      input.value = 'data';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await el.updateComplete;
      const rows = Array.from(
        el.shadowRoot!.querySelectorAll<HTMLButtonElement>('button.search-result-row'),
      );
      expect(rows.length).toBeGreaterThanOrEqual(2);
      rows[0]!.focus();
      expect(el.shadowRoot!.activeElement).toBe(rows[0]);
      rows[0]!.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, composed: true, cancelable: true }),
      );
      expect(el.shadowRoot!.activeElement).toBe(rows[1]);
    });

    it('Home/End typed in the search input are NOT hijacked by the row roving-tabindex handler', async () => {
      const el = await mountNav('appearance');
      const input = searchInput(el);
      input.value = 'a';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await el.updateComplete;
      input.focus();
      input.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Home', bubbles: true, composed: true, cancelable: true }),
      );
      // Focus must stay on the input — the nav-level onKeydown row-navigation handler (which would
      // call rows[0]?.focus()) must never see this event.
      expect(el.shadowRoot!.activeElement).toBe(input);
    });
  });

  // Tempdoc 855 fix-round F2 (S2) — `localizeResourceKey` falls back to the raw i18n key on a cold
  // deep-link boot (this nav mounts before the async backend catalog fetch resolves) and, before
  // this fix, nothing re-rendered once it arrived: the raw key stuck around forever.
  describe('re-renders on a late-arriving catalog (855 fix-round F2 S2)', () => {
    it('a section label mounted BEFORE its catalog key exists shows the raw key, then resolves once the catalog arrives', async () => {
      // Mount with the group label key deliberately UNSEEDED — the raw-key fallback SettingsNav
      // would show on a cold deep-link boot before the backend catalog fetch settles.
      const preArrival = {
        'settings.category.data': 'Data',
        'settings.section.interface': 'Interface',
        'settings.section.theme': 'Theme',
        'settings.section.data': 'Data',
        'settings.search.placeholder': 'Search settings',
        'settings.search.no-results': 'No matching settings',
        // 'settings.group.general' intentionally omitted — simulates the not-yet-arrived catalog.
      };
      __resetForTest();
      __seedForTest(preArrival);
      const el = await mountNav('appearance');
      const header = () => el.shadowRoot!.querySelector('.category-row');
      expect(header()?.textContent?.trim()).toBe('settings.category.appearance');

      // The catalog "arrives" — `__seedForTest` fires the SAME `onCatalogUpdated` notify a real
      // bootMessageCatalog-family merge takes; re-seed with the union (a real merge is additive,
      // never a replace) to keep the already-seeded keys resolved too.
      __seedForTest({ ...preArrival, 'settings.category.appearance': 'Appearance' });
      await el.updateComplete;

      expect(header()?.textContent?.trim()).toBe('Appearance');
    });
  });
});
