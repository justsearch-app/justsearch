// @vitest-environment happy-dom

/**
 * §32 R-E1 — <jf-task-list> render tests, re-expressed for the tempdoc 813 §5 redesign.
 *
 * The panel's default view is now ONE aggregate card driven by the indexing-progress projection;
 * the per-file rows moved behind an opt-in "Details" disclosure. Every pre-813 case below is
 * preserved in INTENT and re-pointed at where its subject now lives (the row cases open the
 * disclosure first), plus the new cases the redesign's honesty rules require.
 *
 * 813 §20 widened that disclosure into the DETAIL tier: per-stage enrichment rows sit above the
 * per-file rows, and the affordance exists whenever there is any of the two to show.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TaskList, READY_DISMISS_MS } from './TaskList.js';
import {
  startTask,
  upsertMirroredTask,
  __resetTasksForTest,
} from '../substrates/tasks/index.js';
import {
  __feedForTest,
  __resetAiStateForTest,
  type StatusSnapshot,
} from '../state/aiStateStore.js';
import type { IndexingProgress } from '../state/indexingProgress.js';
import { formatCount } from '../display/format.js';

void TaskList;

let host: HTMLElement;

beforeEach(() => {
  __resetTasksForTest();
  __resetAiStateForTest();
  host = document.createElement('jf-task-list');
  document.body.appendChild(host);
});

afterEach(() => {
  host.remove();
  vi.useRealTimers();
});

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

/** 574 B — cancel is a <jf-button>; activate it from the inner <jf-control> button. */
async function activateJfButton(el: Element | null | undefined): Promise<void> {
  if (!el) throw new Error('activateJfButton: element not found');
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = el.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}

const q = (sel: string): Element | null | undefined => host.shadowRoot?.querySelector(sel);
const text = (sel: string): string | undefined => q(sel)?.textContent?.trim();

/** Collapse lit's inter-element whitespace so a multi-span row reads as one line. */
const rowText = (sel: string): string | undefined =>
  q(sel)?.textContent?.trim().replace(/\s+/g, ' ');

/** Open the opt-in detail disclosure (813 §5/§20) — where the row lists now live. */
async function showFiles(): Promise<void> {
  (q('[data-testid="tasks-disclosure"]') as HTMLButtonElement | null)?.click();
  await flush();
}

/** Set the projection directly — the render-level equivalent of a poll landing. */
async function setProgress(p: Partial<IndexingProgress>): Promise<void> {
  (host as TaskList).progress = {
    activity: { summary: 'Indexing status unconfirmed', detail: null,
      confirmed: (p.phase ?? 'unknown') !== 'unknown' && p.live !== false, enrichmentDeferred: false },
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
    rebuildPercent: null,
    pendingBytes: null,
    live: true,
    stages: { embedding: true, splade: true, ner: true },
    ...p,
  };
  await flush();
}

const snapshot = (worker: unknown): StatusSnapshot => ({ worker }) as unknown as StatusSnapshot;

const INDEXING_SNAPSHOT = snapshot({
  core: { indexState: 'INDEXING', pendingJobs: 412 },
  enrichment: { backfillMode: 'idle' },
});
const READY_SNAPSHOT = snapshot({
  core: { indexState: 'IDLE', pendingJobs: 0 },
  enrichment: { backfillMode: 'idle' },
});

/**
 * Round-15 F1 — the state this card claimed "Everything is indexed and enriched" over: jobs drained,
 * nothing enriched, and every enrichment stage inapplicable because no embedding service exists.
 * The enrichment block is the round's captured `/api/status`
 * (`evidence/api-history/20260807-011454/api-api-status.json`), verbatim.
 */
const NO_EMBEDDING_MODEL_SNAPSHOT = snapshot({
  core: { indexState: 'IDLE', pendingJobs: 0 },
  enrichment: {
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
  },
});

/**
 * F1-repro's first attempt: the model was removed AFTER enrichment completed, so the vectors persist
 * in the index. Stage flags are identically false — only the coverage differs — which is exactly why
 * the two cases must not be told apart by the flags.
 */
const VECTORS_PERSISTED_SNAPSHOT = snapshot({
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
});

