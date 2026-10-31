// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 813 §3b — the indexing-progress projection is the ONE derivation authority for index-wide
 * progress numbers. These cases pin the properties every consuming surface depends on: the phase
 * arms, the hard-zeroed-snapshot `unknown` arm (a worker that never reported must NOT read as
 * "ready"), disabled-stage handling, and denominator honesty (no NaN, no percent without a real
 * denominator).
 */
import { describe, it, expect } from 'vitest';
import {
  enrichSettledSum,
  isWorkerReportedIndex,
  selectIndexingPhase,
  selectIndexingProgress,
  type EnrichSettleSample,
} from './indexingProgress.js';
import type { StatusResponse } from '../../api/generated/index.js';

type Core = NonNullable<NonNullable<StatusResponse['worker']>['core']>;
type Enrichment = NonNullable<NonNullable<StatusResponse['worker']>['enrichment']>;
type Migration = NonNullable<NonNullable<StatusResponse['worker']>['migration']>;

function snapshot(parts: {
  core?: Partial<Core>;
  enrichment?: Partial<Enrichment>;
  migration?: Partial<Migration>;
}): StatusResponse {
  return {
    worker: {
      core: { indexState: 'IDLE', pendingJobs: 0, ...parts.core },
      enrichment: { backfillMode: 'idle', ...parts.enrichment },
      migration: { ...parts.migration },
    },
  } as StatusResponse;
}

/**
 * Call the selector with the two store-owned memories defaulted to "nothing observed yet" — no drain
 * high-water, no enrichment settle trail — which is what every case below except the W2 and §20
 * groups is about. Both parameters are REQUIRED on the production function on purpose (813 §19 W2 /
 * §20: a defaulted one would let surfaces derive different numbers); this local default keeps the
 * other cases reading about their own subject.
 */
function select(
  status: StatusResponse | null | undefined,
  live: boolean,
  episodeMaxPendingJobs = 0,
  enrichSettleSamples: readonly EnrichSettleSample[] = [],
): ReturnType<typeof selectIndexingProgress> {
  return selectIndexingProgress(status, live, episodeMaxPendingJobs, enrichSettleSamples);
}

describe('selectIndexingProgress — phase arms (813 §3a)', () => {
  it('fatal loop state is reported evidence but never ongoing progress or completion', () => {
    for (const pendingJobs of [0, 200]) {
      const status = snapshot({ core: { indexState: 'FAILED', pendingJobs } });
      expect(isWorkerReportedIndex(status)).toBe(true);
      expect(selectIndexingPhase(status)).toBe('unknown');
      const progress = select(status, true, 300);
      expect(progress.phase).toBe('unknown');
      expect(progress.indexingPercent).toBeNull();
      expect(progress.enrichingPercent).toBeNull();
      expect(progress.etaSeconds).toBeNull();
      expect(progress.enrichingEtaSeconds).toBeNull();
    }
  });
  it('pending jobs ⇒ "indexing", with a running/queued split that adds up to the total', () => {
    const p = select(
      snapshot({
        core: { indexState: 'INDEXING', pendingJobs: 1218 },
        migration: { processingJobsCount: 4 },
      }),
      true,
    );
    expect(p.phase).toBe('indexing');
    expect(p.jobsPending).toBe(1218);
    expect(p.jobsRunning).toBe(4);
    expect(p.jobsQueued).toBe(1214);
    expect(p.jobsRunning + p.jobsQueued).toBe(p.jobsPending);
  });

  it('jobs drained but enrichment counters still pending ⇒ "enriching"', () => {
    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingDocCount: 100,
          embeddingPendingCount: 40,
          embeddingEnabled: true,
        },
      }),
      true,
    );
    expect(p.phase).toBe('enriching');
    expect(p.enrichingPercent).toBe(60);
    expect(p.enrichingPending).toBe(40);
  });

  // 813 §20a (owner live finding, 2026-08-07) — INVERTED from the pre-§20a expectation, which read
  // a non-idle `backfillMode` as activity. The gauge is LAST-KNOWN (written once per
  // `BackfillScheduler.runIdleCycle()`, held between cycles), so with no pending counter anywhere
  // there is no evidence of work and the honest phase is the terminal one. The old expectation is
  // what kept a fully-settled index at "still improving" forever.
  it('jobs drained and NO pending counters ⇒ "ready", whatever the backfill gauge last said', () => {
    for (const backfillMode of ['combined', 'individual', 'idle', '']) {
      const p = select(
        snapshot({
          core: { indexState: 'IDLE', pendingJobs: 0 },
          enrichment: { backfillMode },
        }),
        true,
      );
      expect(p.phase, `backfillMode=${JSON.stringify(backfillMode)}`).toBe('ready');
    }
  });

  // The owner's exact live snapshot: every stage fully settled, gauge stuck on "individual".
  it('REGRESSION: a fully SETTLED index reaches "ready" while the gauge still says "individual"', () => {
    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'individual',
          embeddingDocCount: 21,
          embeddingPendingCount: 0,
          embeddingEnabled: true,
          spladeDocCount: 21,
          spladePendingCount: 0,
          spladeEnabled: true,
          completedNerCount: 21,
          pendingNerCount: 0,
          nerEnabled: true,
          chunk: { chunkDocCount: 84, chunkEmbeddingPendingCount: 0 },
        },
      }),
      true,
    );
    expect(p.phase).toBe('ready');
    expect(p.enrichingPending).toBe(0);
    // Right-reason guard: the stages ARE all counted (so this is not passing because the blend was
    // empty) and the percent reaches a true 100 — the §20 floor only caps a PENDING tail.
    expect(p.enrichingStages.map((r) => r.id)).toEqual([
      'embedding',
      'splade',
      'ner',
      'chunkVectors',
    ]);
    expect(p.enrichingPercent).toBe(100);
  });

  // Evidence wins in BOTH directions: the gauge cannot manufacture work, and it cannot deny it.
  it('pending work ⇒ "enriching" even while the backfill gauge says "idle"', () => {
    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingDocCount: 100,
          embeddingPendingCount: 40,
          embeddingEnabled: true,
        },
      }),
      true,
    );
    expect(p.phase).toBe('enriching');
    expect(p.enrichingPending).toBe(40);
  });

  // A denominator-less pending counter is still evidence (813 §17) — that arm is unchanged by §20a.
  it('a denominator-less pending counter still withholds "ready" with the gauge idle', () => {
    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: { backfillMode: 'idle', chunk: { chunkEmbeddingPendingCount: 1554 } },
      }),
      true,
    );
    expect(p.phase).toBe('enriching');
    expect(p.enrichingPercent).toBeNull();
  });

  it('everything settled ⇒ "ready"', () => {
    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingDocCount: 100,
          embeddingPendingCount: 0,
          embeddingEnabled: true,
          chunk: { chunkDocCount: 400, chunkEmbeddingPendingCount: 0 },
        },
      }),
      true,
    );
    expect(p.phase).toBe('ready');
    expect(p.enrichingPercent).toBe(100);
    expect(p.enrichingPending).toBe(0);
  });

  it('the indexing arm wins over enrichment even while the backfill mode is non-idle', () => {
    const p = select(
      snapshot({
        core: { indexState: 'INDEXING', pendingJobs: 7 },
        enrichment: { backfillMode: 'individual', embeddingDocCount: 10, embeddingPendingCount: 10 },
      }),
      true,
    );
    expect(p.phase).toBe('indexing');
  });
});

