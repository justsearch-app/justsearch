// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './UnifiedChatView.js';
import { consumeShapeStream, dispatchShapeEventToHandlers } from '../../api/streams.js';
import { __resetConversationListForTest, loadConversations } from '../state/conversationListStore.js';
import { readLastViewedConversation } from '../controllers/lastViewedConversation.js';

const ready = {
  capabilities: { chat: true, rag: true, extract: true, embedding: false },
  activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
  snapshotLive: true,
};
vi.mock('../state/aiStateStore.js', () => ({
  startAiStateStore: vi.fn(), stopAiStateStore: vi.fn(),
  subscribeAiState: (listener: (value: unknown) => void) => { listener(ready); return () => {}; },
  setAiActivity: vi.fn(), getAiState: () => ready,
}));
vi.mock('../../api/streams.js', () => ({
  consumeShapeStream: vi.fn(), dispatchShapeEventToHandlers: vi.fn(), RUN_STARTED_EVENT: 'run_started',
}));
interface Chat extends HTMLElement {
  sessionId: string; inputDraft: string; thread: unknown[]; affordance: string; streamingText: string;
  updateComplete: Promise<unknown>; send(): Promise<void>;
}
let el: Chat;
let finish: () => void;
let absent = false;
beforeEach(async () => {
  localStorage.clear(); sessionStorage.clear(); __resetConversationListForTest();
  vi.clearAllMocks(); absent = false; finish = () => {};
  vi.mocked(consumeShapeStream).mockImplementation(() => new Promise<void>(resolve => { finish = resolve; }));
  vi.stubGlobal('fetch', vi.fn(async (url: unknown) => new Response(JSON.stringify(
    String(url).includes('/history?existsOnly=true') ? { exists: !absent }
      : { sessions: [], deletedIds: [], messages: [], events: [], lifecycles: [] },
  ))));
  el = document.createElement('jf-unified-chat-view') as Chat;
  document.body.appendChild(el);
  await el.updateComplete;
  await loadConversations();
  el.sessionId = 'stream-only'; el.affordance = 'chat'; el.thread = [];
});
afterEach(() => {
  finish(); el.remove(); __resetConversationListForTest(); vi.unstubAllGlobals();
});

it.each(['chunk', 'done'] as const)('server %s content counts before any list or history read returns it', async (mode) => {
  el.inputDraft = 'private question';
  const sending = el.send();
  try {
    await vi.waitFor(() => expect(consumeShapeStream).toHaveBeenCalled());
    // Sending alone is still local: no server row or content has yet confirmed the first write.
    absent = true;
    await loadConversations();
    expect(el.sessionId).toBe('stream-only');
    absent = false;
    const onEvent = vi.mocked(consumeShapeStream).mock.calls.at(-1)![2];
    onEvent('probe', {});
    const handlers = vi.mocked(dispatchShapeEventToHandlers).mock.calls.at(-1)![0] as Record<string, (payload: unknown) => void>;
    if (mode === 'chunk') {
      handlers.onChunk?.({ text: 'private streamed answer' });
      expect(el.streamingText).toBe('private streamed answer');
    } else {
      handlers.onDone?.({ finalResponse: 'private streamed answer' });
      finish(); await sending;
      expect(el.thread).toHaveLength(2);
    }
    absent = true; // Delete plus Engine restart happened before any record/list confirmed the id.
    await loadConversations();
    await el.updateComplete;
    expect(el.thread).toEqual([]);
    expect(el.streamingText).toBe('');
    expect(el.sessionId).not.toBe('stream-only');
    expect(readLastViewedConversation()).toBeNull();
  } finally { finish(); await sending; }
});
