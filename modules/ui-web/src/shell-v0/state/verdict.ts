// SPDX-License-Identifier: Apache-2.0
/**
 * verdict.ts — the ONE system-health verdict authority (tempdoc 595).
 *
 * 557 §2.B made the FE a CONSUMER of the backend's tri-state readiness, but the
 * derived "is the system healthy / in flux?" VERDICT was still computed inline in
 * ≥4 places over two un-merged axes (the readiness axis in HealthSurface +
 * readinessNotice; the connection/runtime axis in computeStatusTier/Label) — so a
 * cosmetic `retrieval=DEGRADED` rendered as green "Online" in the status bar AND
 * orange "Service degraded" in Health at the same time (595 §1.1/§10.1).
 *
 * This module is the SINGLE place the verdict is derived. It adds the missing
 * epistemic dimension 557 never modelled — **Stability** (settled vs in-flux) —
 * generalizing the one provisional case 557 got right (`ConnectionPhase.stale`),
 * and rolls `(Stability, readiness, reindex, phase)` into ONE typed
 * `SystemHealthVerdict { kind, severity, reasons }` that every surface consumes.
 *
 * The `check-verdict-derivation` gate keeps the readiness→verdict predicate
 * (`retrieval === …`) confined to this seam, so a surface cannot re-fork it.
 *
 * Pure data → data; no DOM, no IO. Inputs are passed in so it is unit-testable.
 */

import type { ConnectionPhase, ReadinessView } from './aiStateStore.js';
import type { Maybe } from './known.js';
import {
  classifyConsequence,
  isReindexCause,
  severityForCodes,
  type Severity,
} from './readinessNotice.js';

export type { Severity };

/**
 * Why the observed-state is provisional (real-now-but-in-flux). Closed union so
 * every consumer handles it totally. Generalizes `ConnectionPhase`'s two
 * provisional cases (`connecting`→`initial-load`, `stale`→`channel-stale`) and
 * adds the transition causes 557 never modelled, each derived from data already
 * on `/api/status` (595 §4.1 / §9.1).
 */
export type ProvisionalCause =
  | 'initial-load'
  | 'channel-stale'
  | 'rebuilding'
  | 'generation-switch'
  | 'worker-restart'
  // 630: a brief window after OS resume while the worker re-reconciles files changed during sleep.
  | 'catching-up'
  // 649: the poll-specific data is stale (aged past the freshness window) BUT the backend is provably
  // reachable via another channel (an SSE heartbeat) — typically the cheap poll starved behind the
  // browser connection-pool limit under load. Calm: data is merely behind, the backend is alive.
  | 'updating';
// NOTE (595 §4.4 refinement): a stalled live JOB feed is NOT a global Stability
// cause. `Stability` also gates the doc-count / folder renderers (§4.3), which must
// NOT go provisional for a jobs-feed stall (the `/api/status` doc count is still
// fresh). Feed-staleness is therefore PANEL-scoped: the Tasks panel observes it
// directly via `indexingJobsBridge.subscribeFeedStalled`, not through this axis.

/**
 * Tempdoc 837 §2.3 — WHY a generation rebuild / cutover is running. The closed vocabulary the worker
 * maps every persisted manifest source onto (`io.justsearch.app.api.status.MigrationSource`), `unknown`
 * included: a generation built by an older build, by a test driver, or from a hand-edited manifest
 * hands back a label no vocabulary can close over, and that is a real state, not an error.
 */
export type MigrationSource =
  | 'corrupt_index_rebuild'
  | 'embedding_model_change'
  | 'schema_mismatch'
  | 'user_requested_rebuild'
  | 'user_requested_bulk_reindex'
  | 'manual'
  | 'unknown';

const MIGRATION_SOURCES: ReadonlySet<string> = new Set<MigrationSource>([
  'corrupt_index_rebuild',
  'embedding_model_change',
  'schema_mismatch',
  'user_requested_rebuild',
  'user_requested_bulk_reindex',
  'manual',
  'unknown',
]);

/** The `source:<member>` token prefix carried on `verdict.reasons` (837 §2.3(ii)). */
const SOURCE_TOKEN_PREFIX = 'source:';

