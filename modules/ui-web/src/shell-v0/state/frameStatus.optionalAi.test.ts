// SPDX-License-Identifier: Apache-2.0
// FA3 — AI is optional: semantic work waiting for AI models nobody asked for is not "Needs you".
import { describe, expect, it } from 'vitest';
import { deriveFrameStatus } from './frameStatus.js';
import type { AiState } from './aiStateStore.js';

type Engine = 'not_installed' | 'connecting' | 'installing' | 'starting' | 'install_failed' | 'paused' | 'offline';

/** A fresh install's status: keyword index done, its documents waiting for semantic vectors. */
function ai(engine: Engine, enrichment: Record<string, unknown> = {}, install: Record<string, unknown> | null = null): AiState {
  return {
    phase: 'connected', connection: { reachable: true, lastSuccessMs: 100000, lastContactMs: 100000, consecutiveFailures: 0 },
    verdict: { kind: 'operational', severity: 'info', reasons: [] }, stability: { kind: 'settled' },
    aiEngine: { kind: engine, stability: { kind: 'settled' }, installFailure: engine === 'install_failed' ? 'Download failed' : null },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0, indexedDocuments: 7, searchableDocuments: 7 }, enrichment: {
      embeddingEnabled: false, spladeEnabled: false, nerEnabled: false, embeddingDocCount: 7, embeddingPendingCount: 7,
      chunk: { chunkDocCount: 3, chunkEmbeddingPendingCount: 3, chunkVectorsReady: false }, ...enrichment } } },
    installStatus: install, installObservationLive: install !== null,
    indexingStatusLive: true, snapshotLive: true, episodeMaxPendingJobs: 0, enrichSettleSamples: [],
  } as unknown as AiState;
}
const firstUse = { visible: true, known: true, currentStep: 1 };
const derive = (state: AiState, gettingStarted = firstUse) => deriveFrameStatus({ ai: state, now: 100000, contactLostAt: null, gettingStarted });

describe('FA3 optional AI and the status pill', () => {
  it('fresh install, no AI models, nothing asked of AI: the pill shows first-use progress, nothing needs the person', () => {
    for (const engine of ['not_installed', 'connecting'] as const) {
      const model = derive(ai(engine));
      expect(model.progress.phase).toBe('blocked');
      expect(model.pill.label, engine).toBe('Getting started · 1 of 3');
      expect(model.sections.needs, engine).toEqual([]);
    }
  });

  it('after first use, keyword search reads as ready, never "Up to date" over missing semantic coverage', () => {
    const model = derive(ai('not_installed'), { visible: false, known: true, currentStep: 3 });
    expect(model.pill).toEqual({ kind: 'calm', label: 'Keyword search ready', stale: false });
  });

  it('an AI install the person started shows its progress, not "Needs you", while it runs', () => {
    const model = derive(ai('installing', {}, { state: 'running', paused: false, totalBytes: 100, downloadedBytes: 40 }));
    expect(model.pill.kind).toBe('working');
    expect(model.pill.label).toBe('Installing AI models');
    expect(model.sections.needs).toEqual([]);
  });
});

describe('FA3 what still needs the person', () => {
  it.each([
    ['an AI install they started that failed', ai('install_failed')],
    ['an interrupted install left waiting', ai('paused')],
    ['installed AI models that are not running', ai('offline')],
    ['semantic search turned on that cannot run', ai('not_installed', { embeddingEnabled: true, embeddingAvailable: false })],
    ['another enabled producer that cannot run', ai('not_installed', { spladeEnabled: true, spladeAvailable: false, spladePendingCount: 4 })],
  ])('%s shows as needing them', (_case, state) => {
    const model = derive(state);
    expect(model.pill.kind).toBe('needs');
    expect(model.sections.needs.map(row => row.id)).toContain('indexing');
  });
});
