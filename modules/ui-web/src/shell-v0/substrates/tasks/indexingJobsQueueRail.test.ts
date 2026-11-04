// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StatusPill } from '../../components/StatusPill.js';
import type { StatusPanel } from '../../components/StatusPanel.js';
import { __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetTasksForTest, getTask, listTasks } from './index.js';
import { projectJobsToTasks, startIndexingJobsBridge, __setResolveHashForTest, __resetResolvedLabelsForTest } from './indexingJobsBridge.js';
import { __resetEnvelopeStreamPoolForTest } from '../../streaming/EnvelopeStreamPool.js';
import { MultiplexedStream } from '../../streaming/MultiplexedStream.js';
import { formatCount } from '../../display/format.js';
import type { StatusResponse } from '../../../api/generated/index.js';

class Source extends EventTarget {
  readyState = 1;
  close(): void { this.readyState = 2; }
  emit(frame: unknown): void { this.dispatchEvent(new MessageEvent('frame', { data: JSON.stringify(frame) })); }
}
function status(queued: number, processing = 1, failed = 1001): void {
  __feedForTest({ status: {
    worker: { core: { indexState: 'INDEXING', pendingJobs: queued + processing },
      migration: { pendingJobsCount: queued, processingJobsCount: processing }, failure: { failedJobs: failed } },
  } as StatusResponse });
}
const panel = (pill: StatusPill) => pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
/** Opens the indexing row's disclosure (kept open across live updates) and reads one rendered detail line. */
async function detail(pill: StatusPill, id: string): Promise<string | null> {
  const root = panel(pill).shadowRoot!;
  const toggle = root.querySelector<HTMLButtonElement>('article[data-fact="indexing"] button[aria-expanded="false"]');
  if (toggle) { toggle.click(); await panel(pill).updateComplete; }
  return root.querySelector(`[data-detail="${id}"]`)?.textContent ?? null;
}
const queuedRows = (pill: StatusPill) => panel(pill).shadowRoot!.querySelectorAll('[data-detail="queued"]');
let stop: (() => void) | undefined;
let multiplex: MultiplexedStream | undefined;
beforeEach(() => {
  __resetAiStateForTest(); __resetTasksForTest(); __resetEnvelopeStreamPoolForTest(); __resetResolvedLabelsForTest();
  __setResolveHashForTest(async () => null);
  // The pill reads folders/presence on the status cadence; never let a test reach a real origin.
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
});
afterEach(() => {
  stop?.(); multiplex?.stop(); stop = undefined; multiplex = undefined;
  document.body.replaceChildren(); __resetAiStateForTest(); __resetTasksForTest(); __resetEnvelopeStreamPoolForTest();
  __setResolveHashForTest(null); vi.useRealTimers(); vi.unstubAllGlobals();
});

for (const transport of ['standalone', 'multiplexed'] as const) {
  describe(`${transport} million-row rail`, () => {
    it('shows one real queued count, retry until claim, liveness, both failures, DONE, cancel and resync', async () => {
      vi.useFakeTimers();
      const source = new Source();
      let seq = 0;
      if (transport === 'multiplexed') {
        multiplex = new MultiplexedStream({ url: 'http://test/api/shell-events/stream', eventSourceFactory: () => source as unknown as EventSource });
        multiplex.start(); source.dispatchEvent(new Event('open'));
      }
      stop = startIndexingJobsBridge('http://test', multiplex ? { multiplex } : { eventSourceFactory: () => source as unknown as EventSource });
      const send = (kind: 'LIFECYCLE' | 'UPDATE', payload: unknown): void => source.emit({
        streamId: transport === 'multiplexed' ? 'surface:indexing-jobs' : 'indexing-jobs/v1',
        frameKind: kind, seq: ++seq, ts: '2026-10-20T00:00:00Z', resumeToken: `t${seq}`, payload,
      });
      const row = (pathHash: string, state: string) => ({ pathHash, state, collection: 'docs', lastUpdatedMs: Date.now() });
      const resolver = vi.fn(async () => null);
      __setResolveHashForTest(resolver);
      const rail = new StatusPill();
      document.body.append(rail); status(700000, 16);
      send('LIFECYCLE', { kind: 'snapshot', items: [row('active', 'PROCESSING'), row('retry', 'FAILED'), row('gaveup', 'RETRY_EXHAUSTED')] });
      await Promise.resolve(); await Promise.resolve(); await rail.updateComplete; await panel(rail).updateComplete;
      expect(await detail(rail, 'queued')).toContain(`${formatCount(700000)} files queued`);
      expect(queuedRows(rail)).toHaveLength(1);
      expect(await detail(rail, 'failed-files')).toContain(`${formatCount(1001)} failed`);
      expect(getTask('idxjob:active')?.status).toBe('running');
      expect(getTask('idxjob:retry')?.status).toBe('failed');
      expect(getTask('idxjob:gaveup')?.label).toContain('gave up');
      send('UPDATE', { kind: 'update', row: row('retry', 'PENDING') }); status(700001, 16, 1000);
      await Promise.resolve(); await Promise.resolve(); await rail.updateComplete; await panel(rail).updateComplete;
      expect(getTask('idxjob:retry')).toBeUndefined();
      expect(await detail(rail, 'queued')).toContain(`${formatCount(700001)} files queued`);
      send('UPDATE', { kind: 'update', row: row('retry', 'PROCESSING') }); status(700000, 17, 1000);
      expect(getTask('idxjob:retry')?.status).toBe('running');
      vi.advanceTimersByTime(120000);
      expect(getTask('idxjob:retry')?.status).toBe('queued');
      send('UPDATE', { kind: 'update', row: row('retry', 'PROCESSING') });
      expect(getTask('idxjob:retry')?.status).toBe('running');
      send('UPDATE', { kind: 'update', row: row('retry', 'DONE') });
      expect(getTask('idxjob:retry')).toBeUndefined();
      send('UPDATE', { kind: 'delete', primaryKeyValue: 'active' });
      expect(getTask('idxjob:active')).toBeUndefined();
      status(0, 0, 1000);
      await Promise.resolve(); await Promise.resolve(); await rail.updateComplete; await panel(rail).updateComplete;
      expect(queuedRows(rail)).toHaveLength(0);
      send('UPDATE', { kind: 'snapshot-replaced', items: [row('reload', 'PROCESSING')] });
      expect(listTasks().map(t => t.id)).toEqual(['idxjob:reload']);
      expect(resolver.mock.calls.length).toBeLessThanOrEqual(200 + 17);
      await Promise.resolve(); await Promise.resolve(); await rail.updateComplete; await panel(rail).updateComplete;
      panel(rail).shadowRoot!.querySelector<HTMLButtonElement>('article[data-fact="indexing-files"] [aria-expanded="false"]')!.click();
      await Promise.resolve(); await Promise.resolve(); await rail.updateComplete; await panel(rail).updateComplete;
      expect(panel(rail).shadowRoot!.querySelector('[data-detail="file-window"]')?.textContent).toContain('window');
      // The aggregate reads ready; the retained SSE row is qualified, never a live claim.
      expect(panel(rail).shadowRoot!.querySelector('[data-detail="idxjob:reload"]')?.textContent).toContain('last observed running');
      expect(rail.status.model.pill.kind).not.toBe('working');
      expect(panel(rail).shadowRoot!.querySelectorAll('[data-detail^="idxjob:"]').length).toBeLessThanOrEqual(8);
    });
  });
}