describe('selectIndexingProgress — the hard-zeroed / unreachable snapshot must not read as "ready"', () => {
  it('no snapshot at all ⇒ "unknown", no numbers', () => {
    const p = select(null, false);
    expect(p.phase).toBe('unknown');
    expect(p.jobsPending).toBe(0);
    expect(p.enrichingPercent).toBeNull();
  });

  it.each(['UNAVAILABLE', 'NOT_STARTED', 'PENDING', 'OFFLINE', 'RECOVERING', 'DEGRADED', ''])(
    'the WorkerOperationalView.fallback("%s") zeroed block ⇒ "unknown", NOT "ready"',
    (state) => {
      // `CoreIndexView.fallback` zeroes every count; only `indexState` distinguishes it from a real
      // settled worker. Reading those zeros as "ready" is the "0 == done" lie.
      const p = select(
        snapshot({ core: { indexState: state, pendingJobs: 0, indexHealthy: false } }),
        true,
      );
      expect(p.phase).toBe('unknown');
      expect(p.enrichingPercent).toBeNull();
    },
  );

  it('a worker block missing entirely ⇒ "unknown"', () => {
    expect(select({} as StatusResponse, true).phase).toBe('unknown');
  });

  it('liveness is threaded through on every arm (807 A.3), never re-derived here', () => {
    expect(select(null, true).live).toBe(true);
    expect(select(snapshot({}), false).live).toBe(false);
    expect(select(snapshot({}), true).live).toBe(true);
  });
});

