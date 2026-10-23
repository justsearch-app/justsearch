// @vitest-environment happy-dom

/**
 * Tempdoc 578 Workstream A — SystemSelfView ("Now") is now embedded as Health's compact live-strip
 * (`variant="strip"`), the standalone RAIL surface having been retired. The strip must NOT emit its
 * own `<h2>Now</h2>` heading (Health owns the page heading), while the historical `'full'` variant
 * keeps it. Both variants still render the live body (idle state here, since no tasks are seeded).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SystemSelfView.js';
import { visibleIndexQueueCount, enrichingLabel, type SystemSelfView } from './SystemSelfView.js';
import type { IndexingProgress } from '../state/indexingProgress.js';
import {
  __feedForTest,
  __resetAiStateForTest,
  __tickClockForTest,
} from '../state/aiStateStore.js';

/** One projection snapshot — the ONE authority both strip rows now read (813 §3b). */
const progress = (over: Partial<IndexingProgress>): IndexingProgress => ({
  activity: { summary: 'Ready — fully searchable', detail: null, confirmed: true, enrichmentDeferred: false },
  phase: 'ready',
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
  rebuildPercent: null,
  pendingBytes: null,
  live: true,
  stages: { embedding: true, splade: true, ner: true },
  ...over,
});

async function mount(variant?: 'full' | 'strip'): Promise<SystemSelfView> {
  const el = document.createElement('jf-system-self-view') as SystemSelfView;
  if (variant) el.variant = variant;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('jf-system-self-view — strip variant (578 Workstream A)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ state: 'idle', updatedAtEpochMs: Date.now() }),
      }),
    );
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.unstubAllGlobals();
  });

  it("full variant (default) emits its own <h2>Now</h2> heading", async () => {
    const el = await mount();
    expect(el.shadowRoot!.querySelector('h2')?.textContent).toBe('Now');
  });

  it('strip variant emits NO heading (Health owns the page heading)', async () => {
    const el = await mount('strip');
    expect(el.shadowRoot!.querySelector('h2')).toBeNull();
  });

  it('S33.4 strip variant keeps the body and marks a missing observation unconfirmed', async () => {
    const el = await mount('strip');
    // Absence of a status observation cannot establish idleness.
    expect(el.shadowRoot!.querySelector('.body')).not.toBeNull();
    expect(el.shadowRoot!.textContent).toContain('Indexing status unconfirmed');
    expect(el.shadowRoot!.querySelector('[data-testid="self-view-idle"]')).toBeNull();
  });
});

describe('visibleIndexQueueCount', () => {
  it('counts the job queue from the ONE projection', () => {
    expect(visibleIndexQueueCount(progress({ phase: 'indexing', jobsPending: 3792 }))).toBe(3792);
  });

  it('does not invent activity from a drained queue', () => {
    expect(visibleIndexQueueCount(progress({ phase: 'ready' }))).toBeNull();
  });

  // Tempdoc 727 F-2: sandbox-round repro — worker.core.indexState=IDLE, pending/queue_depth/
  // processing_jobs_count all 0, yet the "Now" strip's INDEXING row showed "Processing 10 items /
  // running" (10 = embeddingPending(2) + embeddingQueueSize(3) + vduQueueSize(5), read from
  // /api/inference/status). The 813 remediation removes that second derivation entirely instead of
  // suppressing it: the row now counts jobs from the projection, so a cross-subsystem residue has
  // no way to reach this row at all — and enrichment work is described once, by `enrichingLabel`.
  it('never describes enrichment work — that belongs to the Enrichment row alone', () => {
    // The exact contradiction the collapse removes: an enrichment backlog with NO job rows must
    // produce no indexing row, whatever the worker's index state is.
    expect(
      visibleIndexQueueCount(
        progress({ phase: 'enriching', jobsPending: 0, embeddingPending: 2, vduPending: 5 }),
      ),
    ).toBeNull();
  });

  it('asserts nothing off an unreported or stale snapshot (807 A.3)', () => {
    expect(visibleIndexQueueCount(progress({ phase: 'unknown', jobsPending: 0 }))).toBeNull();
    expect(
      visibleIndexQueueCount(progress({ phase: 'indexing', jobsPending: 12, live: false })),
    ).toBeNull();
  });
});

/**
 * Tempdoc 813 §4 — the other half of the narrowed 727 F-2 suppression. `indexState` is IDLE across
 * BOTH the stale-residue case pinned above and the real enrichment window (the backfill runs on idle
 * cycles and never flips the index state), so suppression alone made the strip claim "System idle"
 * while the GPU was busy for minutes — finding 1's exact mechanism. These cases pin the split.
 */
