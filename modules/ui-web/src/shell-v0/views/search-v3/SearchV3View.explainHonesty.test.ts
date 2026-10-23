// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it } from 'vitest';
import './Sv3Main.js';
import type { Sv3Main } from './Sv3Main.js';
import type { CitationsPanel } from '../../components/chat/CitationsPanel.js';
import { loadHighlighter } from '../../components/markdown/markdownHighlight.js';
import { projectSv3AnswerFrame } from './sv3-honesty.js';
import { sv3CitationHeader } from './sv3-citation-anchor.js';
import type { Sv3Turn } from './sv3-sessions.js';

const explain = (): Sv3Turn => ({
  id: 'explain', recordId: null, assistantRecordId: null, recordOpenedByUser: true,
  kind: 'ask', shapeId: 'core.summarize', status: 'complete',
  question: 'Explain why this result ("chosen-orchard.md") ranked where it did.\n\nRanking trace:\nsparse:4.09 → fusion:1',
  answer: 'The trace reports sparse retrieval followed by fusion.', evidence: {
    sources: [{parentDocId: '/docs/chosen-orchard.md', chunkIndex: -1, startLine: 1, endLine: 1,
      startChar: 0, endChar: 0, excerpt: '', headingText: ''}],
    marks: [], matches: [], retrievalMode: 'hybrid',
  },
  detail: '', toolCalls: 0, activity: [], askedAt: 1, standaloneQuestion: '', reasoning: [],
  durationMs: 1200, modelLabel: null, disposition: null,
});
afterEach(() => document.body.replaceChildren());

it('HO5 Explain frame names its ranking trace rather than document content', () => {
  const frame = projectSv3AnswerFrame(explain(), null, false);
  expect(frame?.verdict).toBe('Explains the ranking of chosen-orchard.md from its ranking trace');
  expect(frame?.elaboration).toBe('');
  // The durable pattern also survives editing the instruction and a missing document identity.
  const edited = {...explain(), question: 'Explain in two sentences.\n\nRanking trace:\nsparse:4.09 → fusion:1'};
  expect(projectSv3AnswerFrame(edited, null, false)?.verdict).toBe('Explains the ranking of chosen-orchard.md from its ranking trace');
  const noDocument = {...explain(), evidence: {...explain().evidence!, sources: []}};
  expect(projectSv3AnswerFrame(noDocument, null, false)?.verdict).toBe('Explains the ranking trace');
});

it('HO5 Explain sources name the subject without an empty excerpt', async () => {
  await loadHighlighter();
  const main = document.createElement('jf-sv3-main') as Sv3Main;
  main.turns = [explain()]; document.body.append(main); await main.updateComplete;
  main.renderRoot.querySelector<HTMLButtonElement>('[data-testid="sv3-turn-sources"]')!.click();
  await main.updateComplete;
  const panel = main.renderRoot.querySelector<CitationsPanel>('jf-citations-panel')!;
  await panel.updateComplete;
  const text = panel.renderRoot.textContent!;
  expect(text).toContain('Result being explained');
  expect(text).toContain('chosen-orchard.md');
  expect(text).toContain('Open chosen-orchard.md');
  expect(panel.renderRoot.querySelector('.preview')).toBeNull();
  expect(text).not.toContain('(no excerpt)');
  let opened: unknown;
  main.addEventListener('sv3-citation-open', e => {opened = (e as CustomEvent).detail;});
  panel.renderRoot.querySelector<HTMLButtonElement>('.source')!.click();
  expect(opened).toMatchObject({docPath: '/docs/chosen-orchard.md'});
  // Let the disclosure's deferred layout and Markdown work finish before teardown.
  await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
});

it('HO5 Explain subject pane makes no cited or retrieved claim', () => {
  expect(sv3CitationHeader(explain(), 0, null)).toBeNull();
});