describe('<jf-task-list> (§32 R-E1)', () => {
  it('collapses when there are no tasks and nothing is indexing or enriching', async () => {
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(true);
  });

  // Pre-813 intent preserved: a running task still renders its status chip, its progress bar and a
  // cancel affordance — they now live inside the opt-in disclosure rather than the default view.
  it('renders a running task with status, progress bar, and cancel (inside the disclosure)', async () => {
    const id = startTask({ label: 'reindex', progress: 0.4, cancel: () => {} });
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(false);
    expect(q(`[data-testid="task-status-${id}"]`)).toBeNull(); // collapsed by default
    await showFiles();
    expect(text(`[data-testid="task-status-${id}"]`)).toBe('running');
    expect(q(`[data-testid="task-cancel-${id}"]`)).not.toBeNull();
    expect(q('.bar > span')).not.toBeNull();
  });

  // Tempdoc 550 Thesis III (bounded projection / F-1 cure), re-expressed at 813 §5's default view:
  // a backlog of queued jobs must render as BOUNDED content — a single COUNT chip, never N pills —
  // and 813 adds that the default view renders no rows at all until asked.
  it('collapses a large queued backlog to a count chip (no per-item flood)', async () => {
    for (let i = 0; i < 550; i++) {
      upsertMirroredTask({ id: `idxjob:q${i}`, label: `Indexing · default (q${i})`, status: 'queued' });
    }
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(false);
    // Default view: 550 queued jobs produce ZERO rows and no chip strip.
    expect(host.shadowRoot?.querySelectorAll('.task').length).toBe(0);
    expect(q('[data-testid="task-summary"]')).toBeNull();
    await showFiles();
    // Disclosed: the count chip reads "550 queued"…
    expect(text('[data-testid="task-count-queued"]')).toBe('550 queued');
    // …and STILL not one row per queued job (queued is collapsed, not listed).
    expect(host.shadowRoot?.querySelectorAll('.task').length).toBe(0);
  });

  it('lists running rows individually while keeping the queued bulk a count', async () => {
    upsertMirroredTask({ id: 'idxjob:r1', label: 'Indexing · default (r1)', status: 'running' });
    for (let i = 0; i < 12; i++) {
      upsertMirroredTask({ id: `idxjob:q${i}`, label: `q${i}`, status: 'queued' });
    }
    await flush();
    await showFiles();
    expect(text('[data-testid="task-count-running"]')).toBe('1 running');
    expect(text('[data-testid="task-count-queued"]')).toBe('12 queued');
    // The single running job is listed as a row; the 12 queued are not.
    expect(host.shadowRoot?.querySelectorAll('.task').length).toBe(1);
  });

  it('the cancel button cancels the task (invokes the cancel fn)', async () => {
    const cancel = vi.fn();
    const id = startTask({ label: 'x', cancel });
    await flush();
    await showFiles();
    await activateJfButton(q(`[data-testid="task-cancel-${id}"]`));
    await flush();
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(text(`[data-testid="task-status-${id}"]`)).toBe('cancelled');
  });
});

