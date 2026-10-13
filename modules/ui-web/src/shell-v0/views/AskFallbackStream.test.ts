// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest';
import { render, type TemplateResult } from 'lit';
import './UnifiedChatView.js';
import { setPendingForceShape } from '../utils/compose.js';

vi.mock('../state/aiStateStore.js', () => ({
  startAiStateStore: vi.fn(), stopAiStateStore: vi.fn(), setAiActivity: vi.fn(),
  subscribeAiState: () => () => {},
  getAiState: () => ({ capabilities: { chat: true, rag: true, extract: false, embedding: false },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    snapshotLive: true }),
}));

afterEach(() => { vi.unstubAllGlobals(); document.body.replaceChildren(); });

async function streamedAnswer(terminal: 'done' | 'error'): Promise<HTMLElement> {
  const frames = [
    { event: 'rag.meta', data: { retrieval_mode: 'BM25',
      retrieval_mode_reason: 'SEMANTIC_BUDGET_EXCEEDED', retrieval_mode_cause: 'loading' } },
    ...(terminal === 'done' ? [{ event: 'chunk', data: { text: 'The keyword answer.' } }] : []),
    { event: terminal, data: terminal === 'done' ? {} : {
      error: 'No matching documents found in the index', errorCode: 'NO_CONTENT' } },
  ];
  const wire = frames.map(frame => `event: ${frame.event}\ndata: ${JSON.stringify(frame.data)}\n\n`).join('');
  vi.stubGlobal('fetch', vi.fn(async (_url: unknown, init?: RequestInit) =>
    init?.method === 'POST'
      ? new Response(new ReadableStream({ start(controller) {
          controller.enqueue(new TextEncoder().encode(wire)); controller.close();
        } }), { headers: { 'content-type': 'text/event-stream' } })
      : new Response(JSON.stringify({ messages: [], events: [], lifecycles: [] }),
          { headers: { 'content-type': 'application/json' } })));
  const view = document.createElement('jf-unified-chat-view') as unknown as {
    inputDraft: string; affordance: string; sessionId: string; apiBase: string;
    send(): Promise<void>; render(): TemplateResult;
  };
  view.inputDraft = 'What do the documents say?'; view.affordance = 'none';
  view.sessionId = 'conv-retrieval'; view.apiBase = 'http://localhost.invalid';
  setPendingForceShape('core.rag-ask');
  // The production send path consumes the real SSE parser and event dispatcher.
  await view.send();
  const host = document.createElement('div');
  render(view.render(), host);
  return host;
}

it('Unified Chat keeps the retrieval explanation after the stream completes', async () => {
  const host = await streamedAnswer('done');
  const answer = host.querySelector('jf-markdown-block') as unknown as { text: string };
  expect(answer?.text).toBe('The keyword answer.');
  expect(host.textContent).toContain('semantic search did not answer in time');
  expect(host.textContent).toContain('search models still loading');
});

it('Unified Chat shows the retrieval explanation beside a terminal error', async () => {
  const host = await streamedAnswer('error');
  expect(host.textContent).toContain('No matching documents found in the index');
  expect(host.textContent).toContain('semantic search did not answer in time');
  expect(host.textContent).toContain('search models still loading');
});

it('Unified Chat renders the retrieval explanation from a recorded answer', () => {
  const view = document.createElement('jf-unified-chat-view') as unknown as {
    affordance: string; unifiedEvents: unknown[]; render(): TemplateResult;
  };
  view.affordance = 'none';
  view.unifiedEvents = [{ id: 'record-answer', occurredAt: new Date(2000).toISOString(),
    kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'The recorded answer.',
    attributes: { shapeId: 'core.rag-ask', retrievalMode: 'BM25',
      retrievalModeReason: 'SEMANTIC_BUDGET_EXCEEDED', retrievalModeCause: 'loading' } }];
  const host = document.createElement('div');
  render(view.render(), host);
  expect(host.textContent).toContain('semantic search did not answer in time');
  expect(host.textContent).toContain('search models still loading');
});
