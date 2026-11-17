// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
/**
 * fm-pane-match — the reader opened on a search result's passage (`match`): the window is asked for
 * around the passage when it lies beyond the document's beginning, the passage is tinted and
 * scrolled to, and the note names "the match". The passage is a hint: when it cannot be confirmed at
 * its offsets the pane opens as it does without one — the beginning, today's note, no notice.
 *
 * The stub behaves like `/api/preview` (offset, length, `truncated`), so a wrong window is visible.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './DocumentPane.js';
import type { DocumentPane } from './DocumentPane.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';

/** 600 numbered lines of 24 characters: 14,400 characters, far longer than one opening window. */
const LONG = Array.from({ length: 600 }, (_, i) => `line ${String(i).padStart(4, '0')} plain words..`).join('\n');
const PASSAGE = 'The harbour gate opens at dawn for the inspection boats.';
const lineAt = (n: number): string => `line ${String(n).padStart(4, '0')} plain words..`;
/** The long document with the passage written in place of line `n`. */
const withPassageAt = (n: number): string => LONG.replace(lineAt(n), PASSAGE);
const span = (doc: string, text = PASSAGE): { startChar: number; endChar: number; excerpt: string } => {
  const startChar = doc.indexOf(text);
  if (startChar < 0) throw new Error('fixture: passage not in document');
  return { startChar, endChar: startChar + text.length, excerpt: text };
};

interface Served { offset: number; max: number }

function serve(doc: string | ((path: string) => string), refuse: (served: Served) => boolean = () => false): { calls: Served[] } {
  const calls: Served[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: string) => {
    const url = new URL(input, 'http://localhost');
    const served = { offset: Number(url.searchParams.get('offsetChars')), max: Number(url.searchParams.get('maxChars')) };
    calls.push(served);
    if (refuse(served)) return { ok: false, status: 400, json: async () => ({ error: 'offset_chars splits a Unicode surrogate pair' }) };
    const text = typeof doc === 'string' ? doc : doc(url.searchParams.get('docId') ?? '');
    return {
      ok: true,
      status: 200,
      json: async () => ({ content: text.slice(served.offset, served.offset + served.max),
        truncated: served.offset + served.max < text.length, textProvenance: null, visualExtractionEvidence: null }),
    };
  }));
  return { calls };
}

async function settle(el: DocumentPane): Promise<void> {
  for (let i = 0; i < 6; i++) {
    await new Promise(resolve => setTimeout(resolve, 0));
    await el.updateComplete;
  }
}

async function open(path: string, setup: (el: DocumentPane) => void): Promise<DocumentPane> {
  const el = document.createElement('jf-document-pane') as DocumentPane;
  setup(el);
  el.docPath = path;
  document.body.append(el);
  await settle(el);
  return el;
}

let scrolledTo: Element[] = [];
const tinted = (el: DocumentPane): string[] =>
  [...el.shadowRoot!.querySelectorAll('pre.source span.hl-weak, pre.source span.hl-strong')].map(s => s.textContent!.replace(/\n$/, ''));
const shownLines = (el: DocumentPane): string[] =>
  [...el.shadowRoot!.querySelectorAll('pre.source span[data-line]')].map(s => s.textContent!.replace(/\n$/, ''));
const note = (el: DocumentPane): string | null => el.shadowRoot!.querySelector('.window-note')?.textContent?.trim() ?? null;
const notices = (el: DocumentPane): string[] =>
  [...el.shadowRoot!.querySelectorAll('.anchor-notice, .span-notice, jf-error-alert')].map(n => n.textContent!.trim());

beforeEach(() => {
  document.body.innerHTML = '';
  setShowDeveloperTools(false);
  scrolledTo = [];
  Element.prototype.scrollIntoView = function scrollIntoView(this: Element): void { scrolledTo.push(this); };
});
afterEach(() => {
  setShowDeveloperTools(false);
  vi.unstubAllGlobals();
});

