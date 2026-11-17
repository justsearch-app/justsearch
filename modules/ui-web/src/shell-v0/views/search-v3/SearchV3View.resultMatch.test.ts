// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * fm-pane-match — opening a search result in Search's side pane opens the reader at the result's
 * first returned passage, taken from the result list in memory when it is opened. The real window,
 * the real shared search store and the real reader; only `fetch` is stubbed: the search answer
 * carries excerpt regions with offsets, and `/api/preview` serves by offset.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { ResultsCard } from '../../components/searchResults/ResultsCard.js';
import type { DocumentPane } from '../../components/documentPane/DocumentPane.js';
import { getSearchState, resetSearchState, submitSearch } from '../../state/searchState.js';
import { getInspectorState, resetInspectorState, setOpen, setSelected } from '../../state/inspectorState.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };
const MANUAL = '/docs/long-manual.txt';
const GUIDE = '/docs/guide.txt';
const BARE = '/docs/bare.txt';

const filler = (n: number): string => Array.from({ length: n }, (_, i) => `filler line ${String(i).padStart(4, '0')} ....`).join('\n');
/** Documents whose passage lies far past any opening window (past character 8,000). */
const MANUAL_PASSAGE = 'The harbour gate opens at dawn for the inspection boats.';
const MANUAL_TEXT = `${filler(369)}\n${MANUAL_PASSAGE}\n${filler(200)}`;
const MANUAL_START = MANUAL_TEXT.indexOf(MANUAL_PASSAGE);
const GUIDE_PASSAGE = 'Guide: the harbour ferry waits by the northern pier.';
const GUIDE_TEXT = `${filler(423)}\n${GUIDE_PASSAGE}\n${filler(200)}`;
const DOCS: Record<string, string> = { [MANUAL]: MANUAL_TEXT, [GUIDE]: GUIDE_TEXT, [BARE]: filler(400) };

type Region = { text?: string; approxLine?: number; startChar?: unknown; endChar?: unknown };
const regionOf = (doc: string, passage: string): Region => {
  const startChar = DOCS[doc]!.indexOf(passage);
  return { text: passage, approxLine: 0, startChar, endChar: startChar + passage.length };
};
let regions: Record<string, Region[]>;
let previews: Array<{ path: string; offset: number }>;
let hold: ((path: string) => boolean) | null;
let held: Array<() => void>;

const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status });

beforeEach(() => {
  sessionStorage.clear(); localStorage.clear(); resetSearchState(); resetInspectorState(); setShowDeveloperTools(false);
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest();
  Element.prototype.scrollIntoView = vi.fn();
  regions = {
    [MANUAL]: [regionOf(MANUAL, MANUAL_PASSAGE)],
    [GUIDE]: [regionOf(GUIDE, GUIDE_PASSAGE)],
    [BARE]: [],
  };
  previews = []; hold = null; held = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/knowledge/search')) {
      return json({ results: [MANUAL, GUIDE, BARE].map(path => ({ id: `doc:${path}`, fields: { path }, excerptRegions: regions[path] })),
        totalHits: 3, matchCount: 3 });
    }
    if (url.includes('/api/preview')) {
      const params = new URL(url, 'http://localhost').searchParams;
      const path = params.get('docId') ?? '';
      const offset = Number(params.get('offsetChars'));
      const max = Number(params.get('maxChars'));
      previews.push({ path, offset });
      const text = DOCS[path] ?? '';
      const answer = json({ content: text.slice(offset, offset + max), truncated: offset + max < text.length });
      if (hold?.(path)) return new Promise<Response>(resolve => held.push(() => resolve(answer)));
      return answer;
    }
    if (url.includes('/api/chat/runs/live')) return json({ runs: [] });
    if (url.includes('/api/chat/conversations')) return json({ sessions: [] });
    return json({});
  }));
});

afterEach(async () => {
  held.forEach(release => release());
  document.body.replaceChildren(); resetSearchState(); resetInspectorState();
  await new Promise(resolve => setTimeout(resolve, 220));
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});

