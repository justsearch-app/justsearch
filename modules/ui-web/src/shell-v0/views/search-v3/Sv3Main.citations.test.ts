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
}
const blocks = (el: Sv3Main): MarkdownBlock[] => [...el.renderRoot.querySelectorAll<MarkdownBlock>('jf-markdown-block')];
const content = (block: MarkdownBlock): HTMLElement => block.renderRoot.querySelector('.md-content')!;
const marks = (block: MarkdownBlock) => [...content(block).querySelectorAll('.cite-ref')];
// Turn ids are on each block as well as on the enclosing turn; scope by the run row instead.
const answerBlock = (el: Sv3Main): MarkdownBlock => blocks(el).find((b) => b.dataset.turnId === 'owner' && b.closest('[data-item-id]')?.getAttribute('data-item-id') === 'answer')!;
afterEach(() => { document.body.replaceChildren(); });

describe('S4.4 agent citation decoration waits for settlement', () => {
  it.each(['status only', 'status and evidence'] as const)('%s settles identical text once; a reload has the same marks', async (finish) => {
    await loadHighlighter();
    const el = await mount([turn({ evidence: finish === 'status only' ? evidence : null })], run);
    const block = answerBlock(el);
    expect(block.isStreaming).toBe(true);
    expect(marks(block)).toHaveLength(0);
    expect(content(block).textContent).toContain('[1]');
    expect(content(block).textContent).toContain('[2]');
    expect(content(block).querySelector('code')?.hasAttribute('data-hl')).toBe(false);
    // Evidence received while the same text streams must not run the settled passes.
    el.turns = [turn()];
    await settle(el);
    expect(answerBlock(el)).toBe(block);
    expect(marks(block)).toHaveLength(0);
    expect(content(block).textContent).toContain('[2]');
    expect(content(block).querySelector('code')?.hasAttribute('data-hl')).toBe(false);

    // No text delta accompanies this terminal: status/evidence alone must release the passes.
    el.turns = [turn({ status: 'complete' })];
    await settle(el);
    expect(answerBlock(el)).toBe(block);
    expect(block.isStreaming).toBe(false);
    expect(marks(block)).toHaveLength(1);
    expect(content(block).textContent).not.toContain('Unverified claim [2]');
    expect(content(block).querySelector('code')?.textContent).toContain('items[2]');
    expect(content(block).querySelector('code')?.hasAttribute('data-hl')).toBe(true);
    const marker = marks(block)[0];
    block.requestUpdate();
    await block.updateComplete;
    expect(marks(block)[0]).toBe(marker);
    const reloaded = await mount([turn({ status: 'complete' })], null);
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

  it.each(['halted', 'failed'] as const)('%s partial text and another turn cannot inherit the live answer marks', async (status) => {
    const partial = [{ kind: 'text' as const, id: 'partial', text: 'A different partial response.' }];
    const other = turn({ id: 'other', status: 'complete', evidence: null, activity: partial });
    const el = await mount([other, turn()], run);
    expect(blocks(el).flatMap(marks)).toHaveLength(0);
    el.run = { ...run, phase: 'ended', feed: { ...run.feed, items: partial } };
    el.turns = [other, turn({ status, evidence: null, activity: partial })];
    await settle(el);
    expect(blocks(el).flatMap(marks)).toHaveLength(0);
    expect(blocks(el).every((b) => b.text === partial[0]!.text)).toBe(true);
    // A new conversation has its own text and evidence even if the old run prop still exists.
    el.turns = [other];
    await settle(el);
    expect(blocks(el).flatMap(marks)).toHaveLength(0);
    expect(blocks(el)).toHaveLength(1);
  });
});
