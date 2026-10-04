// SPDX-License-Identifier: Apache-2.0
/**
 * indexingProgress — tempdoc 813 §3b: the ONE derivation authority for index-wide indexing/enrichment
 * progress numbers.
 *
 * Every index-wide surface (status-bar queue chip, Health's Queue card, Health's "Now" strip, the
 * indexing overlay) renders from this selector instead of privately re-deriving "how much work is
 * left" from whichever field it happened to reach. Numbers may differ between surfaces by SCOPE,
 * never by DERIVATION (§3b) — the §1a defect class was one queue described by two transports.
 *
 * Transport is settled by 813 §13: a POLL-DERIVED SELECTOR over the single `/api/status` snapshot the
 * store already holds (`statusPoll` → `aiStateStore.status`). No new SSE resource, no second fetch —
 * internally consistent by construction, because every number here comes from one snapshot.
 *
 * The snapshot type is the generated wire authority `StatusResponse`; this module declares no local
 * response-shape interface (the invariant `check-observed-state-collapse` used to gate-enforce,
 * retired 930 chunk H in favour of review).
 */

import type { StatusResponse } from '../../api/generated/index.js';

/**
 * The 813 §3a phase model, index-wide scope. `Scanning` is per-scan (the scan SSE) and is therefore
 * not derivable from this snapshot — it is not modelled here.
 *
 * `blocked` (round-15 F1/F1b) is the fourth arm the original three could not express: documents that
 * NEED semantic enrichment while the stage that would produce it is not applicable at all (no
 * embedding service — `NO_EMBEDDING_MODEL`). Before it, that state fell through the applicability
 * filter and was indistinguishable from `ready`, so the card claimed "Everything is indexed and
 * enriched" at 0% coverage — a completion signal green precisely because the work never became
 * reachable (`unreachable-seed-green`). It is deliberately NOT folded into `enriching`: nothing is
 * running, so no percent moves and no estimate exists.
 */
export type IndexingPhase = 'indexing' | 'enriching' | 'blocked' | 'ready' | 'unknown';

/**
 * 813 §4 — which PARENT enrichment stages are applicable to this deployment, index-wide.
 *
 * The per-root wire row (`IndexedRootView`) carries coverage COUNTS but no enabled flags, so a
 * per-root percent has no way of its own to learn that (say) SPLADE is switched off — and a
 * disabled stage's permanently-unsettled documents would pin every folder below 100% forever
 * (the index-wide `stage()` guard below, applied at folder granularity). This is the ONE place
 * applicability is decided, so the per-root derivation consumes it instead of re-reading the
 * `*Enabled` wire flags itself.
 *
 * UNKNOWN applicability is `null`, never an all-false object: all-false is a legible claim ("no
 * stage applies here"), and a consumer that reads it as such renders a coverage tier off whatever
 * counts survive — the {@link EMPTY} snapshot's own defect before this was corrected. The
 * synchronous first callback of `subscribeAiState` delivers exactly that pre-poll snapshot to every
 * consumer on mount, so this is the production path, not an edge case.
 */
export interface EnrichmentApplicability {
  readonly embedding: boolean;
  readonly splade: boolean;
  readonly ner: boolean;
}

/**
 * One enrichment stage's contribution to the blend, tagged with the stage it came from (813 §20).
 *
 * `settled = total - pending` rather than `completed + failed` on purpose: the wire exposes only the
 * COMPLETED / PENDING / FAILED buckets, while a stage's terminal vocabulary also includes
 * COMPLETED_EMPTY (ran fine, produced nothing — `IndexStatusOps.buildEnrichment`'s own note for NER).
 * Counting "not pending" is therefore the faithful TERMINAL count per 813 §13; counting
 * `completed + failed` would under-count and leave enrichment looking permanently unfinished.
 *
 * The id is the MACHINE stage name and stays a machine name here: 813 §20's two layers are
 * capability tiers on the surface and machine stages in the disclosure, so the user-facing wording
 * belongs to the consuming surface, not to this projection.
 */
export interface EnrichingStageRow {
  readonly id: 'embedding' | 'splade' | 'ner' | 'chunkVectors';
  readonly total: number;
  readonly pending: number;
}

