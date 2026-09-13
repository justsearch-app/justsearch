import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Mock tauriRuntime + http modules so streamRequest can resolve the session token
vi.mock('../utils/tauriRuntime', () => ({
  isTauriRuntime: vi.fn(() => false),
}));

/** Helper: create a ReadableStream from SSE-formatted string chunks. */
function sseStream(...chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  let i = 0;
  return new ReadableStream({
    pull(controller) {
      if (i < chunks.length) {
        controller.enqueue(encoder.encode(chunks[i++]));
      } else {
        controller.close();
      }
    },
  });
}

/** Helper: mock fetch to return an SSE response with the given body stream. */
function mockFetchSse(body: ReadableStream<Uint8Array>) {
  globalThis.fetch = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    headers: new Headers({ 'content-type': 'text/event-stream' }),
    body,
  } as unknown as Response);
}

describe('streams.ts terminal event handling', () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    vi.resetModules();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.clearAllMocks();
  });

  it('fires onError with STREAM_INCOMPLETE when stream closes without terminal event', async () => {
    // Stream delivers a chunk but no done/error event, then closes
    mockFetchSse(sseStream('event: chunk\ndata: {"text":"hello"}\n\n'));

    const { streamRequest } = await import('./streams');
    const onChunk = vi.fn();
    const onDone = vi.fn();
    const onError = vi.fn();

    await streamRequest('http://localhost/api/test', {}, { onChunk, onDone, onError });

    expect(onChunk).toHaveBeenCalledWith('hello');
    expect(onDone).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledTimes(1);
    expect((onError.mock.calls[0]?.[0] as { code?: string })?.code).toBe('STREAM_INCOMPLETE');
  });

  it('does not fire STREAM_INCOMPLETE when done event is received', async () => {
    mockFetchSse(
      sseStream(
        'event: chunk\ndata: {"text":"hi"}\n\n',
        'event: done\ndata: {"ok":true}\n\n'
      )
    );

    const { streamRequest } = await import('./streams');
    const onDone = vi.fn();
    const onError = vi.fn();

    await streamRequest('http://localhost/api/test', {}, { onDone, onError });

    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });

  it('suppresses second done event (once-guard)', async () => {
    mockFetchSse(
      sseStream(
        'event: done\ndata: {"first":true}\n\n',
        'event: done\ndata: {"second":true}\n\n'
      )
    );

    const { streamRequest } = await import('./streams');
    const onDone = vi.fn();

    await streamRequest('http://localhost/api/test', {}, { onDone });

    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onDone.mock.calls[0]?.[0]).toEqual({ first: true });
  });

  it('dispatches rag.citation_matches after done (non-terminal not gated)', async () => {
    mockFetchSse(
      sseStream(
        'event: done\ndata: {"ok":true}\n\n',
        'event: rag.citation_matches\ndata: {"matches":[]}\n\n'
      )
    );

    const { streamRequest } = await import('./streams');
    const onDone = vi.fn();
    const onCitationMatches = vi.fn();

    await streamRequest(
      'http://localhost/api/test',
      {},
      { onDone, onCitationMatches }
    );

    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onCitationMatches).toHaveBeenCalledTimes(1);
  });

  it('passes finishReason through done payload', async () => {
    mockFetchSse(
      sseStream(
        'event: done\ndata: {"finishReason":"length"}\n\n'
      )
    );

    const { streamRequest } = await import('./streams');
    const onDone = vi.fn();

    await streamRequest('http://localhost/api/test', {}, { onDone });

    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onDone.mock.calls[0]?.[0]).toEqual(
      expect.objectContaining({ finishReason: 'length' })
    );
  });

  it('suppresses error after done (once-guard)', async () => {
    mockFetchSse(
      sseStream(
        'event: done\ndata: {"ok":true}\n\n',
        'event: error\ndata: {"error":"late error"}\n\n'
      )
    );

    const { streamRequest } = await import('./streams');
    const onDone = vi.fn();
    const onError = vi.fn();

    await streamRequest('http://localhost/api/test', {}, { onDone, onError });

    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });

  it('does not fetch when the caller signal is already aborted', async () => {
    globalThis.fetch = vi.fn();
    const controller = new AbortController();
    controller.abort(new Error('caller stopped this stream'));

    const { streamRequest } = await import('./streams');
    const onError = vi.fn();
    await streamRequest('http://localhost/api/test', {}, { onError }, controller.signal);

    expect(globalThis.fetch).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ code: 'CANCELLED' }));
  });

  it('removes the caller abort listener when the stream completes', async () => {
    mockFetchSse(sseStream('event: done\ndata: {}\n\n'));
    const controller = new AbortController();
    const removeEventListener = vi.spyOn(controller.signal, 'removeEventListener');

    const { streamRequest } = await import('./streams');
    await streamRequest('http://localhost/api/test', {}, {}, controller.signal);

    expect(removeEventListener).toHaveBeenCalledWith('abort', expect.any(Function));
  });
});

