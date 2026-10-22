// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './UnifiedChatView.js';

const ready = {
  capabilities: { chat: true, rag: true, extract: true, embedding: false },
  activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
  snapshotLive: true,
};
vi.mock('../state/aiStateStore.js', () => ({
  startAiStateStore: vi.fn(), stopAiStateStore: vi.fn(), setAiActivity: vi.fn(),
  subscribeAiState: (listen: (s: unknown) => void) => { listen(ready); return () => {}; },
  getAiState: () => ready,
}));
vi.mock('./unifiedThreadClient.js', () => ({
  fetchUnifiedThread: async () => ({ events: [], lifecycles: [] }),
}));
vi.mock('../state/conversationListStore.js', async importOriginal => ({
  ...await importOriginal<typeof import('../state/conversationListStore.js')>(),
  setConversationApiBase: vi.fn(), getRecentSessions: () => [], recordRecentSession: vi.fn(),
  generateConversationTitle: vi.fn(), fetchMessageIds: async () => null,
  resumeConversation: vi.fn(), branchConversation: vi.fn(async () => 'accepted-branch'),
}));

type Message = { role: string; content: string; shapeId: string; id?: string };
type TestView = HTMLElement & {
  apiBase: string; inputDraft: string; updateComplete: Promise<unknown>;
  affordance: string; schemaDraft: string; thread: Message[]; sessionId: string;
};
const rejected = '{"city":42,"fact":"REJECTED_CRASH_CITY"}';
const partial = '{"city":';
let requests: Array<Record<string, unknown>>;
let view: TestView | undefined;
let interrupt: (failure: 'connection' | 'eof' | 'timeout') => void;

beforeEach(() => {
  sessionStorage.clear(); localStorage.clear(); vi.clearAllMocks(); requests = [];
  vi.stubGlobal('fetch', vi.fn(async (url: unknown, options?: RequestInit) => {
    if (!String(url).endsWith('/api/chat/dispatch')) throw new TypeError('Test backend unavailable');
    requests.push(JSON.parse(String(options?.body)) as Record<string, unknown>);
    let removeAbort = () => {};
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        const events = [rejected, partial].map(text => `event: chunk\ndata: ${JSON.stringify({ text })}\n\n`).join('');
        controller.enqueue(new TextEncoder().encode(events));
        const abort = () => controller.error(new DOMException('Request cancelled', 'AbortError'));
        options?.signal?.addEventListener('abort', abort, { once: true });
        removeAbort = () => options?.signal?.removeEventListener('abort', abort);
        interrupt = failure => {
          removeAbort();
          if (failure === 'eof') controller.close();
          else if (failure === 'timeout') controller.error(new DOMException('Stream timed out', 'TimeoutError'));
          else controller.error(new TypeError('Network connection lost'));
        };
      },
      cancel() { removeAbort(); },
    });
    return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } });
  }));
});
afterEach(async () => {
  view?.remove(); view = undefined;
  // Finish reactive renders/lazy child imports before restoring the test environment.
  await new Promise(resolve => setTimeout(resolve, 0));
  vi.unstubAllGlobals();
});

async function mount(): Promise<TestView> {
  document.body.innerHTML = '<jf-shell></jf-shell>';
  view = document.createElement('jf-unified-chat-view') as TestView;
  view.apiBase = 'http://localhost:5173'; document.body.append(view);
  await view.updateComplete;
  view.affordance = 'extract';
  view.schemaDraft = '{"type":"object","required":["city"],"properties":{"city":{"type":"string"}}}';
  await view.updateComplete;
  return view;
}
async function start(v: TestView): Promise<{ sending: Promise<void> }> {
  v.inputDraft = 'Crash after rejected extraction attempt';
  const sending = (v as unknown as { send(): Promise<void> }).send();
  await vi.waitFor(() => expect((v as TestView & { streamingText: string }).streamingText).toContain(partial));
  await v.updateComplete;
  return { sending };
}
function answerText(v: TestView): string {
  return [...v.shadowRoot!.querySelectorAll('jf-markdown-block')]
    .map(block => (block as unknown as { text: string }).text).join('\n');
}


for (const failure of ['connection', 'eof', 'timeout', 'abort'] as const) {
  it(`XT7 Structured ${failure} leaves only a failure note, never rejected or partial JSON`, async () => {
    const v = await mount();
    const { sending } = await start(v);
    expect(answerText(v)).toContain(rejected);
    if (failure === 'abort') {
      v.shadowRoot!.querySelector('jf-composer')!.dispatchEvent(new CustomEvent('composer-cancel'));
    } else interrupt(failure);
    await sending; await v.updateComplete;
    expect(answerText(v), 'interrupted Structured output must leave no answer bubble').toBe('');
    expect((v as TestView & { streamingText: string }).streamingText).toBe('');
    expect(v.thread.filter(m => m.role === 'assistant')).toEqual([]);
    expect(v.shadowRoot!.querySelector('.error')?.textContent, 'a reason remains visible').toMatch(/lost|interrupt|timed out|stopp|cancel/i);
  });
}

it.each(['none', 'documents'])('XN1 preserves %s partial answer behaviour on connection loss', async mode => {
  const v = await mount(); v.affordance = mode;
  const { sending } = await start(v);
  interrupt('connection'); await sending; await v.updateComplete;
  expect(requests[0]!.shapeId).toBe(mode === 'none' ? 'core.free-chat' : 'core.rag-ask');
  expect(answerText(v)).toContain(rejected + partial);
  expect(v.shadowRoot!.querySelector('.error')?.textContent).toContain('Network connection lost');
});