/**
 * 813 §20 — one observation of the enrichment blend's SETTLED sum at a wall-clock instant. The
 * enrichment rate cannot be read off a single snapshot (the wire carries no enrichment-throughput
 * gauge, and `core.recentDocsPerSec` measures INDEXING), so the store keeps a short trail of these
 * and this module turns them into a rate. Owned and stamped by `aiStateStore`, exactly as
 * {@link IndexingProgress.indexingPercent}'s high-water denominator is.
 */
export interface EnrichSettleSample {
  readonly t: number;
  readonly settled: number;
}

export interface IndexingProgress {
  /** §3a phase. `unknown` ⟹ no trustworthy progress observation; NO number below may be rendered. */
  phase: IndexingPhase;
  /** Non-terminal job rows (PENDING + PROCESSING) — `worker.core.pendingJobs` (the queueDepth projection). */
  jobsPending: number;
  /** The PROCESSING slice of {@link jobsPending}. */
  jobsRunning: number;
  /** The PENDING slice of {@link jobsPending}. */
  jobsQueued: number;
  /**
   * Settled-over-total across every APPLICABLE enrichment stage (parent embedding/SPLADE/NER + the
   * chunk tier), 0-100, rounded. `null` when there is no faithful denominator — the worker did not
   * report, or no stage has any documents to enrich. Never NaN, never a fabricated number
   * (`availability.ts:193-194`'s discipline, applied to progress).
   */
  enrichingPercent: number | null;
  /** Documents still PENDING across every applicable enrichment stage (0 when nothing is pending). */
  enrichingPending: number;
  /**
   * Round-15 F1 — documents whose SEMANTIC enrichment is outstanding while the embedding stage is
   * NOT applicable (no embedding service). Positive evidence that the work exists AND cannot run:
   * the sole basis for the {@link IndexingPhase} `blocked` arm, and the reason a surface may not
   * read this state as completion. 0 whenever embedding is applicable — a reachable backlog is
   * {@link enrichingPending}'s subject, not this one.
   */
  blockedPending: number;
  /**
   * 813 §20 — the per-stage BREAKDOWN of {@link enrichingPercent}, one row per stage that
   * contributes to the blend. A PROJECTION of that blend, not a second derivation: the rows are the
   * very {@link EnrichingStageRow}s the percent sums, so a disclosure showing them can differ from
   * the surface percent by SCOPE (one stage vs all) but never by DERIVATION (§3b).
   *
   * Empty whenever the blend has no inputs — a disabled stage contributes no row (as it contributes
   * no numerator and no denominator), and a stage with no documents to enrich has nothing to say.
   */
  enrichingStages: readonly EnrichingStageRow[];
  /**
   * 813 §20 — a COARSE, INDICATIVE seconds-remaining for the `enriching` phase, extrapolated from
   * the OBSERVED settle rate across polls ({@link EnrichSettleSample}). Deliberately NOT
   * {@link etaSeconds}' rate: `core.recentDocsPerSec` gauges the indexing pipeline, and reusing it
   * here would answer a question about enrichment with a measurement of something else.
   *
   * `null` — render nothing, never a placeholder — whenever there is no honest basis: any phase but
   * `enriching`; a stale snapshot; fewer than {@link ENRICH_ETA_MIN_INTERVALS} measured intervals; an
   * interval where the settled sum did not strictly advance (paused/preempted backfill, or the
   * denominator itself moving as ingest adds documents); or a result beyond {@link ETA_MAX_SECONDS},
   * which a six-sample trail cannot support.
   */
  enrichingEtaSeconds: number | null;
  /** Whole documents whose parent embedding is still PENDING (`worker.enrichment.embedding.pendingCount`). */
  embeddingPending: number;
  /** Documents still awaiting visual (VDU) extraction (`worker.core.pendingVduCount`). */
  vduPending: number;
  /**
   * 813 §5b — a COARSE, INDICATIVE seconds-remaining for the {@link IndexingPhase} `indexing` arm
   * only, extrapolated from observed recent throughput over the remaining backlog. `null` whenever
   * there is no honest basis, and the caller renders NOTHING rather than a placeholder:
   *  - any phase other than `indexing` — during `enriching` throughput is legitimately unstable
   *    (§1e: ingest preempts the backfill at batch boundaries), so an estimate there would be a
   *    fabricated number;
   *  - a stale snapshot (`live === false`) — extrapolating from a past measurement asserts a
   *    present rate nobody observed;
   *  - a backlog below {@link ETA_MIN_JOBS}, where the estimate is noise, not information;
   *  - an unstable/absent rate (see {@link deriveEtaSeconds});
   *  - a result beyond {@link ETA_MAX_SECONDS}, which a trailing-3-sample window cannot support;
   *  - a backlog that is still GROWING (a walk is enqueueing faster than the queue drains), where
   *    "remaining / rate" is extrapolating a denominator that has not stopped moving.
   * Doc-count-based by construction and therefore never promoted to a countdown: {@link
   * pendingBytes} is the weight of the same backlog, but sizes are recorded per job and the two are
   * deliberately not combined into a byte-rate, so on a mixed corpus this stays an
   * order-of-magnitude hint at best.
   */
  etaSeconds: number | null;
  /**
   * 813 §19 (W2) — the DETERMINATE position of the `indexing` phase's drain, 0-100, measured against
   * the largest backlog observed this drain episode (`AiState.episodeMaxPendingJobs`, the store's
   * cross-poll memory). `null` whenever there is no denominator to be honest about:
   *  - any phase other than `indexing` (the enrichment fraction is {@link enrichingPercent}'s job);
   *  - `episodeMax <= jobsPending` — no drain has been OBSERVED yet, so the backlog's total is
   *    genuinely unknown and the caller falls back to the indeterminate affordance.
   * Never NaN, never a fabricated 0 from a missing denominator. A *measured* 0 is possible and is
   * kept: `episodeMax > jobsPending` is strictly true there, so the drain was witnessed and rounded
   * below half a percent — "barely started" is what happened, and withholding it would be less
   * truthful than showing it.
   */
  indexingPercent: number | null;
  /**
   * 813 Slice B — the byte weight of the remaining job backlog (`worker.core.pendingBytes`), or
   * `null` when it would not be faithful: nothing recorded, the worker could not compute the
   * aggregate, or more than half the remaining jobs carry no recorded size
   * (`pendingUnknownSizeJobs`), where the sum understates the backlog enough to mislead.
   */
  pendingBytes: number | null;
  /**
   * Is this snapshot still a LIVE observation? Threaded in from the ONE liveness authority
   * (`AiState.snapshotLive`, 807 A.3) — this module does NOT invent a staleness signal, and in
   * particular never reads `chunkCoverage.observedAtMs`, which is a Head serialization stamp.
   */
  live: boolean;
  /**
   * 813 §4 — the applicable parent enrichment stages (see {@link EnrichmentApplicability}). Read by
   * the per-root folder derivation (`folderStatus`), which has counts but no enabled flags.
   * `null` ⟹ applicability is UNKNOWN and NO consumer may claim a coverage tier from it.
   */
  stages: EnrichmentApplicability | null;
}

