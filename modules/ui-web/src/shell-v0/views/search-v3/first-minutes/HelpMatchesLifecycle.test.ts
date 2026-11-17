// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { getSearchState, resetSearchState, setQuery } from '../../../state/searchState.js';
import { AVAILABLE } from '../../../state/availability.js';
import { Sv3SearchController } from '../sv3-search.js';
import { Sv3Results } from '../Sv3Results.js';

const quick = { results: [{ id: 'quick', fields: { path: '/quick' } }],
  helpMatches: { count: 3, capped: true } };
const ok = (body: unknown) => ({ ok: true, json: async () => body });

beforeEach(() => { vi.useFakeTimers(); resetSearchState(); });
afterEach(() => { document.body.replaceChildren(); resetSearchState(); vi.unstubAllGlobals(); vi.useRealTimers(); });

async function expectQuickResponseVisible(): Promise<void> {
  const host = { addController: vi.fn(), requestUpdate: vi.fn(), paneDocPath: () => null } as unknown as
    ConstructorParameters<typeof Sv3SearchController>[0];
  const controller = new Sv3SearchController(host);
  controller.hostConnected();
  try {
    controller.showing = true;
    const view = controller.view(AVAILABLE);
    expect(view.status).toBe('ready');
    expect(view.snapshot?.results[0]?.id).toBe('quick');
    const element = new Sv3Results(); element.view = view;
    document.body.append(element); await element.updateComplete;
    expect(element.shadowRoot!.querySelector('jf-results-card')).not.toBeNull();
    expect(element.shadowRoot!.querySelector('[data-testid="sv3-help-matches"]')?.textContent)
      .toContain('3+ pages');
  } finally { controller.hostDisconnected(); }
}

async function startRefinement(results = quick.results) {
  let settle!: (value: ReturnType<typeof ok> | { ok: boolean; status: number; json: () => Promise<unknown> }) => void;
  let fail!: (reason: Error) => void;
  const pending = new Promise<ReturnType<typeof ok>>((resolve, reject) => { settle = resolve; fail = reject; });
  const fetch = vi.fn().mockResolvedValueOnce(ok({ ...quick, results })).mockReturnValue(pending);
  vi.stubGlobal('fetch', fetch);
  setQuery('needle');
  await vi.advanceTimersByTimeAsync(300);
  expect(getSearchState().helpMatches).toEqual(quick.helpMatches);
  await vi.advanceTimersByTimeAsync(1000);
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(getSearchState().isRefining).toBe(results.length > 0);
  return { settle, fail };
}

it('keeps the quick Help line while refining and replaces it when the pass settles', async () => {
  const { settle } = await startRefinement();
  expect(getSearchState().helpMatches).toEqual(quick.helpMatches);
  settle(ok({ results: [{ id: 'refined' }], helpMatches: { count: 8, capped: false } }));
  await vi.advanceTimersByTimeAsync(0);
  expect(getSearchState().helpMatches).toEqual({ count: 8, capped: false });
  expect(getSearchState().results[0]?.id).toBe('refined');
});

it.each(['http', 'network'] as const)('keeps quick rows and Help after a %s refinement failure', async kind => {
  const { settle, fail } = await startRefinement();
  if (kind === 'network') fail(new Error('offline'));
  else settle({ ok: false, status: 502, json: async () => ({ errorCode: 'INDEX_UNAVAILABLE' }) });
  await vi.advanceTimersByTimeAsync(0);
  expect(getSearchState().results[0]?.id).toBe('quick');
  expect(getSearchState().helpMatches).toEqual(quick.helpMatches);
  expect(getSearchState().isRefining).toBe(false);
  await expectQuickResponseVisible();
});

it('a syntax error clears the quick Help line and rows', async () => {
  const { settle } = await startRefinement();
  settle({ ok: false, status: 400, json: async () => ({ errorCode: 'SEARCH_SYNTAX_INVALID' }) });
  await vi.advanceTimersByTimeAsync(0);
  expect(getSearchState().helpMatches).toBeUndefined();
  expect(getSearchState().results).toEqual([]);
});

it('a new query clears Help immediately and drops a stale refined count', async () => {
  const { settle } = await startRefinement();
  setQuery('different');
  expect(getSearchState().helpMatches).toBeUndefined();
  settle(ok({ results: [], helpMatches: { count: 8, capped: false } }));
  await vi.advanceTimersByTimeAsync(0);
  expect(getSearchState().helpMatches).toBeUndefined();
});

it('a successful refined response without a count retires the quick count', async () => {
  const { settle } = await startRefinement();
  settle(ok({ results: [] }));
  await vi.advanceTimersByTimeAsync(0);
  expect(getSearchState().helpMatches).toBeUndefined();
});

it('keeps a Help-only quick count through refinement and a transport failure', async () => {
  const { fail } = await startRefinement([]);
  expect(getSearchState().helpMatches).toEqual(quick.helpMatches);
  fail(new Error('offline'));
  await vi.advanceTimersByTimeAsync(0);
  expect(getSearchState().helpMatches).toEqual(quick.helpMatches);
});
