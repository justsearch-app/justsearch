// SPDX-License-Identifier: Apache-2.0
/**
 * availability.ts — tempdoc 596: the AVAILABILITY half of the operability authority.
 *
 * 559 Part II Authority V made `jf-control` guarantee a control is *operable + named*.
 * It modelled the third operability question — *"is this control available right now,
 * and if not, why"* — as a bare `disabled` boolean, with the reason hand-derived inline
 * onto a `title` that the browser SUPPRESSES on a disabled control (the disabled element
 * isn't even focusable, so the reason is unreachable). This module adds the missing half:
 * availability is a TYPED value, projected ONCE from the observed-state authority
 * (`aiStateStore`), that `jf-control` renders with a reachable reason + a non-silent block.
 *
 * Four kinds (596 §10/C2 — the sub-kind split keeps the existing hard-disabled consumers untouched):
 *   - `available`              — operable.
 *   - `blocked`                — a HARD intent gate (unconfirmed input, mid-operation). Stays
 *                                native `disabled`; the click is genuinely inert. This is what
 *                                the ~10 existing confirmation/precondition consumers use.
 *   - `unavailable{reason}`    — a SOFT block (AI offline, no docs, still loading, OR a local intent
 *                                gap like "nothing to undo"). Renders `aria-disabled` (focusable,
 *                                reason reachable) and surfaces the reason on an activation attempt.
 *   - `degraded{caveat}`       — 596 §16.4: OPERABLE but a quality caveat (e.g. an optional re-ranker
 *                                is off). Fires `onActivate`; the caveat rides the same tooltip.
 *
 * TWO TIERS — one authority, two entry points (596 §16.3 "generalize beyond AI"; AHA: the AI-store
 * projector and arbitrary local gates do NOT share a reason to change, so they are NOT one signature):
 *   1. CAPABILITY-AGNOSTIC (any state-gated control): the `Availability` TYPE + `unavailableBecause`
 *      (a literal local reason) + `availabilitySeverity`. This is the app's general affordance-availability
 *      authority — already the home of 6+ non-AI gates (selection-empty, draft-empty, refresh-in-flight,
 *      unread-empty: EffectAuditLog, AdvisoryInboxDrawer, the Refresh buttons, Steer), not just AI.
 *   2. AI-STORE PROJECTION: `projectAvailability` derives the AI-capability affordances
 *      (`documents`/`extract`/`agent`) from the one observed-state authority (`aiStateStore`).
 *
 * 595 seam (596 §14/U1): the reason inputs (`capabilities.chat`, `phase`, `index.documentCount`)
 * are raw `aiStateStore` fields TODAY. 595 LAYERS a `SystemHealthVerdict`+severity on top without
 * restructuring them, so `projectAvailability`'s signature is stable: when 595 lands it consumes
 * the verdict instead of the raw fields, with no call-site change. The capability gate here keys
 * on `chat`/`docs` — NOT the `retrieval==='degraded'` signal — so an optional-reranker-only
 * "degraded" (595 §10's over-alarming case) never marks an affordance unavailable.
 */
import type { AiState } from './aiStateStore.js';
import { isKnown } from './known.js';
import {
  classifyConsequence,
  reasonFor,
  KEYWORD_FALLBACK_CAVEAT,
  SEARCH_UNAVAILABLE_CAVEAT,
  MODEL_UNAVAILABLE_CAVEAT,
  PASSAGE_REDUCED_CAVEAT,
  OPTIONAL_CAPABILITY_CAVEAT,
  AI_UNAVAILABLE_CAVEAT,
  OPEN_HEALTH,
  type NoticeRemedy,
} from './readinessNotice.js';
import { formatStartupEstimate } from './startupEstimate.js';

export type { NoticeRemedy };

/** A capability-gated affordance whose availability depends on observed-state. */
export type CapabilityAffordance = 'documents' | 'extract' | 'agent';

export type Availability =
  | { readonly kind: 'available' }
  | { readonly kind: 'blocked' }
  | {
      readonly kind: 'unavailable';
      readonly reason: string;
      readonly transient?: boolean;
      /** Tempdoc 596 §17 — the actionable fix for this gap, from the one reason vocabulary. */
      readonly remedy?: NoticeRemedy;
    }
  /**
   * Tempdoc 596 §16.4 — available-WITH-a-caveat: the affordance is fully OPERABLE (it fires), but a
   * quality/optional capability is reduced (e.g. an optional re-ranker is off → lower ranking quality).
   * Distinct from `unavailable` (which blocks) — `degraded` never blocks. The caveat surfaces on the SAME
   * reachable tooltip the reason uses. Projects from the existing 595 readiness/severity signal (596 does
   * not OWN "degraded"; it consumes it), so it can never alarm louder than the window's own verdict.
   */
  | {
      readonly kind: 'degraded';
      readonly caveat: string;
      readonly remedy?: NoticeRemedy;
    };

