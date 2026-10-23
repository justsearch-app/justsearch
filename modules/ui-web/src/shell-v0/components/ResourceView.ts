// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a.1.9 §A.6 — `<jf-resource-view>` substrate consumer.
 *
 * The single FE consumer for any catalog-declared Resource. Reads the
 * Resource entry from `ResourceCatalogClient`, opens the appropriate
 * subscription strategy, mounts the dispatched renderer, and binds
 * the strategy's state to the renderer's reactive properties.
 *
 * Adding a new Resource on the backend = no new FE code: declare the
 * catalog entry, the FE picks it up at the next `bootResourceCatalog`
 * call and mounts via this element.
 *
 * Usage:
 *   <jf-resource-view resource-id="core.indexing-jobs"></jf-resource-view>
 *
 * The element opens an `EnvelopeStream` against the Resource's
 * declared `endpoint` and applies the Category-specific reducer
 * from `subscriptionStrategy.ts`.
 *
 * For TABULAR Resources with non-empty `itemOperations`, the element
 * also wires a per-row `<jf-row-actions>` renderer into the
 * dispatched table tag (slice 3a.1.9 §A.7).
 *
 * Renderer-prop binding: per slice 3a.1.9 §A.4a's renderer contract,
 * the consumer binds Category-specific props on the dispatched tag.
 * V1 covers TABULAR (proof case) + EVENT_STREAM/HISTORY (table-shaped
 * via specialty-renderer registration in Phase 7); STATE / TIMESERIES
 * fall through to a "Renderer not yet wired" placeholder until a
 * slice adds the binding. (LOG_TAIL retired in slice 448 phase 6 —
 * operator-trace surfaces use the sibling DiagnosticChannel primitive.)
 */

import { subscribeAiState } from '../state/aiStateStore.js';
import { indexingQueueCounts } from '../state/indexingQueueCounts.js';
import { formatCount } from '../display/format.js';
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import type { JsonSchema } from '@jsonforms/core';
import { subscribePooled } from '../streaming/EnvelopeStreamPool.js';
import {
  hasStrategyFor,
  strategyFor,
  type SubscriptionStrategy,
  type StrategyState,
  type TabularData,
  type EventStreamData,
  type HistoryData,
} from '../strategies/subscriptionStrategy.js';
import {
  dispatchResourceRenderer,
  isCategorySupported,
} from '../renderers/resourceRegistry.js';
import { fetchSchema } from '../../api/registry/schemaFetcher.js';
import {
  getResource,
  listResources,
  onCatalogChange,
} from '../../api/registry/ResourceCatalogClient.js';
import { present } from '../display/present.js';
// Allowlisted in eslint.config.js — see 511-followup-B. ResourceView
// is substrate-adjacent (renders detail of a single Resource); the
// `<jf-resource>` aggregate consumes the same shape.
import type { Resource } from '../../api/types/registry.js';
import { formatRelativeIso } from '../../utils/relativeTime.js';
import type { TableColumn } from './Table.js';
import './Table.js';
import './StatusCard.js';
import './RowActions.js';

type ConnectionState = 'idle' | 'connecting' | 'connected' | 'error';

function deriveOperationLabel(operationId: string): string {
  // §2.A: the operation-label logic lives in the one display projector.
  return present({ kind: 'operation', id: operationId }).label;
}

