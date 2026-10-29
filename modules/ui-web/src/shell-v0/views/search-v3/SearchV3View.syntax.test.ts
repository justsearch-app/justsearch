// @vitest-environment happy-dom
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { getSearchState, resetSearchState, restoreSearch } from '../../state/searchState.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { __seedForTest as seedErrors, __resetForTest as resetErrors } from '../../../i18n/errorCatalog.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };
let fetchMock: ReturnType<typeof vi.fn>;
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body });
const reason = 'Close the quoted phrase with a matching quote.';
const invalid = () => response({ errorCode: 'SEARCH_SYNTAX_INVALID', error: reason,
  i18nKey: 'errors.SEARCH_SYNTAX_INVALID', errorClass: 'VALIDATION', retryable: false }, 400);
const good = (id: string) => response({ results: [{ id, fields: { path: '/' + id } }],
  totalHits: 1, matchCount: 1, facets: { language: { en: 1 } } });
const searches = () => fetchMock.mock.calls.filter(call => String(call[0]).includes('/api/knowledge/search'));

beforeEach(() => {
  vi.useFakeTimers();
  localStorage.clear(); sessionStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest();
  resetSearchState();
  seedErrors({ 'errors.SEARCH_SYNTAX_INVALID': 'Check the search syntax.' });
  fetchMock = vi.fn(async () => response({ results: [], conversations: [] }));
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState(); resetErrors();
  vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks();
});
async function mount() {
  const view = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  document.body.append(view); await view.updateComplete; return view;
}
async function composer(view: Mounted) {
  await view.updateComplete;
  const result = view.shadowRoot!.querySelector('jf-sv3-composer') as Mounted;
  await result.updateComplete; return result;
}
async function type(view: Mounted, value: string) {
  const host = await composer(view);
  const field = host.shadowRoot!.querySelector('[data-testid="sv3-composer-input"]') as HTMLTextAreaElement;
  field.value = value; field.dispatchEvent(new Event('input'));
  await host.updateComplete; return field;
}
async function settle(view: Mounted, ms = 0) {
  await vi.advanceTimersByTimeAsync(ms);
  await view.updateComplete; await (await composer(view)).updateComplete;
}
async function submit(view: Mounted, value: string) {
  const field = await type(view, value);
  field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  await settle(view); return field;
}

it('x6 explicit submit keeps text and renders the Engine reason under the bar without stale rows', async () => {
  const view = await mount();
  fetchMock.mockImplementation(async (url: unknown) => String(url).includes('/api/knowledge/search') ? good('old') : response({}));
  await submit(view, 'ordinary');
  expect(getSearchState().results[0]?.id).toBe('old');
  fetchMock.mockImplementation(async (url: unknown) => String(url).includes('/api/knowledge/search') ? invalid() : response({}));
  const field = await submit(view, '"');
  const host = await composer(view);
  expect(host.shadowRoot!.querySelector('[data-testid="sv3-search-error"]')?.textContent).toBe(reason);
  expect(field.value).toBe('"');
  expect(field.getAttribute('aria-invalid')).toBe('true');
  expect(getSearchState().results).toEqual([]);
  expect(getSearchState().facets).toBeNull();
  expect(getSearchState().isSearching).toBe(false);
  expect(getSearchState().isRefining).toBe(false);
  expect(JSON.parse(String(searches().at(-1)?.[1]?.body)).querySyntax).toBe('search');
});

it('x6 automatic 400 stays quiet and schedules no refined request then a correction resumes', async () => {
  const view = await mount();
  fetchMock.mockImplementation(async (url: unknown) => String(url).includes('/api/knowledge/search') ? invalid() : response({}));
  await type(view, '"'); await settle(view, 1500);
  expect(searches()).toHaveLength(1);
  expect(JSON.parse(String(searches()[0]?.[1]?.body)).pipeline.sparseEnabled).toBe(true);
  expect(getSearchState().error).toBeNull();
  expect((await composer(view)).shadowRoot!.querySelector('[data-testid="sv3-search-error"]')).toBeNull();
  expect(getSearchState().isSearching).toBe(false);
  expect(getSearchState().isRefining).toBe(false);
  expect(getSearchState().slowSearch).toBe(false);
  fetchMock.mockImplementation(async (url: unknown) => String(url).includes('/api/knowledge/search') ? good('corrected') : response({}));
  await type(view, '"budget review"'); await settle(view, 1500);
  expect(searches()).toHaveLength(3);
  expect(getSearchState().results[0]?.id).toBe('corrected');
  expect(getSearchState().passStage).toBe('refined');
});

