// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 499 F5 — Resolution quality metrics panel.
 *
 * Reads telemetry data from localStorage (written by resolutionTelemetry.ts)
 * and renders text-based stats: outcome breakdown, top failed IDs, and
 * correction rate.
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';

const STORAGE_KEY = 'jf.resolution-telemetry';

interface TelemetryEntry {
  status: string;
  transport: string;
  attemptedId?: string;
  timestamp: number;
}

export class ResolutionStats extends JfElement {
  static properties = {
    entries: { state: true },
  };

  declare entries: TelemetryEntry[];

  constructor() {
    super();
    this.entries = [];
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.loadEntries();
  }

  private loadEntries(): void {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) { this.entries = []; return; }
      const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
      this.entries = (JSON.parse(raw) as TelemetryEntry[]).filter(e => e.timestamp > cutoff);
    } catch {
      this.entries = [];
    }
  }

  static styles = css`
    :host {
      display: block;
      font-family: ui-monospace, monospace;
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
    }
    .section { margin-bottom: 1rem; }
    .section-title {
      font-weight: bold;
      color: var(--text-primary);
      margin-bottom: 0.25rem;
    }
    .stat-row {
      display: flex;
      justify-content: space-between;
      padding: 0.125rem 0;
    }
    .stat-label { opacity: 0.8; }
    .stat-value { color: var(--text-secondary); font-weight: 500; }
    .failed-id {
      padding: 0.125rem 0;
      color: var(--text-warning);
    }
    .empty { opacity: 0.5; font-style: italic; }
  `;

  override render(): TemplateResult {
    const total = this.entries.length;
    if (total === 0) {
      return html`<div class="empty">No resolution telemetry data yet (7-day window).</div>`;
    }

    const byStatus: Record<string, number> = {};
    const failedIds: Record<string, number> = {};
    for (const e of this.entries) {
      byStatus[e.status] = (byStatus[e.status] ?? 0) + 1;
      if (e.status === 'unresolved' && e.attemptedId) {
        failedIds[e.attemptedId] = (failedIds[e.attemptedId] ?? 0) + 1;
      }
    }

    const topFailed = Object.entries(failedIds)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5);

    const correctionRate = ((byStatus['auto-corrected'] ?? 0) / total * 100).toFixed(1);
    const failRate = ((byStatus['unresolved'] ?? 0) / total * 100).toFixed(1);

    return html`
      <div class="section">
        <div class="section-title">Resolution outcomes (7 days)</div>
        ${Object.entries(byStatus).map(([status, count]) => html`
          <div class="stat-row">
            <span class="stat-label">${status}</span>
            <span class="stat-value">${count} (${(count / total * 100).toFixed(1)}%)</span>
          </div>
        `)}
        <div class="stat-row">
          <span class="stat-label">Total</span>
          <span class="stat-value">${total}</span>
        </div>
      </div>
      <div class="section">
        <div class="section-title">Rates</div>
        <div class="stat-row">
          <span class="stat-label">Correction rate</span>
          <span class="stat-value">${correctionRate}%</span>
        </div>
        <div class="stat-row">
          <span class="stat-label">Failure rate</span>
          <span class="stat-value">${failRate}%</span>
        </div>
      </div>
      ${topFailed.length > 0 ? html`
        <div class="section">
          <div class="section-title">Top failed IDs</div>
          ${topFailed.map(([id, count]) => html`
            <div class="failed-id">${id} (${count}x)</div>
          `)}
        </div>
      ` : ''}
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-resolution-stats')) {
  customElements.define('jf-resolution-stats', ResolutionStats);
}