async function pump(el?: Mounted): Promise<void> {
  for (let i = 0; i < 12; i++) {
    await new Promise(resolve => setTimeout(resolve, 0));
    if (el?.isConnected) await el.updateComplete;
  }
}
async function region(el: Mounted, tag: string): Promise<Mounted> {
  const found = el.shadowRoot?.querySelector(tag) as Mounted;
  expect(found, `<${tag}> is rendered`).toBeTruthy(); await found.updateComplete; return found;
}
async function mount(): Promise<{ view: SearchV3View & Mounted; card: ResultsCard & Mounted; field: HTMLTextAreaElement }> {
  const view = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  document.body.append(view); await pump(view);
  const composer = await region(view, 'jf-sv3-composer');
  const field = composer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-input"]')!;
  field.value = 'harbour'; field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  await pump(view);
  const main = await region(view, 'jf-sv3-main');
  const results = await region(main, 'jf-sv3-results');
  const card = await region(results, 'jf-results-card') as ResultsCard & Mounted;
  expect(card.snapshot?.results).toHaveLength(3);
  return { view, card, field };
}
async function openResult(view: Mounted, card: ResultsCard, path: string): Promise<void> {
  card.dispatchEvent(new CustomEvent('card-open', { bubbles: true, composed: true, detail: { id: `doc:${path}` } }));
  await pump(view);
  const pane = documentPane(view);
  if (pane) await pump(pane as unknown as Mounted);
}
function documentPane(view: Mounted): DocumentPane | null {
  const pane = view.shadowRoot!.querySelector('jf-sv3-pane');
  return (pane?.shadowRoot?.querySelector('jf-document-pane') as DocumentPane | null) ?? null;
}
const tinted = (view: Mounted): string[] =>
  [...documentPane(view)!.shadowRoot!.querySelectorAll('pre.source span.hl-weak')].map(s => s.textContent!.replace(/\n$/, ''));
const firstLine = (view: Mounted): string | undefined =>
  documentPane(view)!.shadowRoot!.querySelector('pre.source span[data-line]')?.textContent?.replace(/\n$/, '');
const noteOf = (view: Mounted): string | undefined => documentPane(view)!.shadowRoot!.querySelector('.window-note')?.textContent?.trim();

describe('M1 a result opens in the pane at its first returned passage', () => {
  it('asks the reader for the part around the passage and tints it', async () => {
    const { view, card } = await mount();
    await openResult(view, card, MANUAL);
    expect(previews).toHaveLength(1);
    expect(previews[0]!.path).toBe(MANUAL);
    expect(previews[0]!.offset).toBeGreaterThan(0);
    expect(previews[0]!.offset).toBeLessThanOrEqual(MANUAL_START);
    expect(tinted(view)).toEqual([MANUAL_PASSAGE]);
    expect(noteOf(view)).toBe('Showing the part of this document around the match.');
  });

  it('moving to another result while the pane is open shows that result\'s passage', async () => {
    const { view, card } = await mount();
    await openResult(view, card, MANUAL);
    await openResult(view, card, GUIDE);
    expect(previews.map(p => [p.path, p.offset > 0])).toEqual([[MANUAL, true], [GUIDE, true]]);
    expect(tinted(view)).toEqual([GUIDE_PASSAGE]);
  });
});

describe('M3 a passage whose offsets are unusable opens the file as today', () => {
  it.each([
    ['not numbers', { startChar: String(MANUAL_START), endChar: String(MANUAL_START + 56) }],
    ['inverted', { startChar: MANUAL_START + 56, endChar: MANUAL_START }],
  ])('offsets %s: the beginning, no tint, no notice (keep)', async (_label, offsets) => {
    regions[MANUAL] = [{ text: MANUAL_PASSAGE, approxLine: 0, ...offsets }];
    const { view, card } = await mount();
    await openResult(view, card, MANUAL);
    expect(previews.map(p => p.offset)).toEqual([0]);
    expect(firstLine(view)).toBe('filler line 0000 ....');
    expect(tinted(view)).toEqual([]);
    expect(documentPane(view)!.shadowRoot!.querySelector('.anchor-notice')).toBeNull();
    expect(noteOf(view)).toBe('Showing the beginning of a longer document.');
  });
});