/**
 * Index states the WORKER itself reports (`IndexStatusOps.buildCore`). Anything else — the
 * `WorkerOperationalView.fallback(...)` states ("UNAVAILABLE", "NOT_STARTED", or a `CapabilityHealth`
 * name) — means the whole worker block was HARD-ZEROED before serialization
 * (`WorkerStatusCache.status()` / `CoreIndexView.fallback`), so its zeros are absence, not "settled".
 * Reading them as `ready` is the "0 == done" lie this allowlist exists to prevent.
 */
const INDEX_REPORTED_STATES: ReadonlySet<string> = new Set(['IDLE', 'INDEXING', 'ERROR', 'FAILED']);

/**
 * Does this snapshot carry a WORKER-REPORTED index block (as opposed to the hard-zeroed fallback
 * shape described above)? Exported so `aiStateStore`'s cross-poll high-water stamp applies the SAME
 * admission test this projection does: a fallback block's `pendingJobs: 0` is absence, not a drained
 * queue, and reading it as a drain would reset the episode's denominator mid-drain.
 */
export function isWorkerReportedIndex(status: StatusResponse | null | undefined): boolean {
  const core = status?.worker?.core;
  if (!status || !core) return false;
  return INDEX_REPORTED_STATES.has(core.indexState ?? '');
}

