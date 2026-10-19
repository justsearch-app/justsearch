// SPDX-License-Identifier: Apache-2.0
/**
 * `<jf-settings-nav>` — tempdoc 855 §9.5: the settings window's vertical grouped nav.
 *
 * Projects the ONE declared register (`views/settingsRegister.ts`) into: group headers + divider
 * rules, category rows, and — for the ACTIVE category only (accordion) — its indented sub-anchor
 * rows. The isolated danger group (Data) renders in the danger token role.
 *
 * Scroll-spy is two-way but this component does NOT own the scroll math: the host
 * (`SettingsSurface`, which owns the scrollable content pane) derives the in-view sub-anchor via
 * the house `deriveFocus()`/`anchorFractions()` (`primitives/navigation.ts` — the codebase
 * deliberately retired its only IntersectionObserver in favor of this derived-focus-on-scroll
 * math) and passes it down as `activeAnchor`; clicking a sub-anchor here dispatches `anchor-jump`
 * and the host scrolls (honoring `prefers-reduced-motion`, mirroring `NavigationController.jumpTo`).
 *
 * a11y (853 floor): real sibling `<button>` rows, never nested-interactive; roving tabindex over
 * every VISIBLE row (category rows + the active category's sub-anchors, OR the flat search-result
 * rows when a query is active); `aria-current` marks the active category/anchor; row hit areas are
 * ≥32px tall (≥24px floor); every color is an existing token role valid across all four palettes
 * (no new colors).
 *
 * Search (855 §6 Phase 4): a labeled input tops the nav (the reserved spot per §4's footer note —
 * "search field tops the nav instead" of a profile header). A non-empty query switches the body
 * from the grouped/accordion view to a flat `searchRegister()` result list (category rows + section
 * rows, section rows labeled with their category); activating a hit dispatches `search-select`
 * (the SAME `selectCategory`/`jumpToAnchor` paths `category-select`/`anchor-jump` already drive —
 * the host composes them), then deterministically restores focus to the grouped view's active
 * category row (855 P4 review merge-blocker — clearing the query removes the focused result row
 * from the DOM, so this must not rely on browser default focus behavior). Escape is two-stage
 * (855 P4 review should-fix): a non-empty query clears + `stopPropagation`s, same as before, so it
 * never reaches the enclosing `<dialog>`'s `cancel` handler; an ALREADY-EMPTY query does nothing
 * here, letting Escape reach the host `<dialog>` and close the window (house convention: ESC closes
 * when there's nothing left to clear).
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { localizeResourceKey, onCatalogUpdated } from '../../i18n/resourceCatalog.js';
import {
  categoryLabel,
  searchRegister,
  type SettingsGroup,
  type SettingsCategory,
  type SettingsSearchResult,
} from '../views/settingsRegister.js';

export class SettingsNav extends JfElement {
  static properties = {
    register: { attribute: false },
    activeCategory: { type: String, attribute: 'active-category' },
    activeAnchor: { type: String, attribute: 'active-anchor' },
    footerVersion: { type: String, attribute: 'footer-version' },
    query: { state: true },
  };

  declare register: readonly SettingsGroup[];
  declare activeCategory: string;
  declare activeAnchor: string | null;
  /** Optional app-version string for the nav footer (855 §4 — "footer: app version, click-to-copy").
   *  Omitted entirely when no clean source is available (see SettingsSurface for the source check). */
  declare footerVersion: string | null;
  /** 855 §6 Phase 4 — the search box's live value; internal to the nav (not reflected to an
   *  attribute — the host never needs to read it, only the `search-select` activation result). */
  declare query: string;
  /** 855 P4 review nit — roving tabindex over search-result rows tracks the LAST-focused row
   *  (not always index 0), matching the category/anchor rows' existing active-row tabindex
   *  pattern; reset to 0 whenever the query (and therefore the result set) changes. */
  private lastFocusedResultIndex = 0;

  /** Tempdoc 855 fix-round F2 (S2) — labels resolve via `localizeResourceKey`, which is
   *  synchronous and falls back to the raw key on a cold deep-link boot (this nav can mount before
   *  the backend catalog fetch settles). Re-renders when the catalog updates so a raw-key label
   *  resolves to its real text once the catalog arrives, instead of staying raw forever. */
  private catalogUpdatedUnsub: (() => void) | null = null;

  constructor() {
    super();
    this.register = [];
    this.activeCategory = '';
    this.activeAnchor = null;
    this.footerVersion = null;
    this.query = '';
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.catalogUpdatedUnsub = onCatalogUpdated(() => this.requestUpdate());
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.catalogUpdatedUnsub?.();
    this.catalogUpdatedUnsub = null;
  }

  static styles = css`
    :host {
      display: flex;
      flex-direction: column;
      min-height: 0;
      height: 100%;
      width: 240px;
      flex: 0 0 240px;
      border-right: 1px solid var(--border-subtle);
      background: var(--surface-2, var(--surface-secondary));
    }
    nav {
      display: flex;
      flex-direction: column;
      height: 100%;
      min-height: 0;
    }
    .search-row {
      flex-shrink: 0;
      padding: 0.75rem 0.5rem 0.5rem;
    }
    .search-input {
      width: 100%;
      min-height: 2rem;
      box-sizing: border-box;
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      background: var(--surface-1);
      color: var(--text-primary);
      font: inherit;
      font-size: var(--font-size-sm);
      padding: 0.375rem 0.625rem;
    }
    /* 574 Move 1/2 — placeholder color is a Class-B ambient facet (ambient-purity gate): the ONE
       definition lives in ambientStyles.ts, adopted into this shadow root by JfElement. No local
       placeholder rule here. */
    .search-input:focus-visible {
      outline: 2px solid var(--focus-ring-color, var(--accent-tint));
      outline-offset: -1px;
    }
    .groups,
    .results {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      padding: 0.75rem 0.5rem;
    }
    .results-list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 0.125rem;
    }
    button.search-result-row {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      width: 100%;
      min-height: 2rem;
      box-sizing: border-box;
      border: none;
      border-radius: 0.5rem;
      background: transparent;
      color: var(--text-primary);
      font: inherit;
      text-align: left;
      cursor: pointer;
      padding: 0.375rem 0.5rem;
    }
    button.search-result-row:hover {
      background: var(--surface-hover);
    }
    button.search-result-row .result-category {
      font-size: var(--font-size-xs);
      color: var(--text-tertiary, var(--text-secondary));
    }
    .search-empty {
      padding: 0.5rem;
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
    }
    .group {
      padding-top: 0.75rem;
    }
    .group:first-child {
      padding-top: 0;
    }
    .group + .group {
      margin-top: 0.5rem;
      border-top: 1px solid var(--border-subtle);
    }
    .group-header {
      padding: 0.25rem 0.5rem;
      font-size: var(--font-size-sm);
      font-weight: 500;
      letter-spacing: 0.02em;
      color: var(--text-secondary);
    }
    .group.danger .group-header {
      color: var(--text-danger);
    }
    .category-list,
    .anchor-list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 0.125rem;
    }
    button.category-row,
    button.anchor-row {
      display: flex;
      align-items: center;
      width: 100%;
      min-height: 2rem;
      box-sizing: border-box;
      border: none;
      border-radius: 0.5rem;
      background: transparent;
      color: var(--text-secondary);
      font: inherit;
      font-size: var(--font-size-sm);
      text-align: left;
      cursor: pointer;
      padding: 0.375rem 0.5rem;
    }
    button.category-row:hover {
      color: var(--text-primary);
      background: var(--surface-hover);
    }
    /* Tempdoc 855 §13/§15.1 remediation — sub-anchors are plain text (Discord's own Appearance →
       Theme/App Icon/… rows carry NO background pill, only a text-brightness change); only
       CATEGORY rows keep the pill. Hover on an anchor is a subtle text-brightness change, no wash. */
    button.anchor-row:hover {
      color: var(--text-primary);
    }
    /* Discord-2025 measured spec (855 §2): a NEUTRAL translucent active pill, not the brand accent.
       Uses --surface-active (distinct from :hover's --surface-hover, tempdoc 855 §12 P3a) — sharing
       --surface-hover made the active row and a hovered-but-inactive row render identically, so
       hovering elsewhere in the list visually "double-highlighted" two rows at once. Categories only
       — anchors get brighter text + aria-current, no background (855 §13/§15.1). */
    button.category-row.active {
      color: var(--text-primary);
      background: var(--surface-active);
    }
    button.category-row.danger {
      color: var(--text-danger);
    }
    button.category-row.danger:hover,
    button.category-row.danger.active {
      background: var(--accent-danger-16);
    }
    button.anchor-row {
      min-height: 1.75rem;
      font-size: var(--font-size-xs);
      padding-left: 1.25rem;
      color: var(--text-tertiary, var(--text-secondary));
    }
    button.anchor-row.active {
      color: var(--text-primary);
    }
    button:focus-visible {
      outline: 2px solid var(--focus-ring-color, var(--accent-tint));
      outline-offset: -2px;
    }
    /* Forced colors (Windows High Contrast) erase the active pill and the brighter anchor text, so
       the current row takes the selection pair ('forced-color-adjust: none' keeps the browser's
       Canvas backplate from landing behind the label); its INSET focus ring turns HighlightText to
       stay visible on that fill. */
    @media (forced-colors: active) {
      button.category-row.active,
      button.anchor-row.active {
        forced-color-adjust: none;
        background: Highlight;
        color: HighlightText;
      }
      button.category-row.active *,
      button.anchor-row.active * {
        color: HighlightText;
      }
      button.category-row.active:focus-visible,
      button.anchor-row.active:focus-visible {
        outline-color: HighlightText;
      }
    }
    .footer {
      flex-shrink: 0;
      padding: 0.5rem 0.75rem;
      border-top: 1px solid var(--border-subtle);
      font-size: var(--font-size-xs);
      color: var(--text-tertiary, var(--text-secondary));
    }
  `;

  private select(id: string): void {
    if (id === this.activeCategory) return;
    this.dispatchEvent(
      new CustomEvent('category-select', { detail: { id }, bubbles: true, composed: true }),
    );
  }

  private jump(key: string): void {
    this.dispatchEvent(
      new CustomEvent('anchor-jump', { detail: { key }, bubbles: true, composed: true }),
    );
  }

  /** Roving tabindex (853/571-precedent pattern): Up/Down/Home/End across every VISIBLE row. */
  private onKeydown(e: KeyboardEvent): void {
    const rows = Array.from(
      this.shadowRoot?.querySelectorAll<HTMLButtonElement>('button[data-nav-row]') ?? [],
    );
    if (rows.length === 0) return;
    const activeEl = this.shadowRoot?.activeElement as HTMLButtonElement | null;
    const i = activeEl ? rows.indexOf(activeEl) : -1;
    let next: number;
    switch (e.key) {
      case 'ArrowDown':
        next = i < 0 ? 0 : (i + 1) % rows.length;
        break;
      case 'ArrowUp':
        next = i < 0 ? rows.length - 1 : (i - 1 + rows.length) % rows.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = rows.length - 1;
        break;
      default:
        return;
    }
    e.preventDefault();
    rows[next]?.focus();
  }

  private onSearchInput(e: Event): void {
    this.query = (e.target as HTMLInputElement).value;
    // A fresh filter is a fresh result set — restart the roving tabindex at row 0 (855 P4 review
    // nit) rather than keeping a stale index from the previous query's rows.
    this.lastFocusedResultIndex = 0;
  }

  /** Non-Escape keys typed into the search input are the input's own business (cursor movement via
   *  Home/End/arrows must NOT be hijacked by `onKeydown`'s row roving-tabindex, which listens on
   *  the enclosing `<nav>`) — so those stop propagation unconditionally.
   *
   *  Escape is two-stage (855 P4 review should-fix): a non-empty query means Escape's job is
   *  "clear the search", so it clears + stops here, exactly as before. An ALREADY-EMPTY query means
   *  there is nothing left for this nav to do with Escape — the house convention (CommandPalette
   *  closes on ESC unconditionally; Discord's own window closes on ESC) is that Escape now means
   *  "close the window", so this deliberately does NOT preventDefault/stopPropagation, letting the
   *  keydown reach the host `<dialog>`'s native Escape handling (which fires `cancel`). */
  private onSearchKeydown(e: KeyboardEvent): void {
    if (e.key === 'Escape') {
      if (!this.query) return;
      e.preventDefault();
      e.stopPropagation();
      this.query = '';
      return;
    }
    e.stopPropagation();
  }

  /** Activation reuses the SAME `selectCategory`/`jumpToAnchor` paths `category-select` and
   *  `anchor-jump` already drive — the host composes them from one event (855 §6 Phase 4). Clears
   *  the query afterward so the nav returns to the grouped view at the newly active category, and
   *  deterministically restores focus there (855 P4 review merge-blocker): clearing `query` removes
   *  the just-activated result row from the DOM, so without an explicit `focus()` the browser drops
   *  focus to `<body>` (empirically verified).
   *
   *  The host's `search-select` listener (`SettingsSurface.activateSearchHit` → `selectCategory`)
   *  sets ITS OWN `activeCategory` property synchronously, but that only reaches this nav as a
   *  re-rendered `active-category` ATTRIBUTE one Lit update cycle later than THIS nav's own
   *  query-clear render (host update → attribute write → this nav's second update) — so a single
   *  `await this.updateComplete` is not guaranteed to observe the restored `activeCategory` yet.
   *  Lit's own documented pattern for exactly this ("updateComplete... won't account for updates
   *  triggered during the update itself... await it in a loop") is the deterministic settle point:
   *  loop until no further update is pending, THEN read the DOM. */
  private async activateSearchResult(result: SettingsSearchResult): Promise<void> {
    this.dispatchEvent(
      new CustomEvent('search-select', {
        detail: { categoryId: result.category.id, sectionKey: result.section?.key },
        bubbles: true,
        composed: true,
      }),
    );
    this.query = '';
    let settled = await this.updateComplete;
    while (!settled) {
      settled = await this.updateComplete;
    }
    const activeRow = this.shadowRoot?.querySelector<HTMLButtonElement>('.category-row.active');
    if (activeRow) {
      activeRow.focus();
    } else {
      this.shadowRoot?.querySelector<HTMLInputElement>('.search-input')?.focus();
    }
  }

  private renderSearchResult(
    result: SettingsSearchResult,
    isRoving: boolean,
    onFocus: () => void,
  ): TemplateResult {
    const label = result.section ? localizeResourceKey(result.section.labelKey) : categoryLabel(result.category);
    return html`
      <li>
        <button
          type="button"
          data-nav-row
          class="search-result-row"
          tabindex=${isRoving ? '0' : '-1'}
          @click=${() => void this.activateSearchResult(result)}
          @focus=${onFocus}
        >
          <span>${label}</span>
          ${result.section
            ? html`<span class="result-category">${categoryLabel(result.category)}</span>`
            : nothing}
        </button>
      </li>
    `;
  }

  private renderSearchResults(): TemplateResult {
    const results = searchRegister(this.query, this.register);
    if (results.length === 0) {
      return html`<div class="search-empty" role="status">
        ${localizeResourceKey('settings.search.no-results')}
      </div>`;
    }
    // 855 P4 review nit: roving tabindex follows the last-focused row, falling back to 0 when it
    // no longer fits the (possibly narrower) filtered result set.
    const rovingIndex =
      this.lastFocusedResultIndex < results.length ? this.lastFocusedResultIndex : 0;
    return html`
      <div class="results">
        <ul class="results-list">
          ${results.map((r, i) =>
            this.renderSearchResult(r, i === rovingIndex, () => {
              this.lastFocusedResultIndex = i;
            }),
          )}
        </ul>
      </div>
    `;
  }

  private renderCategory(category: SettingsCategory, danger: boolean): TemplateResult {
    const isActive = category.id === this.activeCategory;
    const sections = (category.sections ?? []).filter((s) => !s.gate || s.gate());
    return html`
      <li>
        <button
          type="button"
          data-nav-row
          data-settings-category=${category.id}
          class="category-row ${isActive ? 'active' : ''} ${danger ? 'danger' : ''}"
          aria-current=${isActive ? 'true' : nothing}
          tabindex=${isActive ? '0' : '-1'}
          @click=${() => this.select(category.id)}
        >
          ${categoryLabel(category)}
        </button>
        ${isActive && sections.length > 0
          ? html`
              <ul class="anchor-list">
                ${sections.map((s) => {
                  const anchorActive = this.activeAnchor === s.key;
                  return html`
                    <li>
                      <button
                        type="button"
                        data-nav-row
                        data-settings-anchor=${s.key}
                        class="anchor-row ${anchorActive ? 'active' : ''}"
                        aria-current=${anchorActive ? 'true' : nothing}
                        tabindex=${anchorActive ? '0' : '-1'}
                        @click=${() => this.jump(s.key)}
                      >
                        ${localizeResourceKey(s.labelKey)}
                      </button>
                    </li>
                  `;
                })}
              </ul>
            `
          : nothing}
      </li>
    `;
  }

  private renderGroup(group: SettingsGroup): TemplateResult {
    return html`
      <div class="group ${group.danger ? 'danger' : ''}">
        <div class="group-header">${localizeResourceKey(group.labelKey)}</div>
        <ul class="category-list">
          ${group.categories.map((c) => this.renderCategory(c, group.danger === true))}
        </ul>
      </div>
    `;
  }

  override render(): TemplateResult {
    const searching = this.query.trim().length > 0;
    const searchLabel = localizeResourceKey('settings.search.placeholder');
    return html`
      <nav aria-label="Settings categories" @keydown=${(e: KeyboardEvent) => this.onKeydown(e)}>
        <div class="search-row">
          <input
            type="text"
            class="search-input"
            aria-label=${searchLabel}
            placeholder=${searchLabel}
            .value=${this.query}
            @input=${this.onSearchInput}
            @keydown=${(e: KeyboardEvent) => this.onSearchKeydown(e)}
          />
        </div>
        ${searching
          ? this.renderSearchResults()
          : html`<div class="groups">${this.register.map((g) => this.renderGroup(g))}</div>`}
        ${this.footerVersion
          ? html`<div class="footer">${this.footerVersion}</div>`
          : nothing}
      </nav>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-settings-nav')) {
  customElements.define('jf-settings-nav', SettingsNav);
}