export type Stability =
  | { readonly kind: 'settled' }
  | {
      readonly kind: 'provisional';
      readonly cause: ProvisionalCause;
      /**
       * 837 §2.3(ii) — an ADDITIVE facet of a rebuild/switch transition, never a new `cause` token.
       * Two live sites narrow on the cause by EQUALITY — the stuck-rebuild escalation below and
       * `HealthSurface.renderRebuildProgress` — so a `rebuilding-corrupt`-style flat token would
       * compile clean and silently disable both (a wedged rebuild would stop escalating to `warn`,
       * and the progress bar would blank). Both failures are invisible to `tsc`, which is exactly
       * why the source travels beside the cause instead of inside it.
       */
      readonly source?: MigrationSource;
    };

export type VerdictKind =
  | 'operational'
  | 'checking'
  | 'connecting'
  | 'transitioning'
  | 'degraded'
  | 'unreachable';

export interface SystemHealthVerdict {
  readonly kind: VerdictKind;
  /** Drives presentation tone — derived ONCE here, not re-guessed per surface. */
  readonly severity: Severity;
  /** Reason codes / cause tokens behind a non-green verdict (for wording). */
  readonly reasons: readonly string[];
}

export interface StabilityInput {
  readonly phase: ConnectionPhase;
  /** `worker.core.indexState` — `'UNAVAILABLE'` is the worker-down fallback. */
  readonly indexState: string | null | undefined;
  /** `worker.migration.*` — the rebuild / generation-switch signal (595 §9.1). */
  readonly migrationState: string | null | undefined;
  /**
   * 837 §2.3 — `worker.migration.migrationSource`: WHY the building generation exists. The worker
   * has already closed this over its vocabulary on read, so anything unrecognized arriving here is
   * treated as absent rather than trusted.
   */
  readonly migrationSource?: string | null;
  readonly activeGenerationId: string | null | undefined;
  readonly buildingGenerationId: string | null | undefined;
  readonly servingSearchGenerationId: string | null | undefined;
  readonly servingIngestGenerationId: string | null | undefined;
  /** 630: `catchingUp` — true for a brief window after an OS resume (post-sleep reconcile). */
  readonly catchingUp?: boolean | null;
  /**
   * 649: is the backend reachable via ANY channel (poll success OR an SSE frame/heartbeat)? When the
   * poll-freshness phase is `stale` but this is true, the data is merely behind (the cheap poll
   * starved under load) while the backend is alive — a calm `updating` state, NOT `channel-stale`.
   */
  readonly reachableViaContact?: boolean;
}

/**
 * 837 §2.3 — narrow the wire string to the closed facet, or to `undefined` when there is none. The
 * empty string means "nothing is being rebuilt", which is a different fact from `unknown` ("a
 * rebuild is running and we cannot say why"), so it must not become a facet.
 */
function migrationSourceOf(raw: string | null | undefined): MigrationSource | undefined {
  if (raw == null || raw === '') return undefined;
  return MIGRATION_SOURCES.has(raw) ? (raw as MigrationSource) : 'unknown';
}

/**
 * The ONE stability derivation. Precedence: a worker-down/restart fallback and an
 * in-progress generation build dominate the connection axis (they are the
 * higher-severity flux).
 */
