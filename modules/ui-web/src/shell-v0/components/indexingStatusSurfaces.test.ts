// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './StatusDeck.js';
import './TaskList.js';
import './IndexingOverlay.js';
import '../views/HealthSurface.js';
import '../views/LibrarySurface.js';
import { __feedForTest, __resetAiStateForTest, __tickClockForTest, getAiState, type StatusSnapshot } from '../state/aiStateStore.js';
import { __resetTasksForTest } from '../substrates/tasks/index.js';
import type { LibrarySurface } from '../views/LibrarySurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import type { JfElement } from '../primitives/JfElement.js';

type Surface = JfElement;
let surfaces: Surface[];
function status(jobs = 4, reasons = ['energy_saver'], gpuBound = true): StatusSnapshot {
  return {
    worker: {
      core: { indexState: jobs ? 'INDEXING' : 'IDLE', pendingJobs: jobs, indexHealthy: true },
      enrichment: { embeddingEnabled: true, embeddingDocCount: 1000, embeddingPendingCount: 80,
        spladeEnabled: false, nerEnabled: false },
      gpu: { embedOrtCuda: { available: gpuBound, attempted: true } },
    },
    power: { energyReduced: reasons.includes('energy_saver') },
    operating: { indexing: { state: 'PAUSED', reasons }, freshness: { staleSources: [] } },
    readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
  } as unknown as StatusSnapshot;
}
async function flush() {
  await Promise.resolve();
  await Promise.all(surfaces.map((s) => s.updateComplete));
  await Promise.resolve();
  const overlay = surfaces.at(3)?.shadowRoot?.querySelector<Surface>('jf-indexing-overlay');
  await overlay?.updateComplete;
}
async function mountAll() {
  surfaces = ['jf-status-deck', 'jf-task-list', 'jf-health-surface', 'jf-indexing-overlay-host', 'jf-library-surface', 'jf-system-self-view']
    .map((tag) => document.createElement(tag) as Surface);
  const library = surfaces[4] as LibrarySurface;
  library.host_ = {
    platform: { capabilities: new Set<string>() },
    data: { fetch: async () => ({ ok: false, status: 503 }) as Response },
    utilities: { formatRelativeTime: () => 'just now' },
  } as unknown as PluginHostApi;
  for (const el of surfaces) document.body.appendChild(el);
  await flush();
  library.roots = [{ pathHash: 'test-folder', status: 'indexed', walkCompleted: true,
    fileCount: 10, inFlightCount: 4, failedCount: 0,
    parentDocsTotalEmbedding: 10, parentDocsSettledEmbedding: 0 }];
  await flush();
}
function texts(): string[] {
  return surfaces.map((el, i) => i === 3
    ? el.shadowRoot?.querySelector('jf-indexing-overlay')?.shadowRoot?.textContent ?? ''
    : el.shadowRoot?.textContent ?? '');
}
function feed(s: StatusSnapshot) {
  __feedForTest({ status: s, inference: { mode: 'indexing', starting: false } as never });
  __tickClockForTest();
}

