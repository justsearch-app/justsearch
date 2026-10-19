// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it } from 'vitest';
import { type MarkdownBlock, type MarkdownCitation } from './MarkdownBlock.js';
import './MarkdownBlock.js';
import { claimsFromRecord } from './recordEvidence.js';
import { claimsToCitations } from './citationResolve.js';
import { __resetSelectedSource } from '../../state/selectedSource.js';

const sentences = [
  'Alpha retrieval expands the original query',
  'Beta ranking scores the retrieved candidates',
  'Gamma synthesis combines the selected passages',
];
const mark = (sentenceText: string, sentenceIndex = 0): MarkdownCitation => ({
  sentenceText, sentenceIndex, similarity: 0.8, label: sentenceIndex + 1,
  detail: {
    parentDocId: `/docs/source-${sentenceIndex}.md`, startLine: sentenceIndex + 2,
    endLine: sentenceIndex + 3, startChar: 10, endChar: 30, excerpt: 'supporting passage',
  },
  hover: { excerpt: 'supporting passage', title: 'Source', headingText: '' },
});

async function mount(text: string, citations: MarkdownCitation[], format: MarkdownBlock['format'] = 'markdown') {
  const el = document.createElement('jf-markdown-block') as MarkdownBlock;
  el.text = text;
  el.citations = citations;
  el.format = format;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}
const content = (el: MarkdownBlock): HTMLElement => el.renderRoot.querySelector('.md-content')!;
const marks = (root: ParentNode) => [...root.querySelectorAll<HTMLElement>('.cite-ref')];
const visibleProse = (root: HTMLElement) => {
  const copy = root.cloneNode(true) as HTMLElement;
  for (const mark of marks(copy)) mark.remove();
  return copy.textContent;
};
afterEach(() => { document.body.replaceChildren(); __resetSelectedSource(); });

describe('S4.1 list anchoring consumes only the clamped item', () => {
  for (const ordered of [false, true]) {
    for (const loose of [false, true]) {
      for (const punctuation of ['', '.']) {
        const items = sentences.map((s) => s + punctuation);
        const markdown = items.map((s, i) => `${ordered ? `${i + 1}.` : '-'} ${s}`)
          .join(loose ? '\n\n' : '\n');
        // Legacy sentence keys may include subsequent list items. Every item's verification still
        // has its own ordinal and source. Recreate the persisted claimMatches envelope (old source
        // position spelling, no scorer), then use the production reader and resolver on reload.
        const keys = items.map((_, i) => items.slice(i).join('\n'));
        it(`${ordered ? 'ordered' : 'unordered'}, ${loose ? 'loose' : 'tight'}, ${punctuation ? 'punctuated' : 'no punctuation'}, live and reloaded`, async () => {
          const citations = keys.map(mark);
          const sources = citations.map((c) => ({ ...c.detail, headingText: '', chunkIndex: 0 }));
          const record = JSON.parse(JSON.stringify({ matches: keys.map((sentenceText, i) => ({
            sentenceText, sentenceIndex: i, chunkIndex: i, similarity: 0.8,
          })) }));
          const reloaded = claimsToCitations(claimsFromRecord(record), sources);
          for (const input of [citations, reloaded]) {
            const el = await mount(markdown, input);
            const lis = [...content(el).querySelectorAll('li')];
            expect(lis).toHaveLength(3);
            for (const [i, li] of lis.entries()) {
              expect(marks(li), `item ${i + 1} lost its own verified mark`).toHaveLength(1);
              expect(marks(li)[0]?.textContent).toBe(String(i + 1));
              expect(visibleProse(li)?.trim()).toBe(items[i]);
              expect(li.querySelector('.cite-sentence')?.textContent).toBe(items[i]);
              const events: unknown[] = [];
              el.addEventListener('citation-select', (e) => events.push((e as CustomEvent).detail));
              marks(li)[0]!.click();
              expect(events).toEqual([input[i]!.detail]);
            }
          }
        });
      }
    }
  }

  it('nested items retain independent marks and words across parent/child boundaries', async () => {
    const text = `- ${sentences[0]}\n  - ${sentences[1]}\n  - ${sentences[2]}`;
    const el = await mount(text, sentences.map((_, i) => mark(sentences.slice(i).join(' '), i)));
    const lis = [...content(el).querySelectorAll('li')];
    expect(lis.map((li) => marks(li).filter((m) => m.closest('li') === li).map((m) => m.textContent)))
      .toEqual([['1'], ['2'], ['3']]);
    for (const [i, li] of lis.entries()) {
      expect(li.querySelector('.cite-sentence')?.textContent).toBe(sentences[i]);
    }
  });
});