export const AVAILABLE: Availability = { kind: 'available' };

/**
 * Project an affordance's availability from the observed-state authority.
 *
 * Reads raw `aiStateStore` fields (the 595-stable seam). Returns a SOFT `unavailable`
 * (reachable reason) for capability gaps — never a hard `blocked` (that kind is reserved
 * for intent gates, expressed at the call site, not derived from capabilities).
 */
export function projectAvailability(
  affordance: CapabilityAffordance,
  s: AiState | null,
): Availability {
  // Still loading — the store hasn't reported yet (obs #420: the null/connecting window
  // before the first /api/status lands is real and seconds-long under degradation).
  // TRANSIENT: it self-clears, so it reads "still starting", not the settled "offline".
  if (s === null || s.phase === 'connecting') {
    return unavailableFor('inference.starting', true);
  }

  // Tempdoc 807 A.3 — every capability gate below reads the RETAINED snapshot (`capabilities.chat`
  // comes from the last inference poll, which keeps saying `online` after the backend dies). A
  // control whose precondition is a live backend must therefore consult liveness FIRST, or it stays
  // clickable and fails on activation. `binding.unreachable` is the same reason code the verdict
  // mints and the "Backend disconnected." banner words, so the control and the banner cannot drift.
  //
  // NOT transient (round-13 review). `transient` is not a synonym for "will clear eventually" — in
  // `Control.activate` it QUEUES the intent and auto-fires it on the next render where the control is
  // operable. That promise fits a BOUNDED wait (the boot window, an in-flight refresh, a model load);
  // a not-live snapshot is by construction ≥40s with zero contact on any channel — the backend is
  // gone, for an unbounded time — so queueing would convert a click made during an outage into a
  // command firing minutes later, unwatched, and several clicks into a burst. The boot window is
  // unaffected: the `phase === 'connecting'` arm above answers first and stays transient.
  if (!s.snapshotLive) {
    return unavailableFor('binding.unreachable', false);
  }

  // Tempdoc 601 — the local AI model is actively LOADING (runtime.mode==='starting'): a TRANSIENT,
  // forward-looking gap distinct from the settled "offline" below. Keyed on the load state, not the
  // FE connect phase (which is the transport window above). Attach the time-estimate from the last
  // successful startup ("…still starting — usually ready in ~Ns"); when there is no prior duration
  // the estimate is null and the reason stays the bare "still starting" — the unknown arm, never a
  // fabricated number (601 §9). Estimate-only, never a decrementing countdown.
  if (s.runtime?.mode === 'starting') {
    return unavailableFor('inference.starting', true, formatStartupEstimate(s.inference?.lastStartupDurationMs));
  }

  // Settled: the local AI model is offline. The single capability gate every affordance shares.
  if (!s.capabilities.chat) {
    return unavailableFor('inference.offline', false);
  }

  // Documents (RAG) additionally needs at least one indexed document. `capabilities.rag`
  // is `chat && docs>0`; here chat is already true, so the only remaining gap is zero docs.
  if (affordance === 'documents') {
    const docs = s.index?.documentCount;
    if (docs !== undefined && isKnown(docs) && docs.value === 0) {
      // Tempdoc 596 §16.4 — condition-based `availableWhen`. If indexing is in flight the documents
      // WILL appear, so this is a TRANSIENT, forward-looking gap ("available once indexing finishes"),
      // not the settled "No documents indexed yet" dead-end. The resolving condition is known to the FE
      // (runtime.mode / index.pendingJobs); there is no numeric ETA in the snapshot, so we say the
      // CONDITION, never a fabricated "~Ns".
      if (indexingInFlight(s)) {
        return {
          kind: 'unavailable',
          reason: 'Indexing in progress — available once indexing finishes',
          transient: true,
        };
      }
      return unavailableFor('no_documents', false);
    }

    // Tempdoc 596 §16.4 — available-with-a-caveat. Documents/RAG search is operable (chat up, docs > 0)
    // but retrieval ranking is degraded (e.g. an optional re-ranker is off). We DEGRADE, never block —
    // CONSUMING the ONE 595 verdict (`computeVerdict` emits `kind:'degraded'` exactly for retrieval
    // degradation), NOT re-deriving it from `readiness.retrieval` (that would fork the verdict authority,
    // the §4.2 single-derivation rule the `verdict-derivation` gate enforces).
    //
    // Tempdoc 805 §G.2 — the CAVEAT is licensed by the one consequence classifier, not by the
    // verdict's SEVERITY: round 11 measured a warn verdict whose only retrieval cause was a passage
    // gap, and this projection (like the banner) claimed a keyword fallback over a trace showing dense
    // retrieval + reranking executing. Severity says how loud, never what happened. The wordings are
    // IMPORTED (not re-authored here) so the claim exists in exactly one module.
    //
    // Round-14 finding 8 — the caveat NAMES its feature and ROUTES to the detail. Pre-fix, the
    // `cosmetic` arm said "an optional ranking model" (a reader with full API access resolved that to
    // the wrong model) and carried no remedy, so the calm tier was the only one with no way to reach
    // the per-encoder detail the warn tier reaches. `ai-unavailable` gets its OWN wording rather than
    // borrowing the re-ranking one — same discipline, opposite direction.
    const verdict = s.verdict;
    if (verdict !== undefined && verdict.kind === 'degraded') {
      const consequence = classifyConsequence(verdict.reasons);
      return {
        kind: 'degraded',
        caveat:
          consequence === 'search-unavailable'
            ? SEARCH_UNAVAILABLE_CAVEAT
            : consequence === 'model-unavailable'
            ? MODEL_UNAVAILABLE_CAVEAT
            : consequence === 'passage-reduced'
            ? PASSAGE_REDUCED_CAVEAT
            : consequence === 'ai-unavailable'
              ? AI_UNAVAILABLE_CAVEAT
              : consequence === 'cosmetic'
                ? OPTIONAL_CAPABILITY_CAVEAT
                : // `retrieval-impaired` and the conservative `unknown` (an unclassifiable code is not
                  // evidence that retrieval is fine — the classifier's own doctrine).
                  KEYWORD_FALLBACK_CAVEAT,
        remedy: OPEN_HEALTH,
      };
    }
  }

  return AVAILABLE;
}