export function computeStability(i: StabilityInput): Stability {
  // Tempdoc 807 §E.4 — LOST CONTACT DOMINATES EVERY RETAINED-SNAPSHOT CAUSE.
  //
  // Every branch below reads a field off the LAST SUCCESSFUL snapshot, which `statusSig` retains
  // through failed polls. Once the backend is gone, whichever of them the final snapshot happened to
  // hold won forever, and the status line projected it present-tense ("Restarting…" off a retained
  // `UNAVAILABLE`) while every liveness signal correctly read stale — 807 W1 fixed the surfaces
  // AROUND the line but left the line itself pinned by a value nothing verifies. The tense, not the
  // value, is the error: those causes were true when they were measured; they are unobservable now.
  //
  // Scoped to `stale` — the poll-freshness phase that means "a poll DID succeed once and its data has
  // aged out" — because that is the only phase where a retained snapshot exists to be projected from.
  // `connecting` is the first-poll window (nothing retained; the boot grace must stay `initial-load`),
  // and `disconnected` is already dominated by `computeVerdict`'s `unreachable` arm, which outranks
  // stability entirely. `reachableViaContact === false` is the SAME contact fact `isSnapshotLive`
  // consumes (`computeReachability`, 40 s window) — no second authority, no new number.
  if (i.phase === 'stale' && i.reachableViaContact === false) {
    return { kind: 'provisional', cause: 'channel-stale' };
  }
  // Worker process down/restarting: a SUCCESSFUL poll returned the fallback view
  // (ConnectionPhase stays `connected`, so this is NOT caught by phase — 595 §9.1).
  if ((i.indexState ?? '').toUpperCase() === 'UNAVAILABLE') {
    return { kind: 'provisional', cause: 'worker-restart' };
  }
  // 837 §2.3(ii) — the source rides ALONGSIDE the unchanged cause on every migration-derived arm.
  const source = migrationSourceOf(i.migrationSource);
  const ms = (i.migrationState ?? '').toUpperCase();
  if (ms === 'SWITCHING') return { kind: 'provisional', cause: 'generation-switch', source };
  if (ms === 'MIGRATING') return { kind: 'provisional', cause: 'rebuilding', source };
  const building = i.buildingGenerationId ?? '';
  if (building !== '' && building !== (i.activeGenerationId ?? '')) {
    return { kind: 'provisional', cause: 'rebuilding', source };
  }
  const ss = i.servingSearchGenerationId ?? '';
  const si = i.servingIngestGenerationId ?? '';
  if (ss !== '' && si !== '' && ss !== si) {
    return { kind: 'provisional', cause: 'generation-switch', source };
  }
  // 630: post-resume reconcile — lower precedence than a real rebuild/worker-down (above), but
  // more informative than the generic connection-axis cases (below), so name it before them.
  if (i.catchingUp === true) return { kind: 'provisional', cause: 'catching-up' };
  // Connection axis (557's existing provisional cases, generalized).
  if (i.phase === 'connecting') return { kind: 'provisional', cause: 'initial-load' };
  if (i.phase === 'stale') {
    // 649: the poll-data aged out. If the backend is still reachable via another channel (an SSE
    // heartbeat), the data is merely behind (the poll starved under load) — a calm `updating` state.
    //
    // Review 2026-08 (FE review-fix bundle, item 2): this site used to read the field TRUTHILY while
    // the lost-contact guard at the top of this function reads `=== false`, so `undefined` — an older
    // snapshot shape, or the field not populated yet — meant UNKNOWN at one site and "contact lost" at
    // the other. Unified: asserting contact LOSS requires an explicit `false`, which the guard above
    // already returned on, so anything reaching here is contact-live or contact-UNKNOWN and gets the
    // calm `updating` state rather than the `channel-stale` alarm. (The symmetric half of the same
    // rule lives in `computeVerdict`: asserting contact ALIVE requires an explicit `true` there.
    // UNKNOWN never licenses a positive claim in either direction; the phase's own default stands.)
    return { kind: 'provisional', cause: 'updating' };
  }
  return { kind: 'settled' };
}

export interface VerdictInput {
  readonly phase: ConnectionPhase;
  readonly stability: Stability;
  readonly readiness: Maybe<ReadinessView>;
  /**
   * 595 §15.2 (E4) — the backend's OWN signals that a generation rebuild/cutover is
   * stuck (`worker.migration`). Used only to ESCALATE a `rebuilding`/`generation-switch`
   * transition from calm "busy" to "warn" — closing the 593 §D/§F wedge (a wedged
   * rebuild otherwise looks calm forever). Projection only: the ~5 s status poll
   * refreshes the age, so no FE timer is needed.
   */
  readonly migrationPaused?: boolean;
  readonly migrationSwitchingAgeMs?: number;
  readonly migrationSwitchingMaxDurationMs?: number;
  /**
   * 649: is the backend reachable via ANY channel? Distinguishes "no poll has landed yet but the
   * origin is alive (streams beating)" — which should read "Connecting…", not a false "Backend
   * disconnected" — from a genuine unreachable origin (no contact of any kind within the window).
   */
  readonly reachableViaContact?: boolean;
}

/**
 * The ONE verdict. Total over its inputs; precedence: unreachable > transitioning
 * > checking(unknown) > degraded > operational. `transitioning` is ordered above
 * `degraded`/`operational` because a mid-build `indexHealthy` may read true while
 * the old generation serves (595 §9.1). Severity for a degradation comes from the
 * one reason→severity table (`severityForCodes`) so a cosmetic gap (LambdaMART)
 * cannot render with the same alarm as a broken index (595 §10.3).
 *
 * SCOPE (595 §15.3 / slice 456): this is *retrieval / search* health. The AI
 * (inference) component is intentionally EXCLUDED — AI is optional, offline-by-design
 * on fresh installs is not a system fault — and is surfaced by its own AI-Engine card.
 */
