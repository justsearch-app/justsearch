// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { indexingQueueCounts } from './indexingQueueCounts.js';

for (const indexState of ['UNAVAILABLE', 'NOT_STARTED', 'DEGRADED', undefined]) {
  it(`does not certify the hard-zero fallback as an empty queue (${indexState})`, () => {
    expect(indexingQueueCounts({ worker: { core: { indexState },
      migration: { pendingJobsCount: 0, processingJobsCount: 0 }, failure: { failedJobs: 0 },
    } })).toBeNull();
  });
}

describe('whole queue counts from a reported worker', () => {
  it('keeps real counts independent of the window', () => {
    expect(indexingQueueCounts({ worker: { core: { indexState: 'INDEXING' },
      migration: { pendingJobsCount: 700000, processingJobsCount: 16 }, failure: { failedJobs: 1001 },
    } })).toEqual({ queued: 700000, processing: 16, failed: 1001 });
  });
  it('does not turn missing aggregates into zeros', () => {
    expect(indexingQueueCounts({ worker: { core: { indexState: 'IDLE' },
      migration: { pendingJobsCount: 0, processingJobsCount: 0 },
    } })).toBeNull();
  });
});
