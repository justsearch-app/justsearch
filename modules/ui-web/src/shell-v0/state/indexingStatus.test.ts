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
        spladeEnabled: false, nerEnabled: false },
      gpu: { embedOrtCuda: { available: gpuBound, attempted: true } },
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
  it('S33.2 the strict status shape adds no placement key that old clients reject', () => {
    const enrichment = statusResponseSchema.shape.worker.unwrap().shape.enrichment.unwrap();
    expect(Object.keys(enrichment.shape)).not.toContain('backfillGpuBound');
    expect(statusResponseSchema.safeParse(statusFixture).success).toBe(true);
  });

  it('S33.1 scopes energy deferral to idle enrichment while sparse work can continue between files', () => {
    expect(activity(snapshot(8, 80, ['energy_saver']))).toMatchObject({
      summary: 'Text indexing continues',
      detail: 'Idle enrichment deferred to save energy. Sparse enrichment can continue between files.',
      confirmed: true, enrichmentDeferred: true,
    });
    expect(activity(snapshot(0, 80, ['energy_saver']))).toMatchObject({
      summary: 'Enrichment deferred', detail: 'Idle enrichment deferred to save energy.',
    });
  });

  it('S33.2 scopes GPU holds to GPU idle work and all between-file sparse work without guessing placement', () => {
    for (const [reason, cause] of [['game_mode', 'a game is running'], ['gpu_busy_external', 'another app is using the GPU']]) {
      for (const placement of [true, false, undefined]) {
        for (const jobs of [0, 4]) {
          expect(activity(snapshot(jobs, 80, [reason!], placement))).toMatchObject({
            summary: jobs ? 'Text indexing continues' : 'Enrichment pending',
            detail: `GPU-bound idle enrichment deferred while ${cause}. CPU-only idle enrichment can continue. Sparse enrichment between files also waits, including on CPU.`,
            enrichmentDeferred: true,
          });
        }
      }
    }
  });

  it('S33.3 names only a present chat reason and S33.4 clears it on the next observation', () => {
    expect(activity(snapshot(0, 80, ['chat_generating'], true))).toMatchObject({
      detail: 'Indexing shares time with chat.', enrichmentDeferred: false,
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
