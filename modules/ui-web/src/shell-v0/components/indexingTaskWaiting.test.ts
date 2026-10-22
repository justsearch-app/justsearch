// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './StatusDeck.js';
import type { StatusDeck } from './StatusDeck.js';
import type { Task } from '../substrates/tasks/index.js';
import { __feedForTest, __resetAiStateForTest, __tickClockForTest, getAiState, type AiState } from '../state/aiStateStore.js';
import { indexingTaskStatus, type IndexingPhase, type IndexingProgress } from '../state/indexingProgress.js';
import { __resetStatusPollForTest } from '../utils/statusPoll.js';
import { __resetInferencePollForTest } from '../utils/inferencePoll.js';

beforeEach(() => {
  document.body.innerHTML = '';
  __resetStatusPollForTest();
  __resetInferencePollForTest();
  __resetAiStateForTest();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }));
});
afterEach(() => {
  document.body.innerHTML = '';
  vi.unstubAllGlobals();
});

it('qualifies delayed running file rows after the worker stops runnable file work', () => {
  const activity: IndexingProgress['activity'] = { summary: 'Waiting to retry', detail: '2 files waiting.',
    confirmed: true, enrichmentDeferred: false };
  const file = { id: 'idxjob:delayed', status: 'running' };
  for (const phase of ['waiting', 'error', 'ready', 'blocked', 'enriching'] as IndexingPhase[]) {
    expect(indexingTaskStatus(file, activity, phase)).toBe('last observed running');
  }
  expect(indexingTaskStatus(file, activity, 'indexing')).toBe('running');
  expect(indexingTaskStatus({ id: 'agent:live', status: 'running' }, activity, 'waiting')).toBe('running');
  expect(indexingTaskStatus({ id: 'idxjob:done', status: 'done' }, activity, 'waiting')).toBe('done');
});

it('withdraws delayed file work from the running chip while preserving independent agent work', async () => {
  __feedForTest({ status: { worker: { core: { indexState: 'WAITING', pendingJobs: 2,
    indexedDocuments: 37, indexHealthy: true }, failure: { nextRetryAtMs: Date.now() + 60_000 } } } as AiState['status'] });
  __tickClockForTest();
  const el = document.createElement('jf-status-deck') as StatusDeck;
  document.body.appendChild(el);
  el.aiState = getAiState();
  expect(el.aiState.indexingStatusLive).toBe(true);
  const file = { id: 'idxjob:delayed', status: 'running', label: 'delayed-file-row' } as Task;
  el.runningTasks = [file];
  await el.updateComplete;
  expect(el.shadowRoot!.querySelector('[aria-label*="delayed-file-row"]')).toBeNull();
  el.runningTasks = [file, { id: 'agent:live', status: 'running', label: 'independent-agent-row' } as Task];
  await el.updateComplete;
  expect(el.shadowRoot!.querySelector('[aria-label*="independent-agent-row"]')).not.toBeNull();
  expect(el.shadowRoot!.textContent).not.toContain('2 running');
});
