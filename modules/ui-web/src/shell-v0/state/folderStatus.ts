// SPDX-License-Identifier: Apache-2.0
/**
 * folderStatus.ts — the ONE per-folder indexing-status derivation (tempdoc 599 §9.1).
 *
 * The folder-granularity sibling of `computeVerdict` (595 §4.2): a single pure function that
 * decides a watched folder's truthful state, so every row consumer projects from it instead of
 * re-interpreting raw fields at the render site (the 594/597 single-authority discipline).
 *
 * THE TRUTHFULNESS FIX: `ready` derives from job DRAIN (`inFlight === 0 && failed === 0`), never
 * from the walk-completion timestamp. The wire `status` field is walk-derived and means "scanned",
 * NOT "searchable" — so a folder showing "✓ indexed" while its jobs are still processing (the
 * §8.1 false-positive) is unrepresentable here: a non-empty `inFlightCount` forces `indexing`.
 *
 * Pure + dependency-light: takes the already-formatted relative time and the system `provisional`
 * flag (the caller's projection of the `Stability` axis, mirroring `renderObserved`), so the seam
 * needs no host-utility or store import and is trivially unit-testable.
 *
 * TWO TIERS (tempdoc 813 §4 + 809 finding 1, superseding the 599 §10 / 598 note that used to sit
 * here): job drain makes a folder KEYWORD-searchable, which is not the same fact as "fully
 * searchable" — the semantic layers are a separate backfill that carries no per-root job rows, and
 * the DOC-level counters read fully healthy during a PASSAGE-level backfill (809 finding 9's trap;
 * `enrichmentCoverage.ts` documents it). Since 813 the wire row also carries per-root enrichment
 * COVERAGE, so the drained tier splits: `enriching` while coverage is still climbing (the shared
 * `ENRICHMENT_CATCHING_UP_CAVEAT` wording, plus this root's own percent when derivable) and `ready`
 * once it is complete ("fully searchable"). When per-root coverage is not derivable the index-wide
 * positive-evidence boolean (`enrichmentProgress(status).pending`) is the fallback gate — caveat
 * without a percent, never a fabricated number. Neither tier derives from the walk timestamp.
 */

import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import type { EnrichmentApplicability } from './indexingProgress.js';
import { ENRICHMENT_BLOCKED_CAVEAT, ENRICHMENT_CATCHING_UP_CAVEAT } from './enrichmentCoverage.js';

export type FolderState =
  | 'scanning' // walk in progress — files not yet fully enqueued
  | 'indexing' // in-flight jobs > 0
  | 'waiting' // the shared index observation confirms retry-only waiting
  | 'enriching' // 809 finding 1 / 813 §4 — drained + keyword-searchable, enrichment still catching up
  | 'keyword-only' // round-15 F1b — drained + keyword-searchable, semantic enrichment CANNOT run
  | 'ready' // scanned, drained, no failures — fully searchable (or coverage unknowable)
  | 'unverified' // tempdoc 626 §Axis-C — indexed, but the reconcile couldn't verify deletions (cap-skipped)
  | 'failed' // walk error, or terminal failed jobs with nothing in flight
  | 'empty' // scanned, zero indexable files
  | 'unavailable' // tempdoc 599 §16/A1 — the folder's path is gone (deleted/unmounted), not an error
  | 'unknown'; // system in a global transition (rebuild) — don't assert a terminal fact

/** The glyph vocabulary (`renderStatusIcon` / `FolderCardRenderer.statusIcon`). */
export type FolderGlyph = 'indexed' | 'error' | 'pending' | 'unavailable' | 'unverified';

export interface FolderStatus {
  readonly state: FolderState;
  readonly glyph: FolderGlyph;
  readonly metaText: string;
  readonly inFlight: number;
  readonly failed: number;
  /**
   * Tempdoc 914 D3 — `failed` is a LAST-KNOWN count carried across a window in which this root has
   * no settled answer, not a count this sample observed. Absent / `false` ⟹ `failed` is this
   * sample's own number. A consumer that renders the count MUST mark it, visibly and in the
   * accessible name (`failedChipCopy`): the chip may not assert as a present fact something the
   * current wire row does not report.
   */
  readonly failedIsLastKnown?: boolean;
}

