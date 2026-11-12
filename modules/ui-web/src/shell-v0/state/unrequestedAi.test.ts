// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// FA3a / FA3b / FA3c — the status pill and the search banner over what a real Engine without AI
// models reports. The status and the
// AI install status are verbatim from a fresh install (own isolated stack built from 448edc33c3, no
// models, 2026-11-12); the search verdict, the indexing progress and the AI verdict come from the
// real derivations (the store and computeAiEngineVerdict), not from hand-made verdicts.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { deriveFrameStatus } from './frameStatus.js';
import { __feedForTest, __resetAiStateForTest, getAiState, type AiState, type InstallStatus, type StatusSnapshot } from './aiStateStore.js';
import { computeAiEngineVerdict } from './aiVerdict.js';
import { projectSv3Degradation } from '../views/search-v3/sv3-degradation.js';
import { StatusPill } from '../components/StatusPill.js';

/** `/api/status` of the fresh install, the sections the status pill reads. */
const FRESH = {"catchingUp": false, "inference": {"phase": "OFFLINE", "usingExternal": false, "counters": {"softRestarts": 0, "hardRestarts": 0, "transitionsTotal": 0}, "chatEnabledSpec": false, "engineState": "Down", "engineReason": "engine-down", "procedure": "", "leaseHolder": "NONE"}, "readiness": {"composites": {"aiFeatures": {"state": "NOT_READY", "reasonCodes": ["inference.deactivated", "encoders.health.embedding_not_ready"], "stale": false, "maxStalenessMs": 0}, "retrieval": {"state": "DEGRADED", "reasonCodes": ["index.dense_unavailable", "lambdamart.not_configured"], "stale": false, "maxStalenessMs": 0}, "telemetry": {"state": "READY", "reasonCodes": [], "stale": false, "maxStalenessMs": 0}}}, "worker": {"core": {"indexHealthy": true, "indexedDocuments": 5, "pendingJobs": 0, "indexState": "IDLE", "indexSizeBytes": 264, "pendingVduCount": 0, "writerQueueDepth": 0, "writerPendingDocs": 0, "commitCount": 1, "refreshLagMs": 0, "recentJobQueueDepth": [], "recentDocsPerSec": [], "pendingBytes": 0, "pendingUnknownSizeJobs": 0, "searchableDocuments": 5, "indexMaxDoc": 19, "indexNumDocs": 19, "indexedDocumentsUnknown": false, "vduAvailable": false, "ownSearchableDocuments": 0}, "enrichment": {"chunk": {"chunkDocCount": 14, "chunkEmbeddingCompletedCount": 0, "chunkEmbeddingPendingCount": 14, "chunkEmbeddingFailedCount": 0, "chunkVectorCoveragePercent": 0.0, "chunkVectorsReady": false, "chunkSpladeEnabled": false, "chunkSpladeCompletedCount": 0, "chunkSpladePendingCount": 0, "chunkSpladeCoveragePercent": 0.0, "chunkingNotRequired": false, "chunkSpladeDocCount": 0, "chunkSpladeFailedCount": 0}, "embeddingDocCount": 5, "embeddingCompletedCount": 0, "embeddingPendingCount": 5, "embeddingFailedCount": 0, "embeddingCoveragePercent": 0.0, "spladeDocCount": 5, "spladeCompletedCount": 0, "spladePendingCount": 5, "spladeFailedCount": 0, "spladeCoveragePercent": 0.0, "pendingNerCount": 5, "completedNerCount": 0, "failedNerCount": 0, "completeness": [{"stageId": "embed", "tier": "ARTIFACT", "expected": 5, "present": 0, "missing": 5, "failed": 0}, {"stageId": "chunk_embed", "tier": "ARTIFACT", "expected": 14, "present": 0, "missing": 14, "failed": 0}, {"stageId": "splade", "tier": "STATUS", "expected": 5, "present": 0, "missing": 5, "failed": 0}, {"stageId": "ner", "tier": "STATUS", "expected": 5, "present": 0, "missing": 5, "failed": 0}], "completenessKnown": true, "chunkMinChars": 2000, "vectorReadyPercent": 95.0, "enrichmentCompleted": {}, "batchTiming": {"batchCount": {}, "totalMs": {}}, "encoderProfiles": {}, "embeddingEnabled": false, "spladeEnabled": false, "nerEnabled": false, "backfillMode": "idle", "embeddedAndSparseCount": 0, "embeddingAvailable": false, "spladeAvailable": false, "nerAvailable": false, "enrichmentOutcomes": {}}, "migration": {"migrationState": "IDLE", "migrationPaused": false, "migrationSwitchingAgeMs": 0, "migrationSwitchingMaxDurationMs": 1800000}, "failure": {"failedJobs": 0}}, "operating": {"indexing": {"state": "IDLE", "reasons": [], "reasonCode": null, "since": "2026-11-12T03:25:14.353382135Z", "affects": [], "pendingJobs": 0, "agingReleaseAt": null, "sampledAt": "2026-11-12T03:25:26.326Z", "untilEpochMs": null, "waitingForResume": false}}};
/** `/api/ai/install/status` of the same install: nothing installed, nothing started. */
const NOT_INSTALLED: InstallStatus = {"bytesPerSecond": -1, "cancelRequested": false, "currentStage": "", "downloadProfile": "", "downloadedBytes": 0, "errorCode": "", "installedFully": false, "lastError": "", "message": "", "optionalGaps": [{"fileName": "config.json", "packageId": "splade"}, {"fileName": "config.json", "packageId": "reranker"}, {"fileName": "config.json", "packageId": "citation-scorer"}], "packages": [], "paused": false, "pendingRegistryAdditions": [], "phase": "idle", "readyCapabilities": [], "remainingSeconds": -1, "repairNeeded": true, "resumableBytes": 0, "stages": [], "startedAtEpochMs": 0, "state": "idle", "totalBytes": 0, "updatedAtEpochMs": 0};
/** An install the person started that was interrupted: idle, not fully installed, bytes kept to resume. */
const INTERRUPTED: InstallStatus = { ...NOT_INSTALLED, downloadedBytes: 120000, resumableBytes: 120000, startedAtEpochMs: 1794450000000 };
/** An install the person started that failed (a models folder it cannot write). */
const FAILED: InstallStatus = { ...NOT_INSTALLED, state: 'failed', phase: 'failed', errorCode: 'IO_ERROR',
  lastError: 'Could not write to the models folder', startedAtEpochMs: 1794450000000 };

