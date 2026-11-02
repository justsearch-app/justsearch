// SPDX-License-Identifier: Apache-2.0
/**
 * `<jf-surface-tabs>` — tempdoc 571 §11 / 578: the host/member composition primitive's
 * presentation half. A host surface composes this to present its declared member surfaces (and,
 * optionally, its own body) as tabs — generalizing the proven, hand-rolled HealthSurface §11.8
 * Health|Logs strip into one reusable component so a host never hard-codes member tags (the 561
 * "declaration without iteration" anti-pattern).
 *
 * Design notes:
 *  - **Iteration, not hard-coding.** The host passes an ordered `items` list; the component renders
 *    a `role="tablist"` over it and mounts the active member via the SAME path the Stage uses
 *    (`ensureSurfaceLoaded` → `mountSurface`), so members load lazily and stay self-contained.
 *  - **One member mounted at a time.** Only the active tab's element is in the DOM; switching tabs
 *    disconnects the previous member, so its SSE/streams tear down by construction (the §11.8
 *    connection-pool lesson becomes free — no per-host suspend wiring).
 *  - **Per-tab altitude framing (578 §9.4.B).** Each tab carries `data-altitude`; a TRUST member
 *    (e.g. Activity's audit) keeps a visible "Audit" marker + accessible name even when it sits
 *    beside DIAGNOSTIC members, so a cross-altitude host preserves Law II inside the composite.
 *  - **a11y.** Roving-tabindex arrow-key navigation (the piece 571 §11.2-D5 said to add). The host
 *    owns the single page `<h1>`; member surfaces must NOT emit their own `<h1>`/`role="main"`
 *    (the member-demotion rule; 571 §11 / 578).
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import {
  getSurface,
  mountSurface,
} from '../../api/registry/SurfaceCatalogClient.js';
import { ensureSurfaceLoaded, isLazySurface } from '../views/lazySurfaceRegistry.js';
import type { Altitude } from '../../api/types/surface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

/** One tab in a composed host. Either mounts a member surface, or renders host-provided content. */
export interface SurfaceTabItem {
  /** Stable tab id. For a member tab this is the member surface id (used for deep-link selection). */
  id: string;
  /** Human label rendered in the tab strip. */
  label: string;
  /** Declared altitude of the tab's content — drives per-tab framing (578 §9.4.B). */
  altitude?: Altitude;
  /** If set, the tab mounts this member surface by id (the common case). */
  surfaceId?: string;
  /**
   * If set (and no `surfaceId`), the tab projects the host's own slotted content — the host renders
   * `<div slot="<slot>">…</div>` as a light-DOM child of `<jf-surface-tabs>`. Use this (not `content`)
   * for the host's own body so the HOST's shadow CSS styles it (slotted content lives in the host's
   * shadow root). The slot is rendered only while its tab is active.
   */
  slot?: string;
  /** If set (and no `surfaceId`/`slot`), the tab renders this inline content (un-styled / test use). */
  content?: () => TemplateResult;
}

export class SurfaceTabs extends JfElement {
  static properties = {
    items: { attribute: false },
    activeId: { type: String, attribute: 'active-id' },
    apiBase: { type: String, attribute: 'api-base' },
    // NOTE: the attribute must NOT be `aria-*` — an `aria-`-prefixed attribute name that is not a real
    // ARIA attribute fails axe `aria-valid-attr`. The tablist's accessible name is applied as a real
    // `aria-label` in render(); this is just the host-supplied text for it.
    tablistLabel: { type: String, attribute: 'tablist-label' },
    // 578 host-api fix — the host threads its PluginHostApi here so members are mounted WITH host_,
    // exactly as the Shell mounts a standalone surface (Shell.renderOneSurface). Without it, a member
    // that calls this.host_.data.* (Browse, Health, Presentation editor) throws "reading 'data'".
    host_: { attribute: false },
  };

  declare items: SurfaceTabItem[];
  declare activeId: string | undefined;
  declare apiBase: string;
  declare tablistLabel: string;
  declare host_: PluginHostApi | undefined;

  /** Cached mounted member element + the tab id it belongs to, so re-renders don't re-mount. */
  private _activeEl: HTMLElement | null = null;
  private _activeElId: string | null = null;

  constructor() {
    super();
    this.items = [];
    this.activeId = undefined;
    this.apiBase = '';
    this.tablistLabel = 'Views';
    this.host_ = undefined;
  }

  static styles = css`
    :host {
      display: flex;
      flex-direction: column;
      min-height: 0;
      height: 100%;
    }
    .tab-bar { display: flex; flex-wrap: wrap; align-items: center; border-bottom: 1px solid var(--border-subtle); }
    .tab-actions { margin-inline-start: auto; padding: .25rem 1rem; min-width: 0; max-width: 100%; }
    #surface-tabs-panel { flex: 1; min-height: 0; overflow: hidden; }
    .tabs {
      flex-shrink: 0;
      display: flex;
      gap: 0.25rem;
      padding: 0 1.5rem;

    }
    .tabs button {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      background: transparent;
      border: none;
      border-bottom: 2px solid transparent;
      border-radius: 0;
      padding: 0.5rem 0.5rem;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
      cursor: pointer;
    }
    .tabs button:hover:not(:disabled) {
      color: var(--text-primary);
    }
    .tabs button.active {
      color: var(--text-primary);
      border-bottom-color: var(--accent-tint);
    }
    /* 578 §9.4.B — the TRUST member keeps a visible audit marker so its trust framing survives
       living beside DIAGNOSTIC members. Uses existing role tokens only (no new colour). */
    .alt-marker {
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      padding: 0 0.25rem;
    }
    .panel {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
    }
    .loading,
    .empty {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
  `;

