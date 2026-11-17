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
