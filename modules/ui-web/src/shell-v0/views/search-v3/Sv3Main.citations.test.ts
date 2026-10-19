// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it } from 'vitest';
import './Sv3Main.js';
import type { Sv3Main } from './Sv3Main.js';
import type { Sv3Turn, Sv3TurnEvidence } from './sv3-sessions.js';
import { SV3_RUN_FEED_EMPTY, type Sv3RunView } from './sv3-run.js';
import type { MarkdownBlock, MarkdownCitation } from '../../components/chat/MarkdownBlock.js';
import { loadHighlighter } from '../../components/markdown/markdownHighlight.js';

const sentence = 'The retrieval pipeline expands the original query.';
const text = `${sentence} [1] Unverified claim [2].\n\n\`\`\`js\nconst value = items[2];\n\`\`\``;
const citation: MarkdownCitation = {
  sentenceText: sentence, sentenceIndex: 0, label: 1, similarity: 0.8,
  detail: { parentDocId: '/docs/query.md', startLine: 2, endLine: 4, startChar: 10, endChar: 40, excerpt: 'query expansion' },
  hover: { excerpt: 'query expansion', title: 'Query', headingText: '' },
};
const evidence: Sv3TurnEvidence = {
  marks: [citation], matches: [], retrievalMode: 'hybrid',
  sources: [
    { ...citation.detail, chunkIndex: 0, headingText: '' },
    { ...citation.detail, parentDocId: '/docs/other.md', chunkIndex: 0, headingText: '' },
  ],
};
const activity: Sv3Turn['activity'] = [
  { kind: 'text', id: 'interim', text: sentence },
  { kind: 'text', id: 'answer', text },
];
const turn = (over: Partial<Sv3Turn> = {}): Sv3Turn => ({
  id: 'owner', recordId: null, assistantRecordId: null, recordOpenedByUser: false,
  kind: 'agent', question: 'Explain retrieval', answer: '', status: 'streaming', evidence,
  detail: '', toolCalls: 0, activity, askedAt: 1, standaloneQuestion: '', reasoning: [],
  durationMs: null, modelLabel: null, disposition: null, ...over,
});
const run: Sv3RunView = {
  turnId: 'owner', phase: 'running', feed: { ...SV3_RUN_FEED_EMPTY, items: activity }, prompts: [],
};
async function mount(turns: Sv3Turn[], live: Sv3RunView | null): Promise<Sv3Main> {
  const el = document.createElement('jf-sv3-main') as Sv3Main;
  el.turns = turns;
  el.run = live;
  document.body.appendChild(el);
  await settle(el);
  return el;
}
async function settle(el: Sv3Main): Promise<void> {
  await el.updateComplete;
  await Promise.all(blocks(el).map((b) => b.updateComplete));
  await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
  await Promise.all(blocks(el).map((b) => b.updateComplete));
}
const blocks = (el: Sv3Main): MarkdownBlock[] => [...el.renderRoot.querySelectorAll<MarkdownBlock>('jf-markdown-block')];
const content = (block: MarkdownBlock): HTMLElement => block.renderRoot.querySelector('.md-content')!;
const marks = (block: MarkdownBlock) => [...content(block).querySelectorAll('.cite-ref')];
// Turn ids are on each block as well as on the enclosing turn; scope by the run row instead.
const answerBlock = (el: Sv3Main): MarkdownBlock => blocks(el).find((b) => b.dataset.turnId === 'owner' && b.closest('[data-item-id]')?.getAttribute('data-item-id') === 'answer')!;
afterEach(() => { document.body.replaceChildren(); });