describe('enrichingLabel (813 §4 — the narrowed F-2 suppression)', () => {
  it('backfill genuinely active ⇒ an "Enriching — N%" line, NOT the idle close', () => {
    expect(enrichingLabel(progress({ phase: 'enriching', enrichingPercent: 64 }))).toBe(
      'Enriching — 64% · semantic search catching up',
    );
  });

  it('enriching without a faithful denominator ⇒ the phase, no fabricated number', () => {
    const label = enrichingLabel(progress({ phase: 'enriching', enrichingPercent: null }));
    expect(label).toBe('Enriching — semantic search catching up');
    expect(label).not.toMatch(/\d/);
  });

  it('idle residue (phase "ready") ⇒ null — the suppression F-2 installed is retained', () => {
    expect(enrichingLabel(progress({ phase: 'ready', enrichingPercent: 100 }))).toBeNull();
  });

  it('job-queue work ⇒ null (the task rows already say it) and unknown ⇒ null', () => {
    expect(enrichingLabel(progress({ phase: 'indexing', jobsPending: 12 }))).toBeNull();
    expect(enrichingLabel(progress({ phase: 'unknown' }))).toBeNull();
  });

  it('a dead snapshot never asserts live enrichment (807 A.3)', () => {
    expect(
      enrichingLabel(progress({ phase: 'enriching', enrichingPercent: 64, live: false })),
    ).toBeNull();
  });
});

/**
 * The same split, end-to-end at the render site — the half `visibleIndexQueueCount`'s unit cases
 * cannot see. Both scenarios below have `indexState: 'IDLE'` and zero pending jobs; only the
 * enrichment counters differ, and that is exactly what must decide "System idle" vs "Enriching".
 */
describe('jf-system-self-view — the narrowed 727 F-2 suppression, rendered (813 §4)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ state: 'idle', updatedAtEpochMs: Date.now() }),
      }),
    );
  });

  afterEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
    vi.unstubAllGlobals();
  });

  /**
   * The status snapshot AND the inference counters — the second subsystem F-2's residue came from.
   * Feeding both is what makes the render cases below DISCRIMINATING: with the inference counters
   * present, pre-remediation code renders a second, contradictory "Processing N items" row for the
   * same enrichment backlog, so a case that fed status alone passed no matter what the strip did
   * with those counters.
   */
  function feed(enrichment: Record<string, unknown>, inference?: Record<string, unknown>): void {
    __feedForTest({
      status: {
        worker: { core: { indexState: 'IDLE', pendingJobs: 0, indexHealthy: true }, enrichment },
      } as unknown as import('../state/aiStateStore.js').StatusSnapshot,
      inference: (inference ?? {
        embeddingQueueSize: 3,
        vduQueueSize: 5,
      }) as unknown as import('../state/aiStateStore.js').InferenceSnapshot,
    });
    __tickClockForTest();
  }

  it('backfill genuinely active ⇒ ONE Enriching row, no second count for the same work', async () => {
    feed({
      backfillMode: 'combined',
      embeddingEnabled: true,
      embeddingDocCount: 100,
      embeddingPendingCount: 36,
    });
    const el = await mount('strip');
    const row = el.shadowRoot!.querySelector('[data-testid="self-view-enriching"]');
    expect(row?.textContent).toContain('Enriching — 64%');
    expect(el.shadowRoot!.querySelector('[data-testid="self-view-idle"]')).toBeNull();
    // The discriminating half: the jobs queue is drained, so the indexing row must be absent —
    // the inference counters fed above must not become a second description of the same backlog.
    expect(el.shadowRoot!.querySelector('[data-testid="self-view-index-queue"]')).toBeNull();
  });

  /**
   * The arm the F-2 suppression never covered: `indexState` is neither IDLE nor job-backed. Before
   * the collapse this rendered TWO rows for one backlog — "Processing 8 items / running" from the
   * inference counters (8 = embeddingQueueSize 3 + vduQueueSize 5) beside "Enriching — 64%" from
   * the status poll. One backlog, two derivations, two numbers: §1a's defect class.
   */
  it('a worker in ERROR renders no second, contradictory count for the same backlog', async () => {
    __feedForTest({
      status: {
        worker: {
          core: { indexState: 'ERROR', pendingJobs: 0, indexHealthy: false },
          enrichment: {
            backfillMode: 'combined',
            embeddingEnabled: true,
            embeddingDocCount: 100,
            embeddingPendingCount: 36,
          },
        },
      } as unknown as import('../state/aiStateStore.js').StatusSnapshot,
      inference: {
        embeddingQueueSize: 3,
        vduQueueSize: 5,
      } as unknown as import('../state/aiStateStore.js').InferenceSnapshot,
    });
    __tickClockForTest();
    const el = await mount('strip');
    expect(el.shadowRoot!.querySelector('[data-testid="self-view-index-queue"]')).toBeNull();
    expect(
      el.shadowRoot!.querySelector('[data-testid="self-view-enriching"]')?.textContent,
    ).toContain('Enriching — 64%');
  });

  it('idle residue only ⇒ "System idle" is retained (F-2 not repealed)', async () => {
    feed({
      backfillMode: 'idle',
      embeddingEnabled: true,
      embeddingDocCount: 100,
      embeddingPendingCount: 0,
    });
    const el = await mount('strip');
    expect(el.shadowRoot!.querySelector('[data-testid="self-view-enriching"]')).toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="self-view-idle"]')).not.toBeNull();
  });
});