describe('consumeShapeStream', () => {
  it('resolves normally when a done event is received', async () => {
    const body = sseStream('event: chunk\ndata: {"text":"hi"}\n\nevent: done\ndata: {}\n\n');
    mockFetchSse(body);

    const { consumeShapeStream } = await import('./streams');
    const events: string[] = [];
    await consumeShapeStream('http://localhost/test', {}, (event) => { events.push(event); });
    expect(events).toContain('done');
  });

  it('throws STREAM_INCOMPLETE when stream ends without terminal event', async () => {
    const body = sseStream('event: chunk\ndata: {"text":"hi"}\n\n');
    mockFetchSse(body);

    const { consumeShapeStream } = await import('./streams');
    const events: string[] = [];
    try {
      await consumeShapeStream('http://localhost/test', {}, (event) => { events.push(event); });
      expect.fail('should have thrown');
    } catch (e) {
      expect((e as Error).message).toContain('terminal event');
      expect((e as Error & { code?: string }).code).toBe('STREAM_INCOMPLETE');
    }
  });

  it('throws error event instead of STREAM_INCOMPLETE when error arrives', async () => {
    const body = sseStream('event: error\ndata: {"error":"AI_OFFLINE"}\n\n');
    mockFetchSse(body);

    const { consumeShapeStream } = await import('./streams');
    try {
      await consumeShapeStream('http://localhost/test', {}, () => {});
      expect.fail('should have thrown');
    } catch (e) {
      expect((e as Error).message).toBe('AI_OFFLINE');
      expect((e as Error & { code?: string }).code).not.toBe('STREAM_INCOMPLETE');
    }
  });
});

// Tempdoc 834 §1.6 / §2 — the run-stream half of the reader: the cursor grammar, the
// truncation notice, and the typed 404.
describe('consumeShapeStream — run streams (834 S3b)', () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    vi.resetModules();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('sends the cursor as ?sinceSeq=, never as ?since=', async () => {
    mockFetchSse(sseStream('event: done\ndata: {}\n\n'));

    const { consumeShapeStream } = await import('./streams');
    await consumeShapeStream(
      'http://localhost/api/chat/runs/run-1/observe',
      {},
      () => {},
      undefined,
      { sinceSeq: 42 },
    );

    const calls = (globalThis.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls;
    const url = calls[0]![0];
    expect(url).toBe('http://localhost/api/chat/runs/run-1/observe?sinceSeq=42');
    // ?since= carries a ResumeTokenCodec token on the envelope family; reusing the name for a raw
    // integer would be a silent grammar fork, and the backend refuses it outright.
    expect(String(url)).not.toContain('?since=');
    expect(String(url)).not.toContain('&since=');
  });

  it('omits the cursor entirely when it is absent or zero (replay from 0)', async () => {
    mockFetchSse(sseStream('event: done\ndata: {}\n\n'));

    const { consumeShapeStream } = await import('./streams');
    await consumeShapeStream('http://localhost/api/chat/runs', {}, () => {}, undefined, {
      sinceSeq: 0,
    });

    const calls = (globalThis.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls;
    const url = calls[0]![0];
    expect(url).toBe('http://localhost/api/chat/runs');
  });

  it('delivers replay_truncated to the caller and keeps reading — it is not terminal', async () => {
    mockFetchSse(
      sseStream(
        'event: replay_truncated\ndata: {"sinceSeq":5,"oldestRetainedSeq":40}\n\n',
        'event: chunk\ndata: {"text":"rest of the window"}\n\n',
        'event: done\ndata: {}\n\n',
      ),
    );

    const { consumeShapeStream, REPLAY_TRUNCATED_EVENT } = await import('./streams');
    const seen: Array<[string, unknown]> = [];
    await consumeShapeStream('http://localhost/api/chat/runs/run-1/observe', {}, (event, payload) => {
      seen.push([event, payload]);
    });

    expect(seen.map((e) => e[0])).toEqual([REPLAY_TRUNCATED_EVENT, 'chunk', 'done']);
    expect(seen[0]![1]).toEqual({ sinceSeq: 5, oldestRetainedSeq: 40 });
  });

  it('surfaces the typed 404 body so "this run is over" is distinguishable from an error', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      headers: new Headers({ 'content-type': 'application/json' }),
      body: null,
      text: async () =>
        JSON.stringify({
          runId: 'run-gone',
          reason: 'retired',
          recordHint: '/api/chat/conversations/conv-42',
        }),
    } as unknown as Response);

    const { consumeShapeStream } = await import('./streams');
    try {
      await consumeShapeStream('http://localhost/api/chat/runs/run-gone/observe', {}, () => {});
      expect.fail('should have thrown');
    } catch (e) {
      const err = e as Error & { status?: number; runNotFound?: { reason: string; recordHint: string } };
      expect(err.status).toBe(404);
      expect(err.runNotFound?.reason).toBe('retired');
      expect(err.runNotFound?.recordHint).toBe('/api/chat/conversations/conv-42');
    }
  });

  it('leaves a non-typed 404 as a plain HTTP error', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      headers: new Headers(),
      body: null,
      text: async () => 'Not Found',
    } as unknown as Response);

    const { consumeShapeStream } = await import('./streams');
    try {
      await consumeShapeStream('http://localhost/api/chat/runs/x/observe', {}, () => {});
      expect.fail('should have thrown');
    } catch (e) {
      const err = e as Error & { status?: number; runNotFound?: unknown };
      expect(err.status).toBe(404);
      expect(err.runNotFound).toBeUndefined();
    }
  });
});