describe('selectIndexingProgress — denominator honesty', () => {
  it('no enrichable documents ⇒ no percent (null), never NaN and never a fabricated 0%', () => {
    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingDocCount: 0,
          embeddingPendingCount: 0,
          spladeDocCount: 0,
          chunk: { chunkDocCount: 0, chunkEmbeddingPendingCount: 0 },
        },
      }),
      true,
    );
    expect(p.enrichingPercent).toBeNull();
    expect(p.phase).toBe('ready');
  });

  it('a disabled stage is not-applicable — it leaves the denominator alone', () => {
    const withSplade = select(
      snapshot({
        enrichment: {
          embeddingDocCount: 100,
          embeddingPendingCount: 0,
          embeddingEnabled: true,
          spladeDocCount: 100,
          spladePendingCount: 100,
          spladeEnabled: true,
        },
      }),
      true,
    );
    expect(withSplade.enrichingPercent).toBe(50);

    const spladeOff = select(
      snapshot({
        enrichment: {
          embeddingDocCount: 100,
          embeddingPendingCount: 0,
          embeddingEnabled: true,
          spladeDocCount: 100,
          spladePendingCount: 100,
          spladeEnabled: false,
        },
      }),
      true,
    );
    // With SPLADE off the deployment is fully enriched, not stuck at 50% forever.
    expect(spladeOff.enrichingPercent).toBe(100);
    expect(spladeOff.phase).toBe('ready');
  });

  it('the percent is settled-over-total across parent stages AND the chunk tier', () => {
    const p = select(
      snapshot({
        enrichment: {
          embeddingDocCount: 100,
          embeddingPendingCount: 50,
          embeddingEnabled: true,
          chunk: { chunkDocCount: 300, chunkEmbeddingPendingCount: 150 },
        },
      }),
      true,
    );
    // 200 settled of 400 total — NOT the average of the two stage percentages.
    expect(p.enrichingPercent).toBe(50);
    expect(p.enrichingPending).toBe(200);
  });

  it('the percent is derived from counts, not from the pre-baked coverage percents', () => {
    const p = select(
      snapshot({
        enrichment: {
          embeddingDocCount: 100,
          embeddingPendingCount: 25,
          embeddingEnabled: true,
          embeddingCoveragePercent: 3,
          chunk: {
            chunkDocCount: 100,
            chunkEmbeddingPendingCount: 25,
            chunkVectorCoveragePercent: 3,
          },
        },
      }),
      true,
    );
    expect(p.enrichingPercent).toBe(75);
  });

  it('NER counts as a stage on its own two-valued census (it reports no doc count)', () => {
    const p = select(
      snapshot({
        enrichment: { completedNerCount: 30, pendingNerCount: 10, nerEnabled: true },
      }),
      true,
    );
    expect(p.enrichingPercent).toBe(75);
    expect(p.phase).toBe('enriching');
  });

  it('S33.5 an undrained stage with pending above its total has no percentage', () => {
    const p = select(
      snapshot({
        enrichment: { embeddingDocCount: 10, embeddingPendingCount: 999, embeddingEnabled: true },
      }),
      true,
    );
    expect(p.enrichingPercent).toBeNull();
  });

  it('the CHUNK tier is gated on the embedding stage — same encoder, same applicability', () => {
    // Embedding off (no embedding service, or switched off): chunk vectors come from that same
    // encoder, so their pending count NEVER settles. Counting them into the BLEND left the
    // deployment permanently `enriching` — "Up to date" / "System idle" / "fully searchable"
    // unreachable forever, with a percent frozen at a number that would never move.
    //
    // Round-15 F1 REVISED the phase this case yields, not the exclusion it is about: the blend and
    // the percent still exclude the stage (asserted below, unchanged), but zero REACHABLE work is
    // not completion when the reason it is zero is that the stage does not exist. `blocked` keeps
    // everything the original expectation was protecting — no frozen percent, no ETA, no permanent
    // "still improving" — while refusing the completion claim the old `ready` licensed.
    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingEnabled: false,
          embeddingDocCount: 100,
          embeddingPendingCount: 100,
          chunk: { chunkDocCount: 400, chunkEmbeddingPendingCount: 400 },
        },
      }),
      true,
    );
    expect(p.phase).toBe('blocked');
    expect(p.enrichingPending).toBe(0);
    expect(p.enrichingPercent).toBeNull();
    expect(p.enrichingEtaSeconds).toBeNull();
    expect(p.enrichingStages).toEqual([]);
    // The evidence the phase was decided on: the parent stage's 100 + the chunk tier's 400.
    expect(p.blockedPending).toBe(500);
  });

  it('pending work with no denominator still withholds the terminal phase (positive evidence)', () => {
    // #375's enrichmentCoverage doctrine, kept through the 813 merge (§17): a pending counter on an
    // APPLICABLE stage is evidence of unfinished work even when its denominator is absent — the
    // PERCENT is suppressed (no faithful denominator), but the phase must not read 'ready' and let
    // the queue card claim "Up to date" over work it can see.
    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          chunk: { chunkEmbeddingPendingCount: 1554 }, // no chunkDocCount
        },
      }),
      true,
    );
    expect(p.phase).toBe('enriching');
    expect(p.enrichingPending).toBe(1554);
    expect(p.enrichingPercent).toBeNull();
  });

  it('applicability is NULL when the worker did not report — never an all-false claim', () => {
    // All-false says "no stage applies here", which a per-root consumer reads as a licence to
    // score coverage on whatever counts survive. Nothing observed must stay nothing claimed.
    expect(select(null, false).stages).toBeNull();
    expect(select({} as StatusResponse, true).stages).toBeNull();
    expect(
      select(snapshot({ core: { indexState: 'UNAVAILABLE' } }), true).stages,
    ).toBeNull();
    // A REPORTING worker does answer the question.
    expect(select(snapshot({}), true).stages).toEqual({
      embedding: true,
      splade: true,
      ner: true,
    });
  });

  it('exposes the per-subject pending counts the overlay renders, from the same snapshot', () => {
    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0, pendingVduCount: 5 },
        enrichment: { embeddingDocCount: 100, embeddingPendingCount: 3, embeddingEnabled: true },
      }),
      true,
    );
    expect(p.embeddingPending).toBe(3);
    expect(p.vduPending).toBe(5);
  });
});

/**
 * 813 §5b — the estimate is INDICATIVE and suppressed whenever there is no honest basis. Each arm
 * below is a way the estimate could have been fabricated; the projection must return `null`, so the
 * Tasks panel renders no line at all rather than a placeholder.
 */
describe('selectIndexingProgress — indicative estimate (813 §5b)', () => {
  const indexing = (pendingJobs: number, recentDocsPerSec: number[]) =>
    snapshot({ core: { indexState: 'INDEXING', pendingJobs, recentDocsPerSec } });

  it('extrapolates the backlog over the MEDIAN of the trailing rate window', () => {
    // median(10, 4, 4) = 4 ⇒ 400 / 4 = 100s. The mean (6) would have said 67s — one burst of tiny
    // files must not halve the estimate.
    const p = select(indexing(400, [1, 2, 10, 4, 4]), true);
    expect(p.etaSeconds).toBe(100);
  });

  it('suppresses the estimate when a trailing sample is zero (the producer’s "unknown window")', () => {
    // `recentDocsPerSec` reports 0.0 for windows it could not measure (WorkerOpsMetricCatalog's
    // INDEX_DOCS_PER_SEC contract) — a zero in the window means the rate is not established.
    expect(select(indexing(400, [4, 0, 4]), true).etaSeconds).toBeNull();
  });

  it('suppresses the estimate when the trend is too short to be a window', () => {
    expect(select(indexing(400, [4, 4]), true).etaSeconds).toBeNull();
    expect(select(indexing(400, []), true).etaSeconds).toBeNull();
  });

  it('suppresses the estimate on a backlog too small to be worth extrapolating', () => {
    expect(select(indexing(5, [4, 4, 4]), true).etaSeconds).toBeNull();
  });

  it('suppresses an implausibly distant estimate rather than rendering it', () => {
    // 0.01 docs/s over 400 jobs = ~11 hours; a three-sample window cannot support that claim.
    expect(select(indexing(400, [0.01, 0.01, 0.01]), true).etaSeconds).toBeNull();
  });

  it('never estimates during enrichment (§1e: ingest preemption makes throughput unstable)', () => {
    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0, recentDocsPerSec: [4, 4, 4] },
        enrichment: { embeddingDocCount: 1000, embeddingPendingCount: 400, embeddingEnabled: true },
      }),
      true,
    );
    expect(p.phase).toBe('enriching');
    expect(p.etaSeconds).toBeNull();
  });

  it('never estimates from a stale snapshot (a past rate is not a present one)', () => {
    expect(select(indexing(400, [4, 4, 4]), false).etaSeconds).toBeNull();
  });

  // The estimate counted UP while a walk was still enqueueing: "remaining / rate" divides by a
  // denominator that is still rising. The last two queue-depth samples of the SAME snapshot say
  // whether it is.
  it('suppresses the estimate while the backlog is still GROWING (a walk is enqueueing)', () => {
    const p = select(
      snapshot({
        core: {
          indexState: 'INDEXING',
          pendingJobs: 400,
          recentDocsPerSec: [4, 4, 4],
          recentJobQueueDepth: [100, 250, 400],
        },
      }),
      true,
    );
    expect(p.phase).toBe('indexing');
    expect(p.etaSeconds).toBeNull();
  });

  it('estimates once the backlog is draining (the same snapshot shape, depth falling)', () => {
    const p = select(
      snapshot({
        core: {
          indexState: 'INDEXING',
          pendingJobs: 400,
          recentDocsPerSec: [4, 4, 4],
          recentJobQueueDepth: [900, 650, 400],
        },
      }),
      true,
    );
    expect(p.etaSeconds).toBe(100);
  });
});

