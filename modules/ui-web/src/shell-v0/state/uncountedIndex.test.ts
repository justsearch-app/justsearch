/**
 * Task t-20261013-ef3c41, S5 — an index the backend could not count.
 *
 * `/api/status` keeps `indexedDocuments: 0` for older readers and adds `indexedDocumentsUnknown: true`.
 * Before, the 0 was taken at its word: the UI said "0" files and treated the index as empty, which
 * offers "Add documents" and turns off document search for an index that holds documents. These
 * checks drive the real store with the real wire shape, then read what the user sees.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { __feedForTest, __resetAiStateForTest, getAiState, hasLiveDocumentCount } from './aiStateStore.js';
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


describe('count observation provenance', () => {
  beforeEach(() => __resetAiStateForTest());
  afterEach(() => __resetAiStateForTest());

  it('keeps fallback counts visibly last known with their original observation time', () => {
    const observedAtMs = 123456;
    const status = { worker: { core: { indexState: 'IDLE', indexedDocuments: 12, searchableDocuments: 10 },
      countObservations: { active: { state: 'stale', observedAtMs } },
    } } as unknown as StatusSnapshot;
    for (let poll = 0; poll < 2; poll++) {
      __feedForTest({ status });
      const state = getAiState();
      expect(state.index.documentCount).toEqual(known(12));
      expect(hasLiveDocumentCount(state)).toBe(false);
      const fact = projectFact('core.files', state);
      expect(fact.value).toBe('12');
      expect(fact.provenance).toBe(`Last known · observed ${new Date(observedAtMs).toLocaleString()}`);
    }
  });

  it('distinguishes a failed filtered count from the fresh whole-index count', () => {
    __feedForTest({ status: { worker: { core: { indexedDocuments: 12, searchableDocuments: 0, indexedDocumentsUnknown: true },
      countObservations: { active: { state: 'fresh', observedAtMs: 1234 }, searchable: { state: 'unknown', observedAtMs: 0 } },
    } } as unknown as StatusSnapshot });
    expect(getAiState().index.documentCount).toEqual(known(12));
    expect(getAiState().index.searchableDocumentCount.known).toBe(false);
    expect(getAiState().lastSettledIndex).toBeNull();
    expect(projectFact('core.files', getAiState()).value).toBe('12');
  });
  it('never treats a last-known zero as proof the current index is empty', () => {
    __feedForTest({
      inference: { mode: 'online', available: true } as unknown as InferenceSnapshot,
      status: { worker: { core: { indexedDocuments: 0, indexState: 'IDLE', indexHealthy: true },
        countObservations: { active: { state: 'stale', observedAtMs: 1234 } },
      } } as unknown as StatusSnapshot,
    });
    expect(getAiState().capabilities.rag).toBe(true);
    expect(projectAvailability('documents', getAiState())).not.toMatchObject({ kind: 'unavailable', reason: 'No documents indexed yet' });
  });

});
