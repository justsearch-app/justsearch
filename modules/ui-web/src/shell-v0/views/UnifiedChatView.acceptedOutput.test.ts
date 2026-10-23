// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './UnifiedChatView.js';
import { resumeConversation, branchConversation } from '../state/conversationListStore.js';

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
let requests: Array<Record<string, unknown>>;
let view: TestView | undefined;

beforeEach(() => {
  sessionStorage.clear(); localStorage.clear(); vi.clearAllMocks();
  chunks = []; finalResponse = accepted; requests = [];
  vi.stubGlobal('fetch', vi.fn(async (url: unknown, options?: RequestInit) => {
    if (!String(url).endsWith('/api/chat/dispatch')) throw new TypeError('Test backend unavailable');
    requests.push(JSON.parse(String(options?.body)) as Record<string, unknown>);
    const events = chunks.map(text => `event: chunk\ndata: ${JSON.stringify({ text })}\n\n`);
    events.push(`event: done\ndata: ${JSON.stringify({ finalResponse })}\n\n`);
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

for (const rejected of ['{"city":42,"fact":"REJECTED_ROME_OLD"}', '{bad}']) {
  it(`XT3 live result excludes rejected attempt ${rejected}`, async () => {
    const v = await mount(); chunks = [rejected, accepted];
    await send(v, 'extract the city');
    expect(answerText(v)).toContain(accepted);
    expect(answerText(v)).not.toContain(rejected);
    expect(v.thread.filter(m => m.role === 'assistant').map(m => m.content)).toEqual([accepted]);
  });

  it.each(['extract', 'documents', 'none'])(`XT10 %s follow-up excludes rejected attempt ${rejected}`, async mode => {
    const v = await mount(); chunks = [rejected, accepted];
    await send(v, 'extract the city');
    v.affordance = mode; chunks = [accepted];
    await send(v, 'use the accepted city above');
    expect(requests[1]!.shapeId).toBe({ extract: 'core.extract', documents: 'core.rag-ask', none: 'core.free-chat' }[mode]);
    expect(requests[1]!.context).toEqual([
      { role: 'user', content: 'extract the city' },
      { role: 'assistant', content: accepted },
    ]);
  });

  it(`XN1 branch uses accepted inherited answer after rejected attempt ${rejected}`, async () => {
    const v = await mount(); chunks = [rejected, accepted];
    await send(v, 'extract the city');
    const parent = v.sessionId;
    vi.mocked(resumeConversation).mockResolvedValueOnce({
      sessionId: 'accepted-branch', shapeId: 'core.extract', parentSessionId: parent, branchPointMessageId: 'answer-id', messages: [
        { id: 'user-id', role: 'user', content: 'extract the city', shapeId: 'core.extract' },
        { id: 'answer-id', role: 'assistant', content: accepted, shapeId: 'core.extract' },
      ],
    });
    await (v as unknown as { branchHere(id: string): Promise<void> }).branchHere('answer-id');
    expect(branchConversation).toHaveBeenCalledWith(parent, 'answer-id', 'extract the city');
    v.affordance = 'extract'; chunks = [accepted];
    await send(v, 'use the inherited city');
    expect(requests[1]!.conversationId).toBe('accepted-branch');
    expect(requests[1]!.context).toEqual([
      { role: 'user', content: 'extract the city' },
      { role: 'assistant', content: accepted },
    ]);
  });
}

it('XN1 keeps chunk output when the terminal has no finalResponse', async () => {
  const v = await mount(); chunks = [accepted]; finalResponse = undefined;
  await send(v, 'extract city');
  expect(answerText(v)).toContain(accepted);
});
it('XT3 an explicitly empty finalResponse never commits rejected chunks', async () => {
  const v = await mount(); chunks = ['{bad}']; finalResponse = '';
  await send(v, 'extract city');
  expect(v.thread.filter(m => m.role === 'assistant')).toEqual([]);
  expect(answerText(v)).not.toContain('{bad}');
  expect(v.shadowRoot!.querySelector('.error')?.textContent).toMatch(/no result/i);
});