/**
 * 813 Slice B — the byte weight of the remaining backlog. Same denominator discipline as the
 * percent: shown only when it is faithful, withdrawn (never 0) when it is not.
 */
describe('selectIndexingProgress — remaining byte weight', () => {
  const withBytes = (over: Record<string, unknown>) =>
    select(
      snapshot({
        core: { indexState: 'INDEXING', pendingJobs: 100, ...over },
      }),
      true,
    );

  it('reports the recorded weight of the remaining jobs', () => {
    expect(withBytes({ pendingBytes: 5_000_000, pendingUnknownSizeJobs: 4 }).pendingBytes).toBe(
      5_000_000,
    );
  });

  it('withdraws it when most of the backlog has no recorded size', () => {
    // 60 of 100 remaining jobs carry no size: the sum is not a weight of the backlog any more.
    expect(withBytes({ pendingBytes: 5_000_000, pendingUnknownSizeJobs: 60 }).pendingBytes).toBeNull();
  });

  it('withdraws it when there is no backlog to weigh', () => {
    const p = select(
      snapshot({
        core: {
          indexState: 'IDLE',
          pendingJobs: 0,
          pendingBytes: 5_000_000,
          pendingUnknownSizeJobs: 0,
        },
      }),
      true,
    );
    expect(p.pendingBytes).toBeNull();
  });

  it('withdraws it when nothing is recorded, and on the worker UNAVAILABLE marker', () => {
    expect(withBytes({ pendingBytes: 0, pendingUnknownSizeJobs: 0 }).pendingBytes).toBeNull();
    // JobQueue.PendingBytes.UNAVAILABLE: zero bytes plus the -1 marker, never "0 B remaining".
    expect(withBytes({ pendingBytes: 0, pendingUnknownSizeJobs: -1 }).pendingBytes).toBeNull();
  });
});

/**
 * 813 §19 (W2) — the determinate indexing position, measured against the drain episode's observed
 * high-water backlog. The denominator is the STORE's cross-poll memory, threaded in as a required
 * parameter; the selector stays a pure function of (snapshot, liveness, that number).
 */
describe('selectIndexingProgress — determinate indexing percent (813 §19 W2)', () => {
  const indexingWith = (pendingJobs: number, episodeMax: number) =>
    select(snapshot({ core: { indexState: 'INDEXING', pendingJobs } }), true, episodeMax);

  it('computes the drained fraction once a drain has been observed', () => {
    // 1,600 was the episode's peak; 400 remain ⟹ three quarters of the observed work is done.
    expect(indexingWith(400, 1600).indexingPercent).toBe(75);
    expect(indexingWith(1200, 1600).indexingPercent).toBe(25);
  });

  it('is null when the high-water mark EQUALS the backlog — no drain observed, no denominator', () => {
    // The first poll of an episode: max and pending are the same number, so nothing has been
    // witnessed draining and any percent would be a fabricated 0.
    expect(indexingWith(1600, 1600).indexingPercent).toBeNull();
  });

  it('is null while the backlog is still GROWING past the remembered maximum', () => {
    // A stale/lagging max below the current backlog cannot bound it — the fraction would exceed 100.
    expect(indexingWith(1600, 400).indexingPercent).toBeNull();
  });

  it('is null with no memory at all, and never NaN', () => {
    const p = indexingWith(400, 0);
    expect(p.indexingPercent).toBeNull();
    expect(Number.isNaN(p.indexingPercent as unknown as number)).toBe(false);
  });

  it('keeps a MEASURED 0 — a witnessed drain rounding below half a percent is honest', () => {
    // 1 of 1,000 drained ⟹ 0.1% ⟹ rounds to 0. `episodeMax > jobsPending` is strictly true, so this
    // is "barely started", not the missing-denominator 0 the null arm exists to prevent.
    expect(indexingWith(999, 1000).indexingPercent).toBe(0);
  });

  it('is null in every phase other than `indexing`, whatever the remembered maximum says', () => {
    // Enriching: the honest fraction there is `enrichingPercent`, off the coverage counters.
    const enriching = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: { embeddingDocCount: 100, embeddingPendingCount: 40, embeddingEnabled: true },
      }),
      true,
      1600,
    );
    expect(enriching.phase).toBe('enriching');
    expect(enriching.indexingPercent).toBeNull();
    // Ready, and the hard-zeroed `unknown` arm, likewise assert nothing.
    expect(select(snapshot({ core: { indexState: 'IDLE', pendingJobs: 0 } }), true, 1600).indexingPercent).toBeNull();
    expect(select(snapshot({ core: { indexState: 'UNAVAILABLE' } }), true, 1600).indexingPercent).toBeNull();
    expect(select(null, false, 1600).indexingPercent).toBeNull();
  });
});

/**
 * 813 §20 — the enriching percent's FLOOR. The owner found a live card reading "100% · semantic
 * search catching up": a full bar contradicting its own caveat, because `Math.round` promotes a
 * sub-half-percent tail to 100. A percent that says "finished" beside a phase that says "still
 * working" is the §1d false terminal wearing a number.
 */