it('HO5 document summaries and ordinary citations keep their existing presentation', async () => {
  await loadHighlighter();
  const summary = {...explain(), question: 'Summarize chosen-orchard.md'};
  expect(projectSv3AnswerFrame(summary, null, false)?.verdict).toBe('Based on your documents');
  expect(sv3CitationHeader(summary, 0, null)?.turnLabel).toBe('Summarize chosen-orchard.md');
  const main = document.createElement('jf-sv3-main') as Sv3Main;
  main.turns = [summary]; document.body.append(main); await main.updateComplete;
  main.renderRoot.querySelector<HTMLButtonElement>('[data-testid="sv3-turn-sources"]')!.click();
  await main.updateComplete;
  const panel = main.renderRoot.querySelector<CitationsPanel>('jf-citations-panel')!;
  await panel.updateComplete;
  expect(panel.renderRoot.querySelector('.preview')).not.toBeNull();
  expect(panel.renderRoot.textContent).not.toContain('Result being explained');
  expect(main.renderRoot.querySelector('.ask-scope')?.textContent).toBe('Summarize. Retry/Edit requires the original files or passage; after reload, choose them again before sending.');
  expect(main.renderRoot.querySelector('[data-testid="sv3-cite-legend"]')).not.toBeNull();
  await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
});

it('HO5 Explain subject names a Windows result without exposing its full path as the name', async () => {
  await loadHighlighter();
  const main = document.createElement('jf-sv3-main') as Sv3Main;
  const turn = explain();
  main.turns = [{...turn, evidence: {...turn.evidence!, sources: [
    {...turn.evidence!.sources[0]!, parentDocId: 'C:\\docs\\chosen-orchard.md'},
  ]}}];
  document.body.append(main); await main.updateComplete;
  main.renderRoot.querySelector<HTMLButtonElement>('[data-testid="sv3-turn-sources"]')!.click();
  await main.updateComplete;
  const panel = main.renderRoot.querySelector<CitationsPanel>('jf-citations-panel')!;
  await panel.updateComplete;
  expect(panel.renderRoot.querySelector('.sentence')?.textContent).toBe('Result being explained: chosen-orchard.md');
  expect(panel.renderRoot.querySelector('.source-open')?.textContent?.trim()).toBe('Open chosen-orchard.md');
  await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
});

it('HO5 Explain subject disclosure omits sentence-grounding instructions', async () => {
  await loadHighlighter();
  const main = document.createElement('jf-sv3-main') as Sv3Main;
  main.turns = [explain()]; document.body.append(main); await main.updateComplete;
  const disclosure = main.renderRoot.querySelector<HTMLButtonElement>('[data-testid="sv3-turn-sources"]')!;
  disclosure.click(); await main.updateComplete;
  const panel = main.renderRoot.querySelector<CitationsPanel>('jf-citations-panel')!;
  await panel.updateComplete;
  expect(main.renderRoot.querySelector('[data-testid="sv3-cite-legend"]')).toBeNull();
  expect(disclosure.getAttribute('aria-controls')).toBe(panel.id);
  await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
});


it('HO5 Explain guidance names Explain and only its observed reload limitation', async () => {
  await loadHighlighter();
  const main = document.createElement('jf-sv3-main') as Sv3Main;
  main.turns = [explain()];
  main.turnLineage = [{turnId: 'explain', branchFromId: 'answer', forkKey: 'prefix',
    forkFromId: 'source', canEdit: true, versions: null}];
  document.body.append(main); await main.updateComplete;
  expect(main.renderRoot.querySelector('.ask-scope')?.textContent).toBe('Explain. After reload, start Explain from the result again to retry or edit.');
  const edit = main.renderRoot.querySelector<HTMLElement & {updateComplete: Promise<unknown>}>('[data-testid="sv3-turn-edit"]')!;
  await edit.updateComplete;
  edit.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click();await main.updateComplete;
  expect(main.renderRoot.querySelector('[data-testid="sv3-turn-edit-scope"]')?.textContent?.trim()).toBe('Repeats Explain with its original ranking trace; after reload, start Explain from the result again.');
  await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
});
