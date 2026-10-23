// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import {
  __feedForTest, __resetAiStateForTest, getAiState, startAiStateStore, subscribeAiState,
  type AiState, type StatusSnapshot,
} from './aiStateStore.js';
import { bumpOriginContact } from './originContact.js';
import { selectIndexingProgress } from './indexingProgress.js';

// Keep transport quiet so only the store's real staleness interval can announce expiry.
vi.mock('../utils/statusPoll.js', () => ({
  subscribeStatus: () => () => {}, setStatusApiBase: () => {}, refreshStatusNow: async () => {},
}));
vi.mock('../utils/inferencePoll.js', () => ({
  subscribeInference: () => () => {}, setInferenceApiBase: () => {},
}));
vi.mock('../utils/aiInstallPoll.js', async (original) => ({
  ...await original<typeof import('../utils/aiInstallPoll.js')>(),
  subscribeAiInstall: () => () => {},
}));

function observation(sampledAt?: number, staleAfterMs = 30_000): StatusSnapshot {
  return {
    worker: { core: { indexState: 'IDLE', pendingJobs: 0, indexHealthy: true } },
    ...(sampledAt === undefined ? {} : { meta: { workerRpcAtMs: sampledAt, workerRpcStale: false } }),
    operating: { freshness: { staleAfterMs, staleSources: [] } },
  } as unknown as StatusSnapshot;
}
function activity(state: AiState) {
  // Fall back on the old interface when this check is transplanted into the before-state.
  const live = (state as AiState & { indexingStatusLive?: boolean }).indexingStatusLive ?? state.snapshotLive;
  return selectIndexingProgress(state.status, live, state.episodeMaxPendingJobs, state.enrichSettleSamples).activity
    ?? { confirmed: undefined, summary: undefined };
}
beforeEach(() => { vi.useFakeTimers(); __resetAiStateForTest(); });
afterEach(async () => {
  __resetAiStateForTest();
  await Promise.resolve();
  vi.useRealTimers();
});

it('S33.4 the store timer expires the worker observation while SSE alone keeps the Engine reachable and fresh status restores it', async () => {
  const now = Date.now();
  startAiStateStore('');
  __feedForTest({ status: observation(now - 7_000) });
  const seen: boolean[] = [];
  const unsubscribe = subscribeAiState(state => seen.push(activity(state).confirmed));
  try {
    expect(activity(getAiState()).confirmed).toBe(true);
    // No status or inference updates. SSE stamps are deliberately nonreactive, as in production.
    for (let i = 0; i < 4; i++) {
      bumpOriginContact();
      await vi.advanceTimersByTimeAsync(5_000);
    }
    expect(activity(getAiState()).confirmed).toBe(true);
    bumpOriginContact();
    await vi.advanceTimersByTimeAsync(5_000);
    expect(getAiState().snapshotLive).toBe(true);
    expect(activity(getAiState())).toMatchObject({ confirmed: false, summary: 'Indexing status unconfirmed' });
    expect(seen.at(-1), 'subscribers receive expiry without a new poll').toBe(false);

    __feedForTest({ status: observation(Date.now()) });
    await Promise.resolve();
    expect(activity(getAiState()).confirmed).toBe(true);
    expect(seen.at(-1)).toBe(true);
  } finally { unsubscribe(); }
});

it('S33.4 a status without a worker timestamp expires by receipt age using its reported freshness window', async () => {
  startAiStateStore('');
  __feedForTest({ status: observation(undefined, 10_000) });
  expect(activity(getAiState()).confirmed).toBe(true);
  await vi.advanceTimersByTimeAsync(10_000);
  expect(activity(getAiState()).confirmed).toBe(true);
  bumpOriginContact();
  await vi.advanceTimersByTimeAsync(5_000);
  expect(getAiState().snapshotLive).toBe(true);
  expect(activity(getAiState()).confirmed).toBe(false);
});