describe('selectIndexingProgress — the enriching percent may not fake a 100 (813 §20)', () => {
  it('caps at 99 while ANY counted work is pending, where Math.round alone would say 100', () => {
    // Right-reason guard: the unrounded fraction really is above 99.5, so a passing 99 can only come
    // from the floor rule — not from a fixture that was never near 100 in the first place.
    expect(Math.round(((600 - 2) / 600) * 100)).toBe(100);

    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingDocCount: 600,
          embeddingPendingCount: 2,
          embeddingEnabled: true,
        },
      }),
      true,
    );
    expect(p.phase).toBe('enriching');
    expect(p.enrichingPercent).toBe(99);
  });

  it('caps at 99 for a single pending document out of a very large corpus', () => {
    const p = select(
      snapshot({
        enrichment: {
          embeddingDocCount: 250_000,
          embeddingPendingCount: 1,
          embeddingEnabled: true,
        },
      }),
      true,
    );
    expect(p.enrichingPercent).toBe(99);
  });

  it('reaches a true 100 the moment nothing is pending', () => {
    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingDocCount: 600,
          embeddingPendingCount: 0,
          embeddingEnabled: true,
        },
      }),
      true,
    );
    expect(p.enrichingPercent).toBe(100);
    // The cap is about the DISPLAY of unfinished work, not about withholding completion: with the
    // pending bucket empty the phase itself has moved on.
    expect(p.phase).toBe('ready');
  });

  it('leaves the no-denominator arm alone — no total ⇒ null, not 99 and not 100', () => {
    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: { backfillMode: 'idle' },
      }),
      true,
    );
    expect(p.enrichingPercent).toBeNull();
  });
});

/**
 * 813 §20 — the per-stage breakdown is a PROJECTION of the blend: the rows the disclosure lists are
 * the very rows the surface percent sums, so the two can differ by scope but never by derivation.
 */
describe('selectIndexingProgress — enrichingStages (813 §20)', () => {
  const fourStages = snapshot({
    enrichment: {
      embeddingDocCount: 400,
      embeddingPendingCount: 100,
      embeddingEnabled: true,
      spladeDocCount: 400,
      spladePendingCount: 200,
      spladeEnabled: true,
      completedNerCount: 30,
      pendingNerCount: 10,
      nerEnabled: true,
      chunk: { chunkDocCount: 1000, chunkEmbeddingPendingCount: 500 },
    },
  });

  it('carries one row per blend input, with the wire fixture values verbatim', () => {
    const p = select(fourStages, true);
    expect(p.enrichingStages).toEqual([
      { id: 'embedding', total: 400, pending: 100 },
      { id: 'splade', total: 400, pending: 200 },
      { id: 'ner', total: 40, pending: 10 },
      { id: 'chunkVectors', total: 1000, pending: 500 },
    ]);
  });

  it('the rows ADD UP to the percent — same numbers, narrower scope', () => {
    const p = select(fourStages, true);
    const total = p.enrichingStages.reduce((n, r) => n + r.total, 0);
    const pending = p.enrichingStages.reduce((n, r) => n + r.pending, 0);
    expect(total).toBe(1840);
    expect(pending).toBe(810);
    expect(p.enrichingPercent).toBe(Math.round(((total - pending) / total) * 100));
  });

  it('a disabled stage produces NO row and no blend contribution — one exclusion, not two rules', () => {
    const spladeOff = select(
      snapshot({
        enrichment: {
          embeddingDocCount: 100,
          embeddingPendingCount: 0,
          embeddingEnabled: true,
          spladeDocCount: 100,
          spladePendingCount: 100,
          spladeEnabled: false,
        },
      }),
      true,
    );
    expect(spladeOff.enrichingStages.map((r) => r.id)).toEqual(['embedding']);
    expect(spladeOff.enrichingPercent).toBe(100);
  });

  it('a stage with nothing to enrich has no row (no 0/0 row to render)', () => {
    const p = select(
      snapshot({
        enrichment: { embeddingDocCount: 100, embeddingPendingCount: 25, embeddingEnabled: true },
      }),
      true,
    );
    expect(p.enrichingStages.map((r) => r.id)).toEqual(['embedding']);
  });

  it('the chunk row rides the EMBEDDING stage applicability, not its own flag', () => {
    const embeddingOff = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingEnabled: false,
          embeddingDocCount: 100,
          embeddingPendingCount: 100,
          chunk: { chunkDocCount: 400, chunkEmbeddingPendingCount: 400 },
        },
      }),
      true,
    );
    // Chunk vectors come from the same encoder: embedding off ⟹ neither row exists.
    expect(embeddingOff.enrichingStages).toEqual([]);

    const embeddingOn = select(
      snapshot({
        enrichment: {
          embeddingEnabled: true,
          embeddingDocCount: 100,
          embeddingPendingCount: 100,
          chunk: { chunkDocCount: 400, chunkEmbeddingPendingCount: 400 },
        },
      }),
      true,
    );
    expect(embeddingOn.enrichingStages.map((r) => r.id)).toEqual(['embedding', 'chunkVectors']);
  });

  it('is empty on the unknown arm — a worker that did not report has no stages to list', () => {
    expect(select(null, false).enrichingStages).toEqual([]);
    expect(select(snapshot({ core: { indexState: 'UNAVAILABLE' } }), true).enrichingStages).toEqual(
      [],
    );
  });

  it('enrichSettledSum is the SAME settled sum the percent divides (store/selector cannot fork)', () => {
    const p = select(fourStages, true);
    const fromRows = p.enrichingStages.reduce((n, r) => n + (r.total - r.pending), 0);
    expect(enrichSettledSum(fourStages)).toBe(fromRows);
    expect(enrichSettledSum(fourStages)).toBe(1030);
  });

  it('selectIndexingPhase answers exactly what the selector answers (one phase authority)', () => {
    for (const s of [
      fourStages,
      snapshot({ core: { indexState: 'INDEXING', pendingJobs: 12 } }),
      snapshot({ core: { indexState: 'IDLE', pendingJobs: 0 } }),
      snapshot({ core: { indexState: 'UNAVAILABLE' } }),
    ]) {
      expect(selectIndexingPhase(s)).toBe(select(s, true).phase);
    }
    expect(selectIndexingPhase(null)).toBe('unknown');
  });
});

