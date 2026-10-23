// SPDX-License-Identifier: Apache-2.0
/**
 * sv3-degradation — the Search v3 window's ONE reduced-capability projection (inventory E1/E2/E3).
 *
 * The window had no degradation surface at all: a reader whose semantic leg had fallen back, or
 * whose local model was not running, saw a composer that quietly refused and nothing that named the
 * reason or offered a fix. This module supplies that fact, and it AUTHORS NONE OF IT — every word,
 * every cause and every remedy comes from the shared readiness authority (`state/readinessNotice.ts`,
 * bound to the backend's `LifecycleReasonCode` vocabulary by the `check-readiness-reason-codes`
 * gate). This window is a second CONSUMER of that vocabulary, never a second author, exactly as
 * `fixtures.ts` is for the unreachable wording.
 *
 * Three things live here and nowhere else in the window:
 *
 *  1. WHETHER there is anything to say — `warrantsSearchDegradationBanner`, the authority's own
 *     tier predicate, so an `info`-severity cosmetic gap does not buy warning-tier chrome in a
 *     window whose whole complaint was chrome volume.
 *  2. WHAT the causes are, KEYED ON THE REASON CODE rather than on its wording. The authority's
 *     `causes` are already scoped to the branch it took (a rebuild headline lists only the causes a
 *     rebuild clears); this projection keeps that scoping and re-attaches the CODE to each, so every
 *     downstream decision — dedup, the rebuild-headline restatement, the composer's overlap test —
 *     is made against an identifier the backend emits instead of against a sentence someone may
 *     re-word.
 *  3. WHERE the one-click fix is — as a NAVIGATION reference, per the convention `jf-control` and
 *     `jf-capability-map` already share (`components/Control.ts:391-395`): point at the surface the
 *     remedy lives on, never fire an operation out from under its consent ceremony.
 *
 * Pure data → data. The composer renders it and decides nothing, which is what keeps the wording
 * identical to the shipped window's.
 */
import { selectIndexingProgress, type IndexingProgress } from '../../state/indexingProgress.js';
import type { AiState } from '../../state/aiStateStore.js';
import {
  OPEN_HEALTH,
  isReindexCause,
  readinessNotice,
  reasonFor,
  warrantsSearchDegradationBanner,
  type NoticeRemedy,
  type ReadinessNoticeView,
  type Severity,
} from '../../state/readinessNotice.js';

/**
 * A worded cause that still knows WHICH CODE it is (inventory E2).
 *
 * The authority hands back sentences; the backend emits codes. Carrying both is what makes
 * "dedup by code, not by wording" a structural property of this module rather than a comment on
 * it: a wording-keyed implementation could not produce this type at all.
 */
export interface Sv3DegradationCause {
  /** The backend reason code (`LifecycleReasonCode` + the worker-health probe codes). */
  readonly code: string;
  /** That code's wording, from the ONE vocabulary — never re-phrased here. */
  readonly wording: string;
}

/** A remedy this window can take the reader to in one click. */
export interface Sv3DegradationRemedy {
  /** The surface that OWNS the fix — the same `Sv3RemedyDetail.target` the corpus remedy uses. */
  readonly target: string;
  /** The authority's own label for it. */
  readonly label: string;
}

/**
 * What the window's ONE banner slot says when capability is reduced.
 *
 * `headline` RESTS (one line, always visible); `body` and `causes` are the elaboration the reader
 * discloses, or that Detailed mode discloses for them. That split is the window's law — in-flow
 * chrome is summary height — and it is the whole difference from the shipped window's banner, which
 * measured ~100px of a 790px window.
 */
export interface Sv3Degradation {
  /** `"Semantic search degraded."` — the honesty fact, which never hides. */
  readonly headline: string;
  /** The standing consequence sentence. Elaboration: disclosed, not resting. */
  readonly body: string;
  /** Deduped BY CODE, scoped exactly as the authority scoped its own cause list. */
  readonly causes: readonly Sv3DegradationCause[];
  /** The single highest-priority fix, as a navigation this window can perform. */
  readonly remedy: Sv3DegradationRemedy;
  /** The verdict's severity — the banner's tone, never a second derivation of it. */
  readonly severity: Severity;
}

/** A `navigate` remedy read as a reference; `null` for any other kind. */
function navigateReference(remedy: NoticeRemedy): Sv3DegradationRemedy | null {
  return remedy.kind === 'navigate' ? { target: remedy.target, label: remedy.label } : null;
}

/**
 * The authority's always-actionable fallback (`OPEN_HEALTH`), read as this window's reference.
 *
 * Derived rather than typed out, so no surface id or label is hard-coded here. `OPEN_HEALTH` is a
 * `navigate` remedy by construction; the empty arm is unreachable and
 * {@link sv3RemedyReference}'s test asserts the real target comes through.
 */
