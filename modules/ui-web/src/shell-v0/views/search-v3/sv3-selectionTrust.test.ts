// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it } from 'vitest';
import './Sv3Main.js';
import type { Sv3Main } from './Sv3Main.js';
import type { Sv3Turn } from './sv3-sessions.js';
import { projectSv3RecordTurns } from './sv3-record.js';
import { projectSv3AnswerFrame } from './sv3-honesty.js';
import { loadHighlighter } from '../../components/markdown/markdownHighlight.js';
import type { CitationsPanel } from '../../components/chat/CitationsPanel.js';

const question = 'Explain the ranking in one sentence. Trace: sparse-retrieval #2 12.40 · cross-encoder #1 0.98';
function recorded(text: string, selectionKind?: string): Sv3Turn {
  return projectSv3RecordTurns([
    {id:'user',occurredAt:'2026-10-24T00:00:00Z',kind:'USER_MESSAGE',originator:'user',content:text,
      attributes:{shapeId:'core.summarize', ...(selectionKind ? {selectionKind} : {})}},
    {id:'answer',occurredAt:'2026-10-24T00:00:01Z',kind:'ASSISTANT_MESSAGE',originator:'assistant',content:'The result ranks first.',
      attributes:{shapeId:'core.summarize',citations:[{parentDocId:'/docs/chosen-orchard.md',chunkIndex:-1,startLine:1,endLine:1,startChar:0,endChar:selectionKind==='item'?13:0,excerpt:selectionKind==='item'?'document text':''}]}},
  ])[0]!;
}
afterEach(() => document.body.replaceChildren());
it.each([{label:'inline trace',text:question},{label:'shortened',text:'Briefly explain.'},{label:'different question',text:'What explains the score?'},{label:'empty record question',text:''}])('HO5 selection identity survives edited text and record reload: $label', async ({text}) => {
  await loadHighlighter();
  const turn = recorded(text, 'search-trace');
  expect(projectSv3AnswerFrame(turn,null,false)?.verdict).toBe('Explains the ranking of chosen-orchard.md from its ranking trace');
  const main=document.createElement('jf-sv3-main') as Sv3Main;main.turns=[turn];document.body.append(main);await main.updateComplete;
  expect(main.renderRoot.querySelector('.ask-scope')?.textContent).toBe('Explain. After reload, start Explain from the result again to retry or edit.');
  main.renderRoot.querySelector<HTMLButtonElement>('[data-testid="sv3-turn-sources"]')!.click();await main.updateComplete;
  const panel=main.renderRoot.querySelector<CitationsPanel>('jf-citations-panel')!;await panel.updateComplete;
  expect(panel.renderRoot.textContent).toContain('Result being explained');expect(panel.renderRoot.querySelector('.preview')).toBeNull();
  await new Promise<void>(resolve=>requestAnimationFrame(()=>resolve()));
});
it('HO5 a document summary cannot acquire Explain framing by editing its question', () => {
  const turn=recorded('Summarize.\n\nRanking trace:\nquoted words in this document', 'item');
  expect(projectSv3AnswerFrame(turn,null,false)?.verdict).not.toContain('Explains the ranking');
});

it('HO5 legacy trace subject remains Explain without consulting editable words', () => {
  expect(projectSv3AnswerFrame(recorded('A differently worded question.'), null, false)?.verdict).toBe('Explains the ranking of chosen-orchard.md from its ranking trace');
});
it('HO5 a trace with no document identity remains trace-only after reload', () => {
  const turn=recorded('Explain briefly.', 'search-trace');
  const withoutSubject={...turn,evidence:{...turn.evidence!,sources:[]}};
  expect(projectSv3AnswerFrame(withoutSubject,null,false)?.verdict).toBe('Explains the ranking trace');
});
it('HO5 untyped legacy traces without a subject never acquire a document-search claim', () => {
  const turn=recorded('Explain this result.\n\nRanking trace:\nsparse #2 12.40');
  const withoutSubject={...turn,evidence:{...turn.evidence!,sources:[]}};
  const frame=projectSv3AnswerFrame(withoutSubject,null,false);
  expect(frame?.verdict ?? '').not.toMatch(/documents|files|searched/i);
});
