// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import type { ThreadEvent } from '../unifiedThreadProjection.js';
import { resumeConversation } from '../../state/conversationListStore.js';
import { loadConversationExport } from './sv3-export.js';
import { isSv3Recovery } from './sv3-recovery.js';
import { projectSv3RecordTurns } from './sv3-record.js';
import { applySv3Record, SV3_SESSIONS_EMPTY, submitInSession, sessionById } from './sv3-sessions.js';

const event = (id: string, kind: ThreadEvent['kind'], content: string, turnId?: string, extra = {}): ThreadEvent => ({
  id: 'idx-' + id.split('-')[0], kind, content, occurredAt: new Date(1700000000000 + Number(id.split('-')[0]) * 1000).toISOString(),
  originator: 'agent', attributes: { shapeId: 'core.rag-ask', ...(turnId === undefined ? {} : { turnId }), ...extra },
});

it.each([false, true])('interleaved dispatches retain their own answers, errors and reasoning (reversed=%s)', reversed => {
  const outcomes = [event('3-a', 'ASSISTANT_MESSAGE', 'answer A', 'A', { reasoning: [{ text: 'thinking A', durationMs: 12 }], disposition: 'CANCELLED', citations: [{ parentDocId: 'A.md', chunkIndex: 0, excerpt: 'A evidence', startLine: 1, endLine: 2 }] }),
    event('4-b', 'ERROR', 'error B', 'B', { errorCode: 'GENERATION_FAILED' })];
  if (reversed) outcomes.reverse();
  const turns = projectSv3RecordTurns([
    event('1-a', 'USER_MESSAGE', 'identical', 'A'), event('2-b', 'USER_MESSAGE', 'identical', 'B'), ...outcomes.map((e, i) => ({ ...e, occurredAt: new Date(1700000003000 + i * 1000).toISOString() })),
  ]);
  expect(turns.map(t => [t.question, t.answer, t.status])).toEqual([
    ['identical', 'answer A', 'halted'], ['identical', '', 'failed'],
  ]);
  expect(turns[0]?.assistantRecordId).toBe('idx-3');
  expect(turns[0]?.reasoning.map(b => b.text)).toEqual(['thinking A']);
  expect(turns[1]?.detail).toContain('error B');
  expect(turns[1]?.reasoning).toEqual([]);
  expect(turns[0]?.evidence?.sources.map(s => s.parentDocId)).toEqual(['A.md']);
  expect(turns[1]?.evidence).toBeNull();
});

it('an unmatched stamped outcome cannot become the answer to a legacy question', () => {
  const turns = projectSv3RecordTurns([event('1-old', 'USER_MESSAGE', 'legacy'), event('2-new', 'ASSISTANT_MESSAGE', 'somebody else', 'other')]);
  expect(turns[0]?.answer).toBe('');
  expect(turns[0]?.assistantRecordId).toBeNull();
});

it('legacy records retain ordered projection', () => {
  const turns = projectSv3RecordTurns([event('1-old', 'USER_MESSAGE', 'legacy'), event('2-old', 'ASSISTANT_MESSAGE', 'legacy answer')]);
  expect(turns.map(t => [t.question, t.answer, t.status])).toEqual([['legacy', 'legacy answer', 'complete']]);
});

it('the first live merge uses the dispatch identity without borrowing another writer’s row', () => {
  let list = submitInSession(SV3_SESSIONS_EMPTY, 'identical', 1, 'ask', 'conversation', 'core.rag-ask');
  const session = sessionById(list, 'conversation')!;
  const live = { ...session.turns[0]!, dispatchTurnId: 'A' };
  list = { ...list, sessions: list.sessions.map(s => s.id === session.id ? { ...s, turns: [live] } : s) };
  const record = projectSv3RecordTurns([
    event('1-b', 'USER_MESSAGE', 'identical', 'B'), event('2-a', 'USER_MESSAGE', 'identical', 'A'),
    event('3-b', 'ASSISTANT_MESSAGE', 'B answer', 'B'),
  ]);
  const merged = sessionById(applySv3Record(list, session.id, record), session.id)!.turns;
  expect(merged).toHaveLength(2);
  expect(merged.find(t => t.id === live.id)?.status).toBe('streaming');
  expect(merged.find(t => t.id === live.id)?.question).toBe('identical');
  expect(merged.find(t => t.id === live.id)?.recordId).toBe('idx-2');
  expect(merged.find(t => t.recordId === 'idx-1')?.answer).toBe('B answer');
});


