// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { fetchUnifiedThread } from '../views/unifiedThreadClient.js';
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

it('confirms missing held server rows individually after markers are lost', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ sessions: [
    { sessionId: 'gone' }, { sessionId: 'older' }, { sessionId: 'locked' },
  ] }))));
  await loadConversations();
  setActiveConversation('gone');
  const fetcher = vi.fn(async (url: unknown) => {
    const path = String(url);
    if (path.includes('/gone/history?existsOnly=true')) return new Response(JSON.stringify({ exists: false }));
    if (path.includes('/older/history?existsOnly=true')) return new Response(JSON.stringify({ exists: true }));
    if (path.includes('/locked/history?existsOnly=true')) return new Response('{}', { status: 423 });
    return new Response(JSON.stringify({ sessions: [], deletedIds: [] }));
  });
  vi.stubGlobal('fetch', fetcher);
  await loadConversations();
  expect(getConversationListState().deletedIds).toEqual(['gone']);
  expect(getConversationListState().activeId).toBeNull();
  expect(fetcher.mock.calls.map(([url]) => String(url))).toContain('/api/chat/conversations/older/history?existsOnly=true');
});

it('keeps local first-write identities and inconclusive existence answers', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ sessions: [
    { sessionId: 'unanswered' }, { sessionId: 'network' }, { sessionId: 'invalid' },
  ] }))));
  await loadConversations();
  setActiveConversation('local');
  const fetcher = vi.fn(async (url: unknown) => {
    if (String(url).includes('/network/history')) throw new Error('offline');
    if (String(url).includes('/invalid/history')) return new Response(JSON.stringify({ exists: 'false' }));
    if (String(url).includes('/unanswered/history')) return new Response('{}', { status: 503 });
    return new Response(JSON.stringify({ sessions: [], deletedIds: [] }));
  });
  vi.stubGlobal('fetch', fetcher);
  await loadConversations();
  expect(getConversationListState().activeId).toBe('local');
  expect(getConversationListState().deletedIds).toEqual([]);
  expect(fetcher.mock.calls.some(([url]) => String(url).includes('/local/history'))).toBe(false);
});

it('server content without a listed row also qualifies for the missing-id read', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ messages: [{ role: 'user', content: 'server turn' }] }))));
  await resumeConversation('content-only', 'core.free-chat');
  vi.stubGlobal('fetch', vi.fn(async (url: unknown) => new Response(JSON.stringify(
    String(url).includes('/history?existsOnly=true') ? { exists: false } : { sessions: [], deletedIds: [] },
  ))));
  await loadConversations();
  expect(getConversationListState().deletedIds).toContain('content-only');
  expect(getConversationListState().activeId).toBeNull();
});

it('an existence response superseded by a newer restored list cannot delete the restored row', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ sessions: [{ sessionId: 'restored' }] }))));
  await loadConversations();
  let release: (response: Response) => void = () => {};
  let started!: () => void;
  const reading = new Promise<void>(resolve => { started = resolve; });
  vi.stubGlobal('fetch', vi.fn(async (url: unknown) => String(url).includes('/history?existsOnly=true')
    ? new Promise<Response>(resolve => { release = resolve; started(); })
    : new Response(JSON.stringify({ sessions: [], deletedIds: [] }))));
  const pending = loadConversations();
  // Before identity reads were added, the list completion itself reached this safe point.
  await Promise.race([reading, pending]);
  started();
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ sessions: [{ sessionId: 'restored' }], deletedIds: [] }))));
  await loadConversations();
  release(new Response(JSON.stringify({ exists: false })));
  await pending;
  expect(getConversationListState().conversations.map(c => c.id)).toContain('restored');
  expect(getConversationListState().deletedIds).not.toContain('restored');
});

it('server thread content qualifies even when no history row was ever listed', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
    conversationId: 'run-only', lifecycles: [], events: [{ id: 'event', kind: 'USER_MESSAGE',
      occurredAt: '2026-01-01T00:00:01Z', originator: 'user', content: 'server turn', attributes: {} }],
  }))));
  expect((await fetchUnifiedThread('', 'run-only')).events).toHaveLength(1);
  setActiveConversation('run-only');
  vi.stubGlobal('fetch', vi.fn(async (url: unknown) => new Response(JSON.stringify(
    String(url).includes('/history?existsOnly=true') ? { exists: false } : { sessions: [], deletedIds: [] },
  ))));
  await loadConversations();
  expect(getConversationListState().deletedIds).toContain('run-only');
  expect(getConversationListState().activeId).toBeNull();
});

it('one unanswered identity read cannot hold back another confirmed deletion', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ sessions: [
    { sessionId: 'gone' }, { sessionId: 'unanswered' },
  ] }))));
  await loadConversations();
  let release: (response: Response) => void = () => {};
  vi.stubGlobal('fetch', vi.fn(async (url: unknown) => {
    if (String(url).includes('/gone/history')) return new Response(JSON.stringify({ exists: false }));
    if (String(url).includes('/unanswered/history')) return new Promise<Response>(resolve => { release = resolve; });
    return new Response(JSON.stringify({ sessions: [], deletedIds: [] }));
  }));
  const refresh = loadConversations();
  try {
    await vi.waitFor(() => expect(getConversationListState().deletedIds).toContain('gone'));
    expect(getConversationListState().loading).toBe(false);
    expect(getConversationListState().deletedIds).not.toContain('unanswered');
  } finally {
    release(new Response('{}', { status: 503 }));
    await refresh;
  }
});
