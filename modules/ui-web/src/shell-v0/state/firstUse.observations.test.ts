// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __feedForTest, __feedContactForTest, __resetAiStateForTest, __tickClockForTest, getAiState } from './aiStateStore.js';
import { __resetUserStateForTest } from './UserStateDocument.js';
import { getSuggestedRoots } from '../../api/domains/indexing.js';
const files = import.meta.glob('./firstUse.ts', { eager: true });
const api = Object.values(files)[0] as typeof import('./firstUse.js') | undefined;
function feed(state?: 'fresh' | 'stale' | 'unknown', value = 0) {
  __feedForTest({ status: { worker: { core: { indexState: 'IDLE', indexedDocuments: value, searchableDocuments: value, pendingJobs: 0 },
    ...(state ? { countObservations: { searchable: { state, value } } } : {}) } } as never });
  __feedContactForTest();
}
beforeEach(() => { __resetUserStateForTest(); __resetAiStateForTest(); api?.setFirstUseRoots([], new Map()); });
afterEach(() => { __resetAiStateForTest(); vi.useRealTimers(); vi.unstubAllGlobals(); });
describe('first-use observation freshness', () => {
  it('rejects malformed suggested-root items at the wire boundary', async () => {
    for (const suggestions of [[null], [{ label: 3, path: '/notes' }], [{ label: 'Notes', path: '' }]]) {
      vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ suggestions }), { status: 200 })));
      const rejection: unknown = await getSuggestedRoots('').then(() => null, error => error);
      expect(rejection).toBeInstanceOf(Error);
    }
  });
  it('unavailable, explicitly stale and explicitly unknown counts never license an empty state', () => {
    expect(api, 'one shared first-use projection must exist').toBeDefined();
    for (const state of ['stale', 'unknown'] as const) {
      feed(state);
      expect(api?.getFirstUse()).toMatchObject({ empty: false, visible: false, searchable: null, progress: null });
    }
    __feedForTest({ status: null });
    expect(api?.getFirstUse()).toMatchObject({ empty: false, visible: false, progress: null });
  });
  it('positive observations age out without a guessed zero or a retained progress count', () => {
    expect(api, 'one shared first-use projection must exist').toBeDefined();
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-11-09T10:00:00Z')); feed(undefined, 12);
    expect(api?.getFirstUse()).toMatchObject({ searchable: 12, empty: false });
    vi.advanceTimersByTime(300_000); __tickClockForTest();
    expect(api?.getFirstUse()).toMatchObject({ searchable: null, empty: false, progress: null });
    expect(api?.firstUseFacts(getAiState()).aiActive).toBe(false);
  });
});
