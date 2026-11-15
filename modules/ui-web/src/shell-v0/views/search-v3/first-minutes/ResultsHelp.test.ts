// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import '../Sv3Results.js';
import { SV3_RESULTS_IDLE, type Sv3ResultsView } from '../sv3-search.js';
import { requestSurfaceNavigation } from '../../../controllers/navigateRequest.js';
import { buildSearchIntent } from '../../../state/searchState.js';
import { restrictionWire } from '../../../state/searchFiltersState.js';

vi.mock('../../../controllers/navigateRequest.js', { spy: true });
afterEach(() => { document.body.replaceChildren(); vi.restoreAllMocks(); });

it.each(['quick', 'refined'] as const)('%s ordinary search excludes Help without changing Ask restrictions', stage => {
  expect(buildSearchIntent('just', {}, stage)).toHaveProperty('excludedCollections', ['justsearch-help']);
  expect(buildSearchIntent('just', {}, stage, {}, 'agent-history')).not.toHaveProperty('excludedCollections');
  expect(restrictionWire({})).not.toHaveProperty('excludedCollections');
});

it.each([
  ['ready', 1, false, '1 page'], ['empty', 3, false, '3 pages'], ['ready', 7, true, '7+ pages'],
] as const)('shows a quiet Help action after %s results: %s', async (status, count, capped, label) => {
  const el = document.createElement('jf-sv3-results');
  el.view = { ...SV3_RESULTS_IDLE, status, query: 'just', snapshot: {
    query: 'just', results: [], matchCount: 0, totalHits: 999, helpMatches: { count, capped },
    facetsTruncated: false, isSearching: false, processingTimeMs: 1, error: null, searchTrace: null,
  } } as unknown as Sv3ResultsView;
  document.body.append(el); await el.updateComplete;
  const line = el.shadowRoot!.querySelector('[data-testid="sv3-help-matches"]');
  expect(line?.textContent?.replace(/\s+/g, ' ').trim()).toBe(`Also in JustSearch help: ${label}`);
  const button = line?.querySelector('button');
  expect(button?.getAttribute('aria-label')).toBe(`Open Help (${label})`);
  button?.click();
  expect(requestSurfaceNavigation).toHaveBeenCalledWith('core.help-surface');
  if (status === 'empty') expect(el.shadowRoot!.querySelector('[data-testid="sv3-results-empty"]')).not.toBeNull();
});

it.each([undefined, { count: 0, capped: false }])('omits the line when Help did not yield a positive count', async helpMatches => {
  const el = document.createElement('jf-sv3-results');
  el.view = { ...SV3_RESULTS_IDLE, status: 'ready', snapshot: {
    query: 'just', results: [], matchCount: 0, totalHits: 999, helpMatches,
  } } as unknown as Sv3ResultsView;
  document.body.append(el); await el.updateComplete;
  expect(el.shadowRoot!.querySelector('[data-testid="sv3-help-matches"]')).toBeNull();
});
