// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { __resetConversationListForTest, deleteConversation, deleteConversationWithCascade,
  loadConversations, getRecentSessions, recordRecentSession } from './conversationListStore.js';

const records = new Map<string, { sessionId: string; parentSessionId?: string; busy: boolean; firstUserMessage: string }>();
let markers: string[];
let refusal: string | null;
let lost: boolean;
const titles = () => JSON.parse(localStorage.getItem('jf-conversation-titles') ?? '{}');
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
beforeEach(() => {
  __resetConversationListForTest(); localStorage.clear(); sessionStorage.clear();
  markers = []; refusal = null; lost = false; records.clear();
  for (const id of ['root', 'child', 'unrelated']) records.set(id, {
    sessionId: id, ...(id === 'child' ? { parentSessionId: 'root' } : {}), busy: false, firstUserMessage: id,
  });
  localStorage.setItem('jf-conversation-titles', JSON.stringify({ root: 'Root legacy', child: 'Child legacy', unrelated: 'Keep legacy' }));
  recordRecentSession('root'); recordRecentSession('child'); recordRecentSession('unrelated');
  vi.stubGlobal('fetch', vi.fn(async (input: unknown, init?: RequestInit) => {
    const url = new URL(String(input), 'http://engine.test');
    const id = url.pathname.split('/conversations/')[1]?.split('/')[0] ?? '';
    if (init?.method === 'DELETE') {
      if (id === refusal) return json({ error: 'Still running', errorCode: 'CONVERSATION_BUSY' }, 409);
      records.delete(id);
      if (lost) throw Error('Reply lost');
      return json({ ok: true });
    }
    if (url.searchParams.has('existsOnly')) return json({ exists: records.has(id) });
    if (url.searchParams.has('childrenOf')) {
      const parent = url.searchParams.get('childrenOf');
      return json({ locked: false, childrenOf: parent, sessions: [...records.values()].filter(row => row.sessionId === parent || row.parentSessionId === parent) });
    }
    if (url.pathname.endsWith('/title')) return json({ locked: true }, 423);
    return json({ sessions: [...records.values()], deletedIds: markers });
  }));
});
afterEach(() => { __resetConversationListForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it('S53.6 confirmed single deletion retires only its legacy title and recent pointer', async () => {
  await loadConversations(); expect(await deleteConversation('child')).toBe(true);
  expect(titles()).toEqual({ root: 'Root legacy', unrelated: 'Keep legacy' });
  expect(getRecentSessions().map(row => row.sessionId).sort()).toEqual(['root', 'unrelated']);
});
it('S53.6 partial tree deletion retires the confirmed child title and preserves the surviving parent', async () => {
  refusal = 'root'; await loadConversations();
  const result = await deleteConversationWithCascade('root', async () => true);
  expect(result.deletedIds).toEqual(['child']); expect(result.remainingIds).toEqual(['root']);
  expect(titles()).toEqual({ root: 'Root legacy', unrelated: 'Keep legacy' });
});
it('S53.6 a lost reply reconciled as deleted retires only the confirmed identity title', async () => {
  lost = true; await loadConversations();
  const result = await deleteConversationWithCascade('child', async () => true);
  expect(result.deletedIds).toEqual(['child']);
  expect(titles()).toEqual({ root: 'Root legacy', unrelated: 'Keep legacy' });
});
it('S53.6 another window deletion marker retires its title without disturbing unrelated adoption', async () => {
  await loadConversations(); records.delete('child'); markers = ['child']; await loadConversations();
  expect(titles()).toEqual({ root: 'Root legacy', unrelated: 'Keep legacy' });
  expect(getRecentSessions().map(row => row.sessionId).sort()).toEqual(['root', 'unrelated']);
});
it('S53.6 refusal authorizes no browser title or recent discard', async () => {
  refusal = 'child'; await loadConversations(); expect(await deleteConversation('child')).toBe(false);
  expect(titles()).toEqual({ root: 'Root legacy', child: 'Child legacy', unrelated: 'Keep legacy' });
  expect(getRecentSessions()).toHaveLength(3);
});
