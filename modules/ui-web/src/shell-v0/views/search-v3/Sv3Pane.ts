// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-pane — Search's details pane (tempdoc 822 Phase F8; design system §7).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * A REGION, not a reader. The frame — tabs, close, the two presentations — is the shared
 * `components/detailsPane/DetailsPane.ts`; everything a document needs to be read is the product's ONE
 * reading surface, `components/documentPane/DocumentPane.ts`. What is added here is what Search puts
 * in that frame: a tab per cited source, Context (what the assistant sees) and plugin tabs.
 *
 *  - **No second emphasis.** The reader's `HIGHLIGHT_DECAY_MS` is already the citation landing.
 *  - **`docPath` is `attribute: false`** — no markup, deeplink or attribute route reaches this
 *    element; the window's ONE writer of it is `SearchV3View.openPane`.
 *  - **Window-scoped, never document-fixed**: the sheet dims THIS WINDOW's box and nothing of the
 *    frame's chrome.
 *
 * The frame's events are stopped here and re-raised as this window's own, for the reason the citation
 * event is stopped in `Sv3Main`: this window answers its own regions.
 *
 * Side-effect registers <jf-sv3-pane>.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Shared } from './sv3-shared-styles.js';
// The product's ONE reading surface. Registered by ITS OWN side-effect import, never by relying on
// another window having been mounted first — a surface that leaned on a sibling window's
// registration would render an unknown element whenever it was reached first.
import '../../components/documentPane/DocumentPane.js';
import type { DocumentCitationAnchor, DocumentLineRange } from '../../components/documentPane/DocumentPane.js';
import '../../components/detailsPane/DetailsPane.js';
import type { DetailsPane, DetailsTab } from '../../components/detailsPane/DetailsPane.js';
import '../../components/detailsPane/DetailsPluginTab.js';
import { pluginContributionFor } from '../../components/detailsPane/DetailsPluginTab.js';
import { detailsDocumentActions } from '../../components/detailsPane/detailsDocumentActions.js';
import '../../components/ContextInspectorPane.js';
import type { InspectorView } from '../../components/ContextInspectorPane.js';
import '../../components/Button.js';
import type { CitationHeader } from '../../components/chat/evidenceProjection.js';
import { PANE_LABEL } from './fixtures.js';
import { SV3_PANE_CONTEXT_TAB } from './sv3-details.js';

/** Raised when the pane's own close control is used; the window decides what closing means. */
export const SV3_PANE_CLOSE = 'sv3-pane-close';
/** A tab was chosen; `detail.id` is its id. */
const SV3_PANE_TAB = 'sv3-pane-tab';
/** Hide or restore the source the pane is showing, in the assistant's context. */
const SV3_PANE_TOGGLE_HIDDEN = 'sv3-pane-toggle-hidden';

/** The hide/restore control's words for the source on show, or null when it offers none. */
export interface Sv3PaneSourceControl {
  readonly label: string;
  readonly hidden: boolean;
}

export class Sv3Pane extends JfElement {
  static styles = [
    sv3Shared,
    css`
      /* ── The INLINE presentation (>= 980px) ──────────────────────────────────
         A flex item of the window grid whose width the host sets from --pane-width. The neutral
         surface/text/edge tokens the shared frame and reader read are re-pointed at this window's
         palette here, so the frame does not enclose a panel wearing the shipped theme. The
         --accent-tint* family is deliberately NOT re-pointed — those tokens ARE the cited passage's
         land-strong-then-settle emphasis, whose authority is the reader's own. */
      :host {
        display: block;
        min-inline-size: 0;
        min-block-size: 0;
        overflow: hidden;
        background: var(--background);
        color: var(--foreground);
        font-family: var(--font-sans);
        --surface-1: var(--background);
        --surface-2: var(--muted);
        --surface-hover: var(--accent-surface);
        --text-primary: var(--foreground);
        --text-secondary: var(--muted-foreground);
        --text-tertiary: var(--secondary-label);
        --border-subtle: var(--border);
        --font-size-sm: var(--font-size-sv3-sm);
        --font-size-xs: var(--font-size-sv3-xs);
        --duration-fast: var(--duration-sv3-micro);
        --duration-slow: var(--duration-sv3-layout);
        --ease-standard: var(--ease-sv3-enter);
      }
      /* Below 980 px the frame positions itself against the WINDOW's box, so this element leaves the
         box tree. The presentation stays an attribute the window sets from its own measurement, not
         a @container query: container-type on the window host would make it the containing block
         for the fixed-position citation hover card (views/unifiedChatStyles.ts records the trap). */
      :host([overlay]) {
        display: contents;
      }
      .tab-body {
        flex: 1 1 auto;
        min-block-size: 0;
        display: flex;
        flex-direction: column;
      }
      .tab-body[hidden] {
        display: none;
      }
      jf-document-pane,
      jf-context-inspector-pane,
      jf-details-plugin-tab {
        flex: 1 1 auto;
        min-block-size: 0;
        overflow: hidden;
      }
      .footer {
        display: flex;
        flex-wrap: wrap;
        gap: var(--space-2);
      }
      .no-document {
        margin: 0;
        padding: var(--space-6);
        color: var(--muted-foreground);
        font-size: var(--font-size-sv3-sm);
      }
    `,
  ];

