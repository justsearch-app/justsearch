// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// fm-07 (decision PG3): a folder whose row says "Up to date" carries a check, not a clock, also when
// its meaning work only waits for AI; every other row state keeps its own icon.
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import '../LibrarySurface.js';
import type { LibrarySurface } from '../LibrarySurface.js';
import type { IndexedRootView } from '../../../api/generated/schema-types/indexed-root-view.js';
import { createMockHostApi } from '../../plugin-api/testHostApi.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../../state/aiStateStore.js';

async function settle(el: { updateComplete: Promise<unknown> }) {
  for (let i = 0; i < 35; i++) { await Promise.resolve(); await el.updateComplete; }
}
function observe() {
  __feedForTest({ status: { worker: { core: { indexedDocuments: 8, ownSearchableDocuments: 8,
    indexState: 'IDLE', indexHealthy: true }, migration: { migrationState: 'IDLE' } } } as unknown as StatusSnapshot });
}
/** Word search works; no AI is installed, so meaning work waits (the captured no-model enrichment block). */
function observeNoAi() {
  __feedForTest({ status: { worker: { core: { indexedDocuments: 8, ownSearchableDocuments: 8, indexState: 'IDLE', indexHealthy: true },
    migration: { migrationState: 'IDLE' }, enrichment: { embeddingEnabled: false, spladeEnabled: false, nerEnabled: false,
      embeddingDocCount: 8, embeddingPendingCount: 8, embeddingCoveragePercent: 0, spladeDocCount: 8, spladePendingCount: 8,
      spladeCoveragePercent: 0, pendingNerCount: 8, completedNerCount: 0,
      chunk: { chunkDocCount: 2, chunkEmbeddingPendingCount: 2, chunkVectorsReady: false } } },
  readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } as unknown as StatusSnapshot });
}
const root: IndexedRootView = { pathHash: 'a', status: 'indexed', fileCount: 8, failedCount: 0,
  inFlightCount: 0, walkCompleted: true };
async function library(feed: () => void) {
  feed();
  const host = createMockHostApi();
  host.data.fetch = vi.fn(async path => path.startsWith('/api/indexing-roots/substrate')
    ? Response.json({ items: [root] }) : new Response('{}', { status: 503 }));
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = host; el.resolvedPaths = { a: '/Documents' };
  document.body.append(el); await settle(el);
  return el;
}
/** The row's status words and the icon drawn beside them. */
function row(el: LibrarySurface) {
  const shadow = el.shadowRoot!;
  const status = shadow.querySelector('.status-icon');
  expect(status, 'status icon').not.toBeNull();
  const svg = status!.querySelector('svg')!;
  const shape = svg.querySelector('path[d="m9 12 2 2 4-4"]') ? 'check'
    : svg.querySelector('polyline[points="12 6 12 12 16 14"]') ? 'clock'
    : svg.querySelector('path[d="M12 9v4"]') ? 'warning' : 'other';
  return { words: shadow.querySelector('.card-meta')?.textContent?.trim() ?? '', shape, classes: [...status!.classList] };
}

// Nothing here reaches a network: any read past the host's stub fails at once.
beforeEach(() => { vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('No backend in this test'); })); });
afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it('PG3: "Up to date" while meaning work waits for AI carries the check, in the success colour class', async () => {
  const el = await library(observeNoAi);
  const seen = row(el);
  expect(seen.words).toBe('Up to date');
  expect(seen.shape).toBe('check');
  expect(seen.classes).toContain('indexed');
});

it('PG3 (kept): "Up to date" with nothing waiting carries the check', async () => {
  const el = await library(observe);
  const seen = row(el);
  expect(seen.words).toBe('Up to date');
  expect(seen.shape).toBe('check');
  expect(seen.classes).toContain('indexed');
});

it('PG3 (kept): meaning work in progress keeps the clock', async () => {
  const el = await library(observe);
  Object.assign(el, { enrichmentStages: { embedding: true, splade: false, ner: false }, enrichmentBlocked: false,
    roots: [{ ...root, parentDocsTotalEmbedding: 8, parentDocsSettledEmbedding: 4 }] });
  await settle(el);
  const seen = row(el);
  expect(seen.words).toMatch(/^Meaning processing/);
  expect(seen.shape).toBe('clock');
  expect(seen.classes).toContain('pending');
});

it('PG3 (kept): failed meaning work keeps the warning icon', async () => {
  const el = await library(observe);
  Object.assign(el, { enrichmentStages: { embedding: true, splade: false, ner: false }, enrichmentBlocked: false,
    roots: [{ ...root, failedStages: { embedding: true } }] });
  await settle(el);
  const seen = row(el);
  expect(seen.words).toMatch(/^Reading for meaning failed/);
  expect(seen.shape).toBe('warning');
  expect(seen.classes).toContain('unverified');
});

it('PG3 (kept): an unconfirmed reading status keeps the clock and never claims a check', async () => {
  const el = await library(observeNoAi);
  // The folder list's last read failed (the getter reads the shared roots observation).
  Object.defineProperty(el, 'rootsUnconfirmed', { get: () => true });
  el.requestUpdate(); await settle(el);
  const seen = row(el);
  expect(seen.words).toBe('Reading status unconfirmed');
  expect(seen.shape).toBe('clock');
  expect(seen.classes).not.toContain('indexed');
});