/**
 * 813 §20 — the enrichment estimate. Its rate comes from the store's OWN cross-poll settle trail,
 * never from `core.recentDocsPerSec` (which gauges the indexing pipeline). Each arm below is a way
 * the estimate could have been fabricated; the projection must return `null` so the card renders no
 * segment at all.
 */
describe('selectIndexingProgress — enrichment estimate (813 §20)', () => {
  /** 300 of 1,000 documents still pending on one applicable stage ⟹ blend pending = 300. */
  const enriching = snapshot({
    core: { indexState: 'IDLE', pendingJobs: 0 },
    enrichment: {
      backfillMode: 'running',
      embeddingDocCount: 1000,
      embeddingPendingCount: 300,
      embeddingEnabled: true,
    },
  });

  /** A settle trail: `settledDeltas` documents settled per 10 s poll interval. */
  const trail = (settledDeltas: readonly number[]): EnrichSettleSample[] => {
    const out: EnrichSettleSample[] = [{ t: 0, settled: 700 }];
    settledDeltas.forEach((d, i) => {
      out.push({ t: (i + 1) * 10_000, settled: out[out.length - 1]!.settled + d });
    });
    return out;
  };

  it('extrapolates the blend backlog over the observed settle rate', () => {
    // 100 documents per 10 s ⟹ 10/s; 300 pending ⟹ 30 s.
    const p = select(enriching, true, 0, trail([100, 100, 100]));
    expect(p.phase).toBe('enriching');
    expect(p.enrichingEtaSeconds).toBe(30);
  });

  it('uses the MEDIAN interval rate — one fast poll cannot halve the estimate', () => {
    // Rates 10/s, 10/s, 100/s. Median 10 ⟹ 30 s. The MEAN (40/s) would have said 8 s.
    const p = select(enriching, true, 0, trail([100, 100, 1000]));
    expect(p.enrichingEtaSeconds).toBe(30);
  });

  it('is null with too few intervals to establish a rate', () => {
    // Three samples ⟹ two intervals; the rule needs three.
    expect(select(enriching, true, 0, trail([100, 100])).enrichingEtaSeconds).toBeNull();
    expect(select(enriching, true, 0, []).enrichingEtaSeconds).toBeNull();
  });

  it('is null when an interval settled NOTHING — a paused backfill is not a slow one', () => {
    expect(select(enriching, true, 0, trail([100, 0, 100])).enrichingEtaSeconds).toBeNull();
  });

  it('is null when the settled sum went BACKWARDS (ingest moved the denominator)', () => {
    expect(select(enriching, true, 0, trail([100, -50, 100])).enrichingEtaSeconds).toBeNull();
  });

  it('is null when two samples share an instant — no elapsed time, no rate', () => {
    const samples: EnrichSettleSample[] = [
      { t: 1000, settled: 700 },
      { t: 1000, settled: 800 },
      { t: 2000, settled: 900 },
      { t: 3000, settled: 1000 },
    ];
    expect(select(enriching, true, 0, samples).enrichingEtaSeconds).toBeNull();
  });

  it('is null beyond the one-hour cap a six-sample trail cannot support', () => {
    const huge = snapshot({
      core: { indexState: 'IDLE', pendingJobs: 0 },
      enrichment: {
        backfillMode: 'running',
        embeddingDocCount: 1_000_000,
        embeddingPendingCount: 500_000,
        embeddingEnabled: true,
      },
    });
    // 100/s over 500,000 pending ⟹ 5,000 s > ETA_MAX_SECONDS.
    const samples: EnrichSettleSample[] = [0, 1, 2, 3].map((i) => ({
      t: i * 10_000,
      settled: 500_000 + i * 1000,
    }));
    expect(select(huge, true, 0, samples).enrichingEtaSeconds).toBeNull();
  });

  it('is null on a stale snapshot — a past rate is not a present one', () => {
    expect(select(enriching, false, 0, trail([100, 100, 100])).enrichingEtaSeconds).toBeNull();
  });

  it('is null in every phase other than `enriching`, whatever the trail says', () => {
    const indexing = snapshot({ core: { indexState: 'INDEXING', pendingJobs: 400 } });
    expect(select(indexing, true, 0, trail([100, 100, 100])).enrichingEtaSeconds).toBeNull();
    const ready = snapshot({ core: { indexState: 'IDLE', pendingJobs: 0 } });
    expect(select(ready, true, 0, trail([100, 100, 100])).enrichingEtaSeconds).toBeNull();
    expect(select(null, true, 0, trail([100, 100, 100])).enrichingEtaSeconds).toBeNull();
  });

  it('does NOT reuse the indexing rate gauge — a rich recentDocsPerSec buys no enrichment estimate', () => {
    // The two questions have two measurements: `core.recentDocsPerSec` describes the job pipeline.
    const withIndexRate = snapshot({
      core: { indexState: 'IDLE', pendingJobs: 0, recentDocsPerSec: [20, 20, 20, 20] },
      enrichment: {
        backfillMode: 'running',
        embeddingDocCount: 1000,
        embeddingPendingCount: 300,
        embeddingEnabled: true,
      },
    });
    expect(select(withIndexRate, true, 0, []).enrichingEtaSeconds).toBeNull();
  });
});

