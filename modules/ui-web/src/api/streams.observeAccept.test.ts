import { afterEach, expect, it, vi } from 'vitest';
import { consumeShapeStream } from './streams.js';

vi.mock('../utils/tauriRuntime.js', () => ({ isTauriRuntime: () => false }));
afterEach(() => vi.unstubAllGlobals());

it('DS5 DS7 a reattaching observer negotiates the managed event stream', async () => {
  // The managed Engine writer negotiates SSE. Without Accept it returns 200 with an empty
  // text/plain body, observed at the real /runs/{id}/observe interface; the run stays live.
  vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if (new Headers(init?.headers).get('accept') !== 'text/event-stream') {
      return new Response('', { headers: { 'content-type': 'text/plain' } });
    }
    return new Response(
      'event: run_started\ndata: {"runId":"run-1","shapeId":"core.agent-run","conversationId":"conversation-1"}\n\n'
      + 'event: done\ndata: {}\n\n',
      { headers: { 'content-type': 'text/event-stream' } },
    );
  }));
  const events: string[] = [];
  let failure: string | undefined;
  try {
    await consumeShapeStream('/api/chat/runs/run-1/observe', null, (event) => events.push(event));
  } catch (error) {
    failure = String(error);
  }
  expect(failure).toBeUndefined();
  expect(events).toEqual(['run_started', 'done']);
});