export function computeVerdict(i: VerdictInput): SystemHealthVerdict {
  if (i.phase === 'disconnected') {
    // 649: "disconnected" is the POLL-freshness verdict (no poll ever landed + grace elapsed). If the
    // backend is nonetheless reachable via another channel (streams heartbeating), it is alive but the
    // first poll hasn't arrived — that is "Connecting…", not a false "Backend disconnected". Only when
    // there is NO positive contact of any kind do we raise the unreachable alarm.
    if (i.reachableViaContact === true) {
      return { kind: 'connecting', severity: 'info', reasons: [] };
    }
    // Tempdoc 637 #1: carry an FE-derived reason code so the unreachable state words itself
    // through the CAUSE_ROWS vocabulary (a loud banner), instead of a silent empty result one
    // layer up. `binding.unreachable` is declared FE-derived in readiness-reason-codes.v1.json.
    return { kind: 'unreachable', severity: 'error', reasons: ['binding.unreachable'] };
  }
  if (i.stability.kind === 'provisional') {
    if (i.stability.cause === 'initial-load') {
      return { kind: 'connecting', severity: 'info', reasons: [] };
    }
    const cause = i.stability.cause;
    // 837 §2.3(ii) — carry the rebuild's source as an EXTRA token, exactly the way `paused` /
    // `overdue` below already are. `reasons[0]` stays the cause, so nothing downstream reorders.
    const sourceToken = i.stability.source ? [`${SOURCE_TOKEN_PREFIX}${i.stability.source}`] : [];
    // D1-14: an in-place build can keep keyword search serving while its dense leg is
    // rebuilding. Preserve that measured retrieval fact through the transition verdict;
    // migration alone is not evidence of a pause (a BESIDE build still serves dense).
    // A stale poll cannot license a present-tense capability claim.
    const semanticRebuilding =
      (cause === 'rebuilding' || cause === 'generation-switch') &&
      i.phase === 'connected' &&
      i.readiness.known &&
      i.readiness.value.retrieval === 'degraded' &&
      i.readiness.value.reasonCodes.includes('index.embedding_rebuilding')
        ? ['index.embedding_rebuilding']
        : [];
    // E4: escalate a STUCK generation rebuild/cutover, using the backend's own
    // paused flag / age-vs-max-duration (the FE only projects them).
    if (cause === 'rebuilding' || cause === 'generation-switch') {
      const age = i.migrationSwitchingAgeMs ?? 0;
      const maxDur = i.migrationSwitchingMaxDurationMs ?? 0;
      const overdue = age > 0 && maxDur > 0 && age > maxDur;
      if (i.migrationPaused === true) {
        return {
          kind: 'transitioning',
          severity: 'warn',
          reasons: [cause, 'paused', ...sourceToken, ...semanticRebuilding],
        };
      }
      if (overdue) {
        return {
          kind: 'transitioning',
          severity: 'warn',
          reasons: [cause, 'overdue', ...sourceToken, ...semanticRebuilding],
        };
      }
    }
    // Tempdoc 649 — `channel-stale` is genuine lost contact (no poll AND no stream frame within the
    // window), worded "Reconnecting…". Unlike `updating` (reachable, data merely behind → calm "Catching
    // up…"), this is a connectivity problem the user should see as a WARNING, not calm. Severity 'warn'
    // gives the proper ramp: updating(busy→info) < channel-stale(warn→amber) < unreachable(error→red),
    // so the calm and the alarming in-flux states are visually distinct on every surface.
    if (cause === 'channel-stale') {
      return { kind: 'transitioning', severity: 'warn', reasons: [cause] };
    }
    return {
      kind: 'transitioning',
      severity: semanticRebuilding.length > 0 ? 'warn' : 'busy',
      reasons: [cause, ...sourceToken, ...semanticRebuilding],
    };
  }
  // Settled: roll up the readiness axis.
  if (!i.readiness.known) return { kind: 'checking', severity: 'info', reasons: [] };
  const r = i.readiness.value;
  // Tempdoc 600 Design A: the reindex/compat cause is no longer a boolean shortcut — it arrives as
  // a real reason code on the `retrieval` composite (index.blocked_legacy / .schema_mismatch / …),
  // so it flows through the ONE degraded path below and is named by the CAUSE_ROWS vocabulary.
  // Tempdoc 627: a supervised Worker restart in flight surfaces `worker.recovering` on the retrieval
  // composite (alongside downstream consequences like index.not_healthy). Treat its presence as a calm
  // "Restarting…" transitioning state, not an alarming "Service degraded" — a routine self-heal should
  // not read as a failure. (`includes`, not sole-reason: the restart is the root, the rest is downstream.)
  if (r.retrieval === 'degraded' && r.reasonCodes.includes('worker.recovering')) {
    return { kind: 'transitioning', severity: 'busy', reasons: ['worker-restart'] };
  }
  if (r.retrieval === 'degraded') {
    return { kind: 'degraded', severity: severityForCodes(r.reasonCodes), reasons: r.reasonCodes };
  }
  if (r.retrieval === 'unknown') return { kind: 'checking', severity: 'info', reasons: [] };
  return { kind: 'operational', severity: 'ok', reasons: [] };
}

