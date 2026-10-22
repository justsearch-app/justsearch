// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { __resetConversationListForTest, branchConversation, fetchMessageIds, getConversationListState, loadConversations, setActiveConversation, setConversationTitle } from './conversationListStore.js';

beforeEach(() => { localStorage.clear(); __resetConversationListForTest(); });
afterEach(() => { __resetConversationListForTest(); vi.unstubAllGlobals(); });
function serve(reply: unknown, status = 200): void {
  vi.stubGlobal('fetch', vi.fn(async (url: unknown, init?: RequestInit) => new Response(JSON.stringify(
    String(url).includes('/history?existsOnly=true') ? { exists: false }
      : String(url).includes('/conversations?') ? { sessions: [], deletedIds: [] }
        : init?.method === 'POST' || String(url).endsWith('/history') ? reply : {},
  ), { status: String(url).includes('/conversations?') || String(url).includes('/history?existsOnly=true') ? 200 : status })));
}

it('a created branch is server-owned even before its first listed row or message', async () => {
  serve({ sessionId: 'created-branch' });
  expect(await branchConversation('parent', 'message')).toBe('created-branch');
  setActiveConversation('created-branch');
  await loadConversations();
  expect(getConversationListState().activeId).toBeNull();
  expect(getConversationListState().deletedIds).toContain('created-branch');
});

it('a successful title write confirms a server conversation before its first list reply', async () => {
  serve({ ok: true });
  setActiveConversation('title-written');
  expect((await setConversationTitle('title-written', 'Private title')).ok).toBe(true);
  await loadConversations();
  expect(getConversationListState().activeId).toBeNull();
  expect(getConversationListState().deletedIds).toContain('title-written');
});

it('a message-id refresh counts as server content without a preceding list or resume', async () => {
  serve({ messages: [{ role: 'user', content: 'private saved question', id: 'saved-message' }] });
  setActiveConversation('saved-content');
  expect(await fetchMessageIds('saved-content')).toHaveLength(1);
  await loadConversations();
  expect(getConversationListState().activeId).toBeNull();
  expect(getConversationListState().deletedIds).toContain('saved-content');
});

it('an empty history read does not assert that a local first write happened', async () => {
  serve({ messages: [] });
  setActiveConversation('local-only');
  expect(await fetchMessageIds('local-only')).toEqual([]);
  await loadConversations();
  expect(getConversationListState().activeId).toBe('local-only');
  expect(getConversationListState().deletedIds).toEqual([]);
});

it('a title still waiting for the first write never turns a local id into server ownership', async () => {
  serve({ error: 'No such conversation' }, 404);
  setActiveConversation('local-only');
  expect((await setConversationTitle('local-only', 'Private draft title')).status).toBe(404);
  await loadConversations();
  expect(getConversationListState().activeId).toBe('local-only');
  expect(getConversationListState().deletedIds).toEqual([]);
});
