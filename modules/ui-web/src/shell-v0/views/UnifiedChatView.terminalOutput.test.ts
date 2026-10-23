// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './UnifiedChatView.js';
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
  resumeConversation: vi.fn(), branchConversation: vi.fn(async () => 'accepted-branch'),
}));

type Message = { role: string; content: string; shapeId: string; id?: string };
type TestView = HTMLElement & {
  apiBase: string; inputDraft: string; updateComplete: Promise<unknown>;
  affordance: string; schemaDraft: string; thread: Message[]; sessionId: string;
};
const accepted = '{"city":"Paris"}';
let chunks: string[];
let finalResponse: string | undefined;
let terminalError: string | undefined;
let requests: Array<Record<string, unknown>>;
let view: TestView | undefined;

beforeEach(() => {
  sessionStorage.clear(); localStorage.clear(); vi.clearAllMocks();
  chunks = []; finalResponse = accepted; terminalError = undefined; requests = [];
  vi.stubGlobal('fetch', vi.fn(async (url: unknown, options?: RequestInit) => {
    if (!String(url).endsWith('/api/chat/dispatch')) throw new TypeError('Test backend unavailable');
    requests.push(JSON.parse(String(options?.body)) as Record<string, unknown>);
    const events = chunks.map(text => `event: chunk\ndata: ${JSON.stringify({ text })}\n\n`);
    events.push(terminalError
      ? `event: error\ndata: ${JSON.stringify({ error: terminalError })}\n\n`
      : `event: done\ndata: ${JSON.stringify({ finalResponse })}\n\n`);
    return new Response(events.join(''), { headers: { 'Content-Type': 'text/event-stream' } });
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
async function send(v: TestView, instruction: string): Promise<void> {
  v.inputDraft = instruction;
  // Await the real dispatch/stream lifecycle; no outstanding stream promises at teardown.
  await (v as unknown as { send(): Promise<void> }).send();
  await v.updateComplete;
  await new Promise(resolve => setTimeout(resolve, 0));
  await v.updateComplete;
}
function answerText(v: TestView): string {
  return [...v.shadowRoot!.querySelectorAll('jf-markdown-block')]
    .map(block => (block as unknown as { text: string }).text).join('\n');
}

it('XT3 exhausted Structured retries leave only the failure and no rejected answer or context', async () => {
  const v = await mount(); chunks = ['{bad}', '{"city":42}', '{}', '{also bad}'];
  terminalError = 'Extraction failed: schema validation exhausted';
  await send(v, 'all invalid attempts');
  expect(v.shadowRoot!.querySelector('.error')?.textContent).toContain(terminalError);
  expect(answerText(v), 'failed extraction must not leave an ordinary answer bubble').not.toContain('{bad}');
  expect(v.thread.filter(m => m.role === 'assistant')).toEqual([]);
  expect((v as TestView & { streamingText: string }).streamingText).toBe('');
  terminalError = undefined; chunks = [accepted]; v.affordance = 'documents';
  await send(v, 'ask after failure');
  expect(requests[1]!.context).toEqual([{ role: 'user', content: 'all invalid attempts' }]);
});

it.each([
  ['none', 'core.free-chat'],
  ['documents', 'core.rag-ask'],
  ['summarize', 'core.summarize'],
])('XN1 %s keeps streamed text when the terminal answer is empty', async (mode, shape) => {
  const v = await mount(); v.affordance = mode === 'summarize' ? 'none' : mode;
  if (mode === 'summarize') {
    setPendingForceShape('core.summarize');
    setPendingSelection({ kind: 'item', itemKind: 'search-hit', itemId: 'city-document' });
  }
  chunks = ['The ordinary ', 'streamed answer.']; finalResponse = '';
  await send(v, 'an ordinary request');
  expect(requests[0]!.shapeId).toBe(shape);
  expect(answerText(v)).toContain('The ordinary streamed answer.');
  expect(v.thread.filter(m => m.role === 'assistant').map(m => m.content)).toEqual(['The ordinary streamed answer.']);
  expect(v.shadowRoot!.querySelector('.error')).toBeNull();
});
