// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a.2.e — `<jf-log-surface>` Log surface.
 *
 * Consumes the `core.engine-log` DiagnosticChannel (slice 448
 * substrate). Renders a scrollable log view with a bounded ring
 * buffer + filter chips (severity + sub-category + substring
 * search) + pause/resume.
 *
 * Mounts via the Surface Manifest substrate (slice 449): the
 * Java `CoreSurfaceCatalog` declares a `core.logs-surface` Surface
 * pointing at this element's `jf-log-surface` mountTag. Stage's
 * dispatch via `mountSurface()` (478 §4.A) routes here when the
 * Logs rail icon is clicked.
 *
 * Architecture per slice 448 phase 5:
 *   - `getDiagnosticChannel('core.engine-log')` from the
 *     DiagnosticChannelCatalogClient gives the channel definition
 *     (endpoint + presentation metadata).
 *   - `diagnosticChannelStrategy({ cap, subCategoryFilter })`
 *     returns the reducer + initial state for the
 *     `EnvelopeStream<DiagnosticChannelStrategyState>` consumer
 *     pattern.
 *   - The element opens the SSE stream against the endpoint, runs
 *     the reducer to maintain the ring buffer, and renders newest
 *     events first.
 *
 * Catalog-boot race handling per slice 3a.2 §B.B.E.1: if the
 * DiagnosticChannelCatalogClient hasn't booted by the time this
 * element mounts, wait for the
 * `onDiagnosticChannelCatalogChange` event before erroring out.
 *
 * Out of scope (per slice 3a-2-e):
 *   - Log persistence / export
 *   - Structured log parsing beyond severity + sub-category
 *   - Server-side filtering pushdown
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { surfaceLayoutStyles } from '../primitives/surfaceLayout.js';
import '../components/StatusBadge.js';
import '../components/FilterChip.js';
import { newestFirst } from '../primitives/eventStreamProjection.js';
// Fix-pass A.4: side-effect import ensures <jf-boot-phases-panel> is registered when
// LogSurface is loaded (Shell.ts also imports it; this declaration makes the dependency
// explicit at the usage site).
import '../components/BootPhasesPanel.js';
import {
  getDiagnosticChannel,
  listDiagnosticChannels,
  onDiagnosticChannelCatalogChange,
} from '../../api/registry/DiagnosticChannelCatalogClient.js';
import {
  diagnosticChannelStrategy,
  type DiagnosticChannelStrategyState,
} from '../strategies/diagnosticChannelStrategy.js';
import {
  EnvelopeStream,
  type EnvelopeStreamSnapshot,
} from '../streaming/EnvelopeStream.js';
import type {
  DiagnosticEvent,
  SubCategory,
} from '../../api/types/diagnostic.js';

const LOG_CHANNEL_ID = 'core.engine-log';
const RING_BUFFER_CAP = 5000;
const VIRTUALIZER_THRESHOLD = 200;

/** Severities surfaced as filter chips. */
const SEVERITIES = ['DEBUG', 'INFO', 'WARN', 'ERROR'] as const;
type Severity = (typeof SEVERITIES)[number];

/** Sub-category filter set for the strategy (V1 default). */
const DEFAULT_SUB_CATEGORIES: readonly SubCategory[] = [
  'CORE_DIAGNOSTIC',
];

/** Available sub-categories the user can toggle. */
const FILTERABLE_SUB_CATEGORIES: readonly SubCategory[] = [
  'CORE_DIAGNOSTIC',
  'LIBRARY_TRACE',
  'BOOT_TRACE',
];

const SUB_CATEGORY_LABELS: Partial<Record<SubCategory, string>> = {
  CORE_DIAGNOSTIC: 'App logs',
  LIBRARY_TRACE: 'Library logs',
  BOOT_TRACE: 'Startup logs',
};

type ConnectionState = 'idle' | 'connecting' | 'connected' | 'error';