describe('<jf-task-list> — aggregate card (813 §5)', () => {
  // The pre-813-§19 intent, now the NO-DRAIN-OBSERVED arm: with no high-water denominator the
  // backlog is a count, not a fraction, so the bar must claim activity and never a position.
  it('the Indexing phase leads with the phase noun, an INDETERMINATE bar and a remaining count', async () => {
    await setProgress({ phase: 'indexing', jobsPending: 412, jobsQueued: 412, indexingPercent: null });
    expect(host.hasAttribute('data-empty')).toBe(false);
    expect(text('[data-testid="task-title"]')).toBe('Indexing');
    const bar = q('[data-testid="task-aggregate-bar"]')!;
    expect(bar.classList.contains('indeterminate')).toBe(true);
    expect(bar.getAttribute('aria-valuenow')).toBeNull();
    expect(text('[data-testid="task-aggregate-counts"]')).toBe('412 files remaining');
  });

  // 813 §19 (W2) — once the projection HAS measured a drain against the episode's high-water
  // backlog, the same bar becomes determinate and reports its position to assistive tech.
  it('the Indexing phase renders a DETERMINATE bar once a drain has been observed', async () => {
    await setProgress({ phase: 'indexing', jobsPending: 400, jobsQueued: 400, indexingPercent: 75 });
    const bar = q('[data-testid="task-aggregate-bar"]')!;
    expect(bar.classList.contains('indeterminate')).toBe(false);
    expect(bar.getAttribute('aria-valuenow')).toBe('75');
    expect(bar.getAttribute('aria-valuemin')).toBe('0');
    expect(bar.getAttribute('aria-valuemax')).toBe('100');
    // The counts line still says what remains — the bar is the position, not a substitute for it.
    expect(text('[data-testid="task-aggregate-counts"]')).toBe('400 files remaining');
  });

  // The whole W2 seam end to end, through the REAL store: the high-water backlog is cross-poll
  // memory the component never holds itself, so only a two-poll feed proves it is threaded from
  // `aiStateStore` into the selector and out to the bar. (Setting `progress` directly above cannot.)
  it('derives the determinate position from the STORE across two polls, not from one snapshot', async () => {
    __feedForTest({ status: snapshot({ core: { indexState: 'INDEXING', pendingJobs: 1600 } }) });
    const el = document.createElement('jf-task-list') as TaskList;
    document.body.appendChild(el);
    await flush();
    // First poll: max == pending, no drain witnessed ⟹ still indeterminate.
    expect(el.progress.indexingPercent).toBeNull();
    expect(
      el.shadowRoot
        ?.querySelector('[data-testid="task-aggregate-bar"]')
        ?.classList.contains('indeterminate'),
    ).toBe(true);

    __feedForTest({ status: snapshot({ core: { indexState: 'INDEXING', pendingJobs: 400 } }) });
    await flush();
    await flush();
    expect(el.progress.indexingPercent).toBe(75);
    const bar = el.shadowRoot?.querySelector('[data-testid="task-aggregate-bar"]')!;
    expect(bar.classList.contains('indeterminate')).toBe(false);
    expect(bar.getAttribute('aria-valuenow')).toBe('75');
    el.remove();
  });

  // 813 Slice B — "12 files remaining" says nothing about a mixed corpus where one file is a 2 GB
  // video. The weight rides the SAME counts line, and only when the projection calls it faithful.
  it('appends the remaining byte weight when the projection has a faithful one', async () => {
    await setProgress({ phase: 'indexing', jobsPending: 412, pendingBytes: 8 * 1024 * 1024 * 1024 });
    expect(text('[data-testid="task-aggregate-counts"]')).toBe('412 files remaining · 8.00 GB');
  });

  it('renders no byte figure at all when the weight is not faithful (never "0 B")', async () => {
    await setProgress({ phase: 'indexing', jobsPending: 412, pendingBytes: null });
    expect(text('[data-testid="task-aggregate-counts"]')).toBe('412 files remaining');
    expect(text('[data-testid="task-aggregate-counts"]')).not.toContain('B');
  });

  it('the Enriching phase renders the coverage percent as a real fraction', async () => {
    await setProgress({ phase: 'enriching', enrichingPercent: 64, enrichingPending: 900 });
    // 813 §19 (W1) — capability first: keyword search already works during enrichment.
    expect(text('[data-testid="task-title"]')).toBe('Search is ready — still improving');
    const bar = q('[data-testid="task-aggregate-bar"]')!;
    expect(bar.classList.contains('indeterminate')).toBe(false);
    expect(bar.getAttribute('aria-valuenow')).toBe('64');
    expect(text('[data-testid="task-aggregate-counts"]')).toBe('64% · semantic search catching up');
  });

  // Denominator honesty (§1f / availability.ts:193-194): no faithful denominator ⇒ NO number and NO
  // bar — the panel still says what is happening, it just does not invent a fraction.
  it('enriching with a null percent suppresses the number AND the bar, not the card', async () => {
    await setProgress({ phase: 'enriching', enrichingPercent: null, enrichingPending: 0 });
    expect(host.hasAttribute('data-empty')).toBe(false);
    expect(q('[data-testid="task-aggregate-bar"]')).toBeNull();
    expect(text('[data-testid="task-aggregate-counts"]')).toBe('semantic search catching up');
    expect(text('[data-testid="task-aggregate-counts"]')).not.toContain('%');
  });

  // §1d's absence-as-claim defect: the panel used to hide at zero TASKS, so "it disappeared" read as
  // "done" while the semantic layers were still catching up. The aggregate reads the poll, not the
  // SSE row feed, so an empty task list can no longer withdraw the claim.
  it('stays visible during enriching even when the SSE task list is empty', async () => {
    await setProgress({ phase: 'enriching', enrichingPercent: 20, enrichingPending: 500 });
    expect((host as TaskList).tasks.length).toBe(0);
    expect(host.hasAttribute('data-empty')).toBe(false);
    expect(text('[data-testid="task-title"]')).toBe('Search is ready — still improving');
    // 813 §20 — with no stage rows and no extraction backlog either, there is genuinely nothing to
    // disclose, so the affordance stays absent rather than opening on an empty box.
    expect(q('[data-testid="tasks-disclosure"]')).toBeNull();
  });

  it('S33.4 unknown indexing is unconfirmed without a phase, bar or counts', async () => {
    upsertMirroredTask({ id: 'idxjob:r1', label: 'agent op', status: 'running' });
    await setProgress({ phase: 'unknown' });
    expect(text('[data-testid="task-title"]')).toBe('Indexing status unconfirmed');
    expect(q('[data-testid="task-aggregate"]')).toBeNull();
  });

  it('a non-live snapshot renders the stall line rather than passing counts off as live', async () => {
    await setProgress({ phase: 'indexing', jobsPending: 412, live: false });
    expect(q('[data-testid="task-feed-stalled"]')).not.toBeNull();
  });
});

