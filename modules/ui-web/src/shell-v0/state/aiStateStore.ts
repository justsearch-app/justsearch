// SPDX-License-Identifier: Apache-2.0
/**
 * aiStateStore — unified AI state model (tempdoc 508).
 *
 * Composes inference poll + status poll + activity signals into a single
 * subscribable snapshot. Replaces per-component partial views of AI state
 * with one source of truth.
 *
 * Consumers subscribe via `subscribeAiState()`. Chat views report activity
 * via `setAiActivity()`. The store subscribes to existing pollers — it does
 * not replace them.
 *
 * Tempdoc 548 §1 / R1a — **state-as-signal (genuine, not a tick bolt-on).**
 * This is the first store converted to the thesis's signal-core pattern:
 *   - The inputs (inference snapshot, status snapshot, last-success stamps,
 *     activity, install state) are `signal`s — the source of truth.
 *   - `aiState` is a `computed` over them: the derived snapshot recomputes
 *     automatically and is memoized, replacing the hand-rolled
 *     `recompute()` + `emit()` orchestration (and its silent-notify
 *     boilerplate). No manual `notify()` call survives.
 *   - `subscribeAiState` is a thin **shim** over a `Signal.subtle.Watcher`:
 *     the signal drives fan-out; the callback contract (incl. the
 *     plugin-style sync call on subscribe) is preserved so consumers and
 *     the (internal-only) API are unchanged. Subsequent updates fan out on
 *     a microtask (signal-idiomatic batching) — the sync-on-subscribe value
 *     is delivered immediately.
 * Time is not a reactive value, so the staleness timer bumps a `clockTick`
 * signal when reachability flips, which the connection derivation reads.
 */

import { signal, computed, Signal } from '@lit-labs/signals';
import {
  subscribeInference,
  setInferenceApiBase,
  type InferenceSnapshot,
} from '../utils/inferencePoll.js';
import {
  subscribeStatus,
  setStatusApiBase,
  refreshStatusNow as refreshStatusPollNow,
  type StatusSnapshot,
} from '../utils/statusPoll.js';
import {
  subscribeAiInstall,
  setAiInstallApiBase,
  __resetAiInstallPollForTest,
  type AiInstallSnapshot,
  type InstallStatus,
  type AiRuntimeStatus,
  type PackImportStatus,
} from '../utils/aiInstallPoll.js';
import { type Maybe, known, UNKNOWN, mapKnown } from './known.js';
// 813 §19 (W2) — the projection's own admission test, reused (not re-implemented) by the high-water
// stamp below so the store and the selector agree on what counts as a worker-reported snapshot.
import {
  ENRICH_SETTLE_SAMPLE_CAP,
  enrichSettledSum,
  isWorkerReportedIndex,
  selectIndexingPhase,
  type EnrichSettleSample,
} from './indexingProgress.js';
import { humanizeSeconds, elapsedSecondsSince } from './startupEstimate.js';
// Tempdoc 649 — the ONE reachability authority (positive contact across ANY channel), registered as
// the `connection` liveness domain. `aiStateStore` is its sole render site (imports `isOriginReachable`).
import {
  getLastOriginContactMs,
  isOriginReachable,
  bumpOriginContact,
  __resetOriginContactForTest,
} from './originContact.js';
import {
  computeStability,
  computeVerdict,
  verdictHeadline,
  verdictTone,
  type Stability,
  type SystemHealthVerdict,
} from './verdict.js';
// Tempdoc 663 Design pass 2 - the AI-engine lifecycle rollup (594/595/596's fourth sibling), computed
// ONCE here from purely observed signals, alongside `stability`/`verdict`/`runtime`. Local, surface-only
// UI intent (a just-clicked button) is NOT folded in here - see `applyLocalIntent` in `aiVerdict.ts`.
import {
  computeAiEngineVerdict,
  aiEngineHeadline,
  aiEngineTone,
  type AiEngineVerdict,
} from './aiVerdict.js';
import type { NoticeTone } from '../utils/statusTone.js';

// Re-export the raw snapshot types — the store is the single observed-state
// authority, so consumers type `aiState.status` / `aiState.inference` from here.
export type { StatusSnapshot, InferenceSnapshot, InstallStatus, AiRuntimeStatus, PackImportStatus };

// ── Types ──

export interface AiCapabilities {
  chat: boolean;
  rag: boolean;
  extract: boolean;
  embedding: boolean;
}

export interface AiConnection {
  reachable: boolean;
  /** Last *poll* success (the data-freshness stamp). */
  lastSuccessMs: number | null;
  /**
   * Tempdoc 649 — last POSITIVE CONTACT of any channel (poll success OR an SSE frame/heartbeat), the
   * reachability stamp. Distinct from `lastSuccessMs` (poll freshness): under load the poll can lag while
   * a stream keeps contact fresh. Surfaced so the CONNECTION panel can show both timestamps honestly.
   */
  lastContactMs: number | null;
  consecutiveFailures: number;
}

export interface AiRuntime {
  // 663 Stage 2 — 'transitioning' is a REAL backend Mode value (app-api Mode.java: ONLINE, INDEXING,
  // TRANSITIONING, OFFLINE), not a stray string. Previously silently collapsed to 'unknown' here,
  // which is why BrainSurface had to bypass this projection and read the raw untyped snapshot.
  mode: 'offline' | 'online' | 'indexing' | 'transitioning' | 'starting' | 'unknown';
  modelId: string | null;
  modelLabel: string | null;
  contextWindow: number | null;
  /**
   * Tempdoc 883 decision 1 / ADR-0047 — the DERIVED window record beside the observed
   * `contextWindow` above: which ladder rung the engine was launched at and WHY, with the slot
   * count and KV cache type that rung was budgeted against. Null when this process launched no
   * server (nothing started, or an adopted external one whose window it did not choose), which is
   * why it is a separate field rather than a widening of `contextWindow`: that one is the
   * observation (`/props` `n_ctx`) and stays authoritative, this one is the intent.
   */
  contextWindowDerived: {
    rung: number;
    reason: string | null;
    slots: number | null;
    kvType: string | null;
  } | null;
  gpu: { available: boolean; description: string } | null;
  installed: Maybe<boolean>;
  installing: Maybe<boolean>;
  // Tempdoc 601 §20 — wall-clock stamp captured when the model entered the `starting` window
  // (null otherwise). One source for the live "Starting… Ns" elapsed: the status pill
  // (computeStatusLabel) AND BrainSurface read it off the runtime object.
  loadStartedAtMs: number | null;
}

export type ActivityState = 'idle' | 'thinking' | 'streaming' | 'extracting';

export interface AiActivity {
  state: ActivityState;
  shapeId: string | null;
  startedAtMs: number | null;
  canCancel: boolean;
  cancel: (() => void) | null;
}

export interface AiIndex {
  documentCount: Maybe<number>;
  /**
   * Tempdoc 811 C-4 — the population a DEFAULT-scope search can actually return: `documentCount`
   * minus the collections the default scope excludes (today `agent-history`). `documentCount` still
   * describes the whole non-chunk index, which is the right number for Health's "Files"; a
   * user-facing "Searching N documents" string must read THIS one, because it is the only one of
   * the two the user could enumerate. UNKNOWN on a backend that predates the field — a consumer
   * falls back to `documentCount` there, but a KNOWN `0` is a real value and must be shown as 0.
   */
  searchableDocumentCount: Maybe<number>;
  pendingJobs: Maybe<number>;
  embeddingPending: Maybe<number>;
  embeddingBlocked: Maybe<boolean>;
  embeddingQueueSize: Maybe<number>;
  vduQueueSize: Maybe<number>;
}

