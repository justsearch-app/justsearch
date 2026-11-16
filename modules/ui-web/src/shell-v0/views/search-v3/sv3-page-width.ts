// SPDX-License-Identifier: Apache-2.0
/**
 * Search's half of the page-width rule (owner decision, 15 November): "Search with the side pane
 * open: list and pane together, centred, at most 1280 px, the pane 400 px growing to at most 560".
 * The arithmetic is `sv3-boundaries.ts`'s; this module reads the frame's cap, writes the gutter the
 * window's rows and scroller use, owns whether the pane's width is the reader's or automatic, and
 * moves the list for a wheel in the gutter after an open pane.
 *
 * With the pane closed, the main column takes the frame's own `--surface-content-gutter` in CSS (it
 * resolves against each row's containing block). With the pane open inline, the window measures one
 * gutter for the list and the pane together and publishes it as `--sv3-page-gutter`: padding inside
 * the main column's rows and scroller, a margin after the pane. Rows and the scroller keep their full
 * width, so a wheel anywhere beside the list still scrolls it. Reflow never writes the remembered width.
 */
import { css, type ReactiveController, type ReactiveControllerHost } from 'lit';
import { sv3AutomaticPaneWidth, sv3PageGutter } from './sv3-boundaries.js';

/**
 * The rows of the window's main column, on the page column. Before the window's own sheet (which tests
 * read as the last entry), so every rule here is scoped by :host to outrank the own sheet's paddings.
 */
export const sv3PageWidthStyles = css`
  :host {
    --sv3-gutter-start: var(--sv3-page-gutter, var(--surface-content-gutter, 0px));
    --sv3-gutter-end: var(--surface-content-gutter, 0px);
  }
  :host .branch-origin {
    padding-inline: calc(var(--sv3-gutter-start) + var(--space-4)) calc(var(--sv3-gutter-end) + var(--space-4));
  }
  :host .indexing-pause-note, :host .column > .recovery-notice {
    padding-inline: calc(var(--sv3-gutter-start) + var(--floating-content-inset)) calc(var(--sv3-gutter-end) + var(--floating-content-inset));
  }
  :host .dock, :host .forget-row { padding-inline: var(--sv3-gutter-start) var(--sv3-gutter-end); }
  :host(:not([pane-overlay])) jf-sv3-pane { margin-inline-end: var(--sv3-page-gutter, 0px); }
  :host button.pane-grip { right: calc(var(--pane-width) + var(--sv3-page-gutter, 0px)); }
`;

/** The window's measures: its width, the sidebar's occupancy, whether the pane is open inline, the pane's width. */
type Sv3PageMeasures = () => readonly [available: number, sidebar: number, paneInline: boolean, paneWidth: number];

export class Sv3PageWidth implements ReactiveController {
  /** The pane's width is the reader's (remembered, dragged or nudged) rather than automatic. */
  chosen = false;
  /** The sidebar occupancy the gutter was last written for. */
  private sidebar = -1;

  constructor(private readonly host: ReactiveControllerHost & HTMLElement, private readonly measures: Sv3PageMeasures) {
    host.addController(this);
  }

  hostConnected(): void { this.host.addEventListener('wheel', this.onWheel, { passive: true }); }
  hostDisconnected(): void { this.host.removeEventListener('wheel', this.onWheel); }

  /**
   * The frame's width for the list and the pane together (`--surface-content-with-pane-max-width`),
   * or `Infinity` where the frame publishes none: a split stage, or a DOM without layout.
   */
  private cap(): number {
    const raw = getComputedStyle(this.host).getPropertyValue('--surface-content-with-pane-max-width').trim();
    return raw.endsWith('px') ? Number.parseFloat(raw) : Number.POSITIVE_INFINITY;
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

  /** Write the gutter; the pane's width to show: the reader's, or the automatic one. */
  sync(): number {
    const [available, sidebar, paneInline, current] = this.measures();
    this.sidebar = sidebar;
    const cap = this.cap();
    const gutter = paneInline ? sv3PageGutter(available, sidebar, cap) : 0;
    if (gutter > 0) this.host.style.setProperty('--sv3-page-gutter', `${gutter}px`);
    else this.host.style.removeProperty('--sv3-page-gutter');
    return this.chosen ? current : sv3AutomaticPaneWidth(available, sidebar, cap);
  }

  /** The gutter after an open pane is the window's own background: a wheel there moves the list. */
  private readonly onWheel = (event: WheelEvent): void => {
    if (event.composedPath()[0] !== this.host || this.host.style.getPropertyValue('--sv3-page-gutter') === '') return;
    const main = this.host.shadowRoot?.querySelector('jf-sv3-main');
    main?.shadowRoot?.querySelector('.scroller')?.scrollBy({ left: event.deltaX, top: event.deltaY });
  };
}
