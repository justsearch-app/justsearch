// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it } from 'vitest';
import { __feedForTest, __feedContactForTest, __resetAiStateForTest, getAiState } from './aiStateStore.js';
import { getFirstUse, setFirstUseRoots } from './firstUse.js';
import { selectIndexingProgress } from './indexingProgress.js';

beforeEach(() => {
  __resetAiStateForTest();
  setFirstUseRoots([{ pathHash: 'notes' }], new Map([['notes', '/notes']]));
});
afterEach(__resetAiStateForTest);

it.each([undefined, 0])('keeps live reading visible with unknown enrichment and queue %s', pendingJobs => {
  __feedForTest({ status: { worker: {
    core: { indexState: 'INDEXING', indexedDocuments: 3, searchableDocuments: 3, ownSearchableDocuments: 3,
      ...(pendingJobs === undefined ? {} : { pendingJobs }) },
    enrichment: { completenessKnown: false },
  } } });
  __feedContactForTest();
  const ai = getAiState();
  // Enrichment cannot confirm its work; the worker still explicitly reports file reading.
  expect(selectIndexingProgress(ai.status, ai.indexingStatusLive, 0, []).activity.confirmed).toBe(false);
  expect(getFirstUse()).toMatchObject({ reading: true, searchable: 3 });
});
