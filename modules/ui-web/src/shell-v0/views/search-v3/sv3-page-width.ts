// SPDX-License-Identifier: Apache-2.0
/**
 * Search's half of the page-width rule. The owner's rule of 15 November centred the list and the pane
 * together; the picture author's decision of 17 November, delegated by the owner, replaced that half:
 * "The pane docks to the window's right edge. Pane width: 400 px at the default window, then 30 % of
 * the window width, up to 592 px ... The list keeps the maximum width it already has for plain results
 * and is centred in the space left of the pane." The arithmetic is `sv3-boundaries.ts`'s; this module
 * reads the frame's share, and owns whether the pane's width is the reader's or automatic.
 *
 * The pane is the window's last flex track, so it sits against the window's right edge and its
 * surface and lines span its own width. The main column's rows and scroller take the frame's
 * `--surface-content-gutter` in CSS, which resolves against each row's containing block: the space the
 * sidebar and the pane leave, so the list is centred there whether the pane is open or not. Rows and
 * the scroller keep their full width, so a wheel anywhere beside the list still scrolls it. Reflow
 * never writes the remembered width.
 */
import { css } from 'lit';
import { sv3AutomaticPaneWidth } from './sv3-boundaries.js';

/**
 * The rows of the window's main column, on the page column. Interpolated at the top of the window's
 * own sheet (which tests read as its third entry), so every rule here is scoped by :host to outrank
 * the own sheet's paddings that follow it.
 */
export const sv3PageWidthStyles = css`
  :host .branch-origin {
    padding-inline: calc(var(--surface-content-gutter, 0px) + var(--space-4));
  }
  :host .indexing-pause-note, :host .column > .recovery-notice {
    padding-inline: calc(var(--surface-content-gutter, 0px) + var(--floating-content-inset));
  }
  :host .dock, :host .forget-row { padding-inline: var(--surface-content-gutter, 0px); }
`;

/** The window's measures: its width, the sidebar's occupancy, the pane's width. */
type Sv3PageMeasures = () => readonly [available: number, sidebar: number, paneWidth: number];

export class Sv3PageWidth {
  /** The pane's width is the reader's (remembered, dragged or nudged) rather than automatic. */
  chosen = false;
  /** The sidebar occupancy the pane's width was last computed for. */
  private sidebar = -1;

  constructor(private readonly host: HTMLElement, private readonly measures: Sv3PageMeasures) {}

  /**
   * The frame's share of the window for the docked pane (`--surface-pane-share`, a percentage), or
   * `null` where the frame publishes none: a split stage, or a DOM without layout.
   */
  private share(): number | null {
    const raw = getComputedStyle(this.host).getPropertyValue('--surface-pane-share').trim();
    return raw.endsWith('%') ? Number.parseFloat(raw) / 100 : null;
  }

  /** A remembered width is the reader's choice; none leaves the pane automatic. */
  recall(stored: number | null): number | null {
    this.chosen = stored !== null;
    return stored;
  }

  /** Returning the pane to automatic (the remembered width is forgotten by the caller). */
  forget(): number {
    this.chosen = false;
    return this.sync();
  }

  /** Whether the sidebar's occupancy moved since the last sync (it appears with the first conversation). */
  sidebarMoved(sidebar: number): boolean {
    return sidebar !== this.sidebar;
  }

  /** The pane's width to show: the reader's, or the automatic one. */
  sync(): number {
    const [available, sidebar, current] = this.measures();
    this.sidebar = sidebar;
    return this.chosen ? current : sv3AutomaticPaneWidth(available, sidebar, this.share());
  }
}
