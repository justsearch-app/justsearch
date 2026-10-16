// @vitest-environment happy-dom

import { describe, expect, it, beforeEach, vi, afterEach } from 'vitest';
import {
  setQuery,
  submitSearch,
  getSearchState,
  setSearchApiBase,
  subscribeSearch,
  buildSearchIntent,
  QUICK_PIPELINE,
  recordInspectorOpen,
  recordInspectorClose,
  fetchCollectionFacet,
} from './searchState.js';
import {
  setFilterRange,
  __resetSearchFiltersForTest,
} from './searchFiltersState.js';
// Tempdoc 564 Phase 3: searchState validates the raw trace JSON via the generated Zod; the capture
// assertions compare against the same Zod parse (faithful to the new, proto-free boundary).
import { searchTraceSchema } from '../../api/generated/schema-types/search-trace.js';
import { hitStageSchema } from '../../api/generated/schema-types/hit-stage.js';

describe('searchState (slice 463)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    setSearchApiBase('http://test');
    __resetSearchFiltersForTest();
  });

  afterEach(() => {
    __resetSearchFiltersForTest();
  });

  it('empty query clears results immediately', () => {
    setQuery('test');
    setQuery('');
    expect(getSearchState().results).toEqual([]);
    expect(getSearchState().totalHits).toBe(0);
  });

  it('subscriber gets initial state on subscribe', () => {
    let received: string | null = null;
    const unsub = subscribeSearch((s) => (received = s.query));
    expect(received).not.toBeNull();
    unsub();
  });

  it('subscriber notified on query change', () => {
    const calls: string[] = [];
    const unsub = subscribeSearch((s) => calls.push(s.query));
    setQuery('hello');
    expect(calls.includes('hello')).toBe(true);
    unsub();
  });

  it('debounce: setQuery does not fire fetch immediately', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ results: [], totalHits: 0 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('test');
    expect(fetchMock).not.toHaveBeenCalled();
    // 804 B3: the search POST now goes through `authorizedFetch`, which awaits session-token
    // resolution BEFORE issuing the request — so the network call lands one microtask after the
    // debounce timer, not in the same tick. Async timer advance flushes that tick.
    await vi.advanceTimersByTimeAsync(250);
    expect(fetchMock).toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  // Slice 486 G36-widening (filter-snapshot) — filter plumbing in the request body.

  it('omits filters key when no active filter (quick pass carries the cheap pipeline)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ results: [], totalHits: 0 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('test');
    await vi.advanceTimersByTimeAsync(250);
    const body = JSON.parse(fetchMock.mock.calls[0]![1].body);
    // 577 Phase 5: the keystroke path issues the QUICK pass first.
    expect(body).toEqual({ query: 'test', limit: 50, mode: 'text', pipeline: QUICK_PIPELINE });
    expect(body.filters).toBeUndefined();
    vi.unstubAllGlobals();
  });

  it('includes filters.modifiedAt when both bounds set', async () => {
    setFilterRange(1000, 2000);
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ results: [], totalHits: 0 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('test');
    await vi.advanceTimersByTimeAsync(250);
    const body = JSON.parse(fetchMock.mock.calls[0]![1].body);
    expect(body.filters).toEqual({ modifiedAt: { fromMs: 1000, toMs: 2000 } });
    vi.unstubAllGlobals();
  });

  it('includes only the set bound when one is undefined', async () => {
    setFilterRange(1000, undefined);
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ results: [], totalHits: 0 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('test');
    await vi.advanceTimersByTimeAsync(250);
    const body = JSON.parse(fetchMock.mock.calls[0]![1].body);
    expect(body.filters).toEqual({ modifiedAt: { fromMs: 1000 } });
    vi.unstubAllGlobals();
  });

  it('clearing filters omits the field on subsequent search', async () => {
    setFilterRange(1000, 2000);
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ results: [], totalHits: 0 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('test');
    await vi.advanceTimersByTimeAsync(250);
    setFilterRange(undefined, undefined);
    setQuery('test2');
    await vi.advanceTimersByTimeAsync(250);
    const body = JSON.parse(fetchMock.mock.calls[1]![1].body);
    expect(body.filters).toBeUndefined();
    vi.unstubAllGlobals();
  });

  // Tempdoc 549 Phase E4 — query-level unified trace capture for the G33 explain panel
  // (introspection retired; searchTrace is the single source).

  it('starts with null searchTrace', () => {
    expect(getSearchState().searchTrace).toBeNull();
  });

  it('captures the unified searchTrace from the search response (G33)', async () => {
    const searchTrace = {
      version: 1,
      decisionKind: 'sparse_shortcut',
      effectiveMode: 'TEXT',
      stages: [{ id: 'sparse-retrieval', status: 'executed' }],
    };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () =>
        Promise.resolve({
          results: [{ id: 'a', fields: { path: '/a.md' } }],
          totalHits: 1,
          searchTrace,
        }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('hello');
    await vi.runAllTimersAsync();
    expect(getSearchState().searchTrace).toEqual(
      searchTraceSchema.parse(searchTrace),
    );
    vi.unstubAllGlobals();
  });

  // Tempdoc 811 (C-1a) — the corpus marker's input: `fields.collection` must reach the hit, or the
  // row pill can never render regardless of how the card is written.
  it('carries fields.collection onto the hit (and leaves it undefined when the wire omits it)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () =>
        Promise.resolve({
          results: [
            { id: 'h', fields: { path: '/help.md', collection: 'justsearch-help' } },
            { id: 'u', fields: { path: '/mine.md' } },
          ],
          totalHits: 2,
        }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('help');
    await vi.runAllTimersAsync();
    const results = getSearchState().results;
    expect(results.find((r) => r.id === 'h')?.collection).toBe('justsearch-help');
    expect(results.find((r) => r.id === 'u')?.collection).toBeUndefined();
    vi.unstubAllGlobals();
  });

  it('clears searchTrace when a subsequent response omits it', async () => {
    const withTrace = vi.fn().mockResolvedValue({
      ok: true,
      json: () =>
        Promise.resolve({ results: [{ id: 'a' }], totalHits: 1, searchTrace: { version: 1 } }),
    });
    vi.stubGlobal('fetch', withTrace);
    setQuery('first');
    await vi.runAllTimersAsync();
    expect(getSearchState().searchTrace).not.toBeNull();
    const noTrace = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ results: [{ id: 'b' }], totalHits: 1 }),
    });
    vi.stubGlobal('fetch', noTrace);
    setQuery('second');
    await vi.runAllTimersAsync();
    expect(getSearchState().searchTrace).toBeNull();
    vi.unstubAllGlobals();
  });

  // Tempdoc 549 Phase E3 → 577 Phase 4 — the trace FUSION stage ms is the DIAGNOSTIC
  // retrieval-phase number (`retrievalMs`); `processingTimeMs` is the measured wall
  // time of the pass (honest latency — what the user actually waited).

  it('captures retrievalMs from the FUSION stage; processingTimeMs is measured wall time', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () =>
        Promise.resolve({
          results: [{ id: 'a' }],
          totalHits: 1,
          searchTrace: { stages: [{ id: 'fusion', status: 'executed', ms: 5 }] },
        }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('q');
    await vi.runAllTimersAsync();
    expect(getSearchState().retrievalMs).toBe(5);
    const wall = getSearchState().processingTimeMs;
    expect(typeof wall).toBe('number');
    expect(wall).toBeGreaterThanOrEqual(0);
    vi.unstubAllGlobals();
  });

  it('clears searchTrace + processingTimeMs on empty query', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () =>
        Promise.resolve({
          results: [{ id: 'a' }],
          totalHits: 1,
          searchTrace: { stages: [{ id: 'fusion', status: 'executed', ms: 1 }] },
        }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('seed');
    await vi.runAllTimersAsync();
    expect(getSearchState().searchTrace).not.toBeNull();
    setQuery('');
    expect(getSearchState().searchTrace).toBeNull();
    expect(getSearchState().processingTimeMs).toBeNull();
    vi.unstubAllGlobals();
  });

  // Tempdoc 549 (G111) — per-hit trace capture (the per-doc slice; provenance retired E2).

  it('captures the per-hit trace slice from the response', async () => {
    const trace = [
      { id: 'sparse-retrieval', rank: 1, score: 2.1 },
      { id: 'cross-encoder', score: 0.3 },
    ];
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ results: [{ id: 'a', trace }], totalHits: 1 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('q');
    await vi.runAllTimersAsync();
    expect(getSearchState().results[0]?.trace).toEqual(
      trace.map((s) => hitStageSchema.parse(s)),
    );
    vi.unstubAllGlobals();
  });

  // ----- Tempdoc 577 Phases 4+5 — the one intent seam + staged execution -----

  it('buildSearchIntent is the one constructor: identical inputs → identical bodies', () => {
    const a = buildSearchIntent('q', {}, 'refined');
    const b = buildSearchIntent('q', {}, 'refined');
    expect(a).toEqual(b);
    // The quick stage pins the cheap pipeline; refined runs the backend default +
    // excerpts + the facet-count request (Move E).
    expect(buildSearchIntent('q', {}, 'quick')).toEqual({
      query: 'q',
      limit: 50,
      mode: 'text',
      pipeline: QUICK_PIPELINE,
    });
    expect(buildSearchIntent('q', {}, 'refined')).toEqual({
      query: 'q',
      limit: 50,
      includeExcerpts: true,
      facets: {
        include: true,
        fields: [
          { field: 'file_kind' },
          { field: 'mime_base' },
          { field: 'language' },
          { field: 'meta_author' },
        ],
      },
    });
  });

  it('facet selections map onto the wire keyword filters on BOTH stages (Move E)', () => {
    const sel = { file_kind: ['markdown'], meta_author: ['alice'] };
    const quick = buildSearchIntent('q', {}, 'quick', sel);
    const refined = buildSearchIntent('q', {}, 'refined', sel);
    for (const body of [quick, refined]) {
      expect(body.filters).toMatchObject({
        fileKind: ['markdown'],
        metaAuthor: ['alice'],
      });
    }
  });

  it('585 §D D4b — the agent-history scope adds the collection filter; documents scope omits it', () => {
    // Default 'documents' scope → no collection key (the backend default-excludes agent-history).
    expect(buildSearchIntent('q', {}, 'refined', {}, 'documents').filters?.collection).toBeUndefined();
    // 'agent-history' scope → scope the query to the agent-history collection.
    expect(buildSearchIntent('q', {}, 'refined', {}, 'agent-history').filters).toMatchObject({
      collection: ['agent-history'],
    });
    // Default param (no scope arg) behaves like 'documents'.
    expect(buildSearchIntent('q', {}, 'refined').filters?.collection).toBeUndefined();
  });

  it('captures the response facet counts; a quick pass keeps the previous counts', async () => {
    const facets = { file_kind: { markdown: 3 } };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ results: [{ id: 'a' }], totalHits: 1, facets }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('q');
    await vi.runAllTimersAsync();
    expect(getSearchState().facets).toEqual(facets);
    // A quick-pass response without facets keeps the previous counts.
    const noFacets = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ results: [{ id: 'b' }], totalHits: 1 }),
    });
    vi.stubGlobal('fetch', noFacets);
    setQuery('q2');
    vi.advanceTimersByTime(250); // quick pass only — settle not yet elapsed
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(getSearchState().facets).toEqual(facets);
    vi.unstubAllGlobals();
  });

  it('staged: keystroke path runs the quick pass, then the refined pass after settle', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ results: [{ id: 'a' }], totalHits: 1 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('q');
    await vi.runAllTimersAsync();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const first = JSON.parse(fetchMock.mock.calls[0]![1].body);
    const second = JSON.parse(fetchMock.mock.calls[1]![1].body);
    expect(first.pipeline).toEqual(QUICK_PIPELINE);
    expect(second.pipeline).toBeUndefined();
    expect(second.includeExcerpts).toBe(true);
    expect(getSearchState().passStage).toBe('refined');
    expect(getSearchState().isRefining).toBe(false);
    vi.unstubAllGlobals();
  });

  it('submitSearch runs the refined pass immediately (no quick pass)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ results: [{ id: 'a' }], totalHits: 1 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('q'); // seeds query; debounce pending
    submitSearch(); // supersedes the staged path
    await vi.runAllTimersAsync();
    const first = JSON.parse(fetchMock.mock.calls[0]![1].body);
    expect(first.includeExcerpts).toBe(true);
    expect(first.pipeline).toBeUndefined();
    expect(getSearchState().passStage).toBe('refined');
    vi.unstubAllGlobals();
  });

  it('flips slowSearch while an in-flight pass exceeds the slow-hint threshold', async () => {
    const never = new Promise(() => {});
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(never));
    setQuery('q');
    vi.advanceTimersByTime(250); // debounce → quick pass in flight
    expect(getSearchState().slowSearch).toBe(false);
    vi.advanceTimersByTime(500); // past SLOW_HINT_MS
    expect(getSearchState().slowSearch).toBe(true);
    setQuery(''); // cleanup: supersede the hung request
    vi.unstubAllGlobals();
  });

  it('typing again cancels the pending refined pass (generation guard)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ results: [{ id: 'a' }], totalHits: 1 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('first');
    vi.advanceTimersByTime(250); // first quick fires
    await Promise.resolve(); // let the quick response settle enough to schedule
    setQuery('second'); // supersedes generation; cancels first's settle timer
    await vi.runAllTimersAsync();
    const bodies = fetchMock.mock.calls.map((c) => JSON.parse(c[1].body).query);
    // No refined pass for 'first' may run after 'second' was typed.
    const refinedFirst = fetchMock.mock.calls.filter((c) => {
      const b = JSON.parse(c[1].body);
      return b.query === 'first' && b.includeExcerpts === true;
    });
    expect(refinedFirst).toHaveLength(0);
    expect(bodies[bodies.length - 1]).toBe('second');
    vi.unstubAllGlobals();
  });
});

