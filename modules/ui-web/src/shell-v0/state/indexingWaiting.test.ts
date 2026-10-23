// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { statusResponseSchema } from '../../api/generated/schema-types/status-response.js';
import fixture from '../../api/__fixtures__/status-response-live.json';
import type { StatusResponse } from '../../api/generated/index.js';
import { selectIndexingPhase, selectIndexingProgress } from './indexingProgress.js';

function status(indexState: string, pendingJobs: number): StatusResponse {
  return { worker: { core: { indexState, pendingJobs, indexHealthy: true },
    failure: { nextRetryAtMs: 1792600000000 }, migration: { processingJobsCount: 0 } } } as StatusResponse;
}

it('shows retry waiting with the backlog and deadline then follows the worker lifecycle', () => {
  const waiting = selectIndexingProgress(status('WAITING', 7), true, 10, []);
  expect(waiting.phase).toBe('waiting');
  expect(waiting.jobsPending).toBe(7);
  expect(waiting.activity.confirmed).toBe(true);
  expect(waiting.activity.summary).toBe('Waiting to retry');
  expect(waiting.activity.detail).toContain('7 files');
  expect(waiting.activity.detail).toContain(new Date(1792600000000).toLocaleString());
  expect(waiting.etaSeconds).toBeNull();
  expect(waiting.indexingPercent).toBeNull();
  for (const [state, backlog, phase] of [
    ['INDEXING', 7, 'indexing'], ['INDEXING', 6, 'indexing'], ['WAITING', 6, 'waiting'],
    ['IDLE', 0, 'ready'], ['ERROR', 0, 'error'], ['FAILED', 7, 'unknown'],
  ] as const) {
    expect(selectIndexingPhase(status(state, backlog))).toBe(phase);
    expect(selectIndexingProgress(status(state, backlog), true, 10, []).phase).toBe(phase);
  }

  // A later observation replaces waiting; stale observations never claim live work.
  expect(selectIndexingProgress(status('WAITING', 7), false, 10, []).activity.confirmed).toBe(false);
  const ready = selectIndexingProgress(status('INDEXING', 7), true, 10, []);
  expect(ready.activity.summary).toBe('Indexing');
  expect(ready.activity.detail ?? '').not.toContain('retry');

  const noDeadline = status('WAITING', 7);
  noDeadline.worker!.failure!.nextRetryAtMs = 0;
  expect(selectIndexingProgress(noDeadline, true, 10, []).activity.detail).toBe('7 files waiting.');

  // Preserve admission by already generated clients without loosening their schema.
  const wire = structuredClone(fixture);
  wire.worker.core.indexState = 'WAITING';
  expect(statusResponseSchema.safeParse(wire).success).toBe(true);
});

it('terminal job failures stay errors instead of becoming a completion claim', () => {
  const failed = status('ERROR', 0);
  failed.worker!.core!.indexHealthy = false;
  failed.worker!.failure!.failedJobs = 2;
  const progress = selectIndexingProgress(failed, true, 10, []);
  expect(selectIndexingPhase(failed)).toBe('error');
  expect(progress.phase).toBe('error');
  expect(progress.activity.summary).toBe('Indexing error');
  expect(progress.activity.detail).toBe('2 files could not be indexed.');
  expect(progress.indexingPercent).toBeNull();
  expect(progress.etaSeconds).toBeNull();
});
