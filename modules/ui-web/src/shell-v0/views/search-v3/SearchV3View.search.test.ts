// @vitest-environment happy-dom

/**
 * Search v3's wiring to the SHARED search store (tempdoc 822 Phase A1), driven since WP1.1 through
 * Search mode — Enter in the composer — and read off the shared results card.
 *
 * The store is the real one (`state/searchState.ts`) in every case here — mocking it would leave the
 * one thing this slice adds untested. What is stubbed is the store's single exit, the global fetch,
 * which is also why no case can reach the network.
 *
 * Two properties are asserted as MECHANISMS rather than appearances:
 *
 *  - **One send, one request.** The store's keystroke path debounces a pass and schedules a second;
 *    an explicit submit must supersede both. The cases advance past BOTH windows before counting.
 *  - **The count describes what renders.** The label is read back as a number and compared against
 *    the rows actually on screen, so a projection that counted one set and rendered another fails
 *    here rather than lying on screen.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SearchV3View.js';
import './Sv3Empty.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Empty } from './Sv3Empty.js';
import { resetSearchState } from '../../state/searchState.js';
import { matchCountLabel } from '../../components/searchResults/matchCountLabel.js';
import { MAIN_EMPTY, MAIN_UNREACHABLE } from './fixtures.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { __seedForTest as seedErrorCatalog, __resetForTest as resetErrorCatalog } from '../../../i18n/errorCatalog.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

/** A response hit in the wire shape the store parses (`fields.path` is the display identity). */
const hit = (path: string): Record<string, unknown> => ({ id: `doc:${path}`, fields: { path } });

const respond = (body: Record<string, unknown>): void => {
  fetchMock.mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve(body) });
};

/**
 * The calls to the ONE issuance exit. Since Phase F6 the window also READS on mount and on a claim
 * (the app-wide conversation list, the canonical thread record), so a bare call count would answer
 * a different question than "how many times did this window issue?" — the filter keeps the
 * assertion on issuance, which is the thing that must be exactly one.
 */
const searches = (): unknown[][] =>
  fetchMock.mock.calls.filter((call) => String(call[0]).includes('/api/knowledge/search'));

beforeEach(() => {
  // Phase F6 wired this window to APP-WIDE, process-lifetime authorities (the conversation store,
  // the per-tab reload pointer, the shared draft controller). Each is a module singleton or a
  // storage key, so a case that did not reset them would be reading the previous case's state.
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  fetchMock = vi
    .fn()
    .mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({ results: [] }) });
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  // The store is a module singleton: one case's results would otherwise be the next case's state.
  resetSearchState();
  resetErrorCatalog();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function mount(): Promise<SearchV3View & Mounted> {
  const el = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

async function region(el: Mounted, tag: string): Promise<Mounted> {
  const found = el.shadowRoot?.querySelector(tag) as Mounted | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

async function fieldOf(el: Mounted): Promise<HTMLTextAreaElement> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  return field;
}

async function type(el: Mounted, draft: string): Promise<HTMLTextAreaElement> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = await fieldOf(el);
  field.value = draft;
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  return field;
}

/**
 * Type a draft and press Enter in Search mode, the window's default since WP1.1 — the affordance a
 * user has, not an internal call. The keystroke path schedules the store's quick pass; Enter
 * supersedes it with the full search at once, so one Enter is one request.
 */
async function send(el: Mounted, draft: string): Promise<void> {
  const field = await type(el, draft);
  field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  await el.updateComplete;
}

/**
 * Settle the round trip: token resolution, the fetch, its `json()`, and then the renders (the
 * window's, the main surface's, the results region's and the card's). Each await is a macrotask
 * turn, which also drains the microtasks. Returns the results region.
 */
