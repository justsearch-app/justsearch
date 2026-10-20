// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import '../../components/TaskList.js';
import type { TaskList } from '../../components/TaskList.js';
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
let stop: (() => void) | undefined;
let multiplex: MultiplexedStream | undefined;
beforeEach(() => {
  __resetAiStateForTest(); __resetTasksForTest(); __resetEnvelopeStreamPoolForTest(); __resetResolvedLabelsForTest();
  __setResolveHashForTest(async () => null);
});
afterEach(() => {
  stop?.(); multiplex?.stop(); stop = undefined; multiplex = undefined;
  document.body.replaceChildren(); __resetAiStateForTest(); __resetTasksForTest(); __resetEnvelopeStreamPoolForTest();
  __setResolveHashForTest(null); vi.useRealTimers();
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
      const rail = document.createElement('jf-task-list') as TaskList;
      document.body.append(rail); status(700000, 16);
      send('LIFECYCLE', { kind: 'snapshot', items: [row('active', 'PROCESSING'), row('retry', 'FAILED'), row('gaveup', 'RETRY_EXHAUSTED')] });
      await Promise.resolve(); await Promise.resolve(); await rail.updateComplete;
      expect(rail.shadowRoot!.querySelectorAll('[data-testid="task-queued-aggregate"]')).toHaveLength(1);
      expect(rail.shadowRoot!.querySelector('[data-testid="task-queued-aggregate"]')?.textContent).toContain(`${formatCount(700000)} files queued`);
      expect(rail.shadowRoot!.querySelector('[data-testid="task-queue-totals"]')?.textContent).toContain(`${formatCount(1001)} failed`);
      expect(getTask('idxjob:active')?.status).toBe('running');
      expect(getTask('idxjob:retry')?.status).toBe('failed');
      expect(getTask('idxjob:gaveup')?.label).toContain('gave up');
      send('UPDATE', { kind: 'update', row: row('retry', 'PENDING') }); status(700001, 16, 1000);
      await Promise.resolve(); await Promise.resolve(); await rail.updateComplete;
      expect(getTask('idxjob:retry')).toBeUndefined();
      expect(rail.shadowRoot!.querySelector('[data-testid="task-queued-aggregate"]')?.textContent).toContain(`${formatCount(700001)} files queued`);
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
      await Promise.resolve(); await Promise.resolve(); await rail.updateComplete;
      expect(rail.shadowRoot!.querySelector('[data-testid="task-queued-aggregate"]')).toBeNull();
      send('UPDATE', { kind: 'snapshot-replaced', items: [row('reload', 'PROCESSING')] });
      expect(listTasks().map(t => t.id)).toEqual(['idxjob:reload']);
      expect(resolver.mock.calls.length).toBeLessThanOrEqual(200 + 17);
      await Promise.resolve(); await Promise.resolve(); await rail.updateComplete;
      rail.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="tasks-disclosure"]')!.click();
      await Promise.resolve(); await Promise.resolve(); await rail.updateComplete;
      expect(rail.shadowRoot!.querySelector('[data-testid="task-window-notice"]')?.textContent).toContain('window');
      expect(rail.shadowRoot!.querySelectorAll('.task').length).toBeLessThanOrEqual(8);
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
  const rail = document.createElement('jf-task-list') as TaskList;
  document.body.append(rail);
  status(700000, 16, 1001);
  source.emit({ streamId: 'indexing-jobs/v1', frameKind: 'LIFECYCLE', seq: 1,
    ts: '2026-10-20T00:00:00Z', resumeToken: 't1', payload: { kind: 'snapshot',
      items: Array.from({ length: 216 }, (_, i) => ({ pathHash: `window-${i}`,
        state: i < 16 ? 'PROCESSING' : 'FAILED', collection: 'docs', lastUpdatedMs: Date.now() })),
    } });
  await vi.waitFor(() => expect(resolver).toHaveBeenCalledTimes(216));
  await rail.updateComplete;
  rail.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="tasks-disclosure"]')!.click();
  await rail.updateComplete;
  expect(rail.shadowRoot!.querySelectorAll('.task')).toHaveLength(8);
  expect(rail.shadowRoot!.querySelectorAll('[data-testid="task-queued-aggregate"]')).toHaveLength(1);
  expect(rail.shadowRoot!.querySelector('[data-testid="task-queued-aggregate"]')?.textContent).toContain(`${formatCount(700000)} files queued`);
  expect(rail.shadowRoot!.querySelector('[data-testid="task-queue-totals"]')?.textContent).toContain(`${formatCount(1001)} failed`);
  expect(listTasks().length).toBeLessThanOrEqual(216);
});