afterEach(() => vi.unstubAllGlobals());

it('the history reader retains valid dispatch metadata and drops malformed values', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ messages: [
    { role: 'user', content: 'question', turnId: 'A' },
    { role: 'assistant', content: 'answer', turnId: 'A' },
    ...['', '   ', 42, {}, null, 'x'.repeat(4097)].map(turnId => ({ role: 'user', content: 'legacy', turnId })),
  ] }))));
  const result = await resumeConversation('history', 'core.free-chat', { claim: false });
  expect(result.messages.map(m => m.turnId)).toEqual(['A', 'A', undefined, undefined, undefined, undefined, undefined, undefined]);
});

it('export uses a saved exact outcome over a stale local transport failure without pairing identical prompts', async () => {
  const rows = [event('1-b', 'USER_MESSAGE', 'identical', 'B'), event('2-a', 'USER_MESSAGE', 'identical', 'A'),
    event('3-b', 'ASSISTANT_MESSAGE', 'B answer', 'B'), event('4-a', 'ASSISTANT_MESSAGE', 'A answer', 'A')];
  vi.stubGlobal('fetch', vi.fn(async (url: unknown) => new Response(JSON.stringify(String(url).includes('/api/thread/')
    ? { conversationId: 'conversation', events: rows, lifecycles: [] } : { messages: [] }))));
  const list = submitInSession(SV3_SESSIONS_EMPTY, 'identical', 1, 'ask', 'conversation', 'core.rag-ask');
  const initial = sessionById(list, 'conversation')!;
  const session = { ...initial, turns: [{ ...initial.turns[0]!, dispatchTurnId: 'A', status: 'failed' as const, detail: 'transport lost' }] };
  const result = await loadConversationExport('', session, new AbortController().signal);
  expect(result.turns.map(t => [t.status, t.messages[1]?.content, t.detail])).toEqual([
    ['complete', 'B answer', ''], ['complete', 'A answer', ''],
  ]);
});

const retainedTask = { id: 'task', place: null, target: 'conversation', shape: 'core.rag-ask', raw: 'question',
  schema: null, scope: [], record: null, needsReselection: false, effort: 'standard', delivery: 'unknown' };
it('recovery v3 admits only bounded nonblank dispatch identity while v1 and v2 keep their strict shape', () => {
  expect(isSv3Recovery({ version: 3, task: { ...retainedTask, dispatchTurnId: 'A' }, drafts: [] })).toBe(true);
  for (const version of [undefined, 2, 3]) {
    const legacy = { ...(version === undefined ? {} : { version }), task: retainedTask, drafts: [] };
    expect(isSv3Recovery(legacy)).toBe(true);
    for (const dispatchTurnId of ['', ' ', 17, {}, null, '界'.repeat(1366)]) {
      expect(isSv3Recovery({ ...legacy, task: { ...retainedTask, dispatchTurnId } })).toBe(false);
    }
    if (version !== 3) expect(isSv3Recovery({ ...legacy, task: { ...retainedTask, dispatchTurnId: 'A' } })).toBe(false);
  }
  expect(isSv3Recovery({ version: 3, task: { ...retainedTask, dispatchTurnId: 'A', forceAllowed: true }, drafts: [] })).toBe(false);
});

it('a synthetic incomplete stamped error is never terminal evidence, including when persisted by backup', () => {
  const turns = projectSv3RecordTurns([event('1-q', 'USER_MESSAGE', 'extract', 'A', { shapeId: 'core.extract' }),
    event('2-a', 'ERROR', 'interrupted', 'A', { shapeId: 'core.extract', errorCode: 'INCOMPLETE_TURN', terminalError: true })]);
  expect(turns[0]?.savedOutcome).toBe(false);
});
