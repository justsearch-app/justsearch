// SPDX-License-Identifier: Apache-2.0
/**
 * HealthSurface — Lit-side Health rail surface (slice 456 phase 9, final).
 *
 * Self-mounting Surface with operational parity to React HealthView:
 * stats grid (files/size/memory/queue), connection panel, GPU panel,
 * Quick Actions (reindex / recover-component / clear-failed-jobs /
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

import { getIndexedRoots, subscribeIndexedRoots } from '../state/indexedRoots.js';
import { getStatusEpoch, subscribeStatusEpoch } from '../../api/statusInvalidation.js';
import { subscribeProfileSwitch } from '../state/UserStateDocument.js';
import { listTasks } from '../substrates/tasks/index.js';
import { getSeenCursor, subscribeSeenCursor } from '../substrates/recall/recallCursor.js';
import { projectSystemHealth, unresolvedHealthOperations, getHealthRemoteObservations, subscribeHealthObservations, healthObservationCurrent, statusCurrency, healthIndexingProgress, REMOTE_HEALTH_MAX_AGE_MS } from '../state/systemHealth.js';
import { activateOnKey } from '../utils/keyboardHandler.js';
import { takeMemberTabIntent, subscribeMemberTab } from '../router/memberTabIntent.js';
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { surfaceLayoutStyles } from '../primitives/surfaceLayout.js';
import '../components/OpButton.js';
import type { OpErrorEventDetail, OpSuccessEventDetail } from '../components/OpButton.js';
import '../components/StatusBadge.js';
import '../components/Button.js';
import '../components/ErrorAlert.js';
import '../components/OperationOutcomeDetail.js';
import '../components/CapabilityMap.js';
import { parseSseBuffer } from '../../api/sse.js';
import { summarizeWireDrift } from '../../api/wireDriftTelemetry.js';
// Tempdoc 941 — the sibling FE-defect ring: which of OUR stream handlers threw, and how often.
import { summarizeStreamHandlerFailures } from '../../api/streamHandlerTelemetry.js';
import { icon } from '../components/Icon.js';
import { renderAtRestCard, atRestPillStyles } from './security/atRestCard.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
// Slice 447 §X.11.5 Phase 6: plugin recovery-overlay client. Overlays take precedence
// over the backend-declared core recovery for the matched (conditionId, subject) pair.
import { getOverlayRecovery } from '../../api/registry/RecoveryOverlayClient.js';
// Tempdoc 511-followup-A: wire-shape HealthEvent + <jf-health-event> aggregate component.
import type { HealthEvent } from '../../api/generated/index.js';
import { healthEventSchema } from '../../api/domains/health.js';
import { parseWireContract } from '../../api/schemas.js';
import '../aggregate-substrate/components/JfHealthEvent.js';
import '../aggregate-substrate/components/JfOperation.js';
// B1/B7: the observed state (status, inference, phase, readiness) comes from the
// one aiStateStore — Health no longer runs a SECOND /api/status poll. The raw
// snapshot types are the generated/poll shapes, re-exported by the store.
import {
  subscribeAiState,
  hasLiveDocumentCount,
  getStatusReceivedAtMs,
  getInferenceReceivedAtMs,
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
import { getLastOriginContactMs } from '../state/originContact.js';
import { type SystemHealthVerdict } from '../state/verdict.js';
import { formatRelativeMs } from '../../utils/relativeTime.js';
import { noteHealthOnScreen, subscribeEngineLoss, engineLossReason, type EngineLoss } from '../state/engineLoss.js';
import { engineLossAge } from '../components/EngineLoss.js';
import { type Maybe, UNKNOWN } from '../state/known.js';
import { unavailableBecause } from '../state/availability.js';
import { readinessNotice, reasonFor } from '../state/readinessNotice.js';
import { isWorkerReportedIndex, workerCountsUnknown } from '../state/indexingProgress.js';
import '../components/IndexingControls.js';
import {
  ENRICHMENT_BLOCKED_LABEL,
  ENRICHMENT_IN_PROGRESS_LABEL,
} from '../state/enrichmentCoverage.js';
import { present } from '../display/present.js';
import { projectFact } from '../display/facts.js';
import { formatBytes, formatCount, formatTime } from '../display/format.js';
// 569 §15 — co-project the liveness + overflow facets on a REAL surface (the 4th surface).
import { activeBodyFor, subscribePresentation } from '../state/presentationRuntime.js';
import { HEALTH_STATUS_REGION, HEALTH_STATS_REGION } from '../themes/builtinPresentations.js';
import { getSurface } from '../../api/registry/SurfaceCatalogClient.js';
import '../components/DeclaredSurface.js';
// Tempdoc 578 Workstream A — "Now" folded into Health's top as a compact live-strip (the standalone
// core.system-self-view RAIL surface is retired). Import directly so the element is defined when embedded.
import './SystemSelfView.js';

// QueueDbStatus interface elided — derived inline from status response.

/**
 * Tempdoc 518 Appendix F W3.2 — one row of /api/inference/transitions response.
 */
interface TransitionRecord {
  timestampMs: number;
  fromMode: string;
  toMode: string;
  reason: string;
  success: boolean;
  durationMs: number;
  wireCode?: string;
}

/**
 * Tempdoc 518 Appendix G Wave D.1 — one span row from /api/diagnostics/traces.
 * Mirrors the NdjsonSpanExporter line format.
 */
interface TraceSpan {
  trace_id?: string;
  span_id?: string;
  parent_span_id?: string;
  name?: string;
  start?: string;
  end?: string;
  duration_ms?: number;
  status?: string;
  attrs?: Record<string, unknown>;
}

interface TraceExplorerResponse {
  spans?: TraceSpan[];
  tracesAvailable?: boolean;
}

interface RecoveryAction {
  target: string;
  args: Record<string, unknown>;
}

interface RecoveryIndexWire {
  entries?: Array<{
    target?: string;
    conditions?: Array<{ conditionId?: string; subject?: string; defaultArgsJson?: string }>;
  }>;
}

/** The inverse index is a projection of the condition's complete invocation, shared by REST/SSE. */
function recoveryActions(index: RecoveryIndexWire): Map<string, RecoveryAction> {
  const next = new Map<string, RecoveryAction>();
  for (const entry of index.entries ?? []) {
    if (typeof entry.target !== 'string' || !entry.target) continue;
    for (const condition of entry.conditions ?? []) {
      if (!condition.conditionId || typeof condition.defaultArgsJson !== 'string') continue;
      let args: unknown;
      try {
        args = JSON.parse(condition.defaultArgsJson);
      } catch {
        // A malformed invocation is unavailable; never silently replace its arguments with {}.
        continue;
      }
      if (args === null || typeof args !== 'object' || Array.isArray(args)) continue;
      next.set(`${condition.conditionId}|${condition.subject ?? ''}`, {
        target: entry.target, args: args as Record<string, unknown>,
      });
    }
  }
  return next;
}

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
// In memory for this window; tab replacement does not reset an expanded disclosure.
let diagnosticsExpanded = false;

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

export class HealthSurface extends JfElement {
  static properties = {
    transitions: { state: true },
    transitionsAvailable: { state: true },
    recentSpans: { state: true },
    tracesAvailable: { state: true },
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
    bulkOutcomeKey: { state: true },
    bulkError: { state: true },
    error: { state: true },
    recommendedActions: { state: true },
    engineLoss: { state: true },
    diagnosticsOpen: { state: true },
  };

  declare transitions: TransitionRecord[];
  declare transitionsAvailable: boolean;
  declare recentSpans: TraceSpan[];
  declare tracesAvailable: boolean;
  private telemetryTimer: number | null = null;
  private telemetryRequests = new Map<string, AbortController>();
  private sectionUnsub: (() => void) | null = null;
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
  declare bulkOutcomeKey: string;
  declare bulkError: OpErrorEventDetail | null;
  declare error: string | null;
  /**
   * Slice 447 §X.11.5 Phase 4: condition → recommended Operation lookup. Populated
   * from the `core.condition-recovery-index` Resource (REST snapshot at mount + on
   * apiBase change). Each entry maps `${conditionId}|${subject}` → OperationRef. The
   * "Recommended" section in {@link #renderActions} renders one button per entry that
   * invokes the suggested Operation directly, which closes the §X.3.4 named-consumer
   * loop end-to-end (this surface is the rail-mounted production Health view).
   */
  declare recommendedActions: Map<string, RecoveryAction>;