/**
 * tempdoc 644 — realized retrieval-engine state, one entry per engine, projected by
 * `computeRealized` from `status.worker.gpu`. The single FE authority for "which engine actually
 * loaded, and on which device" (the `accelerator`), so a surface (HealthSurface) reads
 * `aiState.realized.*` instead of re-deriving it from the raw snapshot — the fork-prevention the
 * realized-capability register guards.
 */
export type EngineAccelerator = 'gpu' | 'cpu' | null;
export interface EngineRealized {
  loaded: boolean;
  // null = not loaded, OR the ORT device is not yet probed (lazy init — e.g. the reranker only
  // runs at query time), distinct from a known CPU realization.
  accelerator: EngineAccelerator;
  failureReason: string | null;
}
export interface AiRealized {
  reranker: EngineRealized;
  embed: EngineRealized;
  splade: EngineRealized;
}

export type StatusTier = 'online' | 'degraded' | 'offline' | 'disconnected';

/**
 * Connectivity phase — the single "do we have data yet" authority (§2.B).
 *
 * Four states, distinguished by (have we ever succeeded?) × (is the last
 * success still fresh?):
 *  - `connecting`   — never succeeded yet, still within the grace window.
 *  - `connected`    — last success is fresh.
 *  - `stale`        — succeeded before, but the last success aged out; we hold
 *                     last-known values and show a reconnecting indicator
 *                     (don't wipe to "0 files", don't claim the data is fresh).
 *  - `disconnected` — never succeeded and the grace window elapsed (gave up).
 */
export type ConnectionPhase = 'connecting' | 'connected' | 'stale' | 'disconnected';

/** Projected from the backend's readiness composites — the one degradation authority. */
export interface ReadinessView {
  retrieval: 'ready' | 'degraded' | 'unknown';
  aiFeatures: 'ready' | 'degraded' | 'unknown';
  // Tempdoc 600 Design A: the reindex/compat cause is no longer a separate boolean here — it is
  // carried as a real reason code in `reasonCodes` (index.blocked_legacy / .schema_mismatch / …),
  // emitted on the `retrieval` composite by the worker. The verdict reads it via `reasonCodes`, and
  // `readinessNotice.isReindexCause` recognizes it. This collapses the prior fork (boolean + code).
  reasonCodes: string[];
}

export interface AiState {
  phase: ConnectionPhase;
  readiness: Maybe<ReadinessView>;
  capabilities: AiCapabilities;
  connection: AiConnection;
  runtime: AiRuntime;
  activity: AiActivity;
  index: AiIndex;
  /** tempdoc 644 — realized retrieval-engine state (loaded? GPU/CPU? failure), per engine. */
  realized: AiRealized;
  statusLabel: string;
  statusTier: StatusTier;
  /**
   * Tempdoc 649 — the ONE tone for the status-bar pill + liveness dot, the matched sibling of
   * `statusLabel`: both project from the one verdict (`verdictTone`). Replaces the `statusTier`→tone
   * fork that rendered the calm "Catching up…" (`busy`) state as amber `degraded`. Calm in-flux → `info`,
   * real degradation → `warning`/`error`, settled-online → `success`. (`statusTier` stays for any coarse
   * non-tone use; it no longer drives connection-status colour.)
   */
  statusTone: NoticeTone;
  /**
   * 595 §4.1 — the ONE "is what we're showing settled, or in flux?" axis. Every
   * transition-/freshness-sensitive renderer consults this instead of treating a
   * provisional value (a rebuild's 0 docs, a stale feed) as settled.
   */
  stability: Stability;
  /**
   * 595 §4.2 — the ONE system-health verdict. Header, footer, status bar all
   * consume this (none recompute); `computeStatusLabel`/`computeStatusTier` are
   * its status-bar projections. The `attention` overlay (open recovery
   * conditions) is layered locally by HealthSurface on top of this.
   */
  verdict: SystemHealthVerdict;
  /**
   * Tempdoc 807 A.3 — is the retained snapshot still a LIVE observation, or only a past
   * measurement? Projected ONCE from contact reachability by {@link isSnapshotLive}; every surface that
   * renders raw snapshot fields (progress, coverage, queue counts, capability counts, the CONN dot)
   * consults this instead of inventing its own staleness heuristic. `false` ⟹ degrade rather than
   * assert: stop animating, say "last known", make live-backend-preconditioned controls unavailable.
   */
  snapshotLive: boolean;
  /**
   * The last-known raw poll snapshots (B7). Consumers that need fields beyond
   * the projection above (GPU, memory ceiling, uptime, index state, inference
   * queues) read these instead of running a SECOND status/inference poll — the
   * store is the single observed-state authority. `null` until the first
   * successful poll; retained (not wiped) when the connection goes `stale`.
   */
  status: StatusSnapshot | null;
  inference: InferenceSnapshot | null;
  /**
   * 595 §15.3 (E2) — the last index counts observed while the system was SETTLED. During a
   * provisional window the worker fallback reports a real-but-transient `0` / `UNAVAILABLE`
   * (a *successful* poll, so `ConnectionPhase.stale` retention does not shadow it); renderers
   * show this dimmed "last known" value instead of collapsing to `…`, so a healthy rebuild
   * stops reading as data loss. `null` until the first settled poll. Stamped imperatively in
   * `onStatusUpdate` (the poll callback), never written by a `computed`, so the graph is acyclic.
   *
   * <p>811 C-4: `searchableDocumentCount` is the default-search-scope population (see
   * {@link AiIndex}); `null` means the backend did not report it, NOT "zero searchable documents" —
   * a consumer falls back to `documentCount` on `null` and shows a real `0` as `0`.
   */
  lastSettledIndex: {
    documentCount: number;
    searchableDocumentCount: number | null;
    indexSizeBytes: number | null;
  } | null;
  /**
   * 813 §19 (W2) — the largest job backlog observed during the CURRENT drain episode, and the ONLY
   * cross-poll memory the indexing surfaces have. `selectIndexingProgress` is a pure function of a
   * single snapshot, so it cannot know that a backlog of 400 was 1,600 two polls ago; without a
   * remembered maximum the indexing affordance can only ever be indeterminate. Stamped imperatively
   * in `onStatusUpdate` (the poll callback), never written by a `computed`, so the graph is acyclic
   * — the same discipline as {@link lastSettledIndex} above.
   *
   * <p>Only WORKER-REPORTED snapshots are admitted (`isWorkerReportedIndex`): the fallback block's
   * `pendingJobs: 0` is absence, not a drained queue, and reading it as a drain would reset the
   * denominator mid-episode. A genuine drain to 0 DOES reset it, so the next episode measures itself
   * instead of inheriting a stale ceiling.
   */
  episodeMaxPendingJobs: number;
  /**
   * 813 §20 — the ENRICHMENT half's cross-poll memory: a short trail of the enrichment blend's
   * settled sum, each stamped with the wall-clock instant it was observed. The wire carries no
   * enrichment-throughput gauge (`core.recentDocsPerSec` measures the INDEXING pipeline), so an
   * enrichment estimate has no basis at all without remembering how much settled between polls.
   *
   * Stamped imperatively in `onStatusUpdate` through the ONE settled-sum authority
   * (`enrichSettledSum`), never written by a `computed` — the same discipline as
   * {@link episodeMaxPendingJobs} above. CLEARED whenever the derived phase is not `enriching`: a
   * fresh enrichment episode must measure itself rather than inherit a rate from an hour ago, and
   * intervals spanning a phase change would compare two different regimes.
   */
  enrichSettleSamples: readonly EnrichSettleSample[];
  /**
   * 663 Stage 3 — the last-known install/runtime/pack snapshots, fed by the shared, always-on
   * `aiInstallPoll` (mirrors `status`/`inference` above: `null` until the first successful poll,
   * retained — never regressed to `null` — on a later transient failure). BrainSurface consumes
   * these instead of running its own one-shot, non-retrying fetch (the structural cause of the
   * "stuck on Connecting… forever" bug, tempdoc 663 §O).
   */
  installStatus: InstallStatus | null;
  runtimeStatus: AiRuntimeStatus | null;
  packStatus: PackImportStatus | null;
  /**
   * Tempdoc 663 Design pass 2 - the ONE AI-engine lifecycle verdict, the fourth 594/595/596 sibling.
   * Computed once here from `installStatus`/`runtimeStatus`/`runtime`/`connection.reachable` - every
   * consumer (BrainSurface, any future status-pill/capability-map use) reads this instead of privately
   * re-deriving it. Does NOT include surface-local optimistic intent (a just-clicked button) - overlay
   * that via `applyLocalIntent` (`aiVerdict.ts`) on the consuming surface, not here.
   */
  aiEngine: AiEngineVerdict;
}