async function settle(el: Mounted): Promise<Mounted> {
  for (let turn = 0; turn < 6; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
  const main = await region(el, 'jf-sv3-main');
  const results = main.shadowRoot?.querySelector('jf-sv3-results') as Mounted | null;
  if (!results) throw new Error('no results region on the main surface');
  await results.updateComplete;
  const card = results.shadowRoot?.querySelector('jf-results-card') as Mounted | null;
  if (card) await card.updateComplete;
  return results;
}

const cardOf = (results: Mounted): Mounted | null =>
  results.shadowRoot?.querySelector('jf-results-card') as Mounted | null;

const rowsOf = (results: Mounted): HTMLElement[] => [
  ...(cardOf(results)?.shadowRoot?.querySelectorAll<HTMLElement>(
    '[data-testid="search-result-row"]',
  ) ?? []),
];

const textOf = (host: Mounted | null, testid: string): string =>
  host?.shadowRoot?.querySelector(`[data-testid="${testid}"]`)?.textContent?.trim() ?? '';

/**
 * The count label as the card shows it: its meta line, read off the screen, less the latency the
 * shared card appends (" · found in 0.02s"). Anything else on that line stays in, so the equality
 * below is still an equality.
 */
const countLabelOf = (results: Mounted): string =>
  (cardOf(results)?.shadowRoot?.querySelector('.meta-info')?.textContent ?? '')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/ · found in \S+$/, '');

async function emptyElementOf(results: Mounted, testid: string): Promise<Sv3Empty & Mounted> {
  const el = results.shadowRoot?.querySelector(`[data-testid="${testid}"]`) as
    | (Sv3Empty & Mounted)
    | null;
  if (!el) throw new Error(`no [data-testid="${testid}"] in the results region`);
  await el.updateComplete;
  return el;
}

describe('Enter in Search mode issues exactly one search, through the shared store', () => {
  it('runs one request for the draft and docks in the same act', async () => {
    vi.useFakeTimers();
    const el = await mount();
    await send(el, 'northfield lease');
    // Past the store's 200ms keystroke debounce AND its 600ms settle: Enter supersedes both, so a
    // second request here would be a search the user never asked for.
    await vi.advanceTimersByTimeAsync(2000);

    expect(searches()).toHaveLength(1);
    const [url, init] = searches()[0] as [unknown, { body: string }];
    // The endpoint is the shared store's, which is the observable form of "this window owns no client".
    expect(String(url)).toContain('/api/knowledge/search');
    expect(JSON.parse(init.body).query).toBe('northfield lease');
    // The morph still runs: the send is a search AND a state change, not one at the cost of the other.
    expect(el.getAttribute('composer-state')).toBe('docked');
  });

  it('never issues a search for an empty draft', async () => {
    vi.useFakeTimers();
    const el = await mount();
    await send(el, '   ');
    await vi.advanceTimersByTimeAsync(2000);
    expect(searches()).toHaveLength(0);
    expect(el.getAttribute('composer-state')).toBe('hero');
  });

  it('is NOT what a send on the ask tier does — that one talks to the model', async () => {
    // The ask tier is refused here because no model has reported in — so the observable fact is
    // that NOTHING was dispatched to the search store.
    vi.useFakeTimers();
    const el = await mount();
    (el as unknown as { tier: string }).tier = 'ask';
    await el.updateComplete;
    await type(el, 'northfield lease');
    const composer = await region(el, 'jf-sv3-composer');
    composer.shadowRoot
      ?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
      ?.click();
    await vi.advanceTimersByTimeAsync(2000);
    expect(searches()).toEqual([]);
  });
});

