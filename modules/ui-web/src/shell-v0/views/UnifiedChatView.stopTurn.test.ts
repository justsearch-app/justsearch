// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './UnifiedChatView.js';
import { setAiActivity } from '../state/aiStateStore.js';
import { setPendingForceShape, setPendingSelection } from '../utils/compose.js';

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
  resumeConversation: vi.fn(), branchConversation: vi.fn(async () => 'stopped-branch'),
}));

type TestView = HTMLElement & {
  apiBase: string; inputDraft: string; updateComplete: Promise<unknown>;
  affordance: string; schemaDraft: string; sessionId: string; streamingText: string;
  thread: Array<{ role: string; content: string }>; send(): Promise<void>;
};
const marker = '{"city":"OLD_STOP_UNACCEPTED_FACT"}';
let view: TestView | undefined;
let sending: Promise<void> | undefined;
let request: Record<string, unknown>;
let streamSignal: AbortSignal;
let stops: Array<{ url: string; body: Record<string, unknown> }>;
let stopStatus: number;
let settle: () => void;

beforeEach(() => {
  sessionStorage.clear(); localStorage.clear(); vi.clearAllMocks();
  sending = undefined; stops = []; stopStatus = 200; settle = () => {};
  vi.stubGlobal('fetch', vi.fn(async (url: unknown, options?: RequestInit) => {
    if (String(url).endsWith('/stop')) {
      stops.push({ url: String(url), body: JSON.parse(String(options?.body)) });
      if (stopStatus === 200) settle();
      return new Response('{}', { status: stopStatus });
    }
    if (!String(url).endsWith('/api/chat/dispatch')) throw new TypeError('Test backend unavailable');
    request = JSON.parse(String(options?.body)); streamSignal = options!.signal!;
    let removeAbort = () => {};
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(`event: chunk\ndata: ${JSON.stringify({ text: marker })}\n\n`));
        const abort = () => { removeAbort(); controller.error(new DOMException('Request cancelled', 'AbortError')); };
        streamSignal.addEventListener('abort', abort, { once: true });
        removeAbort = () => streamSignal.removeEventListener('abort', abort);
        let open = true;
        settle = () => {
          if (!open || streamSignal.aborted) return;
          open = false; removeAbort();
          controller.enqueue(new TextEncoder().encode('event: error\ndata: {"errorCode":"CANCELLED","error":"Turn stopped."}\n\n'));
          controller.close();
        };
      },
      cancel() { removeAbort(); },
    });
    return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } });
  }));
});
afterEach(async () => {
  settle(); await sending;
  view?.remove(); view = undefined;
  // Settle renders and lazy children before restoring this test's globals.
  await new Promise(resolve => setTimeout(resolve, 0));
  vi.unstubAllGlobals();
});

async function start(mode = 'extract'): Promise<TestView> {
  document.body.innerHTML = '<jf-shell></jf-shell>';
  view = document.createElement('jf-unified-chat-view') as TestView;
  view.apiBase = 'http://localhost:5173'; document.body.append(view); await view.updateComplete;
  view.affordance = mode === 'summarize' ? 'none' : mode;
  if (mode === 'summarize') {
    setPendingForceShape('core.summarize');
    setPendingSelection({ kind: 'item', itemKind: 'search-hit', itemId: 'city-document' });
  }
  view.schemaDraft = '{"type":"object","required":["city"],"properties":{"city":{"type":"string"}}}';
  view.inputDraft = 'Stop before the model finishes this answer';
  sending = view.send();
  await vi.waitFor(() => expect(view!.streamingText).toContain(marker)); await view.updateComplete;
  return view;
}
function pressStop(v: TestView): void {
  v.shadowRoot!.querySelector('jf-composer')!.dispatchEvent(new CustomEvent('composer-cancel'));
}

it('XT7 old Structured Stop names the dispatched Engine turn and keeps its stream for the cancelled terminal', async () => {
  const v = await start(); pressStop(v);
  await vi.waitFor(() => expect(stops, 'Stop must reach the Engine, not merely abort its response').toHaveLength(1));
  expect(request.turnId).toEqual(expect.any(String));
  expect(stops[0]).toEqual({ url: `${v.apiBase}/api/chat/conversations/${v.sessionId}/stop`, body: { turnId: request.turnId } });
  expect(streamSignal.aborted).toBe(false);
  await sending; await v.updateComplete;
  expect(v.streamingText).toBe(''); expect(v.thread.filter(m => m.role === 'assistant')).toEqual([]);
  expect(v.shadowRoot!.querySelector('.error')?.textContent).toContain('Extraction stopped. No completed result was added to the conversation.');
});

it('XT7 activity cancellation uses the same Structured Stop and coalesces repeated presses', async () => {
  await start();
  const activity = vi.mocked(setAiActivity).mock.calls.find(([value]) => value.state === 'extracting')![0];
  activity.cancel!(); activity.cancel!();
  await vi.waitFor(() => expect(stops, 'activity Stop must reach the Engine too').toHaveLength(1));
  await sending; expect(streamSignal.aborted).toBe(false);
});

it('XT7 a refused Structured Stop leaves the stream running and permits a retry', async () => {
  const v = await start(); stopStatus = 503; pressStop(v);
  await vi.waitFor(() => expect(stops, 'the refused Stop request must actually execute').toHaveLength(1));
  expect(streamSignal.aborted).toBe(false); expect(v.streamingText).toContain(marker);
  await v.updateComplete;
  expect(v.shadowRoot!.querySelector('.error')?.textContent).toContain('Could not stop extraction');
  stopStatus = 200; pressStop(v); await sending;
  expect(stops).toHaveLength(2); expect(stops[1]!.body).toEqual(stops[0]!.body);
});

it.each(['none', 'documents', 'summarize'])('XN1 ordinary %s Stop retains the old local-abort contract', async mode => {
  const v = await start(mode); pressStop(v); await sending; await v.updateComplete;
  expect(stops).toEqual([]); expect(request.turnId).toBeUndefined(); expect(streamSignal.aborted).toBe(true);
  expect(v.streamingText).toContain(marker);
});

it('XT7 leaving the Structured window aborts locally without asking the Engine to stop', async () => {
  const v = await start(); v.remove(); await sending;
  expect(stops).toEqual([]); expect(streamSignal.aborted).toBe(true);
});
