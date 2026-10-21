// SPDX-License-Identifier: Apache-2.0
import type { StatusResponse } from '../../api/generated/index.js';
import { isWorkerReportedIndex } from './indexingProgress.js';

/** Exact whole-queue counts, independent of the bounded indexing-jobs row feed. */
export function indexingQueueCounts(status: StatusResponse | null | undefined): {
  queued: number; processing: number; failed: number;
} | null {
  if (!isWorkerReportedIndex(status)) return null;
  const migration = status?.worker?.migration;
  const queued = migration?.pendingJobsCount;
  const processing = migration?.processingJobsCount;
  const failed = status?.worker?.failure?.failedJobs;
  if (![queued, processing, failed].every((n) =>
    typeof n === 'number' && Number.isSafeInteger(n) && n >= 0)) return null;
  return { queued: queued!, processing: processing!, failed: failed! };
}
