// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * fm-pane-match — opening a search result in Search's side pane opens the reader at the result's
 * first returned passage, taken from the result list in memory when it is opened. The real window,
 * the real shared search store and the real reader; only `fetch` is stubbed: the search answer is a
 * raw answer in the Engine's wire shape, and `/api/preview` serves by offset.
 *
 * The answer's shape is the Engine's (SearchResponseBuilder.java, SearchResultMapper.java and
 * api/generated/schema-types/knowledge-search-response.ts): a long file is found through one of its
 * chunks, and such a hit carries the chunk's stored fields as strings in `fields`
 * (`parent_doc_id`, `chunk_start_char`, `chunk_end_char`, ...) while its excerpt regions' positions
 * count from the chunk's start. A whole-file hit has no `parent_doc_id` and file positions.
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
const DEEP = '/docs/deep-long.txt';
const REPEAT = '/docs/repeated.txt';
const WHOLE = '/docs/whole-long.txt';
const FIRST = '/docs/first-lines.txt';
const PATHS = [MANUAL, GUIDE, BARE, DEEP, REPEAT, WHOLE, FIRST];

const filler = (n: number): string => Array.from({ length: n }, (_, i) => `filler line ${String(i).padStart(4, '0')} ....`).join('\n');
/** Documents whose passage lies far past any opening window (past character 8,000). */
const MANUAL_PASSAGE = 'The harbour gate opens at dawn for the inspection boats.';
const MANUAL_TEXT = `${filler(369)}\n${MANUAL_PASSAGE}\n${filler(200)}`;
const MANUAL_START = MANUAL_TEXT.indexOf(MANUAL_PASSAGE);
const GUIDE_PASSAGE = 'Guide: the harbour ferry waits by the northern pier.';
const GUIDE_TEXT = `${filler(423)}\n${GUIDE_PASSAGE}\n${filler(200)}`;
const WHOLE_PASSAGE = 'Whole: the harbour office keeps the tide tables.';
const WHOLE_TEXT = `${filler(400)}\n${WHOLE_PASSAGE}\n${filler(50)}`;
const FIRST_PASSAGE = 'First: the harbour opens early.';
const FIRST_TEXT = `${filler(2)}\n${FIRST_PASSAGE}\n${filler(400)}`;

/** Lines of one length (24 characters with the break) up to exactly `length` characters, ending in a break. */
const fillTo = (length: number): string => {
  const lines: string[] = [];
  let used = 0;
  for (let i = 0; length - used >= 48; i++, used += 24) lines.push(`deep filler ${String(i).padStart(6, '0')} ....`);
  if (length - used > 0) lines.push('.'.repeat(length - used - 1));
  return lines.map(line => `${line}\n`).join('');
};
/** The verifier's case: deep-long.txt's passage at character 249,964, its chunk starting at 249,619. */
const DEEP_CHUNK_START = 249_619;
const DEEP_AT = 249_964;
const DEEP_PASSAGE = (() => {
  const lines = Array.from({ length: 6 }, (_, i) => `Deep passage ${i}: the harbour gate opens at dawn for the boats.`);
  const text = lines.join('\n');
  return text + '.'.repeat(420 - text.length);
})();
const DEEP_TEXT = `${fillTo(DEEP_AT)}${DEEP_PASSAGE}\n${fillTo(6000)}`;

/**
 * A file of identical lines (23 characters with the break): the passage's text is everywhere, so only
 * its position says where it is. Its chunk starts at line 2,400; the region is the chunk's lines
 * 15-19 (characters 345-459 of the chunk), so the file has the same text at 345 near its top.
 */
const SAME = 'the harbour wall line.';
const REPEAT_TEXT = Array.from({ length: 3000 }, () => SAME).join('\n');
const REPEAT_CHUNK_START = 2400 * 23;
const REPEAT_REGION = { startChar: 345, endChar: 345 + 5 * 23 - 1 };
const REPEAT_PASSAGE = REPEAT_TEXT.slice(REPEAT_CHUNK_START + REPEAT_REGION.startChar, REPEAT_CHUNK_START + REPEAT_REGION.endChar);

