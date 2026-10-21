// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { __resetConversationListForTest, resumeConversation, setActiveConversation, restoreActiveConversation, deleteConversationWithCascade, getConversationListState, loadConversations } from './conversationListStore.js';

beforeEach(() => {
  __resetConversationListForTest();
  localStorage.clear();
});
afterEach(() => {
  __resetConversationListForTest();
  vi.unstubAllGlobals();
});

it('a list requested before DELETE cannot resurrect a confirmed deletion', async () => {
  let release!: (value: Response) => void;
  vi.stubGlobal('fetch', vi.fn((_url: unknown, init?: RequestInit) => init?.method === 'DELETE'
    ? Promise.resolve(new Response(JSON.stringify({ ok: true })))
    : new Promise<Response>(resolve => { release = resolve; })));
  const pending = loadConversations();
  await deleteConversationWithCascade('conversation');
  release(new Response(JSON.stringify({ sessions: [{ sessionId: 'conversation' }], deletedIds: [] })));
  await pending;
  expect(getConversationListState().conversations).toEqual([]);
});

it('an authoritative list permits an intentionally restored backup identity', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ ok: true }))));
  await deleteConversationWithCascade('conversation');
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
    sessions: [{ sessionId: 'conversation', title: 'Restored', shapeId: 'core.free-chat' }], deletedIds: [],
  }))));
  await loadConversations();
  expect(getConversationListState().conversations.map(c => c.id)).toEqual(['conversation']);
});

it('a history response captured before DELETE cannot reclaim its deleted conversation', async () => {
  let release!: (value: Response) => void;
  vi.stubGlobal('fetch', vi.fn((_url: unknown, init?: RequestInit) => init?.method === 'DELETE'
    ? Promise.resolve(new Response(JSON.stringify({ ok: true })))
    : new Promise<Response>(resolve => { release = resolve; })));
  const pending = resumeConversation('conversation', 'core.free-chat');
  await deleteConversationWithCascade('conversation');
  release(new Response(JSON.stringify({ messages: [{ role: 'user', content: 'private old turn' }] })));
  await pending;
  expect(getConversationListState().activeId).toBeNull();
});

it('a stale navigation cannot claim or restore a confirmed deleted identity', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ ok: true }))));
  await deleteConversationWithCascade('conversation');
  setActiveConversation('conversation');
  expect(getConversationListState().activeId).toBeNull();
  restoreActiveConversation('conversation');
  expect(getConversationListState().activeId).toBeNull();
});