export interface FolderStatusContext {
  /** Policy pause confirmed by the shared progress selector; folder facts stay independent. */
  readonly indexingPaused?: boolean;
  /** All pending jobs wait for retry, as reported by the shared index-wide projection. */
  readonly retryWaiting?: boolean;
  /** Pre-formatted relative time of the last walk (host util), or '' if never. */
  readonly relativeTime: string;
  /**
   * Tempdoc 626 §Recency — pre-formatted relative time of the last reconcile that CONFIRMED index↔disk
   * correspondence (`lastVerifiedIsoTime`, host util), or '' if never verified. Distinct from
   * `relativeTime` (last write): this is the heartbeat that lets a calm "✓" prove it is fresh.
   */
  readonly verifiedRelativeTime: string;
  /** The system `Stability` axis projected to a boolean (`stability.kind === 'provisional'`). */
  readonly provisional: boolean;
  /**
   * Tempdoc 813 §4 — which parent enrichment stages apply to this deployment, taken from the ONE
   * index-wide progress derivation (`selectIndexingProgress(...).stages`). The wire row carries
   * per-root coverage counts but no enabled flags, so without this a switched-off stage's
   * never-settling documents would pin every folder at "enriching 67%" forever.
   *
   * Absent / `null` ⟹ applicability is UNKNOWN (no poll snapshot yet, or the worker did not
   * report) ⟹ no percent is asserted and the row falls back to the pre-813 wording.
   */
  readonly enrichmentStages?: EnrichmentApplicability | null;
  /**
   * 809 finding 1 — does the enrichment backfill still owe work, INDEX-WIDE? From the ONE claim
   * derivation (`enrichmentProgress(status).pending`), passed in like `provisional` so this seam
   * stays pure and store-free. The FALLBACK gate for the `enriching` tier when this root's own
   * coverage is not derivable (`rootCoverage(...) === null`): positive evidence of pending work
   * still withholds the terminal "✓ fully searchable" claim, it just cannot name a percent.
   */
  readonly enrichmentPending?: boolean;
  /**
   * Round-15 F1b — is the SEMANTIC enrichment stage unable to run at all, index-wide? From the same
   * ONE derivation as {@link enrichmentStages} (`selectIndexingProgress(...).phase === 'blocked'`),
   * passed in for the same reason.
   *
   * A row cannot answer this from its own coverage numbers: with the embedding stage inapplicable,
   * every stage this root could count is excluded, so `rootCoverage` is `null` — the same shape as
   * "coverage not derivable yet". Without this flag the row fell through to the terminal wording and
   * rendered an unqualified green "✓ … Verified just now" at 0% coverage (F1b).
   */
  readonly enrichmentBlocked?: boolean;
  /**
   * Tempdoc 914 D3 — the failed count last observed for this root while its queue was SETTLED, from
   * {@link rememberFailedCounts}. Read only when THIS sample reports zero failures, and only in the
   * branches that have no settled answer of their own: `provisional`, `unavailable`, `walkError` and
   * `inFlight > 0` (review finding S2-1 — the first cut read it in the last of those four only,
   * which is not the one the defect usually lands in).
   *
   * Why FE memory rather than a wire field: the retry ladder re-queues a permanently FAILED job, and
   * while that job sits PENDING the worker's per-root `GROUP BY` counts it as in-flight and not as
   * failed. `IndexedRootView` carries no field that distinguishes a retry from a first attempt
   * (`inFlightCount`/`failedCount` are the only job columns), and the failed-jobs drill-down lists
   * FAILED rows only — so during that window the substrate genuinely cannot say "this folder has
   * failures". Measured on a 2-permanently-failing-file root: 4 of 60 samples at 300ms reported
   * `inFlight=2 failed=0`, which dropped the "N failed" chip and made the drill-down unreachable.
   */
  readonly lastKnownFailed?: number;
}

