// @vitest-environment happy-dom
import { expect, it } from 'vitest';
import { render, type TemplateResult } from 'lit';
import './UnifiedChatView.js';
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

it('reloaded Unified Chat keeps the terminal error and its retrieval explanation', () => {
  const view = document.createElement('jf-unified-chat-view') as unknown as {
    affordance: string; unifiedEvents: unknown[]; render(): TemplateResult;
  };
  view.affordance = 'none'; view.unifiedEvents = records;
  const host = document.createElement('div'); render(view.render(), host);
  expect(host.textContent).toContain('No matching documents found in the index');
  expect(host.textContent).toContain('semantic search did not answer in time');
  expect(host.textContent).toContain('search models still loading');
});

it('document fallback still explains why chunk retrieval was unavailable', () => {
  const view = document.createElement('jf-unified-chat-view') as unknown as {
    ragMeta: Record<string, unknown>; renderPreamble(): TemplateResult;
  };
  view.ragMeta = { retrieval_mode: 'FULLTEXT_FALLBACK' };
  const host = document.createElement('div'); render(view.renderPreamble(), host);
  expect(host.textContent).toContain('(document too small for chunking)');
});

it('reloaded Unified Chat shows a turn the reader stopped as stopped, with its note, not as an answer', () => {
  // The rows the Engine saves for the reader's Stop in Search v3 (S2.3, N2.3): the answer holds what
  // had streamed (nothing, for an early Stop), with the CANCELLED disposition and the rewrite note.
  const at = (ms: number): string => new Date(ms).toISOString();
  const view = document.createElement('jf-unified-chat-view') as unknown as {
    affordance: string; unifiedEvents: unknown[]; render(): TemplateResult;
  };
  view.affordance = 'none';
  view.unifiedEvents = [
    { id: 'q1', occurredAt: at(1000), kind: 'USER_MESSAGE', originator: 'user',
      content: 'and who owns it?', attributes: { shapeId: 'core.rag-ask' } },
    { id: 'a1', occurredAt: at(2000), kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: '',
      attributes: { shapeId: 'core.rag-ask', disposition: 'CANCELLED',
        standaloneQuestion: 'Who owns Project Cobalt?' } },
    { id: 'q2', occurredAt: at(3000), kind: 'USER_MESSAGE', originator: 'user',
      content: 'and when does it ship?', attributes: { shapeId: 'core.rag-ask' } },
    { id: 'a2', occurredAt: at(4000), kind: 'ASSISTANT_MESSAGE', originator: 'agent',
      content: 'It ships on', attributes: { shapeId: 'core.rag-ask', disposition: 'CANCELLED' } },
    { id: 'q3', occurredAt: at(5000), kind: 'USER_MESSAGE', originator: 'user',
      content: 'and who built it?', attributes: { shapeId: 'core.rag-ask' } },
    { id: 'a3', occurredAt: at(6000), kind: 'ASSISTANT_MESSAGE', originator: 'agent',
      content: 'Ada built it.', attributes: { shapeId: 'core.rag-ask' } },
  ];
  const host = document.createElement('div'); render(view.render(), host);
  const answers = [...host.querySelectorAll<HTMLElement>('.message.assistant')];
  expect(answers.map((a) => a.dataset.itemId)).toEqual(['a1', 'a2', 'a3']);
  const stopped = (a: HTMLElement | undefined): string | null | undefined =>
    a?.querySelector('[data-testid="chat-turn-stopped"]')?.textContent?.trim();
  // An early Stop: the note it showed live and the Stop, and no empty answer standing in for one.
  expect(answers[0]?.querySelector('.rewrite-note')?.textContent).toContain('Who owns Project Cobalt?');
  expect(stopped(answers[0])).toBe('Stopped by you.');
  expect(answers[0]?.querySelector('jf-markdown-block')).toBeNull();
  // A late Stop keeps what had streamed, said to be stopped.
  expect(stopped(answers[1])).toBe('Stopped by you.');
  expect((answers[1]?.querySelector('jf-markdown-block') as { text?: string } | null)?.text).toBe('It ships on');
  // A finished answer says nothing of the kind.
  expect(stopped(answers[2])).toBeUndefined();
  expect((answers[2]?.querySelector('jf-markdown-block') as { text?: string } | null)?.text).toBe('Ada built it.');
});

it('the old window reads a no-matching-files outcome and its model answer together (NE10, HN8)', () => {
  const view = document.createElement('jf-unified-chat-view') as unknown as {
    affordance: string; unifiedEvents: unknown[]; render(): TemplateResult;
  };
  view.affordance = 'none';
  view.unifiedEvents = [
    ...records.map(event => ({ ...event, attributes: { ...event.attributes, retrievalScoped: false } })),
    { id: 'model-question', occurredAt: new Date(3000).toISOString(), kind: 'USER_MESSAGE',
      originator: 'user', content: 'What do the documents say?', attributes: { shapeId: 'core.free-chat' } },
    { id: 'model-answer', occurredAt: new Date(4000).toISOString(), kind: 'ASSISTANT_MESSAGE',
      originator: 'agent', content: 'An answer from the model.', attributes: { shapeId: 'core.free-chat' } },
  ];
  const host = document.createElement('div'); render(view.render(), host);
  expect(host.textContent).toContain('No matching documents found in the index');
  const answers = [...host.querySelectorAll('jf-markdown-block')];
  expect(answers).toHaveLength(1);
  expect((answers[0] as unknown as { text: string }).text).toBe('An answer from the model.');
  expect(answers[0]?.getAttribute('frame')).toBe('ungrounded');
  expect(host.textContent).toContain('Model answer');
});