type Patch = { chatOn?: boolean; queue?: 'pending' | 'empty' };

/** The store's state for that status, with the AI verdict for `install` (`null`: not observed yet, a reload). */
function observe(install: InstallStatus | null, patch: Patch = {}): AiState {
  const status = structuredClone(FRESH);
  if (patch.chatOn) status.inference.chatEnabledSpec = true;
  if (patch.queue === 'empty') {
    status.worker.enrichment.embeddingPendingCount = 0;
    status.worker.enrichment.chunk.chunkEmbeddingPendingCount = 0;
  }
  __feedForTest({ status: status as unknown as StatusSnapshot });
  const state = getAiState();
  const aiEngine = computeAiEngineVerdict({ installStatus: install, runtimeStatus: null, runtime: state.runtime,
    reachable: state.connection.reachable, snapshotLive: state.snapshotLive,
    engineState: status.inference.engineState, chatEnabledSpec: status.inference.chatEnabledSpec,
    procedure: status.inference.procedure, engineReason: status.inference.engineReason,
    semanticEnabled: status.worker.enrichment.embeddingEnabled });
  return { ...state, aiEngine, installStatus: install, installObservationLive: install !== null };
}
const afterFirstUse = { visible: false, known: true, currentStep: 3 };
const derive = (state: AiState) => deriveFrameStatus({ ai: state, now: Date.now(), contactLostAt: null, gettingStarted: afterFirstUse });

afterEach(() => __resetAiStateForTest());

