// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
/**
 * fm-rest Q6/Q7 — the pane's reading view does not show a scan's page markers ("--- OCR page N ---"
 * on a line of its own), in source-line rendering and in rendered Markdown alike. What the pane
 * holds is unchanged: line numbers, character anchors and the excerpt witness answer the raw text,
 * and the developer source view still shows it as extracted.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './DocumentPane.js';
import type { DocumentPane } from './DocumentPane.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';

const SCAN = [
  'scanned-delivery',
  '--- OCR page 1 ---',
  'HARBOUR INSPECTION',
  'Cobalt marmalade delivery record',
  '--- OCR page 2 ---',
  'See --- OCR page 1 --- for the crates.',
  'No damage found.',
].join('\n');

function serve(doc: string): void {
  vi.stubGlobal('fetch', vi.fn(async (input: string) => {
    const url = new URL(input, 'http://localhost');
    const offset = Number(url.searchParams.get('offsetChars') ?? 0);
    const max = Number(url.searchParams.get('maxChars') ?? 5000);
    return {
      ok: true,
      status: 200,
      json: async () => ({ content: doc.slice(offset, offset + max), truncated: offset + max < doc.length, textProvenance: 'ocr', visualExtractionEvidence: null }),
    };
  }));
}

async function open(path: string, setup: (el: DocumentPane) => void = () => {}): Promise<DocumentPane> {
  const el = document.createElement('jf-document-pane') as DocumentPane;
  setup(el);
  el.docPath = path;
  document.body.append(el);
  for (let i = 0; i < 4; i++) {
    await new Promise(resolve => setTimeout(resolve, 0));
    await el.updateComplete;
  }
  return el;
}

const lines = (el: DocumentPane): Array<[string | null, string]> =>
  [...el.shadowRoot!.querySelectorAll('pre.source span[data-line]')].map(span => [span.getAttribute('data-line'), span.textContent!.replace(/\n$/, '')]);
const scrollText = (el: DocumentPane): string => el.shadowRoot!.querySelector('.scroll-region')!.textContent ?? '';

beforeEach(() => {
  document.body.innerHTML = '';
  setShowDeveloperTools(false);
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(() => {
  setShowDeveloperTools(false);
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('Q6 the reading view leaves out lines that are only a scan page marker', () => {
  it('PDF source lines: the marker lines are gone, every other line keeps its number', async () => {
    serve(SCAN);
    const el = await open('/docs/scanned-delivery.pdf');
    expect(lines(el)).toEqual([
      ['0', 'scanned-delivery'],
      ['2', 'HARBOUR INSPECTION'],
      ['3', 'Cobalt marmalade delivery record'],
      ['5', 'See --- OCR page 1 --- for the crates.'],
      ['6', 'No damage found.'],
    ]);
  });

  it('PDF source lines: found words stay marked around the hidden marker', async () => {
    serve(SCAN);
    const el = await open('/docs/scanned-delivery.pdf', pane => { pane.markQuery = 'delivery'; });
    const marked = [...el.shadowRoot!.querySelectorAll('pre.source span[data-line] mark')]
      .map(mark => [mark.closest('span[data-line]')!.getAttribute('data-line'), mark.textContent]);
    expect(marked).toEqual([['0', 'delivery'], ['3', 'delivery']]);
  });

  it('rendered Markdown: no marker text, the blocks keep their source lines, found words stay marked', async () => {
    serve(SCAN);
    const el = await open('/docs/scanned-delivery.md', pane => { pane.markQuery = 'delivery'; });
    expect(scrollText(el)).not.toMatch(/^\s*--- OCR page \d+ ---\s*$/m);
    expect(scrollText(el)).not.toContain('OCR page 2');
    const blocks = [...el.shadowRoot!.querySelectorAll('.block')]
      .map(block => [block.getAttribute('data-line-start'), block.getAttribute('data-line-end'), block.textContent!.replace(/\s+/g, ' ').trim()]);
    expect(blocks).toEqual([
      ['0', '0', 'scanned-delivery'],
      ['2', '3', 'HARBOUR INSPECTION Cobalt marmalade delivery record'],
      ['5', '6', 'See --- OCR page 1 --- for the crates. No damage found.'],
    ]);
    expect([...el.shadowRoot!.querySelectorAll('.blocks mark.hl')].map(mark => mark.textContent)).toEqual(['delivery', 'delivery']);
  });

  it('a line with more than the marker stays as it is', async () => {
    serve('Intro\n--- OCR page 3 --- continued\n  --- OCR page 4 ---  \nEnd');
    const el = await open('/docs/scan.pdf');
    expect(lines(el)).toEqual([['0', 'Intro'], ['1', '--- OCR page 3 --- continued'], ['3', 'End']]);
  });
});

describe('Q7 what the pane holds is unchanged', () => {
  const at = (text: string): number => SCAN.indexOf(text);

  it('a citation in a scanned file tints the same line, by the raw offsets', async () => {
    serve(SCAN);
    const el = await open('/docs/scanned-delivery.pdf', pane => {
      pane.citation = { startChar: at('Cobalt'), endChar: at('Cobalt') + 'Cobalt marmalade delivery record'.length, excerpt: 'Cobalt marmalade delivery record', sentenceText: null };
    });
    const tinted = [...el.shadowRoot!.querySelectorAll('pre.source span.hl-weak, pre.source span.hl-strong')]
      .map(span => [span.getAttribute('data-line'), span.textContent!.trim()]);
    expect(tinted).toEqual([['3', 'Cobalt marmalade delivery record']]);
    expect(el.shadowRoot!.querySelector('.anchor-notice')).toBeNull();
  });

  it('an excerpt that spans a marker is still confirmed against the raw text', async () => {
    serve(SCAN);
    const excerpt = 'scanned-delivery\n--- OCR page 1 ---\nHARBOUR INSPECTION';
    const el = await open('/docs/scanned-delivery.pdf', pane => {
      pane.citation = { startChar: 0, endChar: excerpt.length, excerpt, sentenceText: null };
    });
    expect(el.shadowRoot!.querySelector('.anchor-notice')).toBeNull();
    const tinted = [...el.shadowRoot!.querySelectorAll('pre.source span.hl-weak, pre.source span.hl-strong')]
      .map(span => span.getAttribute('data-line'));
    expect(tinted).toEqual(['0', '2']);
  });

  it('an excerpt with the marker removed is not confirmed, as before', async () => {
    serve(SCAN);
    const el = await open('/docs/scanned-delivery.pdf', pane => {
      pane.citation = { startChar: 0, endChar: 40, excerpt: 'scanned-delivery HARBOUR INSPECTION Cobalt', sentenceText: null };
    });
    expect(el.shadowRoot!.querySelector('.anchor-notice')).not.toBeNull();
  });

  it('the developer source view still shows the raw text with its markers', async () => {
    setShowDeveloperTools(true);
    serve(SCAN);
    const el = await open('/docs/scanned-delivery.pdf');
    expect(lines(el).map(([, text]) => text)).toEqual(SCAN.split('\n'));
  });
});