const STALE_THRESHOLD_MS = 15_000; // 3x the inference poll interval

const INITIAL_ACTIVITY: AiActivity = {
  state: 'idle',
  shapeId: null,
  startedAtMs: null,
  canCancel: false,
  cancel: null,
};

// ── Input signals (the source of truth) ──

const inferenceSig = signal<InferenceSnapshot | null>(null);
const statusSig = signal<StatusSnapshot | null>(null);
const lastInferenceSuccessSig = signal<number | null>(null);
const lastStatusSuccessSig = signal<number | null>(null);
const activitySig = signal<AiActivity>(INITIAL_ACTIVITY);
const installStateSig = signal<Maybe<{ installed: boolean; installing: boolean }>>(UNKNOWN);
// 663 Stage 3 — raw install/runtime/pack snapshots from the shared `aiInstallPoll`.
const installStatusSig = signal<InstallStatus | null>(null);
const runtimeStatusSig = signal<AiRuntimeStatus | null>(null);
const packStatusSig = signal<PackImportStatus | null>(null);
// 595 §15.3 (E2) — retained last-settled index counts. Written ONLY by the poll
// callback (`onStatusUpdate`), read by `buildSnapshot`; no computed writes it.
const lastSettledIndexSig = signal<{
  documentCount: number;
  searchableDocumentCount: number | null;
  indexSizeBytes: number | null;
} | null>(null);
// 813 §19 (W2) — the drain episode's high-water backlog. Written ONLY by the poll
// callback (`onStatusUpdate`), read by `buildSnapshot`; no computed writes it.
const episodeMaxPendingJobsSig = signal(0);
// 813 §20 — the enrichment episode's settle trail. Written ONLY by the poll
// callback (`onStatusUpdate`), read by `buildSnapshot`; no computed writes it.
const NO_ENRICH_SAMPLES: readonly EnrichSettleSample[] = [];
const enrichSettleSamplesSig = signal<readonly EnrichSettleSample[]>(NO_ENRICH_SAMPLES);
// Time is not reactive; the staleness timer bumps this when reachability
// would flip, so the `connection` derivation re-evaluates.
const clockTickSig = signal(0);
// Tempdoc 601 §19 — wall-clock stamp captured when the model load ENTERS the `starting` window
// (cleared when it leaves), so the status pill can render a live measured "Starting… Ns" count-up,
// mirroring how `activity.startedAtMs` backs "Thinking… Ns". A static timestamp, not a ticking value:
// `computeStatusLabel` computes the elapsed at render, and the existing 5s `clockTick` drives refresh.
const loadStartedAtSig = signal<number | null>(null);

// ── Lifecycle (non-reactive) ──

const listeners = new Set<(s: AiState) => void>();
let unsubInference: (() => void) | null = null;
let unsubStatus: (() => void) | null = null;
let unsubAiInstall: (() => void) | null = null;
let started = false;
let stalenessTimer: number | null = null;
let _startedAtMs = 0;

// ── Derived state computation (reads the input signals) ──

function friendlyModel(id: string | null | undefined): string | null {
  if (!id) return null;
  const stripped = id
    .replace(/[-.](?:(?:I?Q|[Ff])\d[_A-Z0-9]*)\.gguf$/i, '')
    .replace(/\.gguf$/i, '');
  // Q16: present a clean name, not a raw id — underscores → spaces, collapsed.
  return stripped.replace(/_/g, ' ').replace(/\s+/g, ' ').trim();
}

function computeCapabilities(): AiCapabilities {
  const inference = inferenceSig.get();
  const status = statusSig.get();
  // Tempdoc 737 §12b/§15 soft-off: a background procedure may hold the engine 'online' while the user
  // has DISABLED chat (chatEnabledSpec=false) — chat is NOT available then, even though mode==='online'.
  // `chatEnabledSpec` undefined (older backend) leaves the legacy behaviour unchanged.
  const chatDisabledBySpec = status?.inference?.chatEnabledSpec === false;
  const chat = inference?.mode === 'online' && inference?.available === true && !chatDisabledBySpec;
  const docs = status?.worker?.core?.indexedDocuments ?? 0;
  return {
    chat,
    rag: chat && docs > 0,
    extract: chat,
    embedding: status?.embedding?.compatState === 'COMPATIBLE',
  };
}

/**
 * The one staleness derivation — the single source for "have we ever
 * succeeded, and is the last success still fresh". Both `computeConnection`
 * and `computePhase` read this (B3: removes the ~95% duplicated threshold
 * logic that previously lived in each).
 */
interface Staleness {
  lastSuccessMs: number | null;
  /** No success ever AND the start-up grace window has elapsed. */
  neverConnected: boolean;
  /** Succeeded before, but the last success aged past the threshold. */
  stale: boolean;
}

function computeStaleness(): Staleness {
  // Depend on the clock tick so staleness flips re-evaluate this derivation.
  void clockTickSig.get();
  const lastInf = lastInferenceSuccessSig.get();
  const lastStatus = lastStatusSuccessSig.get();
  const lastSuccess =
    lastInf !== null && lastStatus !== null
      ? Math.max(lastInf, lastStatus)
      : (lastInf ?? lastStatus);
  const now = Date.now();
  return {
    lastSuccessMs: lastSuccess,
    neverConnected: lastSuccess === null && started && now - _startedAtMs > STALE_THRESHOLD_MS,
    stale: lastSuccess !== null && now - lastSuccess > STALE_THRESHOLD_MS,
  };
}