/**
 * 837 §2.3(ii) — read the source facet back off `verdict.reasons`. BOTH wording surfaces consume it
 * ({@link verdictHeadline} and {@link verdictBody}); the design named only the body, and §D.3 caught
 * that `verdictHeadline` has the identical `includes()`-then-`switch(reasons[0])` shape. A facet read
 * by one and not the other means the headline and the body disagree about the same rebuild.
 */
function sourceOfReasons(reasons: readonly string[]): MigrationSource | undefined {
  const token = reasons.find((r) => r.startsWith(SOURCE_TOKEN_PREFIX));
  if (token === undefined) return undefined;
  const value = token.slice(SOURCE_TOKEN_PREFIX.length);
  return MIGRATION_SOURCES.has(value) ? (value as MigrationSource) : 'unknown';
}

/** The ONE human headline for the verdict (Health badge + footer read this). */
export function verdictHeadline(v: SystemHealthVerdict): string {
  switch (v.kind) {
    case 'operational':
      return 'All systems operational';
    case 'checking':
      return 'Checking…';
    case 'connecting':
      return 'Connecting…';
    case 'unreachable':
      return 'Backend disconnected';
    case 'transitioning':
      // E4: a stuck rebuild/cutover escalates its wording (the reasons carry the flag).
      if (v.reasons.includes('paused')) return 'Rebuild paused';
      if (v.reasons.includes('overdue')) return 'Rebuilding… (taking longer than expected)';
      switch (v.reasons[0]) {
        case 'channel-stale':
          return 'Reconnecting…';
        case 'worker-restart':
          return 'Restarting…';
        case 'generation-switch':
          return 'Switching index…';
        case 'catching-up':
          return 'Catching up…';
        case 'updating':
          // 649: reachable but the poll is behind (e.g. starved under load). Calm — the backend is
          // alive, the view is merely catching up. Shares 562's reachable+stale vocabulary.
          return 'Catching up…';
        case 'rebuilding':
        default:
          // 837 §2.3 — name the cause of the rebuild when the backend told us one. The three
          // system-initiated sources answer the question a user actually has ("why is it rebuilding
          // — did something break?"); a rebuild the user asked for, a bare manual start, and an
          // unrecognized legacy source all keep the plain headline, because naming them would tell
          // the user something they already know or something we do not know.
          switch (sourceOfReasons(v.reasons)) {
            case 'corrupt_index_rebuild':
              return 'Repairing index…';
            case 'embedding_model_change':
              return 'Rebuilding for a new AI model…';
            case 'schema_mismatch':
              return 'Rebuilding for a new index format…';
            default:
              return 'Rebuilding…';
          }
      }
    case 'degraded':
      if (v.reasons.some(isReindexCause)) return 'Reindex required';
      // §10.3: a cosmetic degradation (info) must not read as a hard failure.
      return v.severity === 'info' ? 'Reduced capability' : 'Service degraded';
  }
}

/**
 * The ONE tone projection of severity (the Health badge reads this). Values are a
 * subset of `NoticeTone` ('neutral'|'info'|'success'|'warning'|'error'); a cosmetic
 * degradation / transition maps to the calm `info` tone, never the `warning` alarm.
 */
export function verdictTone(
  severity: Severity,
): 'success' | 'info' | 'warning' | 'error' {
  switch (severity) {
    case 'ok':
      return 'success';
    case 'info':
    case 'busy':
      return 'info';
    case 'warn':
      return 'warning';
    case 'error':
      return 'error';
  }
}

