// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AgentSessionController } from './AgentSessionController.js';
import { __resetConversationListForTest, getConversationListState, loadConversations, setActiveConversation } from '../state/conversationListStore.js';

let controller: AgentSessionController;
beforeEach(() => {
  localStorage.clear(); __resetConversationListForTest();
  controller = new AgentSessionController('', () => {});
  vi.stubGlobal('fetch', vi.fn(async (url: unknown) => new Response(JSON.stringify(
    String(url).includes('/history?existsOnly=true') ? { exists: false } : { sessions: [], deletedIds: [] },
  ))));
});
afterEach(() => { controller.destroy(); __resetConversationListForTest(); vi.unstubAllGlobals(); });

it('a run identity alone does not assert that a local first write has landed', async () => {
  controller.conversationId = 'local-only';
  setActiveConversation('local-only');
  controller.onRunStarted({ runId: 'server-run', conversationId: 'local-only' });
  await loadConversations();
  expect(getConversationListState().activeId).toBe('local-only');
  expect(getConversationListState().deletedIds).toEqual([]);
});

it('legacy agent stream content confirms a server conversation before any record read', async () => {
  controller.conversationId = 'legacy-conversation';
  setActiveConversation('legacy-conversation');
  controller.onChunk({ text: 'private agent answer' });
  await loadConversations();
  expect(getConversationListState().activeId).toBeNull();
  expect(getConversationListState().deletedIds).toContain('legacy-conversation');
});

it('a one-shot agent answer confirms server content without any chunk frames', async () => {
  controller.conversationId = 'one-shot';
  setActiveConversation('one-shot');
  controller.onDone({ finalResponse: 'private final answer', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 });
  await loadConversations();
  expect(getConversationListState().activeId).toBeNull();
  expect(getConversationListState().deletedIds).toContain('one-shot');
});

it.each([true, false])('a server replay read confirms content without a conversation list row (pinned=%s)', async (pinned) => {
  if (pinned) controller.conversationId = 'server-replay';
  setActiveConversation('server-replay');
  const fetchBefore = globalThis.fetch;
  vi.stubGlobal('fetch', vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
    if (String(url).endsWith('/events')) return new Response(JSON.stringify({ events: [
      { eventType: 'run_started', payload: { runId: 'server-run', conversationId: 'server-replay' } },
      { eventType: 'chunk', payload: { text: 'private recorded answer' } },
    ] }));
    return fetchBefore(url, init);
  }));
  await controller.loadReplay('server-run');
  await loadConversations();
  expect(getConversationListState().activeId).toBeNull();
  expect(getConversationListState().deletedIds).toContain('server-replay');
});

it('an exported replay alone never asserts that a local conversation exists on the Engine', async () => {
  controller.conversationId = 'local-only';
  setActiveConversation('local-only');
  expect(controller.loadReplayFromExport({ meta: { sessionId: 'exported-run' }, events: [
    { eventType: 'run_started', payload: { runId: 'exported-run', conversationId: 'local-only' } },
    { eventType: 'chunk', payload: { text: 'private exported answer' } },
  ] })).toBe(true);
  await loadConversations();
  expect(getConversationListState().activeId).toBe('local-only');
  expect(getConversationListState().deletedIds).toEqual([]);
});

it('a legacy run identity alone does not assert that a local first write has landed', async () => {
  controller.conversationId = 'legacy-local';
  setActiveConversation('legacy-local');
  controller.onSessionStarted({ sessionId: 'server-run' });
  await loadConversations();
  expect(getConversationListState().activeId).toBe('legacy-local');
  expect(getConversationListState().deletedIds).toEqual([]);
});