  declare diagnosticsOpen: boolean;
  private observationsUnsub: (() => void)[] = [];
  private recoveryAtMs: number | null = null;
  private recoveryFailed = false;
  private recoveryStreamRevision = 0;
  private recoveryRead: AbortController | null = null;
  private failedRead: AbortController | null = null;
  private failedAtMs: number | null = null;
  private failedReadError = false;
  private eventsAtMs: number | null = null;
  private eventsConnected = false;
  private pendingAiEngineFocus = false;
  private pollStatus: number | null = null;
  private unsubAi: (() => void) | null = null;
  /** S5c — while the engine is lost, Health shows its last known facts, each with its age. */
  declare engineLoss: EngineLoss | null;
  private unsubEngineLoss: (() => void) | null = null;
  private engineLossClock: number | null = null;
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
    this.transitions = [];
    this.transitionsAvailable = false;
    this.recentSpans = [];
    this.tracesAvailable = false;
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
    this.bulkOutcomeKey = '';
    this.bulkError = null;
    this.error = null;
    this.recommendedActions = new Map();
    this.diagnosticsOpen = diagnosticsExpanded;
  }

  /**
   * Slice 447 §X.11.5 Phase 4: fetch the condition-recovery-index REST snapshot and
   * populate {@link #recommendedActions}. Plugin overlays (Phase 6) take precedence
   * via {@link getOverlayRecovery} at render time.
   */
  private startRecoveryIndex(): void {
    this.recoveryIndexStreamAbort?.abort();
    const controller = new AbortController();
    this.recoveryIndexStreamAbort = controller;
    this.recommendedActions = new Map();
    this.recoveryAtMs = null; this.recoveryFailed = false;
    this.recoveryStreamRevision = 0;
    const epoch = getStatusEpoch();
    const accept = (index: RecoveryIndexWire): void => {
      if (controller.signal.aborted || this.recoveryIndexStreamAbort !== controller
        || (epoch !== null && epoch !== getStatusEpoch())) return;
      this.recommendedActions = recoveryActions(index);
      this.recoveryAtMs = Date.now(); this.recoveryFailed = false;
      this.recoveryStreamRevision++;
      this.requestUpdate();
    };
    void this.fetchRecoveryIndex();
    void this.startRecoveryIndexStream(controller.signal, accept);
  }

  private recoveryCurrent(): boolean {
    return healthObservationCurrent(this.recoveryAtMs, Date.now(), REMOTE_HEALTH_MAX_AGE_MS, this.recoveryFailed);
  }

  /** Bound includes the response body, even if a transport ignores cancellation. */
  private async readHealthJson(path: string, signal: AbortSignal): Promise<unknown> {
    let abandoned!: () => void;
    const cancelled = new Promise<never>((_resolve, reject) => {
      abandoned = () => reject(new DOMException('Health read abandoned', 'AbortError'));
      signal.addEventListener('abort', abandoned, { once: true });
      if (signal.aborted) abandoned();
    });
    try {
      return await Promise.race([(async () => {
        const response = await this.doFetch(path, { signal });
        if (!response.ok) throw new Error(`Health observation unavailable (${response.status})`);
        return response.json() as Promise<unknown>;
      })(), cancelled]);
    } finally { signal.removeEventListener('abort', abandoned); }
  }

  private async fetchRecoveryIndex(): Promise<void> {
    if (this.recoveryRead) return;
    const parent = this.recoveryIndexStreamAbort;
    if (!parent || parent.signal.aborted) return;
    const read = new AbortController(); this.recoveryRead = read;
    const signal = AbortSignal.any([read.signal, parent.signal]);
    const revision = this.recoveryStreamRevision;
    const timer = setTimeout(() => read.abort(), 5_000);
    const current = () => this.isConnected && this.recoveryIndexStreamAbort === parent && !parent.signal.aborted && revision === this.recoveryStreamRevision;
    try {
      const index = await this.readHealthJson('/api/condition-recovery-index', signal) as RecoveryIndexWire;
      if (!Array.isArray(index.entries)) throw new Error('Recovery conditions unavailable');
      if (current() && !signal.aborted) {
        this.recommendedActions = recoveryActions(index);
        this.recoveryAtMs = Date.now(); this.recoveryFailed = false;
      }
    } catch { if (current()) this.recoveryFailed = true; }
    finally {
      clearTimeout(timer);
      if (this.recoveryRead === read) this.recoveryRead = null;
      this.requestUpdate();
    }
  }

  static styles = [
    atRestPillStyles,
    surfaceLayoutStyles,
    css`
      .dot.engine-dot { width: 0.5rem; height: 0.5rem; }
      .result-dot { display: inline-block; width: 0.5rem; height: 0.5rem; border-radius: 50%; flex-shrink: 0; align-self: center; }
      .health-pill { border-radius: 0.25rem; }
      .connection-pill { float: right; padding: 0.125rem 0.5rem; font-size: var(--font-size-xs); }
      .gpu-layer-pill { padding: 0.0625rem 0.375rem; font-size: var(--font-size-xs); }
      .gpu-available-pill { float: right; padding: 0.125rem 0.5rem; font-weight: 500; }

    :host {
      min-width: 0;
      max-width: 100%;
    }
    .health-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(8rem, 100%), 1fr)); gap: .75rem; margin: 0; }
    .health-metrics dd { margin: .25rem 0 0; font-size: var(--font-size-lg); font-variation-settings: "opsz" 32; overflow-wrap: anywhere; }
    .health-metrics .last-known { font-size: var(--font-size-xs); font-variation-settings: normal; color: var(--text-secondary); }
    .health-rows { margin: 0; }
    .health-row { display: grid; grid-template-columns: minmax(8rem, 1fr) minmax(0, 3fr); gap: 1rem; padding: .8rem 0; border-bottom: 1px solid var(--border-subtle); }
    .health-row dt { font-weight: 600; }
    .health-row dd { margin: 0; overflow-wrap: anywhere; }
    .health-row p { margin: 0 0 .4rem; }
    details { min-width: 0; margin-top: 1rem; }
    summary { cursor: pointer; padding: .75rem 0; font-weight: 600; }
    summary:focus-visible { outline: 2px solid var(--focus-ring-color); outline-offset: 2px; }
    .diagnostic-currency { color: var(--text-secondary); }
    @media (max-width: 35rem) {
      .health-row { grid-template-columns: minmax(0, 1fr); gap: .4rem; }
      .grid-2, .stats { grid-template-columns: minmax(0, 1fr); }
      .header-left { flex-wrap: wrap; }
      .data-row { flex-wrap: wrap; }
      .body { padding: .75rem; }
    }
    @media (forced-colors: active) {
      .health-row { border-color: CanvasText; }
    }
    .header {
      flex-wrap: wrap;
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
      font-size: var(--font-size-headline);
      font-variation-settings: "opsz" 32;
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
      min-width: 0;
      flex: 1;
      overflow-y: auto;
      padding: 1rem 1.5rem;
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }
    .stats {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(12rem, 100%), 1fr));
      gap: 0.75rem;
    }
    /* SH16 — a readout never widens its column: unbroken values (paths, names, reasons) wrap. */
    .card {
      min-width: 0;
      overflow-wrap: anywhere;
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
      border-radius: 8px;
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
      font-variation-settings: "opsz" 32;
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
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.75rem;
    }
    @media (max-width: 60rem) {
      .grid-2 { grid-template-columns: minmax(0, 1fr); }
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
    .recommended .actions { align-items: flex-start; gap: 1rem; }
    .recommended-action {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 0.375rem;
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
      border-radius: 8px;
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
      border-radius: 8px;
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
    :host .header-actions { display: flex; align-items: center; gap: 0.5rem; }
    :host .generation-history { margin-top: 0.75rem; display: flex; align-items: center; gap: 0.5rem; font-size: var(--font-size-xs); }
    :host .secondary-text { color: var(--text-secondary); }
    :host .generation-number { font-weight: 600; font-variant-numeric: tabular-nums; }
    :host .block-detail { display: block; }
    :host .recent-history { margin-top: 0.875rem; font-size: var(--font-size-xs); }
    :host .history-summary { cursor: pointer; color: var(--text-secondary); }
    :host .history-list { margin: 0.5rem 0 0 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 0.25rem; }
    :host .history-result { display:flex; gap:0.5rem; align-items: baseline; font-variant-numeric: tabular-nums; }
    :host .danger-text { color: var(--text-danger); }
    :host .history-count { color: var(--text-secondary); font-size: var(--font-size-xs); }
    :host .history-session { display:flex; gap:0.5rem; align-items: baseline; font-variant-numeric: tabular-nums; cursor: pointer; }
    :host .session-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 18rem; min-width: 0; }
    :host .connection-status { display: flex; align-items: center; gap: 0.5rem; padding: 0.375rem 0; }
    :host .status-label { font-size: var(--font-size-sm); }
    :host .observation-age { font-size: var(--font-size-xs); color: var(--text-secondary); }
    :host .queue-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 0.375rem 1rem; margin-top: 0.5rem; font-size: var(--font-size-sm); }
    :host .queue-value { font-family: monospace; text-align: right; }
    :host .gpu-layer-details { margin-top: 0.5rem; border-top: 1px solid var(--border-subtle); padding-top: 0.5rem; }
    :host .gpu-layer-heading { font-size: var(--font-size-xs); color: var(--text-secondary); margin-bottom: 0.375rem; }
    :host .gpu-layer-grid { display: grid; grid-template-columns: max-content 1fr; gap: 0.375rem 1rem; font-size: var(--font-size-sm); }
    :host .gpu-layer-value { text-align: right; }
    :host .compact-empty { padding: 0.5rem 0; }

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
    this.observationsUnsub = [subscribeIndexedRoots(() => this.requestUpdate()),
      subscribeHealthObservations(() => this.requestUpdate()), subscribeSeenCursor(() => this.requestUpdate()),
      subscribeStatusEpoch(() => this.resetObservations()), subscribeProfileSwitch(() => this.resetObservations())];
    document.addEventListener('visibilitychange', this.resumeHealth);
    noteHealthOnScreen(true);
    this.unsubEngineLoss = subscribeEngineLoss((loss) => { this.engineLoss = loss; if (!loss.lost && this.pendingAiEngineFocus) void this.focusAiEngine(); });
    // Ages move on while nothing else does.
    this.engineLossClock = window.setInterval(() => this.requestUpdate(), 1_000);
    void this.refreshTelemetry();
    this.telemetryTimer = window.setInterval(() => { if (this.autoRefresh) void this.refreshTelemetry(); }, 5000);
    this.sectionUnsub = subscribeMemberTab((hostId, section) => {
      if (hostId !== 'core.health-surface' || section !== 'ai-engine') return false;
      void this.focusAiEngine();
      return true;
    });
    if (takeMemberTabIntent('core.health-surface') === 'ai-engine') void this.focusAiEngine();
    void this.refresh();
    this.startPolling();
    this.startEventStream();
    this.startRecoveryIndex();
    // 569 §15 — re-render when the active presentation changes (the declared status/stats regions
    // appear when CORE_DECLARED is applied, revert when cleared/quarantined — degrade-never-fail).
    this.presentationUnsub = subscribePresentation(() => this.requestUpdate());
  }

  /** Tempdoc 609 — settle transient state on hide (initial-load flag, operation error) so a
   *  return doesn't show a stale spinner or locked action. Subscribed statuses + autoRefresh are
   *  recoverable and untouched (a fresh poll re-populates on reconnect). */
  protected override settleTransients(): void {
    this.loading = false;
    this.error = null;
    this.bulkError = null;
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    for (const stop of this.observationsUnsub) stop();
    this.observationsUnsub = [];
    document.removeEventListener('visibilitychange', this.resumeHealth);
    this.failedRead?.abort(); this.failedRead = null;
    this.recoveryRead?.abort(); this.recoveryRead = null;
    // Tempdoc 609 §R (P2) — stop polling on hide AND null the id, so a retained-then-reconnected Health
    // surface restarts cleanly rather than holding a stale (already-cleared) interval id.
    if (this.pollStatus !== null) {
      window.clearInterval(this.pollStatus);
      this.pollStatus = null;
    }
    for (const controller of this.telemetryRequests.values()) controller.abort();
    this.telemetryRequests.clear();
    if (this.telemetryTimer !== null) window.clearInterval(this.telemetryTimer);
    this.telemetryTimer = null;
    this.sectionUnsub?.();
    this.sectionUnsub = null;
    this.unsubAi?.();
    this.unsubAi = null;
    this.unsubEngineLoss?.();
    this.unsubEngineLoss = null;
    if (this.engineLossClock !== null) window.clearInterval(this.engineLossClock);
    this.engineLossClock = null;
    noteHealthOnScreen(false);
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
      if (this.autoRefresh) { void this.refresh(); void this.fetchRecoveryIndex(); if (!this.eventsConnected) void this.startEventStream(); }
    }, 5000);
  }

  private async startEventStream(): Promise<void> {
    this.eventStreamAbort?.abort();
    const controller = new AbortController(); this.eventStreamAbort = controller;
    this.eventsConnected = false;
    const current = () => this.isConnected && this.eventStreamAbort === controller && !controller.signal.aborted;
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
        signal: controller.signal,
        headers: { Accept: 'text/event-stream' },
      });
      if (!res.ok || !res.body) return;
      if (!current()) return;
      this.eventsConnected = true; this.requestUpdate();
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done || !current()) break;
        this.eventsAtMs = Date.now();
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
        // The last snapshot is retained with its own currency label.
      }
    } finally {
      if (this.eventStreamAbort === controller) { this.eventsConnected = false; this.requestUpdate(); }
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
  private async startRecoveryIndexStream(signal: AbortSignal, accept: (index: RecoveryIndexWire) => void): Promise<void> {
    try {
      // Tempdoc 511-followup-D: see startEventStream for the Accept-
      // header rationale. Same negotiation behavior on this endpoint.
      const res = await this.doFetch('/api/condition-recovery-index/stream', {
        signal,
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
            let index: RecoveryIndexWire | undefined;
            if (env.frameKind === 'LIFECYCLE' && payload?.kind === 'snapshot') {
              index = payload.index as typeof index;
            } else if (env.frameKind === 'UPDATE') {
              index = payload as typeof index;
            }
            if (!index) return;
            accept(index);
          } catch {
            // ignore parse errors
          }
        });
      }
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        // Stream interrupted; the last accepted snapshot remains until reconnect.
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
    if (this.ownershipRefusal()) {
      this.failedJobs = [];
      this.error = null;
      this.initialPollComplete = true;
      return;
    }
    this.failedRead?.abort();
    const read = new AbortController(); this.failedRead = read;
    const timer = setTimeout(() => read.abort(), 5_000);
    this.loading = true;
    try {
      const fj = await this.readHealthJson('/api/indexing/failed-jobs?limit=20', read.signal) as { jobs?: FailedJob[] };
      if (!Array.isArray(fj.jobs)) throw new Error('Failed-file observation unavailable');
      if (this.isConnected && this.failedRead === read && !read.signal.aborted) {
        this.failedJobs = fj.jobs; this.failedAtMs = Date.now(); this.failedReadError = false;
      }
    } catch {
      // A failed read is a currency fact (the Failed files card and the rows say it), never banner text.
      if (this.isConnected && this.failedRead === read) this.failedReadError = true;
    } finally {
      clearTimeout(timer);
      if (this.failedRead === read) { this.failedRead = null; this.loading = false; this.initialPollComplete = true; }
    }
  }

  private readonly resumeHealth = (): void => { this.requestUpdate(); if (document.visibilityState !== 'hidden') { void this.refresh(); void this.fetchRecoveryIndex(); } };
  private resetObservations(): void {
    this.failedRead?.abort(); this.failedRead = null;
    this.failedJobs = []; this.failedAtMs = null;
    this.recoveryRead?.abort(); this.recoveryRead = null;
    this.startRecoveryIndex();
    this.events = []; this.eventsAtMs = null;
    this.startEventStream();
    void this.refresh();
  }
  private healthRows() {
    return projectSystemHealth({ ai: this.aiState, statusAtMs: getStatusReceivedAtMs(), now: Date.now(),
      roots: getIndexedRoots(), remote: getHealthRemoteObservations(), recoveryCurrent: this.recoveryCurrent(),
      recoveryCount: this.recommendedActions.size, seenCursor: getSeenCursor(),
      activeTasks: listTasks().filter(task => ['running', 'queued'].includes(task.status) && !task.operationKey && !task.id.startsWith('idxjob:')).map(task => task.label) });
  }
  private renderOverviewMetrics(): TemplateResult {
    const progress = this.indexingProgress();
    const { head, worker } = this.statusCurrency();
    const workerReported = isWorkerReportedIndex(this.status);
    // A value Health holds but cannot confirm stays readable, marked as last known.
    const metric = (label: string, value: string | null | undefined, current: boolean) => html`<div><dt>${label}</dt><dd>${
      value == null ? '—' : current ? value : html`${value} <span class="last-known">· last known</span>`}</dd></div>`;
    return html`<dl class="health-metrics" aria-label="System measurements">
      ${metric('Files', workerReported ? projectFact('core.files', this.aiState).value : undefined, worker)}
      ${metric('Size', workerReported ? projectFact('core.size', this.aiState).value : undefined, worker)}
      ${metric('Memory', this.status?.memoryUsedBytes != null ? projectFact('core.memory', this.aiState).value : undefined, head)}
      ${metric('Queue', workerReported ? formatCount(progress.jobsPending) : undefined, worker)}
    </dl>`;
  }
  /** The status read's currency, by Health's one rule (systemHealth). */
  private statusCurrency() {
    return statusCurrency(this.aiState, getStatusReceivedAtMs(), Date.now());
  }
  private headCurrent(): boolean {
    return this.statusCurrency().head;
  }
  /** The age of whichever observation is out of date: the status receipt, else the worker sample it carries. */
  private statusAges(): string {
    const at = getStatusReceivedAtMs();
    if (at == null) return 'no status received';
    const sample = this.status?.meta?.workerRpcAtMs;
    return this.headCurrent() && sample != null ? `worker sample ${formatRelativeMs(Math.min(at, sample))}` : `status received ${formatRelativeMs(at)}`;
  }



  private renderHeader(): TemplateResult {
    const rows = this.healthRows();
    const allFine = rows.every(row => row.status === '');
    const meaningful = rows.find(row => row.status && !row.status.toLowerCase().includes('unconfirmed'));
    // A failed status read is said in words; the error itself is never shown.
    const unread = this.aiState?.statusReadFailed !== true ? null : this.status
      ? 'Can’t get current status from the Engine. Showing what was last observed.'
      : 'Can’t get status from the Engine. Health keeps checking.';
    const label = allFine ? 'Everything is working' : meaningful?.status ?? unread ?? 'Checking system status';
    return html`
      <div class="header">
        <div class="header-left">
          <h2>Health</h2>
          <p role="status">${label}</p>
        </div>
        <div class="header-actions">
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
    const pct = this.indexingProgress().rebuildPercent;
    if (pct === null) return nothing;
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
          <div class="progress-bar info" .style=${`width: ${pct}%`}></div>
        </div>
      </div>
    `;
  }

  /**
   * Queue-card sub-text (630). The calm status vocabulary: "Catching up…" (a post-resume reconcile
   * transient) and the shared automatic-hold explanation sit beside the
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
  private indexingProgress() {
    return healthIndexingProgress(this.aiState, this.statusCurrency());
  }

  private queueSubLabel(queue: number, known: boolean): string {
    const progress = this.indexingProgress();
    if (!progress.activity.confirmed || progress.activity.detail) {
      return [progress.activity.summary, progress.activity.detail].filter(Boolean).join(' — ');
    }
    const stability = this.aiState?.stability;
    if (stability?.kind === 'provisional' && stability.cause === 'catching-up') {
      return 'Catching up…';
    }
    if (this.provisional) return 'Rebuilding…';
    if (!known) return 'Unknown';
    if (queue > 0) return progress.activity.summary;
    // 809 finding 1 — the JOB queue draining is not the work finishing. Embedding/SPLADE/NER run
    // afterwards on the backfill scheduler, and until they drain, semantic and hybrid search do not
    // work — so neither the terminal "Up to date" nor "Idle" is true here. Phase comes from the ONE
    // progress projection (which also guards the unknown/zeroed-worker arm); the phrase is the shared
    // enrichment vocabulary the Brain surface and Library rows use, plus the percent when faithful.
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
    // The person's own files (Help excluded), the count the Files value shows.
    const own = this.aiState?.index.ownDocumentCount;
    const docs = own?.known ? own.value : 0;
    // `size` now comes from projectFact('core.size'); only `docs` (tone) + `memUsed` (memRatio) numbers remain.
    const memUsed = this.status?.memoryUsedBytes ?? 0;
    const memMax = this.status?.memoryMaxBytes ?? 0;
    const memoryKnown = this.headCurrent() && this.status?.memoryUsedBytes != null && memMax > 0;
    const memRatio = memoryKnown ? memUsed / memMax : 0;
    // 813 §3b — the Queue card's number comes from the ONE indexing-progress projection, the same
    // authority `queueSubLabel` reads, so the card's value and its sub-text cannot disagree.
    const progress = this.indexingProgress();
    const queue = progress.jobsPending;
    const memColor = memRatio > 0.9 ? 'red' : memRatio > 0.8 ? 'amber' : 'green';
    const memDot = !memoryKnown ? 'neutral' : memRatio > 0.9 ? 'error' : memRatio > 0.8 ? 'warning' : 'healthy';
    // §2.B: when no status has arrived, the honest value is "—" (unknown),
    // never a confident "0" (the zero-as-unknown defect). B7: status comes from
    // the shared store, so its presence is the have-data signal.
    const known = this.headCurrent();
    // 594 §17.2 — Files/Size/Memory VALUES derive from the ONE value authority (projectFact);
    // `?? '—'` preserves the unknown→"—" tri-state. `formatBytes` (the shared util) still formats the
    // non-fact memMax sub + GPU VRAM. The raw numbers above stay for tone/sub logic.
    const filesVal = projectFact('core.files', this.aiState).value ?? '—';
    const sizeVal = projectFact('core.size', this.aiState).value ?? '—';
    const memVal = memoryKnown ? projectFact('core.memory', this.aiState).value ?? '—' : '—';
    // 595 §4.3 — worker-sourced stats (Files/Size/Queue) are PROVISIONAL during a transition (the
    // worker fallback reports 0, which must not render as a settled count); show "…" while provisional.
    // Memory is Head-process state (not worker-dependent), so it uses memVal directly, unaffected.
    const wkr = (formatted: string): string =>
      this.provisional ? '…' : this.aiState?.indexingStatusLive ? formatted : '—';
    // 595 §15.3 (E2) — Files/Size keep showing the last SETTLED value (dimmed) during a provisional
    // window instead of collapsing to '…', so a healthy rebuild stops reading as data loss. Falls back
    // to '…' only before any settled poll. Queue has no meaningful last-settled, so it keeps `wkr`.
    const settled = this.aiState?.lastSettledIndex ?? null;
    const lastKnownIdx = this.provisional && settled !== null;
    // Size honesty: only surface a last-known size that was actually observed; a settled snap that
    // lacked `indexSizeBytes` retains `null`, and Size falls back to '…' rather than a fake "0 B".
    const lastKnownSize = lastKnownIdx && settled!.indexSizeBytes != null;
    const currentCount = hasLiveDocumentCount(this.aiState);
    const lastKnownFiles = lastKnownIdx && !currentCount && settled!.ownDocumentCount != null;
    const filesDisp = currentCount ? filesVal : lastKnownFiles
      ? formatCount(settled!.ownDocumentCount!)
      : wkr(filesVal);
    const sizeDisp = lastKnownSize ? formatBytes(settled!.indexSizeBytes!) : wkr(sizeVal);
    // An unknown Worker queue is no queue reading, never an empty one.
    const queueDisp = wkr(isWorkerReportedIndex(this.status) ? NUM.format(queue) : '—');
    // 569 §15 — when CORE_DECLARED declares the Health stats region, project the metric cards through
    // the engine (the `metric-card` renderer) + the co-projected OVERFLOW strip; else the built-in
    // grid (degrade-never-fail). The surface pre-cooks the formatted metrics into the declared body.
    const statsBody = activeBodyFor(HEALTH_STATS_REGION);
    if (statsBody) {
      const metrics = [
        { label: 'Files', value: filesDisp, icon: 'file-text', tone: !this.provisional && this.aiState?.indexingStatusLive && docs > 0 ? 'success' : 'neutral', sub: lastKnownFiles ? 'Last known' : undefined },
        { label: 'Size', value: sizeDisp, icon: 'database', tone: 'neutral', sub: lastKnownSize ? 'Last known' : undefined },
        { label: 'Memory', value: memVal, icon: 'memory-stick', tone: memDot === 'healthy' ? 'success' : memDot, sub: memMax > 0 ? `of ${formatBytes(memMax)}` : undefined },
        { label: 'Queue', value: queueDisp, icon: 'zap', tone: progress.activity.confirmed && !this.provisional && queue > 0 ? 'warning' : 'neutral', sub: this.queueSubLabel(queue, known) },
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
            <jf-status-dot tone=${!this.provisional && this.aiState?.indexingStatusLive && docs > 0 ? 'success' : 'neutral'}></jf-status-dot>
          </div>
          <div class="card-value" title=${lastKnownFiles ? 'Last known — updating' : nothing}>${filesDisp}</div>
          ${lastKnownFiles ? html`<div class="card-sub">Last known</div>` : nothing}
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
          ${memoryKnown
            ? html`
                <div class="card-sub">of ${formatBytes(memMax)}</div>
                <div class="progress">
                  <div
                    class="progress-bar ${memColor}"
                    .style=${`width: ${Math.min(memRatio * 100, 100)}%`}
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
              tone=${progress.activity.confirmed && !this.provisional && queue > 0 ? 'warning' : 'neutral'}
              ?live=${progress.activity.confirmed && !this.provisional && queue > 0}
            ></jf-status-dot>
          </div>
          <div class="card-value">${queueDisp}</div>
          <div class="card-sub">${this.queueSubLabel(queue, known)}</div>
        </div>
      </div>
    `;
  }

  private renderConnection(): TemplateResult {
    // (observations.md `#270`) Pre-first-poll renders 'connecting…' instead of
    // 'disconnected' so the surface doesn't lie about the boot state.
    const apiStatus = this.headCurrent() ? this.status
      ? this.connPhase === 'stale'
        ? 'reconnecting…'
        : 'connected'
      : this.connPhase === 'connecting'
        ? 'connecting…'
        : 'disconnected' : 'Status unconfirmed';
    const progress = this.indexingProgress();
    const activity = progress.activity;
    const indexState = [activity.summary, activity.detail].filter(Boolean).join(' — ');
    const failure = this.status?.worker?.core?.indexState === 'FAILED' ? 'Indexing failed'
      : this.status?.worker?.core?.indexState === 'ERROR' ? 'Indexing error' : null;
    // Each age is dated by its own source: the status body's receipt, the worker's sample inside it,
    // the last answer or frame on any channel (contact) and the last stream frame. Uptime is as of
    // the status receipt, and says so once that receipt is no longer current.
    const statusAt = getStatusReceivedAtMs();
    const reportedSample = this.status?.meta?.workerRpcAtMs;
    const sampledAt = statusAt != null && reportedSample != null ? Math.min(statusAt, reportedSample) : null;
    const uptime = formatUptime(this.status?.uptimeMs);
    const streamAt = getLastOriginContactMs();
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
          .style=${`color: ${failure ? 'var(--accent-danger)' : !activity.confirmed
            ? 'var(--text-secondary)'
            : progress.phase === 'ready' && this.status?.worker?.core?.indexHealthy === true
              ? 'var(--accent-success)'
              : 'var(--text-secondary)'}`}
          >${failure ?? indexState}</span>
        ${failure ? html`<span>${indexState}</span>` : nothing}
      </div>
      <div class="data-row">
        <span class="key">Uptime</span>
        <span class="val">${uptime !== '—' && !this.headCurrent() ? `${uptime} as of ${formatRelativeMs(statusAt)}` : uptime}</span>
      </div>
      <div class="data-row">
        <span class="key">Last contact</span>
        <span class="val">${formatRelativeMs(this.aiState?.connection.lastContactMs ?? null)}</span>
      </div>
      <div class="data-row">
        <span class="key">Status updated</span>
        <span class="val">${formatRelativeMs(statusAt)}${sampledAt == null ? '' : ` · worker sample ${formatRelativeMs(sampledAt)}`}</span>
      </div>
      <div class="data-row">
        <span class="key">Connections</span>
        <span class="val"
          >${getCurrentOpenChannelCount()} open (peak ${getPeakOpenChannelCount()}) · last frame ${formatRelativeMs(streamAt)}</span
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
            .style=${`color: ${apiStatus === 'connected'
              ? 'var(--accent-success)'
              : apiStatus === 'connecting…'
                ? 'var(--text-muted)'
                : apiStatus === 'reconnecting…'
                  ? 'var(--accent-warning)'
                  : 'var(--accent-danger)'}`}
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
    const confirmed = this.indexingProgress().activity.confirmed;
    return html`
      <div class="card section">
        <h3>${icon({ name: 'database', size: 12 })} Queue DB</h3>
        <div class="data-row">
          <span class="key">Status</span>
          <span
            class="val"
            .style=${`color: ${!confirmed ? 'var(--text-secondary)' : indexHealthy === true
              ? 'var(--accent-success)'
              : indexHealthy === false
                ? 'var(--accent-danger)'
                : 'var(--text-secondary)'}`}
            >${indexHealthy === true
              ? 'Healthy'
              : indexHealthy === false
                ? 'Unhealthy'
                : 'Unknown'}${confirmed ? '' : ` (last observed) · ${this.statusAges()}`}</span
          >
        </div>

      </div>
    `;
  }

  private async focusAiEngine(): Promise<void> {
    this.pendingAiEngineFocus = true;
    if (this.engineLoss?.lost) return;
    this.diagnosticsOpen = true; diagnosticsExpanded = true;
    await this.updateComplete;
    const section = this.shadowRoot?.querySelector<HTMLElement>('#ai-engine');
    section?.scrollIntoView?.({ block: 'start' });
    section?.focus({ preventScroll: true });
    if (section) this.pendingAiEngineFocus = false;
  }

  private async refreshTelemetry(): Promise<void> {
    // Each endpoint owns its bounded request. Slow traces must not hold back
    // transitions, and a polling tick must not cancel work still in progress.
    const read = async <T,>(path: string, publish: (value: T | null) => void): Promise<void> => {
      if (this.telemetryRequests.has(path)) return;
      const controller = new AbortController();
      this.telemetryRequests.set(path, controller);
      let onAbort!: () => void;
      const aborted = new Promise<null>(resolve => {
        onAbort = () => resolve(null);
        controller.signal.addEventListener('abort', onAbort, { once: true });
      });
      const timeout = window.setTimeout(() => controller.abort(), 15000);
      try {
        const response = (async () => {
          try {
            const result = await this.doFetch(path, { signal: controller.signal });
            return result.ok ? await result.json() as T : null;
          } catch { return null; }
        })();
        const value = await Promise.race([response, aborted]);
        if (this.isConnected && this.telemetryRequests.get(path) === controller) publish(value);
      } finally {
        window.clearTimeout(timeout);
        controller.signal.removeEventListener('abort', onAbort);
        if (this.telemetryRequests.get(path) === controller) this.telemetryRequests.delete(path);
      }
    };
    await Promise.all([
      read<{ transitions?: TransitionRecord[] }>('/api/inference/transitions?limit=8', value => {
        this.transitionsAvailable = Array.isArray(value?.transitions);
        this.transitions = value?.transitions ?? [];
      }),
      read<TraceExplorerResponse>('/api/diagnostics/traces?limit=10', value => {
        this.tracesAvailable = value?.tracesAvailable === true;
        this.recentSpans = value?.spans ?? [];
      }),
    ]);
  }

  /**
   * Tempdoc 518 Appendix G Wave D.2 — sparkline of inference generation over the recent
   * transition window. SVG-based, no external charting library. Each transition is a tick
   * mark on the timeline (failures red, successes teal); the current generation value is
   * the right-edge label. When generation is 0 or there are no transitions yet, returns
   * nothing — there's nothing useful to chart.
   */
  private renderGenerationSparkline(): TemplateResult | typeof nothing {
    const rows = this.transitions;
    const currentGen = typeof this.inference?.generation === 'number' ? this.inference.generation : 0;
    if (!rows || rows.length === 0 || currentGen === 0) return nothing;
    // Tempdoc 518 Wave A-E defect Fix-4: render the actual step-function y-line, not just
    // dots. Each successful transition bumps generation by 1; the line shows the count's
    // evolution. Failure dots stay on the line (failure does NOT bump generation, so the
    // step stays flat at that x — visible as a red dot on a horizontal segment).
    const ascending = [...rows].sort((a, b) => a.timestampMs - b.timestampMs);
    const first = ascending[0]!.timestampMs;
    const last = ascending[ascending.length - 1]!.timestampMs;
    const tsSpan = Math.max(1, last - first);
    const W = 200;
    const H = 32;
    const padX = 4;
    const padY = 4;
    const yMax = Math.max(1, currentGen);
    const xOf = (ts: number) => padX + ((ts - first) / tsSpan) * (W - padX * 2);
    const yOf = (gen: number) => H - padY - (gen / yMax) * (H - padY * 2);

    // Build the step function. Walk transitions in chronological order; each success bumps
    // the running gen by 1 (matches the runner's generation counter contract). Each row
    // contributes two points (horizontal then vertical jump on success) and stamps the
    // gen-at-that-row for dot positioning.
    const dotData: Array<{ ts: number; gen: number; row: TransitionRecord }> = [];
    const points: Array<{ x: number; y: number }> = [];
    let gen = 0;
    points.push({ x: padX, y: yOf(gen) });
    for (const row of ascending) {
      const px = xOf(row.timestampMs);
      points.push({ x: px, y: yOf(gen) }); // horizontal segment up to this transition
      if (row.success) {
        gen += 1;
        points.push({ x: px, y: yOf(gen) }); // vertical jump
      }
      dotData.push({ ts: row.timestampMs, gen, row });
    }
    // Final horizontal segment extending to the right edge (current generation plateau).
    points.push({ x: W - padX, y: yOf(gen) });
    const pointsStr = points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');

    return html`
      <div
        data-testid="brain-generation-sparkline"
        class="generation-history"
      >
        ${/* Round-14 finding 10 — "gen" was unqualified and collided with a DIFFERENT in-product
              "generation": /api/inference/status `generation` is an int engine-instance counter,
              while /api/knowledge/status `servingSearchGenerationId` is a string index id. Beside a
              model filename the abbreviation also read as "1st-generation model" (a version claim).
              Naming the axis is the whole fix — the value is unchanged. */ ''}
        <span class="secondary-text">engine generation:</span>
        <span class="generation-number">${currentGen}</span>
        <svg role="img" aria-label="Engine instance history" width="${W}" height="${H}" class="block-detail">
          <polyline
            data-testid="brain-generation-sparkline-line"
            points="${pointsStr}"
            fill="none"
            stroke="var(--accent-tint)"
            stroke-width="1.5"
            stroke-linejoin="round"
          />
          ${dotData.map(
            (d) => html`<circle
              cx="${xOf(d.ts)}"
              cy="${yOf(d.gen)}"
              r="3"
              fill="${d.row.success ? 'var(--accent-tint)' : 'var(--accent-danger)'}"
            >
              <title>${formatTime(new Date(d.row.timestampMs))} · gen=${d.gen} · ${d.row.fromMode.toLowerCase()} → ${d.row.toMode.toLowerCase()} · ${d.row.reason.toLowerCase().replace(/_/g, ' ')} · ${d.row.durationMs}ms${d.row.wireCode ? ` · ${d.row.wireCode}` : ''}</title>
            </circle>`,
          )}
        </svg>
      </div>
    `;
  }

  private renderTransitionTimeline(): TemplateResult | typeof nothing {
    const rows = this.transitions;
    if (!rows || rows.length === 0) return html`<p data-testid="health-transitions-empty">${this.transitionsAvailable ? 'No inference transitions yet.' : 'Inference transitions unavailable.'}</p>`;
    return html`
      ${this.renderGenerationSparkline()}
      <details
        class="transitions recent-history"
        data-testid="brain-transitions-timeline"
      >
        ${/* Round-14 finding 10 — "mode" is ambiguous on this very window: the same panel carries a
              Simple | Detailed disclosure mode and the search surface has rungs. These rows are the
              INFERENCE state machine's transitions; say so. */ ''}
        <summary class="history-summary">
          Recent inference transitions (${rows.length})
        </summary>
        <ul
          class="history-list"
        >
          ${rows.map(
            (r) => html`
              <li
                data-testid="brain-transition-row"
                data-success=${String(r.success)}
                class="history-result"
              >
                <span
                  class="result-dot" .style=${`background: ${r.success ? 'var(--accent-tint)' : 'var(--accent-danger)'}`}
                ></span>
                <span class="secondary-text"
                  >${formatTime(new Date(r.timestampMs))}</span
                >
                <span>${r.fromMode.toLowerCase()} → ${r.toMode.toLowerCase()}</span>
                <span class="secondary-text"
                  >· ${r.reason.toLowerCase().replace(/_/g, ' ')}</span
                >
                <span class="secondary-text">· ${r.durationMs}ms</span>
                ${r.wireCode
                  ? html`<span class="danger-text">· ${r.wireCode}</span>`
                  : nothing}
              </li>
            `,
          )}
        </ul>
      </details>
    `;
  }

  /**
   * Tempdoc 518 Appendix G Wave D.1 — in-product trace explorer panel.
   * Lists the 10 most recent spans from /api/diagnostics/traces. Hidden when the endpoint reports
   * `tracesAvailable: false` — which DiagnosticsController derives purely from whether
   * `<dataDir>/telemetry/traces.ndjson` is a regular file, NOT from the tracing level: with
   * JUSTSEARCH_HEAD_TRACING_LEVEL=none but a leftover traces file on disk, the panel still renders
   * (verified empirically). Detailed-mode-only for that reason among others.
   * Clicking a row copies its trace_id to the clipboard so it can be looked up in
   * otel-desktop-viewer or grep'd against traces.ndjson.
   */
  private renderTraceExplorer(): TemplateResult | typeof nothing {
    if (!this.tracesAvailable || !this.recentSpans || this.recentSpans.length === 0) {
      return html`<p data-testid="health-spans-empty">${this.tracesAvailable ? 'No recent spans.' : 'Recent spans unavailable — no trace file or endpoint available.'}</p>`;
    }
    return html`
      <details
        class="traces recent-history"
        data-testid="brain-trace-explorer"
      >
        <summary class="history-summary">
          Recent spans (${this.recentSpans.length})
          <span class="history-count">
            · click a row to copy trace ID
          </span>
        </summary>
        <ul
          class="history-list"
        >
          ${this.recentSpans.map(
            (s) => html`
              <li
                data-testid="brain-trace-row"
                role="button"
                tabindex="0"
                class="history-session"
                @click=${() => {
                  if (s.trace_id) {
                    void navigator.clipboard?.writeText(s.trace_id);
                  }
                }}
                @keydown=${(e: KeyboardEvent) =>
                  activateOnKey(e, () => {
                    if (s.trace_id) void navigator.clipboard?.writeText(s.trace_id);
                  })}
                title=${s.trace_id ? `trace_id=${s.trace_id} (click to copy)` : ''}
              >
                <span
                  class="result-dot" .style=${`background: ${s.status === 'ERROR' ? 'var(--accent-danger)' : 'var(--accent-tint)'}`}
                ></span>
                <span class="secondary-text">
                  ${s.start ? formatTime(new Date(s.start)) : '—'}
                </span>
                <span class="session-name" title=${s.name ?? ''}>
                  ${s.name ?? '(unnamed)'}
                </span>
                <span class="secondary-text">
                  · ${typeof s.duration_ms === 'number' ? `${Math.round(s.duration_ms)}ms` : '—'}
                </span>
              </li>
            `,
          )}
        </ul>
      </details>
    `;
  }

  private renderAiEngine(): TemplateResult | typeof nothing {
    if (!this.inference) return html`<section id="ai-engine" tabindex="-1" class="card section"><h3>AI Engine</h3><p>Engine status unavailable.</p>${this.renderTransitionTimeline()}${this.renderTraceExplorer()}</section>`;
    const inferenceAt = getInferenceReceivedAtMs();
    const inferenceCurrent = healthObservationCurrent(inferenceAt, Date.now(), 10_000);
    const mode = this.headCurrent() && inferenceCurrent ? this.aiState?.runtime?.mode ?? this.inference.mode ?? 'unknown' : 'Status unconfirmed';
    const isOnline = mode === 'online';
    const failure = this.aiState?.chatFailureReason ?? null;
    const progress = this.indexingProgress();
    const activity = progress.activity;
    return html`
      <div id="ai-engine" tabindex="-1" class="card section">
        <h3>
          ${icon({ name: 'cpu', size: 12 })} AI Engine
          <span
            class="health-pill connection-pill" .style=${`background: ${isOnline ? 'var(--accent-success-16)' : 'var(--surface-2)'}; color: ${isOnline ? 'var(--text-success)' : 'var(--text-secondary)'}`}
            >${mode}</span
          >
        </h3>
        <div class="connection-status">
          <span
            class="dot engine-dot"
            .style=${`background: ${isOnline ? 'var(--accent-success)' : 'var(--text-secondary)'}`}
          ></span>
          <span class="status-label"
            >${isOnline
              ? 'Online'
              : mode === 'Status unconfirmed' ? 'Last observed AI state — current status unconfirmed'
              : mode === 'transitioning'
                ? 'Starting…'
                : // Spec A step 7 (F1): a failed chat says why, in the readiness notice's words.
                  failure
                  ? reasonFor(failure, activity).wording
                  : 'Offline'}</span
          >
          <span class="observation-age">· AI state updated ${formatRelativeMs(inferenceAt)}</span>
        </div>
        <p data-testid="health-ai-indexing-activity">${activity.summary}${activity.detail ? ` — ${activity.detail}` : ''}</p>
        <div
          class="queue-grid"
        >
          <span class="key secondary-text">${activity.confirmed ? 'Embed queue' : 'Last observed embed queue'}</span>
          <span class="val queue-value"
            >${progress.phase === 'unknown' ? '—' : NUM.format(progress.embeddingPending)}</span
          >
          <span class="key secondary-text">${activity.confirmed ? 'VDU queue' : 'Last observed VDU queue'}</span>
          <span class="val queue-value"
            >${progress.phase === 'unknown' ? '—' : NUM.format(progress.vduPending)}</span
          >
          <span class="key secondary-text">Context (actual)</span>
          <span class="val queue-value"
            >${this.inference.llmContextTokens != null
              ? NUM.format(this.inference.llmContextTokens)
              : 'N/A'}</span
          >
          <span class="key secondary-text">Context (configured)</span>
          <span class="val queue-value"
            >${this.inference.configuredContextTokens != null
              ? `${NUM.format(this.inference.configuredContextTokens)}`
              : 'N/A'}</span
          >
        </div>
        ${this.renderRealizedEngines()}
        ${this.renderTransitionTimeline()}
        ${this.renderTraceExplorer()}
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
        return html`<span class="history-count"
          >not loaded</span
        >`;
      }
      const onGpu = e.accelerator === 'gpu';
      const text = onGpu ? 'GPU' : e.accelerator === 'cpu' ? 'CPU' : 'loaded';
      return html`<span
        title=${e.failureReason ?? ''}
        class="health-pill gpu-layer-pill" .style=${`background: ${onGpu ? 'var(--accent-success-16)' : 'var(--surface-2)'}; color: ${onGpu ? 'var(--text-success)' : 'var(--text-secondary)'}`}
        >${text}</span
      >`;
    };
    return html`
      <div
        class="gpu-layer-details"
      >
        <div
          class="gpu-layer-heading"
        >
          Retrieval engines
        </div>
        <div
          class="gpu-layer-grid"
        >
          ${rows.map(
            ([label, e]) => html`
              <span class="key secondary-text">${label}</span>
              <span class="gpu-layer-value">${pill(e)}</span>
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
        ${!this.headCurrent() ? html`<p>Last observed graphics-card data (${this.statusAges()}) — current status unconfirmed.</p>` : nothing}
        <h3>${icon({ name: 'cpu', size: 12 })} GPU
          <span
            class="health-pill gpu-available-pill" .style=${`background: ${gpu.available ? 'var(--accent-success-16)' : 'var(--surface-2)'}; color: ${gpu.available ? 'var(--text-success)' : 'var(--text-secondary)'}`}
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
                      <div class="progress-bar ${color}" .style=${`width: ${ratio * 100}%`}></div>
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
          : html`<div class="empty compact-empty">
              Graphics-card telemetry unavailable; CPU-only operation is not established by this probe.
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
    return html`${!this.headCurrent() ? html`<p>Data protection: last observed (${this.statusAges()}); current status unconfirmed.</p>` : nothing}${renderAtRestCard(this.status)}`;
  }

  /**
   * Slice 447 §X.11.5 Phase 4 + 6: render a "Recommended for current conditions"
   * sub-section above Quick Actions when {@link #recommendedActions} is non-empty.
   * Each entry maps a (conditionId, subject) pair to an Operation that the backend
   * (or a TRUSTED plugin overlay) declared as the recovery; clicking the button
   * uses the catalog control, including its argument and confirmation policy.
   */
  /** The footer body sentence per verdict kind (595 §10.5 — single-sourced wording). */
  private renderRecommendedActions(): TemplateResult {
    const refusal = this.ownershipRefusal();
    if (refusal) return html`<div class="card section recommended"><h3>${refusal.headline}</h3><p>${refusal.body}</p></div>`;
    const indexingSummary = nothing;
    if (!this.recoveryCurrent()) return html`<section class="recommended"><h3>Recovery conditions unconfirmed</h3>
      ${this.recommendedActions.size ? html`<p>Last observed: ${this.recommendedActions.size} recoverable conditions. Checking before offering actions.</p>` : nothing}</section>`;
    if (this.recommendedActions.size === 0) return html`${nothing}`;
    const entries = Array.from(this.recommendedActions.entries()).sort(
      ([a], [b]) => a.localeCompare(b),
    );
    return html`
      <div class="card section recommended">
        <h3>⚡ Fixable now (${entries.length})</h3>
        ${indexingSummary}
        <div class="actions">
          ${entries.map(([key, invocation]) => {
            const [conditionId, subject] = key.split('|');
            const overlay = getOverlayRecovery(conditionId ?? '', subject ?? '');
            // A plugin overlay replaces the invocation. Its target-only contract supplies no
            // arguments; never attach the overridden core operation's arguments to another action.
            const target = overlay ?? invocation.target;
            const args = overlay ? {} : invocation.args;
            const conditionLabel = present({ kind: 'condition', id: conditionId ?? '' }).label;
            return html`
              <div class="recommended-action" role="group" aria-label="Fix: ${conditionLabel}" title="Fix: ${conditionId} on ${subject}">
                <span>Fix: ${conditionLabel}</span>
                <jf-operation
                  operation-id=${target}
                  context="button"
                  api-base=${this.apiBase}
                  .args=${args}
                  @op-success=${(event: CustomEvent<OpSuccessEventDetail>) => {
                    if (['core.bulk-reindex', 'core.rebuild-index', 'core.activate-installed-models'].includes(target)) {
                      this.bulkError = null;
                      this.bulkOutcomeKey = String(event.detail.structuredData?.operationKey ?? '');
                    }
                    this.error = null; void this.refresh();
                  }}
                  @op-error=${(event: CustomEvent<OpErrorEventDetail>) => {
                    if (['core.bulk-reindex', 'core.rebuild-index', 'core.activate-installed-models'].includes(target)) {
                      this.showBulkError(event.detail);
                    } else {
                      this.error = event.detail.message;
                    }
                  }}
                ></jf-operation>
                ${overlay
                  ? html`<span class="overlay-tag" title="Plugin overlay">·plugin</span>`
                  : nothing}
              </div>
            `;
          })}
        </div>
      </div>
    `;
  }

  private showBulkError(error: OpErrorEventDetail): void {
    if (!this.isConnected) return;
    this.bulkOutcomeKey = '';
    this.bulkError = error;
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
    // ops (core.recover-component, core.clear-failed-jobs, core.index-gc)
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
          ${this.ownershipRefusal() ? nothing : html`<jf-operation
            operation-id="core.reindex"
            context="button"
            api-base=${this.apiBase}
          ></jf-operation>`}
          ${this.ownershipRefusal() ? nothing : html`<jf-operation
            operation-id="core.recover-component"
            context="button"
            api-base=${this.apiBase}
            title="Recover the index component"
            .args=${{ name: 'index' }}
          ></jf-operation>`}
          ${this.ownershipRefusal() ? nothing : html`<jf-operation
            operation-id="core.rebuild-index"
            @op-success=${(event: CustomEvent<OpSuccessEventDetail>) => {
              this.bulkError = null;
              this.bulkOutcomeKey = String(event.detail.structuredData?.operationKey ?? '');
            }}
            @op-error=${(event: CustomEvent<OpErrorEventDetail>) => this.showBulkError(event.detail)}
            context="button"
            api-base=${this.apiBase}
          ></jf-operation>`}
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
    const current = healthObservationCurrent(this.failedAtMs, Date.now(), REMOTE_HEALTH_MAX_AGE_MS, this.failedReadError);
    if (this.failedJobs.length === 0) return current ? nothing : html`<section class="card section"><h3>Failed files</h3><p>Failed-file status unconfirmed.</p></section>`;
    return html`
      <div class="card section">
        ${!current ? html`<p>Last observed failures${this.failedAtMs != null ? `, read ${formatRelativeMs(this.failedAtMs)}` : ''} — current status unconfirmed.</p>` : nothing}
        <h3>
          ${icon({ name: 'alert-circle', size: 12 })} Failed files
          (${this.failedJobs.length})
        </h3>
        ${this.failedJobs.slice(0, 10).map(
          (j) => html`
            <div class="failed-row">
              <div class="path" title=${j.path ?? j.pathHash ?? ''}>${j.path ?? j.pathHash ?? 'unknown'}</div>
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
          <div class="empty">${this.eventsConnected && this.eventsAtMs != null ? 'No events yet.' : this.eventsAtMs != null
            ? `Event history unconfirmed — the stream last answered ${formatRelativeMs(this.eventsAtMs)}.` : 'Event history unconfirmed.'}</div>
        </div>
      `;
    }
    return html`
      <div class="card section">
        <h3>Recent events</h3>
        ${!healthObservationCurrent(this.eventsAtMs, Date.now(), REMOTE_HEALTH_MAX_AGE_MS, !this.eventsConnected) ? html`<p>Last observed events, received ${formatRelativeMs(this.eventsAtMs)} — current stream unconfirmed.</p>` : nothing}
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

  private ownershipRefusal() {
    return this.verdict.reasons.includes('index.ownership_unproven')
      ? readinessNotice(this.verdict, undefined,
          this.status?.readiness?.engineComponents?.['index']?.evidence)
      : null;
  }

  /**
   * S5c SC4 — the engine can't be reached: the last facts JustSearch saw, each with when it saw them,
   * and nothing that claims live state (no live controls, streams or "connected" words).
   */
  private renderLastKnown(loss: EngineLoss): TemplateResult {
    const now = Date.now();
    const seen = (ms: number | null | undefined): string => {
      const age = engineLossAge(ms ?? null, now);
      return age ? `last seen ${age}` : 'not seen yet';
    };
    const ai = this.aiState;
    const core = ai?.status?.worker?.core;
    // Each fact is dated by its own observation: these come from the held status body, received then.
    const observed = getStatusReceivedAtMs();
    // A response can carry an older worker sample. Its age is stated beside the receipt, never instead.
    const sampledAt = ai?.status?.meta?.workerRpcAtMs;
    const frozen = observed != null && sampledAt != null && (ai?.status?.meta?.workerRpcStale === true
      || observed - sampledAt > (ai?.status?.operating?.freshness?.staleAfterMs ?? 30_000));
    const sample = frozen ? `sampled by the engine ${engineLossAge(sampledAt, now) ?? ''}`.trim() : undefined;
    // A status whose Worker counts are unknown carries no Worker count at all, and a fallback block's
    // zeros are absence: neither is ever an observed number.
    const uncounted = workerCountsUnknown(ai?.status);
    const fallbackZero = (value: number | null | undefined) => value === 0 && !isWorkerReportedIndex(ai?.status);
    // The person's own files, the count Files shows everywhere; the Help-inclusive searchable count
    // is never shown in its place, so an Engine that reports no own count reads Unknown.
    const own = ai?.index.ownDocumentCount;
    const count = !uncounted && own?.known && own.value >= 0 && !fallbackZero(own.value)
      && ai?.status?.worker?.countObservations?.active?.state !== 'unknown' ? own.value : null;
    const pending = uncounted || core?.pendingJobs == null || fallbackZero(core.pendingJobs) ? null : core.pendingJobs;
    const reason = engineLossReason(loss);
    const supervisorWords: Record<string, string> = {
      starting: 'Starting', running: 'Running', stopping: 'Stopping', restarting: 'Restarting',
      exhausted: 'Stopped after repeated failures', stopped: 'Stopped',
    };
    const facts: { label: string; value: string; when: string; sample?: string }[] = [
      { label: 'Engine', value: loss.action.kind === 'restarting' ? 'Restarting' : loss.cause === 'not-ready' ? 'Not ready' : 'Can’t be reached', when: `last connected ${engineLossAge(ai?.connection.lastContactMs ?? null, now) ?? 'never'}` },
      ...(loss.supervisor ? [{ label: 'Engine process', value: supervisorWords[loss.supervisor.state] ?? loss.supervisor.state,
        when: `reported ${engineLossAge(loss.supervisorAtMs, now) ?? ''}`.trim() }] : []),
      ...(loss.supervisor?.notReady ? [{ label: 'Engine said', value: loss.supervisor.notReady, when: `reported ${engineLossAge(loss.supervisorAtMs, now) ?? ''}`.trim() }] : []),
      { label: 'Index', value: core?.indexState ? core.indexState.charAt(0) + core.indexState.slice(1).toLowerCase().replaceAll('_', ' ') : 'Unknown', when: seen(core?.indexState ? observed : null), sample: core?.indexState ? sample : undefined },
      { label: 'Searchable files', value: count == null ? 'Unknown' : NUM.format(count), when: seen(count == null ? null : observed), sample: count == null ? undefined : sample },
      { label: 'Files waiting', value: pending == null ? 'Unknown' : NUM.format(pending), when: seen(pending == null ? null : observed), sample: pending == null ? undefined : sample },
    ];
    return html`
      <div class="header">
        <div class="header-left">
          <h2>${icon({ name: 'server', size: 16 })} System Health</h2>
          <jf-status-badge tone="error"><span class="dot"></span>${loss.cause === 'not-ready' ? 'Engine not ready' : 'Engine unreachable'}</jf-status-badge>
        </div>
      </div>
      <div class="body" data-testid="health-last-known">
        <div class="card section">
          <h3>${loss.cause === 'not-ready' ? 'JustSearch’s engine isn’t ready' : 'JustSearch can’t reach its engine'}</h3>
          <p>${reason ? `${reason} ` : ''}These are the last facts JustSearch saw, each with when it saw them. Nothing here is live.</p>
          ${facts.map(fact => html`<div class="data-row" data-fact=${fact.label}>
            <span class="key">${fact.label}</span>
            <span class="val">${fact.value} <span class="when">· ${fact.when}</span>${fact.sample ? html` <span class="sample">· ${fact.sample}</span>` : nothing}</span>
          </div>`)}
        </div>
      </div>
    `;
  }

  override render(): TemplateResult {
    if (this.engineLoss?.lost) return this.renderLastKnown(this.engineLoss);
    const refusal = this.ownershipRefusal();
    const progress = this.indexingProgress();
    // Tempdoc 571 §11 / 578 — the §11.8 hand-rolled Health|Logs tab strip is RETIRED: Logs is now a
    // sibling member of the System hub (one <jf-surface-tabs>), so Health renders only its own body.
    // The System host mounts only the active member, so Health's streams are already tab-scoped (the
    // §11.8 SSE-suspend wiring is no longer needed).
    return html`
      ${this.renderHeader()}
      <div class="body">
        ${refusal ? nothing : html`<div data-testid="health-indexing-pause">
          ${progress.pause || !progress.activity.confirmed ? html`<p>${[progress.activity.summary, progress.activity.detail].filter(Boolean).join(' — ')}</p>` : nothing}
          <jf-indexing-controls .progress=${progress} api-base=${this.apiBase}></jf-indexing-controls>
        </div>`}
        ${this.error && !refusal
          ? html`<jf-error-alert tone="error" .onDismiss=${() => (this.error = null)}
              >${this.error}</jf-error-alert
            >`
          : nothing}
        <!-- 578 Workstream A — the live "Now" strip (what's running right now), folded in above the
             snapshot. Reuses <jf-system-self-view> in compact mode; no own heading. -->
        ${this.renderOverviewMetrics()}
        <dl class="health-rows" aria-label="System health">
          ${this.healthRows().map(row => html`<div class="health-row" data-health-row=${row.id}>
            <dt>${row.label}</dt><dd>${row.status ? html`<p>${row.status}</p><jf-button label=${row.action}
              .onActivate=${() => this.dispatchEvent(new CustomEvent('navigate-with-context', { detail: { target: row.destination, state: {} }, bubbles: true, composed: true }))}>${row.action}</jf-button>` : nothing}</dd>
          </div>`)}
        </dl>
        ${this.renderRecommendedActions()}
        ${unresolvedHealthOperations(getHealthRemoteObservations().operations, getSeenCursor()).map(operation => html`<section class="card section" aria-label=${operation.label}>
          <h3>${operation.label}</h3><p>${operation.fresh ? operation.outcome?.message ?? operation.outcome?.phase ?? operation.outcome?.state ?? 'Outcome unconfirmed' : 'Last observed — outcome unconfirmed'}</p>
        </section>`)}
        <details .open=${this.diagnosticsOpen} @toggle=${(event: Event) => { this.diagnosticsOpen = (event.target as HTMLDetailsElement).open; diagnosticsExpanded = this.diagnosticsOpen; }}>
          <summary>Diagnostics</summary>
          ${!this.statusCurrency().worker ? html`<p class="diagnostic-currency">Diagnostic values are last observed (${this.statusAges()}); current status is unconfirmed.</p>` : nothing}
        ${this.renderStats()}
        <!-- Tempdoc 596 §16.5 — "what can I do right now": every capability affordance's availability +
             its inline remedy, projected from the same authority the live controls read. -->
        <jf-capability-map .aiState=${this.aiState}></jf-capability-map>
        <div class="grid-2">${this.renderConnection()} ${this.renderGpu()}</div>
        <div class="grid-2">${this.renderQueueDb()} ${this.renderAiEngine()}</div>
        <div class="grid-2">${this.renderAtRest()}</div>
        ${this.renderFailed()}
        ${this.renderActions()}
        ${this.renderEvents()}
        </details>
        ${this.bulkError ? html`<jf-error-alert tone="error" .onDismiss=${() => (this.bulkError = null)}>
          ${this.bulkError.message}
          ${this.bulkError.errorCode && !this.bulkError.message.endsWith(` (Code: ${this.bulkError.errorCode})`) ? html`<small class="block-detail">Code: ${this.bulkError.errorCode}</small>` : nothing}
        </jf-error-alert>` : nothing}
        ${this.bulkOutcomeKey ? html`<jf-operation-outcome-detail
          operation-key=${this.bulkOutcomeKey} api-base=${this.apiBase}></jf-operation-outcome-detail>` : nothing}
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-health-surface')) {
  customElements.define('jf-health-surface', HealthSurface);
}
