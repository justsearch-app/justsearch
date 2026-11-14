// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { Sv3Results } from '../Sv3Results.js';
import type { ResultsCard } from '../../../components/searchResults/ResultsCard.js';
import { SV3_RESULTS_IDLE } from '../sv3-search.js';
import { __resetAiStateForTest, __feedForTest, __feedContactForTest, setInstallState } from '../../../state/aiStateStore.js';
import { NAVIGATE_TO_SURFACE_EVENT } from '../../../controllers/navigateRequest.js';
beforeEach(() => __resetAiStateForTest());
afterEach(() => { document.body.replaceChildren(); vi.unstubAllGlobals(); });
async function mount(searchTrace: import('../../../../api/generated/schema-types/search-trace.js').SearchTrace | null) {
  const el = new Sv3Results(); el.view = { ...SV3_RESULTS_IDLE, status: 'empty', query: 'private query',
    snapshot: { query: 'private query', results: [], matchCount: 0, totalHits: 0, facetsTruncated: false,
      isSearching: false, error: null, processingTimeMs: 40, searchTrace } };
  document.body.append(el); await el.updateComplete; return el;
}
it('S5-12 literal-only empty screen explains words without repeating the query', async () => {
  const el = await mount({ stages: [{ id: 'sparse-retrieval', status: 'executed' },
    { id: 'dense-retrieval', status: 'skipped' }, { id: 'splade-retrieval', status: 'skipped' }] });
  expect(el.shadowRoot!.textContent).toContain('No file contains these words');
  expect(el.shadowRoot!.textContent).toContain('Search looks for the words as typed. Try fewer or different ones.');
  expect(el.shadowRoot!.textContent).not.toContain('private query');
  expect(el.shadowRoot!.querySelector('[data-testid="empty-add-folder"]')).not.toBeNull();
});
it('S5-12 unknown execution uses the general empty message and no AI advice', async () => {
  const el = await mount(null);
  expect(el.shadowRoot!.textContent).toContain('Nothing matches this search');
  expect(el.shadowRoot!.querySelector('jf-results-card')).toBeNull();
  expect(el.shadowRoot!.querySelector('[data-testid="empty-ai"]')).toBeNull();
});
it('S5-5 empty filtered results retain removable restrictions and offer Clear filters', async () => {
  const el = await mount(null); el.view = { ...el.view,
    filters: [{ key: 'facet-file_kind-pdf', kind: 'facet', field: 'file_kind', value: 'pdf', label: 'Type: pdf' }],
    facetSelections: { file_kind: ['pdf'] } }; await el.updateComplete;
  expect(el.shadowRoot!.textContent).toContain('Filters are on');
  const card = el.shadowRoot!.querySelector<ResultsCard>('jf-results-card');
  expect(card).not.toBeNull();
  await card!.updateComplete;
  expect(card!.shadowRoot!.querySelector('[data-testid="more-filters"]')).not.toBeNull();
  expect(el.shadowRoot!.querySelector('[data-testid="empty-clear-filters"]')).not.toBeNull();
  expect(el.shadowRoot!.querySelector('[aria-label="Remove filter: Type: pdf"]')).not.toBeNull();
  el.view = { ...el.view, filters: [], facetSelections: {} };
  await el.updateComplete;
  expect(el.shadowRoot!.querySelector('jf-results-card')).toBeNull();
});
const literal = { stages: [{ id: 'sparse-retrieval', status: 'executed' },
  { id: 'dense-retrieval', status: 'skipped' }, { id: 'splade-retrieval', status: 'skipped' }] } as const;
function liveAi(installed: boolean, installing: boolean) {
  __feedForTest({ status: { worker: { core: { indexState: 'IDLE', ownSearchableDocuments: 8 } } } as never,
    inference: { mode: 'offline', available: false } as never });
  __feedContactForTest(); setInstallState(installed, installing);
}
it.each([false, true])('S5-12 known optional models show navigation only, installing=%s', async installing => {
  liveAi(false, installing);
  const fetched = vi.fn(async (url: unknown) => new Response(JSON.stringify({ url: String(url) }))); vi.stubGlobal('fetch', fetched);
  const el = await mount({ stages: [...literal.stages] });
  const row = el.shadowRoot!.querySelector('[data-testid="empty-ai"]');
  expect(row).not.toBeNull();
  expect(row!.textContent).toContain(installing ? 'The AI models are being installed.' : 'Needs the optional AI models.');
  const navigated = vi.fn(); document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, navigated);
  try {
    row!.querySelector<HTMLButtonElement>('button')!.click();
    expect(navigated).toHaveBeenCalledTimes(1);
    expect((navigated.mock.calls[0]![0] as CustomEvent).detail).toEqual({ surfaceId: 'core.brain-surface' });
    expect(fetched.mock.calls.filter(call => /install|search|dispatch/.test(String(call[0])))).toHaveLength(0);
    expect(el.view.query).toBe('private query');
  } finally { document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, navigated); }
});
it('S5-12 ready models or learned execution suppress AI advice as availability changes', async () => {
  liveAi(false, false); const el = await mount({ stages: [...literal.stages] });
  expect(el.shadowRoot!.querySelector('[data-testid="empty-ai"]')).not.toBeNull();
  setInstallState(true, false); for (let n = 0; n < 10; n++) await Promise.resolve(); await el.updateComplete;
  expect(el.shadowRoot!.querySelector('[data-testid="empty-ai"]')).toBeNull();
  setInstallState(false, false);
  el.view = { ...el.view, snapshot: { ...el.view.snapshot!, searchTrace: { stages: [{ id: 'splade-retrieval', status: 'executed' }] } } };
  await el.updateComplete;
  expect(el.shadowRoot!.querySelector('[data-testid="empty-ai"]')).toBeNull();
  expect(el.shadowRoot!.textContent).toContain('Nothing matches this search');
});
it.each(['loading', 'unreachable', 'idle'] as const)('S5-15 %s never presents nothing found', async status => {
  const el = await mount(null); el.view = { ...el.view, status, snapshot: null }; await el.updateComplete;
  expect(el.shadowRoot!.querySelector('[data-testid="sv3-results-empty"]')).toBeNull();
});