const EMPTY: IndexingProgress = {
  phase: 'unknown',
  jobsPending: 0,
  jobsRunning: 0,
  jobsQueued: 0,
  enrichingPercent: null,
  enrichingPending: 0,
  blockedPending: 0,
  enrichingStages: [],
  enrichingEtaSeconds: null,
  embeddingPending: 0,
  vduPending: 0,
  etaSeconds: null,
  indexingPercent: null,
  pendingBytes: null,
  live: false,
  // Nothing was reported ⟹ applicability is UNKNOWN ⟹ no per-root percent may be asserted. `null`,
  // not all-false: all-false reads as "no stage applies", which is itself a claim.
  stages: null,
};

/** Trailing `recentDocsPerSec` samples that must ALL be non-zero before the rate is extrapolated. */
const ETA_STABLE_SAMPLES = 3;
/** Below this backlog the extrapolation is noise (at ~1 doc/s the answer is "a moment", not a number). */
const ETA_MIN_JOBS = 20;
/**
 * Above this the trailing-window extrapolation is not credible, and the shared humanizer
 * (`startupEstimate.humanizeSeconds`) has no hour form — so the estimate is withdrawn rather than
 * rendered as an implausible "180m".
 */
const ETA_MAX_SECONDS = 3600;
/**
 * 813 §20 — how many settle samples the store retains. One more than the intervals the enrichment
 * estimate needs, plus headroom, so a median over intervals is possible without keeping a history
 * whose oldest end no longer describes the current rate.
 */
export const ENRICH_SETTLE_SAMPLE_CAP = 6;
/** Measured intervals that must ALL show forward progress before an enrichment rate is extrapolated. */
const ENRICH_ETA_MIN_INTERVALS = 3;

function count(value: number | null | undefined): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0;
}

function stage(
  id: EnrichingStageRow['id'],
  total: number,
  pending: number,
  enabled: boolean | undefined,
): EnrichingStageRow | null {
  // A disabled stage is not-applicable — it contributes to neither numerator nor denominator, so a
  // deployment with SPLADE off cannot be stuck at "67% enriched" forever.
  if (enabled === false) return null;
  if (total <= 0) return null;
  return { id, total, pending: Math.min(pending, total) };
}

/**
 * Everything the enrichment half of the projection needs, read ONCE from one snapshot (813 §20).
 *
 * Assembled here rather than inline in the selector so the three consumers — the selector's percent
 * blend, {@link selectIndexingPhase}, and {@link enrichSettledSum} (which the store calls to stamp
 * its rate memory) — cannot answer "which stages count, and how much of each is settled?" three
 * different ways. That is the §3b rule applied one level down: one derivation, several scopes.
 */
interface EnrichmentWork {
  /** The ONE applicability decision (813 §4). */
  readonly applicable: EnrichmentApplicability;
  /** The blend's inputs: applicable stages that also have a faithful denominator. */
  readonly rows: readonly EnrichingStageRow[];
  /**
   * Phase evidence: pending across applicable stages INCLUDING the denominator-less ones. A stage
   * with no faithful denominator contributes no PERCENT, but its pending counter is still evidence
   * of unfinished work (813 §17 / §1d's false terminal).
   */
  readonly rawPending: number;
  /**
   * Round-15 F1 — the same evidence for work that is UNREACHABLE: pending documents on the semantic
   * stages (parent embedding + the chunk tier that shares its encoder) while embedding is NOT
   * applicable. See {@link IndexingProgress.blockedPending}.
   *
   * Scoped to the EMBEDDING stage on purpose. SPLADE and NER are independently configurable — a
   * deployment can legitimately run with SPLADE off forever (`SpladeConfig.from`), and their
   * never-settling documents are exactly what the applicability filter exists to discount. Embedding
   * is the stage the completion claim rests on: it is what "semantic search" means to the user, it is
   * what `NO_EMBEDDING_MODEL` reports, and its absence is a state the user can ACT on (install AI),
   * which is what makes saying so useful rather than merely pedantic.
   */
  readonly blockedPending: number;
}