  static properties = {
    docPath: { attribute: false },
    sizeBytes: { attribute: false },
    citation: { attribute: false },
    highlightRange: { attribute: false },
    citationHeader: { attribute: false },
    apiBase: { type: String, attribute: 'api-base' },
    overlay: { type: Boolean, reflect: true },
    tabs: { attribute: false },
    activeTab: { attribute: false },
    contextView: { attribute: false },
    selectedItem: { attribute: false },
    sourceControl: { attribute: false },
  };

  /**
   * The document's path. `attribute: false` is the scope guard's structural half: the only way a path
   * reaches this pane is a property write from the window.
   */
  declare docPath: string | null;
  declare sizeBytes: number | undefined;
  /**
   * The cited passage, addressed by the CHARACTER offsets its producer computed (tempdoc 849 §3).
   * Null when the citation carried no span. The reader derives its own line coordinates from this;
   * nothing here converts, clamps or interprets it.
   */
  declare citation: DocumentCitationAnchor | null;
  declare highlightRange: DocumentLineRange | null;
  /** Tempdoc 849 §7 — the citation header, forwarded VERBATIM: this region derives no part of it. */
  declare citationHeader: CitationHeader | null;
  declare apiBase: string;
  /** The sheet presentation, decided by the window from its own box. */
  declare overlay: boolean;
  declare tabs: readonly DetailsTab[];
  /** The active tab's id; null is a closed pane. */
  declare activeTab: string | null;
  declare contextView: InspectorView | null;
  declare selectedItem: unknown;
  declare sourceControl: Sv3PaneSourceControl | null;

  constructor() {
    super();
    this.docPath = null;
    this.highlightRange = null;
    this.citation = null;
    this.citationHeader = null;
    this.apiBase = '';
    this.overlay = false;
    this.tabs = [];
    this.activeTab = null;
    this.contextView = null;
    this.selectedItem = null;
    this.sourceControl = null;
  }

  /** Put the keyboard in the pane. */
  focusPane(): void {
    this.renderRoot.querySelector<DetailsPane>('jf-details-pane')?.focusActiveTab();
  }

  private raise<T>(name: string, detail?: T): void {
    this.dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
  }

  private readonly onClose = (event: Event): void => {
    event.stopPropagation();
    this.raise(SV3_PANE_CLOSE);
  };

  private readonly onTab = (event: Event): void => {
    event.stopPropagation();
    this.raise(SV3_PANE_TAB, (event as CustomEvent<{ id: string }>).detail);
  };

  private footer(): TemplateResult | typeof nothing {
    const control = this.sourceControl;
    if (this.docPath === null || this.activeTab === SV3_PANE_CONTEXT_TAB
      || pluginContributionFor(this.activeTab ?? '') !== null) return nothing;
    return html`<div slot="actions" class="footer">
      ${detailsDocumentActions(this.docPath)}
      ${control === null ? nothing : html`<jf-button variant="ghost" size="sm" label=${control.label}
        data-testid="sv3-pane-toggle-hidden" .onActivate=${() => this.raise(SV3_PANE_TOGGLE_HIDDEN)}
        >${control.hidden ? 'Restore' : 'Hide'}</jf-button>`}
    </div>`;
  }

  render(): TemplateResult | typeof nothing {
    const tab = this.activeTab;
    if (tab === null) return nothing;
    const plugin = pluginContributionFor(tab);
    const reading = tab !== SV3_PANE_CONTEXT_TAB && plugin === null;
    return html`
      <jf-details-pane
        ?overlay=${this.overlay}
        region-label=${PANE_LABEL.region}
        close-label=${PANE_LABEL.dismiss}
        .tabs=${this.tabs}
        .activeTab=${tab}
        @details-close=${this.onClose}
        @details-tab-select=${this.onTab}
      >
        ${this.docPath === null
          ? reading ? html`<p class="no-document">Choose a citation in the answer to read it here.</p>` : nothing
          : html`<div class="tab-body" ?hidden=${!reading}>
              <jf-document-pane
                data-testid="sv3-pane-document"
                .docPath=${this.docPath}
                .sizeBytes=${this.sizeBytes}
                .highlightRange=${this.highlightRange}
                .citation=${this.citation}
                .citationHeader=${this.citationHeader}
                api-base=${this.apiBase}
              ></jf-document-pane>
            </div>`}
        ${tab === SV3_PANE_CONTEXT_TAB
          ? html`<jf-context-inspector-pane data-testid="sv3-pane-context" .view=${this.contextView}></jf-context-inspector-pane>`
          : nothing}
        ${plugin === null
          ? nothing
          : html`<jf-details-plugin-tab data-testid="sv3-pane-plugin" .contribution=${plugin} .selectedItem=${this.selectedItem}></jf-details-plugin-tab>`}
        ${this.footer()}
      </jf-details-pane>
    `;
  }
}

customElements.define('jf-sv3-pane', Sv3Pane);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-pane': Sv3Pane;
  }
}
