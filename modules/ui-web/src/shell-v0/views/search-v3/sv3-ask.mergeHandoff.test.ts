// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { sv3Ask } from './sv3-ask.js';

const selection = {kind: 'result-set' as const, items: [{id: 'chosen.md', kind: 'doc' as const}]};
const sink = () => ({onDelta: vi.fn(), onEvidence: vi.fn(), onReasoning: vi.fn(), onRewrite: vi.fn(),
  onDone: vi.fn(), onRefused: vi.fn(), onHalted: vi.fn(), onLeft: vi.fn(), onFailed: vi.fn()});
afterEach(() => vi.unstubAllGlobals());
function capture() {
  const fetch = vi.fn<typeof globalThis.fetch>(async () => new Response('event: chunk\ndata: {"text":"answer"}\n\nevent: done\ndata: {"finalResponse":"answer"}\n\n'));
  vi.stubGlobal('fetch', fetch);
  return () => JSON.parse((fetch.mock.calls[0]![1] as RequestInit).body as string) as Record<string, unknown>;
}
it('merge model action overrides forced hand-off shape and omits its inputs', async () => {
  const body = capture();const callbacks = sink();
  await sv3Ask({apiBase: 'http://engine.test', question: 'Answer without files', conversationId: 'thread',
    shapeId: 'core.summarize', selection, schema: '{"type":"object"}', docIds: ['chosen.md'],
    context: [{role: 'user', content: 'private file context'}], modelAnswer: true}, callbacks);
  expect(body().shapeId).toBe('core.free-chat');
  for (const field of ['selection', 'schema', 'docIds', 'context']) expect(body()).not.toHaveProperty(field);
  expect(body()).toMatchObject({prompt: 'Answer without files', sessionId: 'thread', conversationId: 'thread'});
  expect(callbacks.onFailed).not.toHaveBeenCalled();expect(callbacks.onDone).toHaveBeenCalledOnce();
});
it('merge ordinary hand-off keeps its forced shape and exact selection', async () => {
  const body = capture();const callbacks = sink();
  await sv3Ask({apiBase: 'http://engine.test', question: 'Summarize this', conversationId: 'thread',
    shapeId: 'core.summarize', selection}, callbacks);
  expect(body()).toMatchObject({shapeId: 'core.summarize', prompt: 'Summarize this', selection});
  expect(callbacks.onFailed).not.toHaveBeenCalled();expect(callbacks.onDone).toHaveBeenCalledOnce();
});
