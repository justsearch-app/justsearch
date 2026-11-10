// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { beforeEach, afterEach, expect, it } from 'vitest';
import { __feedForTest, __feedContactForTest, __resetAiStateForTest } from './aiStateStore.js';
import { __resetUserStateForTest } from './UserStateDocument.js';
import { getFirstUse, setFirstUseRoots, hideGettingStarted } from './firstUse.js';
beforeEach(() => { __resetUserStateForTest(); __resetAiStateForTest(); setFirstUseRoots([], new Map()); });
afterEach(() => __resetAiStateForTest());
function feed(own: number | null | undefined, total = 0, unknown = false) {
  __feedForTest({ status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0,
    indexedDocuments: total, searchableDocuments: total, indexedDocumentsUnknown: unknown,
    ...(own === undefined ? {} : { ownSearchableDocuments: own }) } } } } as never);
  __feedContactForTest();
}
it('older status without an own count is unknown even when searchable is zero', () => {
  feed(undefined);
  expect(getFirstUse()).toMatchObject({ searchable: null, known: false, empty: false, visible: false });
});
it('one Engine snapshot excludes Help, includes ad-hoc documents and stays empty after Hide', () => {
  feed(0, 5);
  expect(getFirstUse()).toMatchObject({ searchable: 0, empty: true, currentStep: 1 });
  hideGettingStarted(); feed(0, 8);
  expect(getFirstUse()).toMatchObject({ visible: false, empty: true, searchable: 0 });
  feed(2, 10);
  expect(getFirstUse()).toMatchObject({ empty: false, searchable: 2, done: [true, false, false] });
});
it('unknown index counts invalidate the own count in the same snapshot', () => {
  feed(0, 0, true);
  expect(getFirstUse()).toMatchObject({ empty: false, searchable: null, known: false });
});