// 813 §19 (W4) — the estimate merged INTO the counts line; the separate `.eta` element is retired,
// so every case below is re-expressed at SEGMENT level on the one fact row. Intent unchanged: a
// coarse "~" estimate when the projection has one, nothing at all when it does not.
describe('<jf-task-list> — indicative estimate (813 §5b, merged by §19 W4)', () => {
  it('renders a coarse "~" estimate as the last segment of the one fact row', async () => {
    await setProgress({ phase: 'indexing', jobsPending: 412, etaSeconds: 130 });
    expect(text('[data-testid="task-aggregate-counts"]')).toBe('412 files remaining · ~2m 10s left');
    // ONE fact row, not two: the card holds the bar and the counts line and nothing else, so the
    // estimate cannot have been merged AND still rendered as its own (retired) element.
    expect(host.shadowRoot?.querySelectorAll('.aggregate > *').length).toBe(2);
  });

  // The full merged run, all three segments — the width case §19 flags as the occlusion risk.
  it('merges counts, byte weight and estimate into a single line', async () => {
    await setProgress({
      phase: 'indexing',
      jobsPending: 412,
      pendingBytes: 8 * 1024 * 1024 * 1024,
      etaSeconds: 103,
    });
    expect(text('[data-testid="task-aggregate-counts"]')).toBe(
      '412 files remaining · 8.00 GB · ~1m 43s left',
    );
  });

  // The visible line stays one line; the INDICATIVE qualifier survives in the accessible label and
  // the hover title rather than being dropped.
  it('carries "at the current rate" in the accessible label and title, not the visible text', async () => {
    await setProgress({ phase: 'indexing', jobsPending: 412, etaSeconds: 130 });
    const line = q('[data-testid="task-aggregate-counts"]')!;
    expect(line.textContent?.trim()).not.toContain('at the current rate');
    expect(line.getAttribute('aria-label')).toBe(
      '412 files remaining · ~2m 10s left at the current rate',
    );
    expect(line.getAttribute('title')).toBe(
      '412 files remaining · ~2m 10s left at the current rate',
    );
  });

  it('renders NO estimate segment when the projection has no honest basis (no placeholder)', async () => {
    await setProgress({ phase: 'indexing', jobsPending: 412, etaSeconds: null });
    const line = q('[data-testid="task-aggregate-counts"]')!;
    expect(line.textContent?.trim()).toBe('412 files remaining');
    expect(line.textContent).not.toContain('left');
    expect(line.textContent).not.toContain('~');
    // No estimate ⇒ no label either, rather than one that just repeats the visible line.
    expect(line.getAttribute('aria-label')).toBeNull();
    expect(line.getAttribute('title')).toBeNull();
  });

  // §1e — enrichment throughput is legitimately unstable (ingest preempts the backfill at batch
  // boundaries), so the Enriching phase never carries an estimate even if one were supplied.
  it('never renders an estimate during Enriching', async () => {
    await setProgress({ phase: 'enriching', enrichingPercent: 40, etaSeconds: 130 });
    const line = q('[data-testid="task-aggregate-counts"]')!;
    expect(line.textContent).not.toContain('left');
    expect(line.textContent).not.toContain('~');
  });
});

