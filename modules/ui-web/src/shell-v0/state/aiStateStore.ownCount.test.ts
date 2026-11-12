// SPDX-License-Identifier: Apache-2.0
// FA4 — the status store projects the person's own file count (the Engine's ownSearchableDocuments,
// bundled Help excluded) and retains it from settled polls; an unreported one stays unknown.
import { afterEach, expect, it } from 'vitest';
import { __feedForTest, __resetAiStateForTest, getAiState, type StatusSnapshot } from './aiStateStore.js';

const HELP = 250;
function observe(own: number | undefined, sizeBytes: number): void {
  const searchable = (own ?? 0) + HELP;
  __feedForTest({ status: { worker: { core: { indexedDocuments: searchable, searchableDocuments: searchable,
    ...(own === undefined ? {} : { ownSearchableDocuments: own }), indexSizeBytes: sizeBytes,
    indexState: 'IDLE', indexHealthy: true }, migration: { migrationState: 'IDLE' } } } as unknown as StatusSnapshot });
}
afterEach(() => { __resetAiStateForTest(); });
it('FA4 the status store projects and retains the own count; a reported 0 stays 0, an unreported one stays unknown', () => {
  observe(undefined, 4096);
  expect(getAiState().index.ownDocumentCount).toEqual({ known: false });
  expect(getAiState().lastSettledIndex).toEqual({ documentCount: HELP, ownDocumentCount: null, indexSizeBytes: 4096 });
  observe(31, 4096);
  expect(getAiState().index.ownDocumentCount).toEqual({ known: true, value: 31 });
  expect(getAiState().lastSettledIndex).toEqual({ documentCount: 31 + HELP, ownDocumentCount: 31, indexSizeBytes: 4096 });
  observe(0, 4096);
  expect(getAiState().index.ownDocumentCount).toEqual({ known: true, value: 0 });
  expect(getAiState().lastSettledIndex?.ownDocumentCount).toBe(0);
});