/**
 * Tempdoc 649 — REACHABILITY (is the backend alive at all?), derived from the most recent POSITIVE
 * CONTACT of ANY channel: a poll success (`lastInference/StatusSuccessSig`) OR any SSE frame/heartbeat
 * (`getLastOriginContactMs`). This is DELIBERATELY SEPARATE from poll-freshness (`computeStaleness`):
 * under load the cheap polls get starved behind the browser's 6-per-host connection limit, but the
 * always-on SSE streams keep heartbeating (15s) — so a poll-only signal wrongly reads "disconnected"
 * while the backend is provably alive. Reachability via the ONE `isOriginReachable` authority fixes
 * that. Before the first contact, the start-up grace window must elapse before we are willing to call
 * the origin unreachable (mirrors `neverConnected`), so startup shows "Connecting…", not a false alarm.
 */
function computeReachability(): { reachable: boolean; lastContactMs: number | null } {
  // Depend on the clock tick so reachability flips re-evaluate (the stream stamp is a plain global; the
  // 5s `checkStaleness` tick bumps `clockTickSig` when the value would change — see checkStaleness).
  void clockTickSig.get();
  const lastInf = lastInferenceSuccessSig.get();
  const lastStatus = lastStatusSuccessSig.get();
  const lastStream = getLastOriginContactMs();
  const lastContactMs = Math.max(lastInf ?? 0, lastStatus ?? 0, lastStream ?? 0) || null;
  const now = Date.now();
  if (lastContactMs === null) {
    // No contact of any kind yet: not reachable, but not "disconnected" until the grace window elapses.
    return { reachable: started && now - _startedAtMs <= STALE_THRESHOLD_MS, lastContactMs: null };
  }
  return { reachable: isOriginReachable(lastContactMs, now), lastContactMs };
}

function computeConnection(): AiConnection {
  // `lastSuccessMs` stays the last POLL success (the data-freshness stamp consumers read); `reachable`
  // is the contact-based truth (tempdoc 649). They are different facts and must not be conflated.
  const { lastSuccessMs } = computeStaleness();
  const { reachable, lastContactMs } = computeReachability();
  return {
    reachable,
    lastSuccessMs,
    lastContactMs,
    consecutiveFailures: reachable ? 0 : 1,
  };
}

/**
 * Tempdoc 883 decision 1 — project the wire's `contextWindow` record onto `AiRuntime`. A rung of
 * zero/absent is NOT a derived window: the block only exists while a server this process launched
 * is running, so anything without a positive rung is reported as "no derived window" rather than as
 * a rung of 0.
 */
function deriveContextWindow(
  inference: InferenceSnapshot | null,
): AiRuntime['contextWindowDerived'] {
  const cw = inference?.contextWindow;
  if (!cw || typeof cw.rung !== 'number' || cw.rung <= 0) return null;
  return {
    rung: cw.rung,
    reason: cw.reason ?? null,
    slots: typeof cw.slots === 'number' && cw.slots > 0 ? cw.slots : null,
    kvType: cw.kvType ?? null,
  };
}

function computeRuntime(): AiRuntime {
  const inference = inferenceSig.get();
  const installState = installStateSig.get();
  // Tempdoc 737 §12c: the runtime-authority engine axis is projected additively onto the /api/status
  // inference block; PREFER it over the legacy /api/inference/status `mode`. Fall back to `mode` when
  // absent (older backend) — the fallback retires with the phase/mode aliases (§12d).
  const authInf = statusSig.get()?.inference;
  const engineState = authInf?.engineState;
  const mode = inference?.mode;
  let resolvedMode: AiRuntime['mode'] = 'unknown';
  if (engineState) {
    if (engineState === 'Healthy') resolvedMode = 'online';
    else if (engineState === 'Down')
      resolvedMode = authInf?.engineReason === 'gpu-yielded-to-indexing' ? 'indexing' : 'offline';
    // Starting/Recovering: keep the live-load 'starting' UI (ETA sub-text) when the load signal is
    // set, else the generic 'transitioning'.
    else if (engineState === 'Starting' || engineState === 'Recovering')
      resolvedMode = inference?.starting ? 'starting' : 'transitioning';
  } else if (mode === 'online') resolvedMode = 'online';
  else if (mode === 'indexing') resolvedMode = 'indexing';
  else if (mode === 'offline') resolvedMode = 'offline';
  // `starting` (an explicit live-load signal) takes priority over the raw 'transitioning' mode —
  // preserves the existing 'starting' UI (ETA sub-text) for the model-load case. Only a
  // 'transitioning' mode WITHOUT the starting flag (a generic mode swap not caused by a load) falls
  // through to the new 'transitioning' branch below (663 Stage 2 — previously silently 'unknown').
  else if (inference?.starting) resolvedMode = 'starting';
  else if (mode === 'transitioning') resolvedMode = 'transitioning';

  return {
    mode: resolvedMode,
    modelId: inference?.activeModelId ?? null,
    modelLabel: friendlyModel(inference?.activeModelId),
    contextWindow: inference?.llmContextTokens ?? null,
    contextWindowDerived: deriveContextWindow(inference),
    gpu: inference?.gpu
      ? {
          available: inference.gpu.cudaAvailable ?? false,
          description: inference.gpu.vramDescription ?? '',
        }
      : null,
    installed: mapKnown(installState, (s) => s.installed),
    installing: mapKnown(installState, (s) => s.installing),
    loadStartedAtMs: loadStartedAtSig.get(),
  };
}

/**
 * Tempdoc 806 B.2 (round-12 finding) — the ONE predicate for "does the system verdict own the status
 * readout, or does the AI-engine verdict?". `computeStatusLabel` and `computeStatusTone` MUST agree
 * here, because they are rendered as a SINGLE indicator: the liveness dot and the words beside it
 * (`LivenessReadout`, `core.retrieval` on the Health Connection card) and the status-bar pill.
 *
 * Round 12 photographed them disagreeing — a danger-red dot beside the word "Online" — because
 * `degraded` was listed in the tone branch (tempdoc 649) and never added to the label branch, so the
 * dot reported the system verdict while the text beside it reported the AI engine. The label was the
 * wrong half: `computeStatusLabel`'s own doc comment already claims "the bar can no longer say
 * 'Online' while Health says 'Service degraded'" — the Health badge projects `verdictHeadline` for
 * every kind, including `degraded`, and the bar did not.
 */
function verdictOwnsStatus(verdict: SystemHealthVerdict): boolean {
  return (
    verdict.kind === 'connecting' ||
    verdict.kind === 'unreachable' ||
    verdict.kind === 'transitioning' ||
    verdict.kind === 'degraded'
  );
}