const DOCS: Record<string, string> = { [MANUAL]: MANUAL_TEXT, [GUIDE]: GUIDE_TEXT, [BARE]: filler(400), [DEEP]: DEEP_TEXT,
  [REPEAT]: REPEAT_TEXT, [WHOLE]: WHOLE_TEXT, [FIRST]: FIRST_TEXT };

type Region = { text?: string; approxLine?: number; startChar?: unknown; endChar?: unknown; matchSpans?: unknown[] };
/** One hit of the answer: a chunk hit's stored chunk fields (strings), and its regions. */
type Answer = { chunk?: Record<string, string>; regions: Region[] };
/** The chunk fields SearchResponseBuilder puts on a chunk hit's `fields` (ChunkSearchOps' allowlist). */
const chunkFields = (path: string, start: number | string, end: number | string, extra: Record<string, string> = {}): Record<string, string> => ({
  doc_id: `chunk-${path.length}-${String(start)}`, parent_doc_id: path, chunk_index: '3', chunk_total: '9',
  chunk_start_char: String(start), chunk_end_char: String(end), chunk_start_line: '0', chunk_end_line: '0', ...extra,
});
/** A whole-file hit's region: the passage's own place in the file. */
const regionOf = (doc: string, passage: string): Region => {
  const startChar = DOCS[doc]!.indexOf(passage);
  return { text: passage, approxLine: 0, startChar, endChar: startChar + passage.length, matchSpans: [] };
};
/** A chunk hit for the passage, as the Engine sends it: the region counts from the chunk's start. */
const chunkHitOf = (doc: string, passage: string, chunkStart = Math.max(0, DOCS[doc]!.indexOf(passage) - 1500)): Answer => {
  const at = DOCS[doc]!.indexOf(passage);
  const chunkEnd = Math.min(DOCS[doc]!.length, chunkStart + 4000);
  return { chunk: chunkFields(doc, chunkStart, chunkEnd),
    regions: [{ text: passage, approxLine: 0, startChar: at - chunkStart, endChar: at - chunkStart + passage.length, matchSpans: [] }] };
};
let answers: Record<string, Answer>;
let previews: Array<{ path: string; offset: number; max: number }>;
let hold: ((path: string) => boolean) | null;
let held: Array<() => void>;

const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status });

