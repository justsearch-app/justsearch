// SPDX-License-Identifier: Apache-2.0
/**
 * HealthLitView — first Lit-side product surface (slice 3a.2).
 *
 * Subscribes to `/api/health/events/stream` via the Phase 2
 * `EnvelopeStream` substrate. Renders one `<jf-status-card>` per
 * Condition + a list of recent Occurrences. Mirrors the
 * substantive part of the React `HealthView` (Conditions section);
 * action buttons + KPI cards are out of scope for this slice and
 * stay React for now.
 *
 * Why a self-contained Lit element rather than a Lit shell with
 * many panes: the substrate's value-add is the streamed-Conditions
 * surface; one pane is enough to prove the substrate works
 * end-to-end against a real backend. Future slices add more panes
 * (action buttons → ActionButton; KPI cards → StatusCard variants;
 * etc.) and split this view across them.
 */

import { formatTime } from '../display/format.js';
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { EnvelopeStream } from '../streaming/EnvelopeStream.js';
import type { SseEnvelope } from '../streaming/envelope-types.js';
import '../components/StatusCard.js';
import '../components/StatusDot.js';
// Slice 3a.1.4 Phase 7: side-effect import of the Resource-view defaults so
// dispatchResourceRenderer resolves to a registered custom-element tag at runtime.
import {
  dispatchResourceRenderer,
  isCategorySupported,
} from '../renderers/resourceRegistry.js';
// NOTE: do NOT import '../renderers/resourceRegistryDefaults.js' here
// (tempdoc 530 UI-cycle gate). That module imports HealthLitView to register
// <jf-health-view>, so importing it back formed a cycle. The defaults load
// app-wide at boot via the shell-v0 barrel (index.ts), before any view
// renders, so the dispatch tags are registered by the time this view mounts.
// Slice 3a.1.4b: resolve MetricRef.label i18n keys at the consumer (replaces the raw-key
// passthrough surfaced by §B.K). The catalog is fetched at app boot via i18n.ts; missing
// keys fall back to the raw key (defensive, mirrors errorCatalog.ts contract).
import { present } from '../display/present.js';
// Tempdoc 604 — the connection badge's REACHABILITY verdict comes from the cheap /api/status poll
// (the one liveness authority), NOT from the SSE channel's own up/down. A wedged enrichment channel
// must not read as "backend down" while the poll says reachable.
import { subscribeAiState, getAiState } from '../state/aiStateStore.js';
import type { NoticeTone } from '../utils/statusTone.js';
// Slice 447 §X.11.5 Phase 6: plugin recovery-overlay client. Overlays take precedence
// over the backend-declared core recovery for the matched (conditionId, subject) pair.
import { getOverlayRecovery } from '../../api/registry/RecoveryOverlayClient.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import type {
  TimeseriesSnapshot,
  MetricRef,
} from '../../api/generated/index.js';
import type {
  AssertedConditionBody,
  HealthEvent,
  ThresholdStateBody,
  LifecycleEventBody,
} from '../../api/domains/health.js';

interface HealthStreamState {
  /** Map from condition id (event.id) to current condition or threshold event. */
  conditions: Map<string, HealthEvent>;
  /** Recent lifecycle occurrences in chronological order (most recent at end). */
  occurrences: HealthEvent[];
  /** Latest seq seen (catalog version). */
  catalogVersion: number;
}

const EMPTY_STATE: HealthStreamState = {
  conditions: new Map(),
  occurrences: [],
  catalogVersion: 0,
};

const OCCURRENCE_BUFFER_CAP = 50;

interface UpdatePayload {
  kind:
    | 'condition-added'
    | 'condition-modified'
    | 'condition-removed'
    | 'occurrence-appended';
  event: HealthEvent;
}

interface LifecyclePayload {
  kind: 'connected' | 'snapshot' | 'heartbeat' | 'reset' | 'closing';
  conditions?: HealthEvent[];
  occurrences?: HealthEvent[];
}