/** A non-negative finite count from an optional wire number (absent / negative ⟹ 0). */
function count(value: number | null | undefined): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0;
}

/**
 * Tempdoc 813 §4 — this root's enrichment coverage as a settled-over-total percent, or `null` when
 * there is no faithful denominator (the `indexingProgress` discipline at folder granularity: never
 * 0/0 → NaN, never a fabricated 0%).
 *
 * Same semantics as the index-wide selector: each APPLICABLE stage contributes its own numerator and
 * denominator, and "settled" is the TERMINAL count the Worker computed (COMPLETED + COMPLETED_EMPTY
 * where the stage defines it + FAILED — `IndexedRootView`'s numerator discipline), so a permanently
 * failed document cannot hold a folder below 100% forever. Denominator discipline is likewise the
 * wire's: every parent stage counts only the documents that carry ITS status field (chunk docs
 * excluded) and the chunk tier is counted over CHUNKED documents, never "N of M files".
 *
 * `complete` is EXACT (`settled >= total`), never "the percent rounded to 100": 999 settled of 1000
 * rounds to 100%, and claiming "fully searchable" there would be the §8.1 false-terminal in a new
 * costume. The displayed percent is correspondingly capped at 99 while work remains.
 */
interface RootCoverage {
  /** Settled coverage, capped at 99 while unsettled; null before any stage settles. */
  readonly percent: number | null;
  readonly complete: boolean;
}

function rootCoverage(
  row: IndexedRootView,
  stages: EnrichmentApplicability | null | undefined,
): RootCoverage | null {
  // Applicability UNKNOWN (no poll snapshot yet — the synchronous first store callback delivers
  // exactly this) ⟹ no tier may be claimed, in EITHER direction: "fully searchable" off a
  // surviving chunk count is the §8.1 false-terminal again.
  if (!stages) return null;
  let total = 0;
  let settled = 0;
  /**
   * A stage joins the ratio only when it is applicable, has its own denominator, AND the wire
   * actually reported its settled count. A MISSING settled key is not "zero settled": it is the
   * same absence as a missing total, and counting the stage anyway freezes a numerator at 0 over a
   * live denominator — a fraction that can never move.
   */
  const addStage = (
    applicable: boolean,
    stageTotal: number | undefined,
    stageSettled: number | undefined,
  ): void => {
    if (!applicable) return;
    if (typeof stageSettled !== 'number' || !Number.isFinite(stageSettled)) return;
    const denominator = count(stageTotal);
    if (denominator <= 0) return;
    total += denominator;
    settled += Math.min(count(stageSettled), denominator);
  };
  // Each parent stage carries its OWN denominator (the docs that actually have that stage's status
  // field — the Worker's `FieldExistsQuery` half), so a document indexed before a stage existed
  // cannot pin the folder below 100% forever.
  addStage(stages.embedding, row.parentDocsTotalEmbedding, row.parentDocsSettledEmbedding);
  addStage(stages.splade, row.parentDocsTotalSplade, row.parentDocsSettledSplade);
  addStage(stages.ner, row.parentDocsTotalNer, row.parentDocsSettledNer);
  // The chunk tier's applicability is the EMBEDDING stage's — chunk vectors come from the same
  // encoder, so with embedding off no chunk will ever settle. (A deployment that does not chunk
  // withdraws it a second way: no chunk docs, no denominator.)
  addStage(stages.embedding, row.chunkDocsTotal, row.chunkDocsSettled);
  if (total <= 0) return null;
  const complete = settled >= total;
  return {
    percent: complete ? 100 : settled <= 0 ? null : Math.min(99, Math.round((settled / total) * 100)),
    complete,
  };
}