describe('<jf-task-list> — disclosure + terminal state (813 §5)', () => {
  it('the disclosure toggles the row list and reports its state to assistive tech', async () => {
    upsertMirroredTask({ id: 'idxjob:r1', label: 'Indexing · default (r1)', status: 'running' });
    await flush();
    const btn = () => q('[data-testid="tasks-disclosure"]') as HTMLButtonElement;
    expect(btn().getAttribute('aria-expanded')).toBe('false');
    expect(btn().textContent?.trim()).toBe('Details');
    expect(q('[data-testid="tasks-files"]')).toBeNull();

    btn().click();
    await flush();
    expect(btn().getAttribute('aria-expanded')).toBe('true');
    expect(btn().textContent?.trim()).toBe('Hide details');
    expect(q('[data-testid="tasks-files"]')).not.toBeNull();

    btn().click();
    await flush();
    expect(btn().getAttribute('aria-expanded')).toBe('false');
    expect(q('[data-testid="tasks-files"]')).toBeNull();
  });

  // The completion signal 813 §14.3 chose: a brief STATE, then dismissal — so "the panel vanished"
  // is no longer the only thing the product ever said about being done.
  it('shows a brief terminal state on the active→ready edge, then dismisses itself', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    __feedForTest({ status: INDEXING_SNAPSHOT });
    const el = document.createElement('jf-task-list') as TaskList;
    document.body.appendChild(el);
    await flush();
    expect(el.progress.phase).toBe('indexing');

    __feedForTest({ status: READY_SNAPSHOT });
    await flush();
    await flush();
    expect(el.showingReady).toBe(true);
    expect(el.shadowRoot?.querySelector('[data-testid="task-title"]')?.textContent?.trim()).toBe(
      'Ready — fully searchable',
    );
    expect(el.hasAttribute('data-empty')).toBe(false);

    vi.advanceTimersByTime(READY_DISMISS_MS);
    await flush();
    expect(el.showingReady).toBe(false);
    expect(el.hasAttribute('data-empty')).toBe(true);
    el.remove();
  });

  /**
   * Round-15 F1 (HIGH, twice reproduced) — the card's completion tier must be a claim about
   * COVERAGE, not about an empty work queue. With no embedding model, nothing can be queued; the
   * card read that as "done" and told the user their corpus was fully enriched at 0% coverage.
   */
  it('F1: does NOT claim "indexed and enriched" when nothing was enriched and nothing can be', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    __feedForTest({ status: INDEXING_SNAPSHOT });
    const el = document.createElement('jf-task-list') as TaskList;
    document.body.appendChild(el);
    await flush();
    expect(el.progress.phase).toBe('indexing');

    __feedForTest({ status: NO_EMBEDDING_MODEL_SNAPSHOT });
    await flush();
    await flush();
    // The terminal window still opens (the run DID end) — with honest words.
    expect(el.progress.phase).toBe('blocked');
    expect(el.showingReady).toBe(true);
    const title = el.shadowRoot?.querySelector('[data-testid="task-title"]')?.textContent?.trim();
    const body = el.shadowRoot
      ?.querySelector('[data-testid="task-aggregate-counts"]')
      ?.textContent?.trim();
    expect(title).not.toBe('Ready — fully searchable');
    expect(title).toBe('Search is ready — keyword only');
    expect(body).not.toContain('indexed and enriched');
    expect(body).toContain('semantic search waiting for AI install');
    expect(
      el.shadowRoot?.querySelector('[data-testid="task-aggregate"]')?.getAttribute('data-phase'),
    ).toBe('blocked');
    // No fabricated progress bar: nothing is progressing.
    expect(el.shadowRoot?.querySelector('[data-testid="task-aggregate-bar"]')).toBeNull();

    // ...and it still gets out of the way, rather than pinning the overlay panel up forever.
    vi.advanceTimersByTime(READY_DISMISS_MS);
    await flush();
    expect(el.hasAttribute('data-empty')).toBe(true);
    el.remove();
  });

  /**
   * F1-repro's negative result, protected: the model is gone but the vectors are in the index, so
   * the corpus IS fully enriched and the completion claim is true. The two states differ only in
   * their coverage counters — a fix that keyed on the model's absence would break this one.
   */
  it('F1-repro BOUNDARY: vectors persisted with the model removed still reads "fully searchable"', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    __feedForTest({ status: INDEXING_SNAPSHOT });
    const el = document.createElement('jf-task-list') as TaskList;
    document.body.appendChild(el);
    await flush();

    __feedForTest({ status: VECTORS_PERSISTED_SNAPSHOT });
    await flush();
    await flush();
    expect(el.progress.phase).toBe('ready');
    expect(el.shadowRoot?.querySelector('[data-testid="task-title"]')?.textContent?.trim()).toBe(
      'Ready — fully searchable',
    );
    expect(
      el.shadowRoot?.querySelector('[data-testid="task-aggregate-counts"]')?.textContent,
    ).toContain('indexed and enriched');
    el.remove();
  });

  // `ready` is also the steady state of an idle index — a panel that announced completion on every
  // poll would be claiming an event it never witnessed.
  it('does not flash the terminal state when it never saw an active phase', async () => {
    __feedForTest({ status: READY_SNAPSHOT });
    const el = document.createElement('jf-task-list') as TaskList;
    document.body.appendChild(el);
    await flush();
    expect(el.showingReady).toBe(false);
    expect(el.hasAttribute('data-empty')).toBe(true);
    el.remove();
  });
});

/**
 * 813 §20 — the DETAIL tier. The surface states the capability ("search is ready, N% enriched");
 * the disclosure lists the machine stages that add up to that N. Every number below is a narrower
 * SCOPE of `enrichingStages`, which is itself the row set the percent sums (§3b).
 */
