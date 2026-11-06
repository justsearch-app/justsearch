// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-details-pane> — the one details pane a window may have (design system §2 "Right edge of a
 * window", §7 "Details pane"). A frame, not a reader: tabs, a close control, the footer actions and
 * the two presentations. What a tab shows is slotted by the window, which owns the selection; a
 * window's pane never reads another window's state, so split layouts cannot cross over (C4).
 *
 * The window decides the presentation from its own box and sets `overlay`. It is an attribute rather
 * than a container query because a container on the window host would become the containing block
 * for the fixed-position citation hover card (see `SearchV3View`'s record of that trap).
 *
 * Events stay inside the window: neither is composed.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { repeat } from 'lit/directives/repeat.js';
import { JfElement } from '../../primitives/JfElement.js';
import { icon } from '../Icon.js';
import '../Button.js';

/** A tab was chosen; `detail.id` is the tab's id. */
export const DETAILS_TAB_SELECT = 'details-tab-select';
/** The close control or the sheet's backdrop was used; the window decides what closing means. */
export const DETAILS_CLOSE = 'details-close';

export interface DetailsTab {
  readonly id: string;
  readonly label: string;
  /** The source number the answer uses for this tab, when it has one. */
  readonly number?: string;
  /** The untruncated name, for the tooltip. */
  readonly title?: string;
}

export class DetailsPane extends JfElement {
  static properties = {
    tabs: { attribute: false },
    activeTab: { attribute: false },
    overlay: { type: Boolean, reflect: true },
    regionLabel: { type: String, attribute: 'region-label' },
    closeLabel: { type: String, attribute: 'close-label' },
    hasActions: { state: true },
  };

  declare tabs: readonly DetailsTab[];
  declare activeTab: string;
  declare overlay: boolean;
  declare regionLabel: string;
  declare closeLabel: string;
  declare private hasActions: boolean;

  private shownStrip = '';
  private stripObserver: ResizeObserver | null = null;

  constructor() {
    super();
    this.tabs = [];
    this.activeTab = '';
    this.overlay = false;
    this.regionLabel = 'Details';
    this.closeLabel = 'Close details';
    this.hasActions = false;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    // F6 finds the details region by this marker (`chrome/frameRegions.ts`).
    this.setAttribute('data-details-pane', '');
    if (typeof ResizeObserver === 'undefined') return;
    // A narrower pane must not leave the selected tab behind the strip's edge.
    this.stripObserver = new ResizeObserver(() => this.revealActiveTab());
    void this.updateComplete.then(() => {
      const strip = this.renderRoot.querySelector('.tabs');
      if (strip !== null) this.stripObserver?.observe(strip);
    });
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.stripObserver?.disconnect();
    this.stripObserver = null;
  }

  override updated(): void {
    // The window rebuilds the list on every render; only a real change may move the reader's scroll.
    const strip = `${this.activeTab}\n${this.tabs.map((tab) => tab.id).join('\n')}`;
    if (strip !== this.shownStrip) {
      this.shownStrip = strip;
      this.revealActiveTab();
    }
  }

  private revealActiveTab(): void {
    this.renderRoot.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]')?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
  }

  /** Put the keyboard on the active tab; the window calls this when the reader opened the pane. */
  focusActiveTab(): void {
    const tab = this.renderRoot.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]')
      ?? this.renderRoot.querySelector<HTMLElement>('[role="tab"]');
    tab?.focus();
  }

  private select(id: string): void {
    this.dispatchEvent(new CustomEvent<{ id: string }>(DETAILS_TAB_SELECT, { detail: { id } }));
  }

  private readonly close = (): void => {
    this.dispatchEvent(new CustomEvent(DETAILS_CLOSE));
  };

  private readonly onTabKeydown = (event: KeyboardEvent): void => {
    const count = this.tabs.length;
    const at = this.tabs.findIndex((tab) => tab.id === this.activeTab);
    const next = event.key === 'ArrowRight' ? (at + 1) % count
      : event.key === 'ArrowLeft' ? (at - 1 + count) % count
        : event.key === 'Home' ? 0
          : event.key === 'End' ? count - 1 : -1;
    if (next < 0 || count === 0) return;
    event.preventDefault();
    this.select(this.tabs[next]!.id);
    void this.updateComplete.then(() => this.focusActiveTab());
  };

  private readonly onActionsSlot = (event: Event): void => {
    this.hasActions = (event.target as HTMLSlotElement).assignedElements().length > 0;
  };

  override render(): TemplateResult {
    return html`
      ${this.overlay
        ? html`<button type="button" class="backdrop" data-testid="details-pane-backdrop"
            aria-label=${this.closeLabel} tabindex="-1" @click=${this.close}></button>`
        : nothing}
      <aside class="surface" data-testid="details-pane" aria-label=${this.regionLabel}>
        <div class="bar">
          <div class="tabs" role="tablist" aria-label=${this.regionLabel} @keydown=${this.onTabKeydown}>
            ${repeat(this.tabs, (tab) => tab.id, (tab) => {
              const active = tab.id === this.activeTab;
              return html`<button type="button" role="tab" class="tab" data-tab=${tab.id}
                id=${`details-tab-${tab.id}`}
                aria-selected=${active ? 'true' : 'false'}
                aria-controls="details-panel"
                tabindex=${active ? 0 : -1}
                title=${tab.title ?? tab.label}
                @click=${() => this.select(tab.id)}>
                ${tab.number !== undefined ? html`<span class="number">${tab.number}</span>` : nothing}
                <span class="label">${tab.label}</span>
              </button>`;
            })}
          </div>
          <jf-button class="close" variant="ghost" size="icon" label=${this.closeLabel}
            data-testid="details-pane-close" .onActivate=${this.close}>${icon({ name: 'x', size: 16 })}</jf-button>
        </div>
        <div class="panel" id="details-panel" role="tabpanel" aria-labelledby=${`details-tab-${this.activeTab}`}>
          <slot></slot>
        </div>
        <div class="actions" ?hidden=${!this.hasActions}><slot name="actions" @slotchange=${this.onActionsSlot}></slot></div>
      </aside>
    `;
  }

  static styles = css`
    :host {
      display: block;
      block-size: 100%;
      min-inline-size: 0;
      min-block-size: 0;
      color: var(--text-primary);
    }
    .surface {
      display: flex;
      flex-direction: column;
      block-size: 100%;
      min-block-size: 0;
      overflow: hidden;
      box-sizing: border-box;
      border-inline-start: var(--frame-hairline) solid var(--border-subtle);
      background: var(--surface-1);
    }
    .bar {
      flex: none;
      display: flex;
      align-items: stretch;
      gap: var(--jf-space-1);
      padding-inline: var(--jf-space-2);
      border-block-end: var(--frame-hairline) solid var(--border-subtle);
    }
    .tabs {
      flex: 1 1 auto;
      min-inline-size: 0;
      display: flex;
      gap: var(--jf-space-1);
      overflow-x: auto;
      scrollbar-width: thin;
    }
    /* Tabs keep their size and the strip scrolls: a label is never squeezed to fit its neighbours. */
    .tab {
      flex: none;
      max-inline-size: var(--frame-details-tab-max);
      display: inline-flex;
      align-items: center;
      gap: var(--jf-space-2);
      padding: var(--jf-space-3) var(--jf-space-2);
      border: 0;
      border-block-end: var(--frame-focus-width) solid transparent;
      background: transparent;
      color: var(--text-secondary);
      font: inherit;
      font-size: var(--font-size-sm);
      white-space: nowrap;
      cursor: pointer;
    }
    .tab:hover { color: var(--text-primary); }
    .tab[aria-selected='true'] {
      color: var(--text-primary);
      border-block-end-color: var(--accent-tint);
    }
    .tab:focus-visible {
      outline: var(--frame-focus-width) solid var(--focus-ring-color);
      outline-offset: calc(-1 * var(--frame-focus-width));
    }
    .label { overflow: hidden; text-overflow: ellipsis; }
    .number {
      flex: none;
      min-inline-size: 1.25em;
      padding-inline: var(--jf-space-1);
      box-sizing: border-box;
      border-radius: var(--frame-control-radius);
      background: var(--accent-tint-16);
      color: var(--text-tint);
      font-size: var(--font-size-xs);
      font-weight: 600;
      font-variant-numeric: tabular-nums;
      text-align: center;
    }
    .tab[aria-selected='true'] .number { background: var(--accent-tint); color: var(--surface-1); }
    .close { flex: none; align-self: center; }
    .panel {
      flex: 1 1 auto;
      min-block-size: 0;
      overflow: hidden;
      display: flex;
      flex-direction: column;
    }
    .actions {
      flex: none;
      display: flex;
      flex-wrap: wrap;
      gap: var(--jf-space-2);
      padding: var(--jf-space-2) var(--jf-space-4);
      border-block-start: var(--frame-hairline) solid var(--border-subtle);
    }
    .actions[hidden] { display: none; }
    .backdrop { display: none; }

    /* Below the window's 980 px: a sheet over the window, dimming its box and nothing of the frame. */
    :host([overlay]) {
      position: absolute;
      inset: 0;
      z-index: var(--z-details-sheet);
    }
    :host([overlay]) .backdrop {
      display: block;
      position: absolute;
      inset: 0;
      padding: 0;
      border: 0;
      background: var(--frame-dialog-dim);
      cursor: default;
    }
    :host([overlay]) .surface {
      position: absolute;
      inset-block: 0;
      inset-inline-end: 0;
      block-size: auto;
      inline-size: min(100%, clamp(var(--frame-details-min), var(--frame-details-sheet-share), var(--frame-details-max)));
      box-shadow: var(--shadow-float);
    }
    @media (forced-colors: active) {
      .tab[aria-selected='true'] { border-block-end-color: Highlight; color: Highlight; }
      .number { border: var(--frame-hairline) solid CanvasText; }
      .tab[aria-selected='true'] .number { background: Highlight; color: HighlightText; }
      :host([overlay]) .surface { border-inline-start-color: CanvasText; }
    }
  `;
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-details-pane')) {
  customElements.define('jf-details-pane', DetailsPane);
}

declare global {
  interface HTMLElementTagNameMap {
    'jf-details-pane': DetailsPane;
  }
}