function formatDuration(startIso: unknown, endIso: unknown): string {
  if (typeof startIso !== 'string' || typeof endIso !== 'string') return '—';
  const s = Date.parse(startIso);
  const e = Date.parse(endIso);
  if (Number.isNaN(s) || Number.isNaN(e)) return '—';
  const ms = e - s;
  if (ms < 0) return '—';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

const operationHistoryColumns: TableColumn[] = [
  {
    id: 'operationId',
    header: 'Operation',
    accessor: (row) => row.operationId,
    format: (v) => deriveOperationLabel(String(v ?? '')),
  },
  {
    id: 'outcome',
    header: 'Outcome',
    accessor: (row) => row.outcome,
  },
  {
    id: 'details',
    header: 'Details',
    accessor: (row) => row,
    sortable: false,
    format: (value) => {
      const row = value as Record<string, unknown>;
      return html`<span style="white-space: normal; overflow-wrap: anywhere">
        ${row.message ?? ''}
        ${row.diagnosticsLink && !row.message ? html`<small style="display: block">Code: ${row.diagnosticsLink}</small>` : ''}
      </span>`;
    },
  },
  {
    id: 'startTime',
    header: 'When',
    accessor: (row) => row.startTime,
    format: (v) => formatRelativeIso(String(v ?? '')),
  },
  {
    id: 'duration',
    header: 'Duration',
    accessor: (row) => formatDuration(row.startTime, row.endTime),
  },
  {
    id: 'provenance',
    header: 'Source',
    accessor: (row) => row.provenance,
    format: (v) => {
      const p = v as Record<string, unknown> | null;
      return p?.transport ? String(p.transport) : '—';
    },
  },
];

export class ResourceView extends JfElement {
  static override properties = {
    resourceId: { type: String, attribute: 'resource-id' },
    apiBase: { type: String, attribute: 'api-base' },
    paneId: { type: String, attribute: 'pane-id' },
    state: { state: true },
    schema: { state: true },
    connectionState: { state: true },
    error: { state: true },
    queueCounts: { state: true },
    queueCountsLive: { state: true },
  } as const;

  declare resourceId: string;
  declare apiBase: string;
  declare paneId: string;
  declare state: StrategyState<unknown> | null;
  declare schema: JsonSchema | null;
  declare connectionState: ConnectionState;
  declare error: string | null;

  private declare queueCountsLive: boolean;
  private declare queueCounts: ReturnType<typeof indexingQueueCounts>;
  private stopQueueStatus: (() => void) | null = null;
  private resource: Resource | null = null;
  private strategy: SubscriptionStrategy<unknown> | null = null;
  private streamUnsubscribe: (() => void) | null = null;
  /**
   * Catalog-arrival listener for the first-visit boot race (slice
   * 3a.2 closure §B.B.E.1). Set when bind() finds the catalog empty
   * and waits for `bootResourceRegistry` to populate it; cleared on
   * fire or in unbind().
   */
  private catalogChangeUnsubscribe: (() => void) | null = null;
  /** Resource id currently bound to (matched against this.resourceId for idempotency). */
  private boundResourceId: string = '';

  constructor() {
    super();
    this.queueCounts = null;
    this.queueCountsLive = false;
    this.resourceId = '';
    this.apiBase = '';
    this.paneId = '';
    this.state = null;
    this.schema = null;
    this.connectionState = 'idle';
    this.error = null;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    void this.bind();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unbind();
  }

  override updated(changed: Map<string, unknown>): void {
    if (
      changed.has('resourceId') &&
      this.isConnected &&
      this.resourceId !== this.boundResourceId
    ) {
      this.unbind();
      void this.bind();
    }
  }

  /**
   * Tags of bespoke specialty renderers that consume their own data
   * (e.g., open their own SSE streams) rather than accepting the
   * substrate's strategy-derived state. The substrate accommodates them
   * by mounting the tag with `api-base` and skipping the strategy/stream.
   *
   * Slice 3a.1.9 §A.8 #2 + §B.B.D Stream-D substitute: HealthLitView
   * is the canonical example — its snapshot wire shape
   * `{conditions, occurrences}` doesn't fit the EVENT_STREAM strategy.
   */
  private static readonly BESPOKE_SPECIALTY_TAGS: ReadonlySet<string> =
    new Set(['jf-health-view']);

  private async bind(): Promise<void> {
    if (this.boundResourceId === this.resourceId && this.resourceId) {
      // Idempotent: already bound to this resource id.
      return;
    }
    if (!this.resourceId) {
      this.error = 'No resource-id specified';
      this.connectionState = 'error';
      return;
    }
    this.boundResourceId = this.resourceId;
    const resource = getResource(this.resourceId);
    if (!resource) {
      // First-visit catalog-boot race (slice 3a.2 §B.B.E.1): the
      // catalog client boots asynchronously from `i18n.ts` at app
      // startup, but a `<jf-resource-view>` mounted before the boot
      // HTTP fetch resolves will see an empty catalog. localStorage
      // hides this on second-and-subsequent loads; first-install
      // users see the "Resource not found" error path otherwise.
      // If the catalog is empty (boot still in flight), wait for the
      // arrival event and re-bind once. If the catalog has entries
      // but our id isn't among them, that's a true not-found.
      if (listResources().length === 0) {
        this.connectionState = 'connecting';
        this.catalogChangeUnsubscribe = onCatalogChange(() => {
          if (this.catalogChangeUnsubscribe) {
            this.catalogChangeUnsubscribe();
            this.catalogChangeUnsubscribe = null;
          }
          // Reset boundResourceId so the second bind() doesn't
          // short-circuit on the idempotency guard.
          this.boundResourceId = '';
          void this.bind();
        });
        return;
      }
      this.error = `Resource not found in catalog: ${this.resourceId}`;
      this.connectionState = 'error';
      return;
    }
    this.resource = resource;
    if (resource.id === 'core.indexing-jobs') {
      this.stopQueueStatus = subscribeAiState((s) => {
        this.queueCounts = indexingQueueCounts(s.status);
        this.queueCountsLive = s.snapshotLive;
      });
    }

    // Bespoke specialty short-circuit (slice 3a.1.9 §A.8 #2): if the
    // dispatched renderer for this Resource is a bespoke specialty that
    // consumes its own data, skip the strategy/stream wiring and mount
    // the tag directly. The renderer is responsible for its own SSE /
    // REST consumption; the substrate just hands it an api-base.
    const dispatchedTag = dispatchResourceRenderer({
      category: resource.category,
      hint: resource.kind,
    });
    if (dispatchedTag && ResourceView.BESPOKE_SPECIALTY_TAGS.has(dispatchedTag)) {
      this.connectionState = 'connected';
      return;
    }

    if (!hasStrategyFor(resource.category, resource.subscriptionMode)) {
      this.error = `No subscription strategy registered for ${resource.category} × ${resource.subscriptionMode}`;
      this.connectionState = 'error';
      return;
    }

    let strategy: SubscriptionStrategy<unknown> | null = null;
    try {
      strategy = strategyFor(resource);
    } catch (err) {
      this.error = `Strategy initialization failed: ${(err as Error).message}`;
      this.connectionState = 'error';
      return;
    }
    if (!strategy) {
      this.error = 'Strategy returned null';
      this.connectionState = 'error';
      return;
    }
    this.strategy = strategy;
    this.state = strategy.initialState;
    this.connectionState = 'connecting';

    // Lazy-fetch the schema in parallel with stream open. Either may resolve
    // first; renderer reads `schema` reactive-property when ready.
    if (resource.schema) {
      void fetchSchema(resource.schema).then((s) => {
        this.schema = s;
      });
    }

    // Open the SSE stream OR call fetchSnapshot per the strategy.
    if (resource.subscriptionMode === 'SSE_STREAM') {
      // Pooled (tempdoc 543 residue #3): subscribe through EnvelopeStreamPool
      // keyed by the resource endpoint, so multiple consumers of the same
      // stream (e.g. this view + the indexing-jobs Task-tray bridge) share one
      // EventSource. The pool's returned unsubscribe handles refcount + the
      // last-release stop, so we no longer hold the stream directly.
      const streamUrl = composeEndpointUrl(this.apiBase, resource.endpoint);
      this.streamUnsubscribe = subscribePooled<StrategyState<unknown>>(
        streamUrl,
        (snap) => {
          this.state = snap.payload;
          this.connectionState = snap.isConnected ? 'connected' : 'connecting';
        },
        () => ({
          url: streamUrl,
          initialState: strategy.initialState,
          reducer: strategy.reducer,
        }),
      );
    } else if (
      resource.subscriptionMode === 'ONE_SHOT' &&
      strategy.fetchSnapshot
    ) {
      // Slice 3a.1.9 §B.B.D Stream A — REST snapshot, no streaming. The
      // strategy's fetchSnapshot returns a hydrated state directly.
      const baseUrl =
        this.apiBase ||
        (typeof globalThis !== 'undefined' &&
        (globalThis as { location?: { origin?: string } }).location?.origin
          ? (globalThis as { location: { origin: string } }).location.origin
          : '');
      strategy
        .fetchSnapshot(baseUrl)
        .then((s) => {
          this.state = s;
          this.connectionState = 'connected';
        })
        .catch((err) => {
          this.error = `Snapshot fetch failed: ${(err as Error).message}`;
          this.connectionState = 'error';
        });
    }
  }

  private unbind(): void {
    this.stopQueueStatus?.();
    this.stopQueueStatus = null;
    this.queueCounts = null;
    this.queueCountsLive = false;
    if (this.catalogChangeUnsubscribe) {
      this.catalogChangeUnsubscribe();
      this.catalogChangeUnsubscribe = null;
    }
    if (this.streamUnsubscribe) {
      // Pooled unsubscribe: decrements the refcount and stops the shared
      // stream on last release (tempdoc 543 residue #3).
      this.streamUnsubscribe();
      this.streamUnsubscribe = null;
    }
    this.strategy = null;
    this.resource = null;
    this.state = null;
    this.schema = null;
    this.error = null;
    this.boundResourceId = '';
  }

  static styles = css`
    :host {
      display: block;
      box-sizing: border-box;
      width: 100%;
      height: 100%;
      font-family: inherit;
    }
    .header {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.5rem 0.75rem;
      border-bottom: 1px solid var(--justsearch-shell-table-row-border, #eee);
    }
    .header h2 {
      margin: 0;
      font-size: var(--font-size-md);
      font-weight: 600;
    }
    .conn-badge {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.125rem 0.5rem;
      border-radius: 4px;
      font-size: var(--font-size-xs);
      background: var(--justsearch-shell-status-badge-bg, #f3f3f3);
    }
    .conn-badge[data-state='error'] {
      background: var(--justsearch-shell-status-severity-error, #c00);
      color: white;
    }
    .body {
      flex: 1 1 auto;
      overflow: auto;
    }
    .placeholder {
      padding: 1rem;
      color: var(--justsearch-shell-table-empty-color, #888);
      font-style: italic;
    }
    .empty-activity {
      padding: 2rem 1rem;
      text-align: center;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
  `;

  override render(): TemplateResult {
    if (this.connectionState === 'error') {
      return html`<div class="placeholder">${this.error}</div>`;
    }
    if (!this.resource || !this.strategy) {
      return html`<div class="placeholder">Loading…</div>`;
    }
    return html`
      <div class="header">
        <h2>${present({ kind: 'resource', key: this.resource.presentation.labelKey }).label}</h2>
        <span class="conn-badge" data-state=${this.connectionState}>
          ${this.connectionState}
        </span>
      </div>
      ${this.resourceId === 'core.indexing-jobs' ? html`
        <p data-testid="indexing-window-notice">
          Live window: active files and up to 200 recent failures. Queue totals include files outside this view.
        </p>
        <p data-testid="indexing-queue-totals">${this.queueCounts
          ? `${this.queueCountsLive ? "" : "Last reported: "}${formatCount(this.queueCounts.queued)} queued · ${formatCount(this.queueCounts.processing)} processing · ${formatCount(this.queueCounts.failed)} failed`
          : 'Queue totals unavailable'}</p>
      ` : nothing}
      <div class="body">${this.renderRenderer()}</div>
    `;
  }

  private renderRenderer(): TemplateResult | typeof nothing {
    const resource = this.resource;
    if (!resource) return nothing;
    // Pass `hint: resource.kind` so specialty registrations (e.g.,
    // EVENT_STREAM × kind=operation-history → jf-table for Ledger
    // migration) dispatch ahead of the Category default.
    const tag = dispatchResourceRenderer({
      category: resource.category,
      hint: resource.kind,
    });
    if (!tag && !isCategorySupported(resource.category)) {
      return html`<div class="placeholder">
        Category ${resource.category} not yet implemented.
      </div>`;
    }
    if (!tag) {
      return html`<div class="placeholder">
        No renderer registered for ${resource.category}.
      </div>`;
    }

    // Bespoke specialty path (slice 3a.1.9 §A.8 #2): the renderer
    // consumes its own data via api-base. The substrate just mounts it.
    if (ResourceView.BESPOKE_SPECIALTY_TAGS.has(tag)) {
      return this.renderBespokeSpecialty(tag);
    }

    // Strategy-driven renderers require state.
    const state = this.state;
    if (!state) return nothing;

    switch (resource.category) {
      case 'TABULAR':
        return this.renderTabular(tag, state.data as TabularData);
      case 'EVENT_STREAM':
        return this.renderEventOrHistory(
          tag,
          (state.data as EventStreamData).events,
        );
      case 'HISTORY':
        return this.renderEventOrHistory(
          tag,
          (state.data as HistoryData).entries,
        );
      default:
        return html`<div class="placeholder">
          Renderer binding for ${resource.category} not yet wired in
          &lt;jf-resource-view&gt; (V1 covers TABULAR / EVENT_STREAM /
          HISTORY).
        </div>`;
    }
  }

  /**
   * Mount a bespoke specialty renderer (slice 3a.1.9 §A.8 #2) — the
   * renderer consumes its own data via `api-base`; the substrate's
   * strategy-derived state is irrelevant.
   *
   * V1 bespoke tags: `jf-health-view`. The substrate accommodates the
   * existing HealthLitView's own SSE consumption rather than forcing
   * decomposition of `core.health-events` into two STATE/HISTORY
   * Resources.
   *
   * Future bespoke renderers register via {@link
   * ResourceView.BESPOKE_SPECIALTY_TAGS} and the resourceRegistry's
   * specialty-renderer dispatch path.
   */
  private renderBespokeSpecialty(tag: string): TemplateResult {
    const apiBase = this.apiBase ?? '';
    if (tag === 'jf-health-view') {
      return html`<jf-health-view api-base=${apiBase}></jf-health-view>`;
    }
    return html`<div class="placeholder">
      Bespoke specialty renderer ${tag} declared but not bound by
      &lt;jf-resource-view&gt; V1.
    </div>`;
  }

  private renderTabular(
    tag: string,
    data: TabularData,
  ): TemplateResult {
    const resource = this.resource!;
    const items = Array.from(data.items.values());
    const hasItemOps = resource.itemOperations.length > 0;
    if (tag === 'jf-table') {
      return html`<jf-table
        .schema=${this.schema}
        .data=${items}
        .userConfig=${undefined}
        pane-id=${this.paneId}
        primary-key=${data.primaryKey}
        .rowActionsRenderer=${hasItemOps
          ? (row: Record<string, unknown>, rowKey: string) =>
              html`<jf-row-actions
                resource-id=${resource.id}
                row-key=${rowKey}
                .row=${row}
                api-base=${this.apiBase}
              ></jf-row-actions>`
          : undefined}
      ></jf-table>`;
    }
    // Plugin-supplied alternative renderers must accept `items`,
    // `schema`, `primaryKey`, and `rowActionsRenderer` per §A.4a.
    return html`<div class="placeholder">
      Plugin renderer ${tag} not bound by &lt;jf-resource-view&gt; V1.
    </div>`;
  }

  private renderEventOrHistory(
    tag: string,
    rows: unknown[],
  ): TemplateResult {
    if (tag === 'jf-table') {
      const typedRows = rows as Record<string, unknown>[];

      if (this.resourceId === 'core.operation-history') {
        if (typedRows.length === 0) {
          return html`<div class="empty-activity">
            No operations recorded yet. Activity appears here as you interact with JustSearch.
          </div>`;
        }
        return html`<jf-table
          .columns=${operationHistoryColumns}
          .data=${typedRows}
          pane-id=${this.paneId}
        ></jf-table>`;
      }

      const tableSchema =
        this.schema && (this.schema as { type?: string }).type === 'object'
          ? ({ type: 'array', items: this.schema } as JsonSchema)
          : this.schema;
      return html`<jf-table
        .schema=${tableSchema}
        .data=${typedRows}
        pane-id=${this.paneId}
      ></jf-table>`;
    }
    if (tag === 'jf-status-card') {
      // EVENT_STREAM / HISTORY default placeholder (slice 3a.1.4
      // §B.6): render a minimal summary card. Slice 444c / 446 will
      // ship dedicated jf-event-stream-list / jf-log-surface renderers.
      const count = rows.length;
      return html`<jf-status-card
        severity="info"
        subject=${present({ kind: 'resource', key: this.resource!.presentation.labelKey }).label}
        reason=${`${count} entries`}
        details=""
      ></jf-status-card>`;
    }
    return html`<div class="placeholder">
      Plugin renderer ${tag} not bound by &lt;jf-resource-view&gt; V1.
    </div>`;
  }
}

function composeEndpointUrl(apiBase: string | null | undefined, endpoint: string): string {
  if (/^https?:\/\//i.test(endpoint)) return endpoint;
  const base = (apiBase ?? '').replace(/\/$/, '');
  return base ? `${base}${endpoint}` : endpoint;
}

customElements.define('jf-resource-view', ResourceView);