/**
 * Build a SOFT `unavailable` from a reason-code via the ONE shared reason vocabulary
 * (tempdoc 596 §17). The control-scoped projection: the wording + remedy come from the same
 * `readinessNotice.CAUSE_ROWS` the window banner + the 595 verdict project from, so a control's reason
 * and the window's reason — and their remedies — cannot drift. (The optional-reranker degraded case never reaches
 * here: this gates on `chat`/`docs`, not `retrieval==='degraded'`.)
 */
function unavailableFor(code: string, transient: boolean, estimate?: string | null): Availability {
  const { wording, remedy } = reasonFor(code);
  // Tempdoc 601 — an optional time-estimate suffix (the model-load "usually ready in ~Ns"), applied
  // at the control-scope projection rather than forking the CAUSE_ROWS wording. `estimate` is null on
  // the unknown arm, leaving the bare reason (no fabricated number).
  const reason = estimate ? `${wording} — usually ready in ${estimate}` : wording;
  return remedy
    ? { kind: 'unavailable', reason, transient, remedy }
    : { kind: 'unavailable', reason, transient };
}

/**
 * Is indexing actively in flight (so a zero-doc state is a passing phase, not a settled empty corpus)?
 * Tempdoc 596 §16.4 — the resolving condition behind the forward-looking documents reason. Reads the
 * observed-state index/runtime signals; `unknown` Maybe fields are treated as "not in flight" (we never
 * promise "indexing" without positive evidence, mirroring the no-fabrication rule for zero-docs).
 */
function indexingInFlight(s: AiState): boolean {
  if (s.runtime?.mode === 'indexing') return true;
  const pending = s.index?.pendingJobs;
  if (pending !== undefined && isKnown(pending) && pending.value > 0) return true;
  const embedding = s.index?.embeddingPending;
  if (embedding !== undefined && isKnown(embedding) && embedding.value > 0) return true;
  return false;
}

/**
 * Build a SOFT `unavailable` from a LITERAL local reason (tempdoc 596 §16.2 — the a11y-debt close).
 *
 * For a LOCAL intent gap (nothing to undo, nothing to refresh, already up to date) the reason is
 * site-specific, NOT a capability/degradation `CAUSE_ROWS` code — so it is passed verbatim rather than
 * looked up in the shared vocabulary (which is reserved for the cross-surface readiness reasons the
 * banner + verdict also speak). The point is the same: the reason becomes REACHABLE (aria-disabled +
 * a focus/hover tooltip) instead of dying on a suppressed `title` of a hard-`disabled` control. There
 * is no remedy — the fix is the user doing the prerequisite (selecting a row, waiting for a refresh).
 *
 * `transient` marks a self-clearing gap (an in-flight refresh) so the activation toast is calm `info`,
 * not `warning` — the same distinction `projectAvailability` draws for "still starting" vs "offline".
 */
export function unavailableBecause(reason: string, transient = false): Availability {
  return transient ? { kind: 'unavailable', reason, transient: true } : { kind: 'unavailable', reason };
}

/** Toast severity for a soft-unavailable activation attempt: transient → info, settled → warning. */
export function availabilitySeverity(a: Extract<Availability, { kind: 'unavailable' }>): 'info' | 'warning' {
  return a.transient ? 'info' : 'warning';
}
