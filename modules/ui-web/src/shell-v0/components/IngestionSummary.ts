// SPDX-License-Identifier: Apache-2.0
import { css, html, nothing, type PropertyValues, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { subscribeAiState } from '../state/aiStateStore.js';
import { ingestionSummarySchema, ingestionSummaryRows, ingestionReasonLabel,
  ingestionOutcomeLabel, type IngestionRollup } from './ingestionSummaryPresentation.js';
import './Button.js';
import './ErrorAlert.js';
import './StatusBadge.js';

const ENDPOINT = '/api/diagnostics/ingestion/summary?since=0';
const LIVE_REFRESH_MIN_MS = 4000; // Same cadence bound as Library's root refresh.
const REQUEST_TIMEOUT_MS = 15000;
const VISIBLE_GROUPS = 20;

/** A retained-event summary, deliberately distinct from current per-folder readiness. */
export class IngestionSummary extends JfElement {
  static properties = {
    compact: { type: Boolean, reflect: true },
    host_: { attribute: false },
    rows: { state: true },
    loading: { state: true },
    announceLoading: { state: true },
    error: { state: true },
    expanded: { state: true },
  };
  static transientState = { loading: false, announceLoading: false, error: false };
  declare compact: boolean;
  declare host_: PluginHostApi | undefined;
  declare rows: IngestionRollup[] | null;
  declare loading: boolean;
  declare announceLoading: boolean;
  declare error: boolean;
  declare expanded: boolean;
  private unsubscribe: (() => void) | null = null;
  private request: AbortController | null = null;
  private timeout: ReturnType<typeof setTimeout> | null = null;
  private generation = 0;
  private lastAttempt = -Infinity;
  private currentHost: PluginHostApi | undefined;

  constructor() {
    super();
    this.compact = false;
    this.rows = null;
    this.loading = true;
    this.announceLoading = true;
    this.error = false;
    this.expanded = false;
  }

  static styles = css`
    :host { display: block; margin-top: 1.5rem; }
    :host([compact]) { margin-top: 0; flex: 1 1 17rem; min-width: 0; font-size: 13px; color: var(--text-secondary); }
    :host([compact]) section { padding: 1rem 0; border: 0; border-radius: 0; background: transparent; }
    summary { cursor: pointer; }
    section { padding: 1rem; background: var(--surface-secondary);
      border: 1px solid var(--border-subtle); border-radius: 0.5rem; }
    header { display: flex; flex-wrap: wrap; align-items: center;
      justify-content: space-between; gap: 0.5rem; }
    h3 { margin: 0; font-size: var(--font-size-md); }
    p { color: var(--text-secondary); font-size: var(--font-size-sm); }
    ul { margin: 0.5rem 0; padding: 0; list-style: none; }
    li { display: flex; flex-wrap: wrap; align-items: baseline;
      gap: 0.5rem; padding: 0.375rem 0; font-size: var(--font-size-sm); }
    .count { font-variant-numeric: tabular-nums; font-weight: 600; }
    .reason { flex: 1 1 14rem; overflow-wrap: anywhere; }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    void this.refresh();
    // Ride the existing status tick; do not create another repeating poller.
    this.unsubscribe = subscribeAiState(() => {
      if (!this.loading && Date.now() - this.lastAttempt >= LIVE_REFRESH_MIN_MS) {
        void this.refresh({ background: true });
      }
    });
  }

  protected override updated(changed: PropertyValues): void {
    if (changed.has('host_') && this.currentHost !== this.host_) {
      this.cancelRequest();
      this.rows = null;
      this.error = false;
      void this.refresh();
    }
  }

  override disconnectedCallback(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
    this.cancelRequest();
    super.disconnectedCallback();
  }

  private cancelRequest(): void {
    this.generation += 1;
    this.request?.abort();
    this.request = null;
    if (this.timeout !== null) clearTimeout(this.timeout);
    this.timeout = null;
    this.loading = false;
  }

  private async refresh({ background = false }: { background?: boolean } = {}): Promise<void> {
    if (!this.isConnected || this.request) return;
    this.currentHost = this.host_;
    if (!this.host_) { this.loading = false; this.error = true; return; }
    const generation = ++this.generation;
    const controller = new AbortController();
    this.request = controller;
    this.lastAttempt = Date.now();
    // Background polling must not repeatedly announce loading and the same result.
    // Keep the live region stable until the outcome count or fetch-error state changes.
    this.announceLoading = !background;
    this.loading = true;
    try {
      // Host data.fetch is Library's authorized transport. Bound both response and body reads.
      const fetchRows = async () => {
        const response = await this.host_!.data.fetch(ENDPOINT, { signal: controller.signal });
        if (!response.ok) throw new Error('Summary unavailable');
        const result = ingestionSummarySchema.parse(await response.json());
        if (result.count !== result.rollups.length) throw new Error('Incomplete summary');
        return ingestionSummaryRows(result.rollups);
      };
      const timeout = new Promise<never>((_, reject) => {
        this.timeout = setTimeout(() => {
          controller.abort();
          reject(new Error('Summary request timed out'));
        }, REQUEST_TIMEOUT_MS);
      });
      const rows = await Promise.race([fetchRows(), timeout]);
      if (generation !== this.generation || !this.isConnected) return;
      this.rows = rows;
      this.error = false;
    } catch {
      if (generation !== this.generation || !this.isConnected) return;
      this.error = true;
    } finally {
      if (generation === this.generation) {
        if (this.timeout !== null) clearTimeout(this.timeout);
        this.timeout = null;
        this.request = null;
        this.loading = false;
      }
    }
  }

  override render(): TemplateResult {
    const total = this.rows?.reduce((sum, row) => sum + row.count, 0) ?? 0;
    const visible = this.expanded ? this.rows : this.rows?.slice(0, VISIBLE_GROUPS);
    const content = this.renderSummary(total, visible ?? null);
    return this.compact ? html`<details><summary>Activity · ${this.rows ? `${total.toLocaleString()} recorded events${this.error ? ' · last known' : ''}` : this.loading ? 'Loading…' : 'Unavailable'}</summary>${content}</details>` : content;
  }

  private renderSummary(total: number, visible: IngestionRollup[] | null): TemplateResult {
    return html`<section aria-labelledby="activity-heading" data-testid="ingestion-summary">
      <header>
        <h3 id="activity-heading">Indexing activity</h3>
        <jf-button .disabled=${this.loading} .onActivate=${() => this.refresh()}>
          ${this.error ? 'Retry activity refresh' : 'Refresh activity'}
        </jf-button>
      </header>
      <p>Recorded outcomes from retained indexing history. A file can appear more than once;
        these counts do not describe its current search readiness.</p>
      ${this.error ? html`<jf-error-alert status="warning">
        Could not refresh indexing activity. Check that JustSearch is running, then retry the refresh.
        ${this.rows ? 'Showing the last loaded summary.' : ''}
      </jf-error-alert>` : nothing}
      <p role="status" aria-live="polite">${this.loading && this.announceLoading
        ? (this.rows ? 'Refreshing indexing activity…' : 'Loading indexing activity…')
        : this.rows ? (total === 0 ? 'No indexing outcomes in retained history.'
          : `${total.toLocaleString()} recorded indexing ${total === 1 ? 'outcome' : 'outcomes'}${this.error ? ' in the last loaded summary' : ''}.`)
          : 'Indexing activity is unavailable.'}</p>
      ${visible?.length ? html`<ul aria-label="Recorded indexing outcomes">
        ${visible.map((row) => {
          const outcome = ingestionOutcomeLabel(row.outcomeClass);
          return html`<li><span class="count">${row.count.toLocaleString()}</span>
            <jf-status-badge .status=${outcome.status}>${outcome.label}</jf-status-badge>
            <span class="reason">${ingestionReasonLabel(row.reasonCode)}</span></li>`;
        })}
      </ul>` : nothing}
      ${this.rows && this.rows.length > VISIBLE_GROUPS ? html`
        <jf-button .onActivate=${() => { this.expanded = !this.expanded; }}>
          ${this.expanded ? 'Show fewer reasons' : `Show all ${this.rows.length} outcome and reason groups`}
        </jf-button>` : nothing}
    </section>`;
  }
}

if (!customElements.get('jf-ingestion-summary')) {
  customElements.define('jf-ingestion-summary', IngestionSummary);
}