describe('the rows are the response, on the shared results card', () => {
  it('renders one row per hit, from the response fields', async () => {
    respond({
      results: [hit('Contracts/Northfield.pdf'), hit('Ops/Reviews/Q2.md')],
      totalHits: 2,
      matchCount: 2,
    });
    const el = await mount();
    await send(el, 'northfield');
    const results = await settle(el);

    const rows = rowsOf(results);
    expect(rows).toHaveLength(2);
    expect(rows.map((r) => r.querySelector('.title-text')?.textContent?.trim())).toEqual([
      'Northfield.pdf',
      'Q2.md',
    ]);
  });

  it('shows each hit’s path as the shared card does: its folders on the row, the full path on hover', async () => {
    respond({
      results: [hit('Contracts/Northfield.pdf'), hit('Ops/Reviews/Q2.md')],
      totalHits: 2,
      matchCount: 2,
    });
    const el = await mount();
    await send(el, 'northfield');
    const results = await settle(el);

    // Each row carries its own hit's path; the row order is not what this checks.
    const rows = rowsOf(results);
    expect(rows).toHaveLength(2);
    const pathOf = (title: string): Element | null | undefined =>
      rows
        .find((r) => r.querySelector('.title-text')?.textContent?.trim() === title)
        ?.querySelector('.path');
    const northfield = pathOf('Northfield.pdf');
    const q2 = pathOf('Q2.md');
    expect(northfield?.getAttribute('title')).toBe('Contracts/Northfield.pdf');
    expect(q2?.getAttribute('title')).toBe('Ops/Reviews/Q2.md');
    expect(northfield?.textContent).toContain('Contracts');
    expect(q2?.textContent).toContain('Ops');
    expect(q2?.textContent).toContain('Reviews');
  });

  it('counts what is on screen, through the shared label authority', async () => {
    respond({
      results: [hit('a/1.md'), hit('a/2.md'), hit('a/3.md'), hit('a/4.md')],
      totalHits: 40,
      matchCount: 12,
    });
    const el = await mount();
    await send(el, 'vendor');
    const results = await settle(el);

    const label = countLabelOf(results);
    expect(label).toBe(matchCountLabel(12, 4, false, 40, false));
    // The probe: whatever the label leads with is the number of rows actually rendered. A projection
    // that counted one set and rendered another (or a row list sliced on the way out) fails HERE.
    const leading = Number(/\d+/.exec(label)?.[0]);
    expect(leading).toBe(rowsOf(results).length);
    expect(rowsOf(results)).toHaveLength(4);
  });

  it('names the surplus rather than hiding it when more renders than matched exactly', async () => {
    respond({ results: [hit('a/1.md'), hit('a/2.md'), hit('a/3.md')], totalHits: 3, matchCount: 1 });
    const el = await mount();
    await send(el, 'freight');
    const results = await settle(el);

    const label = countLabelOf(results);
    expect(label).toBe(matchCountLabel(1, 3, false, 3, false));
    expect(Number(/\d+/.exec(label)?.[0])).toBe(rowsOf(results).length);
  });
});