describe('FA3a a fresh install whose only gap is AI nobody asked for', () => {
  it('the fixture reproduces what the real page computed: search degraded, AI not installed', () => {
    const state = observe(NOT_INSTALLED);
    expect(state.verdict).toEqual({ kind: 'degraded', severity: 'warn',
      reasons: ['index.dense_unavailable', 'lambdamart.not_configured', 'inference.deactivated', 'encoders.health.embedding_not_ready'] });
    expect(state.aiEngine.kind).toBe('not_installed');
    expect(derive(state).progress.phase).toBe('blocked');
  });

  it.each(['pending', 'empty'] as const)('after first use the pill says keyword search is ready (semantic queue %s), never "Checking"', queue => {
    const model = derive(observe(NOT_INSTALLED, { queue }));
    expect(model.pill).toEqual({ kind: 'calm', label: 'Keyword search ready', stale: false });
    expect(model.sections.needs).toEqual([]);
    expect(model.sections.problems).toEqual([]);
  });

  it('after a reload "Checking" lasts only until the AI install status is observed', () => {
    expect(derive(observe(null)).pill.label).toContain('Checking search status');
    expect(derive(observe(NOT_INSTALLED)).pill.label).toBe('Keyword search ready');
  });

  it('chat switched on without AI models: not calm, and not "Checking" — the person asked for AI', () => {
    const model = derive(observe(NOT_INSTALLED, { chatOn: true }));
    expect(model.pill.kind).toBe('needs');
    expect(model.sections.needs.map(row => row.id)).toEqual(['indexing']);
  });
});

describe('FA3a a reduced search the person asked AI for', () => {
  it('chat switched on, semantic queue empty: one Needs-you row says semantic search is degraded, never "Checking"', () => {
    const model = derive(observe(NOT_INSTALLED, { chatOn: true, queue: 'empty' }));
    expect(model.pill).toMatchObject({ kind: 'needs', label: '1 needs you' });
    expect(model.sections.needs.map(row => [row.id, row.title, row.actions?.[0]?.target]))
      .toEqual([['search-reduced', 'Semantic search degraded', 'core.brain-surface']]);
  });
});

describe('FA3b the search banner above the composer', () => {
  it('says nothing when the only gap is AI nobody asked for (semantic queue pending or empty)', () => {
    expect(projectSv3Degradation(observe(NOT_INSTALLED))).toBeNull();
    expect(projectSv3Degradation(observe(NOT_INSTALLED, { queue: 'empty' }))).toBeNull();
  });

  it.each([
    ['chat is switched on without AI models', NOT_INSTALLED, { chatOn: true }],
    ['the AI install the person started failed', FAILED, {}],
    ['the AI install the person started was interrupted', INTERRUPTED, {}],
  ] as const)('stays when the person asked for AI and it cannot run: %s', (_case, install, patch) => {
    expect(projectSv3Degradation(observe(install, patch))?.headline).toBe('Semantic search degraded.');
  });
});

describe('FA3b the status pill\'s screen-reader announcements', () => {
  async function announced(state: AiState): Promise<string | null> {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
    const pill = new StatusPill();
    document.body.append(pill);
    try {
      await pill.updateComplete;
      pill.status.ai = state; pill.status.refresh();
      for (let i = 0; i < 4; i++) await Promise.resolve();
      await pill.updateComplete;
      return pill.shadowRoot!.querySelector('[data-testid="verdict-announcer"]')?.textContent ?? null;
    } finally { pill.remove(); vi.unstubAllGlobals(); }
  }

  it('announce no search warning when the only gap is AI nobody asked for', async () => {
    expect(await announced(observe(NOT_INSTALLED))).toBeNull();
  });

  it('still announce it when the person asked for AI and it cannot run', async () => {
    expect(await announced(observe(FAILED))).toBe('Service degraded');
  });
});

describe('FA3c an AI install the person started that was interrupted or failed needs them', () => {
  it.each([
    ['interrupted', INTERRUPTED, 'ai-install-paused', 'AI download paused'],
    ['failed', FAILED, 'ai-install-failed', 'AI install failed'],
  ] as const)('%s, with an empty or a pending semantic queue: one Needs-you row', (_case, install, id, title) => {
    for (const queue of ['empty', 'pending'] as const) {
      const model = derive(observe(install, { queue }));
      expect(model.pill, queue).toMatchObject({ kind: 'needs', label: '1 needs you', count: 1 });
      expect(model.sections.needs.map(row => [row.id, row.title]), queue).toEqual([[id, title]]);
      expect(model.sections.needs[0]!.actions?.[0]?.target, queue).toBe('core.brain-surface');
    }
  });

  it('the rows say what happened in the AI verdict\'s own words', () => {
    expect(derive(observe(FAILED)).sections.needs.map(row => row.detail)).toEqual(['Could not write to the models folder']);
    expect(derive(observe(INTERRUPTED)).sections.needs.map(row => row.detail))
      .toEqual([expect.stringContaining('already downloaded is kept on disk')]);
  });
});