/**
 * Tempdoc 807 A.3 (round-13 R13-F2) — the ONE liveness predicate: "is what we last observed still a
 * LIVE observation, or only a past measurement?". Sibling of {@link verdictOwnsStatus}: both are pure
 * projections of the SAME verdict authority, so a surface can never invent a second detection
 * mechanism (that divergence is how the status label and the CONN dot drifted apart, 806 W2).
 *
 * `verdictOwnsStatus` governs the status line's own WORDING and TONE. This governs everything the
 * status line does not: the surfaces that render fields off the retained snapshot. Round 13
 * photographed an animating "Building semantic search 2.0% · 5,084 pending", "4/4 active" and a green
 * CONN dot with BOTH java processes dead — the values were right, their TENSE was not.
 *
 * THE RULE: liveness is a CONTACT fact, never a verdict-kind classification. The snapshot is live
 * exactly while the origin is reachable — `computeReachability()`, the same `reachableViaContact`
 * `computeVerdict` itself consumes. The first cut of this predicate read the verdict KIND instead
 * and claimed the two non-live kinds were "exactly the two `computeVerdict` mints when
 * `reachableViaContact` is false". That was false at source (round-13 review): `computeStability`
 * returns from SIX higher-precedence branches before it can reach the `phase === 'stale'` one, and
 * every one of them reads a RETAINED snapshot field — `indexState: UNAVAILABLE` (worker-restart),
 * `migrationState` SWITCHING/MIGRATING, a building≠active generation, serving-search≠serving-ingest,
 * `catchingUp`. `statusSig` is retained on a failed poll, so with the Worker dying first (the
 * ordinary case: the backend writes `indexState: UNAVAILABLE`, then the Head dies) the verdict
 * stayed `transitioning`/`worker-restart` FOREVER, never `channel-stale`, and the whole fix was
 * silently disabled. Contact cannot be retained: it is a stamp of when we last heard anything.
 *
 * AGE is already accounted for and needs no new threshold or second authority: reachability is
 * earned by positive contact within `STREAM_WATCHDOG_STALE_MS` (`originContact.isOriginReachable`,
 * the generated 40s stream-watchdog window = >2× the 15s heartbeat), and before the first contact
 * the boot grace window applies (`computeReachability`, mirroring `neverConnected`) so startup is
 * live, not a false alarm.
 */
export function isSnapshotLive(connection: Pick<AiConnection, 'reachable'>): boolean {
  return connection.reachable;
}

function computeStatusLabel(
  verdict: SystemHealthVerdict,
  runtime: AiRuntime,
  act: AiActivity,
  aiEngine: AiEngineVerdict,
): string {
  // 595 §10.5 — the status bar is a PROJECTION of the one verdict, with the
  // transient ACTIVITY overlay layered on top (active work implies a live
  // connection, so it takes precedence). The connection/runtime phrasing
  // ("Connecting…/Reconnecting…/Rebuilding…/Disconnected") now flows from the
  // verdict, not a parallel phase read — so the bar can no longer say "Online"
  // while Health says "Service degraded".
  if (act.state === 'thinking') {
    const elapsed = act.startedAtMs
      ? Math.round((Date.now() - act.startedAtMs) / 1000)
      : 0;
    return elapsed > 2 ? `Thinking… ${elapsed}s` : 'Thinking…';
  }
  if (act.state === 'streaming') return 'Streaming';
  if (act.state === 'extracting') return 'Extracting';
  // Provisional / unreachable phrasing comes from the ONE verdict-wording source
  // (595 §15.1 — `verdictHeadline`), not a parallel switch here, so the status bar
  // and the Health badge cannot drift apart (§2.B: no confident "offline" default
  // while connecting/reconnecting/transitioning). Backend-connectivity problems
  // outrank AI-install state — nothing AI-related is actionable if the backend
  // itself is unreachable, so this check stays first, unchanged (Design pass 3 §V).
  // Tempdoc 806 B.2: `degraded` joined this set via `verdictOwnsStatus` — the tone branch already had
  // it, so the pair rendered a warning/danger dot next to the AI engine's "Online".
  if (verdictOwnsStatus(verdict)) {
    return verdictHeadline(verdict);
  }
  // Design pass 3 — the AI-specific label now comes from the ONE AI-engine presentation
  // projection (`presentAiEngineVerdict`), not a parallel `runtime.mode` switch — the exact same
  // "one wording source, no drift" discipline the verdict branch above already has. `starting`
  // stays a special case here (unchanged from before): it needs the LIVE, ticking elapsed time
  // (`runtime.loadStartedAtMs`), which a static headline lookup cannot carry.
  if (runtime.mode === 'starting') {
    // Tempdoc 601 §19 — live MEASURED elapsed (a count-up, never a countdown), mirroring the
    // 'thinking' branch above: the `>2s` gate keeps trivially-fast loads on the bare label, and
    // `humanizeSeconds` gives minute-aware wording for cold loads that exceed 60s.
    const elapsed = elapsedSecondsSince(runtime.loadStartedAtMs);
    return elapsed > 2 ? `Starting… ${humanizeSeconds(elapsed)}` : 'Starting…';
  }
  const headline = aiEngineHeadline(aiEngine);
  return aiEngine.kind === 'online' && runtime.modelLabel ? `Online — ${runtime.modelLabel}` : headline;
}

/**
 * Tempdoc 814 §D5 (one authority, one pointer) — the status readout WITHOUT the verdict flavour.
 *
 * The status-bar chip and the chat surface's degradation banner are two projections of the same
 * `verdict`. When the banner is the persistent authority for that fact on the active surface, the
 * chip yields its degradation flavour and reports the AI mode instead. "The AI mode" is not a second
 * derivation: it is exactly what {@link computeStatusLabel}/{@link computeStatusTone} already produce
 * when {@link verdictOwnsStatus} is false, so this runs the SAME two functions with a neutral verdict
 * rather than forking their fallback chain (which carries the activity overlay and the `starting`
 * elapsed clock). One authority, two call sites.
 */
const NEUTRAL_VERDICT: SystemHealthVerdict = { kind: 'operational', severity: 'ok', reasons: [] };

export function statusWithoutVerdictFlavor(
  s: Pick<AiState, 'runtime' | 'activity' | 'aiEngine'>,
): { label: string; tone: NoticeTone } {
  return {
    label: computeStatusLabel(NEUTRAL_VERDICT, s.runtime, s.activity, s.aiEngine),
    tone: computeStatusTone(NEUTRAL_VERDICT, s.runtime, s.aiEngine),
  };
}

function computeIndex(): AiIndex {
  const status = statusSig.get();
  const inference = inferenceSig.get();
  const fromStatus = <T>(v: T | undefined | null): Maybe<T> =>
    status === null || v === undefined || v === null ? UNKNOWN : known(v);
  const fromInference = <T>(v: T | undefined | null): Maybe<T> =>
    inference === null || v === undefined || v === null ? UNKNOWN : known(v);
  return {
    documentCount: fromStatus(status?.worker?.core?.indexedDocuments),
    searchableDocumentCount: fromStatus(status?.worker?.core?.searchableDocuments),
    pendingJobs: fromStatus(status?.worker?.core?.pendingJobs),
    embeddingPending: fromStatus(status?.embedding?.pendingCount),
    embeddingBlocked:
      status === null ? UNKNOWN : known((status.embedding?.compatState ?? '').startsWith('BLOCKED')),
    embeddingQueueSize: fromInference(inference?.embeddingQueueSize),
    vduQueueSize: fromInference(inference?.vduQueueSize),
  };
}

/**
 * tempdoc 644 — the ONE realized retrieval-engine projection (reranker / embed / splade), read off
 * `status.worker.gpu`. Exported as the authority the realized-capability register binds to; surfaces
 * consume `aiState.realized.*` rather than re-reading `worker.gpu.*OrtCuda` ad-hoc (the fork-class).
 * `accelerator` distinguishes a known GPU/CPU realization from a not-yet-probed device (lazy ORT
 * init — `available` false but `attempted` false → `null`, not a false "CPU" claim). Per-query stage
 * execution is a different record (the search trace) and is intentionally not projected here.
 */
