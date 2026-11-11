// SPDX-License-Identifier: Apache-2.0
/**
 * OverlayHost — tempdoc 559 Authority I (the overlay-slot authority).
 *
 * THE single owner of where persistent chrome overlays dock and how they stack.
 * Today ~22 overlays each free-`position:fixed` with a hand-picked z-index, so
 * nothing arbitrates the shared viewport (the badge floats over the header, a
 * feed grows over the rail, the nav-toast overlaps the composer). This host
 * owns named, fixed-positioned **slots** with one z-index scale; an overlay
 * docks by `slot="…"` and drops its own positioning — so "an overlay picks raw
 * coordinates" becomes unrepresentable (collapse, tier-1). Top-layer overlays
 * (ProvenanceBadge's popover; the modal dialogs: CommandPalette, SettingsWindow)
 * still escape to the browser top layer transparently; the slot is just their DOM parent.
 *
 * Slot containers are **content-sized at a viewport anchor** (not full-bleed),
 * with `pointer-events:none` on the container + `auto` on slotted children, so
 * an empty slot never blocks the page. `center` is the one full-viewport slot
 * (modals/palette), which manage their own backdrop.
 *
 * z-scale (single source; overlays no longer choose):
 *   float 900 · chrome 1000 · transient 1100 · modal 2000 · top 3000
 */
import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';

export class OverlayHost extends JfElement {
  static styles = css`
    :host {
      display: contents;
    }
    .slot {
      position: fixed;
      pointer-events: none;
      display: flex;
      gap: 0.5rem;
    }
    .slot ::slotted(*) {
      pointer-events: auto;
    }
    /* corner + edge anchors (content-sized) */
    /* Tempdoc 602 R4, shell slice 2 — dock below the top bar (--frame-topbar-height, the Shell's
       first grid row) so a top-right overlay (badge, plugin-error) never occludes the bar's
       right-hand cluster (status pill, Settings, More).

       The extra 3rem clears a surface's OWN header control row (UnifiedChatView's .header:
       Activity / New chat / Export / the shape badge), which sits immediately below the topbar:
       measured at 1050x800 with --fixtures, that band is y 56-88px, so a column docked at
       2.5rem + 0.5rem = 48px landed squarely on it (the bar was 2.5rem then). The reserved band is therefore the topbar
       row PLUS the surface header row — declared once here, in the ONE slot authority
       (559 Authority I), rather than re-derived by each overlay that docks here.

       Scope note: the reservation now serves this slot's persistent occupants —
       jf-provenance-badge and jf-plugin-error-overlay. jf-advisory-toast-host also docks here,
       but every toast it renders is time-bounded (AdvisoryToastHost's TOAST_DURATION_MS /
       ACK_TOAST_DURATION_MS), so it is no longer the reason the band exists. Asserted by the
       chat-occlusion step's mustNotOverlapSelector row in
       governance/ui-proportion-baseline.v1.json. */
    .top-right {
      top: calc(var(--frame-topbar-height) + 3rem + 0.5rem);
      right: 0.5rem;
      flex-direction: column;
      align-items: flex-end;
      z-index: var(--z-overlay-chrome);
    }
    .top-center {
      top: 0.5rem;
      left: 50%;
      transform: translateX(-50%);
      flex-direction: column;
      align-items: center;
      z-index: var(--z-overlay-transient);
    }
    .bottom-center {
      bottom: 2.5rem;
      left: 50%;
      transform: translateX(-50%);
      flex-direction: column;
      align-items: center;
      z-index: var(--z-overlay-transient);
    }
    /* Tempdoc 813 §5c, shell slice 2 — the bottom-left slot used to reserve the left rail's band (its Settings and Help controls sat
       in that corner). Shell slice 2 retired the rail: Settings and Help live in the top bar, so the
       corner is the window's own and the slot docks at the same inset as its right-hand sibling. */
    .bottom-left {
      bottom: 1rem;
      left: 1rem;
      flex-direction: column;
      z-index: var(--z-overlay-float);
    }
    .bottom-right {
      bottom: 1rem;
      right: 1rem;
      flex-direction: column;
      align-items: flex-end;
      z-index: var(--z-overlay-float);
    }
    /* full-viewport slot for modals/palette (they own their backdrop) */
    .center {
      inset: 0;
      z-index: var(--z-overlay-modal);
    }
  `;

  override render(): TemplateResult {
    return html`
      <div class="slot top-right"><slot name="top-right"></slot></div>
      <div class="slot top-center"><slot name="top-center"></slot></div>
      <div class="slot bottom-center"><slot name="bottom-center"></slot></div>
      <div class="slot bottom-left"><slot name="bottom-left"></slot></div>
      <div class="slot bottom-right"><slot name="bottom-right"></slot></div>
      <div class="slot center"><slot name="center"></slot></div>
    `;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-overlay-host')
) {
  customElements.define('jf-overlay-host', OverlayHost);
}
