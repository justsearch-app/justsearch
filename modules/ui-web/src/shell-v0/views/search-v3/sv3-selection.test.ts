// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest';
import { Sv3SearchController } from './sv3-search.js';
import { clearSelection, getSelection, setSingleSelection, DEFAULT_CAPABILITIES_BY_KIND } from '../../state/selectionState.js';
import { resetSearchState, setQuery, submitSearch } from '../../state/searchState.js';
const ownedSearches: Sv3SearchController[] = [];
const host = { addController() {}, requestUpdate() {} } as never;
async function searchWithList(): Promise<Sv3SearchController> {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ results: [
    { id: 'a', fields: { path: '/a.md' } }, { id: 'b', fields: { path: '/b.md' } },
  ], totalHits: 2, matchCount: 2 }))));
  const search = new Sv3SearchController(host);
  ownedSearches.push(search);
  search.hostConnected(); search.show(); setQuery('test'); submitSearch();
  await vi.waitFor(() => expect(search.snapshot?.results).toHaveLength(2));
  return search;
}
afterEach(async () => {
  for (const search of ownedSearches.splice(0)) search.hostDisconnected();
  clearSelection(); resetSearchState();
  await new Promise<void>(resolve => setTimeout(resolve, 0));
  vi.unstubAllGlobals();
});
it('C5 A8 publishes the chosen hit and chosen result set, and clears on deselect and leave', async () => {
  const search = await searchWithList(); search.select(['a']);
  expect(getSelection()).toMatchObject({ surfaceId: 'core.search-v3-surface', items: [{ kind: 'search-hit', hitId: 'a', path: '/a.md' }] });
  search.select(['a', 'b']);
  expect(getSelection().items[0]).toMatchObject({ kind: 'result-set', items: [{ id: '/a.md', kind: 'doc' }, { id: '/b.md', kind: 'doc' }] });
  search.select([]); expect(getSelection().items).toEqual([]);
  search.select(['b']); search.hostDisconnected(); expect(getSelection().items).toEqual([]);
});
it('C5 A8 leaving Search does not clear another surface selection', async () => {
  const search = await searchWithList(); search.select(['a']);
  setSingleSelection({ kind: 'browse-node', path: '/other', nodeKind: 'file', capabilities: DEFAULT_CAPABILITIES_BY_KIND['browse-node'] }, 'core.library-surface');
  search.rerun(); // loading metadata alone must not take another selection's ownership
  expect(getSelection().surfaceId).toBe('core.library-surface');
  search.hostDisconnected(); expect(getSelection().surfaceId).toBe('core.library-surface');
});

it('C5 A8 a replacement result list and a changed query cannot leave a stale Find related hit', async () => {
  let hits = [{ id: 'a', fields: { path: '/a.md' } }];
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ results: hits, totalHits: hits.length, matchCount: hits.length }))));
  const search = new Sv3SearchController(host);
  ownedSearches.push(search);
  search.hostConnected(); search.show();
  try {
    setQuery('one'); submitSearch();
    await vi.waitFor(() => expect(search.snapshot?.results).toHaveLength(1));
    search.select(['a']);
    expect(getSelection().items[0]).toMatchObject({ kind: 'search-hit', path: '/a.md' });
    hits = [{ id: 'b', fields: { path: '/b.md' } }];
    submitSearch();
    await vi.waitFor(() => expect(search.snapshot?.results[0]?.id).toBe('b'));
    expect(getSelection().items).toEqual([]);
    search.select(['b']);
    expect(getSelection().items[0]).toMatchObject({ path: '/b.md' });
    setQuery('two');
    expect(getSelection().items).toEqual([]);
  } finally { search.hostDisconnected(); }
});
