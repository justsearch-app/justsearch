// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it } from 'vitest';
import { Sv3Results } from '../Sv3Results.js';
import type { ResultsCard } from '../../../components/searchResults/ResultsCard.js';
import type { Sv3Filters } from '../Sv3Filters.js';
import { SV3_RESULTS_IDLE } from '../sv3-search.js';
afterEach(() => document.body.replaceChildren());
it.each([
  { name: 'failed', status: 'unreachable' as const, failure: 'Search unavailable' },
  { name: 'syntax invalid', status: 'unreachable' as const, failure: 'Unclosed quote' },
  { name: 'stale response', status: 'loading' as const, failure: '' },
])('$name withholds the card, count and filters', async ({ status, failure }) => {
  const results = new Sv3Results(); results.view = { ...SV3_RESULTS_IDLE, query: 'harbor', status, failure,
    filters: [{ key: 'facet-file_kind-pdf', kind: 'facet', field: 'file_kind', value: 'pdf', label: 'Type: pdf' }] };
  document.body.append(results); await results.updateComplete;
  expect(results.shadowRoot!.querySelector('jf-results-card')).toBeNull();
  expect(results.shadowRoot!.querySelector('[data-testid="sv3-results-filters"]')).toBeNull();
  expect(results.shadowRoot!.textContent).not.toContain('0 files');
  expect(results.shadowRoot!.querySelector(status === 'loading' ? '[aria-label="Searching"]' : '[data-testid="sv3-results-failed"]')).not.toBeNull();
});

it('a temporary pending response removes result chrome and restores the same open filter editor and draft', async () => {
  const results = new Sv3Results(); const ready = { ...SV3_RESULTS_IDLE, status: 'ready' as const, query: 'harbor',
    snapshot: { query: 'harbor', results: [], matchCount: 0, totalHits: 0, facetsTruncated: false, isSearching: false,
      processingTimeMs: 10, error: null, searchTrace: null } };
  results.view = ready; document.body.append(results); await results.updateComplete;
  const card = results.shadowRoot!.querySelector<ResultsCard>('jf-results-card')!; await card.updateComplete;
  const moreFilters = card.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="more-filters"]');
  expect(moreFilters).not.toBeNull();
  moreFilters!.click(); await card.updateComplete;
  const filters = card.shadowRoot!.querySelector<Sv3Filters>('jf-sv3-filters')!; await filters.updateComplete;
  const input = filters.shadowRoot!.querySelector<HTMLInputElement>('[data-filter-input="folder"]')!;
  input.value = 'unfinished folder'; input.dispatchEvent(new Event('input')); await filters.updateComplete;
  input.focus();
  results.view = { ...ready, status: 'loading', snapshot: null }; await results.updateComplete;
  expect(results.shadowRoot!.querySelector('jf-results-card')).toBeNull();
  results.view = ready; await results.updateComplete; await card.updateComplete;
  expect(results.shadowRoot!.querySelector('jf-results-card')).toBe(card);
  expect(card.shadowRoot!.querySelector('jf-sv3-filters')).toBe(filters);
  expect(filters.open).toBe(true); expect(input.value).toBe('unfinished folder');
  expect(filters.shadowRoot!.activeElement).toBe(input);
  const other = document.createElement('button'); other.textContent = 'Another action'; document.body.append(other);
  results.view = { ...ready, status: 'loading', snapshot: null }; await results.updateComplete; other.focus();
  results.view = ready; await results.updateComplete;
  expect(document.activeElement).toBe(other);
});
