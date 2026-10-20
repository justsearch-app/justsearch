// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { __resetTasksForTest, listTasks, startTask, subscribeTasks } from './index.js';
import { __resetResolvedLabelsForTest, __setResolveHashForTest, projectJobsToTasks } from './indexingJobsBridge.js';

beforeEach(() => {
  __resetTasksForTest();
  __resetResolvedLabelsForTest();
  __setResolveHashForTest(() => new Promise(() => {}));
});
afterEach(() => __setResolveHashForTest(null));

it('retains the entire failure window and reconciles it without pruning or listener churn', () => {
  const items = new Map(Array.from({ length: 216 }, (_, i) => [String(i), {
    pathHash: String(i), state: i < 16 ? 'PROCESSING' : 'FAILED',
    collection: 'docs', lastUpdatedMs: Date.now(),
  }]));
  const listener = vi.fn();
  subscribeTasks(listener);
  projectJobsToTasks(items);
  expect(listTasks()).toHaveLength(216);
  expect(listener).toHaveBeenCalledTimes(1);
  listener.mockClear();
  for (let i = 0; i < 100; i++) projectJobsToTasks(items);
  expect(listTasks()).toHaveLength(216);
  expect(listener).not.toHaveBeenCalled();
  projectJobsToTasks(new Map());
  expect(listTasks()).toHaveLength(0);
  expect(listener).toHaveBeenCalledTimes(1);
});

it('reconciles job tasks without enumerating unrelated live tasks', () => {
  for (let i = 0; i < 10000; i++) startTask({ id: `agent:${i}`, label: 'Live agent work' });
  // A namespace read must return only the bounded mirror, including rows left by a prior mount.
  startTask({ id: 'idxjob:departed', label: 'Prior bridge' });
  expect(listTasks('idxjob:').map(t => t.id)).toEqual(['idxjob:departed']);
  projectJobsToTasks(new Map());
  expect(listTasks('idxjob:')).toEqual([]);
  expect(listTasks('agent:')).toHaveLength(10000);
});