describe('M1 the pane opens at the result\'s passage', () => {
  it('a passage beyond the first window: the window around it is asked for, the passage tinted, found words marked, scrolled to', async () => {
    const doc = withPassageAt(400);
    const { calls } = serve(doc);
    const match = span(doc);
    const el = await open('/docs/long-manual.txt', pane => { pane.match = match; pane.markQuery = 'harbour'; });
    // One request, not at the document's beginning, and covering the passage.
    expect(calls).toHaveLength(1);
    expect(calls[0]!.offset).toBeGreaterThan(0);
    expect(calls[0]!.offset).toBeLessThanOrEqual(match.startChar);
    expect(calls[0]!.offset + calls[0]!.max).toBeGreaterThanOrEqual(match.endChar);
    expect(tinted(el)).toEqual([PASSAGE]);
    const target = scrolledTo.at(-1);
    expect(target?.textContent?.replace(/\n$/, '')).toBe(PASSAGE);
    expect([...target!.querySelectorAll('mark.hl')].map(m => m.textContent)).toEqual(['harbour']);
    expect(notices(el)).toEqual([]);
  });

  it('a passage inside the first window but below the fold: the beginning is asked for, the passage tinted and scrolled to', async () => {
    const doc = withPassageAt(120);
    const { calls } = serve(doc);
    const el = await open('/docs/long-manual.txt', pane => { pane.match = span(doc); });
    expect(calls.map(c => c.offset)).toEqual([0]);
    expect(shownLines(el)[0]).toBe(lineAt(0));
    expect(tinted(el)).toEqual([PASSAGE]);
    expect(scrolledTo.at(-1)?.textContent?.replace(/\n$/, '')).toBe(PASSAGE);
  });
});

describe('M2 the note for a window around a match', () => {
  it('says "around the match", not the cited passage and not the beginning', async () => {
    const doc = withPassageAt(400);
    serve(doc);
    const el = await open('/docs/long-manual.txt', pane => { pane.match = span(doc); });
    expect(note(el)).toBe('Showing the part of this document around the match.');
  });

  it('a citation keeps its own wording (keep)', async () => {
    const doc = withPassageAt(400);
    serve(doc);
    const el = await open('/docs/long-manual.txt', pane => { pane.citation = { ...span(doc), sentenceText: null }; });
    expect(note(el)).toBe('Showing the part of this document around the cited passage.');
    expect(tinted(el)).toEqual([PASSAGE]);
  });
});

describe('M3 a passage that cannot be confirmed opens the pane as today', () => {
  const asToday = (el: DocumentPane): void => {
    expect(shownLines(el)[0]).toBe(lineAt(0));
    expect(note(el)).toBe('Showing the beginning of a longer document.');
    expect(notices(el)).toEqual([]);
    expect(tinted(el)).toEqual([]);
    expect(scrolledTo).toEqual([]);
  };

  it('the file changed since it was read: the window around the passage is tried, then the beginning', async () => {
    const doc = withPassageAt(400);
    const match = span(doc);
    const { calls } = serve(LONG); // the passage is no longer in the file
    const el = await open('/docs/long-manual.txt', pane => { pane.match = match; });
    expect(calls).toHaveLength(2);
    expect(calls[0]!.offset).toBeGreaterThan(0);
    expect(calls[1]!.offset).toBe(0);
    asToday(el);
  });

  it('the file is now shorter than the passage\'s position', async () => {
    const short = LONG.slice(0, 6000);
    const { calls } = serve(short);
    const el = await open('/docs/long-manual.txt', pane => { pane.match = { startChar: 9000, endChar: 9056, excerpt: PASSAGE }; });
    expect(calls.map(c => c.offset > 0)).toEqual([true, false]);
    asToday(el);
  });

  it('the preview refuses the window around the passage: the beginning, no failure shown', async () => {
    const doc = withPassageAt(400);
    const { calls } = serve(doc, served => served.offset > 0);
    const el = await open('/docs/long-manual.txt', pane => { pane.match = span(doc); });
    expect(calls.map(c => c.offset > 0)).toEqual([true, false]);
    asToday(el);
  });

  it.each([
    ['missing', { startChar: undefined, endChar: undefined }],
    ['not numbers', { startChar: '9000', endChar: '9056' }],
    ['not finite', { startChar: Number.NaN, endChar: Number.POSITIVE_INFINITY }],
    ['inverted', { startChar: 9056, endChar: 9000 }],
  ])('offsets %s: only the beginning is asked for (keep)', async (_label, offsets) => {
    const { calls } = serve(withPassageAt(400));
    const el = await open('/docs/long-manual.txt', pane => { pane.match = { excerpt: PASSAGE, ...offsets } as never; });
    expect(calls.map(c => c.offset)).toEqual([0]);
    asToday(el);
  });

  it('inside the first window but not confirmed: no second request, nothing tinted, no notice (keep)', async () => {
    const doc = withPassageAt(120);
    const match = span(doc);
    const { calls } = serve(LONG);
    const el = await open('/docs/long-manual.txt', pane => { pane.match = match; });
    expect(calls.map(c => c.offset)).toEqual([0]);
    asToday(el);
  });

  it('a lost preview answer shows today\'s failure (keep)', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('connection reset'); }));
    const el = await open('/docs/long-manual.txt', pane => { pane.match = span(withPassageAt(400)); });
    expect(notices(el)).toEqual(['This source could not be read. Please try again when the index is ready.']);
  });
});