describe('<jf-task-list> — per-stage detail (813 §20)', () => {
  const STAGES = [
    { id: 'embedding', total: 400, pending: 100 },
    { id: 'splade', total: 400, pending: 0 },
    { id: 'ner', total: 40, pending: 10 },
    { id: 'chunkVectors', total: 1000, pending: 500 },
  ] as const;

  it('opens during PURE enrichment, when there is not a single task row to show', async () => {
    // The defect this closes: the disclosure was gated on the SSE row list, so during enrichment
    // (no jobs, no rows) the card offered no way to see what was still running.
    await setProgress({
      phase: 'enriching',
      enrichingPercent: 62,
      enrichingPending: 610,
      enrichingStages: [...STAGES],
    });
    expect((host as TaskList).tasks.length).toBe(0);
    const btn = q('[data-testid="tasks-disclosure"]') as HTMLButtonElement;
    expect(btn).not.toBeNull();
    expect(btn.textContent?.trim()).toBe('Details');

    await showFiles();
    expect(q('[data-testid="tasks-files"]')).not.toBeNull();
    expect(q('[data-testid="task-stage-embedding"]')).not.toBeNull();
  });

  it('lists one row per stage, in user words, with that stage own settled fraction', async () => {
    await setProgress({
      phase: 'enriching',
      enrichingPercent: 62,
      enrichingStages: [...STAGES],
    });
    await showFiles();
    expect(rowText('[data-testid="task-stage-embedding"]')).toBe('Semantic vectors 300 / 400');
    expect(rowText('[data-testid="task-stage-ner"]')).toBe('Entity recognition 30 / 40');
    // Grouped through the shared formatter, not printed raw (the separator is locale-dependent, so
    // the expectation is built from the same authority the row renders with).
    expect(rowText('[data-testid="task-stage-chunkVectors"]')).toBe(
      `Passage vectors ${formatCount(500)} / ${formatCount(1000)}`,
    );
    expect(rowText('[data-testid="task-stage-chunkVectors"]')).not.toContain('1000');
    // The machine names stay on the projection's ids; the rows speak the user's language.
    expect(q('[data-testid="tasks-files"]')?.textContent).not.toContain('SPLADE');
  });

  it('marks a finished stage with a ✓ and leaves the unfinished ones unmarked', async () => {
    await setProgress({ phase: 'enriching', enrichingPercent: 62, enrichingStages: [...STAGES] });
    await showFiles();
    expect(rowText('[data-testid="task-stage-splade"]')).toBe('Keyword expansion 400 / 400 ✓');
    expect(rowText('[data-testid="task-stage-embedding"]')).not.toContain('✓');
  });

  it('a stage with no row on the projection gets no row here (a disabled stage is not 0%)', async () => {
    await setProgress({
      phase: 'enriching',
      enrichingPercent: 75,
      enrichingStages: [{ id: 'embedding', total: 400, pending: 100 }],
    });
    await showFiles();
    expect(q('[data-testid="task-stage-embedding"]')).not.toBeNull();
    expect(q('[data-testid="task-stage-splade"]')).toBeNull();
    expect(q('[data-testid="task-stage-ner"]')).toBeNull();
  });

  it('renders the extraction backlog WITHOUT a fraction — no denominator exists for it', async () => {
    await setProgress({ phase: 'enriching', enrichingPercent: 62, vduPending: 1234 });
    await showFiles();
    expect(rowText('[data-testid="task-stage-vdu"]')).toBe(
      `Content extraction — ${formatCount(1234)} remaining`,
    );
    // No invented "of N": `pendingVduCount` is a remaining count, not a slice of a known total.
    expect(rowText('[data-testid="task-stage-vdu"]')).not.toContain('/');
  });

  it('omits the extraction row entirely at zero — absence, not "0 remaining"', async () => {
    await setProgress({ phase: 'enriching', enrichingPercent: 62, vduPending: 0, enrichingStages: [...STAGES] });
    await showFiles();
    expect(q('[data-testid="task-stage-vdu"]')).toBeNull();
  });

  it('keeps the per-file section unchanged, BELOW the stage rows', async () => {
    upsertMirroredTask({ id: 'idxjob:r1', label: 'Indexing · default (r1)', status: 'running' });
    await setProgress({ phase: 'enriching', enrichingPercent: 62, enrichingStages: [...STAGES] });
    await showFiles();
    // Both tiers present, and the machine stages come first (detail reads top-down: what the system
    // is doing, then which files it is doing it to).
    const kids = Array.from(q('[data-testid="tasks-files"]')!.children);
    const firstStage = kids.findIndex((k) => k.getAttribute('data-testid')?.startsWith('task-stage-'));
    const summary = kids.findIndex((k) => k.getAttribute('data-testid') === 'task-summary');
    expect(firstStage).toBeGreaterThanOrEqual(0);
    expect(summary).toBeGreaterThan(firstStage);
    expect(q('[data-testid="task-idxjob:r1"]')).not.toBeNull();
  });
});