// Tempdoc 580 §17 P3 — the DWELLED signal (Fix D): a result kept open past the threshold is the
// graded positive above OPENED. The inspector lifecycle arms/cancels the timer via searchState.
describe('searchState — DWELLED (tempdoc 580 §17 P3)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    setSearchApiBase('http://test');
  });
  afterEach(() => {
    recordInspectorClose(); // ensure no timer leaks between tests
    vi.unstubAllGlobals();
  });

  /** Run a search so state.results + interactionId are populated, then return the fetch mock. */
  async function seedSearch(): Promise<ReturnType<typeof vi.fn>> {
    const fetchMock = vi.fn((url: string) => {
      if (String(url).includes('/disposition')) {
        return Promise.resolve({ ok: true, status: 204, json: () => Promise.resolve({}) });
      }
      return Promise.resolve({
        ok: true,
        json: () =>
          Promise.resolve({ results: [{ id: 'a' }, { id: 'b' }], totalHits: 2, interactionId: 'iid-1' }),
      });
    });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('q');
    await vi.runAllTimersAsync();
    return fetchMock;
  }

  function dwellPosts(fetchMock: ReturnType<typeof vi.fn>): Array<Record<string, unknown>> {
    return fetchMock.mock.calls
      .filter((c) => String(c[0]).includes('/disposition'))
      .map((c) => JSON.parse((c[1] as { body: string }).body))
      .filter((b) => b.kind === 'dwelled');
  }

  it('opening a result and dwelling past the threshold posts a DWELLED disposition', async () => {
    const fetchMock = await seedSearch();
    recordInspectorOpen('a');
    expect(dwellPosts(fetchMock)).toHaveLength(0); // not yet
    await vi.advanceTimersByTimeAsync(3100);
    const posts = dwellPosts(fetchMock);
    expect(posts).toHaveLength(1);
    expect(posts[0]).toEqual({ interactionId: 'iid-1', docId: 'a', kind: 'dwelled' });
  });

  it('closing before the threshold does NOT post DWELLED', async () => {
    const fetchMock = await seedSearch();
    recordInspectorOpen('a');
    await vi.advanceTimersByTimeAsync(1000);
    recordInspectorClose();
    await vi.advanceTimersByTimeAsync(5000);
    expect(dwellPosts(fetchMock)).toHaveLength(0);
  });

  it('switching results cancels the first dwell; only the surviving open fires', async () => {
    const fetchMock = await seedSearch();
    recordInspectorOpen('a');
    await vi.advanceTimersByTimeAsync(1000);
    recordInspectorOpen('b'); // switch cancels a's timer, arms b
    await vi.advanceTimersByTimeAsync(3100);
    const posts = dwellPosts(fetchMock);
    expect(posts).toHaveLength(1);
    expect(posts[0]!.docId).toBe('b');
  });

  it('a new query cancels a pending dwell (the user moved on)', async () => {
    const fetchMock = await seedSearch();
    recordInspectorOpen('a');
    await vi.advanceTimersByTimeAsync(1000);
    setQuery('different'); // cancels the dwell
    await vi.advanceTimersByTimeAsync(5000);
    expect(dwellPosts(fetchMock)).toHaveLength(0);
  });

  it('does not attribute dwell to a doc absent from the current results', async () => {
    const fetchMock = await seedSearch();
    recordInspectorOpen('not-a-result'); // e.g. a deep-link open — no join key
    await vi.advanceTimersByTimeAsync(3100);
    expect(dwellPosts(fetchMock)).toHaveLength(0);
  });
});

describe('collection enumeration observation', () => {
  it('POSTs only the scan bound to the observation endpoint and returns the parsed body', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({ facets: { collection: { 'mcp-ingest': 4 } } }),
    })) as unknown as typeof globalThis.fetch;
    vi.stubGlobal('fetch', fetchMock);
    try {
      const payload = await fetchCollectionFacet(50_000, 'http://test');
      expect(payload).toEqual({ facets: { collection: { 'mcp-ingest': 4 } } });
      const [url, init] = (fetchMock as unknown as ReturnType<typeof vi.fn>).mock.calls[0] as [
        string,
        { method: string; body: string },
      ];
      expect(url).toBe('http://test/api/knowledge/collection-facet');
      expect(init.method).toBe('POST');
      expect(JSON.parse(init.body)).toEqual({ maxDocsScanned: 50_000 });
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('throws on a non-2xx rather than reporting an empty corpus', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: false, status: 503 })) as unknown as typeof globalThis.fetch,
    );
    try {
      await expect(fetchCollectionFacet(50_000, 'http://test')).rejects.toThrow('HTTP 503');
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
