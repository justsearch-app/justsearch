// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('../../utils/tauriRuntime', () => ({ isTauriRuntime: () => true }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));

afterEach(() => { vi.unstubAllGlobals(); vi.resetModules(); mocks.invoke.mockReset(); });

describe('default EnvelopeStream authenticated transport', () => {
  it('sends a header, refreshes a rejected token, and parses chunked SSE frames', async () => {
    vi.stubGlobal('EventSource', class { addEventListener() {} removeEventListener() {} close() {} });
    mocks.invoke.mockResolvedValueOnce('old-token').mockResolvedValueOnce('new-token');
    const encoder = new TextEncoder();
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const body = new ReadableStream<Uint8Array>({ start(value) { controller = value; } });
    const send = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ errorCode: 'UI_TOKEN_REQUIRED' }), { status: 401 }))
      .mockResolvedValueOnce(new Response(body, { headers: { 'Content-Type': 'text/event-stream' } }));
    vi.stubGlobal('fetch', send);
    const { EnvelopeStream } = await import('./EnvelopeStream.js');
    const stream = new EnvelopeStream({ url: 'http://127.0.0.1:33221/api/test/stream', initialState: 0,
      reducer: (_state, envelope) => envelope.seq, watchdogStaleMs: 0 });
    try {
      stream.start();
      await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(2));
      expect(new Headers(send.mock.calls[0]?.[1]?.headers).get('X-JustSearch-Session')).toBe('old-token');
      expect(new Headers(send.mock.calls[1]?.[1]?.headers).get('X-JustSearch-Session')).toBe('new-token');
      expect(String(send.mock.calls[1]?.[0])).not.toContain('token');
      const frame = JSON.stringify({ streamId: 'test', frameKind: 'UPDATE', seq: 7, resumeToken: 'r7', payload: {} });
      controller.enqueue(encoder.encode(': keepalive\r\nevent: fra'));
      controller.enqueue(encoder.encode('me\r\ndata: ' + frame + '\r'));
      controller.enqueue(encoder.encode('\n\r\n'));
      await vi.waitFor(() => expect(stream.getSnapshot().seq).toBe(7));
      expect(stream.getSnapshot().isConnected).toBe(true);
    } finally { stream.stop(); }
    expect(send.mock.calls[1]?.[1]?.signal?.aborted).toBe(true);
  });
});


it('reconnects after EOF with the resume checkpoint and a newly resolved boot token', async () => {
  vi.stubGlobal('EventSource', class { addEventListener() {} removeEventListener() {} close() {} });
  mocks.invoke.mockResolvedValueOnce('boot-one').mockResolvedValueOnce('boot-two');
  const frame = (seq: number) => new TextEncoder().encode('event: frame\ndata: ' + JSON.stringify({
    streamId: 'test', frameKind: 'UPDATE', seq, resumeToken: 'r' + seq, payload: {},
  }) + '\n\n');
  const first = new ReadableStream<Uint8Array>({ start(c) { c.enqueue(frame(1)); c.close(); } });
  let next!: ReadableStreamDefaultController<Uint8Array>;
  const second = new ReadableStream<Uint8Array>({ start(c) { next = c; } });
  const send = vi.fn().mockResolvedValueOnce(new Response(first, { headers: { 'Content-Type': 'text/event-stream' } }))
    .mockResolvedValueOnce(Response.json({ errorCode: 'UI_TOKEN_REQUIRED' }, { status: 401 }))
    .mockResolvedValueOnce(new Response(second, { headers: { 'Content-Type': 'text/event-stream' } }));
  vi.stubGlobal('fetch', send);
  const { EnvelopeStream } = await import('./EnvelopeStream.js');
  const stream = new EnvelopeStream({ url: 'http://127.0.0.1:33221/api/test/stream', initialState: 0,
    reducer: (_state, envelope) => envelope.seq, watchdogStaleMs: 0, reconnectBaseMs: 1, reconnectCapMs: 1 });
  try {
    stream.start();
    await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(3));
    expect(String(send.mock.calls[2]?.[0])).toContain('since=r1');
    expect(new Headers(send.mock.calls[2]?.[1]?.headers).get('X-JustSearch-Session')).toBe('boot-two');
    next.enqueue(frame(2));
    await vi.waitFor(() => expect(stream.getSnapshot().seq).toBe(2));
  } finally { stream.stop(); }
});

it('retries a cold-start null token on the next connection instead of caching failure', async () => {
  vi.stubGlobal('EventSource', class { addEventListener() {} removeEventListener() {} close() {} });
  mocks.invoke.mockResolvedValueOnce(null).mockResolvedValueOnce(null).mockResolvedValue('published-late');
  const body = new ReadableStream<Uint8Array>({ start() {} });
  const send = vi.fn()
    .mockResolvedValueOnce(Response.json({ errorCode: 'UI_TOKEN_REQUIRED' }, { status: 401 }))
    .mockResolvedValueOnce(Response.json({ errorCode: 'UI_TOKEN_REQUIRED' }, { status: 401 }))
    .mockResolvedValueOnce(new Response(body, { headers: { 'Content-Type': 'text/event-stream' } }));
  vi.stubGlobal('fetch', send);
  const { EnvelopeStream } = await import('./EnvelopeStream.js');
  const stream = new EnvelopeStream({ url: 'http://127.0.0.1:33221/api/test/stream', initialState: 0,
    reducer: value => value, watchdogStaleMs: 0, reconnectBaseMs: 1, reconnectCapMs: 1 });
  try {
    stream.start();
    await vi.waitFor(() => expect(stream.getSnapshot().isConnected).toBe(true));
    expect(new Headers(send.mock.calls[2]?.[1]?.headers).get('X-JustSearch-Session')).toBe('published-late');
  } finally { stream.stop(); }
});
