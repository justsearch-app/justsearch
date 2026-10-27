// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-pane — the Search v3 window's citation-inspection pane (tempdoc 822 Phase F8).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * A REGION, not a reader. Everything a document needs to be read — the fetch, the rendered/source
 * modes, the provenance header, the close control, and the land-strong-then-settle decay on the cited
 * passage — belongs to the product's ONE reading surface, `components/documentPane/DocumentPane.ts`,
 * which this element mounts by side-effect import (`:112-168`). What is added here is only what the
 * design spec's right panel adds around its own reader: the window-anchored frame, the two
 * presentations the spec's 980px switch chooses between, and the boundary the reader can move.
 *
 *  - **No second emphasis.** The pane's `HIGHLIGHT_DECAY_MS` (`DocumentPane.ts:95,145,226-244`) is
 *    already the citation landing — re-render-safe and reduced-motion-aware. A highlight of this
 *    window's own would be a fork of an authority that exists.
 *  - **Cited documents only** (F8 scope guard, standing owner directive: search integration is
 *    deferred indefinitely). `docPath` is `attribute: false` — there is no markup, deeplink or
 *    attribute route into this element, and the window's ONE writer of it is the citation handler in
 *    `SearchV3View`. Opening an arbitrary search result, a browse row or a path the reader types is
 *    the DEFERRED boundary, and it stays outside this element: nothing here takes a path from
 *    anywhere but a citation, and the chrome carries no search affordance.
 *  - **Window-scoped, never document-fixed** (the palette's containment precedent): in the narrow
 *    presentation the backdrop dims THIS WINDOW's box and nothing of the shipped shell's chrome.
 *
 * The shared `pane-close` is stopped here and re-raised as this window's own `sv3-pane-close`, for
 * the same reason the citation event is stopped in `Sv3Main`: a `composed` event from a shared
 * component travels the whole way to the Shell, and this window answers its own regions.
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
import type { CitationHeader } from '../../components/chat/evidenceProjection.js';
import { PANE_LABEL } from './fixtures.js';

/** Raised when the pane's own close control is used; the window decides what closing means. */
export const SV3_PANE_CLOSE = 'sv3-pane-close';

export class Sv3Pane extends JfElement {
  static styles = [
    sv3Shared,
    css`
      /* ── The INLINE presentation (>= 980px) ──────────────────────────────────
         A flex item of the window grid whose width the host sets from --pane-width; the pane draws
         only its own edge. Right-anchored by DOM order (last child), so border-inline-start is the
         boundary between it and the main column — the spec's border-l border-border on
         bg-background, on our tokens. */
      :host {
        display: block;
        min-inline-size: 0;
        min-block-size: 0;
        overflow: hidden;
        background: var(--background);
        color: var(--foreground);
        font-family: var(--font-sans);
      }
      .surface {
        display: flex;
        flex-direction: column;
        block-size: 100%;
        min-block-size: 0;
        overflow: hidden;
        border-inline-start: 1px solid var(--border);
        background: var(--background);
      }
      /* The CLOTHES, and only the clothes (Sv3Main's precedent for the shared markdown block and
         citations panel): the shared reader's neutral surface/text/edge tokens are re-pointed at this
         window's palette so the frame does not enclose a panel wearing the shipped theme. The
         --accent-tint* family is deliberately NOT re-pointed — those tokens ARE the cited passage's
         land-strong-then-settle emphasis, whose authority is the pane's own. */
      jf-document-pane {
        flex: 1 1 auto;
        min-block-size: 0;
        overflow: hidden;
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
      /* Not rendered at all in the inline presentation: a dim over the window is the narrow
         presentation's own device, and an inert copy of it would still be a node in the flow. */
      .backdrop {
        display: none;
      }

      /* ── The NARROW presentation (< 980px → Sheet) ───────────────────────────
         WINDOW-SCOPED, not document-fixed — the palette's containment precedent: absolutely
         positioned against the window host's box, so the dim reaches the window region and nothing of
         the shipped shell's rail, topbar or status bar.

         Driven by an ATTRIBUTE the window sets from its own measurement, NOT by a @container query,
         and the reason is recorded rather than worked around: a container query needs
         container-type on an ancestor, the only ancestor here is the window host, and
         container-type implies layout containment — which makes the host a containing block for
         FIXED-position descendants. F7's <jf-citation-hover-card> is exactly that (it is positioned
         from viewport coordinates, components/chat/CitationHoverCard.ts:45), so declaring the
         container would silently re-anchor and mis-place every citation preview in the window. This
         is the trap views/unifiedChatStyles.ts:207-212 already recorded for the same reason. The
         breakpoint therefore lives ONCE, in sv3-boundaries.ts, where the sidebar's clamp reads the
         same number — which a duplicated CSS literal could not have guaranteed anyway. */
      :host([overlay]) {
        position: absolute;
        inset: 0;
        z-index: var(--z-overlay);
        overflow: visible;
        background: transparent;
      }
      :host([overlay]) .backdrop {
        display: block;
        position: absolute;
        inset: 0;
        border: 0;
        padding: 0;
        background: var(--dialog-backdrop);
        cursor: default;
      }
      :host([overlay]) .surface {
        position: absolute;
        inset-block: 0;
        inset-inline-end: 0;
        /* The spec's sheet box (w-[min(42vw,28rem)] min-w-80
           max-w-[28rem]), re-expressed against the WINDOW box: the element spans the host, so 42%
           here IS the spec's 42vw of the region this window owns. The chosen inline width does not
           apply — a sheet is not the boundary the reader dragged, which is also why the grip is not
           rendered in this presentation. */
        inline-size: min(100%, max(min(42%, 28rem), 20rem));
        box-shadow: var(--dialog-shadow);
        /* The spec's 200ms opacity/translate sheet budget. An ENTRY animation
           rather than a transition, because the pane is mounted only while a document is open — a
           transition has no "before" to run from. */
        animation: sv3-pane-in var(--duration-sv3-layout) var(--ease-sv3-enter);
      }
      @keyframes sv3-pane-in {
        from {
          opacity: 0;
          transform: translateX(var(--space-8));
        }
        to {
          opacity: 1;
          transform: translateX(0);
        }
      }

      @media (prefers-reduced-motion: reduce) {
        :host([overlay]) .surface {
          animation: none;
        }
      }
    `,
  ];

  static properties = {
    docPath: { attribute: false },
    citation: { attribute: false },
    highlightRange: { attribute: false },
    citationHeader: { attribute: false },
    apiBase: { type: String, attribute: 'api-base' },
    overlay: { type: Boolean, reflect: true },
  };

  /**
   * The cited document's path. `attribute: false` is the scope guard's structural half: the only way
   * a path reaches this pane is a property write from the window's citation handler.
   */
  declare docPath: string | null;
  /**
   * The cited passage, addressed by the CHARACTER offsets its producer computed (tempdoc 849 §3).
   * Null when the citation carried no span. The reader derives its own line coordinates from this;
   * nothing here converts, clamps or interprets it — a coordinate conversion in a region element
   * would be exactly the extra hop the char anchor exists to remove.
   */
  declare citation: DocumentCitationAnchor | null;
  declare highlightRange: DocumentLineRange | null;
  /**
   * Tempdoc 849 §7 — the citation header, forwarded VERBATIM. This region derives no part of it: the
   * facts are joined from the turn's own evidence record in `SearchV3View` and worded by
   * `evidenceProjection.ts`, so a label cannot be re-authored on the way through a frame element.
   */
  declare citationHeader: CitationHeader | null;
  declare apiBase: string;
  /**
   * The narrow presentation, decided by the window from its own box (see the styles above for why it
   * is an attribute and not a container query). NOT forwarded to the shared reader: that component's
   * own `overlay` attribute sizes it against the browser VIEWPORT (`DocumentPane.ts:433-441` —
   * `100vh`/`92vw`, for the Shell's document-level right drawer), which is precisely the reach this
   * window-scoped overlay must not have.
   */
  declare overlay: boolean;

  constructor() {
    super();
    this.docPath = null;
    this.highlightRange = null;
    this.citation = null;
    this.citationHeader = null;
    this.apiBase = '';
    this.overlay = false;
  }

  private readonly onPaneClose = (event: Event): void => {
    event.stopPropagation();
    this.dispatchEvent(new CustomEvent(SV3_PANE_CLOSE, { bubbles: true, composed: true }));
  };

  render(): TemplateResult | typeof nothing {
    if (this.docPath === null) return nothing;
    return html`
      <button
        type="button"
        class="backdrop"
        data-testid="sv3-pane-backdrop"
        aria-label=${PANE_LABEL.dismiss}
        @click=${this.onPaneClose}
      ></button>
      <aside class="surface" data-testid="sv3-pane-surface" aria-label=${PANE_LABEL.region}>
        <jf-document-pane
          data-testid="sv3-pane-document"
          .docPath=${this.docPath}
          .highlightRange=${this.highlightRange}
          .citation=${this.citation}
          .citationHeader=${this.citationHeader}
          api-base=${this.apiBase}
          @pane-close=${this.onPaneClose}
        ></jf-document-pane>
      </aside>
    `;
  }
}

customElements.define('jf-sv3-pane', Sv3Pane);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-pane': Sv3Pane;
  }
}