export function computeRealized(): AiRealized {
  const gpu = statusSig.get()?.worker?.gpu;
  const project = (
    loaded: boolean,
    cuda:
      | { available?: boolean | null; attempted?: boolean | null; failureReason?: string | null }
      | null
      | undefined,
  ): EngineRealized => {
    const accelerator: EngineAccelerator = !loaded
      ? null
      : cuda?.available
        ? 'gpu'
        : cuda?.attempted
          ? 'cpu'
          : null;
    return { loaded, accelerator, failureReason: cuda?.failureReason || null };
  };
  return {
    reranker: project(!!gpu?.rerankerModelPath, gpu?.rerankerOrtCuda),
    embed: project(!!gpu?.embedBackend, gpu?.embedOrtCuda),
    splade: project(!!gpu?.spladeModelPath, gpu?.spladeOrtCuda),
  };
}

function computeStatusTier(
  verdict: SystemHealthVerdict,
  runtime: AiRuntime,
): StatusTier {
  // 595 §10.5 — the status-bar tone is a PROJECTION of the verdict's severity,
  // so it agrees with Health by construction. A cosmetic degradation (severity
  // 'info', e.g. LambdaMART off) stays calm; an impairing one ('warn') turns the
  // dot amber on BOTH surfaces.
  switch (verdict.kind) {
    case 'unreachable':
      return 'disconnected';
    case 'transitioning':
    case 'connecting':
      return 'degraded'; // busy/connecting — a non-green, non-error "in flux" tone
    case 'degraded':
      return verdict.severity === 'info' ? 'online' : 'degraded';
    case 'checking':
    case 'operational':
    default:
      if (runtime.mode === 'online') return 'online';
      if (runtime.mode === 'indexing' || runtime.mode === 'starting') return 'degraded';
      return 'offline';
  }
}

/**
 * Tempdoc 649 — the ONE tone for the status pill + liveness dot, the matched sibling of
 * `computeStatusLabel`. Both the pill and the dot consume this single verdict-derived tone, so `statusTier`
 * is no longer a second tone authority: the calm "Catching up…" (`transitioning`, severity `busy`) projects
 * `verdictTone('busy')='info'` (calm tint) instead of the old `statusTier='degraded'` amber.
 *
 * Scope: this change is intentionally CONNECTION-ONLY. The verdict-driven kinds
 * (`connecting`/`transitioning`/`unreachable`/`degraded`) take their tone from the one `verdictTone`; every
 * NON-connection state keeps its PRE-649 tone:
 *   - AI **activity** ("Thinking…") is NOT special-cased here — its tone falls through to the verdict/runtime
 *     logic, so a "Thinking…" overlay still shows the UNDERLYING health (green healthy / amber degraded), as
 *     before. The label is the only thing the activity overlays (`computeStatusLabel`), not the tone.
 *   - settled `indexing`/`starting` keep their prior **amber** (`warning`) "in-flux" tone (595); only the
 *     connection states were the 649 over-alarm. `online → success`, `offline`/unknown → `neutral`.
 */
function computeStatusTone(
  verdict: SystemHealthVerdict,
  runtime: AiRuntime,
  aiEngine: AiEngineVerdict,
): NoticeTone {
  // Verdict-driven kinds (mirror computeStatusLabel's verdictHeadline branch): tone from the ONE
  // verdict-tone authority — calm `busy` → info, `warn` → warning, `unreachable` (error) → error.
  if (verdictOwnsStatus(verdict)) {
    return verdictTone(verdict.severity);
  }
  // Design pass 3 — the AI-specific tone now comes from the ONE AI-engine presentation projection
  // (`aiEngineTone`), which preserves this function's own pre-existing convention (indexing/starting
  // stay amber, distinct from settled online) — see `aiEngineTone`'s own doc comment.
  if (runtime.mode === 'starting') return 'warning';
  return aiEngineTone(aiEngine.kind);
}

function computePhase(): ConnectionPhase {
  const { lastSuccessMs, neverConnected, stale } = computeStaleness();
  if (lastSuccessMs === null) return neverConnected ? 'disconnected' : 'connecting';
  return stale ? 'stale' : 'connected';
}

function computeReadiness(): Maybe<ReadinessView> {
  const status = statusSig.get();
  if (status === null) return UNKNOWN;
  const composites = status.readiness?.composites ?? {};
  const toState = (k: string): 'ready' | 'degraded' | 'unknown' => {
    const s = composites[k]?.state;
    if (s === 'READY') return 'ready';
    if (s === 'DEGRADED') return 'degraded';
    return 'unknown';
  };
  const reasonCodes = [
    ...(composites['retrieval']?.reasonCodes ?? []),
    ...(composites['aiFeatures']?.reasonCodes ?? []),
  ];
  return known({
    retrieval: toState('retrieval'),
    aiFeatures: toState('aiFeatures'),
    reasonCodes,
  });
}

function buildSnapshot(): AiState {
  const phase = computePhase();
  const readiness = computeReadiness();
  const capabilities = computeCapabilities();
  const connection = computeConnection();
  const runtime = computeRuntime();
  const index = computeIndex();
  const realized = computeRealized();
  const activity = activitySig.get();
  const status = statusSig.get();
  // 595 §4.1/§4.2 — derive the ONE stability axis + verdict, then project the
  // status-bar label/tier from the verdict (no parallel phase/readiness reads).
  const migration = status?.worker?.migration;
  const stability = computeStability({
    phase,
    indexComponentState: status?.readiness?.engineComponents?.['index']?.state,
    indexComponentReasonCode: status?.readiness?.engineComponents?.['index']?.reasonCode,
    indexState: status?.worker?.core?.indexState,
    migrationState: migration?.migrationState,
    // Tempdoc 837 §2.3 — WHY the rebuild is running, carried as an additive facet of the transition
    // (the reason-code authority for it was retired: it could only be emitted inside the window the
    // verdict forces to `transitioning`, where the readiness notice returns null).
    migrationSource: migration?.migrationSource,
    activeGenerationId: migration?.activeGenerationId,
    buildingGenerationId: migration?.buildingGenerationId,
    servingSearchGenerationId: migration?.servingSearchGenerationId,
    servingIngestGenerationId: migration?.servingIngestGenerationId,
    catchingUp: status?.catchingUp,
    // Tempdoc 649 — poll-stale BUT reachable via another channel ⟹ calm "Catching up…", not "Reconnecting…".
    reachableViaContact: connection.reachable,
  });
  const verdict = computeVerdict({
    phase,
    stability,
    readiness,
    // Tempdoc 649 — distinguishes "no poll yet but origin alive" (Connecting…) from a true unreachable.
    reachableViaContact: connection.reachable,
    // 595 §15.2 (E4) — project the backend's own stuck-rebuild signals so a wedged
    // generation cutover escalates from calm "Rebuilding…" to a warning.
    migrationPaused: migration?.migrationPaused,
    migrationSwitchingAgeMs: migration?.migrationSwitchingAgeMs,
    migrationSwitchingMaxDurationMs: migration?.migrationSwitchingMaxDurationMs,
  });
  const installStatus = installStatusSig.get();
  const runtimeStatus = runtimeStatusSig.get();
  // Tempdoc 807 — the ONE liveness answer, computed here from the SAME contact reachability the
  // verdict above consumes, and threaded BOTH into the engine verdict (so it cannot claim a live
  // engine off a dead snapshot) and onto the state (so snapshot-rendering surfaces re-tense the same
  // way, at the same moment).
  const snapshotLive = isSnapshotLive(connection);
  // Tempdoc 663 Design pass 2 - the AI-engine rollup, computed the same way stability/verdict are
  // (purely observed signals; no surface-local UI intent). Computed BEFORE statusLabel/statusTone
  // (Design pass 3) since those now project their AI-specific wording/tone from this value.
  const aiEngine = computeAiEngineVerdict({
    installStatus,
    runtimeStatus,
    runtime,
    reachable: connection.reachable,
    // Tempdoc 807 Part A (round-13 R13-F2) — W1's predicate, threaded in rather than re-derived inside
    // the verdict function: a retained `engineState: 'Healthy'` must not mint a settled green "Online".
    snapshotLive,
    // Tempdoc 737 §12b/§12c — the runtime-authority engine axis (preferred over runtime.mode when present).
    engineState: status?.inference?.engineState,
    chatEnabledSpec: status?.inference?.chatEnabledSpec,
    procedure: status?.inference?.procedure,
    engineReason: status?.inference?.engineReason,
  });
  const statusLabel = computeStatusLabel(verdict, runtime, activity, aiEngine);
  const statusTier = computeStatusTier(verdict, runtime);
  const statusTone = computeStatusTone(verdict, runtime, aiEngine);
  return {
    phase,
    readiness,
    capabilities,
    connection,
    runtime,
    activity,
    index,
    realized,
    statusLabel,
    statusTier,
    statusTone,
    stability,
    verdict,
    // Tempdoc 807 A.3 — projected once, here, from the verdict just computed above.
    snapshotLive,
    status,
    inference: inferenceSig.get(),
    lastSettledIndex: lastSettledIndexSig.get(),
    episodeMaxPendingJobs: episodeMaxPendingJobsSig.get(),
    enrichSettleSamples: enrichSettleSamplesSig.get(),
    installStatus,
    runtimeStatus,
    packStatus: packStatusSig.get(),
    aiEngine,
  };
}

