// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './UnifiedChatView.js';
import { __resetConversationListForTest, deleteConversationWithCascade, loadConversations } from '../state/conversationListStore.js';
import { readLastViewedConversation, setLastViewedConversation } from '../controllers/lastViewedConversation.js';

interface Chat extends HTMLElement {
  sessionId: string;
  thread: unknown[];
  unifiedEvents: unknown[];
  loadConversation(id: string, shape: string): Promise<void>;
  refreshUnifiedThread(): Promise<void>;
  updateComplete: Promise<unknown>;
}
let deleted = false;
let el: Chat;
beforeEach(async () => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  deleted = false;
  vi.stubGlobal('fetch', vi.fn(async (_url: unknown, init?: RequestInit) => {
    if (init?.method === 'DELETE') return new Response(JSON.stringify({ ok: true }));
    return new Response(JSON.stringify({ sessions: [], events: [], lifecycles: [], deletedIds: deleted ? ['conversation'] : [] }));
  }));
  el = document.createElement('jf-unified-chat-view') as Chat;
  document.body.appendChild(el);
  await el.updateComplete;
  // Settle the mount's asynchronous list read before installing the visible cached conversation.
  await loadConversations();
  el.sessionId = 'conversation';
  el.thread = [{ role: 'user', content: 'private cached query' }];
  el.unifiedEvents = [{ id: 'private-search' }];
  setLastViewedConversation('conversation');
});
afterEach(() => {
  el.remove();
  __resetConversationListForTest();
  vi.unstubAllGlobals();
});

it('clears the old window transcript and pointer after a confirmed local delete', async () => {
  await deleteConversationWithCascade('conversation');
  await el.updateComplete;
  expect(el.thread).toEqual([]);
  expect(el.unifiedEvents).toEqual([]);
  expect(el.sessionId).not.toBe('conversation');
  expect(readLastViewedConversation()).toBeNull();
});

it('clears the old window transcript and pointer when a refreshed list confirms deletion', async () => {
  deleted = true;
  await loadConversations();
  await el.updateComplete;
  expect(el.thread).toEqual([]);
  expect(el.unifiedEvents).toEqual([]);
  expect(el.sessionId).not.toBe('conversation');
  expect(readLastViewedConversation()).toBeNull();
});

it('a history read already in flight cannot restore deleted cached turns', async () => {
  let release!: (response: Response) => void;
  vi.stubGlobal('fetch', vi.fn(async (url: unknown, init?: RequestInit) => {
    if (String(url).endsWith('/history')) return new Promise<Response>(resolve => { release = resolve; });
    return new Response(JSON.stringify(init?.method === 'DELETE' ? { ok: true } : { events: [], lifecycles: [] }));
  }));
  const loading = el.loadConversation('conversation', 'core.free-chat');
  await deleteConversationWithCascade('conversation');
  release(new Response(JSON.stringify({ messages: [{ role: 'user', content: 'captured private turn' }], shapeId: 'core.free-chat' })));
  await loading;
  await el.updateComplete;
  expect(el.thread).toEqual([]);
  expect(readLastViewedConversation()).toBeNull();
});

it('a thread read already in flight cannot restore deleted interaction events', async () => {
  let release!: (response: Response) => void;
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { release = resolve; })));
  const loading = el.refreshUnifiedThread();
  // The DELETE must finish while the captured GET is still held.
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ ok: true }))));
  await deleteConversationWithCascade('conversation');
  release(new Response(JSON.stringify({ conversationId: 'conversation', events: [{
    id: 'old-turn', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE',
    originator: 'user', content: 'captured private turn', attributes: {},
  }], lifecycles: [] })));
  await loading;
  expect(el.unifiedEvents).toEqual([]);
  expect(readLastViewedConversation()).toBeNull();
});