/**
 * Derive a folder's truthful indexing status from its wire row + the system stability flag.
 * This is the sole site that decides folder searchability — the `check-folder-status-derivation`
 * gate forbids a second `ready`/`searchable` verdict elsewhere in shell-v0.
 */
export function folderStatus(row: IndexedRootView, ctx: FolderStatusContext): FolderStatus {
  const collection = row.collection ?? 'default';
  const fileCount = row.fileCount ?? -1;
  const inFlight = row.inFlightCount ?? 0;
  const failed = row.failedCount ?? 0;
  const walkError = row.walkError ?? '';
  // The folder had files indexed (its walk admitted ≥1 file and a lastIndexed timestamp was set).
  // The walk-derived `status` flips to 'indexed' exactly when lastIndexed is present.
  const indexed = row.status === 'indexed' || !!row.lastIndexedIsoTime;
  // Tempdoc 599 Fix 1 — whether the filesystem walk has TERMINATED at least once. This is the
  // load-bearing distinction: an empty / all-excluded folder is `walkCompleted` with no lastIndexed
  // (→ "empty"), whereas a walk still in progress (or never run) is not (→ "scanning"). Both
  // otherwise look identical on the wire (no lastIndexed, no walkError).
  const walkCompleted = row.walkCompleted === true;
  // Tempdoc 626 §Axis-C — the last reconcile could NOT verify index-vs-disk delete correspondence
  // (the delete-detection scan was cap-skipped for a very large root). Surfaced as a caveat on an
  // otherwise-ready folder: it stays searchable, but we cannot promise stale entries were pruned.
  const deleteDetectionUnverified = row.deleteDetectionUnverified === true;

  const fileCountText =
    fileCount >= 0
      ? `${fileCount.toLocaleString()} ${fileCount === 1 ? 'file' : 'files'}`
      : 'count pending';

  /**
   * Tempdoc 914 D3, review finding S2-1 — the carry is computed ONCE, ABOVE the branch chain.
   *
   * The first cut applied it only inside the `inFlight > 0` branch, which is not the branch the
   * defect usually lands in: `provisional`, `unavailable` and `walkError` all outrank it and all
   * returned the RAW `failed`, so a retry window that coincided with any of them still dropped the
   * chip. Measured: with the shell provisional for 168 of 250 renders, the first cut engaged 0
   * times. Every branch that does NOT have a settled zero-failure answer uses `failedShown`.
   *
   * The settled branches below (scanning / failed / ready / enriching / keyword-only / unverified /
   * empty) deliberately keep the raw `failed`: there the queue HAS answered, and resurrecting a
   * chip from memory over a drained folder would be the lie 813/906 forbid.
   */
  const carried = count(ctx.lastKnownFailed);
  const failedShown = failed > 0 ? failed : carried;
  const failedIsLastKnown = failed === 0 && carried > 0;

  // During a global rebuild, every row is provisional: render busy + last-known, never a terminal
  // fact (595 honesty vocabulary). Highest precedence so a stale "✓ indexed" can't show mid-flux.
  // The failed chip is last-known here for the same reason the file count is (S2-1).
  if (ctx.provisional) {
    return {
      state: 'unknown',
      glyph: 'pending',
      metaText:
        fileCount >= 0
          ? `${collection} · Rebuilding… · last known ${fileCountText}`
          : `${collection} · Rebuilding…`,
      inFlight,
      failed: failedShown,
      failedIsLastKnown,
    };
  }

  // Tempdoc 599 §16/A1 — the folder's path is gone (deleted/unmounted). The controller classifies the
  // path-missing walk failure to status:"unavailable"; render it calmly (a remedy, not an alarm) and
  // BEFORE the generic walkError→failed branch. Re-derived every poll, so a remount silently recovers.
  if (row.status === 'unavailable') {
    // §17.3 — keep the last-known file count visible (last-known, not "0"): the folder isn't empty,
    // it's disconnected. The FE retains the count across live ticks (LibrarySurface), so show it when
    // known rather than erasing the folder's identity.
    return {
      state: 'unavailable',
      glyph: 'unavailable',
      metaText:
        fileCount >= 0
          ? `${collection} · Folder not found — reconnect the drive, or remove it · last known ${fileCountText}`
          : `${collection} · Folder not found — reconnect the drive, or remove it`,
      inFlight,
      // S2-1 — a disconnected folder's job counts describe nothing; the failures it had are still
      // the last thing known about it, and the drill-down is still how the user reads them.
      failed: failedShown,
      failedIsLastKnown,
    };
  }

  if (walkError) {
    // S2-1 — same reason as the two branches above: a walk that errored did not answer the
    // per-file question, so a last-known failure count stays reachable rather than vanishing.
    return {
      state: 'failed',
      glyph: 'error',
      metaText: `${collection} · ${walkError}`,
      inFlight,
      failed: failedShown,
      failedIsLastKnown,
    };
  }

  // In-flight jobs → indexing, even while the walk is still discovering more (a count-down is more
  // useful than "Scanning"). Outranks the scanning branch for this reason.
  if (inFlight > 0) {
    // The `failed` count rides the structured field (rendered as a clickable chip by the row,
    // tempdoc 599 §16/B1) — not baked into the prose meta — so it can open the drill-down.
    //
    // Tempdoc 914 D3 — a retry re-queue must not make the drill-down unreachable. This sample's own
    // count always wins when it has one; only when it reports ZERO failures does the last settled
    // observation carry the chip through the window (see `lastKnownFailed`). The state line stays
    // truthful ("Indexing · N remaining" — that IS what the queue is doing) and the carried count is
    // flagged as last-known so the chip can say so rather than assert it as a present fact.
    return {
      state: ctx.indexingPaused && deleteDetectionUnverified ? 'unverified'
        : ctx.indexingPaused || ctx.retryWaiting ? 'waiting' : 'indexing',
      glyph: ctx.indexingPaused && deleteDetectionUnverified ? 'unverified' : 'pending',
      metaText: `${collection} · ${ctx.indexingPaused ? 'Waiting for Resume' : ctx.retryWaiting ? 'Waiting to retry' : 'Indexing'} · ${inFlight.toLocaleString()} remaining${ctx.indexingPaused && deleteDetectionUnverified ? " · couldn't verify deletions — reindex to be sure" : ''}`,
      inFlight,
      failed: failedShown,
      failedIsLastKnown,
    };
  }

  // Walk not yet terminated and nothing in flight → genuinely scanning (or never walked).
  if (!walkCompleted && !indexed) {
    return { state: 'scanning', glyph: 'pending', metaText: `${collection} · Scanning folder…`, inFlight, failed };
  }

  // Walk done, nothing in flight: surface terminal failures, then ready, then walked-empty.
  if (failed > 0) {
    // `failed` rides the structured field → a clickable chip on the row (tempdoc 599 §16/B1).
    return {
      state: 'failed',
      glyph: 'error',
      metaText: `${collection} · ${fileCountText}`,
      inFlight,
      failed,
    };
  }

  // Drained, indexed, no failures → searchable. The ONLY path that yields the ✓ glyph — in either of
  // its two tiers (813 §4): the row is honestly searchable as soon as its jobs drain, so the ✓ stays
  // and the meta line carries which tier (the catching-up caveat + a percent vs "fully searchable").
  if (indexed) {
    // Tempdoc 626 §Axis-C — an indexed folder whose deletions couldn't be verified must NOT show the
    // green ✓ (the 599 false-"✓" class, generalized to reconciliation completeness). It is still
    // searchable, so render a calm "couldn't verify — reindex to be sure", never an alarm.
    if (deleteDetectionUnverified) {
      const indexedSuffix = ctx.relativeTime ? ` · indexed ${ctx.relativeTime}` : '';
      return {
        state: 'unverified',
        glyph: 'unverified',
        metaText: `${collection} · ${fileCountText}${indexedSuffix} · couldn't verify deletions — reindex to be sure`,
        inFlight,
        failed,
      };
    }
    const indexedSuffix = ctx.relativeTime ? ` · indexed ${ctx.relativeTime}` : '';
    // Tempdoc 626 §Recency — the freshness heartbeat. Showing WHEN the index↔disk correspondence was
    // last confirmed turns a bare "✓" into a checkable fact ("Verified 2m ago") and makes a folder the
    // round-robin reconcile hasn't reached lately read as mildly stale ("Verified 8m ago") rather than
    // falsely-fresh. Display-only — the `index.drift-unknown` Condition owns the "needs attention" alarm.
    const verifiedSuffix = ctx.verifiedRelativeTime ? ` · Verified ${ctx.verifiedRelativeTime}` : '';
    // Tempdoc 813 §4 + 809 finding 1 — the two-tier split, with a percent rendered ONLY when this
    // root's own coverage denominator is faithful. Four honest arms:
    //   coverage known, incomplete  → `enriching` + the shared caveat + this root's percent
    //   coverage known, complete    → `ready` ("fully searchable") — even while OTHER roots still
    //                                 enrich (per-root truth outranks the index-wide boolean)
    //   coverage unknown, semantic stage inapplicable with work outstanding → `keyword-only` +
    //                                 the blocked caveat, NO percent (round-15 F1b)
    //   coverage unknown, backfill pending index-wide → `enriching` + caveat, NO percent (positive
    //                                 evidence withholds the terminal claim; it cannot name a number)
    //   coverage unknown, nothing pending → pre-813 terminal wording (no tier asserted either way)
    const coverage = rootCoverage(row, ctx.enrichmentStages);
    if (coverage !== null && !coverage.complete) {
      // Keyword-searchable NOW, semantic layers still catching up. The "indexed <time>" stamp is
      // dropped here: the folder's headline fact is the work still running, not when it last changed.
      return {
        state: 'enriching',
        glyph: 'pending',
        metaText: `${collection} · ${fileCountText} · ${ENRICHMENT_CATCHING_UP_CAVEAT}${coverage.percent === null ? '' : ` · ${coverage.percent}%`}${verifiedSuffix}`,
        inFlight,
        failed,
      };
    }
    // Round-15 F1b — the semantic stage cannot run: keyword-searchable is the WHOLE truth about this
    // folder, so the row says so instead of falling through to the terminal wording. Ranked above the
    // pending arm because the two are mutually exclusive by construction (an inapplicable stage
    // contributes no pending evidence) and this one names a state the user can act on.
    if (coverage === null && ctx.enrichmentBlocked === true) {
      return {
        state: 'keyword-only',
        glyph: 'pending',
        metaText: `${collection} · ${fileCountText} · ${ENRICHMENT_BLOCKED_CAVEAT}${verifiedSuffix}`,
        inFlight,
        failed,
      };
    }
    if (coverage === null && ctx.enrichmentPending === true) {
      return {
        state: 'enriching',
        glyph: 'pending',
        metaText: `${collection} · ${fileCountText}${indexedSuffix} · ${ENRICHMENT_CATCHING_UP_CAVEAT}`,
        inFlight,
        failed,
      };
    }
    return {
      state: 'ready',
      glyph: 'indexed',
      metaText:
        coverage === null
          ? `${collection} · ${fileCountText}${indexedSuffix}${verifiedSuffix}`
          : `${collection} · ${fileCountText} · fully searchable${indexedSuffix}${verifiedSuffix}`,
      inFlight,
      failed,
    };
  }

  // Walk completed, no files admitted, no failures → empty / nothing indexable (tempdoc 599 Fix 1).
  return { state: 'empty', glyph: 'pending', metaText: `${collection} · No indexable files`, inFlight, failed };
}