/** The single derived AiState. Recomputes automatically; memoized. */
const aiState = computed<AiState>(buildSnapshot);

// ── subscribe shim: a Signal.subtle.Watcher drives fan-out ──
//
// Replaces the hand-rolled `emit()` (build snapshot + loop listeners called
// at every mutation site). The watcher fires when `aiState` changes; we
// fan out on a microtask (signal-idiomatic batching). The sync value at
// subscribe time is still delivered synchronously (preserves the contract).

let watcherScheduled = false;
let watching = false;
const watcher = new Signal.subtle.Watcher(() => {
  if (watcherScheduled) return;
  watcherScheduled = true;
  queueMicrotask(() => {
    watcherScheduled = false;
    // Canonical re-arm: drain + recompute the pending computeds, then
    // re-watch so the watcher notifies again on the next change.
    for (const s of watcher.getPending()) s.get();
    watcher.watch();
    const snapshot = aiState.get();
    for (const l of listeners) {
      try {
        l(snapshot);
      } catch {
        /* swallow listener errors */
      }
    }
  });
});

function ensureWatching(): void {
  if (watching) return;
  watching = true;
  watcher.watch(aiState);
  aiState.get(); // establish initial dependency tracking
}

// ── Poller integration ──
//
// The existing pollers only notify on success (errors are silently swallowed).
// To detect disconnection, we run a staleness check: if no successful poll has
// arrived in STALE_THRESHOLD_MS, we consider the connection unreachable.

/**
 * Apply a fresh inference snapshot to the input signals. Tempdoc 601 §19 — captures the
 * model-load start (`loadStartedAtSig`) on the `starting` edge so the live "Starting… Ns" count-up
 * has a stamp; shared by the poll callback AND `__feedForTest` so tests exercise the same capture.
 */
function ingestInferenceSnapshot(snap: InferenceSnapshot | null): void {
  const wasStarting = inferenceSig.get()?.starting === true;
  const nowStarting = snap?.starting === true;
  if (nowStarting && !wasStarting) {
    loadStartedAtSig.set(Date.now());
  } else if (!nowStarting && wasStarting) {
    loadStartedAtSig.set(null);
  }
  inferenceSig.set(snap);
  if (snap) lastInferenceSuccessSig.set(Date.now());
}

function onInferenceUpdate(snap: InferenceSnapshot | null): void {
  if (snap) {
    ingestInferenceSnapshot(snap);
  } else {
    // Failed poll: no data change, but a re-evaluation lets the staleness
    // window be reflected (mirrors the old emit-on-null behavior).
    clockTickSig.set(clockTickSig.get() + 1);
  }
}

function onStatusUpdate(snap: StatusSnapshot | null): void {
  if (snap) {
    statusSig.set(snap);
    lastStatusSuccessSig.set(Date.now());
    stampSettledIndex(snap);
    stampEpisodeMaxPendingJobs(snap);
    stampEnrichSettleSamples(snap);
  } else {
    clockTickSig.set(clockTickSig.get() + 1);
  }
}

/**
 * 663 Stage 3 — apply a fresh install/runtime/pack snapshot. `aiInstallPoll` already retains
 * last-known-good per field on a transient failure, so every call here has at-least-as-much data
 * as before; this finally wires the (previously dead-in-production) `installStateSig`.
 */
function onAiInstallUpdate(snap: AiInstallSnapshot): void {
  installStatusSig.set(snap.install);
  runtimeStatusSig.set(snap.runtime);
  packStatusSig.set(snap.packs);
  if (snap.install) {
    installStateSig.set(
      known({
        installed: snap.install.installedFully === true,
        installing: snap.install.state === 'running',
      }),
    );
  }
}

/**
 * 595 §15.3 (E2) — on a SETTLED successful poll, retain the index counts so a later
 * provisional window can show them dimmed. Settled-ness reuses the ONE pure oracle
 * `computeStability` (phase='connected' — we just got a successful poll); a non-settled
 * poll leaves the retained value untouched (do not overwrite good data with the
 * rebuild's transient 0). Imperative-on-input → no signal-graph loop (§17.1).
 */
function stampSettledIndex(snap: StatusSnapshot): void {
  const migration = snap.worker?.migration;
  const stability = computeStability({
    phase: 'connected',
    indexComponentState: snap.readiness?.engineComponents?.['index']?.state,
    indexComponentReasonCode: snap.readiness?.engineComponents?.['index']?.reasonCode,
    indexState: snap.worker?.core?.indexState,
    migrationState: migration?.migrationState,
    activeGenerationId: migration?.activeGenerationId,
    buildingGenerationId: migration?.buildingGenerationId,
    servingSearchGenerationId: migration?.servingSearchGenerationId,
    servingIngestGenerationId: migration?.servingIngestGenerationId,
    catchingUp: snap.catchingUp,
  });
  if (stability.kind !== 'settled') return;
  const documentCount = snap.worker?.core?.indexedDocuments;
  if (documentCount == null) return;
  lastSettledIndexSig.set({
    documentCount,
    // 811 C-4: `null` = the backend never reported it (fall back to documentCount); a reported 0 is
    // a real "the default scope searches nothing" and is retained as 0.
    searchableDocumentCount: snap.worker?.core?.searchableDocuments ?? null,
    // Honesty: a size that was never observed stays `null` (renderers show "…" for Size, not
    // a confident "0 B"). Files retention is gated on documentCount above, the primary path.
    indexSizeBytes: snap.worker?.core?.indexSizeBytes ?? null,
  });
}

