// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * fm-pane-match — the search state carries each excerpt region's position in the document
 * (`startChar`, `endChar`) as the answer sent it, beside its text: the side pane opens a result at
 * its first passage from these, so a hit that drops them would open every result at the beginning.
 */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { getSearchState, resetSearchState, setQuery, setSearchApiBase } from './searchState.js';
import { __resetSearchFiltersForTest } from './searchFiltersState.js';

beforeEach(() => {
  vi.useFakeTimers();
  setSearchApiBase('http://test');
  __resetSearchFiltersForTest();
});
afterEach(() => {
  resetSearchState();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  __resetSearchFiltersForTest();
});

it('a hit keeps every region\'s text, line and character positions in the order the answer gave them', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    json: () => Promise.resolve({
      results: [{ id: 'doc:/m.md', fields: { path: '/m.md' }, excerptRegions: [
        { text: 'the harbour gate', startChar: 8123, endChar: 8139, approxLine: 370, matchSpans: [] },
        { text: 'harbour lights', startChar: 9001, endChar: 9015, approxLine: 410, matchSpans: [] },
      ] }],
      totalHits: 1,
    }),
  }));
  setQuery('harbour');
  await vi.runAllTimersAsync();
  const regions = getSearchState().results[0]?.excerptRegions;
  expect(regions?.map(({ text, approxLine, startChar, endChar }) => ({ text, approxLine, startChar, endChar }))).toEqual([
    { text: 'the harbour gate', approxLine: 370, startChar: 8123, endChar: 8139 },
    { text: 'harbour lights', approxLine: 410, startChar: 9001, endChar: 9015 },
  ]);
});

/**
 * A long file is found through its chunks (SearchResponseBuilder: a chunk hit's result has the parent
 * file's id, and its `fields` map carries the chunk's stored fields, `parent_doc_id`,
 * `chunk_start_char` and `chunk_end_char` among them, as strings). Its regions' positions count from
 * the chunk's start, so the chunk's place in the file has to travel with the hit.
 */
it('R1 a chunk hit keeps its chunk\'s place in the file as the answer gave it, its regions untouched', async () => {
  const region = { text: 'the harbour gate', startChar: 345, endChar: 361, approxLine: 2401,
    matchSpans: [{ field: 'content', startChar: 4, endChar: 11, term: 'harbour' }] };
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    json: () => Promise.resolve({
      results: [
        { id: '/deep-long.txt', score: 1, fields: { path: '/deep-long.txt', doc_id: 'chunk-7f3a', parent_doc_id: '/deep-long.txt',
          chunk_index: '96', chunk_total: '120', chunk_start_char: '249619', chunk_end_char: '251219', chunk_start_line: '2399' },
          matchSpans: [{ field: 'content', startChar: 0, endChar: 7, term: 'harbour' }], excerptRegions: [region] },
        { id: '/m.md', score: 0.5, fields: { path: '/m.md', chunk_start_char: '99' }, excerptRegions: [{ ...region }] },
      ],
      totalHits: 2,
    }),
  }));
  setQuery('harbour');
  await vi.runAllTimersAsync();
  const [chunkHit, wholeHit] = getSearchState().results;
  expect(chunkHit?.chunk).toEqual({ startChar: '249619', endChar: '251219' });
  expect(chunkHit?.excerptRegions).toEqual([region]);
  // A whole-file hit (no parent_doc_id) has no chunk, whatever else its fields hold.
  expect(wholeHit?.chunk).toBeUndefined();
  expect(wholeHit?.excerptRegions).toEqual([region]);
});