const healthReducer = (
  state: HealthStreamState,
  envelope: SseEnvelope,
): HealthStreamState => {
  if (envelope.frameKind === 'UPDATE') {
    const p = envelope.payload as UpdatePayload;
    const next = { ...state, catalogVersion: envelope.seq };
    if (p.kind === 'condition-added' || p.kind === 'condition-modified') {
      const updated = new Map(state.conditions);
      if (p.event.id) updated.set(p.event.id, p.event);
      next.conditions = updated;
    } else if (p.kind === 'condition-removed') {
      const updated = new Map(state.conditions);
      if (p.event.id) updated.delete(p.event.id);
      next.conditions = updated;
    } else if (p.kind === 'occurrence-appended') {
      const occurrences = [...state.occurrences, p.event];
      if (occurrences.length > OCCURRENCE_BUFFER_CAP) {
        occurrences.splice(0, occurrences.length - OCCURRENCE_BUFFER_CAP);
      }
      next.occurrences = occurrences;
    }
    return next;
  }
  // LIFECYCLE
  const p = envelope.payload as LifecyclePayload;
  if (p.kind === 'snapshot') {
    const conditions = new Map<string, HealthEvent>();
    for (const e of p.conditions ?? []) {
      if (e.id) conditions.set(e.id, e);
    }
    const occurrences = (p.occurrences ?? []).slice(-OCCURRENCE_BUFFER_CAP);
    return {
      conditions,
      occurrences,
      catalogVersion: envelope.seq,
    };
  }
  if (p.kind === 'reset') {
    return EMPTY_STATE;
  }
  // 'connected', 'heartbeat', 'closing': no state change beyond catalog version
  return { ...state, catalogVersion: envelope.seq };
};

/**
 * Tempdoc 604 — how long the SSE channel must stay down before the badge says "live updates paused".
 * Shorter than EnvelopeStream's first reconnect backoff so a genuine outage still shows promptly, but
 * long enough that a fast native/owned reconnect doesn't flicker the badge. Presentation timing only
 * (not the producer/consumer-coupled liveness window).
 */
const DISCONNECT_DEBOUNCE_MS = 750;

export class HealthLitView extends JfElement {
  static override properties = {
    apiBase: { type: String, attribute: 'api-base' },
    streamState: { state: true },
    isConnected: { state: true },
    liveUpdatesPaused: { state: true },
    backendReachable: { state: true },
    metricSnapshots: { state: true },
    recommendedActions: { state: true },
  } as const;

  declare apiBase: string;
  declare streamState: HealthStreamState;
  /** Raw SSE channel state (the live-updates channel), distinct from backend reachability. */
  declare isConnected: boolean;
  /**
   * Tempdoc 604 — debounced "the SSE channel is down". A sub-second blip while the channel
   * self-heals (EnvelopeStream reconnect) does NOT flip this, so the badge doesn't flicker.
   */
  declare liveUpdatesPaused: boolean;
  /** Tempdoc 604 — is the backend reachable per the /api/status poll (the one liveness authority)? */
  declare backendReachable: boolean;
  /**
   * Slice 3a.1.4 Phase 7: lazy-fetched cache of TimeseriesSnapshot keyed by Resource id.
   * Reads on-demand when a Condition's body declares {@code relatedMetrics}; the FE
   * iterates the list, dispatches via the Resource-view registry, and binds the cached
   * snapshot to the resulting renderer. State property so a Map mutation triggers
   * re-render via {@link #requestUpdate}.
   */
  declare metricSnapshots: Map<string, TimeseriesSnapshot>;
  /**
   * Slice 447 §X.11.5 Phase 4: condition → recommended Operation lookup. Populated from
   * the `core.condition-recovery-index` Resource (REST snapshot at mount; SSE-stream
   * subscription is a future polish — current minimal consumer fetches once per mount).
   * Key shape: {@code `${conditionId}|${subject}`}. Value: OperationRef string id.
   */
  declare recommendedActions: Map<string, string>;

  private stream: EnvelopeStream<HealthStreamState> | null = null;
  private unsubscribe: (() => void) | null = null;
  private unsubAiState: (() => void) | null = null;
  /** Debounce timer for flipping `liveUpdatesPaused` true (tempdoc 604). */
  private connDebounceTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly metricFetchInflight = new Set<string>();