/**
 * 813 §19 (W2) — track the drain episode's high-water backlog (see
 * {@link AiState.episodeMaxPendingJobs}). Mirrors {@link stampSettledIndex}: imperative-on-input,
 * guarded against the hard-zeroed fallback snapshot, never written from a `computed`. A reported
 * backlog of 0 ENDS the episode, so the next spike starts from a fresh denominator rather than
 * measuring itself against a ceiling from an hour ago.
 */
function stampEpisodeMaxPendingJobs(snap: StatusSnapshot): void {
  if (!isWorkerReportedIndex(snap)) return;
  const pending = snap.worker?.core?.pendingJobs;
  if (typeof pending !== 'number' || !Number.isFinite(pending) || pending <= 0) {
    episodeMaxPendingJobsSig.set(0);
    return;
  }
  if (pending > episodeMaxPendingJobsSig.get()) episodeMaxPendingJobsSig.set(pending);
}

/**
 * 813 §20 — track the enrichment episode's settle trail (see {@link AiState.enrichSettleSamples}).
 * Mirrors {@link stampEpisodeMaxPendingJobs}: imperative-on-input, never written from a `computed`,
 * and phase-gated through the ONE progress authority rather than a private phase re-derivation.
 *
 * The settled sum comes from `enrichSettledSum`, the same stage set `selectIndexingProgress` blends
 * into the percent — so the estimate this trail backs cannot describe a different quantity than the
 * bar it is rendered beside. The empty-array reset is guarded on length because a fresh `[]` is
 * never `Object.is`-equal to the old one, and an unconditional write would invalidate the memoized
 * snapshot on every idle poll.
 */
function stampEnrichSettleSamples(snap: StatusSnapshot): void {
  if (selectIndexingPhase(snap) !== 'enriching') {
    if (enrichSettleSamplesSig.get().length > 0) enrichSettleSamplesSig.set(NO_ENRICH_SAMPLES);
    return;
  }
  const next = [...enrichSettleSamplesSig.get(), { t: Date.now(), settled: enrichSettledSum(snap) }];
  enrichSettleSamplesSig.set(next.slice(-ENRICH_SETTLE_SAMPLE_CAP));
}

function checkStaleness(): void {
  // Tempdoc 649 — two time-derived axes can change between input-signal updates: the poll-freshness
  // PHASE (data current vs aged out) and contact-based REACHABILITY (the stream stamp is a plain
  // global). Bump the clock tick when EITHER would flip vs the currently-displayed snapshot, so the
  // memoized `computed` re-evaluates — covering both the calm "Catching up…" transition (poll goes
  // stale while still reachable) and the unreachable transition (all contact ages out).
  const current = aiState.get();
  const phaseNow = computePhase();
  const { reachable: reachableNow } = computeReachability();
  if (phaseNow !== current.phase || reachableNow !== current.connection.reachable) {
    clockTickSig.set(clockTickSig.get() + 1);
  }
}

// ── Public API (unchanged surface) ──

export function startAiStateStore(apiBase: string): void {
  if (started) return;
  started = true;
  _startedAtMs = Date.now();
  setInferenceApiBase(apiBase);
  setStatusApiBase(apiBase);
  setAiInstallApiBase(apiBase);
  unsubInference = subscribeInference(onInferenceUpdate);
  unsubStatus = subscribeStatus(onStatusUpdate);
  unsubAiInstall = subscribeAiInstall(onAiInstallUpdate);
  stalenessTimer = window.setInterval(checkStaleness, 5000);
}

export function stopAiStateStore(): void {
  unsubInference?.();
  unsubStatus?.();
  unsubAiInstall?.();
  unsubInference = null;
  unsubStatus = null;
  unsubAiInstall = null;
  if (stalenessTimer !== null) {
    window.clearInterval(stalenessTimer);
    stalenessTimer = null;
  }
  started = false;
}

export function subscribeAiState(listener: (s: AiState) => void): () => void {
  listeners.add(listener);
  ensureWatching();
  try {
    listener(aiState.get());
  } catch {
    /* swallow listener errors */
  }
  return () => {
    listeners.delete(listener);
  };
}

export function getAiState(): AiState {
  return aiState.get();
}

/**
 * Tempdoc 727 F-8 — force the shared `/api/status` snapshot to refresh immediately, so a caller
 * that just performed a backend-reflected action (e.g. unlocking chat encryption) doesn't leave
 * dependent projections (the DATA PROTECTION row's `conversationProtection.state`) waiting out the
 * poll interval while a sibling panel driven by that action's own direct response has already
 * updated. Resolves once the fresh snapshot has been applied to `statusSig` (subscribers are
 * notified synchronously within the same call).
 */
export function refreshStatusNow(): Promise<void> {
  return refreshStatusPollNow();
}

export function setAiActivity(patch: Partial<AiActivity>): void {
  activitySig.set({ ...activitySig.get(), ...patch });
}

export function setInstallState(installed: boolean, installing: boolean): void {
  // Brain surface calls this to feed install state into the unified model.
  installStateSig.set(known({ installed, installing }));
}

/** Test-only: inject a poll snapshot + stamp last-success at the current clock. */
export function __feedForTest(opts: {
  status?: StatusSnapshot | null;
  inference?: InferenceSnapshot | null;
}): void {
  if (opts.status !== undefined) {
    statusSig.set(opts.status);
    if (opts.status) {
      lastStatusSuccessSig.set(Date.now());
      stampSettledIndex(opts.status); // E2 — mirror the production poll-callback stamp.
      stampEpisodeMaxPendingJobs(opts.status); // 813 §19 W2 — same mirror, same reason.
      stampEnrichSettleSamples(opts.status); // 813 §20 — same mirror, same reason.
    }
  }
  if (opts.inference !== undefined) {
    // Tempdoc 601 §19 — route through the shared ingest so the `starting`-edge capture
    // (`loadStartedAtSig`) runs in tests, not just the production poll path.
    ingestInferenceSnapshot(opts.inference);
  }
}

/**
 * Test-only (tempdoc 649): record positive origin contact at `ms` (default now), mirroring an SSE
 * frame/heartbeat, so tests can exercise reachable-but-poll-stale without a live stream. Bump the clock
 * tick so the reachability derivation re-evaluates (the stream stamp is a plain global).
 */
export function __feedContactForTest(ms: number = Date.now()): void {
  bumpOriginContact(ms);
  clockTickSig.set(clockTickSig.get() + 1);
}

/** Test-only: force the staleness derivation to re-evaluate (mirrors the interval). */
export function __tickClockForTest(): void {
  clockTickSig.set(clockTickSig.get() + 1);
}

export function __resetAiStateForTest(): void {
  stopAiStateStore();
  listeners.clear();
  inferenceSig.set(null);
  statusSig.set(null);
  lastInferenceSuccessSig.set(null);
  lastStatusSuccessSig.set(null);
  activitySig.set(INITIAL_ACTIVITY);
  installStateSig.set(UNKNOWN);
  installStatusSig.set(null);
  runtimeStatusSig.set(null);
  packStatusSig.set(null);
  lastSettledIndexSig.set(null);
  episodeMaxPendingJobsSig.set(0);
  enrichSettleSamplesSig.set(NO_ENRICH_SAMPLES);
  clockTickSig.set(0);
  loadStartedAtSig.set(null);
  __resetOriginContactForTest();
  __resetAiInstallPollForTest();
  _startedAtMs = 0;
}
