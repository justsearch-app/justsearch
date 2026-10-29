/**
 * #758 — an index the backend could not count.
 *
 * `/api/status` keeps `indexedDocuments: 0` for older readers and adds `indexedDocumentsUnknown: true`.
 * Before, the 0 was taken at its word: the UI said "0" files and treated the index as empty, which
 * offers "Add documents" and turns off document search for an index that holds documents. These
 * checks drive the real store with the real wire shape, then read what the user sees.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { __feedForTest, __resetAiStateForTest, getAiState } from './aiStateStore.js';
import type { StatusSnapshot } from '../utils/statusPoll.js';
import type { InferenceSnapshot } from '../utils/inferencePoll.js';
import { projectAvailability } from './availability.js';
import { projectFact } from '../display/facts.js';
import { known } from './known.js';

function feed(core: Record<string, unknown>, uptimeMs = 5_000): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as unknown as InferenceSnapshot,
    status: { uptimeMs, worker: { core } } as unknown as StatusSnapshot,
  });
}

const UNCOUNTED = { indexedDocuments: 0, searchableDocuments: 0, indexedDocumentsUnknown: true };

describe('an index the backend could not count (t-20261013-ef3c41)', () => {
  beforeEach(() => __resetAiStateForTest());
  afterEach(() => __resetAiStateForTest());

  it('shows "Checking…" while the server is starting, never 0', () => {
    feed(UNCOUNTED, 5_000);
    expect(projectFact('core.files', getAiState()).value).toBe('Checking…');
  });

  it('shows "Unknown" once the start-up window has passed, never 0', () => {
    feed(UNCOUNTED, 10 * 60_000);
    expect(projectFact('core.files', getAiState()).value).toBe('Unknown');
  });

  it('is not treated as empty: the counts are unknown and document search is not "no documents"', () => {
    feed({ indexedDocuments: 0 });
    const empty = projectAvailability('documents', getAiState());
    expect(empty.kind).toBe('unavailable'); // precondition: a known 0 IS the empty-index gap

    __resetAiStateForTest();
    feed(UNCOUNTED);
    const s = getAiState();
    expect(s.index.documentCount.known).toBe(false);
    expect(s.index.searchableDocumentCount.known).toBe(false);
    expect(s.capabilities.rag).toBe(true);
    expect(projectAvailability('documents', s)).not.toEqual(empty);
  });

  it('a counted index is unchanged: a known 0 is still empty, a known count is still shown', () => {
    feed({ indexedDocuments: 0 });
    expect(getAiState().index.documentCount).toEqual(known(0));
    expect(projectFact('core.files', getAiState()).value).toBe('0');
    expect(getAiState().capabilities.rag).toBe(false);

    feed({ indexedDocuments: 12, indexedDocumentsUnknown: false });
    expect(projectFact('core.files', getAiState()).value).toBe('12');
    expect(getAiState().capabilities.rag).toBe(true);
  });
});
