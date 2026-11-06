// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-window-details> — the details pane of a window that has no details of its own to compose, so a
 * document selected there (`host.ui.showInspector`, a citation) shows beside the work rather than
 * pulling the reader to Search. Search composes `jf-details-pane` itself: its width is argued against
 * its sidebar, and its tabs come from the answer.
 *
 * One instance per window. It answers only selections the frame placed in its own window, and keeps
 * its copy when another window selects something, so a split layout's two windows never show each
 * other's document (C4). The inline/sheet choice is asked of this window's own box.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { deepActiveElement } from '../../utils/keyboardHandler.js';
import {
  setOpen as setInspectorOpen,
  getInspectorState,
  subscribeInspector,
  type InspectorState,
  type SelectedItem,
} from '../../state/inspectorState.js';
import { filenameOf } from '../chat/evidenceProjection.js';
import { onInspectorTabChange } from '../../commands/InspectorTabRegistry.js';
import '../documentPane/DocumentPane.js';
import './DetailsPane.js';
import type { DetailsPane, DetailsTab } from './DetailsPane.js';
import './DetailsPluginTab.js';
import { pluginContributionFor, pluginDetailsTabs } from './DetailsPluginTab.js';
import { detailsDocumentActions } from './detailsDocumentActions.js';
import {
  DETAILS_PANE_DEFAULT_PX,
  DETAILS_PANE_KEY_STEP_PX,
  clampDetailsPaneWidth,
  detailsPaneIsInline,
  forgetDetailsPaneWidth,
  readDetailsPaneWidth,
  storeDetailsPaneWidth,
} from './detailsPaneSizing.js';

const DOCUMENT_TAB = 'document';

export class WindowDetails extends JfElement {
  static properties = {
    windowId: { attribute: false },
    apiBase: { type: String, attribute: 'api-base' },
    selected: { state: true },
    tab: { state: true },
    overlay: { type: Boolean, reflect: true },
    widthPx: { state: true },
  };

  /** The canonical surface id of the window this pane belongs to. */
  declare windowId: string;
  declare apiBase: string;
  declare private selected: SelectedItem | null;
  declare private tab: string;
  declare overlay: boolean;
  declare private widthPx: number;

  private opener: HTMLElement | null = null;
  private unsubs: Array<() => void> = [];
  private observer: ResizeObserver | null = null;
  /** The stage, or a split's pane: what this window and its details share. */
  private box: HTMLElement | null = null;

  constructor() {
    super();
    this.windowId = '';
    this.apiBase = '';
    this.selected = null;
    this.tab = DOCUMENT_TAB;
    this.overlay = false;
    this.widthPx = clampDetailsPaneWidth(readDetailsPaneWidth() ?? DETAILS_PANE_DEFAULT_PX);
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.box = (this.parentElement ?? (this.getRootNode() as ShadowRoot).host) as HTMLElement;
    this.unsubs = [
      subscribeInspector(state => this.onInspector(state)),
      onInspectorTabChange(() => this.onPluginTabsChange()),
    ];
    if (typeof ResizeObserver !== 'undefined') {
      this.observer = new ResizeObserver(() => this.measure());
      this.observer.observe(this.box);
    }
    this.measure();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubs.forEach(unsubscribe => unsubscribe());
    this.unsubs = [];
    this.observer?.disconnect();
    this.observer = null;
    this.previousElementSibling?.removeAttribute('inert');
    this.box?.style.removeProperty('padding-inline-end');
  }