describe('M4 a pane opened from elsewhere opens as today', () => {
  it('the shared selection opens a listed file at its beginning, even after it was open at its passage (keep)', async () => {
    const { view, card } = await mount();
    await openResult(view, card, GUIDE);
    setSelected({ id: `doc:${MANUAL}`, title: 'long-manual.txt', path: MANUAL });
    setOpen(true);
    await pump(view); await pump(documentPane(view) as unknown as Mounted);
    expect(documentPane(view)!.docPath).toBe(MANUAL);
    expect(previews.filter(p => p.path === MANUAL)).toEqual([{ path: MANUAL, offset: 0 }]);
    expect(tinted(view)).toEqual([]);
    expect(firstLine(view)).toBe('filler line 0000 ....');
  });
});

describe('M5 nothing new is stored', () => {
  it('the passage\'s position is in no stored value and the shared selection keeps its shape (keep)', async () => {
    const { view, card } = await mount();
    const before = getInspectorState().selected;
    await openResult(view, card, MANUAL);
    expect(getInspectorState().selected).toEqual(before);
    const stored = [localStorage, sessionStorage].flatMap(store =>
      Array.from({ length: store.length }, (_, i) => store.getItem(store.key(i)!) ?? ''));
    expect(stored.filter(value => value.includes(String(MANUAL_START)) || value.includes('startChar'))).toEqual([]);
  });
});

describe('M6 races', () => {
  it('a newer search answer while the pane is open does not move it, and choosing the same result again does not jump', async () => {
    const { view, card } = await mount();
    await openResult(view, card, MANUAL);
    const shown = documentPane(view)!.match;
    expect(tinted(view)).toEqual([MANUAL_PASSAGE]);
    // The same search answers again, now with another passage of the open file first.
    regions[MANUAL] = [regionOf(MANUAL, 'filler line 0010 ....'), regionOf(MANUAL, MANUAL_PASSAGE)];
    submitSearch();
    await pump(view);
    expect(getSearchState().results.find(hit => hit.path === MANUAL)?.excerptRegions?.[0]?.text).toBe('filler line 0010 ....');
    expect(documentPane(view)!.match).toBe(shown);
    await openResult(view, card, MANUAL);
    expect(documentPane(view)!.match).toBe(shown);
    expect(previews).toHaveLength(1);
    expect(tinted(view)).toEqual([MANUAL_PASSAGE]);
  });

  it('choosing a second result while the first one\'s window is loading ends on the second one\'s passage', async () => {
    const { view, card } = await mount();
    hold = path => path === MANUAL;
    await openResult(view, card, MANUAL);
    await openResult(view, card, GUIDE);
    held.forEach(release => release());
    await pump(view); await pump(documentPane(view) as unknown as Mounted);
    expect(previews.map(p => [p.path, p.offset > 0])).toEqual([[MANUAL, true], [GUIDE, true]]);
    expect(documentPane(view)!.docPath).toBe(GUIDE);
    expect(tinted(view)).toEqual([GUIDE_PASSAGE]);
  });

  it('a result without passages opens at the beginning, also after one with a passage (keep)', async () => {
    const { view, card } = await mount();
    await openResult(view, card, MANUAL);
    await openResult(view, card, BARE);
    expect(previews.filter(p => p.path === BARE)).toEqual([{ path: BARE, offset: 0 }]);
    expect(tinted(view)).toEqual([]);
  });

  it('emptying the field closes the pane (keep)', async () => {
    const { view, card, field } = await mount();
    await openResult(view, card, MANUAL);
    expect(view.paneDocPath).toBe(MANUAL);
    field.value = ''; field.dispatchEvent(new Event('input'));
    await pump(view);
    expect(view.paneDocPath).toBeNull();
    expect(documentPane(view)).toBeNull();
  });
});