/** The ONE consequence sentence for the verdict (the Health footer reads this). */
export function verdictBody(v: SystemHealthVerdict): string {
  switch (v.kind) {
    case 'connecting':
      return 'System health is not available yet.';
    case 'checking':
      return 'Confirming retrieval status…';
    case 'unreachable':
      return 'The backend is not reachable. Showing last-known state.';
    case 'transitioning':
      if (v.reasons.includes('paused')) {
        return 'The index rebuild is paused — open Health to resume or investigate.';
      }
      if (v.reasons.includes('overdue')) {
        return 'The index rebuild is taking longer than expected — open Health to check.';
      }
      if (v.reasons.includes('index.embedding_rebuilding')) {
        return 'Semantic search is paused while the index is rebuilt; keyword search remains available.';
      }
      switch (v.reasons[0]) {
        case 'channel-stale':
          return 'Reconnecting to the backend; holding last-known values.';
        case 'worker-restart':
          return 'The knowledge server is restarting; counts will settle shortly.';
        case 'generation-switch':
          return 'Switching to the freshly-built index; this completes shortly.';
        case 'catching-up':
          return 'Re-checking your files for changes made while your computer was asleep.';
        case 'updating':
          // 649: honest, generic wording — the backend is busy/reachable and the view is behind, with
          // NO claim about sleep (distinct from `catching-up`) or a lost connection (not `channel-stale`).
          return 'The backend is busy; showing last-known values while the view catches up.';
        case 'rebuilding':
        default:
          // 837 §2.3 — the same facet the headline above reads, so the two cannot disagree about the
          // same rebuild. The corruption sentence is carried over WORD FOR WORD from the retired
          // `index.rebuilding` CAUSE_ROWS row: that wording was correct, it was simply attached to a
          // code no surface could reach.
          switch (sourceOfReasons(v.reasons)) {
            case 'corrupt_index_rebuild':
              return 'The index was corrupted and is being rebuilt from your files — results are temporarily incomplete.';
            case 'embedding_model_change':
              return 'The AI embedding model changed, so the index is being rebuilt; search continues while it finishes.';
            case 'schema_mismatch':
              return 'The index format changed, so the index is being rebuilt; results will settle when it finishes.';
            case 'user_requested_rebuild':
              return 'The rebuild you requested is running; document counts and results will settle when it finishes.';
            case 'user_requested_bulk_reindex':
              return 'The bulk reindex you requested is running; document counts and results will settle when it finishes.';
            default:
              return 'The index is being rebuilt; document counts and results will settle when it finishes.';
          }
      }
    case 'degraded':
      if (v.reasons.some(isReindexCause)) {
        return 'A reindex is required to restore full search quality.';
      }
      // Tempdoc 837 §1.5 (UR-4) — a `warn` AI-only cause used to fall straight through to
      // "Retrieval is degraded", which is FALSE for a chat-only outage and contradicted the banner
      // (which correctly said "Search is fully working … Chat and answer features are unavailable")
      // off the SAME verdict. Two surfaces, one verdict, opposite claims — the exact defect the
      // consequence-classification authority exists to prevent, so this branch consults that ONE
      // classifier rather than re-deriving a claim from severity. Mirrors the isReindexCause arm
      // directly above; `verdict.ts` is a registered consumer in
      // governance/consequence-classification.v1.json.
      if (classifyConsequence(v.reasons) === 'ai-unavailable') {
        return 'Chat and answer features are unavailable; search itself is unaffected.';
      }
      return v.severity === 'info'
        ? 'An optional capability is unavailable; search still works.'
        : 'Retrieval is degraded. See recent events for detail.';
    case 'operational':
      return 'No recoverable conditions active.';
  }
}

export interface VerdictPresentation {
  readonly tone: ReturnType<typeof verdictTone>;
  readonly headline: string;
  readonly body: string;
  /**
   * The screen-reader announcement of a verdict CHANGE (595 §15.1 / E1). `text` is
   * the concise headline (WCAG 4.1.3 — brief); `politeness` maps to the ONE
   * live-region authority (`<jf-system-notice live>`): `alert` (assertive) only for
   * an error verdict, else `status` (polite).
   */
  readonly announce: { readonly text: string; readonly politeness: 'status' | 'alert' };
}

/**
 * The ONE verdict-presentation projection (595 §15.1). Every surface CONSUMES this —
 * the Health badge (tone + headline), the footer (headline + body), the status bar
 * (headline for the provisional kinds, via computeStatusLabel), and the a11y
 * announcer (announce). It mirrors 557's `present()` display projector, extended
 * from the verdict's VALUE to its PRESENTATION, so the wording lives in one place.
 */
export function presentVerdict(v: SystemHealthVerdict): VerdictPresentation {
  const headline = verdictHeadline(v);
  return {
    tone: verdictTone(v.severity),
    headline,
    body: verdictBody(v),
    announce: { text: headline, politeness: v.severity === 'error' ? 'alert' : 'status' },
  };
}