describe('S4.2 inline code ends before the citation mark', () => {
  it.each(['`index.query`', '`items[2]`', '`index.query` then `items[2]`'])('%s stays verbatim and the mark follows the final code element', async (code) => {
    const text = `Read the value from ${code}`;
    const el = await mount(text, [mark(text)]);
    const codes = [...content(el).querySelectorAll('code')];
    const last = codes.at(-1)!;
    expect(marks(last)).toHaveLength(0);
    expect(last.textContent).toBe(code.endsWith('`items[2]`') ? 'items[2]' : 'index.query');
    expect(last.nextSibling).toBe(marks(content(el))[0]);
    const selection = document.getSelection()!;
    const range = document.createRange();
    range.selectNodeContents(last);
    selection.removeAllRanges();
    selection.addRange(range);
    expect(selection.toString()).toBe(last.textContent);
    selection.removeAllRanges();
  });
});

describe('S4.3 hard breaks separate words within the same verified sentence', () => {
  it.each([
    ['two-space hard break', '  \n'],
    ['backslash hard break', '\\\n'],
    ['"<br>"', '<br>'],
  ])('covers both lines for %s', async (_label, br) => {
    const el = await mount(`## Context\n\nThe retrieval pipeline${br}expands the original query.`, [
      mark('The retrieval pipeline expands the original query.'),
    ]);
    expect(marks(content(el))).toHaveLength(1);
    const p = content(el).querySelector('p')!;
    expect(p.querySelectorAll('.cite-sentence')[1]?.nextSibling).toBe(marks(p)[0]);
    expect([...p.querySelectorAll('.cite-sentence')].map((s) => s.textContent).join(' '))
      .toBe('The retrieval pipeline expands the original query.');
    expect(p.querySelectorAll('br')).toHaveLength(1);
  });

  it('still clamps a key spanning separate paragraphs to the first paragraph', async () => {
    const el = await mount(`## Context\n\n${sentences[0]}\n\n${sentences[1]}`, [mark(sentences.slice(0, 2).join(' '))]);
    const ps = content(el).querySelectorAll('p');
    expect(marks(ps[0]!), content(el).innerHTML).toHaveLength(1);
    expect(marks(ps[1]!)).toHaveLength(0);
    expect(visibleProse(ps[1]!)?.trim()).toBe(sentences[1]);
  });
});

describe('S4.5 plain text updates match a fresh block after DOM decoration', () => {
  for (const [label, next] of [
    ['multiline literal text', 'Replacement answer\n  with indentation and **literal** <b>HTML</b>'],
    ['"Short"', 'Short'],
    ['""', ''],
  ] as const) {
    it(`replaces the decorated text with ${label}`, async () => {
      const original = `${sentences[0]} [1]`;
      const citations = [mark(sentences[0]!)];
      const el = await mount(original, citations, 'plain');
      expect(marks(content(el))).toHaveLength(1);
      el.text = next;
      await el.updateComplete;
      const fresh = await mount(next, citations, 'plain');
      expect(content(el).innerHTML).toBe(content(fresh).innerHTML);
      expect(content(el).textContent).toBe(next);
      expect(content(el).querySelector('b')).toBeNull();
      expect(marks(content(el))).toHaveLength(0);
    });
  }

  it.each(['citations', 'frame', 'sourceCount'] as const)('uses current %s when text changes after an evidence rebuild (N4.6)', async (input) => {
    const el = await mount(`${sentences[0]} [1]`, [mark(sentences[0]!)], 'plain');
    if (input === 'citations') el.citations = [];
    if (input === 'frame') el.frame = 'ungrounded';
    if (input === 'sourceCount') el.sourceCount = 2;
    await el.updateComplete;
    el.text = '  New summary\n[2] **literal** <em>plain</em>';
    await el.updateComplete;
    const fresh = await mount(el.text, el.citations, 'plain');
    fresh.frame = el.frame;
    fresh.sourceCount = el.sourceCount;
    await fresh.updateComplete;
    expect(content(el).innerHTML).toBe(content(fresh).innerHTML);
  });

  it('can switch a decorated answer between plain and markdown and then replace its text', async () => {
    const text = `${sentences[0]} **bold** [1]`;
    const el = await mount(text, [mark(sentences[0]!)], 'plain');
    for (const format of ['markdown', 'plain'] as const) {
      el.format = format;
      await el.updateComplete;
      const fresh = await mount(text, el.citations, format);
      expect(content(el).innerHTML).toBe(content(fresh).innerHTML);
    }
    el.text = '  Second summary\n**literal** <b>plain</b>';
    await el.updateComplete;
    expect(content(el).textContent).toBe(el.text);
    expect(content(el).querySelector('.cite-ref, .cite-sentence, b, strong')).toBeNull();
  });
});
