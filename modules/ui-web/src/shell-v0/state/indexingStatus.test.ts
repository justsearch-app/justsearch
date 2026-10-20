// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import type { StatusResponse } from '../../api/generated/index.js';
import { selectIndexingProgress } from './indexingProgress.js';
import { statusResponseSchema } from '../../api/generated/schema-types/status-response.js';
import statusFixture from '../../api/__fixtures__/status-response-live.json';

function snapshot(jobs: number, pending: number, reasons: string[] = [], gpuBound?: boolean): StatusResponse {
  return {
    worker: {
      core: { indexState: jobs ? 'INDEXING' : 'IDLE', pendingJobs: jobs },
      enrichment: { embeddingEnabled: true, embeddingDocCount: 1000, embeddingPendingCount: pending,
        spladeEnabled: false, nerEnabled: false, backfillGpuBound: gpuBound },
    },
    operating: { indexing: { state: 'PAUSED', reasons }, freshness: { staleSources: [] } },
  } as unknown as StatusResponse;
}

// Read through the existing public selector: on the before-state the missing projection fails an
// assertion, rather than making the suite fail to import a new module or export.
function project(status: StatusResponse | null, live = true) {
  return selectIndexingProgress(status, live, 1000, []);
}
function activity(status: StatusResponse | null, live = true): unknown {
  return (project(status, live) as unknown as { activity?: unknown }).activity;
}

describe('WP3.3 shared indexing activity', () => {
  it('S33.2 the status validator preserves observed GPU, CPU and unknown placement', () => {
    for (const placement of [true, false, null, undefined]) {
      const wire = { ...statusFixture, worker: { ...statusFixture.worker,
        enrichment: { ...statusFixture.worker.enrichment,
          backfillGpuBound: placement } } };
      const parsed = statusResponseSchema.safeParse(wire);
      expect(parsed.success).toBe(true);
      if (parsed.success) expect(parsed.data.worker?.enrichment?.backfillGpuBound).toBe(placement);
    }
  });
  it('S33.1 names energy-deferred enrichment while text indexing continues', () => {
    expect(activity(snapshot(8, 80, ['energy_saver']))).toMatchObject({
      summary: 'Text indexing continues',
      detail: 'Enrichment deferred to save energy.',
      confirmed: true,
      enrichmentDeferred: true,
    });
    expect(activity(snapshot(0, 80, ['energy_saver']))).toMatchObject({
      summary: 'Enrichment deferred', detail: 'Enrichment deferred to save energy.',
    });
  });

  it('S33.2 distinguishes GPU-bound enrichment from CPU work under game and external GPU holds', () => {
    for (const [reason, cause] of [['game_mode', 'a game is running'], ['gpu_busy_external', 'another app is using the GPU']]) {
      expect(activity(snapshot(4, 80, [reason!], true))).toMatchObject({
        summary: 'Text indexing continues',
        detail: `Enrichment deferred while ${cause} (GPU bulk hold).`,
        enrichmentDeferred: true,
      });
      expect(activity(snapshot(0, 80, [reason!], false))).toMatchObject({
        summary: 'Search is ready — still improving',
        detail: `GPU bulk work deferred while ${cause}. CPU enrichment continues.`,
        enrichmentDeferred: false,
      });
      expect(activity(snapshot(0, 80, [reason!]))).toMatchObject({
        detail: `GPU bulk work deferred while ${cause}; enrichment placement unconfirmed.`,
        enrichmentDeferred: false,
      });
    }
  });

  it('S33.3 names only a present chat reason and S33.4 clears it on the next observation', () => {
    expect(activity(snapshot(0, 80, ['chat_generating'], true))).toMatchObject({
      detail: 'Enrichment shares GPU time with chat.', enrichmentDeferred: false,
    });
    expect(activity(snapshot(0, 80, [], true))).toMatchObject({ detail: null, enrichmentDeferred: false });
  });

  it('S33.4 marks stale, unavailable and missing worker observations unconfirmed', () => {
    for (const status of [snapshot(8, 80, ['energy_saver']), snapshot(0, 0), null]) {
      expect(activity(status, false)).toMatchObject({
        summary: 'Indexing status unconfirmed', detail: null, confirmed: false,
      });
    }
    const stale = snapshot(0, 80, ['game_mode'], true);
    stale.operating!.freshness.staleSources = ['indexing'];
    expect(activity(stale)).toMatchObject({ summary: 'Indexing status unconfirmed', confirmed: false });
    expect(activity(null)).toMatchObject({ summary: 'Indexing status unconfirmed', confirmed: false });
    for (const indexState of ['UNAVAILABLE', 'FAILED']) {
      const unavailable = snapshot(0, 0);
      unavailable.worker!.core!.indexState = indexState;
      expect(activity(unavailable)).toMatchObject({ summary: 'Indexing status unconfirmed', confirmed: false });
    }
  });

  it('S33.1 does not call an empty file queue Ready while energy-deferred enrichment waits even with wire IDLE', () => {
    const status = snapshot(0, 80, ['energy_saver']);
    status.operating!.indexing!.state = 'IDLE';
    expect(activity(status)).toMatchObject({ summary: 'Enrichment deferred', enrichmentDeferred: true });
  });

  it('S33.5 caps indexing with a positive tail', () => {
    expect(project(snapshot(1, 0)).indexingPercent).toBe(99);
  });

  it('S33.5 caps enrichment with denominator-less pending work', () => {
    const status = snapshot(0, 0);
    status.worker!.enrichment!.nerEnabled = true;
    status.worker!.enrichment!.pendingNerCount = 0;
    // A pending stage with no counted denominator must still prevent a completion figure.
    status.worker!.enrichment!.embeddingPendingCount = 1;
    status.worker!.enrichment!.embeddingDocCount = 0;
    status.worker!.enrichment!.completedNerCount = 1000;
    expect(project(status).enrichingPercent).toBe(99);
  });

  it('S33.2 reports simultaneous reasons without losing the energy hold on CPU', () => {
    const projected = activity(snapshot(4, 80, ['game_mode', 'gpu_busy_external', 'energy_saver'], false));
    expect(projected).toMatchObject({ summary: 'Text indexing continues', enrichmentDeferred: true });
    expect((projected as { detail: string }).detail).toContain('save energy');
    expect((projected as { detail: string }).detail).toContain('a game is running');
    expect((projected as { detail: string }).detail).toContain('another app is using the GPU');
    expect((projected as { detail: string }).detail).not.toContain('CPU enrichment continues');
  });

  it('S33.5 withholds an invented starting percentage for indexing and enrichment alike', () => {
    const status = snapshot(1000, 1000);
    expect(project(status).indexingPercent).toBeNull();
    expect(project(status).enrichingPercent).toBeNull();
  });
});