const OPEN_HEALTH_REFERENCE: Sv3DegradationRemedy = navigateReference(OPEN_HEALTH) ?? {
  target: '',
  label: '',
};

/**
 * Where a remedy takes the reader, in one click.
 *
 * An `operation` remedy points at Health rather than firing: the operation's label, risk and consent
 * ceremony belong to the operation catalog, and a compact banner is not the place to bypass them.
 * This is verbatim the convention `Control.dispatchRemedy` and `CapabilityMap.dispatchRemedy`
 * already share, so a control's remedy and this window's remedy cannot land in different places.
 */
export function sv3RemedyReference(remedy: NoticeRemedy): Sv3DegradationRemedy {
  return navigateReference(remedy) ?? OPEN_HEALTH_REFERENCE;
}

/**
 * The notice's causes, re-keyed onto their reason CODES and deduped by code (inventory E2).
 *
 * Two mechanics, both code-keyed:
 *
 *  - DEDUP. A verdict's `reasons` are the concatenation of two composites' `reasonCodes`
 *    (`state/aiStateStore.ts:771-774`), so one condition reported on both the `retrieval` and the
 *    `aiFeatures` axis arrives twice. One code, one entry.
 *  - SCOPING. The authority already decided which of the verdict's codes its headline speaks for
 *    (the rebuild headline lists only the causes a rebuild clears). That decision is CONSUMED, not
 *    re-derived: a code survives here only if the authority worded it into `notice.causes`.
 *
 * The wording is read back from `reasonFor` — the same function `wordCauses` uses — so the match is
 * identity on the one vocabulary's output, not a string heuristic over free text.
 */
export function sv3DegradationCauses(
  codes: readonly string[],
  notice: ReadinessNoticeView,
  activity?: IndexingProgress['activity'],
): Sv3DegradationCause[] {
  const scoped = new Set(notice.causes);
  const out: Sv3DegradationCause[] = [];
  const seen = new Set<string>();
  for (const code of codes) {
    if (seen.has(code)) continue;
    const wording = reasonFor(code, activity).wording;
    if (!scoped.has(wording)) continue;
    seen.add(code);
    out.push({ code, wording });
  }
  // The rebuild headline already NAMES the rebuild story, so a lone cause that only restates it is
  // chrome with no information in it. Ported from the shipped window's round-2 ruling
  // (`views/UnifiedChatView.ts:360-368`) and keyed the same way — on `isReindexCause`, never on the
  // sentence. Only a LONE cause is dropped: with two, the list is telling the reader which ones.
  if (out.length === 1 && isReindexCause(out[0]!.code)) return [];
  return out;
}

/**
 * The window's reduced-capability fact, or `null` when there is nothing warranting the banner.
 *
 * `null` covers three genuinely different cases on purpose — healthy, still connecting, and a
 * cosmetic `info`-severity gap Health carries calmly — because all three mean the same thing to
 * this slot: do not spend a line on it.
 */
export function projectSv3Degradation(snapshot: AiState | null): Sv3Degradation | null {
  const verdict = snapshot?.verdict;
  if (verdict === undefined) return null;
  if (!warrantsSearchDegradationBanner(verdict)) return null;
  const activity = snapshot ? selectIndexingProgress(snapshot.status, snapshot.indexingStatusLive,
    snapshot.episodeMaxPendingJobs, snapshot.enrichSettleSamples).activity : undefined;
  const notice = readinessNotice(verdict, activity);
  // Unreachable: the predicate above already returns false for a null notice. Kept as the type's
  // own narrowing rather than a non-null assertion, which would outlive the predicate's contract.
  if (notice === null) return null;
  return {
    headline: notice.headline,
    body: notice.body,
    causes: sv3DegradationCauses(verdict.reasons, notice, activity),
    remedy: sv3RemedyReference(notice.remedy),
    severity: verdict.severity,
  };
}

/**
 * The composer's availability reason, AFTER the banner has taken its share of the slot.
 *
 * The window has one banner slot and no status fact may stand in it twice. The affordance-scoped
 * reason (`projectAvailability('documents', …)`) and the system-scoped banner are both worded by
 * `reasonFor`, so when they are about the same code they are the same sentence — and the banner is
 * the one that also carries a remedy. So the reason yields to it.
 *
 * A reason the banner does NOT word (no indexed documents; indexing in flight; a model-load
 * estimate suffix the cause list has no counterpart for) is not a duplicate and keeps its line: the
 * failure this window can afford is more text, never a refusal with no reason on screen.
 */
export function sv3ComposerReason(
  degradation: Sv3Degradation | null,
  unavailableReason: string,
): string {
  if (degradation === null || unavailableReason === '') return unavailableReason;
  return degradation.causes.some((c) => c.wording === unavailableReason) ? '' : unavailableReason;
}