describe('M4 a citation is unchanged by a match beside it', () => {
  it('a citation that cannot be confirmed still says so, and the match is not read (keep)', async () => {
    const doc = withPassageAt(400);
    const { calls } = serve(LONG);
    const el = await open('/docs/long-manual.txt', pane => {
      pane.citation = { ...span(doc), sentenceText: null };
      pane.match = { startChar: 0, endChar: 24, excerpt: lineAt(0) };
    });
    expect(calls).toHaveLength(1);
    expect(notices(el)).toEqual([
      'The cited passage could not be confirmed at its recorded position — this document may have changed since it was indexed, so nothing is highlighted.',
    ]);
    expect(tinted(el)).toEqual([]);
  });
});

describe('M6 a second document while the first one\'s window is loading', () => {
  it('ends on the second document\'s passage', async () => {
    const first = withPassageAt(400);
    const second = withPassageAt(300).replace(PASSAGE, 'Second harbour passage, where the ferry waits by the pier.');
    const held: Array<() => void> = [];
    const calls: Array<{ path: string; offset: number }> = [];
    vi.stubGlobal('fetch', vi.fn((input: string) => {
      const url = new URL(input, 'http://localhost');
      const path = url.searchParams.get('docId') ?? '';
      const offset = Number(url.searchParams.get('offsetChars'));
      const max = Number(url.searchParams.get('maxChars'));
      calls.push({ path, offset });
      const doc = path === '/docs/first.txt' ? first : second;
      const answer = { ok: true, status: 200, json: async () => ({ content: doc.slice(offset, offset + max), truncated: offset + max < doc.length }) };
      // The first document's answer is held until after the second one has landed.
      if (path === '/docs/first.txt') return new Promise(resolve => held.push(() => resolve(answer)));
      return Promise.resolve(answer);
    }));
    const el = await open('/docs/first.txt', pane => { pane.match = span(first); });
    el.docPath = '/docs/second.txt';
    el.match = span(second, 'Second harbour passage, where the ferry waits by the pier.');
    await settle(el);
    held.forEach(release => release());
    await settle(el);
    expect(calls.map(c => [c.path, c.offset > 0])).toEqual([['/docs/first.txt', true], ['/docs/second.txt', true]]);
    expect(tinted(el)).toEqual(['Second harbour passage, where the ferry waits by the pier.']);
    expect(note(el)).toBe('Showing the part of this document around the match.');
  });
});

describe('M7 a scan\'s page markers', () => {
  it('stay hidden, and the passage after a marker is found and tinted', async () => {
    const scan = [...Array.from({ length: 300 }, (_, i) => (i % 40 === 0 ? `--- OCR page ${i / 40 + 1} ---` : lineAt(i))),
      '--- OCR page 9 ---', PASSAGE, 'after the passage'].join('\n');
    const { calls } = serve(scan);
    const el = await open('/docs/scan.pdf', pane => { pane.match = span(scan); });
    expect(calls[0]!.offset).toBeGreaterThan(0);
    expect(tinted(el)).toEqual([PASSAGE]);
    expect(shownLines(el).some(line => line.startsWith('--- OCR page'))).toBe(false);
    // The line numbers still address the raw text: the passage keeps its number with the markers counted.
    const numbered = el.shadowRoot!.querySelector('pre.source span.hl-weak')!.getAttribute('data-line');
    expect(el.content.split('\n')[Number(numbered)]).toBe(PASSAGE);
  });
});
