// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FrameStatusController } from './frameStatusController.js';
import { __feedForTest, __feedContactForTest, __resetAiStateForTest } from '../state/aiStateStore.js';
import { __resetUserStateForTest } from '../state/UserStateDocument.js';
import { getFirstUse, hideGettingStarted, reopenGettingStarted, setFirstUseHelpFacet, setFirstUseRoots } from '../state/firstUse.js';

const exact = { facets: { collection: { 'justsearch-help': 5 } }, facetsTruncated: false };
type Request = { signal: AbortSignal; resolve(payload: unknown): void; reject(error: Error): void };
let requests: Request[];
let controller: FrameStatusController;
function poll(total = 7): void {
  __feedForTest({ status: { worker: { core: { indexState: 'IDLE', indexedDocuments: total, searchableDocuments: total, pendingJobs: 0 } } } as never });
  __feedContactForTest();
}
async function flush(): Promise<void> { for (let i = 0; i < 20; i++) await Promise.resolve(); }
async function elapse(ms: number): Promise<void> {
  await vi.advanceTimersByTimeAsync(ms); poll(); await flush();
}
beforeEach(() => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-11-10T10:00:00Z'));
  __resetUserStateForTest(); __resetAiStateForTest(); setFirstUseHelpFacet(null); setFirstUseRoots([], new Map());
  requests = [];
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input).includes('/api/knowledge/collection-facet')) {
      return new Promise<Response>((resolve, reject) => requests.push({ signal: init!.signal!, reject,
        resolve: payload => resolve(new Response(JSON.stringify(payload), { status: 200 })) }));
    }
    return Promise.resolve(new Response(JSON.stringify({ items: [], entries: [] }), { status: 200 }));
  }));
  controller = new FrameStatusController({ apiBase: '', multiplex: null, authorizationHost: null,
    addController() {}, requestUpdate() {} } as never);
  controller.hostConnected(); poll();
});
afterEach(() => {
  controller.hostDisconnected(); __resetAiStateForTest(); __resetUserStateForTest();
  vi.useRealTimers(); vi.unstubAllGlobals();
});

describe('first-use Help observation cadence (SB2)', () => {
  it('keeps a landed count through status polls and a pending refresh, with at most one scan per minute', async () => {
    await flush(); expect(requests).toHaveLength(1);
    requests[0]!.resolve(exact); await flush();
    expect(getFirstUse()).toMatchObject({ known: true, searchable: 2, coreVisible: true });
    for (let i = 0; i < 59; i++) {
      await elapse(1_000);
      expect(getFirstUse()).toMatchObject({ known: true, searchable: 2, coreVisible: true });
      expect(requests).toHaveLength(1);
    }
    await elapse(1_000); expect(requests).toHaveLength(2);
    for (let i = 0; i < 5; i++) {
      await elapse(1_000);
      expect(getFirstUse()).toMatchObject({ known: true, searchable: 2, coreVisible: true });
      expect(requests).toHaveLength(2);
    }
    requests[1]!.resolve(exact); await flush();
    await elapse(59_000); expect(requests).toHaveLength(2);
    await elapse(1_000); expect(requests).toHaveLength(3);
  });
  it('accepts a slow facet response after newer status snapshots without aborting or duplicating it', async () => {
    await flush(); expect(requests).toHaveLength(1);
    for (let i = 0; i < 4; i++) await elapse(2_000);
    expect(requests).toHaveLength(1);
    expect(requests[0]!.signal.aborted).toBe(false);
    expect(getFirstUse()).toMatchObject({ known: false, empty: false, coreVisible: false });
    requests[0]!.resolve(exact); await flush();
    expect(getFirstUse()).toMatchObject({ known: true, searchable: 2, coreVisible: true });
  });
  it.each(['failure', 'truncated'] as const)('a %s refresh makes the count unknown and waits 15 seconds before retrying', async kind => {
    await flush(); requests[0]!.resolve(exact); await flush();
    await elapse(60_000); expect(requests).toHaveLength(2);
    expect(getFirstUse().searchable).toBe(2);
    // The retry delay starts when the answer fails, even if that answer was slow.
    await elapse(5_000);
    if (kind === 'failure') requests[1]!.reject(new Error('offline'));
    else requests[1]!.resolve({ ...exact, facetsTruncated: true });
    await flush();
    expect(getFirstUse()).toMatchObject({ known: false, searchable: null, empty: false, coreVisible: false });
    await elapse(14_999); expect(requests).toHaveLength(2);
    await elapse(1); expect(requests).toHaveLength(3);
    expect(getFirstUse().known).toBe(false);
    requests[2]!.resolve(exact); await flush();
    expect(getFirstUse()).toMatchObject({ known: true, searchable: 2, coreVisible: true });
  });
  it('aborts only when Help is no longer needed or the controller disconnects, ignoring late answers', async () => {
    await flush(); const first = requests[0]!;
    hideGettingStarted(); expect(first.signal.aborted).toBe(true);
    first.resolve(exact); await flush(); expect(getFirstUse().searchable).toBeNull();
    await elapse(60_000); expect(requests).toHaveLength(1);
    reopenGettingStarted(); await flush(); expect(requests).toHaveLength(2);
    const second = requests[1]!;
    controller.hostDisconnected(); expect(second.signal.aborted).toBe(true);
    second.resolve(exact); await flush(); expect(getFirstUse().searchable).toBeNull();
  });
});