function readEnrichmentWork(status: StatusResponse | null | undefined): EnrichmentWork {
  const enrichment = status?.worker?.enrichment;
  const chunk = enrichment?.chunk ?? null;
  const nerCompleted = count(enrichment?.completedNerCount);
  const nerPending = count(enrichment?.pendingNerCount);

  // The ONE applicability decision (813 §4): consumed by the index-wide stage math below and, via
  // `IndexingProgress.stages`, by the per-root folder derivation — so "is SPLADE on?" is answered
  // once for every progress surface.
  const applicable: EnrichmentApplicability = {
    embedding: enrichment?.embeddingEnabled !== false,
    splade: enrichment?.spladeEnabled !== false,
    ner: enrichment?.nerEnabled !== false,
  };

  const rows = [
    stage(
      'embedding',
      count(enrichment?.embeddingDocCount),
      count(enrichment?.embeddingPendingCount),
      applicable.embedding,
    ),
    stage(
      'splade',
      count(enrichment?.spladeDocCount),
      count(enrichment?.spladePendingCount),
      applicable.splade,
    ),
    // NER reports no doc-count, so its denominator is its own two-valued census.
    stage('ner', nerCompleted + nerPending, nerPending, applicable.ner),
    // The chunk tier's denominator is CHUNKED documents (813 §13) — never "N of M files". Its
    // applicability is the EMBEDDING stage's: chunk vectors come from the same encoder, so a
    // deployment with embedding switched off (or no embedding service at all) never settles a
    // single chunk. Passing "unknown" here left those chunks pending forever ⟹ phase stuck at
    // `enriching` ⟹ "Up to date" / "System idle" / folder "fully searchable" unreachable.
    stage(
      'chunkVectors',
      count(chunk?.chunkDocCount),
      count(chunk?.chunkEmbeddingPendingCount),
      applicable.embedding,
    ),
  ].filter((s): s is EnrichingStageRow => s !== null);

  // Disabled stages stay excluded: their pending can never settle (813 review F1), so counting them
  // would pin the phase at `enriching` forever.
  const rawPending =
    (applicable.embedding ? count(enrichment?.embeddingPendingCount) : 0) +
    (applicable.splade ? count(enrichment?.spladePendingCount) : 0) +
    (applicable.ner ? nerPending : 0) +
    (applicable.embedding ? count(chunk?.chunkEmbeddingPendingCount) : 0);

  // The mirror of the line above: the SAME counters on the SAME stages, taken when the stage is not
  // applicable. Excluding them from the blend is right (nothing will settle, so a percent and an ETA
  // would both be fabrications); excluding them from the EVIDENCE is what made 0% coverage read as
  // completion.
  const blockedPending = applicable.embedding
    ? 0
    : count(enrichment?.embeddingPendingCount) + count(chunk?.chunkEmbeddingPendingCount);

  return { applicable, rows, rawPending, blockedPending };
}

/**
 * The positive-evidence phase gate (merged from #375's enrichmentCoverage doctrine, 813 §17).
 * Pending work on an APPLICABLE stage withholds the terminal phase even when that stage lacks a
 * faithful denominator — "Up to date" off a missing denominator would be the §1d false terminal.
 *
 * COUNTS ONLY. `enrichment.backfillMode` is deliberately NOT consulted (813 §20a, owner finding
 * 2026-08-07): it is a LAST-KNOWN operator gauge, written once per `BackfillScheduler.runIdleCycle()`
 * and held between cycles (`OperationalMetrics.getBackfillMode` — "no backfill work was
 * available/eligible LAST cycle"), so it describes which pass ran, not whether work remains. Reading
 * it as activity produced the mirror image of §1d: on a fully settled index (every stage 0 pending)
 * a stuck `"individual"` gauge kept the phase at `enriching` forever — "Ready — fully searchable"
 * unreachable, the Tasks card claiming "still improving" over an index with nothing left to improve.
 * The doctrine is symmetric and the gauge fails it in both directions: pending counts are the
 * evidence, and a gauge is not a count.
 *
 * COUNTS ONLY, in BOTH directions (round-15 F1). Zero REACHABLE work is not evidence of completion
 * when the reason it is zero is that the stage producing it does not exist: `blocked` outranks
 * `ready` on the same positive-evidence rule that makes pending outrank drained. It also outranks
 * `enriching`, because the enriching tier's own words ("semantic search catching up") are false while
 * the semantic stage cannot run — a SPLADE-only backfill is still visible as a stage row in the
 * disclosure, so nothing is hidden by saying the truer thing in the headline.
 */