/**
 * Round-15 F1/F1b (sandbox validation of 0.2.0, 2026-08-07) — the `unreachable-seed-green` case.
 *
 * The build under test rendered "Ready — fully searchable / Everything is indexed and enriched" over
 * an index with ZERO enriched documents, and the Library row a bare green "Verified just now",
 * because every enrichment stage was inapplicable (no embedding service ⟹
 * `KnowledgeServer.java:1216` wires `embedding/splade/ner = false`) and the applicability filter
 * discounted their pending counters — leaving no evidence of outstanding work anywhere in the
 * projection, which `derivePhase` then read as `ready`.
 *
 * The fixture below is the enrichment block of the round's own captured `/api/status`
 * (`evidence/api-history/20260807-011454/api-api-status.json`), verbatim — including the three
 * `*Enabled: false` flags that are the mechanism. The headline F1 moment
 * (`evidence/api-knowledge-status-during-install.json`: 5,189 docs, `NO_EMBEDDING_MODEL`,
 * `embeddingCoveragePercent 0.0`, `pendingNerCount 5189`) is the same shape at a larger scale, and
 * this derivation is scale-free.
 */
describe('selectIndexingProgress — round-15 F1: coverage 0 + no model + empty queue', () => {
  /** The captured enrichment block, verbatim. */
  const F1_ENRICHMENT = {
    backfillMode: 'idle',
    embeddingEnabled: false,
    spladeEnabled: false,
    nerEnabled: false,
    embeddingDocCount: 5,
    embeddingPendingCount: 5,
    embeddingCoveragePercent: 0,
    spladeDocCount: 5,
    spladePendingCount: 5,
    spladeCoveragePercent: 0,
    pendingNerCount: 5,
    completedNerCount: 0,
    chunk: { chunkDocCount: 2, chunkEmbeddingPendingCount: 2, chunkVectorsReady: false },
  } as const;

  const f1Snapshot = (): StatusResponse =>
    snapshot({ core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: { ...F1_ENRICHMENT } });

  const f1 = (): ReturnType<typeof selectIndexingProgress> => select(f1Snapshot(), true);

  it('THE defect: nothing enriched and nothing queued does NOT read as the terminal phase', () => {
    expect(f1().phase).not.toBe('ready');
    expect(f1().phase).toBe('blocked');
  });

  it('right-reason guard: the queue really is empty and every stage really is excluded', () => {
    const p = f1();
    // Without these, the case above could pass for a reason that is not the defect.
    expect(p.jobsPending).toBe(0);
    expect(p.enrichingPending).toBe(0);
    expect(p.enrichingStages).toEqual([]);
    expect(p.stages).toEqual({ embedding: false, splade: false, ner: false });
    // ...and the evidence deciding `blocked` is the SEMANTIC backlog: 5 parent docs + 2 chunk docs.
    expect(p.blockedPending).toBe(7);
  });

  it('claims no number it cannot support — no percent, no estimate, no fabricated 0%', () => {
    const p = f1();
    expect(p.enrichingPercent).toBeNull();
    expect(p.enrichingEtaSeconds).toBeNull();
    expect(p.indexingPercent).toBeNull();
  });

  it('the phase-only selector agrees (one derivation, two entry points)', () => {
    expect(selectIndexingPhase(f1Snapshot())).toBe('blocked');
  });

  // F1-repro's FIRST attempt, which did NOT reproduce — recorded by the round as informative and
  // pinned here as the boundary: model-absent alone is not the trigger. Vectors already persisted in
  // the index survive the model's removal, so nothing is outstanding and completion is TRUE.
  it('BOUNDARY: coverage complete with the model since removed still reads complete', () => {
    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingEnabled: false,
          spladeEnabled: false,
          nerEnabled: false,
          embeddingDocCount: 5191,
          embeddingPendingCount: 0,
          embeddingCoveragePercent: 100,
          spladeDocCount: 5191,
          spladePendingCount: 0,
          completedNerCount: 5191,
          pendingNerCount: 0,
          chunk: { chunkDocCount: 1557, chunkEmbeddingPendingCount: 0, chunkVectorsReady: true },
        },
      }),
      true,
    );
    expect(p.phase).toBe('ready');
    expect(p.blockedPending).toBe(0);
  });

  // The applicability filter's original subject (813 review F1) is untouched: a deployment running
  // with SPLADE switched off is not "blocked" — only the SEMANTIC stage's absence is, because only
  // it is what the completion claim and the words "semantic search" rest on.
  it('BOUNDARY: SPLADE off with embedding running is ordinary completion, not blocked', () => {
    const p = select(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingEnabled: true,
          embeddingDocCount: 100,
          embeddingPendingCount: 0,
          spladeEnabled: false,
          spladeDocCount: 100,
          spladePendingCount: 100,
        },
      }),
      true,
    );
    expect(p.phase).toBe('ready');
    expect(p.enrichingPercent).toBe(100);
    expect(p.blockedPending).toBe(0);
  });

  // Precedence: a running job queue is still the headline — `blocked` describes what happens AFTER
  // the drain, so it must not pre-empt the countdown the user is watching.
  it('an active job queue outranks the blocked arm', () => {
    const p = select(
      snapshot({
        core: { indexState: 'INDEXING', pendingJobs: 42 },
        enrichment: { ...F1_ENRICHMENT },
      }),
      true,
    );
    expect(p.phase).toBe('indexing');
  });
});