beforeEach(() => {
  sessionStorage.clear(); localStorage.clear(); resetSearchState(); resetInspectorState(); setShowDeveloperTools(false);
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest();
  Element.prototype.scrollIntoView = vi.fn();
  answers = {
    [MANUAL]: chunkHitOf(MANUAL, MANUAL_PASSAGE),
    [GUIDE]: chunkHitOf(GUIDE, GUIDE_PASSAGE),
    [BARE]: { regions: [] },
    [DEEP]: { chunk: chunkFields(DEEP, DEEP_CHUNK_START, DEEP_CHUNK_START + 1600),
      regions: [{ text: DEEP_PASSAGE, approxLine: 2401, startChar: DEEP_AT - DEEP_CHUNK_START, endChar: DEEP_AT - DEEP_CHUNK_START + 420, matchSpans: [] }] },
    [REPEAT]: { chunk: chunkFields(REPEAT, REPEAT_CHUNK_START, REPEAT_CHUNK_START + 1200),
      regions: [{ text: REPEAT_PASSAGE, approxLine: 2416, ...REPEAT_REGION, matchSpans: [] }] },
    [WHOLE]: { regions: [regionOf(WHOLE, WHOLE_PASSAGE)] },
    [FIRST]: { regions: [regionOf(FIRST, FIRST_PASSAGE)] },
  };
  previews = []; hold = null; held = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/knowledge/search')) {
      return json({ results: PATHS.map(path => ({ id: `doc:${path}`, score: 1, fields: { path, ...answers[path]!.chunk },
        matchedFields: ['content'], matchSpans: [], excerptRegions: answers[path]!.regions })),
        totalHits: PATHS.length, matchCount: PATHS.length });
    }
    if (url.includes('/api/preview')) {
      const params = new URL(url, 'http://localhost').searchParams;
      const path = params.get('docId') ?? '';
      const offset = Number(params.get('offsetChars'));
      const max = Number(params.get('maxChars'));
      previews.push({ path, offset, max });
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
  expect(card.snapshot?.results).toHaveLength(PATHS.length);
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
    ['not numbers', { startChar: '1500', endChar: '1556' }],
    ['inverted', { startChar: 1556, endChar: 1500 }],
  ])('offsets %s: the beginning, no tint, no notice (keep)', async (_label, offsets) => {
    answers[MANUAL] = { ...answers[MANUAL]!, regions: [{ text: MANUAL_PASSAGE, approxLine: 0, ...offsets }] };
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
    expect(previews.filter(p => p.path === MANUAL).map(p => p.offset)).toEqual([0]);
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
    answers[MANUAL] = { chunk: chunkFields(MANUAL, 0, 4000), regions: [regionOf(MANUAL, 'filler line 0010 ....')] };
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
    expect(previews.filter(p => p.path === BARE).map(p => p.offset)).toEqual([0]);
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

/**
 * Where in the FILE the tinted lines are: the pane's text is the served slice of the last request,
 * less a leading half line, so its first character's file position is known; each tinted line's
 * `data-line` then gives its own. Returns the first tinted line's file position and the tinted text.
 */
function tintedPlace(view: Mounted, path: string): { at: number; text: string } | null {
  const pane = documentPane(view)!;
  const spans = [...pane.shadowRoot!.querySelectorAll('pre.source span.hl-weak')];
  if (spans.length === 0) return null;
  const last = previews.filter(p => p.path === path).at(-1)!;
  const served = DOCS[path]!.slice(last.offset, last.offset + last.max);
  const contentAt = last.offset + served.length - pane.content.length;
  expect(served.endsWith(pane.content), 'the pane shows the end of the served slice').toBe(true);
  const lines = pane.content.split('\n');
  const first = Number(spans[0]!.getAttribute('data-line'));
  const at = contentAt + lines.slice(0, first).reduce((sum, line) => sum + line.length + 1, 0);
  return { at, text: spans.map(s => s.textContent!.replace(/\n$/, '')).join('\n') };
}

describe('R1 a chunk hit opens at the chunk\'s start plus the region\'s place', () => {
  it('deep-long.txt: chunk at 249,619, region 345-765: the window around 249,964, the passage tinted, the match note', async () => {
    const { view, card } = await mount();
    await openResult(view, card, DEEP);
    const asked = previews.filter(p => p.path === DEEP);
    expect(asked).toHaveLength(1);
    expect(asked[0]!.offset).toBeGreaterThan(DEEP_AT - 5000);
    expect(asked[0]!.offset).toBeLessThanOrEqual(DEEP_AT);
    expect(asked[0]!.offset + asked[0]!.max).toBeGreaterThanOrEqual(DEEP_AT + 420);
    expect(tintedPlace(view, DEEP)).toEqual({ at: DEEP_AT, text: DEEP_PASSAGE });
    expect(noteOf(view)).toBe('Showing the part of this document around the match.');
    expect(documentPane(view)!.shadowRoot!.querySelector('.anchor-notice')).toBeNull();
    // The row keeps the region as the answer gave it: its own text, its chunk-relative numbers.
    expect(getSearchState().results.find(hit => hit.path === DEEP)?.excerptRegions?.[0]).toMatchObject({ startChar: 345, endChar: 765 });
  });
});

describe('R2 a chunk hit without a usable chunk start has no position', () => {
  it.each([
    ['chunk start missing', () => { const { chunk_start_char: _drop, ...rest } = answers[REPEAT]!.chunk!; return rest; }],
    ['chunk start "abc"', () => ({ ...answers[REPEAT]!.chunk!, chunk_start_char: 'abc' })],
    ['chunk start "-5"', () => ({ ...answers[REPEAT]!.chunk!, chunk_start_char: '-5' })],
    ['chunk start "12.5"', () => ({ ...answers[REPEAT]!.chunk!, chunk_start_char: '12.5' })],
    ['region beyond the chunk\'s length', () => ({ ...answers[REPEAT]!.chunk!, chunk_end_char: String(REPEAT_CHUNK_START + 400) })],
  ])('%s: only the beginning is asked for, nothing tinted, today\'s note', async (_label, chunk) => {
    answers[REPEAT] = { ...answers[REPEAT]!, chunk: chunk() };
    const { view, card } = await mount();
    await openResult(view, card, REPEAT);
    expect(previews.filter(p => p.path === REPEAT).map(p => p.offset)).toEqual([0]);
    // The file holds the region's text at its chunk-relative numbers too: they must not be read as file positions.
    expect(REPEAT_TEXT.slice(REPEAT_REGION.startChar, REPEAT_REGION.endChar)).toBe(REPEAT_PASSAGE);
    expect(tinted(view)).toEqual([]);
    expect(noteOf(view)).toBe('Showing the beginning of a longer document.');
    expect(documentPane(view)!.shadowRoot!.querySelector('.anchor-notice')).toBeNull();
  });

  it('a first chunk starting at 3 (leading whitespace stripped) is placed at 3 + the region\'s start', async () => {
    answers[WHOLE] = { chunk: chunkFields(WHOLE, 3, 12_003), regions: [{ ...regionOf(WHOLE, WHOLE_PASSAGE) }] };
    const at = WHOLE_TEXT.indexOf(WHOLE_PASSAGE);
    answers[WHOLE]!.regions[0] = { ...answers[WHOLE]!.regions[0], startChar: at - 3, endChar: at - 3 + WHOLE_PASSAGE.length };
    const { view, card } = await mount();
    await openResult(view, card, WHOLE);
    expect(tintedPlace(view, WHOLE)).toEqual({ at, text: WHOLE_PASSAGE });
  });
});

describe('R3 repeated text: the pane lands on the passage\'s own position', () => {
  it('a file of identical lines, the passage in a chunk at line 2,400: that place, not the same text near the top', async () => {
    const { view, card } = await mount();
    await openResult(view, card, REPEAT);
    const asked = previews.filter(p => p.path === REPEAT);
    const at = REPEAT_CHUNK_START + REPEAT_REGION.startChar;
    expect(asked).toHaveLength(1);
    expect(asked[0]!.offset).toBeGreaterThan(at - 5000);
    expect(asked[0]!.offset).toBeLessThanOrEqual(at);
    expect(asked[0]!.offset + asked[0]!.max).toBeGreaterThanOrEqual(at + REPEAT_PASSAGE.length);
    expect(tintedPlace(view, REPEAT)).toEqual({ at, text: REPEAT_PASSAGE });
    expect(tinted(view)).toHaveLength(5);
    expect(noteOf(view)).toBe('Showing the part of this document around the match.');
  });
});

describe('R5 whole-file hits are placed at the region\'s own span (keep)', () => {
  it('a passage past the first window: the window around it, tinted at its place', async () => {
    const { view, card } = await mount();
    await openResult(view, card, WHOLE);
    const at = WHOLE_TEXT.indexOf(WHOLE_PASSAGE);
    expect(previews.filter(p => p.path === WHOLE)[0]!.offset).toBeGreaterThan(0);
    expect(tintedPlace(view, WHOLE)).toEqual({ at, text: WHOLE_PASSAGE });
  });

  it('a passage in the first lines: the beginning, the word visible and tinted', async () => {
    const { view, card } = await mount();
    await openResult(view, card, FIRST);
    expect(previews.filter(p => p.path === FIRST).map(p => p.offset)).toEqual([0]);
    expect(firstLine(view)).toBe('filler line 0000 ....');
    expect(tintedPlace(view, FIRST)).toEqual({ at: FIRST_TEXT.indexOf(FIRST_PASSAGE), text: FIRST_PASSAGE });
    expect(noteOf(view)).toBe('Showing the beginning of a longer document.');
  });
});