function derivePhase(jobsPending: number, work: EnrichmentWork): IndexingPhase {
  if (jobsPending > 0) return 'indexing';
  if (work.blockedPending > 0) return 'blocked';
  return work.rawPending > 0 ? 'enriching' : 'ready';
}

/**
 * The §3a phase alone, as a pure function of one snapshot (813 §20).
 *
 * Exported for `aiStateStore`, whose enrichment-rate memory must be CLEARED the moment the phase
 * stops being `enriching` (a fresh episode measures itself). The store therefore needs the phase
 * before the selector can run — and answering it with a private re-derivation is exactly the fork
 * §3b forbids, so it asks the same function {@link selectIndexingProgress} uses.
 */
export function selectIndexingPhase(status: StatusResponse | null | undefined): IndexingPhase {
  const core = status?.worker?.core;
  if (!isWorkerReportedIndex(status) || !core) return 'unknown';
  // The counts remain real, but no progress or completion claim survives a fatal loop death.
  if (core.indexState === 'FAILED') return 'unknown';
  return derivePhase(count(core.pendingJobs), readEnrichmentWork(status));
}

/**
 * The enrichment blend's SETTLED sum — documents past the pending bucket across every stage that
 * contributes to {@link IndexingProgress.enrichingPercent} (813 §20).
 *
 * The ONE settled-sum authority. The store stamps its rate samples by calling THIS, so the trail it
 * accumulates measures the very quantity the percent renders; a store-side re-derivation could drift
 * (a differently-filtered stage set would make the estimate describe a different denominator than
 * the bar above it).
 */
export function enrichSettledSum(status: StatusResponse | null | undefined): number {
  let settled = 0;
  for (const row of readEnrichmentWork(status).rows) settled += row.total - row.pending;
  return settled;
}

/**
 * The coarse enrichment seconds-remaining, or `null` when there is no honest basis (see
 * {@link IndexingProgress.enrichingEtaSeconds}).
 *
 * Every interval must show the settled sum STRICTLY advancing. Enrichment's own instability (§1e:
 * ingest preempts the backfill at batch boundaries) is precisely what a "0 documents settled this
 * poll" interval reports, and averaging over it would extrapolate a rate nobody observed. As with
 * {@link deriveEtaSeconds} the MEDIAN interval rate is used rather than the mean, so one fast poll
 * cannot halve the estimate.
 */
function deriveEnrichEtaSeconds(
  samples: readonly EnrichSettleSample[],
  pending: number,
): number | null {
  if (pending <= 0) return null;
  if (samples.length < ENRICH_ETA_MIN_INTERVALS + 1) return null;
  const rates: number[] = [];
  for (let i = 1; i < samples.length; i += 1) {
    const elapsedSec = (samples[i]!.t - samples[i - 1]!.t) / 1000;
    const settledDelta = samples[i]!.settled - samples[i - 1]!.settled;
    if (!Number.isFinite(elapsedSec) || elapsedSec <= 0) return null;
    if (!Number.isFinite(settledDelta) || settledDelta <= 0) return null;
    rates.push(settledDelta / elapsedSec);
  }
  if (rates.length < ENRICH_ETA_MIN_INTERVALS) return null;
  const sorted = [...rates].sort((a, b) => a - b);
  const rate = sorted[Math.floor(sorted.length / 2)]!;
  if (!Number.isFinite(rate) || rate <= 0) return null;
  const seconds = Math.round(pending / rate);
  if (!Number.isFinite(seconds) || seconds <= 0 || seconds > ETA_MAX_SECONDS) return null;
  return seconds;
}

/**
 * Is the job backlog still GROWING? Read off the last two samples of the SAME snapshot's
 * `worker.core.recentJobQueueDepth` trend, so it needs no cross-poll memory.
 *
 * While a walk is still enqueueing, "remaining / rate" divides a denominator that is still rising —
 * the estimate counts UP as the user watches it, which is worse than no estimate (813 §5b: coarse
 * or absent, never wrong). A refutation only: a trend too short to compare cannot establish growth,
 * so it leaves the other suppressions to decide.
 */
