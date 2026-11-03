// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it } from 'vitest';
import { StatusPanel } from './StatusPanel.js';
import { deriveFrameStatus } from '../state/frameStatus.js';
import { indexingCountsLine, indexingCountsLabel, enrichingCountsLine, enrichingCountsLabel } from '../state/statusProgress.js';
import { selectIndexingProgress, type IndexingProgress } from '../state/indexingProgress.js';
import type { AiState } from '../state/aiStateStore.js';
import type { StatusResponse } from '../../api/generated/index.js';
const raw = (worker: unknown) => ({ worker }) as StatusResponse;
function model(worker: unknown, extra: Partial<AiState> = {}) {
  return deriveFrameStatus({ now: 100000, contactLostAt: null, ai: {
    phase: 'connected', connection: { reachable: true, lastSuccessMs: 100000, lastContactMs: 100000, consecutiveFailures: 0 },
    verdict: { kind: 'operational', severity: 'info', reasons: [] }, stability: { kind: 'settled' },
    aiEngine: { kind: 'offline' }, activity: { state: 'idle' }, indexingStatusLive: true,
    episodeMaxPendingJobs: 1600, enrichSettleSamples: [], status: raw(worker), ...extra,
  } as AiState });
}
async function render(m: ReturnType<typeof model>) {
  const panel = new StatusPanel(); panel.model = m; document.body.append(panel); await panel.updateComplete; return panel;
}
afterEach(() => document.body.replaceChildren());
describe('Running (moved from the task tray and indexing overlay)', () => {
  it.each([[1600, null], [400, 75]])('an observed drain supplies %s remaining and the honest fraction %s', async (jobs, percent) => {
    const m = model({ core: { indexState: 'INDEXING', pendingJobs: jobs }, enrichment: {} });
    const row = m.sections.running.find(r => r.id === 'indexing')!;
    expect(row.percent).toBe(percent); expect(row.detail).toContain(`${jobs.toLocaleString('en-US')} files remaining`);
    const panel = await render(m); const bar = panel.shadowRoot!.querySelector('progress')!;
    if (percent === null) expect(bar.hasAttribute('value')).toBe(false); else expect(bar.value).toBe(percent);
  });
  it('enrichment stays discoverable without SSE tasks and exposes its real stage fractions', async () => {
    const m = model({ core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {
      embeddingEnabled: true, embeddingAvailable: true, embeddingDocCount: 100, embeddingPendingCount: 40,
      spladeEnabled: false, nerEnabled: false,
    } });
    expect(m.progress.phase).toBe('enriching');
    const row = m.sections.running.find(r => r.id === 'indexing')!;
    expect(row.percent).toBe(60); expect(row.detail).toContain('60%');
    expect(row.details!.find(r => r.id === 'stage:embedding')!.detail).toBe('60 / 100');
    const panel = await render(m); const disclosure = [...panel.shadowRoot!.querySelectorAll('button')].find(b => b.textContent === 'Details')!;
    disclosure.click(); await panel.updateComplete;
    expect(panel.shadowRoot!.querySelector('[data-detail="stage:embedding"]')!.textContent).toContain('60 / 100');
    expect(panel.shadowRoot!.querySelector('[data-detail="stage:splade"]')).toBeNull();
  });
  it('blocked meaning search never announces completion or invents an enrichment fraction', async () => {
    const m = model({ core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {
      embeddingEnabled: false, spladeEnabled: false, nerEnabled: false,
      embeddingDocCount: 5, embeddingPendingCount: 5, chunk: { chunkDocCount: 2, chunkEmbeddingPendingCount: 2, chunkVectorsReady: false },
    } });
    expect(m.progress.phase).toBe('blocked'); expect(m.pill.kind).toBe('needs');
    expect(m.sections.needs[0]!.percent).toBeNull();
    const panel = await render(m); expect(panel.shadowRoot!.querySelector('progress')).toBeNull();
    expect(panel.textContent).not.toContain('Everything is indexed and enriched');
  });
  it('persisted vectors with the model removed do not fabricate unfinished work', () => {
    const m = model({ core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {
      embeddingEnabled: false, spladeEnabled: false, nerEnabled: false, embeddingDocCount: 100, embeddingPendingCount: 0,
      chunk: { chunkDocCount: 30, chunkEmbeddingPendingCount: 0, chunkVectorsReady: true },
    } });
    expect(m.progress.phase).toBe('ready'); expect(m.sections.running).toEqual([]); expect(m.pill.kind).toBe('calm');
  });
  it('stale retained indexing suppresses bars and qualifies its last-known activity', async () => {
    const m = model({ core: { indexState: 'INDEXING', pendingJobs: 400 }, enrichment: {} }, { phase: 'stale', indexingStatusLive: false });
    const row = m.sections.running.find(r => r.id === 'indexing')!;
    expect(row.stale).toBe(true); expect(row.title).toContain('Indexing'); expect(row.percent).toBeNull();
    const panel = await render(m); expect(panel.shadowRoot!.querySelector('progress')).toBeNull();
    expect(panel.shadowRoot!.textContent).toContain('Last seen'); expect(m.pill.label).toContain('last seen');
  });
  it('a missing observation provides no counts, percentage or ETA', async () => {
    const m = model(undefined, { status: null });
    expect(m.progress.phase).toBe('unknown'); expect(m.pill.kind).toBe('unknown');
    const panel = await render(m); expect(panel.shadowRoot!.querySelector('progress')).toBeNull();
    expect(panel.shadowRoot!.textContent).not.toContain('0 files');
  });
  it('stage failures and missing artifacts are never presented as finished', () => {
    const m = model({ core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: { embeddingEnabled: true, embeddingAvailable: true,
      embeddingDocCount: 100, embeddingPendingCount: 10, embeddingFailedCount: 5, spladeEnabled: false, nerEnabled: false } });
    const details = m.sections.running.find(r => r.id === 'indexing')!.details!;
    expect(details.find(r => r.id === 'stage:embedding')!.detail).not.toContain('✓');
  });
});
const progress = (extra: Partial<IndexingProgress>): IndexingProgress => ({ ...selectIndexingProgress(null, false, 0, []), ...extra });
describe('progress readout facts', () => {
  it.each([[null, '412 files remaining'], [8 * 1024 ** 3, '412 files remaining · 8.00 GB']])('only a faithful byte weight is shown (%s)', (pendingBytes, expected) => {
    expect(indexingCountsLine(progress({ jobsPending: 412, pendingBytes }))).toBe(expected);
  });
  it('indexing and meaning-search estimates use their own rates and retain the indicative qualifier', () => {
    const p = progress({ jobsPending: 412, etaSeconds: 120, enrichingPercent: 64, enrichingEtaSeconds: 300 });
    expect(indexingCountsLine(p)).toBe('412 files remaining · ~2m left');
    expect(indexingCountsLabel(p)).toContain('at the current rate');
    expect(enrichingCountsLine(p)).toContain('64%'); expect(enrichingCountsLine(p)).toContain('~5m left');
    expect(enrichingCountsLabel(p)).toContain('at the current rate');
  });
  it('unknown fractions and estimates leave no invented placeholders', () => {
    const p = progress({ enrichingPercent: null, enrichingEtaSeconds: null, etaSeconds: null });
    expect(enrichingCountsLine(p)).not.toMatch(/%|~|left/); expect(enrichingCountsLabel(p)).toBeNull(); expect(indexingCountsLabel(p)).toBeNull();
  });
});