it('x5 withholds prior counts and facets while the new query awaits its Engine classification', async () => {
  const view = await mount();
  fetchMock.mockImplementation(async (url: unknown) => String(url).includes('/api/knowledge/search') ? good('old') : response({}));
  await submit(view, 'ordinary');
  expect(getSearchState().facets).toEqual({ language: { en: 1 } });
  await type(view, 'title:budget');
  expect(getSearchState().countsPending).toBe(true);
  expect(getSearchState().matchCount).toBe(0);
  await view.updateComplete;
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as Mounted;
  await main.updateComplete;
  const results = main.shadowRoot!.querySelector('jf-sv3-results') as Mounted;
  await results.updateComplete;
  expect(results.shadowRoot!.querySelector('jf-results-card')).toBeNull();
  expect(getSearchState().isSearching).toBe(true);
});

it('x6 each edit supersedes an older success and an older error body', async () => {
  const view = await mount();
  let release!: (value: unknown) => void;
  fetchMock.mockImplementation((url: unknown) => String(url).includes('/api/knowledge/search')
    ? new Promise(resolve => { release = resolve; }) : Promise.resolve(response({})));
  await type(view, 'valid'); await settle(view, 200);
  expect(searches()).toHaveLength(1);
  await type(view, '"');
  release(good('stale'));
  await settle(view);
  expect(getSearchState().results).toEqual([]);
  let releaseBody!: (value: unknown) => void;
  fetchMock.mockImplementation(async (url: unknown) => String(url).includes('/api/knowledge/search')
    ? { ...invalid(), json: () => new Promise(resolve => { releaseBody = resolve; }) } : response({}));
  await settle(view, 200);
  fetchMock.mockImplementation(async (url: unknown) => String(url).includes('/api/knowledge/search') ? good('current') : response({}));
  await type(view, '"corrected"');
  releaseBody({ errorCode: 'SEARCH_SYNTAX_INVALID', error: reason });
  await settle(view, 1500);
  expect(getSearchState().error).toBeNull();
  expect(getSearchState().results[0]?.id).toBe('current');
  expect(searches()).toHaveLength(4);
});

it('x5 advanced quick results keep counts and facets pending until refinement', async () => {
  const view = await mount();
  fetchMock.mockImplementation(async (url: unknown) => String(url).includes('/api/knowledge/search')
    ? response({ advancedSyntax: true, results: [{ id: 'strict', fields: { path: '/strict' } }],
      matchCount: 1, totalHits: 1 }) : response({}));
  await type(view, 'title:budget'); await settle(view, 250);
  expect(getSearchState().passStage).toBe('quick');
  expect(getSearchState().countsPending).toBe(true);
  expect(getSearchState().facets).toBeNull();
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as Mounted; await main.updateComplete;
  const results = main.shadowRoot!.querySelector('jf-sv3-results') as Mounted; await results.updateComplete;
  const card = results.shadowRoot!.querySelector('jf-results-card') as Mounted; await card.updateComplete;
  expect(card.shadowRoot!.querySelector('.meta-info')?.textContent).toContain('counts pending');
  await settle(view, 1000);
  expect(getSearchState().passStage).toBe('refined');
  expect(getSearchState().countsPending).toBe(false);
});

it('x8 legacy URL restoration keeps operator text and uses Search syntax', async () => {
  const view = await mount();
  restoreSearch({ query: '"budget review" -draft' });
  await settle(view, 1500);
  expect(getSearchState().query).toBe('"budget review" -draft');
  expect(searches()).toHaveLength(2);
  for (const call of searches()) expect(JSON.parse(String(call[1]?.body))).toMatchObject({
    query: '"budget review" -draft', querySyntax: 'search',
  });
});
