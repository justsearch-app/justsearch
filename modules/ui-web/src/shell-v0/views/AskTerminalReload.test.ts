// @vitest-environment happy-dom
import { expect, it } from 'vitest';
import { render, type TemplateResult } from 'lit';
import './search-v3/Sv3Main.js';
import { projectSv3RecordTurns } from './search-v3/sv3-record.js';
import { applySv3Record, mergeStoreConversations, SV3_SESSIONS_EMPTY } from './search-v3/sv3-sessions.js';

const records = [
  { id: 'question-1', occurredAt: new Date(1000).toISOString(), kind: 'USER_MESSAGE' as const,
    originator: 'user', content: 'What do the documents say?', attributes: { shapeId: 'core.rag-ask' } },
  { id: 'error-1', occurredAt: new Date(2000).toISOString(), kind: 'ERROR' as const,
    originator: 'agent', content: 'No matching documents found in the index',
    attributes: { shapeId: 'core.rag-ask', errorCode: 'NO_CONTENT', retrievalMode: 'BM25',
      retrievalModeReason: 'SEMANTIC_BUDGET_EXCEEDED', retrievalModeCause: 'loading' } },
];

it('reloaded Search Ask shows no-evidence and keeps its retrieval explanation', () => {
  const loaded = mergeStoreConversations(SV3_SESSIONS_EMPTY, [{ id: 'conv-retrieval', title: 'Documents', firstUserMessage: 'What do the documents say?', createdAt: 1000, lastActiveAt: 2000 }]);
  const state = applySv3Record(loaded, 'conv-retrieval', projectSv3RecordTurns(records));
  const turn = state.sessions[0]?.turns[0];
  expect(turn?.question).toBe('What do the documents say?');
  expect(turn?.status).toBe('no-evidence');
  expect(turn?.detail).toBe('');
  expect(turn?.evidence).toMatchObject({ retrievalModeReason: 'SEMANTIC_BUDGET_EXCEEDED', retrievalModeCause: 'loading' });
  const view = document.createElement('jf-sv3-main') as unknown as { turn(turn: unknown): TemplateResult };
  const host = document.createElement('div'); render(view.turn(turn), host);
  expect(host.textContent).toContain('Nothing in your files matched this question.');
  expect(host.textContent).not.toContain('The answer failed');
  expect(host.textContent).not.toContain('(empty response)');
  expect(host.textContent).toContain('semantic search did not answer in time');
  expect(host.textContent).toContain('search models still loading');
});
