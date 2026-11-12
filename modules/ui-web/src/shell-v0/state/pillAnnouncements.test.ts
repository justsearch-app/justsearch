// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// FC10 — the status pill's screen-reader text (its hidden live regions) follows the same rule as the
// visible pill. On a fresh install where nobody asked for AI, nothing announces "Semantic search
// waiting for AI install" or any Needs-you wording, after first use and after a reload;
// during an Engine outage the spoken text says what the visible pill says ("Checking search status",
// then "Can't reach the engine"), never "Catching up…". The status and the AI install status of the
// fresh install are verbatim from a real Engine without models (as in unrequestedAi.test.ts); the
// search verdict and the AI verdict come from the real derivations (the store, computeAiEngineVerdict).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __feedForTest, __resetAiStateForTest, getAiState, getStatusReceivedAtMs, refreshStatusNow, startAiStateStore, type AiState, type InstallStatus, type StatusSnapshot } from './aiStateStore.js';
import { __resetEngineLossForTest } from './engineLoss.js';
import { computeAiEngineVerdict } from './aiVerdict.js';
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

/** What the pill shows, and what each of its hidden live regions says. */
async function pillWith(state: AiState | null): Promise<{ shown: string; spoken: string[] }> {
  const pill = new StatusPill();
  document.body.append(pill);
  try {
    await pill.updateComplete;
    pill.status.ai = state; pill.status.refresh();
    for (let i = 0; i < 4; i++) await Promise.resolve();
    await pill.updateComplete;
    const root = pill.shadowRoot!;
    const shown = root.querySelector('.trigger')?.getAttribute('aria-label') ?? '';
    const spoken = [...root.querySelectorAll('jf-system-notice, [aria-live]')]
      .map((region) => (region.textContent ?? '').replace(/\s+/g, ' ').trim()).filter(Boolean);
    return { shown, spoken };
  } finally { pill.remove(); }
}

const NEEDS_YOU = /semantic search waiting for AI install|needs? you/i;

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
  localStorage.clear();
});
afterEach(() => { __resetAiStateForTest(); vi.unstubAllGlobals(); });

describe('FC10 a fresh install where nobody asked for AI says nothing of AI it did not ask for', () => {
  it.each(['pending', 'empty'] as const)('the pill says keyword search is ready (semantic queue %s), and nothing spoken says Needs you or waiting for AI', async (queue) => {
    const { shown, spoken } = await pillWith(observe(NOT_INSTALLED, { queue }));
    expect(shown).toBe('Keyword search ready');
    for (const text of spoken) expect(text, `spoken: ${text}`).not.toMatch(NEEDS_YOU);
  });

  it('after a reload, before the AI install status is read again: the pill and its spoken status both say it is checking', async () => {
    const { shown, spoken } = await pillWith(observe(null));
    expect(shown).toContain('Checking search status');
    for (const text of spoken) expect(text, `spoken: ${text}`).not.toMatch(NEEDS_YOU);
    expect(spoken, 'no search verdict the pill does not show').not.toContain('Service degraded');
    expect(spoken).toContain('Checking search status');
  });

  it.each([
    ['failed', FAILED],
    ['was interrupted', INTERRUPTED],
  ] as const)('an AI install the person started that %s still needs them, shown and spoken alike', async (_case, install) => {
    const { shown, spoken } = await pillWith(observe(install));
    expect(shown).toBe('1 needs you');
    expect(spoken.some((text) => /needs? you|AI install failed|Install Failed|Download Paused|Semantic search degraded|Service degraded/i.test(text)),
      `something spoken says so: ${spoken.join(' | ')}`).toBe(true);
  });
});

describe('FC10 during an Engine outage, the spoken status says what the pill shows', () => {
  type Engine = 'up' | 'refused';
  let engine: Engine = 'up';
  const json = (value: unknown, code = 200) => new Response(JSON.stringify(value), { status: code, headers: { 'Content-Type': 'application/json' } });
  /** A healthy Engine with AI installed and running, as the status poller reads it. */
  async function answer(path: string): Promise<Response> {
    if (engine === 'refused') throw new TypeError('Failed to fetch');
    if (path.includes('/api/status')) return json({ status: 'ok',
      worker: { core: { indexState: 'IDLE', indexHealthy: true, indexedDocuments: 5, searchableDocuments: 5, pendingJobs: 0 } },
      readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] }, aiFeatures: { state: 'READY', reasonCodes: [] } } } });
    if (path.includes('/api/inference')) return json({ mode: 'online', starting: false, available: true });
    if (path.includes('/api/ai/install/status')) return json({ state: 'idle', installedFully: true });
    return json({}, 404);
  }

  beforeEach(() => {
    __resetAiStateForTest(); __resetEngineLossForTest(); engine = 'up';
    vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-11-12T12:00:00Z'));
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => answer(new URL(String(input), 'http://localhost').pathname)));
  });
  afterEach(() => { __resetEngineLossForTest(); vi.useRealTimers(); });

  const verdictSpoken = (pill: StatusPill): string | null =>
    pill.shadowRoot!.querySelector('[data-testid="verdict-announcer"]')?.textContent?.trim() ?? null;

  /** The connected pill, after the store's latest read: what it shows and what its live regions say. */
  async function read(pill: StatusPill): Promise<{ shown: string; spoken: string[] }> {
    for (let i = 0; i < 10; i++) { await Promise.resolve(); await new Promise((resolve) => setTimeout(resolve, 0)); }
    pill.status.refresh();
    await pill.updateComplete;
    const root = pill.shadowRoot!;
    return { shown: root.querySelector('.trigger')?.getAttribute('aria-label') ?? '',
      spoken: [...root.querySelectorAll('jf-system-notice, [aria-live]')].map((r) => (r.textContent ?? '').replace(/\s+/g, ' ').trim()).filter(Boolean) };
  }

  it('the Engine stops answering: the spoken search status says what the pill shows, never "Catching up…"', async () => {
    startAiStateStore('');
    await vi.waitFor(() => expect(getStatusReceivedAtMs()).not.toBeNull());
    const pill = new StatusPill();
    document.body.append(pill);
    try {
      await read(pill);
      vi.setSystemTime(Date.now() + 3_000);
      engine = 'refused';
      await refreshStatusNow();
      for (const later of [12_000, 60_000]) {
        vi.setSystemTime(Date.now() + later);
        await refreshStatusNow();
        const { shown, spoken } = await read(pill);
        const status = /Can't reach the engine/.test(shown) ? "Can't reach the engine" : /Checking search status/.test(shown) ? 'Checking search status' : null;
        expect(status, `the pill no longer claims a current state: ${shown}`).not.toBeNull();
        // No search verdict the pill does not show. (A live region keeps the words it last said; they
        // are not said again, so an earlier indexing announcement is not checked here.)
        for (const text of spoken) expect(text, `spoken beside "${shown}": ${text}`).not.toMatch(/Catching up|Search available|Service degraded/);
        expect(verdictSpoken(pill), `the search status spoken beside "${shown}"`).toBe(status);
      }
    } finally { pill.remove(); }
  });
});