function backlogGrowing(depths: readonly number[] | null | undefined): boolean {
  if (!depths || depths.length < 2) return false;
  const last = depths[depths.length - 1];
  const prev = depths[depths.length - 2];
  if (typeof last !== 'number' || typeof prev !== 'number') return false;
  if (!Number.isFinite(last) || !Number.isFinite(prev)) return false;
  return last > prev;
}

/**
 * The coarse seconds-remaining, or `null` when there is no honest basis (see
 * {@link IndexingProgress.etaSeconds} for the full suppression list).
 *
 * `worker.core.recentDocsPerSec` is a 30-minute RRD trend of the worker's own 180 s rolling
 * throughput gauge (`WorkerOpsMetricCatalog.INDEX_DOCS_PER_SEC`), which reports **0.0** for windows
 * it could not measure (insufficient samples, or nothing processing). So "every sample in the
 * trailing window is non-zero" is the stability test the producer's own vocabulary supports; the
 * MEDIAN of that window is used rather than the mean so one spike (a burst of tiny files) cannot
 * halve the estimate.
 */
function deriveEtaSeconds(
  samples: readonly number[] | null | undefined,
  queueDepths: readonly number[] | null | undefined,
  jobsPending: number,
): number | null {
  if (jobsPending < ETA_MIN_JOBS) return null;
  if (backlogGrowing(queueDepths)) return null;
  if (!samples || samples.length < ETA_STABLE_SAMPLES) return null;
  const tail = samples.slice(-ETA_STABLE_SAMPLES);
  if (!tail.every((v) => Number.isFinite(v) && v > 0)) return null;
  const sorted = [...tail].sort((a, b) => a - b);
  const rate = sorted[Math.floor(sorted.length / 2)]!;
  const seconds = Math.round(jobsPending / rate);
  if (!Number.isFinite(seconds) || seconds <= 0 || seconds > ETA_MAX_SECONDS) return null;
  return seconds;
}

/**
 * The remaining backlog's byte weight, or `null` when it is not faithful (see {@link
 * IndexingProgress.pendingBytes}).
 *
 * `knownBytes <= 0` covers both "nothing recorded" and the worker's UNAVAILABLE marker (which
 * reports zero bytes plus `unknownSizeJobs = -1`, normalized to 0 by {@link count}). The
 * half-the-backlog rule is the same discipline as the enrichment percent: a denominator that is
 * mostly guesses is not a denominator.
 */
function derivePendingBytes(
  knownBytes: number,
  unknownSizeJobs: number,
  jobsPending: number,
): number | null {
  // A weight with no backlog is a contradiction (a residue of one poll's aggregate against
  // another's count); the backlog is the subject, so no backlog means no claim.
  if (jobsPending <= 0) return null;
  if (knownBytes <= 0) return null;
  if (unknownSizeJobs * 2 > jobsPending) return null;
  return knownBytes;
}

/**
 * Derive the index-wide progress projection from one `/api/status` snapshot.
 *
 * @param status the retained poll snapshot (`AiState.status`); `null` before the first poll.
 * @param snapshotLive the ONE liveness answer (`AiState.snapshotLive`).
 * @param episodeMaxPendingJobs the CROSS-POLL memory this pure function cannot have: the largest
 *   backlog observed during the current drain episode (`AiState.episodeMaxPendingJobs`, owned and
 *   stamped by the store). REQUIRED, not optional-with-a-default: a defaulted parameter would let
 *   six surfaces silently derive a different {@link IndexingProgress.indexingPercent} from the
 *   seventh — the two-derivation drift §3b forbids. The selector itself stays pure.
 * @param enrichSettleSamples the second piece of cross-poll memory the store owns (813 §20): the
 *   trail of {@link EnrichSettleSample}s backing {@link IndexingProgress.enrichingEtaSeconds}.
 *   REQUIRED for the same reason, and stamped through {@link enrichSettledSum} so the trail and the
 *   percent measure one quantity.
 */
