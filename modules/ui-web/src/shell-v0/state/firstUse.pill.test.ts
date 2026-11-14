// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { deriveFrameStatus, type StatusFact } from './frameStatus.js';
import type { AiState } from './aiStateStore.js';
const ai = { phase: 'connected', connection: { reachable: true, lastSuccessMs: 100000, lastContactMs: 100000, consecutiveFailures: 0 },
  verdict: { kind: 'operational', severity: 'info', reasons: [] }, stability: { kind: 'settled' },
  aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
  activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
  status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } },
  indexingStatusLive: true, snapshotLive: true, episodeMaxPendingJobs: 0, enrichSettleSamples: [],
} as unknown as AiState;
const setup = { visible: true, known: true, currentStep: 1 };
describe('Getting started calm pill', () => {
  it('uses the calm slot only; all urgent states and finished work keep precedence', () => {
    const derive = (extra: Partial<Parameters<typeof deriveFrameStatus>[0]> = {}) => deriveFrameStatus({ ai, now: 100000, contactLostAt: null, gettingStarted: setup, ...extra }).pill;
    expect(derive({ ai: { ...ai, verdict: { kind: 'unknown', severity: 'info', reasons: [] } } as unknown as AiState }).label).toBe('Checking search status · last seen just now · checking');
    expect(derive({ gettingStarted: { ...setup, currentStep: 2 } }).label).toBe('Up to date');
    expect(derive({ gettingStarted: { ...setup, currentStep: 3 } }).label).toBe('Up to date');
    expect(derive({ gettingStarted: { ...setup, known: false }, ai: null }).kind).toBe('unknown');
    expect(derive().label).toBe('Up to date');
    for (const [section, kind] of [['needs', 'needs'], ['problems', 'problems'], ['running', 'working']] as const) {
      const row: StatusFact = { id: section, section, title: section };
      expect(derive({ conditions: [row] }).kind).toBe(kind);
    }
    expect(derive({ ai: { ...ai, connection: { ...ai.connection, reachable: false } }, contactLostAt: 0 }).kind).toBe('unreachable');
    expect(derive({ ai: { ...ai, verdict: { kind: 'degraded', severity: 'warn', reasons: ['index.schema_open_refused'] } } }).kind).toBe('limited');
    expect(derive({ finished: { title: 'Search is up to date', at: 99999 } }).kind).toBe('finished');
    expect(derive({ gettingStarted: { visible: false, known: true, currentStep: 1 } }).label).toBe('Up to date');
  });
});
