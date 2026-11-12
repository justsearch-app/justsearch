// SPDX-License-Identifier: Apache-2.0
/**
 * Follow-up B1, FB2b: when a status marks its document counts unknown (`indexedDocumentsUnknown`),
 * every count it carries from the Worker (pending jobs, index size, queue counts, documents) is
 * unknown to every reader of the store and the indexing projection, never the 0 or the number the
 * block happens to carry. A counted status keeps its counts, a counted 0 included. Each case reads
 * through the exported projections the surfaces use.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { __feedForTest, __resetAiStateForTest, getAiState, type StatusSnapshot } from './aiStateStore.js';
import { isWorkerReportedIndex, selectIndexingProgress } from './indexingProgress.js';
import { indexingQueueCounts } from './indexingQueueCounts.js';
import { projectFact } from '../display/facts.js';

/** A Worker block with a queue of 3 and a 4 KiB index, its document counts known or marked unknown. */
function snapshot(pending: number, unknown: boolean): StatusSnapshot {
  return { worker: {
    core: { indexState: pending > 0 ? 'INDEXING' : 'IDLE', indexHealthy: true, indexedDocuments: 12, searchableDocuments: 12,
      pendingJobs: pending, indexSizeBytes: pending > 0 ? 4096 : 0, indexedDocumentsUnknown: unknown },
    migration: { pendingJobsCount: pending, processingJobsCount: 0 },
    failure: { failedJobs: 0 },
  } } as unknown as StatusSnapshot;
}

beforeEach(() => __resetAiStateForTest());
afterEach(() => __resetAiStateForTest());

describe('FB2b the unknown flag covers every Worker count', () => {
  it('a status that marks its counts unknown gives no Worker count to any reader', () => {
    // Precondition: the same block, counted, reads as counted (a known 0 queue included).
    const counted = snapshot(0, false);
    __feedForTest({ status: counted });
    expect(isWorkerReportedIndex(counted)).toBe(true);
    expect(getAiState().index.pendingJobs).toEqual({ known: true, value: 0 });
    expect(indexingQueueCounts(counted)).toEqual({ queued: 0, processing: 0, failed: 0 });
    expect(projectFact('core.size', getAiState()).value).toBe('0 B');

    __resetAiStateForTest();
    const uncounted = snapshot(3, true);
    __feedForTest({ status: uncounted });
    expect(isWorkerReportedIndex(uncounted)).toBe(false);
    expect(getAiState().index.documentCount.known).toBe(false);
    expect(getAiState().index.pendingJobs.known).toBe(false);
    expect(indexingQueueCounts(uncounted)).toBeNull();
    const progress = selectIndexingProgress(uncounted, true, 0, []);
    expect(progress.phase).toBe('unknown');
    expect(progress.activity.confirmed).toBe(false);
    expect(projectFact('core.size', getAiState()).value).toMatch(/^(Checking…|Unknown)$/);
  });
});