/**
 * 813 §20 — the enrichment estimate on the card. Same segment discipline as the indexing arm
 * (§19 W4): appended, never substituted; absent entirely when the projection has no honest basis.
 */
describe('<jf-task-list> — enrichment estimate (813 §20)', () => {
  it('appends the estimate to the enriching fact row', async () => {
    await setProgress({ phase: 'enriching', enrichingPercent: 62, enrichingEtaSeconds: 130 });
    expect(text('[data-testid="task-aggregate-counts"]')).toBe(
      '62% · semantic search catching up · ~2m 10s left',
    );
  });

  it('carries "at the current rate" in the accessible label and title, not the visible text', async () => {
    await setProgress({ phase: 'enriching', enrichingPercent: 62, enrichingEtaSeconds: 130 });
    const line = q('[data-testid="task-aggregate-counts"]')!;
    expect(line.textContent).not.toContain('at the current rate');
    expect(line.getAttribute('aria-label')).toBe(
      '62% · semantic search catching up · ~2m 10s left at the current rate',
    );
    expect(line.getAttribute('title')).toBe(
      '62% · semantic search catching up · ~2m 10s left at the current rate',
    );
  });

  it('renders NO estimate segment when the projection has none (no placeholder)', async () => {
    await setProgress({ phase: 'enriching', enrichingPercent: 62, enrichingEtaSeconds: null });
    const line = q('[data-testid="task-aggregate-counts"]')!;
    expect(line.textContent?.trim()).toBe('62% · semantic search catching up');
    expect(line.textContent).not.toContain('left');
    expect(line.getAttribute('aria-label')).toBeNull();
    expect(line.getAttribute('title')).toBeNull();
  });

  it('still suppresses the percent when there is none, estimate or not', async () => {
    await setProgress({
      phase: 'enriching',
      enrichingPercent: null,
      enrichingEtaSeconds: 130,
    });
    expect(text('[data-testid="task-aggregate-counts"]')).toBe(
      'semantic search catching up · ~2m 10s left',
    );
    expect(q('[data-testid="task-aggregate-bar"]')).toBeNull();
  });

  it('never renders the enrichment estimate on the indexing arm', async () => {
    await setProgress({ phase: 'indexing', jobsPending: 412, enrichingEtaSeconds: 130 });
    expect(text('[data-testid="task-aggregate-counts"]')).toBe('412 files remaining');
  });
});


it('keeps retry waiting visible without showing progress or a ready claim', async () => {
  const detail = '8 files waiting. Next retry: 10/21/2026, 10:00:00 PM.';
  await setProgress({ phase: 'waiting', jobsPending: 8, live: true,
    activity: { summary: 'Waiting to retry', detail, confirmed: true, enrichmentDeferred: false } });
  expect(host.hasAttribute('data-empty')).toBe(false);
  expect(text('[data-testid="task-title"]')).toBe('Waiting to retry');
  expect(host.shadowRoot?.textContent).toContain(detail);
  expect(q('[role="progressbar"]')).toBeNull();
  expect(host.shadowRoot?.textContent).not.toContain('Everything is indexed');
  __feedForTest({ status: INDEXING_SNAPSHOT });
  await flush();
  __feedForTest({ status: READY_SNAPSHOT });
  await flush();
  expect((host as TaskList).showingReady).toBe(true);
  __feedForTest({ status: snapshot({ core: { indexState: 'WAITING', pendingJobs: 8 },
    failure: { nextRetryAtMs: Date.now() + 60_000 } }) });
  await flush();
  expect((host as TaskList).showingReady).toBe(false);
  expect(text('[data-testid="task-title"]')).toBe('Waiting to retry');
  expect(q('[role="progressbar"]')).toBeNull();
});