describe('S4.4 agent citation decoration waits for settlement', () => {
  it.each(['status only', 'status and evidence', 'evidence during streaming'] as const)('%s settles identical text once; a reload has the same marks', async (finish) => {
    await loadHighlighter();
    const el = await mount([turn({ evidence: finish === 'status only' ? evidence : null })], run);
    const block = answerBlock(el);
    expect(marks(block)).toHaveLength(0);
    expect(content(block).textContent).toContain('[1]');
    expect(content(block).textContent).toContain('Unverified claim [2]');
    let streamedActivity = activity;
    // Append real answer deltas with the same closed code fence. Highlighting may happen once,
    // but any existing highlighted code and its token nodes must survive subsequent deltas.
    const deltas = finish === 'status and evidence' ? [] : ['The explanation', ' continues', ' here.'];
    let streamedText = text;
    for (const [index, delta] of deltas.entries()) {
      const highlighted = content(block).querySelector('code:has(.hljs-keyword)');
      const tokens = highlighted ? [...highlighted.childNodes] : [];
      streamedText += index === 0 ? `\n\n${delta}` : delta;
      streamedActivity = [activity[0]!, { kind: 'text', id: 'answer', text: streamedText }];
      el.run = { ...run, feed: { ...run.feed, items: streamedActivity } };
      el.turns = [turn({ activity: streamedActivity })];
      await settle(el);
      expect(content(block).textContent).toContain(streamedText.split('\n\n').at(-1)!);
      expect(marks(block)).toHaveLength(0);
      expect(content(block).textContent).toContain('[1]');
      expect(content(block).textContent).toContain('Unverified claim [2]');
      if (highlighted) {
        expect(content(block).querySelector('code')).toBe(highlighted);
        expect(highlighted.childNodes).toHaveLength(tokens.length);
        tokens.forEach((token, i) => expect(highlighted.childNodes[i]).toBe(token));
      }
    }

    // No text delta accompanies this terminal: status/evidence alone must release the passes.
    el.turns = [turn({ status: 'complete', activity: streamedActivity })];
    await settle(el);
    expect(answerBlock(el)).toBe(block);
    expect(marks(block)).toHaveLength(1);
    expect(content(block).textContent).not.toContain('Unverified claim [2]');
    expect(content(block).querySelector('code')?.textContent).toContain('items[2]');
    expect(content(block).querySelector('code')?.hasAttribute('data-hl')).toBe(true);
    const marker = marks(block)[0];
    block.requestUpdate();
    await block.updateComplete;
    expect(marks(block)[0]).toBe(marker);
    const reloaded = await mount([turn({ status: 'complete', activity: streamedActivity })], null);
    expect(content(answerBlock(reloaded)).innerHTML).toBe(content(block).innerHTML);
    expect(blocks(el).filter((b) => b !== block).flatMap(marks)).toHaveLength(0);
  });

  it('late evidence and its withdrawal update settled text without a new text delta', async () => {
    const el = await mount([turn({ status: 'complete', evidence: null })], null);
    const block = answerBlock(el);
    expect(marks(block)).toHaveLength(0);
    el.turns = [turn({ status: 'complete' })];
    await settle(el);
    expect(marks(block)).toHaveLength(1);
    el.turns = [turn({ status: 'complete', evidence: { ...evidence, marks: [] } })];
    await settle(el);
    expect(marks(block)).toHaveLength(0);
    const fresh = await mount([turn({ status: 'complete', evidence: { ...evidence, marks: [] } })], null);
    expect(content(block).innerHTML).toBe(content(answerBlock(fresh)).innerHTML);
  });

  it.each(['halted', 'failed'] as const)('%s partial text keeps evidence off interim prose and unrelated terminal text', async (status) => {
    // The evidence matches the interim item, so passing it to every item would add a wrong mark.
    const partial: Sv3Turn['activity'] = [
      { kind: 'text', id: 'interim', text: sentence },
      { kind: 'text', id: 'answer', text: 'A different partial response.' },
    ];
    const el = await mount([turn()], run);
    el.run = { ...run, phase: 'ended', feed: { ...run.feed, items: partial } };
    el.turns = [turn({ status, activity: partial })];
    await settle(el);
    expect(blocks(el)).toHaveLength(2);
    expect(blocks(el).flatMap(marks)).toHaveLength(0);
    expect(blocks(el).map((b) => content(b).textContent?.trim())).toEqual([sentence, 'A different partial response.']);
    // Positive control: the retained evidence really decorates its matching text when terminal.
    const control = await mount([turn({ status, activity: [partial[0]!] })], null);
    expect(blocks(control).flatMap(marks)).toHaveLength(1);
  });

  it('another conversation uses its own evidence with a stale live run property', async () => {
    const otherSentence = 'Another conversation has different supporting evidence.';
    const otherActivity: Sv3Turn['activity'] = [
      { kind: 'text', id: 'interim', text: otherSentence },
      { kind: 'text', id: 'answer', text: sentence },
    ];
    const otherEvidence = { ...evidence, marks: [{ ...citation, sentenceText: otherSentence }] };
    const other = turn({ id: 'other', status: 'complete', evidence: otherEvidence, activity: otherActivity });
    const el = await mount([turn({ status: 'complete' }), other], run);
    expect(blocks(el).filter((b) => b.dataset.turnId === 'other').flatMap(marks)).toHaveLength(0);
    // A new conversation has its own text and evidence even if the old run prop still exists.
    // The stale run's evidence matches this conversation's terminal sentence, unlike its own.
    el.turns = [other];
    await settle(el);
    expect(blocks(el).flatMap(marks)).toHaveLength(0);
    expect(blocks(el)).toHaveLength(2);
    expect(blocks(el).map((b) => content(b).textContent?.trim())).toEqual([otherSentence, sentence]);
    const control = await mount([turn({ id: 'other', status: 'complete', evidence: otherEvidence,
      activity: [otherActivity[0]!] })], null);
    expect(blocks(control).flatMap(marks)).toHaveLength(1);
  });
});
