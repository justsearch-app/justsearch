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
  /**
   * The source number the answer uses for this tab, when it has one. A numbered tab shows only the
   * number; its name is the tooltip, the accessible name and, while selected, the pane's header.
   */
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
  /** The keyboard was on the strip when the selection moved, so it moves with it. */
  private keyboardFollows = false;

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
    this.stripObserver = new ResizeObserver(() => {
      this.revealActiveTab();
      this.syncStripEdges();
    });
    void this.updateComplete.then(() => this.observeStrip());
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.stripObserver?.disconnect();
    this.stripObserver = null;
  }

  override willUpdate(changed: Map<string, unknown>): void {
    // Asked before the render: the tab the selection leaves may not survive it.
    if (changed.has('activeTab')) this.keyboardFollows = this.shadowRoot?.activeElement?.getAttribute('role') === 'tab';
  }

  override updated(): void {
    if (this.keyboardFollows) {
      this.keyboardFollows = false;
      this.focusActiveTab();
    }
    // The window rebuilds the list on every render; only a real change may move the reader's scroll.
    const strip = `${this.activeTab}\n${this.tabs.map((tab) => tab.id).join('\n')}`;
    if (strip !== this.shownStrip) {
      this.shownStrip = strip;
      this.observeStrip();
      this.revealActiveTab();
      this.syncStripEdges();
    }
  }

  /** The tabs as well as the strip: a font arriving changes what fits without resizing the strip. */
  private observeStrip(): void {
    this.stripObserver?.disconnect();
    this.renderRoot.querySelectorAll('.tabs, [role="tab"]').forEach((element) => this.stripObserver?.observe(element));
  }

  private revealActiveTab(): void {
    this.renderRoot.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]')?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
  }

  /** Mark the ends the strip can still scroll towards, for the edge affordances. */
  private readonly syncStripEdges = (): void => {
    const strip = this.renderRoot.querySelector<HTMLElement>('.tabs');
    if (strip === null) return;
    strip.parentElement?.toggleAttribute('data-more-before', strip.scrollLeft > 1);
    strip.parentElement?.toggleAttribute('data-more-after', strip.scrollLeft + strip.clientWidth < strip.scrollWidth - 1);
  };

  /** A wheel turned over a full strip moves the strip; `passive: false` so the page stays where it is. */
  private readonly onStripWheel = {
    passive: false,
    handleEvent: (event: WheelEvent): void => {
      const strip = event.currentTarget as HTMLElement;
      if (Math.abs(event.deltaX) >= Math.abs(event.deltaY)) return;
      const from = strip.scrollLeft;
      strip.scrollLeft += event.deltaY;
      if (strip.scrollLeft !== from) event.preventDefault();
    },
  };

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
  };

  private readonly onActionsSlot = (event: Event): void => {
    this.hasActions = (event.target as HTMLSlotElement).assignedElements().length > 0;
  };

  override render(): TemplateResult {
    const shown = this.tabs.find((tab) => tab.id === this.activeTab);
    return html`
      ${this.overlay
        ? html`<button type="button" class="backdrop" data-testid="details-pane-backdrop"
            aria-label=${this.closeLabel} tabindex="-1" @click=${this.close}></button>`
        : nothing}
      <aside class="surface" data-testid="details-pane" aria-label=${this.regionLabel}>
        <div class="bar">
          <div class="strip">
            <div class="tabs" role="tablist" aria-label=${this.regionLabel} @keydown=${this.onTabKeydown}
              @wheel=${this.onStripWheel} @scroll=${this.syncStripEdges}>
              ${repeat(this.tabs, (tab) => tab.id, (tab) => {
                const active = tab.id === this.activeTab;
                const name = tab.title ?? tab.label;
                return html`<button type="button" role="tab" class=${tab.number === undefined ? 'tab' : 'tab numbered'}
                  data-tab=${tab.id}
                  id=${`details-tab-${tab.id}`}
                  aria-label=${tab.number === undefined ? nothing : `${tab.number} ${name}`}
                  aria-selected=${active ? 'true' : 'false'}
                  aria-controls="details-panel"
                  tabindex=${active ? 0 : -1}
                  title=${name}
                  @click=${() => this.select(tab.id)}>
                  ${tab.number === undefined
                    ? html`${tab.id === 'document' ? icon({ name: 'file-text', size: 16 }) : nothing}<span class="label">${tab.label}</span>`
                    : html`<span class="number">${tab.number}</span>`}
                </button>`;
              })}
            </div>
            <span class="edge before" data-testid="details-tabs-more-before" aria-hidden="true">${icon({ name: 'chevron-left', size: 14 })}</span>
            <span class="edge after" data-testid="details-tabs-more-after" aria-hidden="true">${icon({ name: 'chevron-right', size: 14 })}</span>
          </div>
          <jf-button class="close" variant="ghost" size="icon" label=${this.closeLabel}
            data-testid="details-pane-close" .onActivate=${this.close}>${icon({ name: 'x', size: 16 })}</jf-button>
        </div>
        ${shown?.number === undefined
          ? nothing
          : html`<p class="source-name" data-testid="details-source-name" title=${shown.title ?? shown.label}>${shown.label}</p>`}
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
    .strip {
      position: relative;
      flex: 1 1 auto;
      min-inline-size: 0;
      display: flex;
    }
    /* The tabs' own padding spaces them. The edge affordances say the strip scrolls, so it needs no
       bar of its own, and a tab revealed at an end clears them. */
    .tabs {
      flex: 1 1 auto;
      min-inline-size: 0;
      display: flex;
      overflow-x: auto;
      scrollbar-width: none;
      scroll-padding-inline: var(--jf-space-6);
    }
    .edge {
      display: none;
      position: absolute;
      inset-block: 0;
      inline-size: var(--jf-space-6);
      align-items: center;
      color: var(--text-primary);
      pointer-events: none;
    }
    .edge.before {
      inset-inline-start: 0;
      justify-content: flex-start;
      background: linear-gradient(to right, var(--surface-1) 60%, transparent);
    }
    .edge.after {
      inset-inline-end: 0;
      justify-content: flex-end;
      background: linear-gradient(to left, var(--surface-1) 60%, transparent);
    }
    .strip[data-more-before] .edge.before,
    .strip[data-more-after] .edge.after { display: flex; }
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
      font-weight: 500;
      white-space: nowrap;
      cursor: pointer;
    }
    .tab[data-tab='document'] { max-inline-size: 100%; }
    .tab.numbered { padding-inline: var(--jf-space-1); }
    .tab:hover { color: var(--text-primary); }
    .tab[aria-selected='true'] {
      color: var(--text-primary);
      border-block-end-color: var(--text-secondary);
    }
    .tab:focus-visible {
      outline: var(--frame-focus-width) solid var(--focus-ring-color);
      outline-offset: calc(-1 * var(--frame-focus-width));
    }
    .label { overflow: hidden; text-overflow: ellipsis; }
    .number {
      flex: none;
      min-inline-size: var(--jf-space-4);
      padding-inline: var(--jf-space-1);
      box-sizing: border-box;
      border-radius: var(--frame-control-radius);
      background: var(--surface-active);
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      font-weight: 500;
      font-variant-numeric: tabular-nums;
      text-align: center;
    }
    .tab[aria-selected='true'] .number { background: var(--surface-active); color: var(--text-primary); }
    .close { flex: none; align-self: center; }
    .source-name {
      flex: none;
      margin: 0;
      padding: var(--jf-space-2) var(--jf-space-4);
      border-block-end: var(--frame-hairline) solid var(--border-subtle);
      font-size: var(--font-size-sm);
      font-weight: 500;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
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
      inset: var(--details-overlay-top, 0px) 0 0;
      block-size: auto;
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
      .edge.before, .edge.after { background: Canvas; color: CanvasText; }
      /* The browser's text backplate would hide HighlightText on Highlight, so this pair opts out. */
      .tab[aria-selected='true'] .number { forced-color-adjust: none; background: Highlight; color: HighlightText; }
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
