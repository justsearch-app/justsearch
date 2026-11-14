// SPDX-License-Identifier: Apache-2.0
/**
 * Sparkline — Tempdoc 508 §11.9 — tiny inline time-series chart.
 *
 * Used to surface the data already collected in
 * `pinnedSearches.runs` (totalHits over time) and anywhere a
 * compact freshness/trend indicator helps. Pure SVG, no
 * dependencies. Renders a polyline + endpoint dot.
 *
 * Usage:
 *   <jf-sparkline values="[1,3,5,2,7]" width="32" height="12"></jf-sparkline>
 *
 * Empty / single-point values render an empty SVG (no NaN paths).
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';

export class Sparkline extends JfElement {
  static properties = {
    values: { type: Array },
    width: { type: Number },
    height: { type: Number },
  };

  declare values: ReadonlyArray<number>;
  declare width: number;
  declare height: number;

  constructor() {
    super();
    this.values = [];
    this.width = 32;
    this.height = 12;
  }

  static styles = css`
    :host {
      display: inline-block;
      vertical-align: middle;
      line-height: 0;
    }
    svg { display: block; overflow: visible; }
    .line {
      fill: none;
      stroke: var(--text-secondary);
      stroke-width: 1.25;
      stroke-linejoin: round;
      stroke-linecap: round;
    }
    .endpoint {
      fill: var(--text-secondary);
    }
  `;

  override render(): TemplateResult {
    const vs = this.values ?? [];
    if (vs.length < 2) {
      return html`<svg width=${this.width} height=${this.height} aria-hidden="true"></svg>`;
    }
    const min = Math.min(...vs);
    const max = Math.max(...vs);
    const range = max - min || 1;
    const w = this.width;
    const h = this.height;
    const dx = w / (vs.length - 1);
    const points = vs
      .map((v, i) => {
        const x = i * dx;
        const y = h - ((v - min) / range) * h;
        return `${x.toFixed(2)},${y.toFixed(2)}`;
      })
      .join(' ');
    const last = vs[vs.length - 1]!;
    const lastX = (vs.length - 1) * dx;
    const lastY = h - ((last - min) / range) * h;
    return html`
      <svg width=${w} height=${h} aria-hidden="true">
        <polyline class="line" points=${points}></polyline>
        <circle class="endpoint" cx=${lastX.toFixed(2)} cy=${lastY.toFixed(2)} r="1.5"></circle>
      </svg>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-sparkline')) {
  customElements.define('jf-sparkline', Sparkline);
}