  /** The element is reused when the window changes: another window's document must not come along. */
  override willUpdate(changed: Map<string, unknown>): void {
    if (changed.has('windowId') && changed.get('windowId') !== undefined) {
      this.selected = null;
      this.opener = null;
      this.onInspector(getInspectorState());
    }
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('selected') || changed.has('overlay')) {
      const opened = this.selected !== null && (changed.get('selected') == null || changed.has('overlay'));
      if (opened && this.overlay) void this.focusPane();
    }
    this.style.setProperty('--details-width', `${this.widthPx}px`);
    // The window takes what an inline pane leaves; a sheet leaves it everything.
    if (this.selected !== null && !this.overlay) this.box?.style.setProperty('padding-inline-end', `${this.widthPx}px`);
    else this.box?.style.removeProperty('padding-inline-end');
    // A sheet is the only thing the keyboard should reach in its window.
    this.previousElementSibling?.toggleAttribute('inert', this.selected !== null && this.overlay);
  }

  private measure(): void {
    const width = this.box?.getBoundingClientRect().width ?? 0;
    this.overlay = !detailsPaneIsInline(width > 0 ? width : Number.POSITIVE_INFINITY);
  }

  private onInspector(state: InspectorState): void {
    if (state.selected === null) { this.clear(); return; }
    if (state.owner !== this.windowId) return;
    if (state.isOpen && state.selected.path) {
      if (this.selected === null) {
        const active = deepActiveElement() as HTMLElement | null;
        this.opener = active;
      }
      this.selected = state.selected;
      this.tab = DOCUMENT_TAB;
    } else this.clear();
  }

  private clear(): void {
    this.selected = null;
    this.opener = null;
  }

  private onPluginTabsChange(): void {
    if (pluginContributionFor(this.tab) === null) this.tab = DOCUMENT_TAB;
    this.requestUpdate();
  }

  private async focusPane(): Promise<void> {
    await this.updateComplete;
    const pane = this.renderRoot.querySelector<DetailsPane>('jf-details-pane');
    await pane?.updateComplete;
    pane?.focusActiveTab();
  }

  private close(): void {
    const opener = this.opener;
    this.clear();
    if (getInspectorState().owner === this.windowId) setInspectorOpen(false);
    void this.updateComplete.then(() => { if (opener?.isConnected) opener.focus(); });
  }

  /** Close from outside the pane (Escape with the keyboard on the page). False when none is open. */
  closeDetails(): boolean {
    if (this.selected === null) return false;
    this.close();
    return true;
  }

  private readonly onKeydown = (event: KeyboardEvent): void => {
    if (event.key !== 'Escape' || event.isComposing) return;
    event.stopPropagation();
    this.close();
  };

  private setWidth(px: number): void {
    this.widthPx = clampDetailsPaneWidth(px);
    storeDetailsPaneWidth(this.widthPx);
  }

  private readonly onGripPointerDown = (event: PointerEvent): void => {
    if (event.button !== 0) return;
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = this.widthPx;
    const move = (moved: PointerEvent): void => { this.widthPx = clampDetailsPaneWidth(startWidth - (moved.clientX - startX)); };
    const end = (): void => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', end);
      window.removeEventListener('pointercancel', end);
      storeDetailsPaneWidth(this.widthPx);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', end);
    window.addEventListener('pointercancel', end);
  };

  private readonly onGripKeydown = (event: KeyboardEvent): void => {
    if (event.key === 'Home') {
      event.preventDefault();
      forgetDetailsPaneWidth();
      this.widthPx = DETAILS_PANE_DEFAULT_PX;
    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault();
      // Right-anchored: LEFT grows the pane, the direction the pointer drags it.
      this.setWidth(this.widthPx + (event.key === 'ArrowLeft' ? DETAILS_PANE_KEY_STEP_PX : -DETAILS_PANE_KEY_STEP_PX));
    }
  };

  private tabs(selected: SelectedItem): DetailsTab[] {
    return [{ id: DOCUMENT_TAB, label: selected.title || filenameOf(selected.path), title: selected.path }, ...pluginDetailsTabs()];
  }

  override render(): TemplateResult | typeof nothing {
    const selected = this.selected;
    if (selected === null) return nothing;
    const plugin = pluginContributionFor(this.tab);
    const highlight = typeof selected.highlightStartLine === 'number' && typeof selected.highlightEndLine === 'number'
      ? { startLine: selected.highlightStartLine, endLine: selected.highlightEndLine } : null;
    return html`
      ${this.overlay ? nothing : html`<button type="button" class="grip" data-testid="details-pane-grip"
        aria-label="Resize details — arrow keys resize, Home returns to automatic"
        title="Resize details — arrow keys resize, Home returns to automatic"
        @pointerdown=${this.onGripPointerDown} @keydown=${this.onGripKeydown}></button>`}
      <jf-details-pane ?overlay=${this.overlay} region-label="Details" close-label="Close details"
        .tabs=${this.tabs(selected)} .activeTab=${this.tab}
        @keydown=${this.onKeydown}
        @details-close=${() => this.close()}
        @details-tab-select=${(event: CustomEvent<{ id: string }>) => { this.tab = event.detail.id; }}>
        <div class="tab-body" ?hidden=${plugin !== null}>
          <jf-document-pane data-testid="window-details-document" .docPath=${selected.path} .highlightRange=${highlight}
            api-base=${this.apiBase}></jf-document-pane>
        </div>
        ${plugin === null ? nothing : html`<jf-details-plugin-tab .contribution=${plugin} .selectedItem=${selected}></jf-details-plugin-tab>`}
        ${plugin === null ? html`<div slot="actions" class="footer">${detailsDocumentActions(selected.path)}</div>` : nothing}
      </jf-details-pane>
    `;
  }

  static styles = css`
    :host {
      display: block;
      position: absolute;
      inset-block: 0;
      inset-inline-end: 0;
    }
    :host(:not([overlay])) jf-details-pane { inline-size: var(--details-width); }
    /* Below 980 px the frame positions itself against the window's box. */
    :host([overlay]) { position: static; }
    .grip {
      position: absolute;
      inset-block: 0;
      inset-inline-start: calc(-1 * var(--jf-space-2));
      z-index: var(--z-base);
      inline-size: var(--jf-space-4);
      padding: 0;
      border: 0;
      background: transparent;
      cursor: col-resize;
    }
    .grip:focus-visible { outline: var(--frame-focus-width) solid var(--focus-ring-color); }
    .tab-body { flex: 1 1 auto; min-block-size: 0; display: flex; flex-direction: column; }
    .tab-body[hidden] { display: none; }
    jf-document-pane, jf-details-plugin-tab { flex: 1 1 auto; min-block-size: 0; overflow: hidden; }
    .footer { display: flex; flex-wrap: wrap; gap: var(--jf-space-2); }
  `;
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-window-details')) {
  customElements.define('jf-window-details', WindowDetails);
}

declare global {
  interface HTMLElementTagNameMap {
    'jf-window-details': WindowDetails;
  }
}
