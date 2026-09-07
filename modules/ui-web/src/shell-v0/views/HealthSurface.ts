// SPDX-License-Identifier: Apache-2.0
/**
 * HealthSurface — Lit-side Health rail surface (slice 456 phase 9, final).
 *
 * Self-mounting Surface with operational parity to React HealthView:
 * stats grid (files/size/memory/queue), connection panel, GPU panel,
 * Quick Actions (reindex / restart-worker / clear-failed-jobs /
 * export-diagnostics / bulk-reindex), failed-files panel, recent
 * events stream (consumes /api/health/events/stream via SSE).
 *
 * Polls /api/status every 5s for stats; reads worker.failure for
 * failed-job count. The HealthLitView Conditions stream from slice
 * 3a.2 is *not* wrapped here — that view stays for substrate-isolation
 * routes; HealthSurface is the production rail surface.
 *
 * Side-effect registers `<jf-health-surface>`.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { surfaceLayoutStyles } from '../primitives/surfaceLayout.js';
import '../components/OpButton.js';
import '../components/StatusBadge.js';
import '../components/Button.js';
import '../components/ErrorAlert.js';
import '../components/CapabilityMap.js';
import { parseSseBuffer } from '../../api/sse.js';
import { summarizeWireDrift } from '../../api/wireDriftTelemetry.js';
// Tempdoc 941 — the sibling FE-defect ring: which of OUR stream handlers threw, and how often.
import { summarizeStreamHandlerFailures } from '../../api/streamHandlerTelemetry.js';
import { icon } from '../components/Icon.js';
import { renderAtRestCard } from './security/atRestCard.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
// Slice 447 §X.11.5 Phase 6: plugin recovery-overlay client. Overlays take precedence
// over the backend-declared core recovery for the matched (conditionId, subject) pair.
import { getOverlayRecovery } from '../../api/registry/RecoveryOverlayClient.js';
// Tempdoc 511-followup-A: wire-shape HealthEvent + <jf-health-event> aggregate component.
import type { HealthEvent } from '../../api/generated/index.js';
import { healthEventSchema } from '../../api/domains/health.js';
import { parseWireContract } from '../../api/schemas.js';
import '../aggregate-substrate/components/JfHealthEvent.js';
// B1/B7: the observed state (status, inference, phase, readiness) comes from the
// one aiStateStore — Health no longer runs a SECOND /api/status poll. The raw
// snapshot types are the generated/poll shapes, re-exported by the store.
import {
  subscribeAiState,
  type AiState,
  type StatusSnapshot,
  type InferenceSnapshot,
  type ConnectionPhase,
  type ReadinessView,
  type EngineRealized,
} from '../state/aiStateStore.js';
// Tempdoc 662 post-implementation: the connection-budget runtime-peak signal was
// collected but shown nowhere — surfaced here alongside the other connection facts.
import { getCurrentOpenChannelCount, getPeakOpenChannelCount } from '../state/liveChannelBudget.js';
import { presentVerdict, type SystemHealthVerdict } from '../state/verdict.js';
import { formatRelativeMs } from '../../utils/relativeTime.js';
import { type Maybe, UNKNOWN } from '../state/known.js';
import { unavailableBecause } from '../state/availability.js';
import { selectIndexingProgress } from '../state/indexingProgress.js';
import {
  ENRICHMENT_BLOCKED_LABEL,
  ENRICHMENT_IN_PROGRESS_LABEL,
} from '../state/enrichmentCoverage.js';
import { present } from '../display/present.js';
import { projectFact } from '../display/facts.js';
import { formatBytes, formatCount } from '../display/format.js';
// 569 §15 — co-project the liveness + overflow facets on a REAL surface (the 4th surface).
import { activeBodyFor, subscribePresentation } from '../state/presentationRuntime.js';
import { HEALTH_STATUS_REGION, HEALTH_STATS_REGION } from '../themes/builtinPresentations.js';
import { getSurface } from '../../api/registry/SurfaceCatalogClient.js';
import '../components/DeclaredSurface.js';
// Tempdoc 578 Workstream A — "Now" folded into Health's top as a compact live-strip (the standalone
// core.system-self-view RAIL surface is retired). Import directly so the element is defined when embedded.
import './SystemSelfView.js';

// QueueDbStatus interface elided — derived inline from status response.

interface FailedJob {
  path?: string;
  pathHash?: string;
  errorMessage?: string;
  attempts?: number;
  lastUpdatedMs?: number;
}

// Tempdoc 511-followup-A: HealthEvent type now comes from generated wire-types.ts.
// The prior local interface (`title/message/level/timestamp:number`) was
// structurally incompatible with the actual SSE payload
// (`severity/i18nKey/body/timestamp:string`) — every event row rendered
// with undefined fields. The substrate's <jf-health-event> aggregate
// component now owns the row rendering off the canonical wire shape.

const NUM = new Intl.NumberFormat();

function formatUptime(ms: number | undefined): string {
  if (ms == null || ms <= 0) return '—';
  const sec = Math.floor(ms / 1000);
  const days = Math.floor(sec / 86400);
  const hours = Math.floor((sec % 86400) / 3600);
  const minutes = Math.floor((sec % 3600) / 60);
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m`;
  return `${sec}s`;
}

function formatIndexState(state: string): string {
  switch (state) {
    case 'IDLE':
      return 'Ready';
    case 'INDEXING':
      return 'Indexing';
    case 'NOT_STARTED':
      return 'Not started';
    case 'UNAVAILABLE':
      return 'Unavailable';
    case 'ERROR':
      return 'Error';
    case 'FAILED':
      return 'Indexing failed';
    default:
      return state || 'Unknown';
  }
}

export class HealthSurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    host_: { attribute: false },
    status: { state: true },
    aiState: { state: true },
    inference: { state: true },
    connPhase: { state: true },
    readiness: { state: true },
    verdict: { state: true },
    provisional: { state: true },
    failedJobs: { state: true },
    events: { state: true },
    autoRefresh: { state: true },
    loading: { state: true },
    initialPollComplete: { state: true },
    busy: { state: true },
    error: { state: true },
    recommendedActions: { state: true },
  };

  declare apiBase: string;
  declare host_: PluginHostApi;
  /** Which tab is shown: the Health overview, or the embedded Logs view (§11.8). */
  declare status: StatusSnapshot | null;
  declare inference: InferenceSnapshot | null;
  /** 594 §17.2 — the full snapshot, so metric VALUES can project through `projectFact`. */
  declare aiState: AiState | null;
  /** Connection phase from the one observed-state authority (B7). */
  declare connPhase: ConnectionPhase;
  /** Backend readiness projection — the degradation authority (B2). */
  declare readiness: Maybe<ReadinessView>;
  /** The ONE system-health verdict (595 §4.2) — header + footer consume it. */
  declare verdict: SystemHealthVerdict;
  /** 595 §4.3 — backend mid-transition; the worker-sourced stat cards show "…". */
  declare provisional: boolean;
  declare failedJobs: FailedJob[];
  declare events: HealthEvent[];
  declare autoRefresh: boolean;
  declare loading: boolean;
  /**
   * One-shot flag: true after the first {@link #refresh} attempt completes
   * (success or error). Used to distinguish "haven't polled yet" from
   * "polled and disconnected" — without it, the surface mounts in a
   * misleading 'Backend disconnected' state with zero values during the
   * pre-first-poll window. (observations.md `#270` fix.)
   */
  declare initialPollComplete: boolean;
  declare busy: Record<string, boolean>;
  declare error: string | null;
  /**
   * Slice 447 §X.11.5 Phase 4: condition → recommended Operation lookup. Populated
   * from the `core.condition-recovery-index` Resource (REST snapshot at mount + on
   * apiBase change). Each entry maps `${conditionId}|${subject}` → OperationRef. The
   * "Recommended" section in {@link #renderActions} renders one button per entry that
   * invokes the suggested Operation directly, which closes the §X.3.4 named-consumer
   * loop end-to-end (this surface is the rail-mounted production Health view).
   */
  declare recommendedActions: Map<string, string>;

  private pollStatus: number | null = null;
  private unsubAi: (() => void) | null = null;
  // 569 §15 — presentation subscription (declared status/stats regions on this real surface).
  private presentationUnsub: (() => void) | null = null;
  private eventStreamAbort: AbortController | null = null;
  /**
   * Slice 447-followup-live-wiring §X.12 Item 1.1 — SSE subscription on the
   * `core.condition-recovery-index` STATE × SSE_STREAM Resource. Closes
   * the gap that §X.12 surfaced: the producer side ships
   * `ConditionRecoveryIndexChangeRegistry`, but the FE consumer fetched
   * the REST snapshot once and ignored the stream.
   */
  private recoveryIndexStreamAbort: AbortController | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.host_ = undefined as unknown as PluginHostApi;
    this.status = null;
    this.aiState = null;
    this.inference = null;
    this.connPhase = 'connecting';
    this.readiness = UNKNOWN;
    this.verdict = { kind: 'connecting', severity: 'info', reasons: [] };
    this.provisional = false;
    this.failedJobs = [];
    this.events = [];
    this.autoRefresh = true;
    this.loading = false;
    this.initialPollComplete = false;
    this.busy = {};
    this.error = null;
    this.recommendedActions = new Map();
  }

  /**
   * Slice 447 §X.11.5 Phase 4: fetch the condition-recovery-index REST snapshot and
   * populate {@link #recommendedActions}. Plugin overlays (Phase 6) take precedence
   * via {@link getOverlayRecovery} at render time.
   */
  private async fetchRecoveryIndex(): Promise<void> {
    try {
      const r = await this.doFetch('/api/condition-recovery-index');
      if (!r.ok) return;
      const index = (await r.json()) as {
        entries?: Array<{
          target?: string;
          conditions?: Array<{ conditionId?: string; subject?: string }>;
        }>;
      };
      const next = new Map<string, string>();
      for (const entry of index.entries ?? []) {
        const target = entry.target ?? '';
        for (const c of entry.conditions ?? []) {
          const cid = c.conditionId ?? '';
          const subj = c.subject ?? '';
          if (cid && target) next.set(`${cid}|${subj}`, target);
        }
      }
      this.recommendedActions = next;
    } catch {
      // Silent failure — Quick Actions section still renders without the
      // Recommended sub-section.
    }
  }

  static styles = [
    surfaceLayoutStyles,
    css`
    .header {
      flex-shrink: 0;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0.75rem 1.5rem;
      gap: 1rem;
      border-bottom: 1px solid var(--border-subtle);
    }
    .header-left {
      display: flex;
      align-items: center;
      gap: 0.75rem;
    }
    .header h2 {
      margin: 0;
      font-size: var(--font-size-md);
      font-weight: 500;
    }
    /* 574 B2 — the health status pill is the jf-status-badge atom now; the per-surface
       .badge.healthy/.warn fork is deleted. The .dot below stays (used inside the badge slot
       + the metric rows; currentColor tone-matches the badge's projected text colour). */
    .dot {
      width: 0.4rem;
      height: 0.4rem;
      border-radius: 50%;
      background: currentColor;
    }
    /* 574 B1 — every ACTION button on this surface is the jf-button atom now
       (the global button{}/.icon-btn fork is deleted). Tempdoc 571 §11 / 578 — the §11.8 Health|Logs
       tab strip + embedded-log rules are RETIRED (Logs is a sibling System member now). */
    .body {
      flex: 1;
      overflow-y: auto;
      padding: 1rem 1.5rem;
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }
    .stats {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
      gap: 0.75rem;
    }
    .card {
      padding: 0.875rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
    }
    .card-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 0.4rem;
    }
    .card-head-left {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .card-icon-box {
      width: 1.625rem;
      height: 1.625rem;
      border-radius: 0.4rem;
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }
    .card-icon-box.teal {
      background: var(--accent-tint-16);
      color: var(--text-tint);
    }
    .card-icon-box.blue {
      background: var(--accent-chat-16);
      color: var(--text-chat);
    }
    .card-icon-box.purple {
      background: var(--accent-command-16);
      color: var(--text-command);
    }
    .card-icon-box.amber {
      background: var(--accent-warning-16);
      color: var(--text-warning);
    }
    .card-icon-box.muted {
      background: var(--surface-tertiary);
      color: var(--text-secondary);
    }
    .card-label {
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-secondary);
    }
    .card-value {
      font-size: var(--font-size-xl);
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }
    .card-sub {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.25rem;
    }
    .progress {
      height: 0.375rem;
      background: var(--surface-tertiary);
      border-radius: 9999px;
      overflow: hidden;
      margin-top: 0.4rem;
    }
    .progress-bar {
      height: 100%;
      transition: width var(--duration-normal) var(--ease-standard);
    }
    .progress-bar.green {
      background: var(--accent-success);
    }
    .progress-bar.amber {
      background: var(--accent-warning);
    }
    .progress-bar.red {
      background: var(--accent-danger);
    }
    /* 595 §15.3 (E3) — a rebuild is calm/in-progress, not "done" (green) or a warning
       (amber); the info tone matches the verdict's busy→info tone. */
    .progress-bar.info {
      background: var(--accent-info);
    }
    .rebuild-progress {
      margin-bottom: 0.75rem;
      padding: 0.6rem 0.75rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      background: var(--surface-1);
    }
    .rebuild-progress-head {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
    }
    .grid-2 {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.75rem;
    }
    @media (max-width: 60rem) {
      .grid-2 { grid-template-columns: 1fr; }
    }
    .section h3 {
      margin: 0 0 0.5rem 0;
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-secondary);
      font-weight: 600;
    }
    .data-row {
      display: flex;
      justify-content: space-between;
      padding: 0.375rem 0;
      font-size: var(--font-size-sm);
      border-bottom: 1px solid var(--border-subtle);
    }
    .data-row:last-of-type {
      border-bottom: none;
    }
    .data-row .key {
      color: var(--text-secondary);
    }
    .data-row .val {
      color: var(--text-primary);
      font-family: monospace;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
    }
    /* Audience-gated <jf-operation> hosts render no content for viewers the
       operation is not visible to (tempdoc 689); without this, the empty
       light-DOM hosts stay zero-width flex children and each reserves a
       phantom flex gap (measured 16px inter-button gaps + trailing dead
       space in the 689 UX audit). */
    .actions jf-operation:not(:has(jf-op-button)) {
      display: none;
    }
    .events-list {
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
    }
    .event-row {
      padding: 0.5rem 0.625rem;
      background: var(--surface-tertiary);
      border-radius: 0.375rem;
      font-size: var(--font-size-xs);
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 0.5rem;
    }
    .event-row.error {
      border-left: 3px solid var(--accent-danger);
    }
    .event-row.warning {
      border-left: 3px solid var(--accent-warning);
    }
    .event-row.info {
      border-left: 3px solid var(--accent-tint);
    }
    .event-message {
      flex: 1;
    }
    .event-time {
      color: var(--text-secondary);
      font-family: monospace;
      flex-shrink: 0;
    }
    .failed-row {
      padding: 0.5rem 0.625rem;
      background: var(--surface-tertiary);
      border-radius: 0.375rem;
      font-size: var(--font-size-xs);
      margin-bottom: 0.25rem;
    }
    .failed-row .path {
      font-family: monospace;
      font-size: var(--font-size-xs);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .failed-row .err {
      color: var(--text-danger);
      margin-top: 0.125rem;
      font-family: monospace;
    }
    label.checkbox {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      cursor: pointer;
    }
    label.checkbox input {
      cursor: pointer;
    }
    .empty {
      padding: 1rem;
      text-align: center;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
  `,
  ];

  override connectedCallback(): void {
    super.connectedCallback();
    // Tempdoc 511-followup-D: aggregate-substrate bootstrap moved to
    // module-load in `shell-v0/index.ts`. By the time this callback
    // fires, all canonical strategies are already registered.
    // B7: the shared observed-state (status / inference / phase / readiness)
    // comes from the one aiStateStore — Health does NOT run its own status
    // poll. Only Health-specific streams (failed-jobs, recovery-index, events)
    // are fetched locally below.
    this.unsubAi = subscribeAiState((s: AiState) => {
      this.aiState = s;
      this.status = s.status;
      this.inference = s.inference;
      this.connPhase = s.phase;
      this.readiness = s.readiness;
      this.verdict = s.verdict;
      this.provisional = s.stability.kind === 'provisional';
    });
    void this.refresh();
    this.startPolling();
    this.startEventStream();
    void this.fetchRecoveryIndex();
    this.startRecoveryIndexStream();
    // 569 §15 — re-render when the active presentation changes (the declared status/stats regions
    // appear when CORE_DECLARED is applied, revert when cleared/quarantined — degrade-never-fail).
    this.presentationUnsub = subscribePresentation(() => this.requestUpdate());
  }

  /** Tempdoc 609 — settle transient state on hide (initial-load flag, per-op busy locks, op error) so a
   *  return doesn't show a stale spinner or locked action. Subscribed statuses + autoRefresh are
   *  recoverable and untouched (a fresh poll re-populates on reconnect). */
  protected override settleTransients(): void {
    this.loading = false;
    this.busy = {};
    this.error = null;
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    // Tempdoc 609 §R (P2) — stop polling on hide AND null the id, so a retained-then-reconnected Health
    // surface restarts cleanly rather than holding a stale (already-cleared) interval id.
    if (this.pollStatus !== null) {
      window.clearInterval(this.pollStatus);
      this.pollStatus = null;
    }
    this.unsubAi?.();
    this.unsubAi = null;
    this.presentationUnsub?.();
    this.presentationUnsub = null;
    this.eventStreamAbort?.abort();
    this.recoveryIndexStreamAbort?.abort();
  }

  // Tempdoc 571 §11 / 578 — the §11.8 setTab/suspend/resume tab-stream juggling is RETIRED with the
  // Health|Logs strip: Logs is a sibling System member now, and the System host mounts only the active
  // member, so Health's streams are tab-scoped by construction (no manual suspend/resume needed).

  // B7: this poll now only refreshes the Health-specific failed-jobs list;
  // status/inference are driven by the shared aiStateStore subscription.
  private startPolling(): void {
    if (this.pollStatus !== null) window.clearInterval(this.pollStatus);
    this.pollStatus = window.setInterval(() => {
      if (this.autoRefresh) void this.refresh();
    }, 5000);
  }

  private async startEventStream(): Promise<void> {
    this.eventStreamAbort?.abort();
    this.eventStreamAbort = new AbortController();
    try {
      // Tempdoc 511-followup-D: Javalin's SSE endpoint negotiates the
      // response content type from the request's Accept header. Without
      // it, the backend returns `text/plain; content-length: 0` and the
      // stream closes immediately. Native EventSource sends the header
      // automatically — fetch() does not, so we set it explicitly here
      // and in `startRecoveryIndexStream` below. Verified via 4-way curl
      // probe (proxy + direct, with + without header); see tempdoc 511
      // §511-indirect Spike A.
      const res = await this.doFetch('/api/health/events/stream', {
        signal: this.eventStreamAbort.signal,
        headers: { Accept: 'text/event-stream' },
      });
      if (!res.ok || !res.body) return;
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        buffer = parseSseBuffer(buffer, ({ data }) => {
          if (!data) return;
          try {
            // Tempdoc 511-followup-A: payload uses the wire HealthEvent shape
            // (severity/i18nKey/body/timestamp) per HeadHealthEventsEmitter +
            // LifecycleSnapshotTap. The <jf-health-event> component reads these
            // directly; no shape coercion happens here.
            const env = JSON.parse(data) as Record<string, unknown>;
            const payload = env.payload as Record<string, unknown> | undefined;
            // Tempdoc 564 Phase 1: validate each raw health event against the generated
            // schema at the parse boundary (non-fail-open — logs `[WireContract]` on drift),
            // instead of the prior unchecked `as HealthEvent[]` cast.
            if (env.frameKind === 'LIFECYCLE' && payload?.kind === 'snapshot') {
              const conds = ((payload.conditions as unknown[]) ?? []).map((e) =>
                parseWireContract(healthEventSchema, e, 'GET /api/health/events/stream (snapshot condition)'),
              );
              const occs = ((payload.occurrences as unknown[]) ?? []).map((e) =>
                parseWireContract(healthEventSchema, e, 'GET /api/health/events/stream (snapshot occurrence)'),
              );
              this.events = [...occs.slice(-30), ...conds].slice(-50);
            } else if (env.frameKind === 'UPDATE') {
              const p = payload as { kind?: string; event?: unknown };
              if (p.event) {
                const event = parseWireContract(
                  healthEventSchema,
                  p.event,
                  'GET /api/health/events/stream (update)',
                );
                this.events = [...this.events, event].slice(-50);
              }
            }
          } catch {
            // ignore parse errors
          }
        });
      }
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        // Stream interrupted; silently retry next refresh
      }
    }
  }

  /**
   * Slice 447-followup-live-wiring §X.12 Item 1.1 — subscribe to the
   * `core.condition-recovery-index` SSE stream. Mirrors {@link startEventStream}
   * (no `EventSource` because we need an `AbortController`-cancelable fetch
   * loop that survives Lit's lifecycle). The stream emits two relevant
   * envelope shapes (per `SseEnvelopeWriter` + `ConditionRecoveryIndexChangeRegistry`):
   *
   *   - On subscribe: LIFECYCLE frame with `payload.kind === "snapshot"` and
   *     `payload.index` = the full ConditionRecoveryIndex.
   *   - On change: UPDATE frame whose `payload` IS the ConditionRecoveryIndex
   *     (`channel.publish(SseFrameKind.UPDATE, index)`).
   *
   * Both rebuild {@link recommendedActions} from `index.entries`.
   */
  private async startRecoveryIndexStream(): Promise<void> {
    this.recoveryIndexStreamAbort?.abort();
    this.recoveryIndexStreamAbort = new AbortController();
    try {
      // Tempdoc 511-followup-D: see startEventStream for the Accept-
      // header rationale. Same negotiation behavior on this endpoint.
      const res = await this.doFetch('/api/condition-recovery-index/stream', {
        signal: this.recoveryIndexStreamAbort.signal,
        headers: { Accept: 'text/event-stream' },
      });
      if (!res.ok || !res.body) return;
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        buffer = parseSseBuffer(buffer, ({ data }) => {
          if (!data) return;
          try {
            const env = JSON.parse(data) as Record<string, unknown>;
            const payload = env.payload as Record<string, unknown> | undefined;
            // LIFECYCLE snapshot: index is nested under payload.index.
            // UPDATE: payload IS the index (per ConditionRecoveryIndexChangeRegistry.broadcast).
            let index: { entries?: Array<{ target?: string; conditions?: Array<{ conditionId?: string; subject?: string }> }> } | undefined;
            if (env.frameKind === 'LIFECYCLE' && payload?.kind === 'snapshot') {
              index = payload.index as typeof index;
            } else if (env.frameKind === 'UPDATE') {
              index = payload as typeof index;
            }
            if (!index) return;
            const next = new Map<string, string>();
            for (const entry of index.entries ?? []) {
              const target = entry.target ?? '';
              for (const c of entry.conditions ?? []) {
                const cid = c.conditionId ?? '';
                const subj = c.subject ?? '';
                if (cid && target) next.set(`${cid}|${subj}`, target);
              }
            }
            this.recommendedActions = next;
          } catch {
            // ignore parse errors
          }
        });
      }
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        // Stream interrupted; the REST snapshot fetched at connectedCallback
        // remains the last-known state until the next reconnect.
      }
    }
  }


  private doFetch(path: string, init?: RequestInit): Promise<Response> {
    return this.host_.data.fetch(path, {
      method: init?.method,
      headers: init?.headers as Record<string, string> | undefined,
      body: init?.body as string | undefined,
      signal: init?.signal ?? undefined,
    });
  }

  // B7: refresh now fetches ONLY the Health-specific failed-jobs list.
  // status + inference come from the shared aiStateStore (see connectedCallback).
  private async refresh(): Promise<void> {
    this.loading = true;
    try {
      const fj = await this.doFetch('/api/indexing/failed-jobs?limit=20').then((r) =>
        r.ok ? r.json() : null,
      );
      if (fj?.jobs) this.failedJobs = fj.jobs as FailedJob[];
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    } finally {
      this.loading = false;
      this.initialPollComplete = true;
    }
  }

  private async invokeOp(operationId: string, key: string): Promise<void> {
    if (this.busy[key]) return;
    this.busy = { ...this.busy, [key]: true };
    this.error = null;
    try {
      await this.host_.data.invokeOperation(operationId);
      await this.refresh();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    } finally {
      this.busy = { ...this.busy, [key]: false };
    }
  }

  private renderHeader(): TemplateResult {
    // Slice 456 §gap-fix: overall-health badge intentionally EXCLUDES the
    // inference (AI) component — fresh installs have AI offline by design,
    // and the React HealthView's overallHealth check matches this. Lifecycle
    // state DEGRADED with reason 'inference.offline' is NOT a system fault.
    // 595 §4.2 — the verdict is the ONE derived authority (computed in
    // aiStateStore from stability + readiness); the header CONSUMES it, it does
    // not recompute. HealthSurface layers only its LOCAL `attention` overlay
    // (open recovery conditions) on top, and only when the system is otherwise
    // green — so it can never re-introduce the header/footer split (§1.1).
    const verdict = this.verdict;
    const present = presentVerdict(verdict);
    const hasOpenConditions = this.recommendedActions.size > 0;
    const attention =
      hasOpenConditions && (verdict.kind === 'operational' || verdict.kind === 'checking');
    const label = attention ? 'Attention needed' : present.headline;
    const tone = attention ? 'warning' : present.tone;
    return html`
      <div class="header">
        <div class="header-left">
          <h2>${icon({ name: 'server', size: 16 })} System Health</h2>
          <jf-status-badge tone=${tone}>
            <span class="dot"></span>
            ${label}
          </jf-status-badge>
        </div>
        <div style="display: flex; align-items: center; gap: 0.5rem">
          <label class="checkbox">
            <input
              type="checkbox"
              .checked=${this.autoRefresh}
              @change=${(e: Event) =>
                (this.autoRefresh = (e.target as HTMLInputElement).checked)}
            />
            Auto-refresh
          </label>
          <jf-button
            variant="ghost"
            size="icon"
            label="Refresh now"
            .availability=${this.loading ? unavailableBecause('Refreshing…', true) : undefined}
            .onActivate=${() => void this.refresh()}
          >
            ${icon({ name: 'refresh-cw', size: 14, spin: this.loading })}
          </jf-button>
        </div>
      </div>
    `;
  }

  /**
   * 595 §15.3 (E3) — an INDICATIVE rebuild progress bar, shown only while the system is
   * provisional for a rebuild / generation-switch. The fraction is `building / active`
   * (the new generation's docs-so-far over the OLD generation's count); since the rebuild
   * can change the corpus, this is an APPROXIMATION — clamped to ≤100% and labelled
   * "Rebuilding…", never an exact "N%". The ~5 s status poll advances it (no FE timer).
   * Reuses the local `.progress`/`.progress-bar` markup (the calm `info` tone).
   */
  private renderRebuildProgress(): TemplateResult | typeof nothing {
    const stability = this.aiState?.stability;
    if (!stability || stability.kind !== 'provisional') return nothing;
    if (stability.cause !== 'rebuilding' && stability.cause !== 'generation-switch') return nothing;
    const migration = this.status?.worker?.migration;
    const active = migration?.activeIndexedDocuments ?? 0;
    const building = migration?.buildingIndexedDocuments ?? 0;
    if (active <= 0) return nothing; // no faithful denominator yet — the badge already says "Rebuilding…"
    const ratio = Math.min(building / active, 1);
    const pct = Math.round(ratio * 100);
    return html`
      <div
        class="rebuild-progress"
        role="progressbar"
        aria-label="Index rebuild progress"
        aria-valuemin="0"
        aria-valuemax="100"
        aria-valuenow=${pct}
      >
        <div class="rebuild-progress-head">
          ${icon({ name: 'refresh-cw', size: 13 })}
          <span>Rebuilding…</span>
        </div>
        <div class="progress">
          <div class="progress-bar info" style="width: ${ratio * 100}%"></div>
        </div>
      </div>
    `;
  }

  /**
   * Queue-card sub-text (630). The calm status vocabulary: "Catching up…" (a post-resume reconcile
   * transient) and "Paused — saving energy" (OS energy saver is deferring backfill) sit beside the
   * working state, and the **terminal "Up to date"** (idle + a verified-healthy index) is the
   * explicit trust close — the Dropbox "Syncing… → Up to date" model, to which "Catching up…" and
   * "Paused…" both resolve once they clear. None of these is a fault — search keeps working.
   *
   * Tempdoc 813 §4 makes the terminal close TWO-TIER. Job drain only buys the keyword tier; the
   * semantic layers keep building afterwards, so "Up to date" on job drain alone was the §1d
   * completion lie ("a completion claim is a capability claim", §11). Between the tiers the card
   * reads "Building semantic search — N%", and "Up to date" is reserved for coverage-complete. The number
   * comes from the ONE indexing-progress projection — this card derives none of it privately.
   */
  private queueSubLabel(queue: number, known: boolean): string {
    const stability = this.aiState?.stability;
    if (stability?.kind === 'provisional' && stability.cause === 'catching-up') {
      return 'Catching up…';
    }
    if (this.provisional) return 'Rebuilding…';
    if (this.status?.power?.energyReduced === true && queue > 0) return 'Paused — saving energy';
    if (!known) return 'Unknown';
    if (queue > 0) return 'Indexing';
    // 809 finding 1 — the JOB queue draining is not the work finishing. Embedding/SPLADE/NER run
    // afterwards on the backfill scheduler, and until they drain, semantic and hybrid search do not
    // work — so neither the terminal "Up to date" nor "Idle" is true here. Phase comes from the ONE
    // progress projection (which also guards the unknown/zeroed-worker arm); the phrase is the shared
    // enrichment vocabulary the Brain surface and Library rows use, plus the percent when faithful.
    const progress = selectIndexingProgress(
      this.status,
      this.aiState?.snapshotLive ?? false,
      this.aiState?.episodeMaxPendingJobs ?? 0,
      this.aiState?.enrichSettleSamples ?? [],
    );
    if (progress.phase === 'enriching') {
      return progress.enrichingPercent === null
        ? ENRICHMENT_IN_PROGRESS_LABEL
        : `${ENRICHMENT_IN_PROGRESS_LABEL} — ${progress.enrichingPercent}%`;
    }
    // Round-15 F1 — "Up to date" is the COVERAGE-complete close (813 §4), so it may not be reached by
    // a coverage the system never built and cannot build. No percent: nothing is progressing.
    if (progress.phase === 'blocked') return ENRICHMENT_BLOCKED_LABEL;
    // Idle + a verified-healthy index ⇒ the explicit terminal "Up to date"; otherwise stay honest
    // with "Idle" (don't claim up-to-date when health isn't confirmed).
    return this.status?.worker?.core?.indexHealthy === true ? 'Up to date' : 'Idle';
  }

  private renderStats(): TemplateResult {
    const docs = this.status?.worker?.core?.indexedDocuments ?? 0;
    // `size` now comes from projectFact('core.size'); only `docs` (tone) + `memUsed` (memRatio) numbers remain.
    const memUsed = this.status?.memoryUsedBytes ?? 0;
    const memMax = this.status?.memoryMaxBytes ?? 0;
    const memRatio = memMax > 0 ? memUsed / memMax : 0;
    // 813 §3b — the Queue card's number comes from the ONE indexing-progress projection, the same
    // authority `queueSubLabel` reads, so the card's value and its sub-text cannot disagree.
    const queue = selectIndexingProgress(
      this.status,
      this.aiState?.snapshotLive ?? false,
      this.aiState?.episodeMaxPendingJobs ?? 0,
      this.aiState?.enrichSettleSamples ?? [],
    ).jobsPending;
    const memColor = memRatio > 0.9 ? 'red' : memRatio > 0.8 ? 'amber' : 'green';
    const memDot = memRatio > 0.9 ? 'error' : memRatio > 0.8 ? 'warning' : 'healthy';
    // §2.B: when no status has arrived, the honest value is "—" (unknown),
    // never a confident "0" (the zero-as-unknown defect). B7: status comes from
    // the shared store, so its presence is the have-data signal.
    const known = this.status != null;
    // 594 §17.2 — Files/Size/Memory VALUES derive from the ONE value authority (projectFact);
    // `?? '—'` preserves the unknown→"—" tri-state. `formatBytes` (the shared util) still formats the
    // non-fact memMax sub + GPU VRAM. The raw numbers above stay for tone/sub logic.
    const filesVal = projectFact('core.files', this.aiState).value ?? '—';
    const sizeVal = projectFact('core.size', this.aiState).value ?? '—';
    const memVal = projectFact('core.memory', this.aiState).value ?? '—';
    // 595 §4.3 — worker-sourced stats (Files/Size/Queue) are PROVISIONAL during a transition (the
    // worker fallback reports 0, which must not render as a settled count); show "…" while provisional.
    // Memory is Head-process state (not worker-dependent), so it uses memVal directly, unaffected.
    const wkr = (formatted: string): string =>
      this.provisional ? '…' : known ? formatted : '—';
    // 595 §15.3 (E2) — Files/Size keep showing the last SETTLED value (dimmed) during a provisional
    // window instead of collapsing to '…', so a healthy rebuild stops reading as data loss. Falls back
    // to '…' only before any settled poll. Queue has no meaningful last-settled, so it keeps `wkr`.
    const settled = this.aiState?.lastSettledIndex ?? null;
    const lastKnownIdx = this.provisional && settled !== null;
    // Size honesty: only surface a last-known size that was actually observed; a settled snap that
    // lacked `indexSizeBytes` retains `null`, and Size falls back to '…' rather than a fake "0 B".
    const lastKnownSize = lastKnownIdx && settled!.indexSizeBytes != null;
    const filesDisp = lastKnownIdx
      ? formatCount(settled!.documentCount)
      : wkr(filesVal);
    const sizeDisp = lastKnownSize ? formatBytes(settled!.indexSizeBytes!) : wkr(sizeVal);
    // 569 §15 — when CORE_DECLARED declares the Health stats region, project the metric cards through
    // the engine (the `metric-card` renderer) + the co-projected OVERFLOW strip; else the built-in
    // grid (degrade-never-fail). The surface pre-cooks the formatted metrics into the declared body.
    const statsBody = activeBodyFor(HEALTH_STATS_REGION);
    if (statsBody) {
      const metrics = [
        { label: 'Files', value: filesDisp, icon: 'file-text', tone: !this.provisional && known && docs > 0 ? 'success' : 'neutral', sub: lastKnownIdx ? 'Last known' : undefined },
        { label: 'Size', value: sizeDisp, icon: 'database', tone: 'neutral', sub: lastKnownSize ? 'Last known' : undefined },
        { label: 'Memory', value: memVal, icon: 'memory-stick', tone: memDot === 'healthy' ? 'success' : memDot, sub: memMax > 0 ? `of ${formatBytes(memMax)}` : undefined },
        { label: 'Queue', value: wkr(NUM.format(queue)), icon: 'zap', tone: !this.provisional && queue > 0 ? 'warning' : 'neutral', sub: this.queueSubLabel(queue, known) },
      ];
      // 594 §11.3 #4 — thread THIS surface's 571 altitude (read from the catalog, not re-declared) so
      // the engine renders absent capabilities as "<name> off" on this DIAGNOSTIC surface.
      const statsBodyWithAltitude = { ...statsBody, altitude: getSurface('core.health-surface')?.altitude };
      return html`${this.renderRebuildProgress()}<jf-declared-surface
        .declaration=${statsBodyWithAltitude}
        .data=${{ metrics }}
        .enabled=${true}
      ></jf-declared-surface>`;
    }
    return html`
      ${this.renderRebuildProgress()}
      <div class="stats">
        <div class="card">
          <div class="card-head">
            <div class="card-head-left">
              <span class="card-icon-box teal">${icon({ name: 'file-text', size: 14 })}</span>
              <span class="card-label">Files</span>
            </div>
            <jf-status-dot tone=${!this.provisional && known && docs > 0 ? 'success' : 'neutral'}></jf-status-dot>
          </div>
          <div class="card-value" title=${lastKnownIdx ? 'Last known — updating' : nothing}>${filesDisp}</div>
          ${lastKnownIdx ? html`<div class="card-sub">Last known</div>` : nothing}
        </div>
        <div class="card">
          <div class="card-head">
            <div class="card-head-left">
              <span class="card-icon-box blue">${icon({ name: 'database', size: 14 })}</span>
              <span class="card-label">Size</span>
            </div>
            <jf-status-dot tone="neutral"></jf-status-dot>
          </div>
          <div class="card-value" title=${lastKnownSize ? 'Last known — updating' : nothing}>${sizeDisp}</div>
          ${lastKnownSize ? html`<div class="card-sub">Last known</div>` : nothing}
        </div>
        <div class="card">
          <div class="card-head">
            <div class="card-head-left">
              <span class="card-icon-box purple"
                >${icon({ name: 'memory-stick', size: 14 })}</span
              >
              <span class="card-label">Memory</span>
            </div>
            <jf-status-dot tone=${memDot === 'healthy' ? 'success' : memDot}></jf-status-dot>
          </div>
          <div class="card-value">${memVal}</div>
          ${memMax > 0
            ? html`
                <div class="card-sub">of ${formatBytes(memMax)}</div>
                <div class="progress">
                  <div
                    class="progress-bar ${memColor}"
                    style="width: ${Math.min(memRatio * 100, 100)}%"
                  ></div>
                </div>
              `
            : nothing}
        </div>
        <div class="card">
          <div class="card-head">
            <div class="card-head-left">
              <span class="card-icon-box ${!this.provisional && queue > 0 ? 'amber' : 'muted'}"
                >${icon({ name: 'zap', size: 14 })}</span
              >
              <span class="card-label">Queue</span>
            </div>
            <jf-status-dot
              tone=${!this.provisional && queue > 0 ? 'warning' : 'neutral'}
              ?live=${!this.provisional && queue > 0}
            ></jf-status-dot>
          </div>
          <div class="card-value">${wkr(NUM.format(queue))}</div>
          <div class="card-sub">${this.queueSubLabel(queue, known)}</div>
        </div>
      </div>
    `;
  }

  private renderConnection(): TemplateResult {
    // (observations.md `#270`) Pre-first-poll renders 'connecting…' instead of
    // 'disconnected' so the surface doesn't lie about the boot state.
    const apiStatus = this.status
      ? this.connPhase === 'stale'
        ? 'reconnecting…'
        : 'connected'
      : this.connPhase === 'connecting'
        ? 'connecting…'
        : 'disconnected';
    const rawIndexState = this.status?.worker?.core?.indexState ?? 'UNKNOWN';
    const indexState = formatIndexState(rawIndexState);
    // Endpoint / index-state / uptime are content (data), shared by both renders.
    const rows = html`
      <div class="data-row">
        <span class="key">API endpoint</span>
        <span class="val">${this.apiBase || 'Not connected'}</span>
      </div>
      <div class="data-row">
        <span class="key">Index state</span>
        <span
          class="val"
          style="color: ${rawIndexState === 'IDLE'
            ? 'var(--accent-success)'
            : rawIndexState === 'INDEXING'
              ? 'var(--accent-warning)'
              : rawIndexState === 'ERROR' || rawIndexState === 'FAILED' || rawIndexState === 'UNAVAILABLE'
                ? 'var(--accent-danger)'
                : 'var(--text-secondary)'}"
          >${indexState}</span
        >
      </div>
      <div class="data-row">
        <span class="key">Uptime</span>
        <span class="val">${formatUptime(this.status?.uptimeMs)}</span>
      </div>
      <div class="data-row">
        <span class="key">Backend reachable</span>
        <span class="val">${formatRelativeMs(this.aiState?.connection.lastContactMs ?? null)}</span>
      </div>
      <div class="data-row">
        <span class="key">Data updated</span>
        <span class="val">${formatRelativeMs(this.aiState?.connection.lastSuccessMs ?? null)}</span>
      </div>
      <div class="data-row">
        <span class="key">Connections</span>
        <span class="val"
          >${getCurrentOpenChannelCount()} open (peak ${getPeakOpenChannelCount()})</span
        >
      </div>
    `;
    // 569 §15 — when CORE_DECLARED declares the connection status region, the live STATUS line is
    // CO-PROJECTED: the engine derives the tri-state tone from the ONE observed-state authority
    // (`aiStateStore`), so the author can't hand-paint a fake "connected" colour (Move 3 liveness).
    // This replaces the inline-style status row below; else the built-in render (degrade-never-fail).
    const statusBody = activeBodyFor(HEALTH_STATUS_REGION);
    if (statusBody) {
      return html`
        <div class="card section">
          <jf-declared-surface
            .declaration=${statusBody}
            .data=${{}}
            .enabled=${true}
          ></jf-declared-surface>
          ${rows}
        </div>
      `;
    }
    return html`
      <div class="card section">
        <h3>${icon({ name: 'server', size: 12 })} Connection</h3>
        <div class="data-row">
          <span class="key">Status</span>
          <span
            class="val"
            style="color: ${apiStatus === 'connected'
              ? 'var(--accent-success)'
              : apiStatus === 'connecting…'
                ? 'var(--text-muted)'
                : apiStatus === 'reconnecting…'
                  ? 'var(--accent-warning)'
                  : 'var(--accent-danger)'}"
            >${apiStatus}</span
          >
        </div>
        ${rows}
      </div>
    `;
  }

  private renderQueueDb(): TemplateResult | typeof nothing {
    if (!this.status) return nothing;
    const indexHealthy = this.status?.worker?.core?.indexHealthy ?? null;
    return html`
      <div class="card section">
        <h3>${icon({ name: 'database', size: 12 })} Queue DB</h3>
        <div class="data-row">
          <span class="key">Status</span>
          <span
            class="val"
            style="color: ${indexHealthy === true
              ? 'var(--accent-success)'
              : indexHealthy === false
                ? 'var(--accent-danger)'
                : 'var(--text-secondary)'}"
            >${indexHealthy === true
              ? 'Healthy'
              : indexHealthy === false
                ? 'Unhealthy'
                : 'Unknown'}</span
          >
        </div>
        <div class="data-row">
          <span class="key">Last backup</span>
          <span class="val" style="color: var(--text-secondary)">Not configured</span>
        </div>
        <div class="data-row">
          <span class="key">Integrity check</span>
          <span class="val" style="color: var(--text-secondary)">—</span>
        </div>
      </div>
    `;
  }

  private renderAiEngine(): TemplateResult | typeof nothing {
    if (!this.inference) return nothing;
    const mode = this.inference.mode ?? 'unknown';
    const isOnline = mode === 'online';
    return html`
      <div class="card section">
        <h3>
          ${icon({ name: 'cpu', size: 12 })} AI Engine
          <span
            style="float: right; padding: 0.125rem 0.5rem; border-radius: 0.25rem; font-size: var(--font-size-xs); ${isOnline
              ? 'background: var(--accent-success-16); color: var(--text-success);'
              : 'background: var(--surface-2); color: var(--text-secondary);'}"
            >${mode}</span
          >
        </h3>
        <div style="display: flex; align-items: center; gap: 0.5rem; padding: 0.375rem 0">
          <span
            class="dot"
            style="background: ${isOnline
              ? 'var(--accent-success)'
              : 'var(--text-secondary)'}; width: 0.5rem; height: 0.5rem"
          ></span>
          <span style="font-size: var(--font-size-sm)"
            >${isOnline ? 'Online' : mode === 'transitioning' ? 'Starting…' : 'Offline'}</span
          >
        </div>
        <div
          style="display: grid; grid-template-columns: max-content 1fr max-content 1fr; gap: 0.375rem 1rem; margin-top: 0.5rem; font-size: var(--font-size-sm)"
        >
          <span class="key" style="color: var(--text-secondary)">Embed queue</span>
          <span class="val" style="font-family: monospace; text-align: right"
            >${NUM.format(this.inference.embeddingQueueSize ?? 0)}</span
          >
          <span class="key" style="color: var(--text-secondary)">VDU queue</span>
          <span class="val" style="font-family: monospace; text-align: right"
            >${NUM.format(this.inference.vduQueueSize ?? 0)}</span
          >
          <span class="key" style="color: var(--text-secondary)">Context (actual)</span>
          <span class="val" style="font-family: monospace; text-align: right"
            >${this.inference.llmContextTokens != null
              ? NUM.format(this.inference.llmContextTokens)
              : 'N/A'}</span
          >
          <span class="key" style="color: var(--text-secondary)">Context (configured)</span>
          <span class="val" style="font-family: monospace; text-align: right"
            >${this.inference.configuredContextTokens != null
              ? `${NUM.format(this.inference.configuredContextTokens)}`
              : 'N/A'}</span
          >
        </div>
        ${this.renderRealizedEngines()}
      </div>
    `;
  }

  /**
   * tempdoc 644 — per-engine realized state (reranker / embeddings / SPLADE): loaded? + a GPU/CPU
   * accelerator pill + a failure-reason tooltip. Projects the ONE `aiState.realized` authority
   * (computeRealized) — never re-reads `worker.gpu.*OrtCuda` here (the fork the
   * realized-capability register prevents). Additive to the AI Engine card; no new route/surface.
   */
  private renderRealizedEngines(): TemplateResult | typeof nothing {
    const realized = this.aiState?.realized;
    if (!realized) return nothing;
    const rows: Array<[string, EngineRealized]> = [
      ['Reranker', realized.reranker],
      ['Embeddings', realized.embed],
      ['SPLADE', realized.splade],
    ];
    const pill = (e: EngineRealized): TemplateResult => {
      if (!e.loaded) {
        return html`<span style="color: var(--text-secondary); font-size: var(--font-size-xs)"
          >not loaded</span
        >`;
      }
      const onGpu = e.accelerator === 'gpu';
      const text = onGpu ? 'GPU' : e.accelerator === 'cpu' ? 'CPU' : 'loaded';
      return html`<span
        title=${e.failureReason ?? ''}
        style="padding: 0.0625rem 0.375rem; border-radius: 0.25rem; font-size: var(--font-size-xs); ${onGpu
          ? 'background: var(--accent-success-16); color: var(--text-success);'
          : 'background: var(--surface-2); color: var(--text-secondary);'}"
        >${text}</span
      >`;
    };
    return html`
      <div
        style="margin-top: 0.5rem; border-top: 1px solid var(--border-subtle); padding-top: 0.5rem"
      >
        <div
          style="font-size: var(--font-size-xs); color: var(--text-secondary); margin-bottom: 0.375rem"
        >
          Retrieval engines
        </div>
        <div
          style="display: grid; grid-template-columns: max-content 1fr; gap: 0.375rem 1rem; font-size: var(--font-size-sm)"
        >
          ${rows.map(
            ([label, e]) => html`
              <span class="key" style="color: var(--text-secondary)">${label}</span>
              <span style="text-align: right">${pill(e)}</span>
            `,
          )}
        </div>
      </div>
    `;
  }

  private renderGpu(): TemplateResult | typeof nothing {
    const gpu = this.status?.gpu;
    if (!gpu || gpu.available == null) return nothing;
    const ratio =
      gpu.totalVramBytes && gpu.totalVramBytes > 0 && gpu.usedVramBytes != null
        ? gpu.usedVramBytes / gpu.totalVramBytes
        : 0;
    const color = ratio > 0.9 ? 'red' : ratio > 0.75 ? 'amber' : 'green';
    return html`
      <div class="card section">
        <h3>${icon({ name: 'cpu', size: 12 })} GPU
          <span
            style="float:right; padding: 0.125rem 0.5rem; border-radius: 0.25rem; font-weight: 500; ${gpu.available
              ? 'background: var(--accent-success-16); color: var(--text-success);'
              : 'background: var(--surface-2); color: var(--text-secondary);'}"
            >${gpu.available ? 'Detected' : 'Unavailable'}</span
          >
        </h3>
        ${gpu.available
          ? html`
              ${gpu.totalVramBytes != null && gpu.usedVramBytes != null
                ? html`
                    <div class="data-row">
                      <span class="key">VRAM</span>
                      <span class="val"
                        >${formatBytes(gpu.usedVramBytes)} / ${formatBytes(gpu.totalVramBytes)}</span
                      >
                    </div>
                    <div class="progress">
                      <div class="progress-bar ${color}" style="width: ${ratio * 100}%"></div>
                    </div>
                  `
                : nothing}
              ${gpu.gpuUtilizationPercent != null
                ? html`
                    <div class="data-row">
                      <span class="key">Utilization</span>
                      <span class="val">${gpu.gpuUtilizationPercent}%</span>
                    </div>
                  `
                : nothing}
              ${gpu.driverVersion
                ? html`
                    <div class="data-row">
                      <span class="key">Driver</span>
                      <span class="val">${gpu.driverVersion}</span>
                    </div>
                  `
                : nothing}
              ${gpu.cudaFunctional != null
                ? html`
                    <div class="data-row">
                      <span class="key">GPU acceleration</span>
                      <span class="val">${gpu.cudaFunctional ? 'Detected' : 'Unavailable'}</span>
                    </div>
                  `
                : nothing}
              ${gpu.source && gpu.source !== 'none'
                ? html`
                    <div class="data-row">
                      <span class="key">Source</span>
                      <span class="val"
                        >${gpu.source}${gpu.confidence ? html` · ${gpu.confidence}` : nothing}</span
                      >
                    </div>
                  `
                : nothing}
            `
          : html`<div class="empty" style="padding: 0.5rem 0">
              No GPU detected. Inference runs on CPU.
            </div>`}
      </div>
    `;
  }

  /**
   * Tempdoc 629 (FLOOR): the "Data protection" card — honest at-rest status of the data-dir volume.
   * Mirrors the GPU card's host-capability projection (state + source · confidence). Coarse on/off
   * fidelity: configuration quality (TPM-only vs pre-boot PIN) needs admin and renders as
   * "unknown — needs admin", so the surface never over-claims. Per-store rows are derived from the
   * store classification — the index + conversations both ride the data-dir volume, so under the
   * FLOOR (no app-level encryption yet) both reflect the OS disk-encryption state.
   */
  private renderAtRest(): TemplateResult | typeof nothing {
    // 629 remaining-work: the card markup now lives in the shared `renderAtRestCard` so the Security &
    // Privacy surface renders the identical card. HealthSurface's own `.card`/`.data-row` styles cover it.
    return renderAtRestCard(this.status);
  }

  /**
   * Slice 447 §X.11.5 Phase 4 + 6: render a "Recommended for current conditions"
   * sub-section above Quick Actions when {@link #recommendedActions} is non-empty.
   * Each entry maps a (conditionId, subject) pair to an Operation that the backend
   * (or a TRUSTED plugin overlay) declared as the recovery; clicking the button
   * dispatches the Operation through the existing invokeOp pathway.
   */
  /** The footer body sentence per verdict kind (595 §10.5 — single-sourced wording). */
  private renderRecommendedActions(): TemplateResult {
    // 595 §4.2 — the footer rollup CONSUMES the same one verdict the header does,
    // so the two can no longer disagree on a boundary value (the §1.1 split: a
    // 'Service degraded' header beside a '✓ All systems operational' footer). The
    // verdict already encodes connecting / transitioning / checking / degraded /
    // unreachable; the footer only adds the green "✓" affordance for operational.
    if (this.recommendedActions.size === 0) {
      const v = this.verdict;
      if (v.kind === 'operational') {
        return html`
          <div class="card section recommended all-healthy">
            <h3>✓ All systems operational</h3>
            <p>No recoverable conditions active.</p>
          </div>
        `;
      }
      const present = presentVerdict(v);
      return html`
        <div class="card section recommended">
          <h3>${present.headline}</h3>
          <p>${present.body}</p>
        </div>
      `;
    }
    const entries = Array.from(this.recommendedActions.entries()).sort(
      ([a], [b]) => a.localeCompare(b),
    );
    return html`
      <div class="card section recommended">
        <h3>⚡ Fixable now (${entries.length})</h3>
        <div class="actions">
          ${entries.map(([key, opRef]) => {
            const [conditionId, subject] = key.split('|');
            const overlay = getOverlayRecovery(conditionId ?? '', subject ?? '');
            const target = overlay ?? opRef;
            const busyKey = target.replace(/^core\./, '');
            const isBusy = !!this.busy[busyKey];
            // §2.A: the VISIBLE label is humanized via the display projector — never
            // the raw condition id. The title= keeps the raw id + subject for
            // precise a11y/debugging.
            const conditionLabel = present({ kind: 'condition', id: conditionId ?? '' }).label;
            return html`
              <jf-button
                size="sm"
                label="Fix: ${conditionLabel}"
                ?disabled=${isBusy}
                .onActivate=${() => void this.invokeOp(target, busyKey)}
                title="Fix: ${conditionId} on ${subject}"
              >
                ${isBusy
                  ? html`⏳ Running…`
                  : html`${icon({ name: 'refresh-cw', size: 12 })} Fix: ${conditionLabel}`}
                ${overlay
                  ? html`<span class="overlay-tag" title="Plugin overlay">·plugin</span>`
                  : nothing}
              </jf-button>
            `;
          })}
        </div>
      </div>
    `;
  }

  private renderActions(): TemplateResult {
    // Tempdoc 511 Phase 4 migration: Quick Actions are now catalog-
    // driven via `<jf-operation context="button">`. The strategy
    // resolves label, risk, and confirm-strategy from the wire's
    // OperationCatalog, so adding / removing / renaming an operation
    // does not require touching this surface. No `viewer-audience`
    // override is passed here — the block renders per the ambient
    // audience gate (`viewerAudienceState`): USER-tier ops
    // (core.reindex, core.rebuild-index, core.export-diagnostics —
    // USER-tier as of tempdoc 689) are always visible; OPERATOR-tier
    // ops (core.restart-worker, core.clear-failed-jobs, core.index-gc)
    // only appear when the viewer is in operator viewing mode.
    //
    // Pre-migration UX: confirmAsync modal for bulk-reindex.
    // Post-migration UX: ActionButton inline confirm (matches the
    // wire's policy.confirm = {kind: 'INLINE'} for bulk-reindex).
    // The wire is now the single source of truth for ceremony.
    return html`
      <div class="card section">
        <h3>Quick actions</h3>
        <div class="actions" @op-success=${() => this.refresh()}>
          <jf-operation
            operation-id="core.reindex"
            context="button"
            api-base=${this.apiBase}
          ></jf-operation>
          <jf-operation
            operation-id="core.restart-worker"
            context="button"
            api-base=${this.apiBase}
          ></jf-operation>
          <jf-operation
            operation-id="core.rebuild-index"
            context="button"
            api-base=${this.apiBase}
          ></jf-operation>
          <jf-operation
            operation-id="core.clear-failed-jobs"
            context="button"
            api-base=${this.apiBase}
          ></jf-operation>
          <jf-operation
            operation-id="core.export-diagnostics"
            context="button"
            api-base=${this.apiBase}
            .args=${{
              feTelemetry: {
                wireDrift: summarizeWireDrift(),
                // Tempdoc 941 — an FE handler that threw mid-stream leaves the turn looking like a
                // backend that sent nothing; the export carries the trace that tells them apart.
                streamHandlerFailures: summarizeStreamHandlerFailures(),
              },
            }}
          ></jf-operation>
          <jf-operation
            operation-id="core.index-gc"
            context="button"
            api-base=${this.apiBase}
          ></jf-operation>
        </div>
      </div>
    `;
  }

  private renderFailed(): TemplateResult | typeof nothing {
    if (this.failedJobs.length === 0) return nothing;
    return html`
      <div class="card section">
        <h3>
          ${icon({ name: 'alert-circle', size: 12 })} Failed files
          (${this.failedJobs.length})
        </h3>
        ${this.failedJobs.slice(0, 10).map(
          (j) => html`
            <div class="failed-row">
              <div class="path" title=${j.path ?? ''}>${j.path ?? j.pathHash ?? 'unknown'}</div>
              ${j.errorMessage ? html`<div class="err">${j.errorMessage}</div>` : nothing}
            </div>
          `,
        )}
      </div>
    `;
  }

  private renderEvents(): TemplateResult {
    if (this.events.length === 0) {
      return html`
        <div class="card section">
          <h3>Recent events</h3>
          <div class="empty">No events yet.</div>
        </div>
      `;
    }
    return html`
      <div class="card section">
        <h3>Recent events</h3>
        <div class="events-list">
          ${this.events
            .slice(-30)
            .reverse()
            .map(
              (e) => html`
                <jf-health-event
                  context="activity-row"
                  .event=${e}
                ></jf-health-event>
              `,
            )}
        </div>
      </div>
    `;
  }

  override render(): TemplateResult {
    // Tempdoc 571 §11 / 578 — the §11.8 hand-rolled Health|Logs tab strip is RETIRED: Logs is now a
    // sibling member of the System hub (one <jf-surface-tabs>), so Health renders only its own body.
    // The System host mounts only the active member, so Health's streams are already tab-scoped (the
    // §11.8 SSE-suspend wiring is no longer needed).
    return html`
      ${this.renderHeader()}
      <div class="body">
        ${this.error
          ? html`<jf-error-alert tone="error" .onDismiss=${() => (this.error = null)}
              >${this.error}</jf-error-alert
            >`
          : nothing}
        <!-- 578 Workstream A — the live "Now" strip (what's running right now), folded in above the
             snapshot. Reuses <jf-system-self-view> in compact mode; no own heading. -->
        <jf-system-self-view variant="strip" api-base=${this.apiBase}></jf-system-self-view>
        ${this.renderStats()}
        <!-- Tempdoc 596 §16.5 — "what can I do right now": every capability affordance's availability +
             its inline remedy, projected from the same authority the live controls read. -->
        <jf-capability-map .aiState=${this.aiState}></jf-capability-map>
        <div class="grid-2">${this.renderConnection()} ${this.renderGpu()}</div>
        <div class="grid-2">${this.renderQueueDb()} ${this.renderAiEngine()}</div>
        <div class="grid-2">${this.renderAtRest()}</div>
        ${this.renderFailed()}
        ${this.renderRecommendedActions()}
        ${this.renderActions()}
        ${this.renderEvents()}
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-health-surface')) {
  customElements.define('jf-health-surface', HealthSurface);
}
