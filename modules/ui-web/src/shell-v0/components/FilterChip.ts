// SPDX-License-Identifier: Apache-2.0
/**
 * FilterChip (`jf-filter-chip`) — tempdoc 574 §23.B (Move 3, the visual-atom tier). @atom
 *
 * The ONE filter-toggle chip: a bordered, transparent pill that soft-tints when active. The toggle-filter
 * shape that LogSurface (severity / sub-category) and AdvisoryInboxDrawer (class / transport / outcome) each
 * hand-rolled with drifting radius (9999px vs 1rem), active treatment (soft-tint vs solid fill) and font.
 * The active colour PROJECTS from the 565 `statusTone` authority (`toneAccentSoft` bg + `toneAccent` text),
 * so an active chip's tint can never be off-palette; `tone` defaults to `info`, and a severity chip passes
 * `tone="error"` / `"warning"`.
 *
 * Presentational: the host owns NO toggle logic — the consumer binds `@click` and flips `active`. Renders a
 * real `<button aria-pressed>` so the toggle is keyboard-operable (the 559 controls-a11y contract). Slotted
 * content (icon, label, a `.chip-count`) is styled by the consumer's light-DOM CSS.
 */
import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { type NoticeTone, toneAccent, toneAccentSoft } from '../utils/statusTone.js';

export class FilterChip extends JfElement {
  static properties = {
    active: { type: Boolean, reflect: true },
    tone: { type: String },
  };

  declare active: boolean;
  /** The tone the chip tints to when active (default `info`); a severity chip passes `error`/`warning`. */
  declare tone?: NoticeTone;

  constructor() {
    super();
    this.active = false;
  }

  static styles = css`
    :host {
      display: inline-flex;
    }
    button {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.125rem 0.5rem;
      border-radius: 9999px;
      border: 1px solid var(--border-subtle);
      background: transparent;
      color: var(--text-secondary);
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
      transition: background var(--duration-fast), color var(--duration-fast),
        border-color var(--duration-fast);
    }
    button:hover {
      border-color: var(--accent);
      color: var(--text-primary);
    }
    button:focus-visible {
      outline: 2px solid var(--focus-ring-color);
      outline-offset: 1px;
    }
    button[aria-pressed='true'] {
      background: var(--chip-active-bg);
      color: var(--chip-active-fg);
      border-color: var(--chip-active-fg);
    }
  `;

  override render(): TemplateResult {
    const tone = this.tone ?? 'info';
    return html`<button
      type="button"
      part="chip"
      aria-pressed=${this.active ? 'true' : 'false'}
      .style=${`--chip-active-bg: ${toneAccentSoft(tone)}; --chip-active-fg: ${toneAccent(tone)}`}
    >
      <slot></slot>
    </button>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-filter-chip')) {
  customElements.define('jf-filter-chip', FilterChip);
}