  private get resolvedActiveId(): string | undefined {
    const first = this.items[0];
    if (first === undefined) return undefined;
    const has = this.items.some((t) => t.id === this.activeId);
    return has ? this.activeId : first.id;
  }

  private select(id: string): void {
    if (id === this.resolvedActiveId) return;
    this.activeId = id;
    this.dispatchEvent(
      new CustomEvent('tab-change', { detail: { id }, bubbles: true, composed: true }),
    );
  }

  /** Roving-tabindex arrow-key navigation across the tab strip (571 §11.2-D5). */
  private onKeydown(e: KeyboardEvent): void {
    const ids = this.items.map((t) => t.id);
    const active = this.resolvedActiveId;
    if (active === undefined) return;
    const i = ids.indexOf(active);
    let next: number | null = null;
    switch (e.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        next = (i + 1) % ids.length;
        break;
      case 'ArrowLeft':
      case 'ArrowUp':
        next = (i - 1 + ids.length) % ids.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = ids.length - 1;
        break;
      default:
        return;
    }
    const target = ids[next];
    if (target === undefined) return;
    e.preventDefault();
    this.select(target);
    void this.updateComplete.then(() => {
      const btn = this.shadowRoot?.querySelector<HTMLButtonElement>(
        `button[data-tab-id="${target}"]`,
      );
      btn?.focus();
    });
  }

  private renderPanel(item: SurfaceTabItem): TemplateResult {
    if (!item.surfaceId && item.slot) {
      // Host-provided body via a named slot — the host's shadow CSS styles the projected content.
      this._activeEl = null;
      this._activeElId = null;
      return html`<div class="panel"><slot name=${item.slot}></slot></div>`;
    }
    if (item.content && !item.surfaceId) {
      // Inline host content (un-styled / test use). No cached element to manage.
      this._activeEl = null;
      this._activeElId = null;
      return html`<div class="panel">${item.content()}</div>`;
    }
    const surfaceId = item.surfaceId;
    if (!surfaceId) return html`<div class="empty">No content for ${item.label}.</div>`;
    const surface = getSurface(surfaceId);
    if (!surface) return html`<div class="empty">Unknown surface: ${surfaceId}</div>`;
    const tag = surface.mountTag;
    // Lazy-load the member's module (registers the custom element), mirroring Shell.renderOneSurface.
    if (isLazySurface(tag) && !customElements.get(tag)) {
      void ensureSurfaceLoaded(tag);
      void customElements.whenDefined(tag).then(() => this.requestUpdate());
      return html`<div class="loading">Loading ${item.label}…</div>`;
    }
    // Reuse the mounted element across re-renders of the SAME tab; recreate on a tab switch (which
    // disconnects the previous member → its streams tear down: the §11.8 scoping, for free).
    if (this._activeElId !== item.id || this._activeEl === null) {
      this._activeEl = mountSurface(surface, { apiBase: this.apiBase, host_: this.host_ });
      this._activeElId = item.id;
    }
    return html`<div class="panel">${this._activeEl ?? html`<div class="empty">Cannot mount ${item.label}.</div>`}</div>`;
  }

  render(): TemplateResult {
    const active = this.resolvedActiveId;
    const activeItem = this.items.find((t) => t.id === active) ?? this.items[0];
    if (activeItem === undefined) return html`${nothing}`;
    return html`
      <div class="tab-bar"><div class="tabs" role="tablist" aria-label=${this.tablistLabel} @keydown=${this.onKeydown}>
        ${this.items.map((t) => {
          const isActive = t.id === active;
          const isTrust = t.altitude === 'TRUST';
          return html`
            <button
              role="tab"
              id="tab-${t.id}"
              data-tab-id=${t.id}
              data-altitude=${t.altitude ?? 'PRODUCT'}
              aria-selected=${isActive ? 'true' : 'false'}
              aria-controls="surface-tabs-panel"
              aria-label=${isTrust ? `${t.label} — audit` : t.label}
              tabindex=${isActive ? '0' : '-1'}
              class=${isActive ? 'active' : ''}
              @click=${() => this.select(t.id)}
            >
              ${t.label}
              ${isTrust ? html`<span class="alt-marker">Audit</span>` : nothing}
            </button>
          `;
        })}
      </div>
      <div class="tab-actions"><slot name="actions"></slot></div></div>
      <!-- ids are shadow-root-scoped, so tab↔panel association is unambiguous per instance. -->
      <div id="surface-tabs-panel" role="tabpanel" aria-labelledby="tab-${active}">
        ${this.renderPanel(activeItem)}
      </div>
    `;
  }
}

customElements.define('jf-surface-tabs', SurfaceTabs);
