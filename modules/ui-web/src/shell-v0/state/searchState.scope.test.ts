// @vitest-environment happy-dom

import { afterEach, describe, expect, it, beforeEach, vi } from 'vitest';
import {
  getScopeChips,
  subscribeScopeChips,
  addScopeChip,
  removeScopeChip,
  clearScopeChips,
  buildSearchIntent,
  setQuery,
  resetSearchState,
  setSearchApiBase,
  type SearchScopeChip,
} from './searchState.js';
import { __resetSearchFiltersForTest } from './searchFiltersState.js';

const fileChip: SearchScopeChip = { kind: 'file', label: '/a/b.md', docIds: ['doc-1'] };
const resultSetChip: SearchScopeChip = {
  kind: 'result-set',
  label: '3 results',
  docIds: ['doc-1', 'doc-2', 'doc-3'],
};

describe('searchState — scope chips (Search-Thread S3)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    setSearchApiBase('http://test');
    __resetSearchFiltersForTest();
    clearScopeChips();
  });

  afterEach(() => {
    resetSearchState();
    vi.useRealTimers();
  });

  it('starts empty', () => {
    expect(getScopeChips()).toEqual([]);
  });

  it('addScopeChip appends a chip', () => {
    addScopeChip(fileChip);
    expect(getScopeChips()).toEqual([fileChip]);
  });

  it('addScopeChip is a no-op for a duplicate (same kind + same docId set)', () => {
    addScopeChip(fileChip);
    addScopeChip({ kind: 'file', label: '/a/b.md (again)', docIds: ['doc-1'] });
    expect(getScopeChips()).toHaveLength(1);
    expect(getScopeChips()[0]!.label).toBe('/a/b.md');
  });

  it('addScopeChip treats a same-set docId order permutation as the same chip', () => {
    addScopeChip(resultSetChip);
    addScopeChip({ kind: 'result-set', label: 'dup', docIds: ['doc-3', 'doc-1', 'doc-2'] });
    expect(getScopeChips()).toHaveLength(1);
  });

  it('addScopeChip allows two chips of different kind carrying the same docIds', () => {
    addScopeChip(fileChip);
    addScopeChip({ kind: 'result-set', label: 'set', docIds: ['doc-1'] });
    expect(getScopeChips()).toHaveLength(2);
  });

  it('removeScopeChip removes by index', () => {
    addScopeChip(fileChip);
    addScopeChip(resultSetChip);
    removeScopeChip(0);
    expect(getScopeChips()).toEqual([resultSetChip]);
  });

  it('removeScopeChip on an out-of-range index is a no-op', () => {
    addScopeChip(fileChip);
    removeScopeChip(5);
    removeScopeChip(-1);
    expect(getScopeChips()).toEqual([fileChip]);
  });

  it('clearScopeChips empties the list', () => {
    addScopeChip(fileChip);
    addScopeChip(resultSetChip);
    clearScopeChips();
    expect(getScopeChips()).toEqual([]);
  });

  it('subscribeScopeChips fires immediately with the current snapshot', () => {
    addScopeChip(fileChip);
    let received: SearchScopeChip[] | null = null;
    const unsub = subscribeScopeChips((chips) => (received = chips));
    expect(received).toEqual([fileChip]);
    unsub();
  });

  it('subscribeScopeChips fires on every mutation', () => {
    const snapshots: number[] = [];
    const unsub = subscribeScopeChips((chips) => snapshots.push(chips.length));
    addScopeChip(fileChip);
    addScopeChip(resultSetChip);
    removeScopeChip(0);
    clearScopeChips();
    // initial (0) + add (1) + add (2) + remove (1) + clear (0)
    expect(snapshots).toEqual([0, 1, 2, 1, 0]);
    unsub();
  });

  it('mutators do NOT auto re-issue the search (matches the toggleFacetValue precedent, not setSearchScope)', () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ results: [], totalHits: 0 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('hello');
    vi.advanceTimersByTime(250);
    fetchMock.mockClear();
    addScopeChip(fileChip);
    removeScopeChip(0);
    clearScopeChips();
    expect(fetchMock).not.toHaveBeenCalled();
    resetSearchState();
    vi.unstubAllGlobals();
  });

  it('buildSearchIntent omits docIds when no chips are passed', () => {
    const body = buildSearchIntent('q', {}, 'refined');
    expect(body.filters?.docIds).toBeUndefined();
  });

  it('buildSearchIntent sets filters.docIds to the union of every chip\'s docIds, deduped', () => {
    const body = buildSearchIntent('q', {}, 'refined', {}, 'documents', [fileChip, resultSetChip]);
    const docIds = body.filters?.docIds as string[];
    expect(new Set(docIds)).toEqual(new Set(['doc-1', 'doc-2', 'doc-3']));
    expect(docIds).toHaveLength(3); // deduped — doc-1 appears in both chips
  });

  it('an active-query search issues the request body carrying the scope-chip docIds union', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ results: [], totalHits: 0 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    addScopeChip(fileChip);
    setQuery('hello');
    await vi.advanceTimersByTimeAsync(250);
    const body = JSON.parse(fetchMock.mock.calls[0]![1].body);
    expect(body.filters.docIds).toEqual(['doc-1']);
    resetSearchState();
    vi.unstubAllGlobals();
  });
});
