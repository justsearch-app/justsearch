// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __feedForTest, __feedContactForTest, __resetAiStateForTest, __tickClockForTest } from './aiStateStore.js';
import { __resetUserStateForTest } from './UserStateDocument.js';
import { getFirstUse, setFirstUseRoots } from './firstUse.js';

beforeEach(() => {
  __resetUserStateForTest(); __resetAiStateForTest();
  setFirstUseRoots([{ pathHash: 'notes' }], new Map([['notes', '/notes']]));
});
afterEach(() => { __resetAiStateForTest(); vi.useRealTimers(); });
function observe(indexState: string, pendingJobs?: number, paused = false, state: 'fresh' | 'stale' | 'unknown' = 'fresh') {
  __feedForTest({ status: {
    worker: { core: { indexState, indexedDocuments: 3, searchableDocuments: 3,
      ...(pendingJobs === undefined ? {} : { pendingJobs }) }, countObservations: { searchable: { state, value: 3 } } },
    ...(paused ? { operating: { indexing: { state: 'PAUSED', reasons: ['user_paused'], untilEpochMs: null, waitingForResume: true } } } : {}),
  } as never });
  __feedContactForTest();
}
describe('SB6 live reading observations', () => {
  it.each([undefined, 0])('reports still reading during INDEXING with queue %s', pending => {
    observe('INDEXING', pending);
    expect(getFirstUse().progress).toBe('3 files searchable so far · still reading');
  });
  it('reports the observed queue ahead of indeterminate work', () => {
    observe('INDEXING', 8);
    expect(getFirstUse().progress).toBe('3 files searchable so far · 8 waiting to be read');
  });
  it.each([undefined, 0])('idle with queue %s reports no reading', pending => {
    observe('IDLE', pending);
    expect(getFirstUse().progress).toBe('3 files searchable so far');
  });
  it.each([undefined, 0])('paused with queue %s reports no reading despite the last INDEXING state', pending => {
    observe('INDEXING', pending, true);
    expect(getFirstUse().progress).toBe('3 files searchable so far');
  });
  it('a paused observed queue still describes waiting files', () => {
    observe('INDEXING', 8, true);
    expect(getFirstUse().progress).toBe('3 files searchable so far · 8 waiting to be read');
  });
  it.each(['stale', 'unknown'] as const)('%s counts show no progress even with observed work', state => {
    observe('INDEXING', 8, false, state);
    expect(getFirstUse().progress).toBeNull();
  });
  it('an expired status shows no retained progress', () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-11-10T00:00:00Z'));
    observe('INDEXING', 8);
    expect(getFirstUse().progress).toBe('3 files searchable so far · 8 waiting to be read');
    vi.advanceTimersByTime(300_000); __tickClockForTest();
    expect(getFirstUse().progress).toBeNull();
  });
  it('an unavailable worker shows no progress', () => {
    observe('UNAVAILABLE', 0);
    expect(getFirstUse().progress).toBeNull();
  });
});