beforeEach(() => {
  surfaces = [];
  __resetAiStateForTest();
  __resetTasksForTest();
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { headers: { 'Content-Type': 'application/json' } })));
});
afterEach(async () => {
  for (const el of surfaces) el.remove();
  __resetAiStateForTest();
  __resetTasksForTest();
  await Promise.resolve();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('WP3.3 shared status surfaces', () => {
  it('S33.1 S33.6 every surface says text indexing continues and enrichment waits for energy', async () => {
    feed(status());
    await mountAll();
    for (const [i, text] of texts().entries()) {
      expect(text, surfaces[i]!.localName).toContain('Text indexing continues');
      expect(text, surfaces[i]!.localName).toContain('Idle enrichment deferred to save energy. Sparse enrichment can continue between files.');
      expect(text, surfaces[i]!.localName).not.toMatch(/queue[^.]*paused|Paused — saving energy|Batch Processing Active/);
    }
  });

  it('S33.2 S33.4 all surfaces replace and clear the reason after a fresh observation', async () => {
    feed(status());
    await mountAll();
    feed(status(4, ['game_mode']));
    await flush();
    for (const [i, text] of texts().entries()) {
      expect(text, surfaces[i]!.localName).toContain('GPU-bound idle enrichment deferred while a game is running.');
      expect(text, surfaces[i]!.localName).not.toContain('save energy');
    }
    feed(status(4, []));
    await flush();
    for (const text of texts()) {
      expect(text).not.toContain('game is running');
      expect(text).not.toContain('save energy');
    }
  });

  it('S33.4 retained stale observations are unconfirmed on every surface', async () => {
    feed(status());
    await mountAll();
    const stale = status();
    stale.operating!.freshness.staleSources = ['indexing'];
    feed(stale);
    await flush();
    for (const [i, text] of texts().entries()) {
      expect(text, surfaces[i]!.localName).toContain('Indexing status unconfirmed');
      expect(text, surfaces[i]!.localName).not.toContain('Text indexing continues');
    }
  });

  it.each([4, 0])('S33.4 aged observations with %i queued files expire on every surface while inference stays live and recover on fresh status', async (jobs) => {
    vi.useFakeTimers();
    const observedAt = Date.now();
    const fresh = status(jobs);
    fresh.meta = { workerRpcAtMs: observedAt - 7_000, workerRpcStale: false };
    fresh.operating!.freshness.staleAfterMs = 30_000;
    feed(fresh);
    await mountAll();
    for (const text of texts()) expect(text).toContain('save energy');

    vi.setSystemTime(observedAt + 23_001);
    __feedForTest({ inference: { mode: 'indexing', starting: false } });
    await flush();
    expect(getAiState().snapshotLive, 'inference still confirms Engine reachability').toBe(true);
    for (const [i, text] of texts().entries()) {
      expect(text, surfaces[i]!.localName).toContain('Indexing status unconfirmed');
      expect(text, surfaces[i]!.localName).not.toMatch(/Text indexing continues|Ready — fully searchable|save energy/);
    }

    const recovered = status(jobs, []);
    recovered.meta = { workerRpcAtMs: Date.now(), workerRpcStale: false };
    recovered.operating!.freshness.staleAfterMs = 30_000;
    feed(recovered);
    await flush();
    for (const [i, text] of texts().entries()) {
      expect(text, surfaces[i]!.localName).not.toContain('Indexing status unconfirmed');
      expect(text, surfaces[i]!.localName).not.toContain('save energy');
    }
  });

  it('S33.1 empty file queues still describe energy-deferred enrichment on every surface', async () => {
    feed(status(0));
    await mountAll();
    for (const [i, text] of texts().entries()) {
      expect(text, surfaces[i]!.localName).toContain('Enrichment deferred');
      expect(text, surfaces[i]!.localName).not.toMatch(/Ready — fully searchable|Up to date|System idle|Batch Processing Active/);
    }
  });

  it('S33.2 every surface distinguishes CPU enrichment under game and external GPU holds', async () => {
    feed(status(0, ['game_mode'], false));
    await mountAll();
    for (const reason of ['game_mode', 'gpu_busy_external']) {
      feed(status(0, [reason], false));
      await flush();
      for (const [i, text] of texts().entries()) {
        expect(text, surfaces[i]!.localName).toContain('CPU-only idle enrichment can continue.');
        expect(text, surfaces[i]!.localName).not.toContain('Enrichment deferred while');
      }
    }
  });

  it('S33.3 every surface names chat only while that reason is observed', async () => {
    feed(status(0, ['chat_generating']));
    await mountAll();
    for (const [i, text] of texts().entries()) {
      expect(text, surfaces[i]!.localName).toContain('Indexing shares time with chat.');
    }
    feed(status(0, []));
    await flush();
    for (const text of texts()) expect(text).not.toContain('shares time with chat');
  });

  it('N33.3 deferred enrichment retains the measured aggregate without an active progress bar', async () => {
    feed(status(0));
    await mountAll();
    const tasks = surfaces[1]!.shadowRoot!;
    const aggregate = tasks.querySelector('[data-testid="task-aggregate"]');
    expect(aggregate).not.toBeNull();
    expect(aggregate?.getAttribute('data-phase')).toBe('enriching');
    expect(aggregate?.querySelector('[data-testid="task-aggregate-counts"]')?.textContent).toContain('enrichment steps waiting');
    expect(aggregate?.querySelector('[role="progressbar"]')).toBeNull();
  });

  it('S33.5 Library with no settled documents stays enriching without inventing a percentage', async () => {
    feed(status(0, []));
    await mountAll();
    const library = surfaces[4] as LibrarySurface;
    library.roots = library.roots.map(root => ({ ...root, inFlightCount: 0 }));
    await flush();
    expect(library.shadowRoot!.textContent).toContain('semantic search still catching up');
    expect(library.shadowRoot!.textContent).not.toContain('0%');
  });

  it('S33.6 announces one semantic change once across identical polls', async () => {
    feed(status());
    await mountAll();
    const deck = surfaces[0]!;
    const announcer = deck.shadowRoot?.querySelector('[data-testid="indexing-announcer"]');
    expect(announcer).not.toBeNull();
    expect(announcer?.textContent).toContain('Idle enrichment deferred to save energy. Sparse enrichment can continue between files.');
    const mutations: MutationRecord[] = [];
    const observer = new MutationObserver((records) => mutations.push(...records));
    observer.observe(announcer!, { childList: true, characterData: true, subtree: true });
    try {
      feed(status());
      await flush();
      expect(mutations).toHaveLength(0);
      feed(status(4, ['game_mode']));
      await flush();
      expect(mutations).toHaveLength(1);
      expect(announcer?.textContent).toContain('a game is running');
      feed(status(4, ['game_mode']));
      await flush();
      expect(mutations).toHaveLength(1);
      expect(surfaces.slice(1).flatMap((el) => [...el.shadowRoot!.querySelectorAll('[data-testid="indexing-announcer"]')])).toHaveLength(0);
    } finally { observer.disconnect(); }
  });
});