describe('the four outcomes are distinct, and each says only what it knows', () => {
  it('shows the pending state while the request is in flight, then the answer', async () => {
    let release: (value: unknown) => void = () => undefined;
    fetchMock.mockReturnValue(
      new Promise((resolve) => {
        release = resolve;
      }),
    );
    const el = await mount();
    await send(el, 'insurance');
    const results = await settle(el);

    expect(results.shadowRoot?.querySelector('[data-testid="sv3-results-loading"]')).not.toBeNull();
    // Pending is not emptiness and not a result: neither verdict may be on screen yet.
    expect(results.shadowRoot?.querySelector('[data-testid="sv3-results-empty"]')).toBeNull();
    expect(cardOf(results)).toBeNull();
    expect(rowsOf(results)).toHaveLength(0);

    release({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ results: [hit('Admin/Insurance/2026.pdf')], matchCount: 1 }),
    });
    const settled = await settle(el);
    expect(settled.shadowRoot?.querySelector('[data-testid="sv3-results-loading"]')).toBeNull();
    expect(rowsOf(settled)).toHaveLength(1);
  });

  it('renders the spec zero state when the corpus really did answer with nothing', async () => {
    respond({ results: [], totalHits: 0, matchCount: 0 });
    const el = await mount();
    await send(el, 'zzzz');
    const results = await settle(el);

    const empty = await emptyElementOf(results, 'sv3-results-empty');
    expect(empty.shadowRoot?.querySelector('[data-testid="sv3-empty-title"]')?.textContent?.trim())
      .toBe(MAIN_EMPTY.title);
    expect(
      empty.shadowRoot?.querySelector('[data-testid="sv3-empty-description"]')?.textContent?.trim(),
    ).toBe(MAIN_EMPTY.description);
    // The roomier region gets the roomier padding — the spec's one breakpoint on this component.
    expect(empty.hasAttribute('roomy')).toBe(true);
    expect(rowsOf(results)).toHaveLength(0);
    expect(cardOf(results)).toBeNull();
    expect(results.shadowRoot?.querySelector('[data-testid="sv3-results-failed"]')).toBeNull();
  });

  it('explains a transport failure with safe recovery guidance', async () => {
    fetchMock.mockRejectedValue(new Error('Failed to fetch'));
    const el = await mount();
    await send(el, 'northfield');
    const results = await settle(el);

    const failed = await emptyElementOf(results, 'sv3-results-failed');
    expect(
      failed.shadowRoot?.querySelector('[data-testid="sv3-empty-title"]')?.textContent?.trim(),
    ).toBe(MAIN_UNREACHABLE.title);
    // The store's own words for the failure, kept as checkable detail beside the state.
    expect(textOf(results, 'sv3-results-failure-detail')).toContain('Open Health to check the connection');
    expect(textOf(results, 'sv3-results-failure-detail')).not.toContain('Failed to fetch');
    expect(failed.getAttribute('role')).toBe('alert');
    // ...and it is NOT the zero state: claiming "nothing matched" here would invent a fact about a
    // corpus nothing was ever asked of.
    expect(results.shadowRoot?.querySelector('[data-testid="sv3-results-empty"]')).toBeNull();
    expect(rowsOf(results)).toHaveLength(0);
    expect(cardOf(results)).toBeNull();
  });

  it('treats a refused response the same way, and words it differently from zero results', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 502, json: () => Promise.resolve({}) });
    const el = await mount();
    await send(el, 'northfield');
    const results = await settle(el);

    await emptyElementOf(results, 'sv3-results-failed');
    expect(textOf(results, 'sv3-results-failure-detail')).toContain(
      'Search could not be completed. Open Health',
    );
    // The two states must be distinguishable on screen, not merely in the code that picked them.
    expect(MAIN_UNREACHABLE.title).not.toBe(MAIN_EMPTY.title);
    expect(MAIN_UNREACHABLE.description).not.toBe(MAIN_EMPTY.description);
  });

  it('906: a typed 502 explanation reaches the live failure surface', async () => {
    seedErrorCatalog({ 'errors.INDEX_UNAVAILABLE': 'The search index is not available.' });
    fetchMock.mockResolvedValue({ ok: false, status: 502, json: async () => ({
      error: 'wire fallback', errorCode: 'INDEX_UNAVAILABLE', i18nKey: 'errors.INDEX_UNAVAILABLE', retryable: true,
    }) });
    const el = await mount();
    await send(el, 'northfield');
    const results = await settle(el);
    const failure = await emptyElementOf(results, 'sv3-results-failed');
    expect(failure.getAttribute('role')).toBe('alert');
    expect(textOf(results, 'sv3-results-failure-detail')).toContain(
      'The search index is not available. Try searching again.',
    );
    expect(results.shadowRoot?.querySelector('[data-testid="sv3-results-empty"]')).toBeNull();
    expect(searches()).toHaveLength(1);
  });

  it('recovers: a second search replaces the failure with the results it gets', async () => {
    fetchMock.mockRejectedValueOnce(new Error('Failed to fetch'));
    const el = await mount();
    await send(el, 'northfield');
    await settle(el);

    respond({ results: [hit('Contracts/Northfield.pdf')], totalHits: 1, matchCount: 1 });
    await send(el, 'northfield contract');
    const results = await settle(el);
    expect(results.shadowRoot?.querySelector('[data-testid="sv3-results-failed"]')).toBeNull();
    expect(rowsOf(results)).toHaveLength(1);
  });
});