/**
 * Tempdoc 599 §16/B1 + 914 D3 — the failed chip's COPY, in ONE place for its two render sites (the
 * hand-authored Library card and the declared `FolderCardRenderer`), so the honesty qualifier
 * cannot land on one and not the other.
 *
 * Review finding S2-2: the qualifier is VISIBLE, not aria-only. A carried count reads
 * "2 failed - last known" on screen (in the muted/italic treatment `StatusDeck` already gives a
 * last-known value), with the full sentence on hover and in the accessible name.
 *
 * WHY the chip still sets `label`, given `Button.ts`'s "no label on a button with visible slot
 * text" rule: `jf-control.resolvedName()` reads `operationId`/`label` and its OWN `textContent`,
 * and this chip's text is slotted through TWO shadow roots (jf-button -> jf-control -> slot), so
 * the primitive cannot see it — dropping `label` makes it warn "no accessible name" and leans on
 * browser slot-flattening the primitive itself declares it does not do. The rule's actual
 * requirement (WCAG 2.5.3) is that the accessible name CONTAIN the visible text, not equal it, so
 * `label` is built as the visible text PLUS the explanation. `labelContainsText` below is that
 * invariant, asserted in the unit tests rather than left to callers to remember.
 */
export interface FailedChipCopy {
  /** The chip's visible text. */
  readonly text: string;
  /** The accessible name. Always starts with {@link text} (WCAG 2.5.3 "label in name"). */
  readonly label: string;
  /** Hover detail, or '' when the count is this poll's own. */
  readonly title: string;
}