export class LogSurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    connectionState: { state: true },
    error: { state: true },
    events: { state: true },
    paused: { state: true },
    severityFilter: { state: true },
    subCategoryFilter: { state: true },
    searchText: { state: true },
  };

  declare apiBase: string;
  declare connectionState: ConnectionState;
  declare error: string | null;
  declare events: DiagnosticEvent[];
  declare paused: boolean;
  declare severityFilter: ReadonlySet<Severity>;
  declare subCategoryFilter: ReadonlySet<SubCategory>;
  declare searchText: string;

  /** SSE stream subscription teardown. */
  private streamUnsubscribe: (() => void) | null = null;
  /** Underlying SSE connection. */
  private stream: EnvelopeStream<DiagnosticChannelStrategyState> | null = null;
  /** Catalog-boot race subscription teardown. */
  private catalogChangeUnsubscribe: (() => void) | null = null;
  /** When paused: events that arrive but aren't shown. */
  private bufferedWhilePaused: DiagnosticEvent[] = [];

  constructor() {
    super();
    this.apiBase = '';
    this.connectionState = 'idle';
    this.error = null;
    this.events = [];
    this.paused = false;
    this.severityFilter = new Set(SEVERITIES);
    this.subCategoryFilter = new Set(DEFAULT_SUB_CATEGORIES);
    this.searchText = '';
  }

  override connectedCallback(): void {
    super.connectedCallback();
    void this.bind();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unbind();
  }

  /**
   * Open the channel + start the stream. Catalog-boot race per
   * slice 3a.2 §B.B.E.1.
   */
  private async bind(): Promise<void> {
    const channel = getDiagnosticChannel(LOG_CHANNEL_ID);
    if (!channel) {
      // First-visit catalog-boot race: catalog boot may still be in
      // flight. If listDiagnosticChannels() is empty, wait for the
      // arrival event and re-bind. If there ARE entries but ours
      // isn't there, that's a true not-found.
      if (listDiagnosticChannels().length === 0) {
        this.connectionState = 'connecting';
        this.catalogChangeUnsubscribe = onDiagnosticChannelCatalogChange(() => {
          if (this.catalogChangeUnsubscribe) {
            this.catalogChangeUnsubscribe();
            this.catalogChangeUnsubscribe = null;
          }
          void this.bind();
        });
        return;
      }
      this.error = `Channel not found in catalog: ${LOG_CHANNEL_ID}`;
      this.connectionState = 'error';
      return;
    }

    const strategy = diagnosticChannelStrategy({
      cap: RING_BUFFER_CAP,
      subCategoryFilter: this.subCategoryFilter,
    });

    this.connectionState = 'connecting';
    const baseUrl =
      this.apiBase ||
      (typeof globalThis !== 'undefined' &&
        (globalThis as { location?: { origin?: string } }).location?.origin
        ? (globalThis as { location: { origin: string } }).location.origin
        : '');
    const url = `${baseUrl}${channel.endpoint}`;

    const stream = new EnvelopeStream<DiagnosticChannelStrategyState>({
      url,
      initialState: strategy.initialState,
      reducer: strategy.reducer,
    });
    this.stream = stream;
    this.streamUnsubscribe = stream.subscribe(
      (snap: EnvelopeStreamSnapshot<DiagnosticChannelStrategyState>) => {
        this.connectionState = snap.isConnected ? 'connected' : 'connecting';
        const incoming = snap.payload.data.events;
        if (this.paused) {
          // Pause/resume intent: "freeze the visible view; resume
          // shows the latest stream state at the time of resume."
          // (Per slice 3a-2-e §"Tentative scope": "stops appending
          // new entries to the visible buffer; underlying stream
          // stays open.")
          //
          // While paused, mirror the ring buffer's current state in
          // bufferedWhilePaused. On resume, events <- bufferedWhilePaused.
          // Defensive copy (not shared reference) so the buffer
          // doesn't drift if the reducer were to mutate later.
          this.bufferedWhilePaused = [...incoming];
          // No requestUpdate — the visible events array hasn't
          // changed; pausing is supposed to FREEZE the view.
          return;
        }
        // Reactive update: reassign array reference so Lit re-renders.
        this.events = [...incoming];
      },
    );
    stream.start();
  }

  private unbind(): void {
    if (this.catalogChangeUnsubscribe) {
      this.catalogChangeUnsubscribe();
      this.catalogChangeUnsubscribe = null;
    }
    if (this.streamUnsubscribe) {
      this.streamUnsubscribe();
      this.streamUnsubscribe = null;
    }
    if (this.stream) {
      this.stream.stop();
      this.stream = null;
    }
    this.events = [];
    this.bufferedWhilePaused = [];
    this.connectionState = 'idle';
    this.error = null;
  }

  /**
   * Apply the filter chip set + substring search to the ring buffer.
   * Newest-first ordering (events come from the strategy in arrival
   * order; we reverse the visible slice at render time).
   */
  private filteredEvents(): DiagnosticEvent[] {
    const text = this.searchText.trim().toLowerCase();
    // Filter in arrival order, then project newest-first via the shared ordering primitive (tempdoc
    // 571 — the one concern genuinely shared with the Activity action-ledger; filter chips / pause /
    // virtualization stay Logs-specific). filter() preserves order, so this is equivalent to the
    // prior reverse-while-filtering loop.
    const matched = this.events.filter(
      (e) =>
        this.severityFilter.has(e.level as Severity) &&
        this.subCategoryFilter.has(e.subCategory) &&
        (text.length === 0 || e.message.toLowerCase().includes(text)),
    );
    return newestFirst(matched);
  }

  private toggleSeverity(level: Severity): void {
    const next = new Set(this.severityFilter);
    if (next.has(level)) next.delete(level);
    else next.add(level);
    this.severityFilter = next;
  }

  private toggleSubCategory(sc: SubCategory): void {
    const next = new Set(this.subCategoryFilter);
    if (next.has(sc)) next.delete(sc);
    else next.add(sc);
    this.subCategoryFilter = next;
  }

  private togglePause(): void {
    if (this.paused) {
      // Resuming: snap visible to the latest stream state. If new
      // events arrived during pause, bufferedWhilePaused was
      // updated by the SSE callback above; if NO new events
      // arrived, bufferedWhilePaused still holds the pre-pause
      // snapshot from the else-branch below.
      this.events = [...this.bufferedWhilePaused];
      this.bufferedWhilePaused = [];
      this.paused = false;
    } else {
      // Pausing: defensive seed of bufferedWhilePaused with the
      // current visible state. Without this, a pause-then-resume
      // cycle with NO incoming events between would clear the
      // view (bufferedWhilePaused would still be []). The SSE
      // callback overwrites this seed when an event arrives.
      this.paused = true;
      this.bufferedWhilePaused = [...this.events];
    }
  }

  private clearVisible(): void {
    this.events = [];
    this.bufferedWhilePaused = [];
  }

  static styles = [
    surfaceLayoutStyles,
    css`
    .header {
      flex-shrink: 0;
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--border-subtle);
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      background: var(--surface-1);
    }
    .header h2 {
      margin: 0;
      font-size: var(--font-size-md);
      font-weight: 600;
    }
    .controls {
      display: flex;
      gap: 0.5rem;
      flex-wrap: wrap;
      align-items: center;
    }
    .chip-group {
      display: flex;
      gap: 0.25rem;
      align-items: center;
    }
    input[type='text'] {
      flex: 1;
      min-width: 12rem;
      padding: 0.25rem 0.5rem;
      background: var(--surface-tertiary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      color: var(--text-primary);
      font-size: var(--font-size-xs);
      font-family: ui-monospace, monospace;
    }
    button.action {
      padding: 0.25rem 0.6rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      cursor: pointer;
    }
    button.action:hover {
      background: var(--surface-hover);
      color: var(--text-primary);
    }
    button.action.paused {
      color: var(--text-warning);
      border-color: var(--accent-warning-30);
    }
    .body {
      flex: 1;
      overflow-y: auto;
      padding: 0.5rem 1rem;
    }
    .empty,
    .error {
      padding: 2rem;
      text-align: center;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    .error {
      color: var(--text-danger);
    }
    .row {
      display: grid;
      grid-template-columns: 9rem 4rem 9rem 1fr;
      gap: 0.5rem;
      padding: 0.125rem 0;
      border-bottom: 1px solid var(--border-subtle);
      font-family: ui-monospace, monospace;
      font-size: var(--font-size-xs);
      align-items: baseline;
    }
    .row .ts {
      color: var(--text-tertiary);
      white-space: nowrap;
    }
    .row .level {
      font-weight: 600;
    }
    .row .level.DEBUG {
      color: var(--text-tertiary);
    }
    .row .level.INFO {
      color: var(--text-primary);
    }
    .row .level.WARN {
      color: var(--text-warning);
    }
    .row .level.ERROR {
      color: var(--text-danger);
    }
    .row .logger {
      color: var(--text-tertiary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .row .msg {
      word-break: break-word;
    }
    /* 574 B2 — the status pills are the jf-status-badge atom now; the per-surface .badge fork
       (tinted bg + solid tone text) is deleted. */
  `,
  ];

  override render(): TemplateResult {
    if (this.connectionState === 'error') {
      return html`
        <div class="header"><h2>Logs</h2></div>
        <div class="error">${this.error ?? 'Unknown error.'}</div>
      `;
    }
    const visible = this.filteredEvents();
    return html`
      <div class="header">
        <h2>
          Logs
          <jf-status-badge tone="info">${this.connectionState}</jf-status-badge>
          ${this.paused
            ? html`<jf-status-badge tone="warning">paused</jf-status-badge>`
            : nothing}
        </h2>
        <div class="sub" style="font-size: var(--font-size-sm);color:var(--text-secondary);margin:0.25rem 0 0.5rem">
          What the system is doing internally — live diagnostic stream from
          head + worker. For action audit (who clicked what), see Activity.
        </div>
        <div class="controls">
          <div class="chip-group" aria-label="Severity filter">
            ${SEVERITIES.map(
              (sev) => html`
                <jf-filter-chip
                  ?active=${this.severityFilter.has(sev)}
                  tone=${sev === 'ERROR' ? 'error' : sev === 'WARN' ? 'warning' : 'info'}
                  @click=${() => this.toggleSeverity(sev)}
                  >${sev}</jf-filter-chip
                >
              `,
            )}
          </div>
          <div class="chip-group" aria-label="Sub-category filter">
            ${FILTERABLE_SUB_CATEGORIES.map(
              (sc) => html`
                <jf-filter-chip
                  ?active=${this.subCategoryFilter.has(sc)}
                  @click=${() => this.toggleSubCategory(sc)}
                  title=${sc}
                  >${SUB_CATEGORY_LABELS[sc] ?? sc}</jf-filter-chip
                >
              `,
            )}
          </div>
          <input
            type="text"
            placeholder="Search messages…"
            .value=${this.searchText}
            @input=${(e: Event) => (this.searchText = (e.target as HTMLInputElement).value)}
          />
          <button
            class="action ${this.paused ? 'paused' : ''}"
            @click=${() => this.togglePause()}
          >
            ${this.paused ? 'Resume' : 'Pause'}
          </button>
          <button class="action" @click=${() => this.clearVisible()}>Clear</button>
        </div>
      </div>
      ${this.subCategoryFilter.has('BOOT_TRACE')
        ? html`<jf-boot-phases-panel></jf-boot-phases-panel>`
        : nothing}
      <div class="body">
        ${visible.length === 0
          ? html`<div class="empty">
              ${this.events.length === 0
                ? 'Waiting for log events…'
                : 'No events match the current filter.'}
            </div>`
          : visible.length > VIRTUALIZER_THRESHOLD
            ? this.renderVirtualizedRows(visible)
            : visible.map((e) => this.renderRow(e))}
      </div>
    `;
  }

  /**
   * For large buffers, fall back to chunked rendering so the DOM
   * doesn't get 5000 rows at once. V1.5.1 simple slice-by-cap; V1.6
   * lit-virtualizer integration for true windowing.
   */
  private renderVirtualizedRows(visible: DiagnosticEvent[]): TemplateResult {
    return html`
      ${visible.slice(0, VIRTUALIZER_THRESHOLD).map((e) => this.renderRow(e))}
      <div class="empty">
        Showing newest ${VIRTUALIZER_THRESHOLD} of ${visible.length} entries.
        Tighten the filter to see older events.
      </div>
    `;
  }

  private renderRow(e: DiagnosticEvent): TemplateResult {
    return html`
      <div class="row">
        <span class="ts">${formatTimestamp(e.timestamp)}</span>
        <span class="level ${e.level}">${e.level}</span>
        <span class="logger" title=${e.loggerName}>${e.loggerName}</span>
        <span class="msg">${e.message}</span>
      </div>
    `;
  }
}

function formatTimestamp(iso: string): string {
  // ISO-8601 → HH:MM:SS.mmm display
  const m = /T(\d{2}:\d{2}:\d{2}(?:\.\d+)?)/.exec(iso);
  return m ? m[1]! : iso;
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-log-surface')) {
  customElements.define('jf-log-surface', LogSurface);
}