describe('enrichment status truthfulness', () => {
  it.each(['embeddingPendingCount', 'spladePendingCount', 'pendingNerCount'] as const)(
    'keeps unreadable %s unknown through both selectors', field => {
      const status = snapshot({ enrichment: { [field]: -1 } });
      expect(selectIndexingPhase(status)).toBe('unknown');
      const progress = select(status, true);
      expect(progress.activity.summary).not.toContain('fully searchable');
      expect(progress.enrichingPercent).toBeNull();
      expect(progress.enrichingEtaSeconds).toBeNull();
    });

  it.each(['embeddingFailedCount', 'spladeFailedCount', 'failedNerCount'] as const)(
    'withholds completion for terminal %s', field => {
      const status = snapshot({ core: { indexHealthy: true }, enrichment: { [field]: 1 } });
      expect(selectIndexingPhase(status)).toBe('error');
      expect(select(status, true).activity.summary).not.toContain('fully searchable');
    });

  it('includes enabled chunk SPLADE independently of embedding and its status-less siblings', () => {
    const status = snapshot({ enrichment: {
      embeddingEnabled: false, spladePendingCount: 0,
      chunk: { chunkDocCount: 200, chunkSpladeEnabled: true,
        chunkSpladeCompletedCount: 3, chunkSpladePendingCount: 1 },
    } } as Parameters<typeof snapshot>[0]);
    const progress = select(status, true);
    expect(progress.phase).toBe('enriching');
    expect(progress.enrichingStages).toEqual([{ id: 'chunkSplade', total: 4, pending: 1 }]);
    expect(progress.enrichingPercent).toBe(75);
    expect(enrichSettledSum(status)).toBe(3);
    status.worker!.enrichment!.chunk!.chunkSpladePendingCount = 0;
    expect(selectIndexingPhase(status)).toBe('ready');
  });

  it.each([false, true])('blocks unavailable embedding with chunkOnly=%s', chunkOnly => {
    const status = snapshot({ enrichment: { embeddingEnabled: true, embeddingAvailable: false,
      embeddingPendingCount: chunkOnly ? 0 : 1,
      chunk: { chunkEmbeddingPendingCount: chunkOnly ? 1 : 0 },
      spladePendingCount: 5,
    } } as Parameters<typeof snapshot>[0]);
    const progress = select(status, true);
    expect(progress.phase).toBe('blocked');
    expect(progress.activity.summary).toBe('Semantic search unavailable');
    expect(progress.enrichingEtaSeconds).toBeNull();
    status.worker!.enrichment!.embeddingAvailable = true;
    expect(selectIndexingPhase(status)).toBe('enriching');
  });

  it('does not round one missing artifact into a completion claim', () => {
    const status = snapshot({ enrichment: {
      embeddingDocCount: 10000, embeddingCompletedCount: 10000, embeddingCoveragePercent: 99.99,
      completeness: [{ stageId: 'embed', tier: 'ARTIFACT', expected: 10000, present: 9999, missing: 1, failed: 0 }],
    } });
    expect(selectIndexingPhase(status)).toBe('error');
    expect(select(status, true).enrichingPercent).toBeNull();
  });
});


describe('independent count failures and sparse population', () => {
  it('withholds completion when only the completeness audit is unreadable', () => {
    const status = snapshot({ enrichment: { completenessKnown: false } });
    expect(selectIndexingPhase(status)).toBe('unknown');
    expect(select(status, true).activity.summary).not.toContain('fully searchable');
  });

  it.each(['chunkEmbeddingPendingCount', 'chunkVectorCoveragePercent', 'chunkDocCount'] as const)(
    'keeps independently unreadable %s unknown', field => {
      const status = snapshot({ enrichment: { chunk: { [field]: -1 } } });
      expect(selectIndexingPhase(status)).toBe('unknown');
    });

  it('shows chunk SPLADE failures over its own population', () => {
    const status = snapshot({ enrichment: { embeddingEnabled: false,
      chunk: { chunkDocCount: 500, chunkSpladeEnabled: true, chunkSpladeDocCount: 4,
        chunkSpladeCompletedCount: 3, chunkSpladePendingCount: 0, chunkSpladeFailedCount: 1 },
    } });
    const progress = select(status, true);
    expect(progress.phase).toBe('error');
    expect(progress.enrichingStages).toEqual([{ id: 'chunkSplade', total: 4, pending: 0, failed: 1 }]);
    expect(progress.enrichingPercent).toBeNull();
  });
});


describe('live enrichment producer availability', () => {
  it.each(['splade', 'ner', 'chunkSplade'] as const)('blocks unavailable enabled %s work', stage => {
    const status = snapshot({ enrichment: {
      embeddingEnabled: true, embeddingAvailable: true,
      spladeEnabled: true, spladeAvailable: stage === 'ner', nerEnabled: true, nerAvailable: stage !== 'ner',
      spladeDocCount: 2, spladePendingCount: stage === 'splade' ? 1 : 0,
      pendingNerCount: stage === 'ner' ? 1 : 0,
      chunk: { chunkSpladeEnabled: stage === 'chunkSplade', chunkSpladeDocCount: 2,
        chunkSpladePendingCount: stage === 'chunkSplade' ? 1 : 0 },
    } });
    const progress = select(status, true);
    expect(progress.phase).toBe('blocked');
    expect(progress.activity.summary).toBe('Enrichment unavailable');
    expect(progress.enrichingEtaSeconds).toBeNull();
    status.worker!.enrichment!.spladeAvailable = true;
    status.worker!.enrichment!.nerAvailable = true;
    expect(selectIndexingPhase(status)).toBe('enriching');
  });
});


it('requires a zero-chunk declaration before certifying a nonempty library', () => {
  const status = snapshot({ core: { indexedDocuments: 1 }, enrichment: {
    chunk: { chunkDocCount: 0, chunkingNotRequired: false },
  } });
  expect(selectIndexingPhase(status)).toBe('error');
  expect(select(status, true).activity.summary).not.toContain('fully searchable');
  status.worker!.enrichment!.chunk!.chunkingNotRequired = true;
  expect(selectIndexingPhase(status)).toBe('ready');
});


it.each(['unknown', 'stale'] as const)('does not infer zero passage work from a %s parent count', state => {
  const status = snapshot({ core: { indexedDocuments: 0, indexedDocumentsUnknown: state === 'unknown' },
    enrichment: { chunk: { chunkDocCount: 0, chunkingNotRequired: false } },
  });
  status.worker!.countObservations = { active: { state, observedAtMs: state === 'unknown' ? 0 : 1234 } };
  expect(selectIndexingPhase(status)).toBe('unknown');
  expect(select(status, true).enrichingPercent).toBeNull();
  expect(select(status, true).activity.summary).not.toContain('fully searchable');
  status.worker!.enrichment!.chunk!.chunkingNotRequired = true;
  expect(selectIndexingPhase(status)).toBe('ready');
});