it('terminal failures withdraw the completion window and remain visible as errors', async () => {
  __feedForTest({ status: INDEXING_SNAPSHOT });
  await flush();
  __feedForTest({ status: READY_SNAPSHOT });
  await flush();
  expect((host as TaskList).showingReady).toBe(true);
  __feedForTest({ status: snapshot({ core: { indexState: 'ERROR', pendingJobs: 0, indexHealthy: false },
    failure: { failedJobs: 2 }, migration: { pendingJobsCount: 0, processingJobsCount: 0 } }) });
  await flush();
  expect((host as TaskList).showingReady).toBe(false);
  expect(host.hasAttribute('data-empty')).toBe(false);
  expect(text('[data-testid="task-title"]')).toBe('Indexing error');
  expect(host.shadowRoot?.textContent).toContain('2 files could not be indexed.');
  expect(host.shadowRoot?.textContent).not.toContain('Everything is indexed');
  expect(q('[role="progressbar"]')).toBeNull();
});


it.each([
  { embeddingDocCount: 2, embeddingCompletedCount: 1, embeddingFailedCount: 1 },
  { spladeDocCount: 2, spladeCompletedCount: 1, spladeFailedCount: 1 },
  { completedNerCount: 1, failedNerCount: 1 },
  { chunk: { chunkDocCount: 2, chunkEmbeddingCompletedCount: 1, chunkEmbeddingFailedCount: 1 } },
  { embeddingDocCount: 10000, embeddingCompletedCount: 10000,
    completeness: [{ stageId: 'embed', expected: 10000, present: 9999, missing: 1, failed: 0 }] },
])('does not override the failed or missing enrichment headline with a completion claim: %j', async enrichment => {
  __feedForTest({ status: INDEXING_SNAPSHOT });
  await flush();
  __feedForTest({ status: snapshot({ core: { indexState: 'IDLE', pendingJobs: 0, indexHealthy: true }, enrichment }) });
  await flush();
  expect((host as TaskList).progress.phase).toBe('error');
  expect((host as TaskList).showingReady).toBe(false);
  expect(text('[data-testid="task-title"]')).toBe('Indexing error');
  expect(host.shadowRoot?.textContent).not.toContain('fully searchable');
  expect(host.shadowRoot?.textContent).toContain('Keyword search remains available');
});

it('withdraws the completion window when the next count cannot be read', async () => {
  __feedForTest({ status: INDEXING_SNAPSHOT });
  await flush();
  __feedForTest({ status: READY_SNAPSHOT });
  await flush();
  expect((host as TaskList).showingReady).toBe(true);
  __feedForTest({ status: snapshot({ core: { indexState: 'IDLE', pendingJobs: 0, indexHealthy: true },
    enrichment: { embeddingPendingCount: -1 } }) });
  await flush();
  expect((host as TaskList).progress.phase).toBe('unknown');
  expect((host as TaskList).showingReady).toBe(false);
  expect(host.shadowRoot?.textContent).not.toContain('fully searchable');
});


it('describes a producer lost after startup without an AI-install claim in the completion window', async () => {
  __feedForTest({ status: INDEXING_SNAPSHOT });
  await flush();
  __feedForTest({ status: snapshot({ core: { indexState: 'IDLE', pendingJobs: 0, indexHealthy: true },
    enrichment: { embeddingEnabled: true, embeddingAvailable: false, embeddingDocCount: 2, embeddingPendingCount: 2 } }) });
  await flush();
  expect(text('[data-testid="task-title"]')).toBe('Semantic search unavailable');
  expect(text('[data-testid="task-aggregate-counts"]')).toContain('Embedding cannot run right now');
  expect(host.shadowRoot?.textContent).not.toContain('waiting for AI install');
});


it('keeps unavailable embedding and other real enrichment work visible after the completion window', async () => {
  vi.useFakeTimers();
  __feedForTest({ status: INDEXING_SNAPSHOT });
  await flush();
  __feedForTest({ status: snapshot({ core: { indexState: 'IDLE', pendingJobs: 0, indexHealthy: true },
    enrichment: { embeddingEnabled: true, embeddingAvailable: false, embeddingDocCount: 2,
      embeddingPendingCount: 2, spladeEnabled: true, spladeAvailable: true,
      spladeDocCount: 4, spladePendingCount: 1, spladeCompletedCount: 3 } }) });
  await flush();
  await vi.advanceTimersByTimeAsync(READY_DISMISS_MS + 1);
  await flush();
  expect((host as TaskList).showingReady).toBe(false);
  expect(host.hasAttribute('data-empty')).toBe(false);
  expect(text('[data-testid="task-title"]')).toBe('Semantic search unavailable');
  await showFiles();
  expect(rowText('[data-testid="task-stage-splade"]')).toBe('Keyword expansion 3 / 4');
  expect(host.shadowRoot?.textContent).not.toContain('still improving');
  expect(host.shadowRoot?.textContent).not.toContain('fully searchable');
});