export function failedChipCopy(failed: number, lastKnown: boolean): FailedChipCopy {
  const noun = `file${failed === 1 ? '' : 's'}`;
  const text = lastKnown ? `${failed} failed · last known` : `${failed} failed`;
  const explanation = lastKnown
    ? `${failed} ${noun} failed as of the last settled check; this folder has work in progress now`
    : '';
  return {
    text,
    label: lastKnown
      ? `${text} — ${explanation}. Show the failed ${noun}`
      : `${text} — show the failed ${noun}`,
    title: explanation,
  };
}

/**
 * Tempdoc 914 D3 — the BOUND on the last-known failed count `folderStatus` reads.
 *
 * Pure fold of the previous memory over the newest poll, so the retention rule is one testable
 * function rather than surface state nobody can inspect. Per root, exactly three cases:
 *
 * <ul>
 *   <li>this sample reports failures ⟹ REMEMBER that number (the memory is refreshed, never aged);
 *   <li>this sample reports none but the queue is UNSETTLED (`inFlightCount > 0`) ⟹ carry the
 *       previous number, because "0 failed" is not yet an answer — a re-queued FAILED job counts as
 *       in-flight while it waits;
 *   <li>this sample reports none with a SETTLED queue ⟹ DISCHARGE it. A drained root with no
 *       failures is the substrate answering the question directly, and it outranks any memory.
 * </ul>
 *
 * `provisional` (review finding S2-1) suspends the third case: during a global rebuild the seam
 * itself refuses to treat ANY per-root number as a terminal fact, so a transitional zero must not
 * be allowed to discharge the memory the `provisional` branch is about to render as last-known.
 * A reported failure count still refreshes it.
 *
 * A root missing from the poll is dropped (removed / not yet listed), so the map cannot grow past
 * the current listing, and no entry can survive one settled zero-failure observation of its root.
 */
export function rememberFailedCounts(
  previous: Readonly<Record<string, number>>,
  rows: readonly IndexedRootView[],
  opts: { readonly provisional?: boolean } = {},
): Record<string, number> {
  const next: Record<string, number> = {};
  for (const row of rows) {
    const hash = row.pathHash ?? '';
    if (!hash) continue;
    const failed = count(row.failedCount);
    if (failed > 0) {
      next[hash] = failed;
      continue;
    }
    const settled = count(row.inFlightCount) === 0 && opts.provisional !== true;
    if (!settled) {
      const carried = count(previous[hash]);
      if (carried > 0) next[hash] = carried;
    }
  }
  return next;
}