export function selectIndexingProgress(
  status: StatusResponse | null | undefined,
  snapshotLive: boolean,
  episodeMaxPendingJobs: number,
  enrichSettleSamples: readonly EnrichSettleSample[],
): IndexingProgress {
  const core = status?.worker?.core;
  if (!isWorkerReportedIndex(status) || !core || core.indexState === 'FAILED') {
    return { ...EMPTY, live: snapshotLive };
  }

  const migration = status.worker?.migration;
  const jobsPending = count(core.pendingJobs);
  // The running/queued split comes from the SAME jobs table this count projects (`jobStateCounts()`),
  // not from counting rendered task rows — that row list is a different transport (the SSE mirror)
  // and is exactly what drifted in §1a.
  const jobsRunning = Math.min(count(migration?.processingJobsCount), jobsPending);
  // Derived, not read from `migration.pendingJobsCount`: `queueDepth()` and `jobStateCounts()` are two
  // separate reads of the jobs table, so trusting both independently could render a split that does not
  // add up to the total the same surface shows. running + queued == jobsPending, by construction.
  const jobsQueued = jobsPending - jobsRunning;

  // ONE read of the enrichment half (813 §20) — the same `EnrichmentWork` the phase gate and the
  // store's settled-sum stamp consume, so the blend, its per-stage breakdown and the rate memory are
  // three SCOPES of one derivation rather than three derivations.
  const work = readEnrichmentWork(status);
  const applicable = work.applicable;

  let total = 0;
  let pending = 0;
  for (const s of work.rows) {
    total += s.total;
    pending += s.pending;
  }
  // No applicable stage ⟹ no faithful denominator ⟹ no percent (never 0/0 → NaN, never a fake 0%).
  // Symmetric rule (owner finding, 2026-08-06): never a fake 100% either — Math.round lets a
  // sub-half-percent tail (e.g. 2 of 600 pending) render "100%" beside "semantic search catching
  // up", a full bar contradicting its own caveat. While ANY counted work is pending the display
  // is capped at 99; a true 100 is only reachable when pending === 0 (and the phase gate, which
  // also sees denominator-less stages, still decides `ready` on its own evidence).
  const enrichingPercent =
    total > 0 ? Math.min(pending > 0 ? 99 : 100, Math.round(((total - pending) / total) * 100)) : null;

  const phase = derivePhase(jobsPending, work);

  // 813 §19 (W2) — the high-water denominator. `episodeMax > jobsPending` is the whole admission
  // test: it is simultaneously "a drain was observed" and "the denominator is positive", so the
  // division can neither be 0/0 nor produce a percent from a backlog that has only ever grown.
  const episodeMax = count(episodeMaxPendingJobs);
  const indexingPercent =
    phase === 'indexing' && episodeMax > jobsPending
      ? Math.round(((episodeMax - jobsPending) / episodeMax) * 100)
      : null;

  return {
    phase,
    jobsPending,
    jobsRunning,
    jobsQueued,
    enrichingPercent,
    // The displayed pending count matches the phase evidence (rawPending), not the percent's
    // stage-filtered sum — a surface saying "enriching" must be able to show the work it saw.
    enrichingPending: work.rawPending,
    // The same discipline for the `blocked` arm: the evidence the phase was decided on, so a surface
    // saying "semantic search is waiting" can show how much is waiting.
    blockedPending: work.blockedPending,
    // The percent's own inputs, handed on unchanged (813 §20) — a projection of the blend, not a
    // second pass over the wire.
    enrichingStages: work.rows,
    enrichingEtaSeconds:
      phase === 'enriching' && snapshotLive
        ? deriveEnrichEtaSeconds(enrichSettleSamples, pending)
        : null,
    embeddingPending: count(status.worker?.enrichment?.embeddingPendingCount),
    vduPending: count(core.pendingVduCount),
    etaSeconds:
      phase === 'indexing' && snapshotLive
        ? deriveEtaSeconds(core.recentDocsPerSec, core.recentJobQueueDepth, jobsPending)
        : null,
    indexingPercent,
    pendingBytes: derivePendingBytes(
      count(core.pendingBytes),
      count(core.pendingUnknownSizeJobs),
      jobsPending,
    ),
    live: snapshotLive,
    stages: applicable,
  };
}