it('bounds outstanding label requests and ignores late results for departed window rows', async () => {
  const pending: Array<(path: string | null) => void> = [];
  const resolver = vi.fn(() => new Promise<string | null>(resolve => pending.push(resolve)));
  __setResolveHashForTest(resolver);
  const source = new Source();
  stop = startIndexingJobsBridge('http://test', { eventSourceFactory: () => source as unknown as EventSource });
  const emit = (seq: number) => source.emit({ streamId: 'indexing-jobs/v1', frameKind: 'UPDATE', seq, ts: '2026-10-20T00:00:00Z', resumeToken: `t${seq}`,
    payload: { kind: 'snapshot-replaced', items: Array.from({ length: 216 }, (_, i) => ({ pathHash: `${seq}-${i}`, state: i < 16 ? 'PROCESSING' : 'FAILED', collection: 'docs', lastUpdatedMs: Date.now() })) } });
  for (let i = 1; i <= 100; i++) emit(i);
  expect(resolver.mock.calls.length).toBeLessThanOrEqual(216);
  for (const resolve of pending.splice(0)) resolve('C:\\departed\\old.txt');
  await vi.waitFor(() => expect(getTask('idxjob:100-0')).toBeDefined());
  expect(listTasks().every(t => !t.label.includes('old.txt'))).toBe(true);
  expect(listTasks().length).toBeLessThanOrEqual(216);
});


it('does not retain resolved labels for every file visited over a long run', async () => {
  const resolver = vi.fn(async (_resource: string, hash: string) => `C:/corpus/${hash}.txt`);
  __setResolveHashForTest(resolver);
  const project = async (hash: string): Promise<void> => {
    projectJobsToTasks(new Map([[hash, { pathHash: hash, state: 'PROCESSING', collection: 'docs', lastUpdatedMs: Date.now() }]]));
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  };
  await project('old');
  for (let i = 0; i < 1000; i++) await project(`next-${i}`);
  await project('old');
  expect(resolver.mock.calls.filter(([, hash]) => hash === 'old')).toHaveLength(2);
  expect(listTasks().map(t => t.id)).toEqual(['idxjob:old']);
});


it('renders only eight file tasks at 700k queued, with at most window plus concurrency label requests', async () => {
  const resolver = vi.fn(async (_resource: string, hash: string) => `C:/corpus/${hash}.txt`);
  __setResolveHashForTest(resolver);
  const source = new Source();
  stop = startIndexingJobsBridge('http://test', { eventSourceFactory: () => source as unknown as EventSource });
  const rail = new StatusPill();
  document.body.append(rail);
  status(700000, 16, 1001);
  source.emit({ streamId: 'indexing-jobs/v1', frameKind: 'LIFECYCLE', seq: 1,
    ts: '2026-10-20T00:00:00Z', resumeToken: 't1', payload: { kind: 'snapshot',
      items: Array.from({ length: 216 }, (_, i) => ({ pathHash: `window-${i}`,
        state: i < 16 ? 'PROCESSING' : 'FAILED', collection: 'docs', lastUpdatedMs: Date.now() })),
    } });
  await vi.waitFor(() => expect(resolver).toHaveBeenCalledTimes(216));
  await rail.updateComplete; await panel(rail).updateComplete;
  expect(await detail(rail, 'queued')).toContain(`${formatCount(700000)} files queued`);
  expect(panel(rail).shadowRoot!.querySelectorAll('[data-detail^="idxjob:"]')).toHaveLength(8);
  expect(queuedRows(rail)).toHaveLength(1);
  expect(await detail(rail, 'failed-files')).toContain(`${formatCount(1001)} failed`);
  expect(listTasks().length).toBeLessThanOrEqual(216);
});
