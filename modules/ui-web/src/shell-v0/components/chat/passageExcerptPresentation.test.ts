// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it } from 'vitest';
import { CitationsPanel, type RetrievalCitation, type CitationSelectDetail } from './CitationsPanel.js';
import { CitationHoverCard } from './CitationHoverCard.js';
import { __resetSelectedSource } from '../../state/selectedSource.js';

afterEach(() => {
  document.body.replaceChildren();
  __resetSelectedSource();
});

async function sourceViews(excerpt: string) {
  const source: RetrievalCitation = {
    parentDocId: '/docs/guide.md', chunkIndex: 0, chunkTotal: 1,
    startChar: 0, endChar: excerpt.length, startLine: 0, endLine: 9,
    headingText: 'Guide', headingLevel: 1, score: 0.9, excerpt,
  };
  const panel = new CitationsPanel();
  panel.sources = [source];
  panel.citations = [{ parentDocId: source.parentDocId, sourceIndex: 0, sentenceIndex: 0, sentenceText: 'The guide explains search.', similarity: 0.9 }];
  panel.sourcesExpanded = true;
  const hover = new CitationHoverCard();
  hover.data = { parentDocId: source.parentDocId, excerpt, score: 0.9 };
  hover.visible = true;
  document.body.append(panel, hover);
  await Promise.all([panel.updateComplete, hover.updateComplete]);
  return { source, panel, hover };
}

describe('Search citation excerpt presentation', () => {
  it('B1 hides closed leading front matter in the source card, expanded preview and hover', async () => {
    for (const newline of ['\n', '\r\n']) {
      const excerpt = ['---', 'title: Search', 'type: reference', '---', '', ' \t', '# Search', 'Read your documents.'].join(newline);
      const { panel, hover } = await sourceViews(excerpt);
      const expected = ['# Search', 'Read your documents.'].join(newline);
      expect(panel.shadowRoot!.querySelector('.source .sentence')?.textContent).toBe(expected);
      expect(panel.shadowRoot!.querySelector('.preview > div')?.textContent).toBe(expected);
      expect(hover.shadowRoot!.querySelector('.excerpt')?.textContent).toBe(expected);
    }
  });

  it('B1 handles an empty closed block without removing later separators', async () => {
    const { panel, hover } = await sourceViews('---\n---\n\nBody\n---\nLater');
    expect(panel.shadowRoot!.querySelector('.source .sentence')?.textContent).toBe('Body\n---\nLater');
    expect(hover.shadowRoot!.querySelector('.excerpt')?.textContent).toBe('Body\n---\nLater');
  });

  it('B2 preserves empty, unclosed, non-leading and front-matter-only passages verbatim', async () => {
    for (const excerpt of ['', '---\ntitle: Search', '---\ntitle: Search\n---', '---\ntitle: Search\n---\n \t\n', '\n---\ntitle: Search\n---\nBody', ' ---\ntitle: Search\n---\nBody', 'Text\n---\ntitle: Search\n---\nBody', '----\ntitle: Search\n---\nBody']) {
      const { panel, hover } = await sourceViews(excerpt);
      expect(panel.shadowRoot!.querySelector('.source .sentence')?.textContent ?? '').toBe(excerpt);
      expect(hover.shadowRoot!.querySelector('.excerpt')?.textContent ?? '').toBe(excerpt);
    }
  });

  it('B2 keeps original evidence, verification, citation identity and character anchors', async () => {
    const excerpt = '---\ntitle: Search\n---\n\nThe guide explains search.';
    const { source, panel, hover } = await sourceViews(excerpt);
    let detail: CitationSelectDetail | undefined;
    panel.addEventListener('citation-select', (event) => { detail = (event as CustomEvent<CitationSelectDetail>).detail; });
    (panel.shadowRoot!.querySelector('button.source') as HTMLButtonElement).click();
    expect(detail).toMatchObject({ parentDocId: source.parentDocId, startChar: 0, endChar: excerpt.length, startLine: 0, endLine: 9, excerpt });
    expect(panel.sources[0]).toEqual(source);
    expect(hover.data?.excerpt).toBe(excerpt);
    expect(panel.shadowRoot!.querySelector('.source')?.textContent).toContain('Grounds 1 sentence');
    expect(panel.shadowRoot!.querySelector('[data-cite-key]')?.getAttribute('data-cite-key')).toContain(source.parentDocId);
  });

  it('B1 removes a closed header longer than the card budget before the card shortens it', async () => {
    const header = ['---', 'title: Long header', ...Array.from({ length: 30 }, (_, i) => `tag${i}: value number ${i} for the metadata block`), '---'].join('\n');
    const { panel, hover } = await sourceViews(`${header}\n\nOpen the desktop folder to find the report.`);
    expect(panel.shadowRoot!.querySelector('.source .sentence')?.textContent).toContain('Open the desktop folder');
    expect(panel.shadowRoot!.querySelector('.source .sentence')?.textContent).not.toContain('tag0:');
    expect(panel.shadowRoot!.querySelector('.preview > div')?.textContent).toContain('Open the desktop folder');
    expect(hover.shadowRoot!.querySelector('.excerpt')?.textContent).toContain('Open the desktop folder');
  });

  it('B2 keeps ordinary Markdown between two leading horizontal rules', async () => {
    const excerpt = '---\n# Warning\nNever delete the original invoices before making a backup.\n---\nOpen the desktop folder.';
    const { panel, hover } = await sourceViews(excerpt);
    expect(panel.shadowRoot!.querySelector('.source .sentence')?.textContent).toContain('Never delete the original invoices');
    expect(hover.shadowRoot!.querySelector('.excerpt')?.textContent).toBe(excerpt);
  });
});