  constructor() {
    super();
    this.apiBase = '';
    this.streamState = EMPTY_STATE;
    this.isConnected = false;
    this.liveUpdatesPaused = false;
    this.backendReachable = true;
    this.metricSnapshots = new Map();
    this.recommendedActions = new Map();
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.backendReachable = getAiState().connection.reachable;
    this.unsubAiState = subscribeAiState((s) => {
      this.backendReachable = s.connection.reachable;
    });
    if (this.apiBase) {
      this.startStream();
      this.fetchRecoveryIndex();
    }
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.stopStream();
    if (this.unsubAiState) {
      this.unsubAiState();
      this.unsubAiState = null;
    }
    if (this.connDebounceTimer !== null) {
      clearTimeout(this.connDebounceTimer);
      this.connDebounceTimer = null;
    }
  }

  /**
   * Tempdoc 604 — route the SSE channel's up/down through a debounce so a sub-second blip while
   * EnvelopeStream self-heals does not flicker the badge. A reconnect within the grace window keeps
   * the badge "connected"; only a sustained outage flips `liveUpdatesPaused`.
   */
  private updateConnDisplay(connected: boolean): void {
    this.isConnected = connected;
    if (connected) {
      if (this.connDebounceTimer !== null) {
        clearTimeout(this.connDebounceTimer);
        this.connDebounceTimer = null;
      }
      this.liveUpdatesPaused = false;
      return;
    }
    if (this.connDebounceTimer === null && !this.liveUpdatesPaused) {
      this.connDebounceTimer = setTimeout(() => {
        this.connDebounceTimer = null;
        this.liveUpdatesPaused = true;
      }, DISCONNECT_DEBOUNCE_MS);
    }
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('apiBase')) {
      this.stopStream();
      if (this.apiBase) {
        this.startStream();
        this.fetchRecoveryIndex();
      }
    }
  }

  private startStream(): void {
    const url = `${this.apiBase.replace(/\/$/, '')}/api/health/events/stream`;
    this.stream = new EnvelopeStream<HealthStreamState>({
      url,
      initialState: EMPTY_STATE,
      reducer: healthReducer,
    });
    this.unsubscribe = this.stream.subscribe((snap) => {
      this.streamState = snap.payload;
      this.updateConnDisplay(snap.isConnected);
    });
    this.stream.start();
  }

  private stopStream(): void {
    if (this.unsubscribe) {
      this.unsubscribe();
      this.unsubscribe = null;
    }
    if (this.stream) {
      this.stream.stop();
      this.stream = null;
    }
  }

  /**
   * Slice 447 §X.11.5 Phase 4: fetch the {@code core.condition-recovery-index} REST
   * snapshot once on mount and rebuild the recommendedActions lookup. SSE-stream
   * subscription with replace-on-change is a future polish; the snapshot fetch is
   * sufficient for the named Health-surface consumer to render recommended-action
   * indicators.
   */
  private fetchRecoveryIndex(): void {
    authorizedFetch('/api/condition-recovery-index')
      .then((r) => (r.ok ? r.json() : null))
      .then((index) => {
        if (!index || typeof index !== 'object') return;
        const entries = (index as { entries?: Array<{ target?: string; conditions?: Array<{ conditionId?: string; subject?: string }> }> }).entries ?? [];
        const next = new Map<string, string>();
        for (const entry of entries) {
          const target = entry.target ?? '';
          const conds = entry.conditions ?? [];
          for (const c of conds) {
            const cid = c.conditionId ?? '';
            const subj = c.subject ?? '';
            if (cid && target) next.set(`${cid}|${subj}`, target);
          }
        }
        this.recommendedActions = next;
        this.requestUpdate();
      })
      .catch(() => {
        // Silent failure — the panel just doesn't render recommended actions.
        // The condition list itself stays correct.
      });
  }

  /**
   * Tempdoc 604 — the connection badge. Three states, with REACHABILITY owned by the poll, not the
   * SSE channel: (1) channel up (or within the debounce grace) → "Connected"; (2) channel down but
   * the backend is reachable per the poll → "Live updates paused — reconnecting…" (a secondary hint,
   * NOT a hard outage — the stream self-heals); (3) channel down AND backend unreachable →
   * "Disconnected". This stops a wedged enrichment channel from reading as "backend down".
   */
  private renderConnBadge(): TemplateResult {
    const channelUp = this.isConnected || !this.liveUpdatesPaused;
    let tone: NoticeTone;
    let label: string;
    if (channelUp) {
      tone = 'info';
      label = 'Connected';
    } else if (this.backendReachable) {
      tone = 'warning';
      label = 'Live updates paused — reconnecting…';
    } else {
      tone = 'error';
      label = 'Disconnected';
    }
    return html`
      <span class="conn-badge">
        <jf-status-dot tone=${tone} label=${label}></jf-status-dot>
        ${label}
      </span>
    `;
  }

  static styles = css`
    :host {
      display: block;
      padding: 1rem;
      box-sizing: border-box;
      height: 100%;
      overflow: auto;
      font-family: inherit;
    }
    .header {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      margin-block-end: 1rem;
    }
    .header h2 {
      margin: 0;
      font-size: var(--font-size-lg);
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
    .conditions {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      margin-block-end: 1.5rem;
    }
    .empty {
      padding: 1rem;
      color: var(--justsearch-shell-table-empty-color, #888);
      font-style: italic;
    }
    h3 {
      margin: 0 0 0.5rem 0;
      font-size: var(--font-size-sm);
      font-weight: 600;
      color: var(--justsearch-shell-form-label-color);
    }
    .occurrences {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }
    .occurrence {
      display: grid;
      grid-template-columns: auto 1fr auto;
      gap: 0.5rem;
      padding: 0.25rem 0.5rem;
      font-size: var(--font-size-sm);
      border-bottom: 1px solid
        var(--justsearch-shell-table-row-border, #eee);
    }
    .occurrence-severity {
      font-size: var(--font-size-xs);
      padding: 0 0.25rem;
      border-radius: 2px;
      align-self: center;
    }
    .occurrence-severity[data-severity='INFO'] {
      background: var(--justsearch-shell-status-severity-info, #3a8dde);
      color: white;
    }
    .occurrence-severity[data-severity='WARNING'] {
      background: var(--justsearch-shell-status-severity-warning, #d97706);
      color: white;
    }
    .occurrence-severity[data-severity='ERROR'] {
      background: var(--justsearch-shell-status-severity-error, #c00);
      color: white;
    }
    .occurrence-time {
      color: var(--justsearch-shell-status-details-color, #555);
      font-variant-numeric: tabular-nums;
    }
    /* Slice 3a.1.4 Phase 7: relatedMetrics inline rendering. */
    .condition-row {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }
    .related-metrics {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      padding-left: 1rem;
      font-size: var(--font-size-xs);
      color: var(--justsearch-shell-status-details-color, #555);
    }
    .metric-loading,
    .metric-unsupported {
      font-style: italic;
      opacity: 0.7;
    }
  `;

  override render(): TemplateResult {
    const conditionCount = this.streamState.conditions.size;
    const conditionEntries = Array.from(
      this.streamState.conditions.values(),
    );

    return html`
      <div class="header">
        <h2>System Health</h2>
        ${this.renderConnBadge()}
        <span class="conn-badge">
          catalog v${this.streamState.catalogVersion}
        </span>
      </div>

      <h3>Conditions (${conditionCount})</h3>
      <div class="conditions">
        ${conditionCount === 0
          ? html`<div class="empty">No active conditions.</div>`
          : conditionEntries.map((event) => this.renderCondition(event))}
      </div>

      <h3>Recent occurrences (${this.streamState.occurrences.length})</h3>
      <div class="occurrences">
        ${this.streamState.occurrences.length === 0
          ? html`<div class="empty">No occurrences yet.</div>`
          : [...this.streamState.occurrences]
              .reverse()
              .slice(0, 10)
              .map((event) => this.renderOccurrence(event))}
      </div>
    `;
  }

  private renderCondition(event: HealthEvent): TemplateResult {
    let subject = event.id ?? '';
    let reason = '';
    let details = '';
    let relatedMetrics: MetricRef[] = [];
    if (event.body?.kind === 'condition') {
      const body = event.body as AssertedConditionBody;
      subject = body.subject || subject;
      reason = body.reason || '';
      details = body.message || '';
      // Slice 3a.1.4 Phase 6/7: backend-declared metric correlation.
      relatedMetrics = (body as { relatedMetrics?: MetricRef[] }).relatedMetrics ?? [];
      // Tempdoc 600 Design B: a blind monitor (status UNKNOWN — "observation failed or not yet
      // available") is a DIAGNOSTIC observable, not an alarm. Present it with calm, legible wording
      // instead of the raw PascalCase reason code; its INFO severity keeps the status-card tone calm.
      if (body.status === 'UNKNOWN') {
        reason = 'Cannot evaluate yet';
        details = body.message || 'This monitor has no data yet.';
      }
    } else if (event.body?.kind === 'threshold') {
      const body = event.body as ThresholdStateBody;
      subject = body.subject || subject;
      reason = `${body.phase}`;
      const mag = body.magnitudes ?? {};
      const magKeys = Object.keys(mag);
      if (magKeys.length > 0) {
        details = magKeys.map((k) => `${k}=${mag[k]}`).join(', ');
      }
      if (body.message) {
        details = details ? `${body.message} (${details})` : body.message;
      }
      relatedMetrics = (body as { relatedMetrics?: MetricRef[] }).relatedMetrics ?? [];
    }
    // Slice 447 §X.11.5 Phase 6: plugin recovery overlay takes precedence over the
    // backend-declared core recovery (subject to trust-tier governance enforced by
    // RecoveryOverlayClient at merge time).
    const overlay = getOverlayRecovery(event.id ?? '', subject);
    const recommendedAction = overlay ?? this.recommendedActions.get(`${event.id ?? ''}|${subject}`);
    return html`
      <div class="condition-row">
        <jf-status-card
          severity=${event.severity}
          subject=${subject}
          reason=${reason}
          details=${details}
        ></jf-status-card>
        ${recommendedAction
          ? html`<div class="recommended-action">
              Recommended: <code>${recommendedAction}</code>
            </div>`
          : nothing}
        ${relatedMetrics.length > 0
          ? html`<div class="related-metrics">
              ${relatedMetrics.map((ref) => this.renderMetricRef(ref))}
            </div>`
          : nothing}
      </div>
    `;
  }

  /**
   * Renders a single {@link MetricRef} via the Resource-view renderer registry.
   *
   * <p>Slice 3a.1.4 Phase 7: dispatches by {@code (TIMESERIES, hint?)} to the registered
   * tag (default {@code <jf-timeseries-sparkline>}). Falls back to a placeholder when
   * the Resource isn't supported (HISTORY Category — slice 444c hasn't shipped a default
   * renderer yet) or when the hint resolves to no entry. (Slice 448 phase 6 retired
   * LOG_TAIL — operator-trace surfaces use the sibling DiagnosticChannel primitive.)
   *
   * <p>Snapshot binding: lazy-fetches via REST on first observation and caches in
   * {@link #metricSnapshots}. SSE-stream-driven live updates per metric is a Phase 7+
   * follow-up; V1 uses on-demand REST fetch (the metric's {@code /api/metrics/{id}}
   * endpoint returns the latest snapshot). The snapshot endpoint serves a stable shape
   * even before the producer accumulates samples (HTTP 200 with empty {@code values}).
   */
  private renderMetricRef(ref: MetricRef): TemplateResult | typeof nothing {
    if (!isCategorySupported('TIMESERIES')) return nothing;
    const tag = dispatchResourceRenderer({
      category: 'TIMESERIES',
      hint: ref.hint ?? undefined,
    });
    if (!tag) return nothing;
    const resourceId = ref.resourceId ?? '';
    if (!resourceId) return nothing;
    const snapshot = this.metricSnapshots.get(resourceId);
    // Slice 3a.1.4b: resolve i18n key → localized label at the consumer. ref.label is a
    // raw I18nKey string (per the MetricRef wire shape); the resourceCatalog runtime
    // consumer fetches the registry-resource catalog at app boot and resolves keys
    // synchronously here. Missing keys fall through to raw-key passthrough (defensive).
    const localizedLabel = ref.label
      ? present({ kind: 'resource', key: ref.label }).label
      : resourceId;
    if (!snapshot) {
      // Trigger lazy fetch (idempotent — guarded by metricFetchInflight).
      void this.fetchMetricSnapshot(resourceId);
      return html`<span class="metric-loading">${localizedLabel}…</span>`;
    }
    // Only the canonical SPARK renderer ships in V1. Future hint-specific renderers
    // (gauge, histogram) register additional tags; this consumer doesn't need to know.
    if (tag === 'jf-timeseries-sparkline') {
      return html`<jf-timeseries-sparkline
        .snapshot=${snapshot}
        label=${localizedLabel}
      ></jf-timeseries-sparkline>`;
    }
    // Forward-compat: an unknown tag means a plugin registered an alternative.
    // Render an empty element of that tag and rely on the plugin to bind via attributes.
    return html`<span class="metric-unsupported">${localizedLabel} (${tag})</span>`;
  }

  /**
   * Lazy-fetches the latest TimeseriesSnapshot for a metric Resource id and caches it
   * in {@link #metricSnapshots}. Idempotent: the {@link #metricFetchInflight} guard
   * prevents overlapping fetches for the same id.
   *
   * <p>The Resource id format is {@code core.<dashed-name>} but the endpoint path uses
   * dot-separated camelCase ({@code /api/metrics/worker.job_queue.depth}). The path
   * derives from the Resource catalog's declared {@code endpoint} field — currently
   * V1 supports only the canonical {@code core.metric-worker-job-queue-depth} →
   * {@code /api/metrics/worker.job_queue.depth} mapping. Future metrics extend this
   * map alongside their catalog registration.
   */
  private async fetchMetricSnapshot(resourceId: string): Promise<void> {
    if (this.metricFetchInflight.has(resourceId)) return;
    if (this.metricSnapshots.has(resourceId)) return;
    this.metricFetchInflight.add(resourceId);
    try {
      const path = mapResourceIdToSnapshotPath(resourceId);
      if (!path) return;
      const resp = await authorizedFetch(path);
      if (!resp.ok) return;
      const json = (await resp.json()) as { snapshot?: TimeseriesSnapshot };
      if (!json.snapshot) return;
      const next = new Map(this.metricSnapshots);
      next.set(resourceId, json.snapshot);
      this.metricSnapshots = next;
    } catch {
      // Swallow — fetch failure leaves the metric in the loading state. A retry on the
      // next reducer-driven re-render is acceptable; aggressive retry is out of V1 scope.
    } finally {
      this.metricFetchInflight.delete(resourceId);
    }
  }

  private renderOccurrence(event: HealthEvent): TemplateResult {
    const body = event.body as LifecycleEventBody;
    const attrs = body?.attributes ?? {};
    const detailEntries = Object.entries(attrs)
      .filter(([k]) => k !== 'subject')
      .slice(0, 3)
      .map(([k, v]) => `${k}=${String(v)}`)
      .join(', ');
    const time = event.timestamp ? formatTime(new Date(event.timestamp)) : '';
    return html`
      <div class="occurrence">
        <span class="occurrence-severity" data-severity=${event.severity}>
          ${event.severity}
        </span>
        <span>
          <strong>${event.id}</strong>
          ${detailEntries ? html`<span> — ${detailEntries}</span>` : null}
        </span>
        <span class="occurrence-time">${time}</span>
      </div>
    `;
  }
}

customElements.define('jf-health-view', HealthLitView);

/**
 * Slice 3a.1.4 Phase 7 + 3a.1.4b cohort: maps a Resource id to its snapshot endpoint
 * path. The cleanest long-term form is registry-driven derivation (read the Resource
 * entry's `endpoint` field, strip the `/stream` suffix). V1 hardcodes the four
 * concrete mappings that ship today; expand on each new TIMESERIES Resource.
 */
function mapResourceIdToSnapshotPath(resourceId: string): string | null {
  switch (resourceId) {
    case 'core.metric-worker-job-queue-depth':
      return '/api/metrics/worker.job_queue.depth';
    case 'core.metric-worker-documents-indexed-rate-per-sec':
      return '/api/metrics/worker.documents.indexed.rate_per_sec';
    case 'core.metric-gpu-utilization-percent':
      return '/api/metrics/gpu.utilization.percent';
    case 'core.metric-gpu-memory-utilization-percent':
      return '/api/metrics/gpu.memory.utilization.percent';
    default:
      return null;
  }
}
